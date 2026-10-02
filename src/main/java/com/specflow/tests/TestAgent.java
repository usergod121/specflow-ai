package com.specflow.tests;

import com.specflow.context.ContextAssembler;
import com.specflow.exception.BlockedCommandException;
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
     * <p><b>「生成→跑」是一个循环，不是一条直线。</b>实测过：它写的测试代码第一版就编不过
     * （{@code javac} 少带 {@code -encoding UTF-8}，14 个错），而人只能点一次「重新生成」，
     * 修一轮又只修掉一个症状。所以从这一批起，<b>只要脚本一条用例的结论都没跑出来</b>
     * （多半就是编不过、跑不起来），引擎自己再生成一版（最多 {@value #MAX_GENERATIONS} 版），
     * 并把上一版的原始错误带进下一次生成——重掷骰子只会再错一遍。
     *
     * <p><b>能跑起来但用例没过，绝不自动重跑</b>（十五.6）：那种失败机器判不了是谁的错，
     * 自动重跑只会烧调用、还会把「谁错了」这个判断从人手里抢走。停在那里等人放行。
     *
     * <p><b>被安全闸拦下（危险命令）另给一次机会。</b>那一版一个字节都没落盘、一行都没跑，
     * 能喂回去的只有「引擎为什么拒了你」——实测过它连着三版都写同一句 {@code rm -rf "$OUT_DIR"}，
     * 旧行为是一版就停，于是连着白跑三轮、失败清单里还是空的。现在：带上拒绝原因再生成<b>一次</b>；
     * 换了一版还是被拒就停下交给人（记成 {@link TestOutcome.Failure.Kind#BLOCKED_COMMAND}
     * 那一条看得见的失败项），人可以停用那条用例、也可以再点「重新生成」继续优化。
     * 只给一次，是因为这是安全语义：反复求它别写删除命令，等于把闸门当成摆设。
     *
     * <p>环境起不来（{@link TestOutcome.Failure.Kind#hard() 硬判据}）时同样只生成这一版：
     * 它不是「没有结论」那一档，停机交给上层，产物与产品改动都留着等人处置。
     *
     * @param settings  跑单元还是单元+集成
     * @param variables 测试环境那组变量（连接信息 + 引擎那三个把手）；没有环境时是 {@code null}
     * @param location  这次脚本在哪儿跑（容器 / 宿主）。它决定入口脚本叫什么、引擎执行什么命令，
     *                  也决定留档里那句执行位置——三件事必须来自<b>同一个</b>判断
     */
    public TestOutcome run(Spec spec, List<PlanReview.TestCase> cases, TestSettings settings,
                           Map<String, String> variables, ExecutionLocation location) {
        TestOutcome outcome = null;
        // 「被安全拦截」那一次单独记：它只给一次「把拒绝原因喂回去、换一种写法」的机会。
        // 为什么给：实测过它连着三版都写同一句 rm -rf "$OUT_DIR"，一版就停等于连着白跑三轮；
        // 为什么只一次：那是安全语义，不是讨价还价——反复求它别写删除命令，等于把闸门当成摆设
        boolean blockedRetried = false;
        // 上一版被安全拦截时，喂给下一次生成的不是「脚本输出」而是那句拒绝原因
        String refusal = null;
        for (int generation = 1; generation <= MAX_GENERATIONS; generation++) {
            // 模型调用本身失败会从这里原样冒泡（整次运行都要停）；「生成被拒」落成下面那一档
            Attempt attempt = attempt(spec, cases, settings, variables, location, outcome, refusal);
            if (attempt.rejected()) {
                outcome = TestReport.withCalls(attempt.outcome(), generation);
                if (!attempt.blocked() || blockedRetried || generation == MAX_GENERATIONS) {
                    log.warn("测试产物没能落地，这次测试到此为止：{}", attempt.rejection().getMessage());
                    return outcome;
                }
                blockedRetried = true;
                refusal = attempt.rejection().getMessage();
                log.warn("第 {} 版测试产物被安全闸拦下了，把拒绝原因喂回去换一种写法再生成一版：{}",
                        generation, refusal);
                continue;
            }
            // 每生成一版就是一次真实调用，账要按版数记（用户掏的钱不能说少）
            outcome = TestReport.withCalls(attempt.outcome(), generation);
            if (!attempt.reportedNothing()) {
                // 要么跑出结论了（交给人），要么是硬判据那两档（上层停机），要么压根没跑。
                // 三种都不再重试
                return outcome;
            }
            if (generation == MAX_GENERATIONS) {
                log.warn("生成 {} 版测试代码都跑不出一条用例的结论，停下来交给人：{}",
                        MAX_GENERATIONS, outcome.detail());
                return outcome;
            }
            log.warn("这一版测试代码跑不出一条用例的结论（第 {} 版），带上原始错误再生成一版：{}",
                    generation, attempt.outcome().output());
            // 下一版要修的是**这一版的运行错误**，不再是上上版那句拒绝原因：不清掉它，
            // 提示词里会挂着一句和当前这一版毫无关系的「你上一版被拦下了」
            refusal = null;
            // 把这一版收掉再进下一版：它编不过/跑不起来，留着只会让 tools/ 只增不减，
            // 而真正要给人看的那一份是最后一版（前几版的原始错误已经写进下一版的提示词里）
            attempt.artifacts().delete();
        }
        return outcome;
    }

    /**
     * 生成 + 跑<b>一版</b>测试产物。
     *
     * <p>拆出来是为了让上面那个循环只有「什么时候再来一版」这一件事：生成、落盘闸门、
     * 溯源核对、跑脚本、对账，五步的顺序和判据全在这一处，一版和最后一版走的是<b>同一条路</b>。
     * <b>{@link #generate}（重新生成那条路）走的也是它</b>：从这一批起，凡是生成的测试代码
     * 都要过「跑一次、看有没有结论」这道编译核对，那条路上不再有「跳过编译」的例外。
     *
     * @param previous 上一版跑完的结论；第一版是 {@code null}。
     *                 非空时它的原始输出会作为「上一版的错误」附在提示词里——模型照着自己的
     *                 原始报错改，比重新想一遍命中率高得多（实测过它连着三版都选了同一条错路）
     * @param refusal  上一版<b>被安全闸拦下</b>时那句拒绝原因；不是这种重试时是 {@code null}。
     *                 它和 {@code previous} 分开：那一次产出压根没跑起来，能喂回去的只有
     *                 「引擎为什么拒了你」，把一份空输出当错误喂过去，它只会以为是运行环境的问题
     * @return 这一版的结果。<b>两种失败分开走：</b>模型调用本身失败会原样抛出去
     *         （整次运行都要停），而「这批测试代码不能用」落成 {@link Attempt#rejected()}——
     *         怎么交代由调用方决定（{@code run} 落成一条结论，{@code generate} 原样抛出）。
     *         之所以不在这里抛：两者都是 {@code SpecflowException}，调用方分不开
     */
    private Attempt attempt(Spec spec, List<PlanReview.TestCase> cases, TestSettings settings,
                            Map<String, String> variables, ExecutionLocation location,
                            TestOutcome previous, String refusal) {
        TestArtifacts artifacts = TestArtifacts.create(projectRoot,
                location == null ? ExecutionLocation.host() : location);
        String response;
        try {
            response = ask(spec, cases, artifacts, settings, variables, previous, refusal);
        } catch (RuntimeException e) {
            // 模型调用没回来 = 这一轮一个字节都没生成。刚建的那个空 tools/<时间戳>/ 要收掉：
            // 留着它会攒成一串空目录，看上去像「跑过好几次测试」，而实际什么都没跑。
            // 异常原样冒泡：这不是「这批测试代码不能用」，是整次运行都要停的那一类
            artifacts.delete();
            throw e;
        }
        try {
            List<String> written = write(response, artifacts, settings);
            // 跑之前先机器核对「用例 ⇄ 测试代码」的连线（四条判据）：缺哪条、多哪条、
            // 哪条期望被改了。任何一条不通过就**拒绝跑**——跑出来的结论没有意义，
            // 而且它会看着像一份证据。产物留着给人看差异，走「重新生成」那条路补
            CaseTraceCheck.Report trace = CaseTraceCheck.check(cases, contentsOf(written));
            if (!trace.ok()) {
                log.warn("溯源核对不通过，拒绝运行这批测试：{}", trace.summarize());
                return new Attempt(TestReport.traceRefused(1, artifacts.relative(), written,
                        cases, trace), artifacts, false, written, trace, null);
            }
            // 跑哪一个入口，由这次勾没勾集成决定：勾了就是**两个都跑**
            // （两个都在同一个位置跑：有可用环境就是容器里，否则宿主上；见 runScripts）
            TestOutcome raw = runScripts(spec, artifacts, written, settings, variables);
            // 「一条结论都没报出来」要在对账**之前**判：对账会把清单上的每一条都补成「没过」，
            // 那份账看不出脚本到底报过几条（见 TestReport.ranWithoutConclusions）
            boolean reportedNothing = TestReport.ranWithoutConclusions(raw);
            TestOutcome outcome = TestReport.coverage(raw, cases, trace.links());
            log.info("测试脚本跑完：退出码 {}（{}）", outcome.exit(), artifacts.relative());
            // 环境起不来（硬判据）时**什么都不删**：停下的只是这一次运行，现场原样留着
            // （产品改动进「待处置」、产物留在 tools/ 里）——见 run 的注释
            return new Attempt(outcome, artifacts, reportedNothing, written, trace, null);
        } catch (BlockedCommandException e) {
            // 安全闸拦下了它写的东西（高危命令）：产物一个字节都不落盘、也一行都不执行。
            // 「这一批没落地」是一档**看得见的失败**（不是一句日志就完了）：它和断言失败并排
            // 进失败清单、进界面、进留档，人据此决定是停用那条用例还是让它换一种写法。
            // 产物照旧整批清掉——它是模型写的、而且在宿主上真跑，留着没有意义也不会被执行
            log.warn("测试产物被安全闸拦下（不落盘、不执行），这一批已清掉：{}", e.getMessage());
            artifacts.delete();
            return new Attempt(TestReport.blockedCommand(1, e.getMessage()), artifacts, false,
                    List.of(), null, e);
        } catch (SpecflowException e) {
            // 生成阶段就被拒了（没按协议写、路径越界）：产物一个字节都不留。
            // 产品代码刚才编译通过、改动还在磁盘上，这里只报告「这批测试代码不能用」。
            // <b>这一档照旧删产物</b>：拒绝的理由是这批代码本身不能用（不是环境的事），
            // 而它连一个可执行的入口都没落地，留着只是一堆指向空处的文件
            log.warn("测试产物没能落地，已整批清掉：{}", e.getMessage());
            artifacts.delete();
            return new Attempt(TestReport.rejected(1, e.getMessage()), artifacts, false,
                    List.of(), null, e);
        }
    }

    /**
     * 一版的结果，连同它的产物目录与写下的文件一起交出去。
     *
     * <p>为什么要把产物一起带上：重试之前得先把这一版删掉（它跑不起来，留着只会让
     * {@code tools/} 只增不减），而删除是 {@link TestArtifacts} 的事——
     * 循环那边只该知道「这一版不成了，收掉它」，不该自己拼路径。
     *
     * <p>为什么还要带上 {@code written} 与 {@code trace}：{@link #generate} 那边要把
     * 这一版原样交回给界面（正文 + 连线核对结果 + 它编不编得过），而它走的是<b>同一条</b>
     * {@link #attempt}。少这两样，那条路就只能自己再拼一遍——那正是「两套实现」的开头。
     *
     * @param reportedNothing 这一版<b>一条用例的结论都没报出来</b>（自动重试的判据）。
     *                        它必须在对账之前算好：对账之后那份账里每条用例都有下场了
     * @param rejection       这一版<b>没能落盘</b>时那个原因（协议、越界、高危命令）；
     *                        落盘成功时是 {@code null}。两种失败在调用方那边要分开交代，
     *                        而它们都是 {@code SpecflowException}，所以在这里就把类型记下来
     */
    private record Attempt(TestOutcome outcome, TestArtifacts artifacts, boolean reportedNothing,
                           List<String> written, CaseTraceCheck.Report trace,
                           SpecflowException rejection) {

        /** 这一版是不是「生成就被拒了」（产物已经清掉，没什么可给人看的）。 */
        boolean rejected() {
            return rejection != null;
        }

        /**
         * 这一版是不是被<b>安全闸</b>拦下的（危险命令），而不是协议不符 / 路径越界。
         *
         * <p>两者收场不同：安全拦截值得再生成一版（拒绝原因喂回去让它换写法），
         * 协议问题重掷骰子还是同样的错。判据就是抛出来的那个类型
         * （见 {@link BlockedCommandException}），不靠比对消息里的字。
         */
        boolean blocked() {
            return rejection instanceof BlockedCommandException;
        }
    }

    /**
     * 同一次运行里最多生成几版测试代码。
     *
     * <p>3 = 首版 + 两次自动重试。定 3 而不是更多：每一次都是真金白银的一次模型调用，
     * 而实测里「连着三版都选同一条错路」是出现过的（三次都拿反射去改进程环境变量）。
     * 到上限就停下，把原始错误摆给人——继续试下去只是替一个已经判不了的局面烧钱。
     */
    private static final int MAX_GENERATIONS = 3;

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
     * 「测试代码错了 → 重新生成」那条路：生成一批新的测试产物，<b>并且先做一次编译核对</b>。
     *
     * <p>它和 {@link #run} 走的是<b>同一条</b> {@link #attempt}：生成 → 落盘闸门 → 溯源核对 →
     * 跑一次入口脚本 → 看有没有结论。差别只在结果怎么用——这里<b>不把跑出来的断言结论当验收证据</b>
     * （那要等人点「放行」、点「下一轮」再跑，十五.6），只用它回答「这批代码到底跑不跑得起来」。
     * 从这一批起，「重新生成时跳过编译校验」那个例外没有了：上一次实测里人点完「重新生成」，
     * 拿到的是一批<b>编不过</b>的代码，而界面上写着「已重新生成」，等于把一轮白跑当成了进展。
     *
     * <p>跑不出结论（多半是编不过）时它有自己的预算：最多 {@value #MAX_GENERATIONS} 版，
     * 和 {@link #run} 那一份<b>各记各的</b>（这是两条路、两次动作，谁也不该吃掉对方的次数）。
     * 到上限就把最后一版和它的原始错误一起交出去，由界面标成「它的代码编不过」。
     *
     * <p>它不写产品代码、不碰运行留档、也不进轮次账：这不是一次运行，是一次生成。
     * 环境那一摊（预热 / reset）也不在这里——那是<b>一次运行</b>的排场（见 {@code DevelopmentAgent}），
     * 这里只是把脚本执行一次看有没有结论。
     *
     * @return 产物目录、写了哪些文件、每个文件的正文、连线核对结果；
     *         最后那一版编不过时还有一段「它的代码编不过 + 原始错误」
     * @throws SpecflowException 生成被拒（没按协议写、路径越界）；命中高危命令也会抛，
     *                           但那种先带拒绝原因重生成一次（见 {@code run} 的注释），
     *                           换了一版还被拦下才抛出来。两种情况产物一个字节都不留
     */
    public Generated generate(Spec spec, List<PlanReview.TestCase> cases) {
        return generate(spec, cases, TestSettings.UNIT_ONLY, null, ExecutionLocation.host());
    }

    /**
     * 生成 + 编译核对，这一次带环境。
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
     * 生成 + 编译核对，位置由调用方给。
     *
     * <p>位置必须和真正跑起来那一次<b>是同一个</b>：名字（{@code run.sh} 还是 {@code run.cmd}）
     * 由它决定，而编译核对这一步立刻就要执行它，之后「放行」时引擎还会照着同一个位置去执行。
     * 两次判断不一致的结果是「产物有了、入口找不到」。
     */
    public Generated generate(Spec spec, List<PlanReview.TestCase> cases, TestSettings settings,
                              Map<String, String> variables, ExecutionLocation location) {
        Generated generated = null;
        TestOutcome previous = null;
        // 和 run 那一边同一条规矩：被安全闸拦下只给一次换写法的机会（见 run 的注释）。
        // 这条路也要有——人点「重新生成」要的正是「换一版」，而一版就被同一条删除命令拒掉，
        // 他能做的只是再点一次，等同一个结果
        String refusal = null;
        for (int generation = 1; generation <= MAX_GENERATIONS; generation++) {
            Attempt attempt = attempt(spec, cases, settings, variables, location, previous, refusal);
            if (attempt.rejected()) {
                if (attempt.blocked() && refusal == null && generation < MAX_GENERATIONS) {
                    refusal = attempt.rejection().getMessage();
                    log.warn("重新生成的第 {} 版被安全闸拦下，把拒绝原因喂回去换一种写法再生成一版：{}",
                            generation, refusal);
                    continue;
                }
                // 这条路是同步接口，说得出「为什么不行」（界面上就是一条错误提示）；和 run()
                // 那边落成 TestOutcome.rejected 是同一个理由，只是这里把**原来那个异常**
                // 原样交出去（换一个类型或重拼一句话，都会丢掉它自带的说法）。
                // 产物已经在 attempt 里清掉了：协议问题重掷骰子还是同样的错，
                // 换了一版还被安全闸拦下也一样——那时候人要看的是那句拒绝原因，不是第三次尝试
                throw attempt.rejection();
            }
            String directory = attempt.artifacts().relative();
            generated = new Generated(directory, attempt.written(),
                    sources(projectRoot, directory, attempt.written()), attempt.trace(),
                    compileProblem(attempt, generation));
            if (!attempt.reportedNothing()) {
                // 跑出结论了（编得过）：这一版就是交给人 review 的那一版
                return generated;
            }
            if (generation == MAX_GENERATIONS) {
                log.warn("重新生成 {} 版测试代码都跑不出一条用例的结论，把最后一版和原始错误交给人：{}",
                        MAX_GENERATIONS, attempt.outcome().output());
                return generated;
            }
            log.warn("重新生成的这一版跑不出一条用例的结论（第 {} 版），带上原始错误再生成一版：{}",
                    generation, attempt.outcome().output());
            // 同 run 那边：下一版要修的是这一版的运行错误，不再挂着上上版那句拒绝原因
            refusal = null;
            // 和 run 那边同一条规矩：中间这几版收掉，只留最后一版（原始错误已经进了下一版的提示词）
            attempt.artifacts().delete();
            previous = attempt.outcome();
        }
        return generated;
    }

    /**
     * 这一版「编不过」时给用户的那一段；编得过（跑出了结论）时是 {@code null}。
     *
     * <p>判据与 {@link #run} 那边<b>同一个</b>（{@link TestReport#ranWithoutConclusions}）：
     * 脚本跑了、退出码拿得到、却一条 {@code PASS} / {@code FAIL} 都没报出来。
     * 措辞也沿用引擎里那一档的标签（{@link TestOutcome.Failure.Kind#UNRUNNABLE}）——
     * 它说的是「引擎亲见的是没有结论」，而不是替模型判「你编译错了」。
     *
     * <p>原始错误<b>原样带走</b>：掐掉它，人就只能猜这批代码坏在哪。
     */
    private static CompileProblem compileProblem(Attempt attempt, int generations) {
        if (!attempt.reportedNothing()) {
            return null;
        }
        String first = attempt.outcome().failures().stream()
                .map(TestOutcome.Failure::actual)
                .filter(text -> !text.isBlank())
                .findFirst()
                .orElse("");
        String text = TestOutcome.Failure.Kind.UNRUNNABLE.label()
                + "：它一条用例的结论都没跑出来，换了 " + generations + " 版都是这样（多半是编不过）。"
                + (first.isEmpty() ? "" : "第一条错误：" + first);
        return new CompileProblem(text, attempt.outcome().output());
    }

    /**
     * 生成结果：产物在哪儿、写了哪些文件、每个文件长什么样、这批代码接上线了没有、
     * 以及它到底跑不跑得起来。
     *
     * @param problem 最后那一版<b>跑不出一条结论</b>时的那段话（标着「它的代码编不过」+ 原始错误）；
     *                编得过时是 {@code null}——那时候没什么可说的，人看正文就行
     */
    public record Generated(String directory, List<String> files, Map<String, String> sources,
                            CaseTraceCheck.Report trace, CompileProblem problem) {
    }

    /**
     * 「它编不过」那一档：给用户的一句话，以及它凭什么这么说（脚本的原始输出）。
     *
     * <p>分成两栏是因为它们的去处不同：那句话是结论（界面上一眼看见），
     * 原始输出是证据（折在下面，但一个字节都不许掐）。
     */
    public record CompileProblem(String text, String output) {
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
     *
     * @param previous 上一版跑完的结论（第一版是 {@code null}）。非空时把它的原始输出
     *                 附在用户消息的最后一段：这一版是「修上一版的错」，不是重新想一遍
     * @param refusal  上一版被安全闸拦下时那句拒绝原因（不是这种重试时 {@code null}）：
     *                 这一版要修的是「写法被闸门拒了」，不是「跑起来的报错」
     */
    private String ask(Spec spec, List<PlanReview.TestCase> cases, TestArtifacts artifacts,
                       TestSettings settings, Map<String, String> variables,
                       TestOutcome previous, String refusal) {
        String message = assembler.userMessage(spec, templates)
                + "\n" + TestProtocol.caseList(cases);
        if (refusal != null && !refusal.isBlank()) {
            message = message + "\n" + TestProtocol.refusalNotice(refusal);
        } else if (previous != null) {
            message = message + "\n" + TestProtocol.retryNotice(previous);
        }
        return llm.complete(List.of(
                ChatMessage.system(assembler.systemMessage(spec, templates,
                        TestProtocol.instructions(artifacts.relative(),
                                entriesOf(artifacts, settings), variables))),
                ChatMessage.user(message)));
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
     * 把这次写下的产物<b>完整</b>读回来，给溯源核对用。
     *
     * <p>为什么不复用 {@link #sources}：那一份是给人看的，单文件掐到 4000 字、最多读 8 个文件——
     * 掐掉的那半截里可能正好有锚点，于是「其实写了」会被判成「漏实现」，而人得重新生成一次
     * 才看得出来。这里一个字符都不许少。
     *
     * <p>读不出来就少一个文件、记一条警告：刚写过的文件读不回来是磁盘的事，那时真的少了锚点，
     * 报「漏实现」也是对的（宁可拒绝跑让人看一眼，也不要放过一批对不上线的测试）。
     */
    private Map<String, String> contentsOf(List<String> written) {
        SafePathResolver resolver = new SafePathResolver(projectRoot);
        Map<String, String> contents = new LinkedHashMap<>();
        for (String file : written) {
            try {
                contents.put(file, Files.readString(resolver.resolve(file), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                log.warn("读不回刚写下的测试产物 {}：{}", file, e.getMessage());
            }
        }
        return contents;
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
