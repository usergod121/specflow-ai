package com.specflow.cli;

import com.specflow.SpecflowCli;
import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.history.RunRecord;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.project.SnapshotConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.tests.TestOutcome;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.VerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令行端到端测试。
 *
 * <p>覆盖到 {@code validate} / {@code templates} / {@code init} 三条不联网的命令。
 * {@code run} 需要真实模型，不在这里测——它的编排逻辑由
 * {@code DevelopmentAgentTest} 用假模型覆盖。
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

    /** 造一条「跑过测试、第 1 条过了、第 2 条没过」的运行记录。 */
    private void recordFailingRun(Path artifacts) {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);
        recorder.testsFinished(new TestOutcome(root.relativize(artifacts).toString().replace('\\', '/'),
                List.of(), 1, 1, VerificationResult.failed("测试脚本", "run", "一条没过"),
                List.of(), List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, false))));
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
