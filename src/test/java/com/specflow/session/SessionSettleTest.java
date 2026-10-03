package com.specflow.session;

import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话的四个动作（§19）：接受、中断、撤回本轮、撤回整个会话——在真磁盘上走一遍。
 *
 * <p>为什么要真磁盘：这四个动作的差别全都在「动哪几份快照、把文件恢复成谁的样子」上，
 * 而这件事只有拿真的文件比才看得准。纯折叠那一层（{@link SessionTest}）已经钉住了
 * 「哪几轮算数」，这里钉的是另半个问题：<b>点下去之后磁盘上到底变成了什么</b>。
 *
 * <p>两个撤销粒度是这一批最容易做错的地方，而做错的样子很安静：
 * 「撤回本轮」撤到了别人的改动、或者「撤回整个会话」只撤了最后一轮——
 * 界面上都会显示成一句「已撤回」，用户要过很久才会发现自己的代码回到了奇怪的地方。
 */
@DisplayName("会话：接受 / 中断 / 撤回本轮 / 撤回整个会话")
class SessionSettleTest {

    @TempDir
    Path root;

    // ---------- 两个撤销粒度 ----------

    /**
     * 撤回本轮 = 回到<b>上一轮结束时</b>的样子，会话还开着。
     *
     * <p>「还开着」是这条路与「中断」的全部差别：用户想的是「这一轮不算，我再来一遍」，
     * 而不是「这件事不做了」。把它做成中断，等于逼人把整个会话扔掉重新开。
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

    @Test
    @DisplayName("撤回整个会话：回到会话最开始的样子，会话仍然开着")
    void undoSessionGoesBackToTheVeryStart() throws IOException {
        session(3);

        Teardown.Done done = Teardown.undoSession(root, ProjectConfig.DEFAULT, store(), null);

        assertThat(Files.readString(file())).as("回到会话最开始").isEqualTo("v0\n");
        assertThat(snapshots()).as("三份快照一份不剩").isEmpty();
        assertThat(artifacts(1)).doesNotExist();
        assertThat(artifacts(3)).doesNotExist();
        assertThat(done.summarize()).contains("已撤回整个会话").contains("会话还开着");

        Session after = store().session(snapshotRoot()).orElseThrow();
        assertThat(after.rounds()).extracting(Session.Round::settlement)
                .containsExactly(RunRecord.Settlement.UNDO_SESSION,
                        RunRecord.Settlement.UNDO_SESSION, RunRecord.Settlement.UNDO_SESSION);
        assertThat(after.live()).isEmpty();
        assertThat(after.canUndoSession()).as("已经没有还留着的改动了").isFalse();
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

    @Test
    @DisplayName("中断：撤到会话最开始、会话收场（不再开着）")
    void interruptRollsBackTheWholeSessionAndClosesIt() throws IOException {
        session(3);

        Teardown.Done done = Teardown.settle(root, ProjectConfig.DEFAULT, store(), null,
                Teardown.Choice.INTERRUPT);

        assertThat(Files.readString(file())).as("中断 = 回到会话最开始").isEqualTo("v0\n");
        assertThat(snapshots()).isEmpty();
        assertThat(done.summarize()).contains("已中断").contains("会话最开始");
        assertThat(done.summarize()).as("中断是出口，不许说「会话还开着」")
                .doesNotContain("会话还开着");
        assertThat(store().session(snapshotRoot())).as("中断是出口：会话到此为止").isEmpty();
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
