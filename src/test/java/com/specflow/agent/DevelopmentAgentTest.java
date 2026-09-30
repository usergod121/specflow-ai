package com.specflow.agent;

import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener.StepState;
import com.specflow.agent.AgentListener.StepsSource;
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
import com.specflow.tests.EntryScripts;
import com.specflow.tests.TestOutcome;
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
                new BuildConfig("echo [ERROR] 程序包 com.google.gson 不存在 & exit 1", null, null),
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
     * 环境问题这一档和「测试没过」是两种收场：脚本压根没跑起来，磁盘上的改动也就没有
     * 任何证据支撑，必须整轮回滚、把原始错误交给人——和编译那边判「缺依赖」是同一条路。
     */
    @Test
    @DisplayName("测试跑不起来（环境问题）：整轮回滚，把原始错误交给人")
    void rollsBackWhenTestsCannotRun() {
        ScriptedLlm llm = new ScriptedLlm(
                patch("int a = 1;", "int a = 2;"),
                artifactsPatch(2, "BLOCKED | javac not found"));

        AgentResult result = agent(llm, new ScriptedVerifier(passed()))
                .run(TestSpecs.spec(List.of("Foo.java")), planWithCases());

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_ENVIRONMENT);
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
        assertThat(result.detail()).contains("javac not found").contains("环境问题");
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
     */
    private String artifactsPatch(int exit, String failureLine) {
        return "<<<<<<< SEARCH {{ENTRY}}\n=======\n" + EntryScripts.body(exit, failureLine)
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
            patches.add(List.copyOf(messages));
            if (responses.isEmpty()) {
                throw new IllegalStateException("脚本已用尽，模型被调用了 " + calls.size() + " 次");
            }
            return fill(responses.poll(), messages);
        }

        /**
         * 填掉回复里的那两个占位符。
         *
         * <p>测试产物的目录带时间戳，用例写不出它——而真模型是从系统提示词里读到这个目录的，
         * 假模型照做才像真的。占位符只在这两处替换，别的回复原样返回。
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
            return response.replace("{{DIR}}", dir)
                    .replace("{{ENTRY}}", dir + "/" + EntryScripts.name());
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
