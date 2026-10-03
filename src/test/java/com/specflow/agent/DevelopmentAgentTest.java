package com.specflow.agent;

import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener.StepState;
import com.specflow.agent.AgentListener.StepsSource;
import com.specflow.env.CommandRunner;
import com.specflow.env.ComposeFile;
import com.specflow.env.EnvConfigLoader;
import com.specflow.env.TestEnvironment;
import com.specflow.exception.PatchConflictException;
import com.specflow.history.RunRecord;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.patch.PatchApplier;
import com.specflow.project.BuildConfig;
import com.specflow.project.LlmConfig;
import com.specflow.project.ProjectConfig;
import com.specflow.project.SnapshotConfig;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanStep;
import com.specflow.review.ReviewProtocol;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.spec.Spec;
import com.specflow.spec.VerifySpec;
import com.specflow.template.TemplateRegistry;
import com.specflow.tests.CaseHowStage;
import com.specflow.tests.EntryScripts;
import com.specflow.tests.Refeed;
import com.specflow.tests.TestOutcome;
import com.specflow.tests.TestSettings;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.CompileVerifier;
import com.specflow.verify.VerificationContext;
import com.specflow.verify.VerificationResult;
import com.specflow.verify.Verifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 编排行为的测试。
 *
 * <p>这里不联网、不跑真实编译器：模型与校验器都是脚本化的假实现。
 * 这样测的是「什么时候重试、什么时候回滚、什么时候放弃」——
 * 也就是 Agent 真正的那部分逻辑，而不是模型或 Maven 能不能用。
 */
@DisplayName("开发 Agent 的编排")
class DevelopmentAgentTest {

    @TempDir
    Path root;

    private static final String ORIGINAL = """
            class Foo {
                int a = 1;
            }
            """;

    private static final String BAR_ORIGINAL = """
            class Bar {
                int b = 0;
            }
            """;

    /** 系统提示词里那个产物目录：测试产物的路径带时间戳，只有从这里读得到。 */
    private static final java.util.regex.Pattern ARTIFACT_DIRECTORY =
            java.util.regex.Pattern.compile("tools/\\d{8}-\\d{6}(-\\d+)?");

    /**
     * 系统提示词里那几个<b>入口脚本</b>的完整路径（宿主上是 {@code run.cmd}、容器里是 {@code run.sh}）。
     *
     * <p>协议里把它写得明明白白，假模型照读就是了——按本机平台写死的话，
     * 「环境就绪、脚本进容器跑」那条路会交出一份 {@code run.cmd}，而引擎要的是 {@code run.sh}。
     * 文件名本身只含 ASCII，所以这个正则在中文提示词里也认得出（示例里那个
     * {@code tools/<时间戳>/测试文件名} 因为带中文，不会被匹配到）。
     */
    private static final java.util.regex.Pattern ENTRY_PATH =
            java.util.regex.Pattern.compile("tools/\\d{8}-\\d{6}(-\\d+)?/[A-Za-z0-9._-]+\\.(cmd|sh)");

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("Foo.java"), ORIGINAL);
    }

    @Test
    @DisplayName("一轮通过：补丁落盘，状态为成功")
    void appliesPatchAndSucceeds() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));
        ScriptedVerifier verifier = new ScriptedVerifier(passed());

        AgentResult result = agent(llm, verifier).run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(read("Foo.java")).contains("int a = 2;");
        assertThat(llm.patches()).hasSize(1);
    }

    @Test
    @DisplayName("系统消息里带着补丁协议，让模型知道该输出什么格式")
    void sendsProtocolInSystemMessage() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        agent(llm, new ScriptedVerifier(passed())).run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(llm.patches().get(0).get(0).role()).isEqualTo(ChatMessage.SYSTEM);
        assertThat(llm.patches().get(0).get(0).content()).contains("<<<<<<< SEARCH");
        assertThat(llm.patches().get(0).get(1).content()).contains("int a = 1;");
    }

    @Test
    @DisplayName("锚点失配时不落盘，把原因回喂后第二轮成功")
    void retriesAfterAnchorFailure() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 999;", "int a = 2;"),
                patch("int a = 1;", "int a = 2;"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(read("Foo.java")).contains("int a = 2;");
        assertThat(lastUserMessage(llm)).contains("ANCHOR_NOT_FOUND").contains("没有任何改动");
    }

    @Test
    @DisplayName("锚点反复失配时放弃，磁盘保持原样")
    void givesUpAfterConflictRetries() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("nope", "x"), patch("nope", "x"), patch("nope", "x"), patch("nope", "x"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.attempts()).isEqualTo(4);
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("校验失败时回滚，并把「已回滚」这一事实告诉模型")
    void rollsBackAndRetriesWhenVerificationFails() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 1;", "int a = 3;"));
        ScriptedVerifier verifier = new ScriptedVerifier(failed(), passed());

        AgentResult result = agent(llm, verifier).run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(read("Foo.java")).contains("int a = 3;");
        assertThat(lastUserMessage(llm)).contains("已被回滚").contains("编译错误：找不到符号");
    }

    @Test
    @DisplayName("校验重试次数用尽时放弃，磁盘回到初始状态")
    void restoresWorkspaceWhenRetriesExhausted() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));
        Spec spec = TestSpecs.spec(List.of("Foo.java"),
                new VerifySpec(true, null, 0, VerifySpec.AUTO_ROUNDS));

        AgentResult result = agent(llm, new ScriptedVerifier(failed())).run(spec);

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("校验被跳过时报告「未校验」，而不是伪装成通过")
    void reportsUnverifiedWhenAllVerifiersSkipped() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        AgentResult result = agent(llm, new ScriptedVerifier(skipped()))
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS_UNVERIFIED);
        assertThat(result.detail()).contains("没有执行任何校验");
        assertThat(read("Foo.java")).contains("int a = 2;");
    }

    @Test
    @DisplayName("模型声明信息不足时立刻停下，不碰任何文件")
    void stopsWhenModelNeedsContext() {
        ScriptedLlm llm = new ScriptedLlm("NEED_CONTEXT: 需要 src/main/java/com/demo/Bar.java");

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_CONTEXT);
        assertThat(result.detail()).contains("Bar.java");
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
        assertThat(llm.patches()).hasSize(1);
    }

    @Test
    @DisplayName("补丁后面附带 NEED_CONTEXT 字样不当作中止信号")
    void ignoresNeedContextInsideLongResponse() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;")
                + "\n如果想更精确，NEED_CONTEXT: 更多文件\n不止一行\n还有一行\n");

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
    }

    @Test
    @DisplayName("mode=create 可以新建文件并通过校验")
    void createsNewFile() {
        ScriptedLlm llm = new ScriptedLlm("""
                <<<<<<< SEARCH src/main/java/demo/New.java
                =======
                class New {}
                >>>>>>> REPLACE
                """);
        Spec spec = TestSpecs.spec(List.of("src/main/java/demo/New.java"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed())).run(spec);

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(root.resolve("src/main/java/demo/New.java")).exists();
    }

    @Test
    @DisplayName("进度回调按顺序上报每一轮发生了什么")
    void reportsProgressToListener() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 999;", "int a = 2;"),
                patch("int a = 1;", "int a = 2;"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(listener.events).containsExactly(
                // 施工单从哪来会先说一声，之后才是逐轮的事
                "plan:SINGLE:1",
                "round:1",
                "rejected:1",
                "round:2",
                "applied:2",
                "verified:2",
                "finished:SUCCESS");
    }

    @Test
    @DisplayName("校验失败时回调里能看到回滚")
    void reportsRollbackToListener() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 1;", "int a = 3;"));
        RecordingListener listener = new RecordingListener();

        agent(llm, new ScriptedVerifier(failed(), passed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(listener.events).containsExactly(
                "plan:SINGLE:1",
                "round:1",
                "applied:1",
                "verified:1",
                "restored:1",
                "round:2",
                "applied:2",
                "verified:2",
                "finished:SUCCESS");
    }

    @Test
    @DisplayName("人工中断在轮与轮之间生效，磁盘回滚到初始状态")
    void stopsWhenCancelled() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 1;", "int a = 3;"));
        CancelAfterRoundListener listener = new CancelAfterRoundListener();

        AgentResult result = agent(llm, new ScriptedVerifier(failed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6, VerifySpec.AUTO_ROUNDS)));

        assertThat(result.status()).isEqualTo(AgentResult.Status.CANCELLED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
        assertThat(llm.patches()).hasSize(1);
        // 单步执行压根没有「第几步」这回事：它的步态由录制器按运行结果补
        // （见 RunRecorder.singleStepState），引擎一个步级事件都不该发
        assertThat(listener.events).as("单步不发步级事件，中断也不例外").isEmpty();
    }

    @Test
    @DisplayName("默认重试上限是 6 轮，不是 1 轮")
    void defaultRetryBudgetIsSix() {
        assertThat(VerifySpec.DEFAULT.maxRetry()).isEqualTo(6);
    }

    @Test
    @DisplayName("判定为环境问题时立刻停下，不再烧轮次，磁盘回滚")
    void stopsImmediatelyOnEnvironmentalFailure() {
        // 第二份补丁是备着的：真多跑了一轮，它就会被用掉
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 1;", "int a = 3;"));
        ScriptedVerifier verifier = new ScriptedVerifier(VerificationResult.failed(
                "编译校验", "mvn compile", "程序包 com.google.gson 不存在",
                VerificationResult.Kind.ENVIRONMENT));

        AgentResult result = agent(llm, verifier)
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6, VerifySpec.AUTO_ROUNDS)));

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_ENVIRONMENT);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(llm.patches()).hasSize(1);
        // 光说「失败了」没用，得说清凭什么算环境问题、以及文件已经回去了
        assertThat(result.detail()).contains("程序包 com.google.gson 不存在").contains("回滚");
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("连续两轮卡在同一个错误上时停下，不再往下试")
    void stopsWhenTheSameFailureRepeats() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 1;", "int a = 3;"),
                patch("int a = 1;", "int a = 4;"));
        ScriptedVerifier verifier = new ScriptedVerifier(failed());

        AgentResult result = agent(llm, verifier)
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6, VerifySpec.AUTO_ROUNDS)));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(llm.patches()).hasSize(2);
        assertThat(result.detail()).contains("同一个错误");
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("两轮错误不一样就继续重试——指纹不能粗到把改好的也拦下")
    void keepsRetryingWhenTheFailureChanges() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 1;", "int a = 3;"),
                patch("int a = 1;", "int a = 4;"));
        ScriptedVerifier verifier = new ScriptedVerifier(
                VerificationResult.failed("脚本校验", "javac", "编译错误：找不到符号 a"),
                VerificationResult.failed("脚本校验", "javac", "编译错误：找不到符号 b"),
                passed());

        AgentResult result = agent(llm, verifier)
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6, VerifySpec.AUTO_ROUNDS)));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.attempts()).isEqualTo(3);
    }

    /**
     * 真实事故的回归：读编译日志时抛了编码异常，异常一路穿出去，
     * 于是这一轮既没有结论、也不回滚，界面上还写着「运行中断（共 0 轮）」，
     * 而用户同时看到「已写入 3 个文件」。
     */
    @Test
    @DisplayName("校验器自己炸了也要落成结论：停下、回滚，而不是把整轮掀掉")
    void survivesVerifierCrash() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));
        Verifier broken = new Verifier() {
            @Override
            public String name() {
                return "会炸的校验";
            }

            @Override
            public VerificationResult verify(VerificationContext context) {
                throw new IllegalStateException("无法读取编译日志：Input length = 1");
            }
        };

        AgentResult result = agent(llm, broken).run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_ENVIRONMENT);
        assertThat(result.detail()).contains("Input length = 1");
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
    }

    /**
     * 这一条接的是真的编译校验器：真起一条命令、真按内容分类、真回滚、真留日志。
     * 上面几条用假校验器测的是编排，这条测的是「接起来之后是不是真的这样」。
     */
    @Test
    @DisplayName("真跑一遍缺依赖的现场：停下、回滚、日志留在项目里、路径写进给用户的那句话")
    void stopsAndKeepsLogOnRealMissingDependency() throws IOException {
        // 第二份补丁是备着的：真多跑了一轮，它就会被用掉
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 1;", "int a = 3;"));
        ProjectConfig project = new ProjectConfig(
                new BuildConfig("echo [ERROR] 程序包 com.google.gson 不存在 & exit 1"),
                LlmConfig.DEFAULT, SnapshotConfig.DEFAULT);

        AgentResult result = new DevelopmentAgent(root, project, TemplateRegistry.empty(),
                llm, List.of(new CompileVerifier()))
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6, VerifySpec.AUTO_ROUNDS)));

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_ENVIRONMENT);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.detail())
                .as("给用户的那句话: %s", result.detail())
                .contains("程序包 com.google.gson 不存在")   // 凭什么说是环境问题
                .contains(".specflow/logs/compile-")         // 完整日志在哪
                .contains("回滚");
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);

        try (var files = Files.list(root.resolve(".specflow/logs"))) {
            assertThat(files).as("失败日志确实留下来了").hasSize(1);
        }
    }

    @Test
    @DisplayName("确认过的方案会作为施工图回喂给开发阶段，且拼在消息末尾")
    void feedsApprovedPlanIntoDevelopment() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));
        PlanReview approved = PlanReview.of("在 Foo 里加一个方法",
                "flowchart TD\n    A[入口] --> B[出口]", List.of());

        agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")), approved);

        String userMessage = llm.patches().get(0).get(1).content();
        assertThat(userMessage).contains("已确认的实现方案").contains("在 Foo 里加一个方法");
        assertThat(userMessage).endsWith("\n");
    }

    @Test
    @DisplayName("没有方案时不出现施工图段落")
    void omitsPlanSectionWhenAbsent() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        agent(llm, new ScriptedVerifier(passed())).run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(llm.patches().get(0).get(1).content()).doesNotContain("已确认的实现方案");
    }

    // ---------- 回喂给开发（十五.6 第一条路） ----------

    /**
     * 回喂那一段的位置：<b>需求 → 施工单 → 上一轮的测试失败</b>。
     *
     * <p>顺序不是排版问题：排在最前会盖过需求本身，排在施工单前面又会让它以为先改代码再对单子。
     * 放在最后，它才是「按这份单子做，顺带把这几条修掉」——而这正是这条路的全部意思。
     */
    @Test
    @DisplayName("回喂那一段拼在最后：需求 → 施工单 → 上一轮的测试失败")
    void appendsRefeedAfterThePlan() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"),
                artifactsPatch(0, "PASS | 1"));
        PlanReview approved = planWithCases();
        Refeed refeed = refeed();

        agent(llm, new ScriptedVerifier(passed())).run(TestSpecs.spec(List.of("Foo.java")),
                approved, refeed);

        String userMessage = llm.patches().get(0).get(1).content();
        assertThat(userMessage).contains("## 需求").contains("已确认的实现方案").contains(Refeed.HEADING);
        assertThat(userMessage.indexOf("## 需求"))
                .isLessThan(userMessage.indexOf("已确认的实现方案"));
        assertThat(userMessage.indexOf("已确认的实现方案"))
                .as("回喂在施工单之后").isLessThan(userMessage.indexOf(Refeed.HEADING));
    }

    @Test
    @DisplayName("没有回喂时提示词里不多那一段（每一轮都带着它就成了噪声）")
    void omitsRefeedSectionWhenNotRefeeding() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"),
                artifactsPatch(0, "PASS | 1"));

        agent(llm, new ScriptedVerifier(passed())).run(TestSpecs.spec(List.of("Foo.java")),
                planWithCases());

        assertThat(llm.patches().get(0).get(1).content()).doesNotContain(Refeed.HEADING);
    }

    /**
     * 「它没有改动」这一笔要落进留档。
     *
     * <p>实测里那一轮回喂，模型把文件<b>原样再交了一遍</b>（写入内容与旧版逐字相同、
     * diff 为空），而留档只写着「已写入 1 个文件」——和真改过长得一模一样，
     * 人点完「下一轮」看不出它其实什么都没做。判据是引擎算的（{@link Refeed#unchanged}）。
     */
    @Test
    @DisplayName("回喂之后它没改动：留档里明写「它没有改动」")
    void recordsThatTheModelChangedNothing() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        PlanReview approved = planWithCases();
        // 交回来的内容与原文逐字相同：diff 会是空的（实测里它就是这么干的）
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 1;"),
                artifactsPatch(0, "PASS | 1"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        Refeed refeed = refeed();

        new DevelopmentAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(), llm,
                List.of(new ScriptedVerifier(passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP, refeed))
                .run(spec, approved, refeed);

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();
        assertThat(record.refeed()).as("回喂了哪几条、原文是什么，都要留档").isNotNull();
        assertThat(record.refeed().cases()).containsExactly(1);
        assertThat(record.refeed().text()).contains(Refeed.HEADING).contains("用例 1");
        assertThat(record.unchanged()).as("它这一轮一个字节都没改").isTrue();
    }

    /** 回喂确实是产品代码真改了：不许标成「它没有改动」。 */
    @Test
    @DisplayName("回喂之后它真改了：不留「它没有改动」这一笔")
    void doesNotClaimUnchangedWhenTheModelDidChange() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        PlanReview approved = planWithCases();
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"),
                artifactsPatch(0, "PASS | 1"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        Refeed refeed = refeed();

        new DevelopmentAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(), llm,
                List.of(new ScriptedVerifier(passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP, refeed))
                .run(spec, approved, refeed);

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();
        assertThat(record.unchanged()).as("这一处 diff 是真的，不能标成没改").isFalse();
    }

    // ---------- 测试阶段：生成 → 跑脚本 → 看退出码 ----------

    @Test
    @DisplayName("测试没过：改动留在磁盘上等处置，状态是「测试没过」而不是失败")
    void keepsChangesWhenTestsFail() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                artifactsPatch(1, "FAIL | 1 | a == 2 | a == 1 | code is wrong"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")), planWithCases());

        assertThat(result.status()).isEqualTo(AgentResult.Status.TESTS_FAILED);
        assertThat(read("Foo.java")).as("错的可能是用例，所以改动不回滚").contains("int a = 2;");
        assertThat(result.detail())
                .contains("退出码 1")
                .contains("用例 1")
                .contains("期望 a == 2")
                .contains("实际 a == 1")
                .as("谁错了机器判不了，所以不许替用户下结论")
                .contains("交给你定");
        assertThat(result.verifications()).as("测试脚本的结论挂在校验结果里，CLI 那一行才有得打")
                .anySatisfy(verification -> assertThat(verification.verifier()).isEqualTo("测试脚本"));
    }

    /**
     * 脚本自己说的「跑不起来」和「引擎亲见的环境起不来」是两种收场。
     *
     * <p>那一档（{@link #refusesIntegrationWithoutAnEnvironment}）要回滚；而这一档
     * <b>只停下等人</b>：实测里脚本拿一句 {@code BLOCKED} 盖住了自己的编译错误，
     * 旧实现无条件采信，整次回滚——编译通过的产品改动被撤掉、测试产物被删掉，
     * 用户两个都看不到。现在：产物留着、改动留在磁盘上等人处置。
     */
    @Test
    @DisplayName("脚本说跑不起来（BLOCKED）：不回滚、不删产物，只停下等人")
    void doesNotRollBackWhenTheScriptSaysBlocked() {
        // 三份同样的回复：跑不出来会**自动重试到上限**（这一批的第 6 条规则），
        // 每一版都花一次真实调用，所以桩也得按版数给
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                artifactsPatch(2, "BLOCKED | javac not found"),
                artifactsPatch(2, "BLOCKED | javac not found"),
                artifactsPatch(2, "BLOCKED | javac not found"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")), planWithCases());

        assertThat(result.status()).as("停在「测试没过」这一档：改动留着等人处置")
                .isEqualTo(AgentResult.Status.TESTS_FAILED);
        assertThat(read("Foo.java")).as("它说的话不足以把编译通过的产品改动撤掉")
                .contains("int a = 2;");
        assertThat(result.detail()).contains("javac not found").contains("脚本自己说它没跑起来");
        assertThat(root.resolve("tools")).as("产物留着：人要看得见它写成什么样").exists();
    }

    /**
     * <b>环境预热起不来不影响开发</b>（§18 的第 7 条）。
     *
     * <p>声明了环境、docker 却用不了时，旧行为是整次运行被拒（界面上 409），用户白等一轮。
     * 现在：异步预热起不来就只记一句「环境不可用」，这次跳过集成、单元回退宿主照跑，
     * 产品改动照旧留着等人处置——环境是这台机器的事，不是这次改动的结论。
     */
    @Test
    @DisplayName("环境起不来不影响开发：记一句「环境不可用」，跳过集成、单元回退宿主")
    void keepsGoingWhenTheEnvironmentCannotWarmUp() throws IOException {
        Path declaration = root.resolve(EnvConfigLoader.relativePath());
        Files.createDirectories(declaration.getParent());
        Files.writeString(declaration, """
                image: "x:1"
                """);
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        PlanReview approved = planWithCases();
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                artifactsPatch(0, "PASS | 1"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        // docker 探得到、但 up 那条命令没有桩（等于「这台机器上起不来」）：预热会在毫秒级失败
        FakeEnvironmentRunner runner = new FakeEnvironmentRunner("sf-whatever-app-1", false);

        AgentResult result = new DevelopmentAgent(root, ProjectConfig.DEFAULT,
                TemplateRegistry.empty(), llm, List.of(new ScriptedVerifier(passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP),
                new TestSettings(true), new TestEnvironment(root, runner)).run(spec, approved);

        assertThat(result.status()).as("环境没起来不是这次改动的结论").isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(read("Foo.java")).contains("int a = 2;");
        assertThat(result.verifications()).anySatisfy(verification ->
                assertThat(verification.command()).as("回退宿主这件事要写在结果里").contains("宿主"));
        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();
        assertThat(record.environment()).as("留档里记着「环境不可用」这一笔").isNotNull();
        assertThat(record.environment().detail())
                .contains("环境不可用").contains("跳过集成").contains("回退到宿主");
    }

    /**
     * 闸门：没有用例清单就不进测试阶段。
     * 这一条护的是「老用法一个字节都没变」——它也会在多花一次模型调用这件事上立刻露出来。
     */
    @Test
    @DisplayName("没有用例清单就不跑测试阶段，一次模型调用都不多花")
    void skipsTestPhaseWithoutCases() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")),
                        planWithSteps(step(1, "第一步", false, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(llm.calls()).as("只有开发那一轮").hasSize(1);
    }

    /**
     * <b>进测试之前再查一次停止</b>。
     *
     * <p>测试那一段最长五分钟，进去就停不下来（生成要一次模型调用，脚本执行有自己的时限）。
     * 用户刚按过停止却还要等五分钟，正是这一问要避免的事。它之前只有代码没有测试——
     * 而这条路上最容易犯的错是「查了但查错了地方」：查在生成之后，就等于没查
     * （用户要多等一次模型调用，而且产物已经落盘了）。
     */
    @Test
    @DisplayName("测试开始前查一次停止：不生成测试产物、不跑脚本，直接按中断收场")
    void stopsBeforeTheTestPhaseStarts() {
        // 第二份回复是测试产物：真进了测试阶段，它一定会被用掉
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                artifactsPatch(0, "PASS | 1"));
        CancelBeforeTestListener listener = new CancelBeforeTestListener();

        AgentResult result = new DevelopmentAgent(root, ProjectConfig.DEFAULT,
                TemplateRegistry.empty(), llm, List.of(new ScriptedVerifier(passed())), listener)
                .run(TestSpecs.spec(List.of("Foo.java")), planWithCases());

        assertThat(result.status()).isEqualTo(AgentResult.Status.CANCELLED);
        assertThat(llm.patches()).as("只跑了开发那一轮：测试那一次「生成产物」的调用没有发生")
                .hasSize(1);
        assertThat(listener.testsStarted).as("连「测试进行中」那一行都不该发").isZero();
        assertThat(root.resolve("tools")).as("一个字节的测试产物都不该落盘").doesNotExist();
        assertThat(read("Foo.java")).as("中断照旧回滚到运行前").isEqualTo(ORIGINAL);
    }

    // ---------- 测试环境（十五.5） ----------

    /**
     * 勾了集成测试却没有环境声明：在<b>测试阶段</b>当场停下，算环境问题，而且<b>不回滚</b>。
     *
     * <p>它不能算「测试代码写错了」——坏的不是模型写的东西，是这台机器上没有那套环境。
     * 分错档的代价不对称：判成测试代码问题，用户会去改一份本来就对的代码。
     *
     * <p>「不回滚」是用户 2026-10-02 拍板的那一条：硬判据（环境起不来 / 超时）同样<b>只停下</b>，
     * 改动留在磁盘上进「待处置」、测试产物留在 {@code tools/} 里，由他决定保留还是撤回。
     * 旧口径是机器替人把改动收掉，于是人手里什么都没剩——而机器判错方向的代价不对称。
     */
    @Test
    @DisplayName("勾了集成测试但项目没声明环境：按环境问题收场，改动未回滚、进「待处置」")
    void refusesIntegrationWithoutAnEnvironment() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                artifactsPatch(0, "PASS | 1"));
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        PlanReview approved = planWithCases();
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        AgentResult result = new DevelopmentAgent(root, ProjectConfig.DEFAULT,
                TemplateRegistry.empty(), llm, List.of(new ScriptedVerifier(passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP),
                new TestSettings(true), TestEnvironment.of(root)).run(spec, approved);

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_ENVIRONMENT);
        assertThat(result.detail())
                .contains("环境起不来").contains("env.yaml")
                .as("界面与留档读的都是这句话：它必须说清磁盘现在什么样")
                .contains("改动未回滚").contains("等你处置");
        assertThat(read("Foo.java")).as("硬判据只停下，不回滚")
                .contains("int a = 2;");
        assertThat(root.resolve("tools")).as("没进到生成那一步，产物目录一个都不该有")
                .doesNotExist();
        // 「待处置」不是一句口号：快照改了名，界面上那枚「接受 / 中断」才有东西可依
        assertThat(WorkspaceSnapshot.undisposed(new SafePathResolver(root),
                root.resolve(SnapshotConfig.DEFAULT_DIR)))
                .as("改动进了「待处置」：用户能保留也能撤回").hasSize(1);
        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();
        assertThat(record.status()).isEqualTo("NEEDS_ENVIRONMENT");
        assertThat(record.detail()).as("留档里同样写着「未回滚」——事后翻记录的人只有它")
                .contains("环境起不来").contains("改动未回滚");
    }

    /**
     * 环境就绪之后：先 reset，再把连接信息喂给测试代码，然后才跑集成入口。
     *
     * <p>这条链上最容易漏的是「连接信息没送进去」——那样模型只能猜，而猜出来的连接串
     * 在换一台机器时全错（十五.5：它永远不用猜）。
     *
     * <p>这套假环境的容器名<b>故意不是这个 compose 项目的</b>：这样执行位置回退宿主
     * （脚本会真的在本机跑一遍，拿到 PASS | 1），而「真的在容器里跑」由
     * {@code RealDockerEnvironmentTest} 用真容器验。桩报「就绪」的话，引擎会把脚本
     * 包成 {@code docker compose exec} 去执行，而这一步没有可注入的桩——那验的就不是
     * 这一条的事了。
     */
    @Test
    @DisplayName("集成测试：先重置数据，再把连接信息交给测试代码，脚本按集成入口跑")
    void runsIntegrationAfterResettingTheEnvironment() throws IOException {
        // 一套假的环境：探得到 docker、up 起得来、已经有 compose 文件、重置命令成功
        Path envDir = root.resolve(ComposeFile.ROOT).resolve("20260930-120000");
        Files.createDirectories(envDir);
        Files.writeString(envDir.resolve(ComposeFile.NAME), "services: {}\n");
        Path declaration = root.resolve(EnvConfigLoader.relativePath());
        Files.createDirectories(declaration.getParent());
        Files.writeString(declaration, """
                image: "eclipse-temurin:17"
                env:
                  DB_HOST: "db"
                reset:
                  - "python -m clean-db"
                """);
        FakeEnvironmentRunner runner = new FakeEnvironmentRunner("sf-not-this-project-app-1");

        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                integrationArtifactsPatch(0, "PASS | 1"));

        AgentResult result = new DevelopmentAgent(root, ProjectConfig.DEFAULT,
                TemplateRegistry.empty(), llm, List.of(new ScriptedVerifier(passed())),
                AgentListener.NOOP, new TestSettings(true),
                new TestEnvironment(root, runner))
                .run(TestSpecs.spec(List.of("Foo.java")), planWithCases());

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(runner.ran("exec -T app sh -c python -m clean-db"))
                .as("每次跑之前都要重置数据，不能省").isTrue();
        assertThat(llm.system()).as("连接信息原样进提示词：它永远不用猜")
                .contains("DB_HOST").contains("db");
        // 集成入口脚本确实被跑了（假模型按协议写了它，引擎要认那个文件）
        assertThat(result.verifications()).anySatisfy(verification ->
                assertThat(verification.verifier()).isEqualTo("测试脚本"));
    }

    /**
     * 留档是这一批唯一的「事后可查」的地方：代码可能已经被改回去，失败清单不会。
     * 所以用例清单与失败清单都要落在同一条记录里——只记失败的那几条，分母就没了。
     */
    @Test
    @DisplayName("用例清单与失败清单都进运行留档")
    void recordsTestOutcomeIntoTheRunArchive() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        PlanReview approved = planWithCases();
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                artifactsPatch(1, "FAIL | 1 | a == 2 | a == 1 | code is wrong"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        new DevelopmentAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(), llm,
                List.of(new ScriptedVerifier(passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP)).run(spec, approved);

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();

        assertThat(record.status()).isEqualTo("TESTS_FAILED");
        assertThat(record.testCases()).as("用例清单是分母，失败清单是分子").hasSize(1);
        assertThat(record.tests()).isNotNull();
        assertThat(record.tests().failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.ASSERTION);
            assertThat(failure.testCase()).isEqualTo("1");
            assertThat(failure.expected()).isEqualTo("a == 2");
            assertThat(failure.actual()).isEqualTo("a == 1");
        });
        assertThat(record.tests().directory()).startsWith("tools/");
        assertThat(record.timeline()).as("时间线上也要有一行，翻记录的人才知道跑过测试")
                .anySatisfy(line -> assertThat(line.text()).contains("测试没过"));
    }

    // ---------- 施工单：按步循环 ----------

    @Test
    @DisplayName("有施工单时按步走：每一步先给一条施工指令，说清只改这一步涉及的文件")
    void feedsStepInstructionBeforeEachStep() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed())).run(
                TestSpecs.spec(List.of("Foo.java")),
                planWithSteps(step(1, "先把 a 改成 2", false, "Foo.java"),
                        step(2, "再把 a 改成 3", false, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.attempts()).isEqualTo(2);
        // 第二步的 SEARCH 锚点是「第一步改完之后」的内容：步与步之间不回滚
        assertThat(read("Foo.java")).contains("int a = 3;");

        List<ChatMessage> secondStep = llm.patches().get(1);
        String instruction = secondStep.get(secondStep.size() - 1).content();
        assertThat(instruction).contains("本步施工指令").contains("第 2 步")
                .contains("再把 a 改成 3");
        assertThat(instruction).as("必须说清只改这一步的文件，否则它会把后面几步一起做掉")
                .contains("只改上面这些文件").contains("Foo.java");

        // 第一条消息里要给全整份施工单：只给当前这一步，它不知道自己在整条链上的位置
        String firstMessage = llm.patches().get(0).get(1).content();
        assertThat(firstMessage).contains("施工单").contains("先把 a 改成 2").contains("再把 a 改成 3");
    }

    @Test
    @DisplayName("施工单是先给全的，然后逐步报开始/结束")
    void reportsStepProgressToListener() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        RecordingListener listener = new RecordingListener();

        agent(llm, new ScriptedVerifier(passed()), listener).run(
                TestSpecs.spec(List.of("Foo.java")),
                planWithSteps(step(1, "第一步", false, "Foo.java"), step(2, "第二步", false, "Foo.java")));

        assertThat(listener.events).containsExactly(
                "plan:APPROVED:2",
                "step:1", "round:1", "applied:1", "verified:1", "stepdone:1:SUCCESS",
                "step:2", "round:2", "applied:2", "verified:2", "stepdone:2:SUCCESS",
                "finished:SUCCESS");
        assertThat(listener.plan).extracting(PlanStep::goal).containsExactly("第一步", "第二步");
    }

    @Test
    @DisplayName("某一步编译失败：回滚到这一步的进入点再重试，前面几步的成果留着")
    void rollsBackToStepEntryPointAndRetries() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"),
                // 第三步的锚点是「第二步开始前」的内容：只有真回滚了才找得到它
                patch("int a = 2;", "int a = 4;"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed(), failed(), passed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java")),
                        planWithSteps(step(1, "第一步", false, "Foo.java"),
                                step(2, "第二步", false, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(read("Foo.java")).contains("int a = 4;");
        assertThat(listener.events).as("本步回滚说的是「回到该步开始前」，不是整轮")
                .contains("stepback:2");
    }

    @Test
    @DisplayName("某一步重试耗尽：整个运行回滚到起点，前面几步的成果一并撤掉")
    void rollsBackWholeRunWhenAStepGivesUp() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed(), failed()), listener).run(
                TestSpecs.spec(List.of("Foo.java"),
                        new VerifySpec(true, null, 0, VerifySpec.AUTO_ROUNDS)),
                planWithSteps(step(1, "第一步", false, "Foo.java"),
                        step(2, "第二步", false, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.detail()).contains("第 2 步");
        assertThat(read("Foo.java")).as("第一步已经做成的也要撤掉——半成品比全撤更难收拾")
                .isEqualTo(ORIGINAL);
        assertThat(snapshotNames()).isEmpty();
        // 失败的那一步必须有自己的终态。不发的话界面上的步骤条永远停在「进行中」，
        // 留档里也找不到它是第几步倒下的——而「停在哪一步」正是用户最需要看见的事
        assertThat(listener.events).endsWith("stepdone:2:FAILED", "finished:FAILED");
    }

    /**
     * 分步时失败收场，<b>正在跑的那一步</b>要报失败。
     *
     * <p>引擎原先只在成功和中间态时报终态，其余收场都直接返回了：步骤条于是永远停在
     * 「进行中」，留档里也没有失败的那一步。这条把五种收场各钉一遍——它们走的是不同的
     * return 语句，只钉一种的话，另外四种照样能漏。
     */
    @Test
    @DisplayName("分步时人工中断：正在跑的那一步报失败，不是停在「进行中」")
    void marksTheStepFailedWhenCancelledMidStep() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        CancelAfterRoundListener listener = new CancelAfterRoundListener();

        AgentResult result = agent(llm, new ScriptedVerifier(failed()), listener).run(
                TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6, VerifySpec.AUTO_ROUNDS)),
                planWithSteps(step(1, "第一步", false, "Foo.java"),
                        step(2, "第二步", false, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.CANCELLED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(read("Foo.java")).as("已经做完的第一步也要撤掉").isEqualTo(ORIGINAL);
        assertThat(listener.events).as("第 2 步一次都没开始，它不该出现在事件里")
                .containsExactly("step:1", "stepdone:1:FAILED");
    }

    @Test
    @DisplayName("分步时模型喊缺料：正在跑的那一步报失败，前面几步的改动一并撤回")
    void marksTheStepFailedWhenTheModelAsksForContext() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                "NEED_CONTEXT: 需要 src/main/java/com/demo/Bar.java 的现有写法")
                .answeringStepsWith(stepsAnswer(2, "第一步", "第二步"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_CONTEXT);
        assertThat(result.detail()).contains("已把前面几步的改动一并撤回");
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
        assertThat(listener.events).containsExactly(
                "plan:GENERATED:2",
                "step:1", "round:1", "applied:1", "verified:1", "stepdone:1:SUCCESS",
                "step:2", "round:2", "restored:2", "stepdone:2:FAILED",
                "finished:NEEDS_CONTEXT");
    }

    /**
     * 失败的那一步在留档里也要在。
     *
     * <p>界面上的步骤条靠事件，事后翻记录的人只有留档。少了这一条，
     * 「这次停在第几步、它是怎么倒下的」在历史详情里就完全看不出来。
     */
    @Test
    @DisplayName("分步失败也留档：失败的那一步带着它的状态和轮次记下来")
    void recordsTheFailedStepInTheRunHistory() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        Spec spec = TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 0, VerifySpec.AUTO_ROUNDS));
        PlanReview approved = planWithSteps(step(1, "第一步", false, "Foo.java"),
                step(2, "第二步", false, "Foo.java"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        new DevelopmentAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(), llm,
                List.of(new ScriptedVerifier(passed(), failed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP)).run(spec, approved);

        RunRecord record = store.load(store.list().get(0).id());
        assertThat(record.steps()).as("成功一步、失败一步，两步都在").hasSize(2);
        assertThat(record.steps().get(0).state()).isEqualTo(StepState.SUCCESS.name());
        assertThat(record.steps().get(1)).satisfies(failed -> {
            assertThat(failed.index()).isEqualTo(2);
            assertThat(failed.state()).as("留档里要看得见它是哪一步倒下的")
                    .isEqualTo(StepState.FAILED.name());
            assertThat(failed.rounds()).isEqualTo(1);
        });
    }

    /**
     * 已确认方案那一档不认模型喊的「缺料」。
     *
     * <p>它按普通回答处理：补丁解析回喂一句「没有任何补丁块」，它要么改口给出补丁，
     * 要么在重试上限上用失败收场。原先认了它就会挂起等人再点一次「直接继续」——
     * 而用户刚刚明确说过没有更多材料了。两条路都不再原地打转。
     */
    @Test
    @DisplayName("方案已确认时不认「缺料」这个出口：回喂之后接着做，不挂起")
    void ignoresTheNeedContextExitWhenThePlanIsConfirmed() {
        ScriptedLlm llm = new ScriptedLlm(
                "NEED_CONTEXT: 我还需要 src/main/java/com/demo/Bar.java",
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener).run(
                TestSpecs.spec(List.of("Foo.java")),
                planWithSteps(step(1, "第一步", false, "Foo.java"),
                        step(2, "第二步", false, "Foo.java")));

        assertThat(result.status()).as("不挂起：用户刚说过没有更多材料了")
                .isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.attempts()).as("那一轮算一轮，不是白跑").isEqualTo(3);
        assertThat(listener.events).as("没有挂起这回事")
                .doesNotContain("finished:NEEDS_CONTEXT", "stepdone:1:FAILED");
        List<ChatMessage> afterIgnoring = llm.patches().get(1);
        assertThat(afterIgnoring.get(afterIgnoring.size() - 1).content())
                .as("它那句话被当成普通回答：回喂的是补丁协议那一条")
                .contains("补丁无法应用").contains("没有任何改动");
        assertThat(read("Foo.java")).contains("int a = 3;");
    }

    /**
     * 与上一条同一档、另一条入口：用户按了「直接放行」的续跑同样不认那个出口。
     *
     * <p>两条入口（已确认的方案 / force 续跑）共用一个 {@code noNeedContext}，
     * 分开钉是因为提示词那两档也各有各的测法——只测一条，另一条断了不会有任何东西变红。
     */
    @Test
    @DisplayName("「直接放行」的续跑也不认那个出口：照样往下做")
    void ignoresTheNeedContextExitWhenResumedWithForce() {
        ScriptedLlm llm = new ScriptedLlm(
                "NEED_CONTEXT: 我还是缺 src/main/java/com/demo/Bar.java",
                patch("int a = 1;", "int a = 2;"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .resume(TestSpecs.spec(List.of("Foo.java")), null,
                        "NEED_CONTEXT: 缺东西", true, List.of());

        assertThat(result.status()).as("不因为模型再喊一次就挂起")
                .isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(listener.events).doesNotContain("finished:NEEDS_CONTEXT");
    }

    @Test
    @DisplayName("声明为中间态的那一步：编译没过也不阻塞，继续下一步")
    void doesNotBlockOnIntermediateStep() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(failed(), passed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java")),
                        planWithSteps(step(1, "先加接口（这一步编不过）", true, "Foo.java"),
                                step(2, "接上实现", false, "Foo.java")));

        assertThat(result.status()).as("最后一步编译过了，整件事就是成了")
                .isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.attempts()).as("中间态那一步不重试：缺的是下一步的代码，不是修补")
                .isEqualTo(2);
        assertThat(listener.events).contains("stepdone:1:INTERMEDIATE", "stepdone:2:SUCCESS");
        assertThat(read("Foo.java")).as("中间态的改动要留着，下一步接着它做")
                .contains("int a = 3;");
        // 得先告诉它「这一步编不过没事」，否则它会为了让项目编过而乱改别处
        assertThat(llm.patches().get(0).get(2).content()).contains("中间态");
    }

    @Test
    @DisplayName("最后一步是中间态且编译没过：不能算成功——施工单跑完本该能编译")
    void refusesSuccessWhenLastStepStaysBroken() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed(), failed())).run(
                TestSpecs.spec(List.of("Foo.java")),
                planWithSteps(step(1, "第一步", false, "Foo.java"),
                        step(2, "最后一步（人硬标的中间态）", true, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.detail()).contains("中间态").contains("编不过");
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("中断在步与步之间也生效：停下、回滚整个运行，而且不假装开始了下一步")
    void stopsBetweenSteps() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        CancelAfterStepListener listener = new CancelAfterStepListener(1);

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java")),
                        planWithSteps(step(1, "第一步", false, "Foo.java"),
                                step(2, "第二步", false, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.CANCELLED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(llm.patches()).as("第二步一次都没开始").hasSize(1);
        assertThat(read("Foo.java")).as("已经做完的第一步也要撤掉").isEqualTo(ORIGINAL);
        // 停在这一步和下一步之间，不能先报「第 2 步开始」再停——那界面会闪出一个
        // 根本没跑过的步骤，人还以为它做了点什么
        assertThat(listener.events).as("只该看到第 1 步").containsExactly("step:1", "stepdone:1");
    }

    @Test
    @DisplayName("总轮次用尽：整轮回滚，并说清上限是多少")
    void stopsWhenTheTotalRoundBudgetRunsOut() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 1;", "int a = 3;"));
        // 两轮错误不一样，所以「同一个错误连着两轮」那条不会先把它拦下
        ScriptedVerifier verifier = new ScriptedVerifier(
                VerificationResult.failed("脚本校验", "javac", "编译错误：找不到符号 a"),
                VerificationResult.failed("脚本校验", "javac", "编译错误：找不到符号 b"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, verifier, listener).run(
                TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6, 2)),
                planWithSteps(step(1, "第一步", false, "Foo.java"),
                        step(2, "第二步", false, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.detail()).contains("总轮次已用尽").contains("上限 2 轮");
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
        // 两轮都花在第 1 步里，闸门是在它的下一轮之前落下的——那一步因此得报失败
        assertThat(listener.events).contains("stepback:1")
                .endsWith("stepdone:1:FAILED", "finished:FAILED");
    }

    @Test
    @DisplayName("检查阶段给过施工单就直接用，一次都不多问")
    void usesApprovedPlanWithoutAskingAgain() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        RecordingListener listener = new RecordingListener();

        agent(llm, new ScriptedVerifier(passed()), listener).run(
                TestSpecs.spec(List.of("Foo.java")),
                planWithSteps(step(1, "第一步", false, "Foo.java"), step(2, "第二步", false, "Foo.java")));

        assertThat(listener.source).isEqualTo(StepsSource.APPROVED);
        assertThat(listener.probeCalls).isZero();
        assertThat(llm.calls()).as("没有任何多余的调用").hasSize(2);
    }

    /**
     * 有已确认的方案时，提示词里不能留「缺料就认输」那个出口。
     *
     * <p>这一条盯的是两处：系统提示词里那一段的<b>存在与否</b>，和回给模型的话里
     * <b>有没有明说</b>「不要再要求补充信息」。只删提示词里那一段而话里不说，
     * 模型照样会按它见过的别的提示词行事；反过来只说不删，等于一边递梯子一边说别爬。
     */
    @Test
    @DisplayName("检查过、方案已确认：提示词里没有 NEED_CONTEXT 那一段，话里明说不要再要求补充信息")
    void approvedPlanDropsTheNeedContextOutlet() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));

        agent(llm, new ScriptedVerifier(passed()), new RecordingListener()).run(
                TestSpecs.spec(List.of("Foo.java")),
                planWithSteps(step(1, "第一步", false, "Foo.java"), step(2, "第二步", false, "Foo.java")));

        List<ChatMessage> first = llm.patches().get(0);
        assertThat(first.get(0).role()).isEqualTo(ChatMessage.SYSTEM);
        assertThat(first.get(0).content()).as("协议照旧，但那个出口没了")
                .contains("<<<<<<< SEARCH")
                .doesNotContain("NEED_CONTEXT")
                .doesNotContain("信息不足");
        assertThat(first.get(1).content()).as("已确认方案那一段明说别再要东西")
                .contains("不要再要求补充信息")
                .doesNotContain("信息确实不足");
        assertThat(first.get(first.size() - 1).content()).as("每一步开工前那条施工指令也说一遍")
                .contains("按施工单做，不要再要求补充信息");
    }

    /**
     * 与上一条相反的方向：没检查过时那个出口必须还在。
     *
     * <p>现生成施工单和单步执行是两条不同的路，两条都要留——否则模型缺料时只能猜，
     * 而猜错的代价是整轮白跑。
     */
    @Test
    @DisplayName("没检查过、施工单是开工前现生成的：仍然留着 NEED_CONTEXT 出口")
    void keepsTheNeedContextOutletWhenStepsAreGenerated() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"),
                patch("int a = 3;", "int a = 4;"))
                .answeringStepsWith(stepsAnswer(3, "第一步", "第二步", "第三步"));

        agent(llm, new ScriptedVerifier(passed()), new RecordingListener())
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(llm.patches().get(0).get(0).content())
                .as("现生成施工单那条路：出口还在").contains("NEED_CONTEXT");
        assertThat(lastUserMessage(llm)).as("而且不叮嘱它「不要要求补充信息」")
                .doesNotContain("不要再要求补充信息");
    }

    @Test
    @DisplayName("没检查过、连施工单都没拿到（单步执行）：出口也还在")
    void keepsTheNeedContextOutletWhenFallingBackToSingleStep() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        agent(llm, new ScriptedVerifier(passed()), new RecordingListener())
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(llm.patches().get(0).get(0).content())
                .as("单步执行那条路：出口也还在").contains("NEED_CONTEXT");
        assertThat(lastUserMessage(llm)).doesNotContain("不要再要求补充信息");
    }

    /**
     * 续跑那条路也归这一条管：有已确认方案时不再说「仍然缺就说出来」。
     *
     * <p>不说的话，系统提示词里刚拆掉的出口会被这句叮嘱原地搭回来——
     * 而那一次续跑的用户已经点过确认，他不会再补料。
     */
    @Test
    @DisplayName("有已确认方案时续跑也不递梯子：不写「仍然缺就说出来」那一句")
    void resumeWithApprovedPlanKeepsTellingItNotToAsk() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));

        agent(llm, new ScriptedVerifier(passed()), new RecordingListener()).resume(
                TestSpecs.spec(List.of("Foo.java")),
                planWithSteps(step(1, "第一步", false, "Foo.java"), step(2, "第二步", false, "Foo.java")),
                "NEED_CONTEXT: 缺东西", false, List.of());

        List<ChatMessage> sent = llm.patches().get(0);
        assertThat(sent.get(0).content()).doesNotContain("NEED_CONTEXT");
        assertThat(sent.get(3).content()).as("该强硬的时候没有软下来")
                .contains("不要再要求补充信息")
                .doesNotContain("最多 3 行");
    }

    @Test
    @DisplayName("没跑过检查：开工前现生成施工单，来源记为 GENERATED，花掉的调用不算轮次")
    void generatesStepsWhenThereIsNoPlan() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"),
                patch("int a = 3;", "int a = 4;"))
                .answeringStepsWith(stepsAnswer(3, "第一步", "第二步", "第三步"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.attempts()).as("生成施工单那一次不算轮次").isEqualTo(3);
        assertThat(listener.source).isEqualTo(StepsSource.GENERATED);
        assertThat(listener.probeCalls).isEqualTo(1);
        assertThat(listener.plan).extracting(PlanStep::index).containsExactly(1, 2, 3);
        assertThat(read("Foo.java")).contains("int a = 4;");
    }

    @Test
    @DisplayName("现生成的施工单核不过时带着机器的意见再要一次；两次都不行就按单步跑，不卡人")
    void fallsBackToSingleStepWhenGenerationKeepsFailing() {
        // 这份单子核不过的地方是「要动清单外的 Bar.java」——清单是硬白名单，那一步做不了。
        // 步数特意给 2 步：步数已经不是核不过的理由了（下限降到 2），
        // 拿它当失败原因的话，这条用例会在「1 步也放行」之后悄悄失去意义
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"))
                .answeringStepsWith("<<<<<<< STEPS\n"
                        + "1 | 先动清单外的那个类 | Bar.java | 能编译 | 自洽\n"
                        + "2 | 再把 a 改成 2 | Foo.java | 能编译 | 自洽\n"
                        + ">>>>>>> STEPS\n");
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).as("拿不到施工单也要把活干完").isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(listener.source).isEqualTo(StepsSource.SINGLE);
        assertThat(listener.probeCalls).as("第一次加带着意见的第二次").isEqualTo(2);
        assertThat(listener.plan).as("退化成一个虚拟步骤，好让循环只有一条路").hasSize(1);
        assertThat(listener.events).as("单步不发步级事件——那正是老行为")
                .doesNotContain("step:1", "stepdone:1:SUCCESS");
        // 第二次要单时把「为什么这份用不了」说清了，而不是重掷骰子
        String retry = llm.calls().get(1).get(3).content();
        assertThat(retry).contains("核不过").contains("不在目标文件清单里");
        assertThat(retry).as("步数已经不该再被当成问题").doesNotContain("少于");
    }

    /**
     * 只有 1 步不算核不过：这件事本来就不用拆，引擎按单步把它跑完。
     *
     * <p>这条是「下限 3 → 2」那个改动的落点：真模型试跑里一个简单需求被切成 2 步（合理），
     * 却撞上「3～7」的下限被拦了一次；而 1 步更不该拦——拦它只会逼模型为了凑步数
     * 把一件完整的事硬切开。
     */
    @Test
    @DisplayName("只有一步：不重问、不拦人，就按单步跑完")
    void acceptsASingleGeneratedStep() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"))
                .answeringStepsWith("<<<<<<< STEPS\n1 | 一步搞定 | Foo.java | 能编译 | 自洽\n>>>>>>> STEPS\n");
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(listener.probeCalls).as("第一步就收下了，没有第二次要单").isEqualTo(1);
        assertThat(listener.source).as("它是模型给的那一步，不是「拿不到施工单」那个虚拟步")
                .isEqualTo(StepsSource.GENERATED);
        assertThat(listener.plan).singleElement()
                .satisfies(step -> assertThat(step.goal()).isEqualTo("一步搞定"));
        assertThat(listener.events).as("按单步跑：不发步级事件").doesNotContain("step:1", "stepdone:1:SUCCESS");
        assertThat(result.attempts()).as("只有一步要跑，一轮就够").isEqualTo(1);
    }

    @Test
    @DisplayName("按步留档：每一步的目标、状态、用了几轮、改了什么都记下来")
    void recordsEachStepInTheRunHistory() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        PlanReview approved = planWithSteps(step(1, "先加接口", true, "Foo.java"),
                step(2, "接上实现", false, "Foo.java"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, spec, approved, AgentListener.NOOP);

        new DevelopmentAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(), llm,
                List.of(new ScriptedVerifier(failed(), passed())), recorder).run(spec, approved);

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();
        assertThat(record.stepsSource()).isEqualTo(StepsSource.APPROVED.name());
        assertThat(record.steps()).hasSize(2);
        assertThat(record.steps().get(0)).satisfies(first -> {
            assertThat(first.index()).isEqualTo(1);
            assertThat(first.goal()).isEqualTo("先加接口");
            assertThat(first.intermediate()).isTrue();
            assertThat(first.state()).isEqualTo(StepState.INTERMEDIATE.name());
            assertThat(first.rounds()).isEqualTo(1);
            assertThat(first.changes()).as("中间态的改动留在盘上，所以记在它名下")
                    .singleElement().satisfies(change -> assertThat(change.path()).isEqualTo("Foo.java"));
        });
        assertThat(record.steps().get(1)).satisfies(second -> {
            assertThat(second.index()).isEqualTo(2);
            assertThat(second.state()).isEqualTo(StepState.SUCCESS.name());
            assertThat(second.rounds()).isEqualTo(1);
            assertThat(second.changes()).singleElement();
        });
    }

    @Test
    @DisplayName("标了中间态却编过了：时间线上记一笔，因为那说明这步切得比必要的还碎")
    void notesWhenAnIntermediateStepCompilesAnyway() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        PlanReview approved = planWithSteps(step(1, "先加接口", true, "Foo.java"),
                step(2, "接上实现", false, "Foo.java"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        new DevelopmentAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(), llm,
                List.of(new ScriptedVerifier(passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP)).run(spec, approved);

        RunRecord record = store.load(store.list().get(0).id());
        assertThat(record.steps().get(0).state()).isEqualTo(StepState.SUCCESS.name());
        assertThat(record.timeline()).extracting(RunRecord.Line::text)
                .anySatisfy(text -> assertThat(text).contains("标的是中间态").contains("实际编译通过"));
    }

    @Test
    @DisplayName("步与步之间失败的那一轮改动不算数：记账要跟着回滚一起抹掉")
    void stepChangesAreForgottenWhenTheStepRolledBack() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"),
                patch("int a = 2;", "int a = 4;"));
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        PlanReview approved = planWithSteps(step(1, "第一步", false, "Foo.java"),
                step(2, "第二步", false, "Foo.java"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        new DevelopmentAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(), llm,
                List.of(new ScriptedVerifier(passed(), failed(), passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP)).run(spec, approved);

        RunRecord record = store.load(store.list().get(0).id());
        RunRecord.Step second = record.steps().get(1);
        assertThat(second.rounds()).as("第二步试了两次").isEqualTo(2);
        assertThat(second.changes()).as("第一次那版已经回滚，不该出现在留档里")
                .singleElement().satisfies(change -> assertThat(change.diff()).contains("int a = 4;"));
    }

    @Test
    @DisplayName("一轮多步：留档里的改动是**所有步**并起来的那一份，不是最后一步")
    void keepsEveryStepsChangesAsTheRoundsChanges() throws IOException {
        // 一轮两步、一步新建一个文件——实测里正是这一种漏掉了第一步新建的那个类：
        // 第一步建 TextStats.java、第二步建 Label.java，留档与轮次间 diff 只剩后者（§19.13）
        ScriptedLlm llm = new ScriptedLlm(
                create("New.java", "class New {\n    int n = 1;\n}"),
                create("Other.java", "class Other {\n    int o = 2;\n}"));
        Spec spec = TestSpecs.spec(List.of("New.java", "Other.java"));
        PlanReview approved = planWithSteps(step(1, "先建 New", false, "New.java"),
                step(2, "再建 Other", false, "Other.java"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        AgentResult result = new DevelopmentAgent(root, ProjectConfig.DEFAULT,
                TemplateRegistry.empty(), llm, List.of(new ScriptedVerifier(passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP)).run(spec, approved);

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.changes()).extracting(PatchApplier.FileChange::relative)
                .as("运行结果里就该是两步的改动").containsExactly("New.java", "Other.java");

        RunRecord record = store.load(store.list().get(0).id());
        assertThat(record.changes()).extracting(RunRecord.Change::path)
                .as("留档里也要全：界面上的轮次间 diff 读的就是它，丢一半会让人误判这一轮做了什么")
                .containsExactly("New.java", "Other.java");
        assertThat(record.changes()).allSatisfy(change ->
                assertThat(change.created()).as("两个都是这一步新建的").isTrue());
        assertThat(record.steps()).extracting(RunRecord.Step::index)
                .as("两步各自的账照旧").containsExactly(1, 2);
        assertThat(record.steps().get(0).changes()).extracting(RunRecord.Change::path)
                .as("每一步自己那一份不动：哪一步写了哪一版，翻 steps 查得到")
                .containsExactly("New.java");
        assertThat(record.steps().get(1).changes()).extracting(RunRecord.Change::path)
                .containsExactly("Other.java");
    }

    /**
     * 两步碰同一个文件时并成<b>一条</b>，不是两条。
     *
     * <p>两条的话，界面上的「改了哪些文件」会把同一个文件说两遍、diff 画两遍；
     * 而合并后只剩「最后那一刻的样子」也不要紧——每一步各自那一版逐字留在
     * {@code steps[].changes} 里，两边合起来才是完整且可追溯的那份账。
     */
    @Test
    @DisplayName("两步碰同一个文件：并成一条，「新建」只要本轮建过一次就算新建")
    void mergesTheSameFileAcrossSteps() {
        ScriptedLlm llm = new ScriptedLlm(
                create("New.java", "class New {\n    int n = 1;\n}"),
                patch("New.java", "    int n = 1;", "    int n = 2;"));
        Spec spec = TestSpecs.spec(List.of("New.java"));
        PlanReview approved = planWithSteps(step(1, "先建出来", false, "New.java"),
                step(2, "再改一处", false, "New.java"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        AgentResult result = new DevelopmentAgent(root, ProjectConfig.DEFAULT,
                TemplateRegistry.empty(), llm, List.of(new ScriptedVerifier(passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP)).run(spec, approved);

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.relative()).isEqualTo("New.java");
            assertThat(change.created())
                    .as("本轮新建过它：后面那一步是「修改」，但这一轮对这个文件而言就是新建").isTrue();
            assertThat(change.diff()).as("差异用最后那一版的").contains("int n = 2;");
        });
        RunRecord record = store.load(store.list().get(0).id());
        assertThat(record.changes()).extracting(RunRecord.Change::path)
                .as("并成一条，不重复").containsExactly("New.java");
        assertThat(record.steps()).hasSize(2);
        assertThat(record.steps().get(0).changes()).extracting(RunRecord.Change::diff)
                .as("第一步新建那一版照旧留在它自己名下").singleElement()
                .satisfies(diff -> assertThat(diff).contains("int n = 1;"));
    }

    /**
     * 步内重试前被回滚掉的那一版，<b>不许留在本轮改动里</b>。
     *
     * <p>它和 {@link #stepChangesAreForgottenWhenTheStepRolledBack} 是同一件事的两半：
     * 那一半管的是步级账（{@code steps[].changes}），这一半管的是本轮这份并集。
     * 只在一边抹掉，留档里就会出现「这一轮改了 New.java」而磁盘上根本没有它——
     * 而这一栏正是用户事后判断「我的代码现在是什么样」的依据。
     */
    @Test
    @DisplayName("步内重试回滚掉的那一版，本轮改动里也不许留着")
    void forgetsRolledBackAttemptsFromTheRoundsChanges() {
        // 第一步改 Foo（过）→ 第二步第一版新建 New.java（校验没过、回滚），重试那一版改建 Other.java
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                create("New.java", "class New {\n    int n = 1;\n}"),
                create("Other.java", "class Other {\n    int o = 2;\n}"));
        Spec spec = TestSpecs.spec(List.of("Foo.java", "New.java", "Other.java"));
        PlanReview approved = planWithSteps(step(1, "先改 Foo", false, "Foo.java"),
                step(2, "再建一个", false, "New.java"));
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        AgentResult result = new DevelopmentAgent(root, ProjectConfig.DEFAULT,
                TemplateRegistry.empty(), llm, List.of(new ScriptedVerifier(passed(), failed(), passed())),
                RunRecorder.start(store, spec, approved, AgentListener.NOOP)).run(spec, approved);

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(result.changes()).extracting(PatchApplier.FileChange::relative)
                .as("第一步的改动留着；被回滚掉的那一版不许进来")
                .containsExactly("Foo.java", "Other.java");
        assertThat(root.resolve("New.java")).as("回滚是实的：那个文件确实没了").doesNotExist();
    }

    @Test
    @DisplayName("回滚按目标清单全部文件做，不只是这一步声明过的那些")
    void rollsBackFilesTheStepNeverDeclared() throws IOException {
        Files.writeString(root.resolve("Bar.java"), BAR_ORIGINAL);
        ScriptedLlm llm = new ScriptedLlm(
                patch("Foo.java", "int a = 1;", "int a = 2;"),
                // 第 2 步嘴上只说动 Foo，手上把 Bar 也改了；这一轮会编译失败
                patch("Foo.java", "int a = 2;", "int a = 3;") + patch("Bar.java", "class Bar {", "class Bar { int b;"),
                patch("Foo.java", "int a = 2;", "int a = 5;"));
        // 施工单上第 2 步只声明了 Foo.java
        PlanReview approved = planWithSteps(step(1, "第一步", false, "Foo.java"),
                step(2, "第二步（只声明动 Foo）", false, "Foo.java"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed(), failed(), passed()))
                .run(TestSpecs.spec(List.of("Foo.java", "Bar.java")), approved);

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(read("Foo.java")).contains("int a = 5;");
        // 进入点如果只按「这一步声明的文件」拍，Bar 就回不去了——
        // 而回滚不全，后面每一轮的锚点都会对不上
        assertThat(read("Bar.java")).isEqualTo(BAR_ORIGINAL);
    }

    // ---------- 辅助 ----------

    @Test
    @DisplayName("成功之后留下待处置的快照，不再自动删掉")
    void keepsSnapshotWaitingForDecisionAfterSuccess() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(snapshotNames()).hasSize(1);
        assertThat(snapshotNames().get(0)).endsWith(WorkspaceSnapshot.PENDING_SUFFIX);
    }

    @Test
    @DisplayName("上一次的改动还没处置时拒绝开工：模型不调、磁盘不碰")
    void refusesToStartWhilePreviousChangeUndisposed() {
        ScriptedLlm first = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));
        agent(first, new ScriptedVerifier(passed())).run(TestSpecs.spec(List.of("Foo.java")));

        ScriptedLlm second = new ScriptedLlm(patch("int a = 2;", "int a = 3;"));
        AgentResult result = agent(second, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.PENDING_DECISION);
        assertThat(result.attempts()).isZero();
        assertThat(second.calls()).isEmpty();
        assertThat(read("Foo.java")).contains("int a = 2;");
    }

    @Test
    @DisplayName("校验失败回滚之后删掉本轮快照，不在磁盘上留下作废的档案")
    void removesSnapshotAfterRollback() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        AgentResult result = agent(llm, new ScriptedVerifier(failed()))
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 0, VerifySpec.AUTO_ROUNDS)));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
        assertThat(snapshotNames()).isEmpty();
    }

    @Test
    @DisplayName("续跑：不重发上下文，只补「它说过什么」和「接下来怎么办」")
    void resumesWithoutResendingContext() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .resume(TestSpecs.spec(List.of("Foo.java")), null, "NEED_CONTEXT: 我需要 Bar.java",
                        false, List.of());

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        List<ChatMessage> sent = llm.patches().get(0);
        assertThat(sent).as("system + user + 它说过的话 + 接着跑这一句，就这四条").hasSize(4);
        assertThat(sent.get(2).role()).isEqualTo(ChatMessage.ASSISTANT);
        assertThat(sent.get(2).content()).contains("我需要 Bar.java");
        assertThat(sent.get(3).content()).contains("最多 3 行");
        // 补过料那一档：用户还可能再补（他刚补过一次），所以出口留着——这和「直接放行」是两档
        assertThat(sent.get(0).content()).as("补过料的续跑仍然留着那个出口")
                .contains("NEED_CONTEXT");
    }

    /**
     * 「直接放行」那一档要认到<b>协议本身</b>，不能只认回话那一句。
     *
     * <p>这是真跑出来的一条：{@code specflow continue --force} 时回给模型的话是硬的那一句
     * （「不要再要求补充信息」），而系统提示词却还带着「缺料就认输」那一段
     * （实测 1383 字 vs 不带时 894 字）。一边拆梯子一边递梯子，结果就是它又停下来要料，
     * 用户点了几次「直接继续」都还是原地打转——而只测回话那一句的断言是绿的。
     */
    @Test
    @DisplayName("「直接继续」的话更强硬：用现有信息做，不许再要东西")
    void forceResumeTellsItToStopAsking() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        agent(llm, new ScriptedVerifier(passed()))
                .resume(TestSpecs.spec(List.of("Foo.java")), null, "NEED_CONTEXT: 缺东西",
                        true, List.of());

        List<ChatMessage> sent = llm.patches().get(0);
        assertThat(sent.get(3).content())
                .contains("不要再要求补充信息")
                .doesNotContain("最多 3 行");
        assertThat(sent.get(0).content()).as("协议里也不该再有那个出口：留着它，这句狠话就是白说的")
                .contains("<<<<<<< SEARCH")
                .doesNotContain("NEED_CONTEXT")
                .doesNotContain("信息不足");
        assertThat(sent.get(1).content()).as("要求那一句同样不许留「信息确实不足」这个后门")
                .doesNotContain("信息确实不足");
    }

    /**
     * 续跑要复用上一次那条留档里的施工单。
     *
     * <p>挂起的那次运行已经花调用定过一次单子（见 {@link #generateSteps}），
     * 而续跑重开一轮时又去问了一遍——真试跑里那是<b>白花的 2 次调用</b>。
     * 留档里存着那份单子的唯一理由是：挂起时磁盘已经回滚，其它地方都没有它了。
     */
    @Test
    @DisplayName("续跑：留档里有施工单就接着用，一次都不再多问")
    void resumeReusesTheStepsFromTheRecord() {
        List<PlanStep> recorded = List.of(
                step(1, "第一步", false, "Foo.java"),
                step(2, "第二步", false, "Foo.java"),
                step(3, "第三步", false, "Foo.java"));
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"),
                patch("int a = 3;", "int a = 4;"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .resume(TestSpecs.spec(List.of("Foo.java")), null, "NEED_CONTEXT: 缺东西",
                        true, recorded);

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(listener.source).as("来源说得清它是从哪来的").isEqualTo(StepsSource.RESUMED);
        assertThat(listener.probeCalls).as("为它一次调用都没花").isZero();
        assertThat(listener.plan).extracting(PlanStep::goal)
                .containsExactly("第一步", "第二步", "第三步");
        assertThat(llm.calls()).as("加上生成施工单那次，一共只有三次——每一步一次")
                .hasSize(3);
        assertThat(llm.calls()).as("没有哪一次是在「只产施工单」")
                .noneMatch(ScriptedLlm::asksForStepsOnly);
    }

    /**
     * 老记录里没有施工单这一项（读出来是 {@code null}），续跑必须能退化——
     * 那时只能照旧现生成一份，而不是崩在空指针上。
     */
    @Test
    @DisplayName("续跑：留档里没有施工单（老记录）就现生成，不崩也不卡")
    void resumeWithoutRecordedStepsGeneratesAgain() {
        // 老记录读出来是 null；归一化在这一处做，引擎那条路上就只有一个判据
        assertThat(new DevelopmentAgent.Resume("NEED_CONTEXT: 缺东西", true, null).steps())
                .as("老记录读出来是 null，认成「没有施工单」").isEmpty();

        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"),
                patch("int a = 3;", "int a = 4;"))
                .answeringStepsWith(stepsAnswer(3, "第一步", "第二步", "第三步"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .resume(TestSpecs.spec(List.of("Foo.java")), null, "NEED_CONTEXT: 缺东西",
                        true, null);

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(listener.source).as("留档里没有就只能现生成").isEqualTo(StepsSource.GENERATED);
        assertThat(listener.probeCalls).isEqualTo(1);
    }

    /**
     * 续跑复用留档里那份施工单之前，必须对着<b>当前</b>清单再核一遍。
     *
     * <p>那份单子是照<b>上一次</b>的清单核过的，而续跑的前提恰恰是用户补了料——
     * 他会改清单。清单变了、单子里某一步却还引用着已经不在清单里的文件时，
     * 那一步物理上做不了（清单外的文件改不了、也建不了）：照旧直接跑，等于让越界的步
     * 静默执行，白烧一次调用再整轮回滚，用户只看到一句「失败」。
     */
    @Test
    @DisplayName("续跑：留档里那份单子对不上现在清单时拒绝开工，并说清是哪一步")
    void refusesResumeWhenTheRecordedScheduleOutlivedTheTargets() {
        // 挂起时清单里有 Bar.java，用户后来把它挪出去了：单子第 2 步因此做不了
        List<PlanStep> recorded = List.of(
                step(1, "第一步", false, "Foo.java"),
                step(2, "第二步（要动 Bar）", false, "Bar.java"));
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .resume(TestSpecs.spec(List.of("Foo.java")), null, "NEED_CONTEXT: 缺东西",
                        true, recorded);

        assertThat(result.status()).isEqualTo(AgentResult.Status.PLAN_OUTDATED);
        assertThat(result.attempts()).isZero();
        assertThat(llm.calls()).as("一个字节都没动：模型一次都没调").isEmpty();
        assertThat(result.detail())
                .as("要说清哪一步、哪个文件、以及接下来该干什么")
                .contains("第 2 步").contains("Bar.java").contains("不在目标文件清单里")
                .contains("加回目标文件清单");
        assertThat(read("Foo.java")).as("磁盘上什么都没变").isEqualTo(ORIGINAL);
    }

    /**
     * 反向：补了料、把文件<b>加进</b>清单时，旧单子照样能用——不许误拒。
     *
     * <p>一个「清单变过就作废」的实现照样能让上面那条绿，而它会把正常的续跑全部挡住：
     * 用户补料最常见的样子就是往清单里加文件。
     */
    @Test
    @DisplayName("续跑：清单里加了文件不算过期，单子照旧接着用")
    void reusesTheRecordedScheduleWhenTargetsOnlyGrew() {
        List<PlanStep> recorded = List.of(
                step(1, "第一步", false, "Foo.java"),
                step(2, "第二步", false, "Foo.java"));
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = agent(llm, new ScriptedVerifier(passed()), listener)
                .resume(TestSpecs.spec(List.of("Foo.java", "Bar.java")), null,
                        "NEED_CONTEXT: 我要 Bar.java", false, recorded);

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        assertThat(listener.source).isEqualTo(StepsSource.RESUMED);
        assertThat(listener.probeCalls).as("清单变过也不重问").isZero();
    }

    private List<String> snapshotNames() throws IOException {
        Path directory = root.resolve(SnapshotConfig.DEFAULT_DIR);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * 一份「上一轮测试失败」的回喂内容：引擎按十五.7 的固定模板拼出来的那一段。
     *
     * <p>直接用 {@link Refeed#of} 拼，而不是在测试里手写一段文案——这样测的是
     * 「引擎拼出来的东西被放进了提示词、也被落了档」，而不是测试自己编的那句话。
     */
    private static Refeed refeed() {
        TestOutcome tests = new TestOutcome("tools/20260101-000000",
                List.of("tools/20260101-000000/run.cmd"), 1, 1,
                VerificationResult.failed("测试脚本", "run.cmd", "退出码 1"),
                List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "1",
                        "a == 2", "a == 1", "代码错了")),
                List.of(new TestOutcome.CaseResult(1, false)), List.of());
        return Refeed.of(planWithCases().cases(), tests, List.of("Foo.java"), List.of(1));
    }

    private DevelopmentAgent agent(LlmClient llm, Verifier verifier) {
        return new DevelopmentAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(),
                llm, List.of(verifier));
    }

    private DevelopmentAgent agent(LlmClient llm, Verifier verifier, AgentListener listener) {
        return new DevelopmentAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(),
                llm, List.of(verifier), listener);
    }

    private String patch(String search, String replace) {
        return patch("Foo.java", search, replace);
    }

    private String patch(String file, String search, String replace) {
        return "<<<<<<< SEARCH " + file + "\n" + search + "\n=======\n" + replace
                + "\n>>>>>>> REPLACE\n";
    }

    /**
     * <b>新建</b>一个文件的补丁块：SEARCH 那一栏留空。
     *
     * <p>为什么不复用 {@link #patch}：它会把空锚点写成「一个空行」，那是另一个形状
     * （协议里「整份新建」就是 SEARCH 栏直接跟分隔行，见 {@code SearchReplaceStrategy}）。
     */
    private static String create(String file, String content) {
        return "<<<<<<< SEARCH " + file + "\n=======\n" + content + "\n>>>>>>> REPLACE\n";
    }

    /** 一份施工单：给出的每一步都动 Foo.java，除非另说。 */
    private static PlanReview planWithSteps(PlanStep... steps) {
        return PlanReview.of("分几步做", "flowchart TD\n    A[入口] --> B[出口]", List.of(),
                List.of(steps));
    }

    /**
     * 一份带<b>用例清单</b>的方案：测试阶段靠它启动（没有它就不跑测试）。
     * 不分步，所以这次运行走的是单步那条路。
     */
    private static PlanReview planWithCases() {
        return PlanReview.of("做点事", "flowchart TD\n    A[入口] --> B[出口]", List.of(), List.of(),
                List.of(new PlanReview.TestCase(1, "a 变成 2", "读 Foo.java 里的 a",
                        PlanReview.TestCase.Level.MUST, "a == 2", "无")));
    }

    /**
     * 「生成测试产物」那一次调用的回复。
     *
     * <p>产物路径里的目录是引擎当次给的（带时间戳），测试写不出这个值——所以这里用
     * {@code {{ENTRY}}} 占位，由假模型照系统提示词替换（真模型也是这么知道该写哪儿的）。
     *
     * <p>锚点（{@code CASE 编号} + {@code expect: 期望}）必须和 {@link #planWithCases}
     * 那份清单对得上：引擎在跑之前会机器核对「用例 ⇄ 测试代码」的连线，对不上的产物
     * 会被直接拒绝运行——那样测的就不是运行这条路，而是拒绝这条路了。
     */
    private String artifactsPatch(int exit, String failureLine) {
        return "<<<<<<< SEARCH {{ENTRY}}\n=======\n"
                + EntryScripts.anchored(exit, planWithCases().cases(), failureLine)
                + ">>>>>>> REPLACE\n";
    }

    /**
     * 勾了集成测试时的那一次回复：<b>两个</b>入口脚本都要给（十五.4）。
     *
     * <p>只给单元那个的话，产物会因为「没有集成入口」被拒——而那是这批测试自己的错，
     * 不是被测代码的错。真模型拿到的是同一份协议（「这一次要两个」），所以照做。
     *
     * <p>锚点只写在单元那个入口上：这一次只有一条用例、它验的是单元那条路。
     * 同一条用例的编号在整批产物里写两遍，引擎会判成「重复实现」并拒绝运行
     * （见 {@code CaseTraceCheck}）——那是一条真规矩，不是这个桩能随便绕的。
     */
    private String integrationArtifactsPatch(int exit, String failureLine) {
        return "<<<<<<< SEARCH {{ENTRY}}\n=======\n"
                + EntryScripts.anchored(exit, planWithCases().cases(), failureLine)
                + ">>>>>>> REPLACE\n"
                + "<<<<<<< SEARCH {{ITENTRY}}\n=======\n"
                + EntryScripts.body(exit, failureLine)
                + ">>>>>>> REPLACE\n";
    }

    private static PlanStep step(int index, String goal, boolean intermediate, String... files) {
        return new PlanStep(index, goal, List.of(files), "能编译", intermediate);
    }

    /** 一份合法的施工单文本，给「开工前现生成」那条路用。 */
    private static String stepsAnswer(int count, String... goals) {
        StringBuilder out = new StringBuilder("<<<<<<< STEPS\n");
        for (int i = 1; i <= count; i++) {
            out.append(i).append(" | ").append(i <= goals.length ? goals[i - 1] : "第 " + i + " 步")
                    .append(" | Foo.java | 能编译 | 自洽\n");
        }
        return out.append(">>>>>>> STEPS\n").toString();
    }

    private String read(String name) {
        try {
            return Files.readString(root.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String lastUserMessage(ScriptedLlm llm) {
        List<ChatMessage> last = llm.patches().get(llm.patches().size() - 1);
        return last.get(last.size() - 1).content();
    }

    private static VerificationResult passed() {
        return VerificationResult.passed("脚本校验", "true", "");
    }

    private static VerificationResult failed() {
        return VerificationResult.failed("脚本校验", "javac", "编译错误：找不到符号");
    }

    private static VerificationResult skipped() {
        return VerificationResult.skipped("脚本校验", "未配置");
    }

    /** 按脚本逐次返回响应的假模型；记录每次实际发送的消息，便于断言反馈内容。 */
    private static final class ScriptedLlm implements LlmClient {

        private final Deque<String> responses;
        private final List<List<ChatMessage>> calls = new ArrayList<>();
        private final List<List<ChatMessage>> patches = new ArrayList<>();

        /** 「只产施工单」那一次调用收到的回复。默认给一份核不过的，见 {@link #complete}。 */
        private String stepsAnswer = "这个需求我拆不开。";

        ScriptedLlm(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        /** 让开工前那次「只产施工单」的调用回一份指定的东西。 */
        ScriptedLlm answeringStepsWith(String answer) {
            this.stepsAnswer = answer;
            return this;
        }

        @Override
        public String complete(List<ChatMessage> messages) {
            calls.add(List.copyOf(messages));
            if (asksForStepsOnly(messages)) {
                // 默认给一份核不过的回复：多数用例测的是**单步**行为，而拿不到可用的施工单时
                // 引擎正好退化到单步——所以它等价于「这次没有施工单」。
                // 分步行为由下面那几个用例专门覆盖
                return stepsAnswer;
            }
            if (asksForHowStage(messages)) {
                // 第二段（补「怎么测」）那一次调用：自动回一份照抄期望的答案。
                // 它**不占脚本里的应答**，因为每一次跑测试阶段都会先发生这一次——
                // 让它吃掉一条脚本，几十条既有用例的「第几轮」就全对不上了。
                // 这一段本身的行为由 CaseHowStageTest 专门覆盖
                return howAnswer(messages);
            }
            patches.add(List.copyOf(messages));
            if (responses.isEmpty()) {
                throw new IllegalStateException("脚本已用尽，模型被调用了 " + calls.size() + " 次");
            }
            return fill(responses.poll(), messages);
        }

        /** 这一次是不是第二段（补「怎么测」）。协议原文是唯一的判据。 */
        private static boolean asksForHowStage(List<ChatMessage> messages) {
            return messages.stream().anyMatch(message -> message.role().equals(ChatMessage.SYSTEM)
                    && message.content().contains(CaseHowStage.INSTRUCTIONS));
        }

        /**
         * 第二段的答案：照抄第一段那份清单里每一条的「期望」，并给一句像样的「怎么测」。
         *
         * <p>它模拟的是<b>守规矩的模型</b>。要测「它把期望改掉时引擎怎么办」，
         * 那属于 {@code CaseHowStageTest} 的活（那边直接喂协议不符的回话）。
         */
        private static String howAnswer(List<ChatMessage> messages) {
            String user = messages.get(messages.size() - 1).content();
            StringBuilder out = new StringBuilder();
            boolean inTable = false;
            for (String line : user.split("\n")) {
                if (line.startsWith("## 第一段定下来的清单")) {
                    inTable = true;
                    continue;
                }
                if (!inTable) {
                    continue;
                }
                if (line.isBlank()) {
                    inTable = false;
                    continue;
                }
                String[] parts = line.split("\\|");
                String index = parts[0].strip();
                if (parts.length < 4 || !index.matches("\\d+")) {
                    continue;
                }
                out.append(index).append(" | 照清单里的入口调一次，看结果 | ")
                        .append(parts[3].strip()).append('\n');
            }
            return out.length() == 0 ? "这次没什么可补的。" : out.toString();
        }

        /**
         * 填掉回复里的那几个占位符。
         *
         * <p>测试产物的目录带时间戳，用例写不出它——而真模型是从系统提示词里读到这个目录的，
         * 假模型照做才像真的。占位符只在这几处替换，别的回复原样返回。
         *
         * <p><b>入口脚本的名字也从提示词里读</b>，不按本机平台写死：这次脚本在哪儿跑决定它叫
         * {@code run.cmd} 还是 {@code run.sh}（宿主 / 容器，见 {@code ExecutionLocation}），
         * 而协议里把这两个路径写得明明白白。写死的话，「环境就绪、脚本进容器跑」那条路
         * 会收到一份 {@code run.cmd}，引擎要的是 {@code run.sh}——整批产物被拒，
         * 而报出来的原因是「没给入口脚本」（测试自己的错，不是被测代码的）。
         */
        private static String fill(String response, List<ChatMessage> messages) {
            if (!response.contains("{{")) {
                return response;
            }
            String system = messages.get(0).content();
            java.util.regex.Matcher matcher = ARTIFACT_DIRECTORY.matcher(system);
            if (!matcher.find()) {
                throw new IllegalStateException("系统提示词里没有产物目录：\n" + system);
            }
            String dir = matcher.group();
            List<String> entries = entryPaths(system);
            if (entries.isEmpty()) {
                throw new IllegalStateException("系统提示词里没有入口脚本的路径：\n" + system);
            }
            return response.replace("{{DIR}}", dir)
                    .replace("{{ENTRY}}", entries.get(0))
                    .replace("{{ITENTRY}}", entries.size() > 1 ? entries.get(1) : entries.get(0));
        }

        /** 提示词里点名的入口脚本路径，按出现顺序去重：第一份是单元、第二份是集成。 */
        private static List<String> entryPaths(String system) {
            java.util.List<String> found = new java.util.ArrayList<>();
            java.util.regex.Matcher matcher = ENTRY_PATH.matcher(system);
            while (matcher.find()) {
                if (!found.contains(matcher.group())) {
                    found.add(matcher.group());
                }
            }
            return found;
        }

        /** 认这次调用要的是不是「只产施工单」的那份协议。 */
        private static boolean asksForStepsOnly(List<ChatMessage> messages) {
            return messages.stream().anyMatch(message -> message.role().equals(ChatMessage.SYSTEM)
                    && message.content().contains(ReviewProtocol.STEPS_MARKER)
                    && !message.content().contains(ReviewProtocol.FLOW_MARKER));
        }

        /** 全部调用，含开工前生成施工单的那次。 */
        List<List<ChatMessage>> calls() {
            return calls;
        }

        /** 只要补丁的那几次调用——断言「第几轮」时该数的是它们。 */
        List<List<ChatMessage>> patches() {
            return patches;
        }

        /**
         * 最近一次调用收到的系统提示词。
         *
         * <p>「连接信息有没有送进去」「入口脚本叫什么」这两件事都只能从它看——
         * 它们不在补丁里，而在发给模型的那一段话里。
         */
        String system() {
            List<ChatMessage> last = calls.get(calls.size() - 1);
            for (ChatMessage message : last) {
                if (ChatMessage.SYSTEM.equals(message.role())) {
                    return message.content();
                }
            }
            return "";
        }
    }

    /** 按脚本返回校验结果的假校验器；最后一个结果会被重复使用。 */
    private static final class ScriptedVerifier implements Verifier {
        private final Deque<VerificationResult> results;

        ScriptedVerifier(VerificationResult... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public String name() {
            return "脚本校验";
        }

        @Override
        public VerificationResult verify(VerificationContext context) {
            return results.size() > 1 ? results.poll() : results.peek();
        }
    }

    /** 第 1 轮校验结束后叫停，用来验证中断只在轮与轮之间生效。顺带记下它看到的步级事件。 */
    private static final class CancelAfterRoundListener implements AgentListener {

        private final List<String> events = new ArrayList<>();
        private boolean cancelled;

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void stepStarted(PlanStep step) {
            events.add("step:" + step.index());
        }

        @Override
        public void stepFinished(PlanStep step, StepState state) {
            events.add("stepdone:" + step.index() + ":" + state);
        }

        @Override
        public void verificationFinished(int round, List<VerificationResult> results) {
            cancelled = true;
        }
    }

    /** 第 n 步结束时叫停，用来验证中断在步与步之间也生效；顺带记下它看到过哪些步。 */
    private static final class CancelAfterStepListener implements AgentListener {

        private final int afterStep;
        private final List<String> events = new ArrayList<>();
        private boolean cancelled;

        CancelAfterStepListener(int afterStep) {
            this.afterStep = afterStep;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void stepStarted(PlanStep step) {
            events.add("step:" + step.index());
        }

        @Override
        public void stepFinished(PlanStep step, StepState state) {
            events.add("stepdone:" + step.index());
            cancelled = step.index() >= afterStep;
        }
    }

    /**
     * 在「第一轮校验跑完」之后叫停——也就是<b>正好卡在测试阶段之前</b>。
     *
     * <p>挑这个时刻是因为要验的正是「进测试之前那一问」：早了会停在轮与轮之间
     * （那是另一条路，已经测过了），晚了就进了测试阶段——而那一段停不下来。
     */
    private static final class CancelBeforeTestListener implements AgentListener {

        private int testsStarted;
        private boolean cancelled;

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void verificationFinished(int round, List<VerificationResult> results) {
            cancelled = true;
        }

        @Override
        public void testsStarted(int cases) {
            testsStarted++;
        }
    }

    /**
     * 一套假的环境：探得到 docker、容器在跑、exec 都成功。
     *
     * <p>用它是因为「真起容器」不该出现在单元测试里——但那不等于环境这一层不用测：
     * 这一批要验的是「引擎有没有先重置、有没有把连接信息交出去」，
     * 而那些问题和 docker 本身没关系。
     */
    private static final class FakeEnvironmentRunner implements CommandRunner {

        private final com.specflow.env.FakeCommandRunner delegate =
                new com.specflow.env.FakeCommandRunner();

        FakeEnvironmentRunner(String container) {
            this(container, true);
        }

        /**
         * @param upWorks 起环境那条命令成不成。给 {@code false} 就是「这台机器上起不来」——
         *                预热失败那一档（环境不可用、跳过集成）只有它能造出来
         */
        FakeEnvironmentRunner(String container, boolean upWorks) {
            delegate.ok("version", "fake docker 1.0")
                    .ok("ps -a", container)
                    .ok("volume ls", container.replace("-app-1", "_data"))
                    .ok("network ls", container.replace("-app-1", "_default"))
                    .ok("exec -T app", "");
            if (upWorks) {
                // 预热那条路（开发一开跑就异步起环境）：up 起得来，否则
                // 「先重置数据」那一条测的就不是重置，而是「环境不可用」那一档了
                delegate.ok("up -d --wait", "");
            }
        }

        boolean ran(String fragment) {
            return delegate.ran(fragment);
        }

        @Override
        public Result run(List<String> command, java.util.Map<String, String> environment,
                          Path workdir, long timeoutSeconds) {
            return delegate.run(command, environment, workdir, timeoutSeconds);
        }
    }

    /** 把回调压成字符串序列，便于用一条断言表达「按什么顺序发生了什么」。 */
    private static final class RecordingListener implements AgentListener {
        private final List<String> events = new ArrayList<>();
        private List<PlanStep> plan;
        private StepsSource source;
        private int probeCalls;

        @Override
        public void stepsResolved(List<PlanStep> steps, StepsSource source, int probeCalls) {
            this.plan = steps;
            this.source = source;
            this.probeCalls = probeCalls;
            events.add("plan:" + source + ":" + steps.size());
        }

        @Override
        public void stepStarted(PlanStep step) {
            events.add("step:" + step.index());
        }

        @Override
        public void stepFinished(PlanStep step, StepState state) {
            events.add("stepdone:" + step.index() + ":" + state);
        }

        @Override
        public void stepRestored(PlanStep step, int round, String reason) {
            events.add("stepback:" + step.index());
        }

        @Override
        public void roundStarted(int round) {
            events.add("round:" + round);
        }

        @Override
        public void planRejected(int round, PatchConflictException failure) {
            events.add("rejected:" + round);
        }

        @Override
        public void filesApplied(int round, List<PatchApplier.FileChange> changes) {
            events.add("applied:" + round);
        }

        @Override
        public void verificationFinished(int round, List<VerificationResult> results) {
            events.add("verified:" + round);
        }

        @Override
        public void workspaceRestored(int round, String reason) {
            events.add("restored:" + round);
        }

        @Override
        public void finished(AgentResult result) {
            events.add("finished:" + result.status().name());
        }
    }
}
