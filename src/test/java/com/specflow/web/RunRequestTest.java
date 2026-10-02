package com.specflow.web;

import com.specflow.review.PlanReview;
import com.specflow.review.PlanStep;
import com.specflow.spec.Spec;
import com.specflow.spec.VerifySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 界面的运行请求 → 引擎的 {@link Spec}。
 *
 * <p>两道自检预算都要能穿过这一层：{@code maxRetry} 是「每一步试几次」，
 * {@code maxRounds} 是「一整单最多烧几次调用」。界面这一轮还没有它们的输入框，
 * 但字段一旦漏掉，下一批做界面时会出现「填了没反应」这种最难查的错。
 */
@DisplayName("运行请求")
class RunRequestTest {

    @Test
    @DisplayName("总轮次从请求传进 spec；不填就是自动")
    void carriesRoundBudget() {
        Spec explicit = request(12).toSpec();
        assertThat(explicit.verify().maxRounds()).isEqualTo(12);
        assertThat(explicit.verify().roundBudget(3)).as("手写的以它为准").isEqualTo(12);

        Spec automatic = request(null).toSpec();
        assertThat(automatic.verify().maxRounds()).isEqualTo(VerifySpec.AUTO_ROUNDS);
        assertThat(automatic.verify().roundBudget(3)).isEqualTo(9);
    }

    @Test
    @DisplayName("从 spec 还原表单时也带回来，否则存了草稿再打开就被悄悄改成自动了")
    void roundTripsThroughSpec() {
        Spec spec = request(12).toSpec();

        RunRequest reloaded = RunRequest.from(spec);

        assertThat(reloaded.maxRounds()).isEqualTo(12);
        assertThat(reloaded.toSpec().verify().maxRounds()).isEqualTo(12);
    }

    /**
     * 界面拿到 {@code /api/review} 的 {@code plan} 之后，是<b>原样</b>塞进运行请求带回来的。
     * 所以那份对象必须能被拆开重拼——写出去的是它、读回来的还是它。
     * 这条链一断，表现是「检查过了、点运行却 400」，而两边各自的测试都还是绿的。
     */
    @Test
    @DisplayName("检查返回的那份方案原样回传时还认得出来，包括施工单")
    void acceptsThePlanItJustReturned() throws IOException {
        PlanReview plan = PlanReview.of("摘要", "flowchart TD\n    A[入口] --> B[出口]",
                List.of(new PlanReview.MissingItem("表结构", PlanReview.MissingItem.Severity.QUALITY,
                        "可能写错列", "查询结果不对", "照现有 SQL 来")),
                List.of(new PlanStep(1, "第一步", List.of("Foo.java"), "能编译", true),
                        new PlanStep(2, "第二步", List.of("Foo.java", "Bar.java"), "能编译", false)));

        String json = Http.JSON.writeValueAsString(plan);
        RunRequest echo = Http.JSON.readValue(
                "{\"prompt\":\"改点东西\",\"targets\":[\"Foo.java\"],\"approvedPlan\":" + json + "}",
                RunRequest.class);

        assertThat(echo.approvedPlan().summary()).isEqualTo("摘要");
        assertThat(echo.approvedPlan().flowchart()).contains("flowchart TD");
        assertThat(echo.approvedPlan().missing()).singleElement()
                .satisfies(item -> assertThat(item.what()).isEqualTo("表结构"));
        assertThat(echo.approvedPlan().steps()).hasSize(2);
        assertThat(echo.approvedPlan().steps().get(0).intermediate()).isTrue();
        assertThat(echo.approvedPlan().steps().get(1).files())
                .containsExactly("Foo.java", "Bar.java");
    }

    private static RunRequest request(Integer maxRounds) {
        return RunRequest.of(null, "改点东西", null, null, null, List.of("Foo.java"),
                null, null, null, true, 6, maxRounds, null,
                // 回喂编号：这一条不是「下一轮」，所以是空的
                List.of());
    }

    /**
     * 回喂那几条失败用例的编号要能发得进来、也要认得出来（十五.6 第一条路）。
     *
     * <p>去重与排序在引擎那一侧做（见 {@code RunRequest.pickedRefeed}）：界面只管把勾中的
     * 编号发过来，而拼出来的那一段必须是稳定的——同一份勾选两次跑出来的提示词要一模一样。
     */
    @Test
    @DisplayName("回喂的编号：去重、丢非法值、升序，空表示不是「下一轮」")
    void carriesRefeedIndexes() {
        assertThat(request(null).pickedRefeed()).isEmpty();
        assertThat(RunRequest.of(null, "x", null, null, null, List.of("Foo.java"),
                null, null, null, true, 6, 3, null, List.of(8, 2, 8, 0, -1)).pickedRefeed())
                .containsExactly(2, 8);
    }

    /**
     * 用例清单是检查阶段产出的，测试阶段（以及下一批的界面）都靠它吃饭。
     * 它同样要经得起「写出去、原样读回来」这一趟——界面是把整个 plan 原样回传的。
     */
    @Test
    @DisplayName("用例清单要能原样回传：编号、要测什么、怎么测、分级、期望、对应的验收标准一个不丢")
    void carriesTestCasesBackIntoTheRun() throws IOException {
        PlanReview plan = PlanReview.of("摘要", "flowchart TD\n    A-->B", List.of(), List.of(),
                List.of(new PlanReview.TestCase(1, "按编号查得到", "用已有编号查一次",
                        PlanReview.TestCase.Level.MUST, "返回的那条 id 等于传入的编号",
                        "订单能按编号查询")));

        String json = Http.JSON.writeValueAsString(plan);
        RunRequest echo = Http.JSON.readValue(
                "{\"prompt\":\"改点东西\",\"targets\":[\"Foo.java\"],\"approvedPlan\":" + json + "}",
                RunRequest.class);

        assertThat(echo.approvedPlan().cases()).singleElement().satisfies(testCase -> {
            assertThat(testCase.index()).isEqualTo(1);
            assertThat(testCase.what()).isEqualTo("按编号查得到");
            assertThat(testCase.how()).contains("已有编号");
            assertThat(testCase.level()).isEqualTo(PlanReview.TestCase.Level.MUST);
            assertThat(testCase.expected()).contains("id 等于传入的编号");
            assertThat(testCase.acceptance()).contains("按编号查询");
        });
    }

    /**
     * 老记录、以及还没跟上这一版的界面，发回来的 plan 里没有 {@code cases} 这个键。
     * 读出来是 {@code null} 的话，测试阶段会拿它去 {@code isEmpty()}——一次运行跑到一半 NPE。
     * 「没带用例」和「没有用例」本来就该是同一个结果。
     */
    @Test
    @DisplayName("方案里没有用例这一项时是空清单，不是 null")
    void toleratesMissingCaseBlock() throws IOException {
        RunRequest echo = Http.JSON.readValue(
                "{\"prompt\":\"x\",\"targets\":[\"Foo.java\"],"
                        + "\"approvedPlan\":{\"summary\":\"摘要\",\"flowchart\":\"flowchart TD\"}}",
                RunRequest.class);

        assertThat(echo.approvedPlan().cases()).isEmpty();
        assertThat(echo.approvedPlan().steps()).isEmpty();
        assertThat(echo.approvedPlan().missing()).isEmpty();
    }
}
