package com.specflow.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>路由这一层</b>：不启 Spring，直接 new 出来调（{@code Api(Session)} 是包内构造）。
 *
 * <h2>为什么这么测，而不是 MockMvc</h2>
 * 这个项目刻意只引了 {@code spring-boot-starter-web}，没有 {@code spring-boot-starter-test}——
 * 为了一个三五个 GET 冒烟去动 pom、再养一个 Spring 上下文，代价比收益大。
 * 而 <code>Api</code> 的每个方法都是"取参数 → 调下层 → 拼响应"的纯 Java，
 * 直接调它就能钉住**响应的形状**（键在不在、类型对不对、没打开项目时会不会炸）。
 *
 * <p>评审指出过：这个文件有 1000 多行，此前<b>一条测试都没有</b>——把路由改坏，
 * 175 条测试一条都不会红，只有真跑起来才知道。这一组就是补那个窟窿。
 */
class ApiTest {

    /**
     * 别碰用户真实的那份「最近打开」列表：`Session.open()` 会顺手 remember，
     * 而这里开的是 @TempDir——不隔离的话每跑一次测试就往用户欢迎页里塞一堆
     * {@code junit-…} 死条目（真发生过：九个点了没用的条目把真项目挤出了前十）。
     */
    @org.junit.jupiter.api.BeforeAll
    static void pinRecent(@TempDir Path temp) throws IOException {
        System.setProperty("specflow.recent", temp.resolve("recent.json").toString());
    }

    /** 造一个"能打开"的项目：有 .specflow + 一条流程的骨架。 */
    private static Path project(Path root) throws IOException {
        Path specflow = root.resolve(".specflow");
        Files.createDirectories(specflow);
        Files.writeString(specflow.resolve("project.yaml"),
                "build:\n  compile: \"javac -d out src/Main.java\"\n");
        return root;
    }

    private static Session opened(Path root) {
        Session session = new Session();
        session.open(root);
        return session;
    }

    @Test
    @DisplayName("没打开项目时：state 说没打开，flows 是空表，不会抛")
    void nothingOpenIsSafe() {
        Api api = new Api(new Session());

        assertThat(api.state()).containsEntry("open", false);
        assertThat((List<?>) api.flows(20).get("flows")).isEmpty();
        assertThat(api.flows(20).get("current")).isEqualTo("");
    }

    @Test
    @DisplayName("/api/state：打开之后有 root / project / flowId / recent")
    void stateAfterOpen(@TempDir Path root) throws IOException {
        Api api = new Api(opened(project(root)));

        Map<String, Object> state = api.state();

        assertThat(state).containsEntry("open", true);
        assertThat(state.get("root")).asString().endsWith(root.getFileName().toString());
        assertThat(state.get("flowId")).asString().isNotBlank();
        assertThat(state).containsKeys("recent", "project");
    }

    @Test
    @DisplayName("/api/flow：五格都在，每格有 key/state/blockedReason/facts")
    void flowViewShape(@TempDir Path root) throws IOException {
        Api api = new Api(opened(project(root)));

        Map<String, Object> view = api.flow();

        assertThat(view).containsKeys("flowId", "nodes", "cursor", "stage");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) view.get("nodes");
        assertThat(nodes).hasSize(5);
        assertThat(nodes).extracting(one -> String.valueOf(one.get("key")))
                .containsExactly("env", "probe", "cases", "dev", "test");
        // 第一格是 READY（可以做、还没冻结），其余 LOCKED —— 闸的顺序规则在 view 里就体现出来
        assertThat(nodes.get(0)).containsEntry("state", "READY");
        assertThat(nodes.get(1)).containsEntry("state", "LOCKED");
        assertThat(nodes.get(0)).containsKeys("blockedReason", "facts");
    }

    @Test
    @DisplayName("/api/node/{k}/events：首次读不回放历史，之后按游标增量给")
    void eventsAreIncremental(@TempDir Path root) throws IOException {
        Session session = opened(project(root));
        Api api = new Api(session);

        Map<String, Object> first = api.nodeEvents("env", 0);
        assertThat(first).containsKeys("events", "running", "stage", "next");
        long before = ((Number) first.get("next")).longValue();

        // 写一条事件之后：从"上次的游标"往后拉，正好只拿到这一条
        session.events().warn("env", "一条测试事件");
        Map<String, Object> second = api.nodeEvents("env", before);
        @SuppressWarnings("unchecked")
        List<com.specflow.core.Event> rows = (List<com.specflow.core.Event>) second.get("events");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).text()).asString().contains("一条测试事件");
        // next 的定义是"下一次该从哪一行开始"，所以它等于最后一条的 id + 1
        assertThat(((Number) second.get("next")).longValue()).isEqualTo(rows.get(0).id() + 1);
    }

    @Test
    @DisplayName("/api/node/test/approvals 与 /checkpoint：没有东西时也给一份合法的空形状")
    void testPanelsAreHonestWhenEmpty(@TempDir Path root) throws IOException {
        Api api = new Api(opened(project(root)));

        Map<String, Object> approvals = api.testApprovals();
        assertThat((List<?>) approvals.get("approvals")).isEmpty();
        assertThat(approvals).containsKeys("waitSeconds", "note");

        Map<String, Object> checkpoint = api.testCheckpoint();
        assertThat(checkpoint).containsEntry("present", false);
    }

    @Test
    @DisplayName("/api/node/probe/save：context 传一段字符串（界面那个 textarea）不会被悄悄清空")
    @SuppressWarnings("unchecked")
    void probeSaveKeepsStringContext(@TempDir Path root) throws IOException {
        Session session = opened(project(root));
        Api api = new Api(session);
        // 把 ① 做完，② 才可动。容器在这里起不来，所以照闸要的那几个事实直接写一笔再冻住
        session.flow().note(com.specflow.flow.Node.ENV, Map.of(
                com.specflow.flow.Facts.ENV_NOT_NEEDED, "true",
                com.specflow.flow.Facts.CONTAINER_BUILD, "true"));
        session.flow().freeze(com.specflow.flow.Node.ENV);

        // files / context 给的是**多行字符串**（前端 textarea 的原样），changes 给的是数组
        Map<String, Object> saved = api.probeSave(Map.of(
                "files", "src/Main.java\nREADME.md",
                "changes", List.of("src/Main.java"),
                "context", "Main.java 是唯一入口\nREADME 里写了期望输出",
                "prompt", "修好 Main"));

        Map<String, Object> probe = (Map<String, Object>) saved.get("probe");
        // 这两行就是这个 bug 的形状：以前 String 走到 lines() 里被当成「不是 List」→ 清成空
        assertThat((List<String>) probe.get("context"))
                .containsExactly("Main.java 是唯一入口", "README 里写了期望输出");
        assertThat((List<String>) probe.get("files")).containsExactly("src/Main.java", "README.md");

        // 盘上读回来的一模一样（真相在盘上，不在内存里）
        assertThat((List<String>) api.probeView().get("context"))
                .containsExactly("Main.java 是唯一入口", "README 里写了期望输出");
    }

    @Test
    @DisplayName("/api/flow：每一格都带 actions（漏了这一行 = 五个页面全都没有按钮）")
    @SuppressWarnings("unchecked")
    void flowViewShipsActions(@TempDir Path root) throws IOException {
        Api api = new Api(opened(project(root)));

        Map<String, Object> view = api.flow();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) view.get("nodes");

        for (Map<String, Object> node : nodes) {
            // 这里拿到的是 Actions.ActionSpec（进程内），serialize 成 JSON 后就是前端 lib/actions.js
            // 认的那几栏：id 用来绑处理函数、label 显示、menu 决定进页头还是「⋯」
            List<com.specflow.flow.Actions.ActionSpec> actions =
                    (List<com.specflow.flow.Actions.ActionSpec>) node.get("actions");
            assertThat(actions)
                    .as("「%s」这一格的动作表", node.get("key"))
                    .isNotNull()
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(actions.get(0).id()).as("id 不能是空的：前端按它绑处理函数").isNotBlank();
            assertThat(actions.get(0).label()).as("label 不能是空的：界面上要显示它").isNotBlank();
            // 叫停必须一路走到界面上：后端声明了但前端没接、或者某一格干脆没声明，
            // 用户就会在看到「点『叫停』」这句话的地方找不到那一枚（⑤ 卡死时正是这样）。
            assertThat(actions.stream().anyMatch(one -> one.id().equals("cancel") && one.onlyWhileRunning()))
                    .as("「%s」这一格的动作表里没有叫停", node.get("key"))
                    .isTrue();
        }

        // ① 的两个关键入口必须在（起环境是它唯一能往下走的路；冻结是解锁下游的唯一入口）
        List<com.specflow.flow.Actions.ActionSpec> env =
                (List<com.specflow.flow.Actions.ActionSpec>) nodes.get(0).get("actions");
        assertThat(env.stream().map(com.specflow.flow.Actions.ActionSpec::id).toList())
                .contains("up", "freeze");
    }

    @Test
    @DisplayName("/api/node/cancel：没活在跑时是 409 并说清「没有东西可以停」，不是回一句「已停下」")
    void cancelWithoutARunIsConflict(@TempDir Path root) throws IOException {
        Api api = new Api(opened(project(root)));

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                api::cancel);
        assertThat(thrown.getMessage())
                .as("话术要让人知道「现在没东西可停」，而不是以为叫停生效了")
                .contains("没有在跑");
        assertThat(thrown).as("这一档必须是 409（「现在不能这么做」），不是 500（「引擎炸了」）")
                .isInstanceOf(com.specflow.flow.FlowRejected.class);
        assertThat(((com.specflow.flow.FlowRejected) thrown).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("路由上不认识的节点名：抛 ApiException（由 Errors 翻成 400），不是 500")
    void unknownNodeIsBadRequest(@TempDir Path root) throws IOException {
        Api api = new Api(opened(project(root)));

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> api.nodeEvents("nope", 0));
        assertThat(thrown.getMessage()).contains("nope");
    }
}
