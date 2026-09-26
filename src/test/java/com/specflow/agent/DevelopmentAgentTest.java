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

        assertThat(result.succeeded()).isTrue();
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

        AgentResult result = agent(llm, new ScriptedVerifier(passed(), failed())).run(
                TestSpecs.spec(List.of("Foo.java"),
                        new VerifySpec(true, null, 0, VerifySpec.AUTO_ROUNDS)),
                planWithSteps(step(1, "第一步", false, "Foo.java"),
                        step(2, "第二步", false, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.detail()).contains("第 2 步");
        assertThat(read("Foo.java")).as("第一步已经做成的也要撤掉——半成品比全撤更难收拾")
                .isEqualTo(ORIGINAL);
        assertThat(snapshotNames()).isEmpty();
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

        AgentResult result = agent(llm, verifier).run(
                TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6, 2)),
                planWithSteps(step(1, "第一步", false, "Foo.java"),
                        step(2, "第二步", false, "Foo.java")));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.detail()).contains("总轮次已用尽").contains("上限 2 轮");
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
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
                "NEED_CONTEXT: 缺东西", false);

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
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"))
                .answeringStepsWith("<<<<<<< STEPS\n1 | 一步搞定 | Foo.java | 能编译 | 自洽\n>>>>>>> STEPS\n");
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
        assertThat(retry).contains("核不过").contains("少于 3 步");
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
                .resume(TestSpecs.spec(List.of("Foo.java")), null, "NEED_CONTEXT: 我需要 Bar.java", false);

        assertThat(result.status()).isEqualTo(AgentResult.Status.SUCCESS);
        List<ChatMessage> sent = llm.patches().get(0);
        assertThat(sent).as("system + user + 它说过的话 + 接着跑这一句，就这四条").hasSize(4);
        assertThat(sent.get(2).role()).isEqualTo(ChatMessage.ASSISTANT);
        assertThat(sent.get(2).content()).contains("我需要 Bar.java");
        assertThat(sent.get(3).content()).contains("最多 3 行");
    }

    @Test
    @DisplayName("「直接继续」的话更强硬：用现有信息做，不许再要东西")
    void forceResumeTellsItToStopAsking() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        agent(llm, new ScriptedVerifier(passed()))
                .resume(TestSpecs.spec(List.of("Foo.java")), null, "NEED_CONTEXT: 缺东西", true);

        assertThat(llm.patches().get(0).get(3).content())
                .contains("不要再要求补充信息")
                .doesNotContain("最多 3 行");
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
            return responses.poll();
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

    /** 第 1 轮校验结束后叫停，用来验证中断只在轮与轮之间生效。 */
    private static final class CancelAfterRoundListener implements AgentListener {

        private boolean cancelled;

        @Override
        public boolean cancelled() {
            return cancelled;
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
