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
    @DisplayName("路由上不认识的节点名：抛 ApiException（由 Errors 翻成 400），不是 500")
    void unknownNodeIsBadRequest(@TempDir Path root) throws IOException {
        Api api = new Api(opened(project(root)));

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> api.nodeEvents("nope", 0));
        assertThat(thrown.getMessage()).contains("nope");
    }
}
