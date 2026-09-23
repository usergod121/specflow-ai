package com.specflow.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("运行进度缓冲区")
class RunHubTest {

    private final RunHub hub = new RunHub();

    @Test
    @DisplayName("事件按 id 递增，界面靠游标增量拉取")
    void assignsIncrementingIds() {
        hub.startRun("run-1");

        assertThat(hub.publish("info", 1, "第一条").id()).isEqualTo(1);
        assertThat(hub.publish("info", 1, "第二条").id()).isEqualTo(2);
    }

    @Test
    @DisplayName("按游标只取新事件")
    void returnsEventsAfterCursor() {
        hub.startRun("run-1");
        hub.publish("info", 1, "一");
        hub.publish("info", 1, "二");
        hub.publish("info", 1, "三");

        assertThat(hub.view(0).events()).hasSize(3);
        assertThat(hub.view(2).events()).extracting(RunEvent::text).containsExactly("二", "三");
        assertThat(hub.view(4).events()).isEmpty();
    }

    @Test
    @DisplayName("开始新运行时清空上一轮的事件并换新的 runId")
    void newRunResetsHistory() {
        hub.startRun("run-1");
        hub.publish("info", 1, "旧事件");
        hub.finish();

        hub.startRun("run-2");

        assertThat(hub.runId()).isEqualTo("run-2");
        assertThat(hub.view(0).events()).isEmpty();
        assertThat(hub.publish("info", 1, "新事件").id()).isEqualTo(1);
    }

    @Test
    @DisplayName("已有任务在跑时拒绝第二个——两个 Agent 同时改同一批文件会互相踩")
    void rejectsConcurrentRun() {
        hub.startRun("run-1");

        assertThatThrownBy(() -> hub.startRun("run-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有任务正在运行");
    }

    @Test
    @DisplayName("finish 之后可以再次启动")
    void allowsRunAfterFinish() {
        hub.startRun("run-1");
        hub.finish();

        assertThat(hub.running()).isFalse();
        assertThat(hub.startRun("run-2")).isEqualTo("run-2");
    }

    @Test
    @DisplayName("事件数量有上限，超出后丢弃最早的")
    void capsEventCount() {
        hub.startRun("run-1");
        for (int i = 0; i < 600; i++) {
            hub.publish("info", 1, "第 " + i + " 条");
        }

        RunHub.RunView view = hub.view(0);
        assertThat(view.events()).hasSize(500);
        assertThat(view.events().get(0).text()).isEqualTo("第 100 条");
    }

    @Test
    @DisplayName("结束事件带着结构化结果")
    void carriesResultPayload() {
        hub.startRun("run-1");
        hub.publishResult(java.util.Map.of("status", "SUCCESS"));

        RunEvent event = hub.view(0).events().get(0);
        assertThat(event.type()).isEqualTo(RunEvent.TYPE_RESULT);
        assertThat(event.isResult()).isTrue();
    }
}
