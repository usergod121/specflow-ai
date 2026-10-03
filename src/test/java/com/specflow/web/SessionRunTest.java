package com.specflow.web;

import com.specflow.history.RunStore;
import com.specflow.project.LlmConfig;
import com.specflow.project.ProjectConfig;
import com.specflow.project.SnapshotConfig;
import com.specflow.session.Session;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.tests.Teardown;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话在服务这一环上的接线（§19）：<b>跑第一轮会自动开一个会话、接着跑就是它的下一轮、
 * 门禁认得自己人、那一步撤回到得了磁盘</b>。
 *
 * <p>为什么这一条必须走真服务 + 假模型：<b>「一次会话 = N 轮」这件事没有单独的开关</b>。
 * 界面从来不告诉引擎「这是下一轮」——引擎自己从留档里看出「有一个会话开着」，
 * 于是下一次运行就是它的下一轮。这条接线一旦断了，界面上会表现为
 * 「每点一次运行就是一个新会话」：每一轮都从第 1 轮开始，而每一轮的快照都挡着下一轮
 * （门禁照旧），用户只能一轮一接受。<b>两边各自的测试都会是绿的</b>，
 * 所以这里用两次真运行把「第 1 轮、第 2 轮」钉住。
 */
@DisplayName("会话：跑一轮、接着跑一轮、撤回本轮")
class SessionRunTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("跑两次：第一次开一个会话，第二次就是它的第 2 轮（门禁放行自己人的快照）")
    void everyRunJoinsTheOpenSession() throws Exception {
        Files.writeString(root.resolve("Foo.java"), "old\n");
        try (StubModelServer model = StubModelServer.answering(
                // 每一轮开工前那两次「现生成施工单」的探测（桩不认识 STEPS 块，引擎退化成单步）
                "拆不开。", "拆不开。", patch("old", "new"),
                "拆不开。", "拆不开。", patch("new", "newer"))) {
            RunService service = service(model);

            service.start(request());
            awaitIdle(service);

            Map<String, Object> first = service.session();
            assertThat(first.get("present")).as("跑完一轮就有一个会话开着").isEqualTo(true);
            assertThat(first.get("round")).as("这是它的第 1 轮").isEqualTo(1);
            assertThat(first.get("rounds")).isEqualTo(1);
            assertThat(first.get("liveRounds")).as("这一轮的改动还在磁盘上").isEqualTo(1);
            assertThat(first.get("canUndoRound")).isEqualTo(true);
            assertThat(snapshots()).as("一轮一份快照").hasSize(1);

            // 第 2 轮：它必须<b>进得来</b>——老口径「有待处置的快照就拒绝开工」会把这里挡掉，
            // 而那正是用户说「点下一轮才继续」时要走的那一步
            service.start(request());
            awaitIdle(service);

            Map<String, Object> second = service.session();
            assertThat(second.get("round")).as("同一个会话的第 2 轮").isEqualTo(2);
            assertThat(second.get("rounds")).isEqualTo(2);
            assertThat(second.get("liveRounds")).isEqualTo(2);
            assertThat(snapshots()).hasSize(2);
            assertThat(second.get("id")).as("会话 id 从第 1 轮起就不变").isEqualTo(first.get("id"));

            // 留档里也要串得起来：两条记录同一个会话 id、轮次 1 与 2
            RunStore store = store();
            assertThat(store.sessionRecords((String) first.get("id"), allRecords()))
                    .extracting(record -> record.session().round()).containsExactly(1, 2);

            service.shutdown();
        }
    }

    @Test
    @DisplayName("撤回本轮：文件回到上一轮结束时的样子，会话还开着、还能接着跑")
    void undoRoundRollsBackOnlyTheLastRound() throws Exception {
        Files.writeString(root.resolve("Foo.java"), "old\n");
        try (StubModelServer model = StubModelServer.answering(
                "拆不开。", "拆不开。", patch("old", "new"),
                "拆不开。", "拆不开。", patch("new", "newer"))) {
            RunService service = service(model);
            service.start(request());
            awaitIdle(service);
            service.start(request());
            awaitIdle(service);

            Teardown.Done done = service.undoRound();

            assertThat(Files.readString(root.resolve("Foo.java")))
                    .as("回到第 1 轮结束时的样子").isEqualTo("new\n");
            assertThat(done.summarize()).contains("已撤回本轮").contains("会话还开着");
            assertThat(snapshots()).as("只丢掉第 2 轮那份快照").hasSize(1);
            Map<String, Object> payload = service.session();
            assertThat(payload.get("present")).as("会话还开着").isEqualTo(true);
            assertThat(payload.get("round")).as("轮次序号不复用：撤回过也是第 2 轮").isEqualTo(2);
            assertThat(payload.get("liveRounds")).isEqualTo(1);
            assertThat(payload.get("canUndoRound")).as("已经撤过的那一轮没得再撤").isEqualTo(false);
            assertThat((String) payload.get("undoRoundWhy")).contains("已经撤过");

            service.shutdown();
        }
    }

    @Test
    @DisplayName("接受：整个会话收场，快照清干净，界面上的会话视图随之消失")
    void acceptClosesTheWholeSession() throws Exception {
        Files.writeString(root.resolve("Foo.java"), "old\n");
        try (StubModelServer model = StubModelServer.answering(
                "拆不开。", "拆不开。", patch("old", "new"),
                "拆不开。", "拆不开。", patch("new", "newer"))) {
            RunService service = service(model);
            service.start(request());
            awaitIdle(service);
            service.start(request());
            awaitIdle(service);

            Teardown.Done done = service.accept();

            assertThat(done.rounds()).as("一次处置覆盖整个会话的 2 轮").isEqualTo(2);
            assertThat(done.summarize()).contains("整个会话 2 轮");
            assertThat(Files.readString(root.resolve("Foo.java")))
                    .as("接受就是文件不动").isEqualTo("newer\n");
            assertThat(snapshots()).isEmpty();
            assertThat(service.session().get("present"))
                    .as("接受是会话的出口：收完之后界面回到「还没有会话」").isEqualTo(false);

            service.shutdown();
        }
    }

    /**
     * 不属于这个会话的遗留快照<b>照旧挡门</b>。
     *
     * <p>为什么还要留这条闸：会话开着的日常就是「磁盘上挂着几份快照」，所以门禁必须
     * 放行自己人；但一份<b>会话之前</b>留下的、没人处置过的改动（改完就崩了、或者上一次
     * 用命令行跑的）不能被顺手吞进这个会话——它的改动混在里面，事后谁也分不清
     * 哪几处是哪一次的。
     */
    @Test
    @DisplayName("会话之前留下的遗留快照：照旧挡住下一轮，并说清是哪几份")
    void foreignSnapshotsStillBlockTheNextRound() throws Exception {
        Files.writeString(root.resolve("Foo.java"), "old\n");
        try (StubModelServer model = StubModelServer.answering(
                "拆不开。", "拆不开。", patch("old", "new"))) {
            RunService service = service(model);
            service.start(request());
            awaitIdle(service);

            // 一份「会话之前」留下的快照：把刚拍的那份改成很久以前的时间戳
            String name = snapshots().get(0);
            Path directory = snapshotRoot().resolve(name);
            Files.move(directory, snapshotRoot().resolve("20260101-000000-000.pending"));

            assertThatThrownBy(() -> service.start(request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不属于这个会话")
                    .hasMessageContaining("20260101-000000-000");

            service.shutdown();
        }
    }

    // ---------- 辅助 ----------

    private static String patch(String from, String to) {
        return "<<<<<<< SEARCH Foo.java\n" + from + "\n=======\n" + to + "\n>>>>>>> REPLACE\n";
    }

    /**
     * 这一次的请求。<b>关掉编译校验</b>（{@code verifyCompile=false}）：这台机器上的
     * 临时项目没有构建命令，开着它每一轮都会以「缺编译环境」收场并回滚——
     * 那样测的就不是会话，而是编译校验了（会话要的是「有改动留在磁盘上」这个前提，
     * 由 {@code SUCCESS_UNVERIFIED} 满足）。
     */
    private static RunRequest request() {
        return RunRequest.of(null, "把 a 改成 2", null, null, null, List.of("Foo.java"),
                null, null, null, Boolean.FALSE, 0, 4, null, List.of(), List.of());
    }

    private RunService service(StubModelServer model) throws Exception {
        Files.createDirectories(root.resolve(".specflow"));
        Files.writeString(root.resolve(".specflow").resolve("local.env"), "SPECFLOW_TEST_KEY=sk-test\n");
        ProjectConfig project = new ProjectConfig(null,
                new LlmConfig(model.baseUrl(), "stub", "SPECFLOW_TEST_KEY", 5, 0.0, 0),
                SnapshotConfig.DEFAULT);
        return new RunService(root, project, root.resolve(".specflow/templates"));
    }

    /** 等到这次运行收场（它的终态事件发出来为止）。 */
    private static void awaitIdle(RunService service) throws InterruptedException {
        for (int attempt = 0; attempt < 400 && service.hub().running(); attempt++) {
            Thread.sleep(25);
        }
        assertThat(service.hub().running()).as("这一次运行收场了").isFalse();
    }

    private RunStore store() {
        return new RunStore(root.resolve(RunStore.DEFAULT_DIR));
    }

    private List<com.specflow.history.RunRecord> allRecords() {
        return store().list().stream().map(summary -> store().load(summary.id())).toList();
    }

    private Path snapshotRoot() {
        return root.resolve(SnapshotConfig.DEFAULT_DIR);
    }

    private List<String> snapshots() {
        return WorkspaceSnapshot.names(snapshotRoot());
    }
}
