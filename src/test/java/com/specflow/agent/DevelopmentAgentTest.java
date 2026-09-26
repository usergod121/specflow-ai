package com.specflow.agent;

import com.specflow.TestSpecs;
import com.specflow.exception.PatchConflictException;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.patch.PatchApplier;
import com.specflow.project.BuildConfig;
import com.specflow.project.LlmConfig;
import com.specflow.project.ProjectConfig;
import com.specflow.project.SnapshotConfig;
import com.specflow.review.PlanReview;
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
        assertThat(llm.calls()).hasSize(1);
    }

    @Test
    @DisplayName("系统消息里带着补丁协议，让模型知道该输出什么格式")
    void sendsProtocolInSystemMessage() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        agent(llm, new ScriptedVerifier(passed())).run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(llm.calls().get(0).get(0).role()).isEqualTo(ChatMessage.SYSTEM);
        assertThat(llm.calls().get(0).get(0).content()).contains("<<<<<<< SEARCH");
        assertThat(llm.calls().get(0).get(1).content()).contains("int a = 1;");
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
                new VerifySpec(true, null, 0));

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
        assertThat(llm.calls()).hasSize(1);
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
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6)));

        assertThat(result.status()).isEqualTo(AgentResult.Status.CANCELLED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(read("Foo.java")).isEqualTo(ORIGINAL);
        assertThat(llm.calls()).hasSize(1);
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
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6)));

        assertThat(result.status()).isEqualTo(AgentResult.Status.NEEDS_ENVIRONMENT);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(llm.calls()).hasSize(1);
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
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6)));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(llm.calls()).hasSize(2);
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
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6)));

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
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 6)));

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

        String userMessage = llm.calls().get(0).get(1).content();
        assertThat(userMessage).contains("已确认的实现方案").contains("在 Foo 里加一个方法");
        assertThat(userMessage).endsWith("\n");
    }

    @Test
    @DisplayName("没有方案时不出现施工图段落")
    void omitsPlanSectionWhenAbsent() {
        ScriptedLlm llm = new ScriptedLlm(patch("int a = 1;", "int a = 2;"));

        agent(llm, new ScriptedVerifier(passed())).run(TestSpecs.spec(List.of("Foo.java")));

        assertThat(llm.calls().get(0).get(1).content()).doesNotContain("已确认的实现方案");
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
                .run(TestSpecs.spec(List.of("Foo.java"), new VerifySpec(true, null, 0)));

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
        List<ChatMessage> sent = llm.calls().get(0);
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

        assertThat(llm.calls().get(0).get(3).content())
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
        return "<<<<<<< SEARCH Foo.java\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE\n";
    }

    private String read(String name) {
        try {
            return Files.readString(root.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String lastUserMessage(ScriptedLlm llm) {
        List<ChatMessage> last = llm.calls().get(llm.calls().size() - 1);
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

        ScriptedLlm(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public String complete(List<ChatMessage> messages) {
            calls.add(List.copyOf(messages));
            if (responses.isEmpty()) {
                throw new IllegalStateException("脚本已用尽，模型被调用了 " + calls.size() + " 次");
            }
            return responses.poll();
        }

        List<List<ChatMessage>> calls() {
            return calls;
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

    /** 把回调压成字符串序列，便于用一条断言表达「按什么顺序发生了什么」。 */
    private static final class RecordingListener implements AgentListener {

        private final List<String> events = new ArrayList<>();

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
