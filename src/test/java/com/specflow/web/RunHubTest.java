package com.specflow.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("运行进度缓冲区")
class RunHubTest {

    private final RunHub hub = new RunHub();

    @Test
    @DisplayName("事件按 id 递增，界面靠游标增量拉取")
    void assignsIncrementingIds() {
        hub.startRun("run-1");

        assertThat(hub.publish("info", 1, 0, null, "第一条").id()).isEqualTo(1);
        assertThat(hub.publish("info", 1, 0, null, "第二条").id()).isEqualTo(2);
    }

    @Test
    @DisplayName("按游标只取新事件")
    void returnsEventsAfterCursor() {
        hub.startRun("run-1");
        hub.publish("info", 1, 0, null, "一");
        hub.publish("info", 1, 0, null, "二");
        hub.publish("info", 1, 0, null, "三");

        assertThat(hub.view(0).events()).hasSize(3);
        assertThat(hub.view(2).events()).extracting(RunEvent::text).containsExactly("二", "三");
        assertThat(hub.view(4).events()).isEmpty();
    }

    @Test
    @DisplayName("开始新运行时清空上一轮的事件并换新的 runId")
    void newRunResetsHistory() {
        hub.startRun("run-1");
        hub.publish("info", 1, 0, null, "旧事件");
        hub.finish();

        hub.startRun("run-2");

        assertThat(hub.runId()).isEqualTo("run-2");
        assertThat(hub.view(0).events()).isEmpty();
        assertThat(hub.publish("info", 1, 0, null, "新事件").id()).isEqualTo(1);
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
            hub.publish("info", 1, 0, null, "第 " + i + " 条");
        }

        RunHub.RunView view = hub.view(0);
        assertThat(view.events()).hasSize(500);
        assertThat(view.events().get(0).text()).isEqualTo("第 100 条");
    }

    @Test
    @DisplayName("日志事件带着它属于第几步，界面据此把一段日志按步分组")
    void logEventCarriesStep() {
        hub.startRun("run-1");

        RunEvent event = hub.publish("warn", 4, 2, null, "第 2 步：校验未通过");

        assertThat(event.step()).isEqualTo(2);
        assertThat(event.round()).isEqualTo(4);
        assertThat(event.type()).isEqualTo(RunEvent.TYPE_LOG);
    }

    /**
     * 步态是<b>结构化字段</b>，不是从文案里推出来的。
     *
     * <p>以前只推一句「第 2 步：加接口：成功」，界面得拿这句话去比对才画得出步态；
     * Java 里改一句措辞，界面就静默错位。现在由引擎在事件上写清楚是哪一档。
     */
    @Test
    @DisplayName("步级事件带着步态字段；轮级事件没有——它不改变某一步的状态")
    void stepEventsCarryTheStepState() {
        hub.startRun("run-1");

        RunEvent started = hub.publish("info", 0, 2, RunEvent.STEP_RUNNING, "第 2 步：开始");
        RunEvent finished = hub.publish("info", 0, 2, "SUCCESS", "第 2 步：成功");
        RunEvent round = hub.publish("info", 7, 2, null, "第 7 轮：调用模型…");

        assertThat(started.stepState()).isEqualTo(RunEvent.STEP_RUNNING);
        assertThat(finished.stepState()).isEqualTo("SUCCESS");
        assertThat(round.stepState()).as("轮级事件不带步态").isNull();
        assertThat(round.step()).as("它照样带着步号——两件事不是一回事").isEqualTo(2);
    }

    @Test
    @DisplayName("施工单是一次给全的：界面在第一步之前就知道总共有几步")
    void publishesWholePlanBeforeFirstStep() {
        hub.startRun("run-1");

        hub.publishPlan("共 3 步", Map.of("source", "GENERATED", "steps", List.of()));

        RunEvent event = hub.view(0).events().get(0);
        assertThat(event.type()).isEqualTo(RunEvent.TYPE_PLAN);
        assertThat(event.isPlan()).isTrue();
        assertThat(event.isResult()).isFalse();
        assertThat(event.payload()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) event.payload()).get("source")).isEqualTo("GENERATED");
    }

    @Test
    @DisplayName("结束事件带着结构化结果")
    void carriesResultPayload() {
        hub.startRun("run-1");
        hub.publishResult(Map.of("status", "SUCCESS"));

        RunEvent event = hub.view(0).events().get(0);
        assertThat(event.type()).isEqualTo(RunEvent.TYPE_RESULT);
        assertThat(event.isResult()).isTrue();
        assertThat(event.isPlan()).isFalse();
    }
}
