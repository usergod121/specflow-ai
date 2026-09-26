package com.specflow.web;

import com.specflow.agent.AgentListener;
import com.specflow.project.LlmConfig;
import com.specflow.project.ProjectConfig;
import com.specflow.review.PlanStep;
import com.specflow.review.ReviewOutcome;
import com.specflow.review.StepAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「停止」这条链路上，只有服务这一环没有别的地方管：界面把请求发到
 * {@code /api/cancel}，路由调 {@link RunService#cancel()}，引擎每轮开头问
 * {@link RunService#cancelled()}。
 *
 * <p>引擎那一侧（问到真就回滚停下）由 {@code DevelopmentAgentTest} 覆盖，
 * 路由那一侧（没有任务时 409、方法不对 405）由 {@code WebServerTest} 覆盖。
 * 这里只钉住中间这一环——「请求过停止之后，引擎问到的就是真」，
 * 否则界面上的按钮点了等于没点，而两边各自的测试都还是绿的。
 */
@DisplayName("停止运行")
class RunServiceTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("没点过停止时，引擎问到的是假")
    void notCancelledByDefault() {
        RunService service = service();

        assertThat(service.cancelled()).isFalse();

        service.shutdown();
    }

    @Test
    @DisplayName("点过停止之后，引擎问到的就是真")
    void cancelAsksTheAgentToStop() {
        RunService service = service();

        service.cancel();

        assertThat(service.cancelled()).isTrue();

        service.shutdown();
    }

    /**
     * 步级事件是界面这批新拿到的东西，形状在这里钉住：
     * 施工单一次给全（含来源与额外花的调用），每一步的开始/结束是一条带 {@code step} 的日志。
     * 界面靠 {@code step} 把一段乱序的日志按步分组，也靠它做「某一步的 diff」。
     */
    @Test
    @DisplayName("施工单一次推全，后面每条日志都带着它属于第几步")
    void pushesPlanAndStepScopedEvents() {
        RunService service = service();
        PlanStep first = new PlanStep(1, "先加接口", List.of("Foo.java"), "能编译", true);
        PlanStep second = new PlanStep(2, "接上实现", List.of("Bar.java"), "能编译", false);

        service.stepsResolved(List.of(first, second), AgentListener.StepsSource.GENERATED, 1);
        service.stepStarted(first);
        service.roundStarted(1);
        service.stepFinished(first, AgentListener.StepState.INTERMEDIATE);
        service.stepStarted(second);
        service.stepFinished(second, AgentListener.StepState.SUCCESS);

        List<RunEvent> events = service.hub().view(0).events();
        assertThat(events).hasSize(6);

        RunEvent plan = events.get(0);
        assertThat(plan.type()).isEqualTo(RunEvent.TYPE_PLAN);
        assertThat(plan.step()).isZero();
        assertThat(plan.text()).contains("现生成的施工单").contains("共 2 步").contains("1 次模型调用");
        assertThat(plan.payload()).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) plan.payload();
        assertThat(payload.get("source")).isEqualTo("GENERATED");
        assertThat(payload.get("probeCalls")).isEqualTo(1);
        assertThat(payload.get("steps")).isEqualTo(List.of(first, second));

        assertThat(events).filteredOn(event -> event.type().equals(RunEvent.TYPE_LOG))
                .extracting(RunEvent::step).containsExactly(1, 1, 1, 2, 2);
        assertThat(events.get(1).text()).as("第 1 步的日志挂在第 1 步上").contains("第 1 步").contains("先加接口");
        assertThat(events.get(2).text()).contains("第 1 轮");
        assertThat(events.get(3).level()).as("中间态要显眼：此刻磁盘上的代码是坏的").isEqualTo("warn");
        assertThat(events.get(5).level()).isEqualTo("info");
        // 步态是结构化字段：步开始/结束各推一档，轮级那条不推（它不改变某一步的状态）
        assertThat(events).filteredOn(event -> event.stepState() != null)
                .extracting(RunEvent::stepState)
                .containsExactly(RunEvent.STEP_RUNNING, "INTERMEDIATE", RunEvent.STEP_RUNNING, "SUCCESS");
        assertThat(events.get(2).stepState()).as("「第 1 轮：调用模型…」不是步态").isNull();

        service.shutdown();
    }

    /**
     * 步态由引擎在事件里说清楚，界面不再拿中文文案去猜。
     *
     * <p>这一条盯的是「上游真的发了这个字段」：只改界面、上游不发，
     * 每一步都会停在「未做」——那看起来像界面没画，其实是没人告诉它。
     * 三步都要在：开始（进行中）、回滚去重试（又回到进行中）、结束（终态枚举名）。
     */
    @Test
    @DisplayName("步级事件的步态：开始是进行中，回滚后回到进行中，结束给终态枚举名")
    void pushesStructuredStepState() {
        RunService service = service();
        PlanStep first = new PlanStep(1, "先加接口", List.of("Foo.java"), "能编译", false);
        PlanStep second = new PlanStep(2, "接上实现", List.of("Bar.java"), "能编译", false);

        service.stepStarted(first);
        service.stepRestored(first, 1, "校验未通过");
        service.stepFinished(first, AgentListener.StepState.SUCCESS);
        service.stepStarted(second);
        service.stepFinished(second, AgentListener.StepState.FAILED);

        assertThat(service.hub().view(0).events()).extracting(RunEvent::stepState)
                .containsExactly(RunEvent.STEP_RUNNING, RunEvent.STEP_RUNNING, "SUCCESS",
                        RunEvent.STEP_RUNNING, "FAILED");

        service.shutdown();
    }

    private RunService service() {
        return new RunService(root, ProjectConfig.DEFAULT, root.resolve(".specflow/templates"));
    }

    /**
     * 检查阶段那条链——模型响应 → 解析 → 两块机器校验 → {@link ReviewOutcome}。
     *
     * <p>起一个假模型服务把整条链走通，而不是只单测 {@link StepAudit}：
     * 光测那一层的话，「核了但结果没接进返回体」这种断线照样是绿的。
     */
    @Test
    @DisplayName("检查的返回体里带着施工单和它的机器校验结果")
    void reviewReturnsPlanAndStepAudit() throws Exception {
        String answer = """
                <<<<<<< SUMMARY
                分三步做。
                >>>>>>> SUMMARY

                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW

                <<<<<<< STEPS
                1 | 先改一个清单外的类 | src/main/java/demo/Nope.java | 能编译 | 自洽
                2 | 再把 a 改成 2 | src/main/java/demo/Foo.java | 能编译 | 自洽
                3 | 最后收尾 | src/main/java/demo/Foo.java | 能编译 | 中间态
                >>>>>>> STEPS
                """;
        try (StubModelServer model = StubModelServer.answering(answer)) {
            Files.createDirectories(root.resolve(".specflow"));
            Files.writeString(root.resolve(".specflow").resolve("local.env"),
                    "SPECFLOW_TEST_KEY=sk-test\n");
            ProjectConfig project = new ProjectConfig(null,
                    new LlmConfig(model.baseUrl(), "stub", "SPECFLOW_TEST_KEY", 5, 0.0, 0),
                    null);
            RunService service = new RunService(root, project, root.resolve(".specflow/templates"));

            ReviewOutcome outcome = service.review(RunRequest.of(null, "加一个接口", null, null, null,
                    List.of("src/main/java/demo/Foo.java"), null, null, null, null, null, null));
            service.shutdown();

            assertThat(outcome.plan().steps()).as("施工单解析出来了").hasSize(3);
            assertThat(outcome.plan().steps().get(1).goal()).isEqualTo("再把 a 改成 2");
            // 两条硬拦：清单外的文件、最后一步标中间态
            assertThat(outcome.stepAudit().blocking()).isTrue();
            assertThat(outcome.stepAudit().findings()).hasSize(2);
            assertThat(outcome.stepAudit().findings()).extracting(StepAudit.Finding::step)
                    .containsExactlyInAnyOrder(1, 3);
        }
    }
}
