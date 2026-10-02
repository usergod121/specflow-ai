package com.specflow.cli;

import com.specflow.SpecflowCli;
import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.exception.SpecflowException;
import com.specflow.history.RunRecord;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.project.SnapshotConfig;
import com.specflow.review.PlanReview;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.tests.TestOutcome;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.VerificationResult;
import com.specflow.web.StubModelServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令行端到端测试。
 *
 * <p>覆盖到 {@code validate} / {@code templates} / {@code init} 几条不联网的命令，
 * 以及 {@code accept} / {@code rollback} / {@code env} 的收场语义。
 *
 * <p>{@code run} 不能拿真模型测（慢、不确定），但<b>能用本机的假模型端点测</b>——
 * 「命令行参数真的被解析、真的进了发给模型的那条提示词」只有这样才验得到
 * （见 {@code refeedsThePreviousFailuresFromTheCommandLine}）。
 * 编排逻辑本身由 {@code DevelopmentAgentTest} 用假模型覆盖。
 */
@DisplayName("命令行")
class SpecflowCliTest {

    @TempDir
    Path root;

    private String project;

    @BeforeEach
    void setUp() {
        project = root.toString();
    }

    @Test
    @DisplayName("validate 通过时返回 0")
    void validateSucceedsOnGoodSpec() throws Exception {
        writeSpec("""
                prompt: 给 Foo 加一行日志
                targets: [Foo.java]
                """);

        assertThat(SpecflowCli.execute("validate", "-p", project)).isZero();
    }

    @Test
    @DisplayName("validate 失败时返回 1，且不改动任何文件")
    void validateFailsOnBadSpec() throws Exception {
        writeSpec("""
                prompt: x
                targets: [../escape.txt]
                """);

        assertThat(SpecflowCli.execute("validate", "-p", project)).isEqualTo(1);
    }

    @Test
    @DisplayName("spec 文件不存在时返回 1 而不是崩溃")
    void validateFailsWhenSpecMissing() {
        assertThat(SpecflowCli.execute("validate", "-p", project, "-s", "nope.yaml")).isEqualTo(1);
    }

    @Test
    @DisplayName("validate 会加载模板并报出它的标签")
    void validateLoadsTemplate() throws Exception {
        Path templates = root.resolve(".specflow/templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("t.yaml"), """
                name: t
                tags: [java, mybatis]
                system: 你是后端工程师
                """);

        writeSpec("""
                template: t
                prompt: 做点事
                targets: [Foo.java]
                """);
        assertThat(SpecflowCli.execute("validate", "-p", project)).isZero();
    }

    @Test
    @DisplayName("引用了不存在的模板时 validate 返回 1")
    void validateFailsOnUnknownTemplate() throws Exception {
        writeSpec("""
                template: 不存在的模板
                prompt: 做点事
                targets: [Foo.java]
                """);

        assertThat(SpecflowCli.execute("validate", "-p", project)).isEqualTo(1);
    }

    @Test
    @DisplayName("templates 在没有任何模板时也返回 0")
    void templatesSucceedsWhenEmpty() {
        assertThat(SpecflowCli.execute("templates", "-p", project)).isZero();
    }

    @Test
    @DisplayName("init 生成配置骨架，且不覆盖已有文件")
    void initScaffoldsProject() throws Exception {
        assertThat(SpecflowCli.execute("init", "-p", project)).isZero();

        assertThat(root.resolve(".specflow/project.yaml")).exists();
        assertThat(root.resolve(".specflow/templates/spring-backend.yaml")).exists();
        assertThat(root.resolve(".specflow/templates/fix-bug.yaml")).exists();
        assertThat(root.resolve("spec.yaml")).exists();

        Path impl = root.resolve(".specflow/templates/spring-backend.yaml");
        String before = Files.readString(impl);
        Files.writeString(impl, before + "\n# 用户自己的改动\n");

        assertThat(SpecflowCli.execute("init", "-p", project)).isZero();
        assertThat(Files.readString(impl)).endsWith("# 用户自己的改动\n");
    }

    @Test
    @DisplayName("init 生成的项目可以直接通过 validate")
    void initOutputIsValid() {
        assertThat(SpecflowCli.execute("init", "-p", project)).isZero();

        assertThat(SpecflowCli.execute("validate", "-p", project)).isZero();
    }

    @Test
    @DisplayName("没有子命令时打印帮助并返回 0")
    void printsUsageWithoutSubcommand() {
        assertThat(SpecflowCli.execute()).isZero();
    }

    @Test
    @DisplayName("没有待处置的改动时 accept 与 rollback 都是 0，不做任何事")
    void decidingWithNothingPendingSucceeds() {
        assertThat(SpecflowCli.execute("accept", "-p", project)).isZero();
        assertThat(SpecflowCli.execute("rollback", "-p", project)).isZero();
    }

    @Test
    @DisplayName("rollback 按快照把文件恢复原样，并把快照清掉")
    void rollbackRestoresFilesFromSnapshot() throws Exception {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "old");
        markPendingSnapshot(file);
        Files.writeString(file, "new");

        assertThat(SpecflowCli.execute("rollback", "-p", project)).isZero();

        assertThat(Files.readString(file)).isEqualTo("old");
        assertThat(undisposedSnapshots()).isEmpty();
    }

    @Test
    @DisplayName("accept 保留磁盘上的改动，只把快照清掉")
    void acceptKeepsFilesAndClearsSnapshot() throws Exception {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "old");
        markPendingSnapshot(file);
        Files.writeString(file, "new");

        assertThat(SpecflowCli.execute("accept", "-p", project)).isZero();

        assertThat(Files.readString(file)).isEqualTo("new");
        assertThat(undisposedSnapshots()).isEmpty();
    }

    @Test
    @DisplayName("没有挂起的运行时 continue 返回 0，并说清没有东西可接着跑")
    void continueWithoutSuspendedRunIsFine() throws Exception {
        writeSpec("""
                prompt: 给 Foo 加一行日志
                targets: [Foo.java]
                """);

        assertThat(SpecflowCli.execute("continue", "-p", project)).isZero();
    }

    // ---------- 测试环境（十五.5） ----------

    /**
     * {@code specflow env status}：没写声明时是 0（这不是错误，只是这个项目只能跑单元测试），
     * 而且<b>一条 docker 命令都不会起</b>——「没声明」这件事本身就已经把话说完了。
     */
    @Test
    @DisplayName("env status：没声明环境时返回 0，并说清只能跑单元测试")
    void envStatusWithoutDeclaration() {
        assertThat(SpecflowCli.execute("env", "status", "-p", project)).isZero();
        assertThat(SpecflowCli.execute("env", "-p", project))
                .as("不带动作时默认就是 status").isZero();
    }

    /** 声明写错了：返回 1，并把带行号的问题逐条打出来（命令行下没有界面可看）。 */
    @Test
    @DisplayName("env status：声明写错时返回 1，问题里带着行号")
    void envStatusWithBrokenDeclaration() throws Exception {
        Path file = root.resolve(".specflow/env.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "image: \"x:1\"\nworkdir: \"work\"\n");

        assertThat(SpecflowCli.execute("env", "init", "-p", project)).isEqualTo(1);
    }

    @Test
    @DisplayName("env 的动作认不出来时返回 1，并列出能用的是哪几个")
    void envRejectsUnknownAction() {
        assertThat(SpecflowCli.execute("env", "whatever", "-p", project)).isEqualTo(1);
    }

    /**
     * {@code env clear}：没声明环境时返回 0 并说清「没有可清的东西」。
     *
     * <p>这里是 0 而不是 1：<b>没东西可清不是失败</b>。但也不能静默——用户点了「清空」，
     * 却什么都没被告知，他会以为环境真被清掉了（而实际是这个项目压根没有环境）。
     */
    @Test
    @DisplayName("env clear：没声明环境时返回 0，并说清没有可清的东西")
    void envClearWithoutDeclaration() {
        assertThat(SpecflowCli.execute("env", "clear", "-p", project)).isZero();
    }

    /**
     * {@code env reset}：命令行上「把数据恢复到一个已知状态」的那个入口。
     *
     * <p>为什么必须有：界面上这一步藏在「每次跑测试之前」和收场里（没有单独的按钮），
     * 所以命令行用户没有它就没法处理「上一轮的数据把这一轮的断言带偏」——
     * 而那种失败看起来像被测代码不稳定，最难查。
     */
    @Test
    @DisplayName("env reset：没声明环境时返回 0，并说清没有可重置的数据")
    void envResetWithoutDeclaration() {
        assertThat(SpecflowCli.execute("env", "reset", "-p", project)).isZero();
    }

    /** 声明了却没初始化过：这一次真做不成，退出码 1，原始错误里写着先初始化。 */
    @Test
    @DisplayName("env reset：声明了但没初始化 → 1")
    void envResetBeforeInit() throws Exception {
        Path file = root.resolve(".specflow/env.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "image: \"x:1\"\nworkdir: \"/work\"\n");

        assertThat(SpecflowCli.execute("env", "reset", "-p", project)).isEqualTo(1);
    }

    /**
     * 命令行的 {@code accept} 同样是<b>完整</b>收场（十五.8）：删产物、把人的选择写进留档。
     *
     * <p>两个入口各写一遍收场，迟早有一边少做一件；而少的那一件不是当场看得出来的
     * （产物没删要等下次翻 {@code tools/}，留档没写要等事后想复盘「那次为什么带着红接受」）。
     */
    @Test
    @DisplayName("accept：产物删掉、留档里写下「接受」与当时带着的失败")
    void acceptRecordsTheSettlement() throws Exception {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "old");
        markPendingSnapshot(file);
        Files.writeString(file, "new");
        Path artifacts = root.resolve("tools/20260930-180000");
        Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("run.cmd"), "echo FAIL\n");
        recordFailingRun(artifacts);

        assertThat(SpecflowCli.execute("accept", "-p", project)).isZero();

        assertThat(Files.readString(file)).as("接受 = 文件留在磁盘上").isEqualTo("new");
        assertThat(artifacts).doesNotExist();
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecord.Settlement settlement = store.load(store.latestId()).settlement();
        assertThat(settlement.choice()).isEqualTo(RunRecord.Settlement.ACCEPT);
        assertThat(settlement.failing()).containsExactly(2);
    }

    // ---------- 回喂（十五.6 第一条路）：命令行上的「下一轮」 ----------

    /**
     * {@code run --refeed 2}：命令行上也能把上一轮失败的哪几条喂回给开发。
     *
     * <p>这一条走的是<b>真命令 + 假模型</b>（本机的 OpenAI 兼容端点），验的是三件事接上了：
     * ①那一栏被解析成了编号；②内容由引擎按十五.7 的模板从<b>上一轮那条留档</b>里拼
     * （界面与命令行共用 {@code RunStore.refeed}，谁都不许自己拼文案）；
     * ③它真的进了发给模型的那条提示词。只测 {@code Refeed.of} 的话，
     * 「拼出来了但没发给模型」这种断线照样是绿的——而那正是这条路的全部意义。
     */
    @Test
    @DisplayName("run --refeed 2：把上一轮失败的那几条喂给开发（真命令 + 假模型）")
    void refeedsThePreviousFailuresFromTheCommandLine() throws Exception {
        writeSpec("""
                prompt: 把 a 改成 2
                targets: [Foo.java]
                """);
        Files.writeString(root.resolve("Foo.java"), "class Foo { int a = 1; }\n");
        recordRefeedableRun();

        try (StubModelServer model = StubModelServer.answering(
                // 开工前那两次「现生成施工单」的探测（桩不认识 STEPS 块，于是引擎退化成单步）
                "这个需求我拆不开。",
                "这个需求我拆不开。",
                // 开发那一轮：真改一个文件（改没改成不是这一条要验的，验的是它收到了什么）
                "<<<<<<< SEARCH Foo.java\nclass Foo { int a = 1; }\n=======\n"
                        + "class Foo { int a = 2; }\n>>>>>>> REPLACE\n")) {
            writeProjectConfig(model.baseUrl());

            assertThat(SpecflowCli.execute("run", "-p", project, "--refeed", "2")).isZero();

            int development = -1;
            for (int index = 0; index < model.calls(); index++) {
                if (!model.askedForStepsOnly(index)) {
                    development = index;
                    break;
                }
            }
            assertThat(development).as("开发那一轮调用过（一共 %s 次调用）", model.calls())
                    .isNotNegative();
            String user = model.userOf(development);
            assertThat(user).as("回喂那一段真的进了提示词：%s", user)
                    .contains("## 上一轮的测试失败")
                    .contains("用例 2「改完还能编译」")
                    .contains("期望 compiled")
                    .contains("实际 not compiled")
                    .contains("Foo.java");
            assertThat(user).as("不给测试代码：照着断言改代码等于对着答案抄")
                    .doesNotContain("echo FAIL");
        }
    }

    @Test
    @DisplayName("--refeed all = 上一轮失败清单里的全部（和界面上的「全选」同一件事）")
    void refeedAllMeansEveryFailingCase() {
        recordRefeedableRun();
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        assertThat(RunCommand.refeedOf("all", store).cases()).containsExactly(2);
        assertThat(RunCommand.refeedOf("2", store).cases()).containsExactly(2);
        assertThat(RunCommand.refeedOf("2, 3", store).cases()).as("编号分隔符随便写")
                .containsExactly(2, 3);
        assertThat(RunCommand.refeedOf("", store).present()).as("没写这一栏 = 不是「下一轮」").isFalse();
        assertThat(RunCommand.refeedOf(null, store).present()).isFalse();
    }

    /**
     * 写错了要当场拒，而且两种说法不是同一句：一个是「你的参数写错了」，
     * 另一个是「上一轮压根没有可回喂的东西」。混成一句，用户会去改一个本来就对的参数。
     */
    @Test
    @DisplayName("--refeed 写错了当场拒：认不出编号 / 上一轮没跑过测试")
    void refeedRejectsWhatItCannotFeed() {
        RunStore empty = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        assertThatThrownBy(() -> RunCommand.refeedOf("7,8", empty))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("没有可回喂的失败清单");

        recordRefeedableRun();
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        assertThatThrownBy(() -> RunCommand.refeedOf("这几条", store))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("--refeed 要写编号");
    }

    // ---------- 辅助 ----------

    /**
     * 造一条<b>可以拿来回喂</b>的记录：带用例清单、带失败清单、带目标文件。
     *
     * <p>三样缺一不可——回喂那一段要「用例的语义描述」（清单里有）、
     * 「期望 vs 实际」（失败清单里有）、「涉及的目标文件」（run 级 targets）。
     */
    private void recordRefeedableRun() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        List<PlanReview.TestCase> cases = List.of(
                new PlanReview.TestCase(1, "a 变成 2", "读 Foo.java 里的 a",
                        PlanReview.TestCase.Level.MUST, "a == 2", "无"),
                new PlanReview.TestCase(2, "改完还能编译", "跑一次编译",
                        PlanReview.TestCase.Level.SHOULD, "compiled", "无"));
        TestOutcome tests = new TestOutcome("tools/20260930-180000", List.of(), 1, 1,
                VerificationResult.failed("测试脚本", "run", "一条没过"),
                List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "2",
                        "compiled", "not compiled", "code is wrong")),
                List.of(new TestOutcome.CaseResult(1, true), new TestOutcome.CaseResult(2, false)),
                List.of());
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                PlanReview.of("做点事", "", List.of(), List.of(), cases), AgentListener.NOOP);
        recorder.testsFinished(tests);
        recorder.finished(AgentResult.testsFailed(1, List.of(), List.of(), "一条没过"));
    }

    /**
     * 一份指向假模型的项目配置 + 密钥：{@code run} 那条路要自己读 {@code project.yaml}
     * （界面那条路是把它装配好传进来的，命令行没有这一层）。
     */
    private void writeProjectConfig(String baseUrl) throws Exception {
        Path config = root.resolve(".specflow/project.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                llm:
                  base-url: "%s"
                  model: "stub"
                  api-key-env: "SPECFLOW_TEST_KEY"
                """.formatted(baseUrl));
        Files.writeString(root.resolve(".specflow/local.env"), "SPECFLOW_TEST_KEY=sk-test\n");
    }

    /** 造一条「跑过测试、第 1 条过了、第 2 条没过」的运行记录。 */
    private void recordFailingRun(Path artifacts) {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);
        recorder.testsFinished(new TestOutcome(root.relativize(artifacts).toString().replace('\\', '/'),
                List.of(), 1, 1, VerificationResult.failed("测试脚本", "run", "一条没过"),
                List.of(), List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, false)), List.of()));
        recorder.finished(AgentResult.testsFailed(1, List.of(), List.of(), "一条没过"));
    }

    /** 造一份「校验通过、等人处置」的快照，模拟上一次运行留下的东西。 */
    private void markPendingSnapshot(Path file) {
        WorkspaceSnapshot.capture(new SafePathResolver(root),
                        root.resolve(SnapshotConfig.DEFAULT_DIR), List.of(file))
                .markPending();
    }

    private List<WorkspaceSnapshot> undisposedSnapshots() {
        return WorkspaceSnapshot.undisposed(new SafePathResolver(root),
                root.resolve(SnapshotConfig.DEFAULT_DIR));
    }

    private void writeSpec(String yaml) throws Exception {
        Files.writeString(root.resolve("spec.yaml"), yaml);
    }
}
