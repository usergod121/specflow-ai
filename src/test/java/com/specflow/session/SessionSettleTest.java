package com.specflow.session;

import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.env.EnvConfigLoader;
import com.specflow.env.FakeCommandRunner;
import com.specflow.env.TestEnvironment;
import com.specflow.history.RunRecord;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.project.ProjectConfig;
import com.specflow.project.SnapshotConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.tests.Refeed;
import com.specflow.tests.Teardown;
import com.specflow.tests.TestOutcome;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话的三个动作（§19）：接受、中断并回到会话最初、撤回本轮——在真磁盘上走一遍。
 *
 * <p>为什么要真磁盘：这三个动作的差别全都在「动哪几份快照、把文件恢复成谁的样子」上，
 * 而这件事只有拿真的文件比才看得准。纯折叠那一层（{@link SessionTest}）已经钉住了
 * 「哪几轮算数」，这里钉的是另半个问题：<b>点下去之后磁盘上到底变成了什么</b>。
 *
 * <p>「撤回本轮」是这一批最容易做错的地方，而做错的样子很安静：
 * 它撤到了别人的改动——界面上显示成一句「已撤回本轮」，用户要过很久才会发现
 * 自己的代码回到了奇怪的地方。另一个曾经独立存在的「撤回整个会话」已经和中断合并
 * （它们连动几份快照都一样，见 {@code Teardown}），这里只剩三个动作。
 */
@DisplayName("会话：接受 / 中断并回到会话最初 / 撤回本轮")
class SessionSettleTest {

    @TempDir
    Path root;

    /** 声明里带一条 reset：会话收场时要跑它（清环境数据），而不是把容器收掉。 */
    private static final String DECLARATION = """
            image: "eclipse-temurin:17"
            workdir: "/work"
            reset:
              - "rm -rf /data/*"
            """;

    // ---------- 一步撤销 ----------

    /**
     * 撤回本轮 = 回到<b>上一轮结束时</b>的样子，会话还开着。
     *
     * <p>「还开着」是这条路与「中断并回到会话最初」的全部差别：用户想的是「这一轮不算，
     * 我再来一遍」，而不是「这件事不做了」。把它也做成中断，等于逼人把整个会话扔掉重新开。
     */
    @Test
    @DisplayName("撤回本轮：回到上一轮结束时的样子，只删这一轮的产物，会话还开着")
    void undoRoundGoesBackOneRound() throws IOException {
        List<String> contents = session(3);
        assertThat(Files.readString(file())).isEqualTo(contents.get(2));

        Teardown.Done done = Teardown.undoRound(root, ProjectConfig.DEFAULT, store(), null);

        assertThat(Files.readString(file()))
                .as("回到上一轮结束时（第 2 轮）的样子").isEqualTo(contents.get(1));
        assertThat(done.rounds()).as("这一次处置覆盖了会话的 3 轮").isEqualTo(3);
        assertThat(done.summarize()).contains("已撤回本轮").contains("会话还开着");
        assertThat(artifacts(3)).as("这一轮的产物跟着这一轮的改动一起走").doesNotExist();
        assertThat(artifacts(2)).as("上一轮的产物不动：它那一轮的改动还在磁盘上").exists();
        assertThat(snapshots()).as("只丢掉这一轮那份快照").hasSize(2);

        Session after = store().session(snapshotRoot()).orElseThrow();
        assertThat(after.rounds().get(2).settlement()).isEqualTo(RunRecord.Settlement.UNDO_ROUND);
        assertThat(after.live()).extracting(Session.Round::round)
                .as("第 1、2 轮还留着改动").containsExactly(1, 2);
        assertThat(after.canUndoRound())
                .as("第 3 轮已经撤过：要接着跑就点「下一轮」").isFalse();
    }

    /**
     * 最新那一轮一个字节都没留在磁盘上（跑失败、引擎自己回滚了）：撤回本轮<b>当场拒</b>。
     *
     * <p>不拒的话它会去撤<b>上一轮</b>那份快照——用户点的是「撤回本轮」，
     * 拿到的却是「上一轮也没了」。这种错只能在当场拦下。
     */
    @Test
    @DisplayName("最新那轮没留下改动时撤回本轮：当场拒，说的清为什么")
    void refusesUndoRoundWhenThereIsNothingToUndo() throws IOException {
        List<String> contents = session(2);
        // 第 2 轮的快照抽掉 = 它自己回滚过（引擎在失败路径上就是这么做的）
        deleteTree(snapshotRoot().resolve(snapshots().get(snapshots().size() - 1)));

        assertThatThrownBy(() -> Teardown.undoRound(root, ProjectConfig.DEFAULT, store(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没在磁盘上留下改动")
                .hasMessageContaining("自己回滚了");
        assertThat(Files.readString(file())).as("拒绝之后磁盘一个字节都没动")
                .isEqualTo(contents.get(1));
    }

    // ---------- 两个出口 ----------

    @Test
    @DisplayName("接受：文件留着、快照清干净、每轮的产物都删掉、整个会话落档为「已接受」")
    void acceptSettlesEveryRound() throws IOException {
        List<String> contents = session(3);

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store(), null,
                Teardown.Choice.ACCEPT);

        assertThat(Files.readString(file())).as("接受就是「文件不动」").isEqualTo(contents.get(2));
        assertThat(snapshots()).as("整个会话的快照一次清干净（一次处置覆盖 N 轮）").isEmpty();
        for (int k = 1; k <= 3; k++) {
            assertThat(artifacts(k)).as("第 " + k + " 轮的产物也要删").doesNotExist();
        }
        assertThat(done.rounds()).isEqualTo(3);
        assertThat(done.summarize()).contains("已接受").contains("整个会话 3 轮");
        assertThat(store().session(snapshotRoot()))
                .as("接受是会话的出口：收完之后没有开着的会话了").isEmpty();
        assertThat(store().sessionRecords(sessionId(), allRecords()))
                .extracting(record -> record.settlement().choice())
                .containsExactly(RunRecord.Settlement.ACCEPT, RunRecord.Settlement.ACCEPT,
                        RunRecord.Settlement.ACCEPT);
    }

    /**
     * 中断并回到会话最初：<b>合并后的那个动作</b>，五件事一件都不许少——
     * 结束会话、文件回到会话起点、清环境数据、删测试产物、留档记录。
     *
     * <p>为什么要一条条钉住：这五件事里任何一件漏掉，界面上都只显示成一句「已中断」。
     * 尤其是<b>删产物</b>与<b>清数据</b>——它们不是当场看得见的（要等下次翻 {@code tools/}
     * 或者下次跑测试拿到一份脏数据），而那两样正是「下一次运行莫名其妙地红」的来源。
     * 它同时是「整个会话一次处置」的落点：三份快照、三份产物，一次清干净。
     */
    @Test
    @DisplayName("中断并回到会话最初：文件回到会话起点、会话结束、产物删净、环境数据重置、留档记中断")
    void interruptRollsBackTheWholeSessionAndClosesIt() throws IOException {
        declare(DECLARATION);
        session(3);
        FakeCommandRunner docker = dockerReady();
        TestEnvironment environment = initialized(docker);

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store(), environment,
                Teardown.Choice.INTERRUPT);

        assertThat(Files.readString(file())).as("中断 = 回到会话最开始").isEqualTo("v0\n");
        assertThat(snapshots()).as("三份快照一份不剩").isEmpty();
        for (int k = 1; k <= 3; k++) {
            assertThat(artifacts(k)).as("第 " + k + " 轮的测试产物也要删").doesNotExist();
        }
        assertThat(docker.ran("exec -T app sh -c rm -rf /data/*"))
                .as("清环境数据：这一笔做的是 reset（不是把容器收掉）").isTrue();
        assertThat(docker.ran("down")).as("容器留着复用：收场不是关环境").isFalse();
        assertThat(done.summarize()).contains("已中断并回到会话最初").contains("会话最开始")
                .contains("环境数据已重置");
        assertThat(done.summarize()).as("中断是出口，不许说「会话还开着」")
                .doesNotContain("会话还开着");
        assertThat(done.summarize()).as("「结束会话」这件事必须在回音里说出来——"
                + "不说的话，用户以为它和「撤回本轮」一样还开着").contains("会话到此为止");
        assertThat(store().session(snapshotRoot())).as("中断是出口：会话到此为止").isEmpty();
        assertThat(store().sessionRecords(sessionId(), allRecords()))
                .as("每一轮都落上「已中断」，而且那句话说的是会话最初，不是「这个运行开始前」")
                .extracting(record -> record.settlement().choice())
                .containsExactly(RunRecord.Settlement.INTERRUPT, RunRecord.Settlement.INTERRUPT,
                        RunRecord.Settlement.INTERRUPT);
        assertThat(store().sessionRecords(sessionId(), allRecords()).get(1).settlementSummary())
                .as("留档里那句收场话说的必须是「回到会话最初」（不是「这个运行开始前」）")
                .contains("会话最初");
        assertThat(loadRound(2).settlementSummary())
                .as("界面上历史那一行用的就是同一句")
                .contains("会话最初");
    }

    /**
     * 撤回过的那一轮，后来接受整个会话时<b>不许被改写成「已接受」</b>。
     *
     * <p>那一轮的改动真的没留在磁盘上，把它写成已接受就是留档撒谎——
     * 而事后翻记录的人正是靠这一栏判断「这一轮的改动最后留没留」。
     * 同时还要保证：会话照样被认为已经收场（不然它会永远显示成开着的）。
     */
    @Test
    @DisplayName("先撤回本轮再接受：那一轮保留撤回档，会话照样算收场")
    void acceptingKeepsTheUndoOnTheRoundThatWasRolledBack() throws IOException {
        session(3);
        Teardown.undoRound(root, ProjectConfig.DEFAULT, store(), null);

        Teardown.settle(root, ProjectConfig.DEFAULT, store(), null, Teardown.Choice.ACCEPT);

        List<RunRecord> records = store().sessionRecords(sessionId(), allRecords());
        assertThat(records).extracting(record -> record.settlement().choice())
                .containsExactly(RunRecord.Settlement.ACCEPT, RunRecord.Settlement.ACCEPT,
                        RunRecord.Settlement.UNDO_ROUND);
        assertThat(store().session(snapshotRoot()))
                .as("会话收场了——只看最后那一轮的话，这里会永远显示成开着的").isEmpty();
    }

    // ---------- 辅助：造一个 N 轮的会话 ----------

    /**
     * 造一个 N 轮的会话，一轮一轮地走真链路：拍快照 → 改文件 → 落一条属于这一轮的留档。
     *
     * <p>每轮之间停一下：快照是<b>按时间窗口</b>认领到轮次上的（见 {@code Session}），
     * 而这里的每一轮只有几毫秒——不留出间隔，几轮的开工时刻会挤在同一毫秒里，
     * 测的就成了「时间戳分辨率」而不是会话本身。真实运行里每轮要几分钟，不存在这个问题。
     *
     * @return 每一轮跑完之后文件的内容（下标 = 轮次 - 1）
     */
    private List<String> session(int rounds) throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "v0\n");
        List<String> after = new ArrayList<>();
        String sessionId = "";
        for (int k = 1; k <= rounds; k++) {
            RunRecord.SessionRef ref = k == 1
                    ? RunRecord.SessionRef.opening()
                    : RunRecord.SessionRef.next(sessionId, k);
            RunRecorder recorder = RunRecorder.start(store(), TestSpecs.spec(List.of("Foo.java")),
                    null, AgentListener.NOOP, Refeed.none(), List.of(), ref);
            // 快照拍在记录开工之后、下一轮开工之前：这正是「这一轮的进入点」
            WorkspaceSnapshot.capture(new SafePathResolver(root), snapshotRoot(), List.of(file))
                    .markPending();
            String content = "v" + k + "\n";
            Files.writeString(file, content);
            after.add(content);
            Path artifacts = artifacts(k);
            Files.createDirectories(artifacts);
            Files.writeString(artifacts.resolve("run.cmd"), "echo PASS\n");
            recorder.testsFinished(outcome(root.relativize(artifacts).toString().replace('\\', '/')));
            recorder.finished(AgentResult.testsFailed(1, List.of(), List.of(), "第 " + k + " 轮"));
            // 会话 id 只在第 1 轮定下来（= 那一轮的记录 id），后面几轮沿用它
            if (sessionId.isEmpty()) {
                sessionId = store().latestId();
            }
            sleep();
        }
        return after;
    }

    private static TestOutcome outcome(String directory) {
        return new TestOutcome(directory, List.of(), 1, 1,
                VerificationResult.failed("测试脚本", "run", "一条没过"),
                List.of(), List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, false)), List.of());
    }

    private static void sleep() {
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private RunStore store() {
        return new RunStore(root.resolve(RunStore.DEFAULT_DIR));
    }

    private List<RunRecord> allRecords() {
        return store().list().stream().map(summary -> store().load(summary.id())).toList();
    }

    /** 这几条记录属于的那个会话 id（= 第 1 轮那条记录的 id）。 */
    private String sessionId() {
        return allRecords().get(0).session().id();
    }

    /**
     * 折一次会话，取第 n 轮。
     *
     * <p>为什么不用 {@code store().session(...)}：会话收场之后它按定义就查不到了
     * （那是「还开着吗」的判据），而这里要看的正是收场之后那一轮的留档怎么说。
     */
    private Session.Round loadRound(int round) {
        return Session.of(sessionId(), allRecords(), snapshots()).rounds().stream()
                .filter(one -> one.round() == round).findFirst().orElseThrow();
    }

    /** 写一份 {@code .specflow/env.yaml}：只有声明了环境，收场才会去清数据。 */
    private void declare(String source) throws IOException {
        Path path = root.resolve(EnvConfigLoader.relativePath());
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    /**
     * 一个已经初始化过的环境（假 docker）。
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

    private Path snapshotRoot() {
        return root.resolve(SnapshotConfig.DEFAULT_DIR);
    }

    private List<String> snapshots() {
        return WorkspaceSnapshot.names(snapshotRoot());
    }

    private Path file() {
        return root.resolve("Foo.java");
    }

    private Path artifacts(int round) {
        return root.resolve("tools/round-" + round);
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
