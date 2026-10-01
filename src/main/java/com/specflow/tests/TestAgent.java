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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        return run(spec, cases, TestSettings.UNIT_ONLY, null, ExecutionLocation.host());
    }

    /**
     * 跑一轮测试阶段，这一次带环境。
     *
     * @param settings  跑单元还是单元+集成
     * @param variables 测试环境那组变量（连接信息 + 引擎那三个把手）；没有环境时是 {@code null}
     */
    public TestOutcome run(Spec spec, List<PlanReview.TestCase> cases, TestSettings settings,
                           Map<String, String> variables) {
        return run(spec, cases, settings, variables, ExecutionLocation.host());
    }

    /**
     * 跑一轮测试阶段，位置由调用方给。
     *
     * @param settings  跑单元还是单元+集成
     * @param variables 测试环境那组变量（连接信息 + 引擎那三个把手）；没有环境时是 {@code null}
     * @param location  这次脚本在哪儿跑（容器 / 宿主）。它决定入口脚本叫什么、引擎执行什么命令，
     *                  也决定留档里那句执行位置——三件事必须来自<b>同一个</b>判断
     */
    public TestOutcome run(Spec spec, List<PlanReview.TestCase> cases, TestSettings settings,
                           Map<String, String> variables, ExecutionLocation location) {
        TestArtifacts artifacts = TestArtifacts.create(projectRoot,
                location == null ? ExecutionLocation.host() : location);
        String response;
        try {
            response = ask(spec, cases, artifacts, settings, variables);
        } catch (RuntimeException e) {
            // 模型调用没回来 = 这一轮一个字节都没生成。刚建的那个空 tools/<时间戳>/ 要收掉：
            // 留着它会攒成一串空目录，看上去像「跑过好几次测试」，而实际什么都没跑
            artifacts.delete();
            throw e;
        }
        try {
            List<String> written = write(response, artifacts, settings);
            // 跑哪一个入口，由这次勾没勾集成决定：勾了就是**两个都跑**
            // （两个都在同一个位置跑：有可用环境就是容器里，否则宿主上；见 runScripts）
            TestOutcome outcome = TestReport.coverage(
                    runScripts(spec, artifacts, written, settings, variables), cases);
            log.info("测试脚本跑完：退出码 {}（{}）", outcome.exit(), artifacts.relative());
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

    /**
     * 跑这次该跑的入口脚本，把它们的结果合成一份（十五.4/15.5）。
     *
     * <p><b>勾了集成测试就跑两个</b>：单元那个和集成那个。两个都在<b>同一个位置</b>跑——
     * 有可用环境就是容器里（引擎把命令包成 {@code docker compose exec}），否则都回退宿主。
     * 只跑其中一个的话，另一条路上的用例会被 {@link TestReport#coverage} 对成「没验」——
     * 而清单是一份、分母是整个清单，于是**每次勾集成都会收到一份假的失败清单**。
     *
     * <p>两次执行共用<b>同一份时限</b>：界面上那句「测试进行中（最长 N 分钟）」是按一个
     * 时限说的，各给一份就等于把那句话变成谎话（用户等的是 N 分钟，实际最长 2N）。
     *
     * <p>单元那一个跑不起来（环境问题）就不往下跑了：那是「立刻停」的信号，
     * 再进容器跑一遍只是白等——而且它同样会以环境问题收场。
     */
    private TestOutcome runScripts(Spec spec, TestArtifacts artifacts, List<String> written,
                                   TestSettings settings, Map<String, String> variables) {
        VerificationContext context = new VerificationContext(projectRoot, spec, project);
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(TestScriptVerifier.DEFAULT_TIMEOUT_SECONDS);
        List<String> entries = settings.integration()
                ? List.of(artifacts.entry(), artifacts.integrationEntry())
                : List.of(artifacts.entry());

        List<TestOutcome> outcomes = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            TestScriptVerifier.ScriptResult result = new TestScriptVerifier(projectRoot,
                    entries.get(index), remainingSeconds(deadline), variables, artifacts.location())
                    .run(context);
            outcomes.add(TestReport.conclude(result, artifacts.relative(), written,
                    // 生成测试代码只花了一次模型调用：算在第一个脚本那一笔上
                    index == 0 ? 1 : 0));
            if (result.verification().environmental()) {
                break;
            }
        }
        return TestReport.merge(outcomes);
    }

    /**
     * 从共用时限里还剩多少秒。
     *
     * <p>至少给 1 秒：{@code waitFor(0)} 会让最后一个脚本一启动就被判超时——
     * 那会把「时间用完了」说成「脚本有问题」。
     */
    private static long remainingSeconds(long deadlineNanos) {
        long left = TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime());
        return Math.max(1, left);
    }

    /**
     * <b>只生成、不跑</b>：十五.6 里「测试代码错了」那条路要的东西。
     *
     * <p>它和 {@link #run} 只差最后一步——不执行入口脚本。差这一步正是这条路的意义：
     * 用户认为坏的是测试代码本身，那么把新生成的代码顺手跑一遍，只会再收到一份「失败的证据」，
     * 而他要的是先看一眼这批代码写成什么样（十五.6：停下等你 review，不自动重跑）。
     *
     * <p>它不写产品代码、不碰运行留档、也不进轮次账：这不是一次运行，是一次生成。
     *
     * @return 产物目录、写了哪些文件、以及每个文件的正文
     * @throws SpecflowException 生成阶段被拒（没按协议写、路径越界、命中高危命令）；
     *                           模型调用本身失败也会冒泡出去。两种情况下产物一个字节都不留
     */
    public Generated generate(Spec spec, List<PlanReview.TestCase> cases) {
        return generate(spec, cases, TestSettings.UNIT_ONLY, null, ExecutionLocation.host());
    }

    /**
     * 只生成、不跑，这一次带环境。
     *
     * <p>重新生成也要跟着这次勾没勾集成走：勾了集成却只重新生成了一个单元入口，
     * 「放行」之后跑集成那一步会因为找不到入口而失败——而人要的是「换一版测试代码」，
     * 不是「少一个文件」。
     */
    public Generated generate(Spec spec, List<PlanReview.TestCase> cases, TestSettings settings,
                              Map<String, String> variables) {
        return generate(spec, cases, settings, variables, ExecutionLocation.host());
    }

    /**
     * 只生成、不跑，位置由调用方给。
     *
     * <p>位置必须和真正跑起来那一次<b>是同一个</b>：名字（{@code run.sh} 还是 {@code run.cmd}）
     * 由它决定，而「放行」之后引擎会照着同一个位置去执行。两次判断不一致的结果是
     * 「产物有了、入口找不到」。
     */
    public Generated generate(Spec spec, List<PlanReview.TestCase> cases, TestSettings settings,
                              Map<String, String> variables, ExecutionLocation location) {
        TestArtifacts artifacts = TestArtifacts.create(projectRoot,
                location == null ? ExecutionLocation.host() : location);
        String response;
        try {
            response = ask(spec, cases, artifacts, settings, variables);
        } catch (RuntimeException e) {
            artifacts.delete();
            throw e;
        }
        try {
            List<String> written = write(response, artifacts, settings);
            return new Generated(artifacts.relative(), written,
                    sources(projectRoot, artifacts.relative(), written));
        } catch (SpecflowException e) {
            // 这条路是同步接口，说得出「为什么不行」（界面上就是一条错误提示）；和 run()
            // 那边落成 TestOutcome.rejected 是同一个理由，只是这里直接把原因交出去
            log.warn("重新生成测试产物失败，已整批清掉：{}", e.getMessage());
            artifacts.delete();
            throw e;
        }
    }

    /** 生成结果：产物在哪儿、写了哪些文件、每个文件长什么样。 */
    public record Generated(String directory, List<String> files, Map<String, String> sources) {
    }

    /** 单个文件最多读回这么多字符：它给眼睛看，再长也不会有人在这里读完。 */
    private static final int MAX_SOURCE_CHARS = 4000;

    /** 最多读回几个文件。约定的形态是一分类一文件，正常就两三个。 */
    private static final int MAX_SOURCE_FILES = 8;

    /**
     * 花一次模型调用，让它把测试产物写出来。
     *
     * <p>它和 {@link #write} 分开，是为了让「模型调用没回来」（整次运行都要停）
     * 和「这批代码不能用」（一次正常的结果）在调用方那边<b>仍是两条路</b>：
     * 前者的异常一路冒泡，后者落成 {@code TestOutcome.rejected}。合成一个方法的话，
     * 两种失败分不开——而它们的收场方式完全不同。
     */
    private String ask(Spec spec, List<PlanReview.TestCase> cases, TestArtifacts artifacts,
                       TestSettings settings, Map<String, String> variables) {
        return llm.complete(List.of(
                ChatMessage.system(assembler.systemMessage(spec, templates,
                        TestProtocol.instructions(artifacts.relative(),
                                entriesOf(artifacts, settings), variables))),
                ChatMessage.user(assembler.userMessage(spec, templates)
                        + "\n" + TestProtocol.caseList(cases))));
    }

    /** 这一次要哪几个入口脚本：单元那个永远要；集成那个只有勾了集成测试才要。 */
    private static TestProtocol.Entries entriesOf(TestArtifacts artifacts, TestSettings settings) {
        return new TestProtocol.Entries(artifacts.entry(),
                settings.integration() ? artifacts.integrationEntry() : null,
                artifacts.location().inContainer());
    }

    /**
     * 把模型给的补丁块落盘，返回写了哪些文件（相对项目根）。
     *
     * <p>这段是 {@link #run} 与 {@link #generate} <b>共用</b>的：两处各写一遍的话，
     * 协议校验、入口脚本检查、高危命令拦截这些「防作弊的闸」迟早只长在其中一条路上，
     * 而另一条路上悄悄少一道——重新生成那条路正是最需要它们的（那批代码还没被人看过）。
     *
     * @throws SpecflowException 路径越界、给了锚点、内容为空、命中高危命令、没有入口脚本
     */
    private List<String> write(String response, TestArtifacts artifacts, TestSettings settings) {
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
        // 勾了集成测试却只给了单元入口：那一条路根本跑不起来，而它的表现会是
        // 「入口脚本不存在：…/run-it.cmd」——看着像环境的问题，其实是它没给。
        // 在<b>生成阶段</b>就拦下，用户收到的才是「它没给这个文件」
        if (settings.integration() && !artifacts.hasIntegrationEntry(written)) {
            throw new SpecflowException("这次勾了集成测试，但产物里没有集成入口脚本 "
                    + artifacts.integrationEntry() + "：集成的那几条用例没地方跑。"
                    + "重新生成一次，或者先只跑单元测试");
        }
        log.info("测试产物已写入 {}，共 {} 个文件", artifacts.relative(), written.size());
        return written;
    }

    /**
     * 把测试产物读回成「路径 → 正文」，给界面 review 这批代码用。
     *
     * <p>为什么要读回来：界面上的用例 chip 要说得出「这条用例由哪段测试代码验」——
     * 只给一个 {@code tools/<时间戳>/xxx.py} 的路径，用户还得切到 IDE 里自己翻，
     * 而「看不出测试代码写成什么样」正是「到底谁错了」判不下去的头号原因。
     *
     * <p>两道自我约束：只读 {@code directory} 底下的文件（就算路径是引擎自己记下来的，
     * 读盘这一步照样核一遍白名单），单文件与总文件数都有上限。它是给人看的，不是传输通道：
     * 读不出来就少给一个，不能让一个坏文件把整次运行的结论掀翻。
     *
     * @param directory 产物目录（相对项目根）；空串表示产物已经清掉了
     * @param files     这次写了哪些文件（相对项目根）
     */
    public static Map<String, String> sources(Path projectRoot, String directory, List<String> files) {
        if (directory == null || directory.isBlank() || files == null || files.isEmpty()) {
            return Map.of();
        }
        SafePathResolver resolver = new SafePathResolver(projectRoot);
        Map<String, String> sources = new LinkedHashMap<>();
        for (String file : files) {
            if (sources.size() >= MAX_SOURCE_FILES) {
                log.info("{} 里的产物超过 {} 个，剩下的不读了", directory, MAX_SOURCE_FILES);
                break;
            }
            // 前缀要连斜杠一起比：tools/a 不该认下 tools/ab/x
            if (file == null || !file.startsWith(directory + "/")) {
                continue;
            }
            try {
                String text = Files.readString(resolver.resolve(file), StandardCharsets.UTF_8);
                sources.put(file, text.length() > MAX_SOURCE_CHARS
                        ? text.substring(0, MAX_SOURCE_CHARS) + "\n…（后面还有，见磁盘上的原文件）"
                        : text);
            } catch (IOException | RuntimeException e) {
                // 入口脚本可能是 GBK 编码的 .cmd：读不出来不算错，只是这一段看不成
                log.warn("读不回测试产物 {}：{}", file, e.getMessage());
            }
        }
        return sources;
    }
}
