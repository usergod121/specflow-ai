package com.specflow.tests;

import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.env.EnvConfigLoader;
import com.specflow.env.FakeCommandRunner;
import com.specflow.env.TestEnvironment;
import com.specflow.exception.SpecflowException;
import com.specflow.history.RunRecord;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.project.ProjectConfig;
import com.specflow.project.SnapshotConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收场（十五.8）：接受与中断两条路各要做的四件事，以及「做不成时怎么办」。
 *
 * <p>它盯的是<b>三条一起成立才算收场</b>：磁盘上的文件有了归宿、产物与数据被清掉、
 * 人做的那个选择写进了留档。少任何一条都不是当场看得出来的——
 * 产物没删要等到下次翻 {@code tools/} 才会发现，留档没写要等到事后想复盘「那次为什么带着红接受」
 * 才发现，而那时已经无从查证。
 *
 * <p>环境那一侧用 {@link FakeCommandRunner}：这台机器上真的起容器要拉镜像、要几分钟，
 * 而这里要验的是<b>「跑哪几条命令、什么时候不跑」</b>——那正是收场会不会污染下一次的全部内容。
 * 真容器上再走一遍的版本在 {@code RealDockerEnvironmentTest} 里。
 */
@DisplayName("收场：接受与中断")
class TeardownTest {

    @TempDir
    Path root;

    /** 声明里带一条 reset：收场时要跑它（清环境数据），而不是把容器收掉。 */
    private static final String DECLARATION = """
            image: "eclipse-temurin:17"
            workdir: "/work"
            reset:
              - "rm -rf /data/*"
            """;

    // ---------- 两条收场路 ----------

    @Test
    @DisplayName("接受：改动留在磁盘上、快照删掉、产物删掉、环境数据重置、留档写下「带着几条失败」")
    void acceptKeepsFilesAndRecordsTheChoice() throws IOException {
        declare(DECLARATION);
        Files.writeString(root.resolve("Foo.java"), "old");
        markPending("Foo.java");
        Files.writeString(root.resolve("Foo.java"), "new");
        Path artifacts = artifacts("tools/20260930-120000");
        recordTestRun(artifacts);
        FakeCommandRunner docker = dockerReady();
        TestEnvironment environment = initialized(docker);

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store(),
                environment, Teardown.Choice.ACCEPT);

        assertThat(Files.readString(root.resolve("Foo.java")))
                .as("接受就是「文件不动」——它已经是最终结果").isEqualTo("new");
        assertThat(waitingSnapshots()).as("快照是「还没处置」的凭据，处置完就该没有").isEmpty();
        assertThat(artifacts).as("产物是照着这一轮改动写的，改动已处置完").doesNotExist();
        assertThat(docker.ran("exec -T app sh -c rm -rf /data/*"))
                .as("清环境数据：这一笔做的是 reset").isTrue();
        assertThat(docker.ran("down")).as("容器留着复用：收场不是关环境").isFalse();

        RunRecord.Settlement settlement = store().load(store().latestId()).settlement();
        assertThat(settlement.choice()).isEqualTo(RunRecord.Settlement.ACCEPT);
        assertThat(settlement.failing()).as("当时带着哪几条失败——这就是「接受时带着 N 条失败」")
                .containsExactly(2, 3);
        assertThat(done.summarize()).contains("已接受").contains("1 份测试产物").contains("环境数据已重置");
    }

    @Test
    @DisplayName("中断：文件恢复到运行前、快照与产物删掉、环境数据重置、留档记的是中断")
    void interruptRestoresFilesAndRecordsTheChoice() throws IOException {
        declare(DECLARATION);
        Files.writeString(root.resolve("Foo.java"), "old");
        markPending("Foo.java");
        Files.writeString(root.resolve("Foo.java"), "new");
        Path artifacts = artifacts("tools/20260930-121500");
        recordTestRun(artifacts);

        TestEnvironment environment = initialized(dockerReady());

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store(),
                environment, Teardown.Choice.INTERRUPT);

        assertThat(Files.readString(root.resolve("Foo.java")))
                .as("中断 = 回到这次运行开始前").isEqualTo("old");
        assertThat(waitingSnapshots()).isEmpty();
        assertThat(artifacts).doesNotExist();
        assertThat(done.files()).as("报出恢复了几个文件").isEqualTo(1);
        assertThat(store().load(store().latestId()).settlement().choice())
                .isEqualTo(RunRecord.Settlement.INTERRUPT);
        assertThat(store().load(store().latestId()).settlement().failing())
                .as("中断也记下当时红在哪几条上：文件回滚了，「这次运行的结论」不该跟着没")
                .containsExactly(2, 3);
    }

    /**
     * 没有待处置的改动：这次收场什么都不做。
     *
     * <p>幂等不是洁癖：界面上那两个按钮、CLI 那两条命令都可能被点第二次，
     * 而第二次要是照样「按留档删产物」，删掉的可能是<b>另一次</b>运行的产物——
     * 那次运行还等着人处置。
     */
    @Test
    @DisplayName("没有待处置的改动：一件都不动，也不许去删别的运行的产物")
    void doesNothingWithoutPendingChanges() throws IOException {
        declare(DECLARATION);
        Path artifacts = artifacts("tools/20260930-133000");
        recordTestRun(artifacts);
        FakeCommandRunner docker = dockerReady();

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store(),
                new TestEnvironment(root, docker), Teardown.Choice.ACCEPT);

        assertThat(done.settled()).isFalse();
        assertThat(done.summarize()).contains("没有待处置的改动");
        assertThat(artifacts).as("这次没有处置东西，就不该动任何产物").exists();
        assertThat(docker.lines()).as("连一条命令都不该跑").isEmpty();
        assertThat(store().load(store().latestId()).settlement())
                .as("没处置就不写收场：写下去等于谎称已接受").isNull();
    }

    /**
     * 恢复失败：快照必须留着，收场留档也不许写。
     *
     * <p>这是唯一一件「做错了会丢用户代码」的事，所以它没有 best-effort 一说：
     * 快照是唯一还答得出「原文是什么」的东西，删了它用户就再也回不到这次运行之前；
     * 而写一句「已中断」下去，用户会以为已经回到起点了。
     */
    @Test
    @DisplayName("恢复失败：报错、快照留着、不写收场留档（用户可以重试）")
    void keepsTheSnapshotWhenRestoreFails() throws IOException {
        declare(DECLARATION);
        Files.writeString(root.resolve("Foo.java"), "old");
        WorkspaceSnapshot snapshot = markPending("Foo.java");
        Files.writeString(root.resolve("Foo.java"), "new");
        // 把快照里的原文副本抽掉：这正是「快照坏了」时恢复会遇到的情况
        Files.delete(snapshot.directory().resolve("files").resolve("Foo.java"));
        recordTestRun(artifacts("tools/20260930-140000"));

        assertThatThrownBy(() -> Teardown.settle(root, ProjectConfig.DEFAULT, store(),
                new TestEnvironment(root, dockerReady()), Teardown.Choice.INTERRUPT))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("快照留着");

        assertThat(waitingSnapshots()).as("回不去就留着它，让用户还能重试").hasSize(1);
        assertThat(store().load(store().latestId()).settlement()).isNull();
    }

    // ---------- 产物与环境数据：做不成时怎么交代 ----------

    /**
     * 产物要按留档删：跑过的那一份，加上<b>重新生成过</b>的那几份。
     *
     * <p>漏掉后者是上一批留下来的真实缺口：重新生成会新开一个 {@code tools/<时间戳>/}，
     * 而留档里只记着跑的那一份——于是 {@code tools/} 只增不减，而「接受之后产物没了」
     * 本来是用户唯一看得见的「收场真的做完了」。
     */
    @Test
    @DisplayName("产物：跑过的那一份和重新生成过的几份都删掉")
    void deletesEveryArtifactTheArchiveKnows() throws IOException {
        declare(DECLARATION);
        Files.writeString(root.resolve("Foo.java"), "old");
        markPending("Foo.java");
        Path ran = artifacts("tools/20260930-120000");
        Path regen = artifacts("tools/20260930-123000");
        Path again = artifacts("tools/20260930-124000");
        recordTestRun(ran);
        RunStore store = store();
        store.regenerated(store.latestId(), List.of(2, 3), "tools/20260930-123000");
        store.regenerated(store.latestId(), List.of(2, 3), "tools/20260930-124000");

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store,
                new TestEnvironment(root, dockerReady()), Teardown.Choice.ACCEPT);

        assertThat(ran).doesNotExist();
        assertThat(regen).as("重新生成的那一份也在留档里，所以也要删").doesNotExist();
        assertThat(again).doesNotExist();
        assertThat(done.artifacts()).hasSize(3);
    }

    @Test
    @DisplayName("没声明环境：一次进程都不起（收场不该为没配环境的项目付出任何代价）")
    void neverTouchesDockerWithoutDeclaration() throws IOException {
        Files.writeString(root.resolve("Foo.java"), "old");
        markPending("Foo.java");
        recordTestRun(artifacts("tools/20260930-150000"));
        FakeCommandRunner docker = dockerReady();

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store(),
                new TestEnvironment(root, docker), Teardown.Choice.ACCEPT);

        assertThat(done.reset()).isFalse();
        assertThat(docker.lines()).as("连 docker 都不该探一次").isEmpty();
        assertThat(done.summarize()).contains("没有需要重置的环境数据");
    }

    /**
     * reset 跑挂了：收场本身照常生效，问题如实报出来。
     *
     * <p>为什么不让它把整个请求变成失败：用户刚做的那个决定（接受还是中断）已经生效了、
     * 文件也处置完了。为一条清理命令回一个 500，他会以为自己的决定没生效。
     */
    @Test
    @DisplayName("清环境数据失败：收场照常、问题如实报出来（十五.9：清理做不到 100%）")
    void reportsWhenResettingFails() throws IOException {
        declare(DECLARATION);
        Files.writeString(root.resolve("Foo.java"), "old");
        markPending("Foo.java");
        Path artifacts = artifacts("tools/20260930-160000");
        recordTestRun(artifacts);
        FakeCommandRunner docker = dockerReady().fail("exec -T app sh -c rm -rf /data/*", 1,
                "read-only file system");
        TestEnvironment environment = initialized(docker);

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store(),
                environment, Teardown.Choice.ACCEPT);

        assertThat(done.settled()).as("收场是生效了的").isTrue();
        assertThat(done.reset()).as("重置没成功——原始原因在 problems 里，不假装清干净了").isFalse();
        assertThat(done.problems()).singleElement().asString()
                .contains("环境数据没重置").contains("read-only file system");
        assertThat(done.summarize())
                .as("没清成的时候不许说「没有需要重置的环境数据」——那是一句假话")
                .doesNotContain("没有需要重置的环境数据").contains("没收掉的");
        assertThat(artifacts).as("清理失败不该把处置本身也拦下").doesNotExist();
        assertThat(waitingSnapshots()).isEmpty();
    }

    /** 留档写不进去：如实报出来，但收场本身照常生效。 */
    @Test
    @DisplayName("留档写不进去：报出来，处置本身照常生效")
    void reportsWhenTheArchiveCannotBeWritten() throws IOException {
        Files.writeString(root.resolve("Foo.java"), "old");
        markPending("Foo.java");
        // 把 runs 目录做成一个文件：写记录必然失败
        Files.writeString(root.resolve(RunStore.DEFAULT_DIR), "不是目录");

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store(), null,
                Teardown.Choice.ACCEPT);

        assertThat(done.settled()).isTrue();
        assertThat(waitingSnapshots()).as("快照该删还是要删").isEmpty();
        assertThat(done.problems()).singleElement().asString().contains("留档");
    }

    // ---------- 辅助 ----------

    private RunStore store() {
        return new RunStore(root.resolve(RunStore.DEFAULT_DIR));
    }

    /** 造一个待处置的快照（校验通过那一种），并把目录内容动一下。 */
    private WorkspaceSnapshot markPending(String... files) {
        List<Path> paths = Arrays.stream(files).map(root::resolve).toList();
        return WorkspaceSnapshot.capture(new SafePathResolver(root),
                root.resolve(SnapshotConfig.DEFAULT_DIR), paths).markPending();
    }

    private List<WorkspaceSnapshot> waitingSnapshots() {
        return Teardown.waiting(root, ProjectConfig.DEFAULT);
    }

    private Path artifacts(String directory) throws IOException {
        Path path = root.resolve(directory);
        Files.createDirectories(path);
        Files.writeString(path.resolve("run.cmd"), "echo PASS\n");
        return path;
    }

    /** 造一条「跑过测试、第 1 条过了、第 2 与第 3 条没过」的运行记录。 */
    private void recordTestRun(Path artifacts) {
        RunRecorder recorder = RunRecorder.start(store(), TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);
        recorder.testsFinished(new TestOutcome(root.relativize(artifacts).toString().replace('\\', '/'),
                List.of(), 1, 1, VerificationResult.failed("测试脚本", "run", "两条没过"),
                List.of(), List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, false),
                        // 压根没跑到的那一条：它也算法「没过的」——没验不能算过
                        new TestOutcome.CaseResult(3, false)), List.of()));
        recorder.finished(AgentResult.testsFailed(1, List.of(), List.of(), "两条没过"));
    }

    private void declare(String source) throws IOException {
        Path file = root.resolve(EnvConfigLoader.relativePath());
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    /**
     * 一个已经初始化过的环境。
     *
     * <p>收场清的是<b>活着</b>的环境的数据（跑一次 {@code reset}）：没初始化过就没有 compose 文件，
     * 也就没有可重置的东西——所以这一条必须先起一次，否则测的是另一条分支。
     */
    private TestEnvironment initialized(FakeCommandRunner docker) {
        TestEnvironment environment = new TestEnvironment(root, docker);
        environment.up();
        return environment;
    }

    /** 假 runner：探得到 docker、查询类命令一律空输出、up 与 init/reset 都成功。 */
    private static FakeCommandRunner dockerReady() {
        return new FakeCommandRunner()
                .ok("version", "fake docker 1.0")
                .ok("ps -a", "")
                .ok("volume ls", "")
                .ok("network ls", "")
                .ok("down", "")
                .ok("up -d --wait", "")
                .ok("exec -T app", "");
    }
}
