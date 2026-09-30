package com.specflow.tests;

import com.specflow.context.ContextAssembler;
import com.specflow.exception.SpecflowException;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.patch.PatchBlock;
import com.specflow.patch.PatchParser;
import com.specflow.project.ProjectConfig;
import com.specflow.review.PlanReview;
import com.specflow.spec.Spec;
import com.specflow.template.TemplateRegistry;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.VerificationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * 测试 Agent：把「用例清单」变成一份能跑的测试产物，跑一遍，然后如实报出结果。
 *
 * <p>它只走一条链，一步都不多：
 * <pre>
 *   花一次模型调用 → 让模型产出 tools/&lt;时间戳&gt;/ 下的测试代码和一个入口脚本
 *   → 引擎<b>只落盘</b>（自己的白名单 + 高危命令这一道闸）
 *   → 执行入口脚本 → 看退出码 → 读它打印的失败清单
 * </pre>
 *
 * <p><b>它不回喂、不改产品代码、不做决定。</b>测试失败是给人看的证据，不是自动门槛：
 * 谁错了（代码还是用例）机器判不了，硬判就会逼出「为了过一条写错的用例把正确代码改成错的」。
 * 这一批连「下一轮」这个动作都还没有——先把事实摆出来。
 *
 * <p>它和开发 Agent <b>不通信</b>：两边只通过「人在检查阶段确认过的那份用例清单」
 * 耦合（十五.2）。所以这里拿得到的是 spec 与用例，而不是开发 Agent 的内部状态。
 */
public final class TestAgent {

    private static final Logger log = LoggerFactory.getLogger(TestAgent.class);

    private final Path projectRoot;
    private final ProjectConfig project;
    private final TemplateRegistry templates;
    private final LlmClient llm;
    private final ContextAssembler assembler;
    private final PatchParser parser = new PatchParser();

    public TestAgent(Path projectRoot, ProjectConfig project, TemplateRegistry templates,
                     LlmClient llm) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.project = project;
        this.templates = templates;
        this.llm = llm;
        this.assembler = new ContextAssembler(new SafePathResolver(this.projectRoot));
    }

    /**
     * 跑一轮测试阶段。
     *
     * <p>不抛业务异常：生成不出来、脚本跑不起来、断言没过，都是<b>正常的结果</b>，
     * 各自落成 {@link TestOutcome} 里的一档。只有模型调用本身失败会冒泡出去——
     * 那时候整个运行都会停，和开发阶段那次调用失败是同一件事。
     *
     * @param cases 检查阶段定下来的用例清单；为空表示这次不测（调用方不该调到这里来）
     */
    public TestOutcome run(Spec spec, List<PlanReview.TestCase> cases) {
        TestArtifacts artifacts = TestArtifacts.create(projectRoot);
        String response;
        try {
            response = llm.complete(List.of(
                    ChatMessage.system(assembler.systemMessage(spec, templates,
                            TestProtocol.instructions(artifacts.relative(), artifacts.entry()))),
                    ChatMessage.user(assembler.userMessage(spec, templates)
                            + "\n" + TestProtocol.caseList(cases))));
        } catch (RuntimeException e) {
            // 模型调用没回来 = 这一轮一个字节都没生成。刚建的那个空 tools/<时间戳>/ 要收掉：
            // 留着它会攒成一串空目录，看上去像「跑过好几次测试」，而实际什么都没跑
            artifacts.delete();
            throw e;
        }
        try {
            List<PatchBlock> blocks = parser.parse(response);
            List<String> written = artifacts.write(blocks);
            // 没有入口脚本等于没东西可跑。这一条必须机器查：模型漏了它，脚本跑不起来的原因
            // 会表现成「命令不存在」，而那是环境的锅——明明是它没给。
            // 比对按文件系统的规矩来（Windows 上不认大小写）：它把 run.cmd 写成 RUN.CMD 时
            // 落的是同一个文件，字符串比不过就会收到一句和事实不符的「没给入口脚本」
            if (written.stream().noneMatch(artifacts::isEntry)) {
                throw new SpecflowException("产物里没有入口脚本 " + artifacts.entry()
                        + "；引擎只会执行这一个文件");
            }
            log.info("测试产物已写入 {}，共 {} 个文件", artifacts.relative(), written.size());

            TestScriptVerifier.ScriptResult result = new TestScriptVerifier(projectRoot, artifacts.entry())
                    .run(new VerificationContext(projectRoot, spec, project));
            log.info("测试脚本退出码 {}（{}）", result.exit(), artifacts.relative());
            // 两层结论：先按输出定这一档是哪一类失败，再拿检查阶段的清单对账，
            // 把「验了几条」补上——少了后一步，一条都没跑的运行会显示成全绿
            TestOutcome outcome = TestReport.coverage(
                    TestReport.conclude(result, artifacts.relative(), written, 1), cases);
            if (outcome.environmental()) {
                // 环境问题这一次会连同产品改动一起回滚（上层收场时决定），测试产物也就没有
                // 可测的代码了：整批删掉，别让一个指向已回滚代码的脚本留在项目里。
                // 留档里那句结论留着——它答得出「当时想验什么、为什么没跑成」
                log.warn("测试跑不起来（环境问题），产物已清掉：{}", artifacts.relative());
                artifacts.delete();
                return TestReport.cleared(outcome);
            }
            return outcome;
        } catch (SpecflowException e) {
            // 生成阶段就被拒了（没按协议写、路径越界、命中高危命令）：产物一个字节都不留。
            // 产品代码刚才编译通过、改动还在磁盘上，这里只报告「这批测试代码不能用」
            log.warn("测试产物没能落地，已整批清掉：{}", e.getMessage());
            artifacts.delete();
            return TestReport.rejected(1, e.getMessage());
        }
    }
}
