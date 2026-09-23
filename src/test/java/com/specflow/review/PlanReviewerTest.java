package com.specflow.review;

import com.specflow.TestSpecs;
import com.specflow.context.ContextAssembler;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.template.TemplateRegistry;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("检查阶段")
class PlanReviewerTest {

    @TempDir
    Path root;

    private static final String RESPONSE = """
            <<<<<<< SUMMARY
            复用已有写法加一个查询接口。
            >>>>>>> SUMMARY
            <<<<<<< FLOW
            flowchart TD
                A[GET /orders] --> B[OrderService 查询]
            >>>>>>> FLOW
            """;

    @Test
    @DisplayName("检查看到的是和开发阶段同一份上下文，只是协议不同")
    void reviewUsesSameContextWithReviewProtocol() throws IOException {
        Files.writeString(root.resolve("OrderService.java"), "class OrderService { /* 已有实现 */ }");
        RecordingLlm llm = new RecordingLlm(RESPONSE);

        PlanReview review = reviewer(llm).review(TestSpecs.spec(List.of("OrderService.java")));

        assertThat(review.flowchart()).contains("GET /orders");
        assertThat(llm.messages).hasSize(2);

        // 协议换成检查阶段的，不再要求输出补丁块
        assertThat(llm.messages.get(0).content()).contains(ReviewProtocol.FLOW_MARKER);
        assertThat(llm.messages.get(0).content()).doesNotContain("<<<<<<< SEARCH");

        // 上下文仍然是同一份：目标文件的内容在里面
        assertThat(llm.messages.get(1).content()).contains("已有实现").contains("## 需求");
    }

    @Test
    @DisplayName("缺失清单要喂回开发阶段，但换了个说法：按默认值直接写，别再停下来问")
    void renderFeedsFallbacksIntoDevelopment() {
        PlanReview review = PlanReview.of("摘要文字", "flowchart TD\n    A-->B",
                List.of(new PlanReview.MissingItem("orders 表结构",
                        PlanReview.MissingItem.Severity.BLOCKING, "写不出 SQL", "查询会报错",
                        "先按 id/order_no/status 三列写")));

        assertThat(review.render())
                .contains("摘要文字")
                .contains("```mermaid")
                .contains("flowchart TD")
                // 用户确认过的那份方案里，模型自己定的默认值必须跟着走，
                // 否则它在开发阶段会把同一件事重新猜一遍，猜出另一套
                .contains("orders 表结构")
                .contains("先按 id/order_no/status 三列写")
                .contains("不要停下来问")
                .contains("写明你假设了什么");
    }

    @Test
    @DisplayName("没给默认值的项也要有话说，不能渲染成空白的一条")
    void renderExplainsMissingFallback() {
        PlanReview review = PlanReview.of("", "flowchart TD\n    A-->B",
                List.of(new PlanReview.MissingItem("命名习惯",
                        PlanReview.MissingItem.Severity.QUALITY, "可能不一致", "名字不同", "")));

        assertThat(review.render()).contains("命名习惯").contains("按常规做法处理");
    }

    @Test
    @DisplayName("有阻断项时 blocking 为真，界面据此拦人；其余情况都不拦")
    void blockingOnlyForBlockingSeverity() {
        PlanReview blocking = PlanReview.of("", "flowchart TD\n    A-->B",
                List.of(new PlanReview.MissingItem("表结构",
                        PlanReview.MissingItem.Severity.BLOCKING, "", "", "")));
        PlanReview quality = PlanReview.of("", "flowchart TD\n    A-->B",
                List.of(new PlanReview.MissingItem("命名",
                        PlanReview.MissingItem.Severity.QUALITY, "", "", "")));
        PlanReview unknown = PlanReview.of("", "flowchart TD\n    A-->B",
                List.of(new PlanReview.MissingItem("说不清",
                        PlanReview.MissingItem.Severity.UNKNOWN, "", "", "")));

        assertThat(blocking.blocking()).isTrue();
        assertThat(quality.blocking()).isFalse();
        assertThat(unknown.blocking()).as("没标严重度的不能当成阻断").isFalse();
    }

    /**
     * 这一条来自真模型的现场：第一版判据（「有默认值就不许阻断」）复跑后**没生效**——
     * 阻断数两次都是 2，另一头还多涨了一条。原因是那条判据太靠"读起来像什么"，
     * 模型学会了句式就往上报。现在换成两问：**我猜得动吗？猜错要返工吗？**
     */
    @Test
    @DisplayName("提示词里必须写着两问判据：猜得动吗、猜错要返工吗")
    void instructionsPinTheTwoQuestions() {
        assertThat(ReviewProtocol.INSTRUCTIONS)
                .contains("我猜得动吗？猜错要返工吗？")
                .contains("会被后面所有代码跟着抄")
                .contains("猜错改几行就能回来的一次性选择")
                .contains("三档都要真的用起来");
    }

    @Test
    @DisplayName("阻断的样例要覆盖两类，而且不能出现「标了阻断、默认值却是照现有写法来」")
    void examplesCoverBothBlockingShapes() {
        List<String> examples = ReviewProtocol.INSTRUCTIONS.lines()
                .filter(line -> line.contains("| 阻断 |"))
                .toList();

        assertThat(examples).as("两类各要有一条样例").hasSizeGreaterThanOrEqualTo(2);
        // 阻断那条样例的默认值栏必须写「猜下去会变成什么」，不能是一句「照现有写法来」——
        // 那正是上一版让模型学歪的地方
        assertThat(examples).allSatisfy(example -> assertThat(example).doesNotContain("照现有代码"));
        assertThat(examples).anySatisfy(example -> assertThat(example).contains("把这次的需求改成"));
        assertThat(examples).anySatisfy(example -> assertThat(example).contains("接口风格"));
    }

    @Test
    @DisplayName("没有缺失项时 complete 为真")
    void completeWhenNothingMissing() {
        assertThat(PlanReview.of("", "flowchart TD\n    A-->B", List.of()).complete()).isTrue();
        assertThat(PlanReview.of("", "flowchart TD\n    A-->B",
                List.of(new PlanReview.MissingItem("x", PlanReview.MissingItem.Severity.OPTIONAL,
                        "", "", ""))).complete()).isFalse();
    }

    private PlanReviewer reviewer(RecordingLlm llm) {
        return new PlanReviewer(new ContextAssembler(new SafePathResolver(root)),
                TemplateRegistry.empty(), llm);
    }

    /** 记录实际发出的消息，用来断言「检查阶段看到的上下文和开发阶段是同一份」。 */
    private static final class RecordingLlm implements LlmClient {

        private final List<ChatMessage> messages = new ArrayList<>();
        private final String response;

        RecordingLlm(String response) {
            this.response = response;
        }

        @Override
        public String complete(List<ChatMessage> sent) {
            messages.addAll(sent);
            return response;
        }
    }
}
