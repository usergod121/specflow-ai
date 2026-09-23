package com.specflow.review;

import com.specflow.exception.SpecflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("检查结果解析")
class PlanParserTest {

    private final PlanParser parser = new PlanParser();

    @Test
    @DisplayName("三个块都能解析出来")
    void parsesAllBlocks() {
        PlanReview review = parser.parse("""
                <<<<<<< SUMMARY
                新增一个查询订单的接口，复用已有的 Mapper 写法。
                >>>>>>> SUMMARY

                <<<<<<< FLOW
                flowchart TD
                    A[GET /orders] --> B{参数是否合法}
                    B -->|否| C[返回 400]
                    B -->|是| D[OrderService 查询]
                    D --> E[返回结果]
                >>>>>>> FLOW
                """);

        assertThat(review.summary()).contains("复用已有的 Mapper 写法");
        assertThat(review.flowchart()).startsWith("flowchart TD").contains("B{参数是否合法}");
        assertThat(review.missing()).isEmpty();
        assertThat(review.complete()).isTrue();
    }

    @Test
    @DisplayName("缺失清单按竖线拆成五个字段，严重度认出来")
    void parsesMissingItems() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW

                <<<<<<< MISSING
                订单表结构 | 阻断 | 没有字段名就写不出 SQL | 查询可能直接报错 | 先按 id/order_no/status 三列写
                OrderDTO 的命名习惯 | 影响质量 | 能编译但可能和项目风格不一致 | 字段名和你要的叫法不同 | 按现有代码的驼峰命名写
                >>>>>>> MISSING
                """);

        assertThat(review.missing()).hasSize(2);
        PlanReview.MissingItem first = review.missing().get(0);
        assertThat(first.what()).isEqualTo("订单表结构");
        assertThat(first.severity()).isEqualTo(PlanReview.MissingItem.Severity.BLOCKING);
        assertThat(first.impact()).contains("写不出 SQL");
        assertThat(first.business()).contains("报错");
        assertThat(first.fallback()).contains("三列");
        assertThat(review.missing().get(1).severity())
                .isEqualTo(PlanReview.MissingItem.Severity.QUALITY);
        assertThat(review.complete()).isFalse();
        assertThat(review.blocking()).isTrue();
    }

    @Test
    @DisplayName("严重度认不出来就标「未标」，绝不替它当成阻断——那会白白拦住人")
    void unknownSeverityNeverBlocks() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                <<<<<<< MISSING
                订单表结构 | 挺重要的 | 写不出 SQL | 查询会错 | 随便先写
                >>>>>>> MISSING
                """);

        assertThat(review.missing()).singleElement()
                .satisfies(item -> assertThat(item.severity())
                        .isEqualTo(PlanReview.MissingItem.Severity.UNKNOWN));
        assertThat(review.blocking()).isFalse();
    }

    @Test
    @DisplayName("越严重的排越前，同档保持模型给的顺序")
    void sortsBySeverity() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                <<<<<<< MISSING
                日志格式 | 可选 | 无所谓 | 看不出差别 | 按现有格式
                命名习惯 | 影响质量 | 能编译 | 名字不同 | 驼峰
                表结构 | 阻断 | 写不出 SQL | 查不到数据 | 先假定三列
                错误码 | 可选 | 无所谓 | 文案不同 | 用 400
                >>>>>>> MISSING
                """);

        assertThat(review.missing()).extracting(PlanReview.MissingItem::what)
                .containsExactly("表结构", "命名习惯", "日志格式", "错误码");
    }

    @Test
    @DisplayName("缺失清单写「无」视为什么都不缺")
    void treatsNothingMissingAsComplete() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW

                <<<<<<< MISSING
                无
                >>>>>>> MISSING
                """);

        assertThat(review.missing()).isEmpty();
        assertThat(review.complete()).isTrue();
        assertThat(review.blocking()).isFalse();
    }

    @Test
    @DisplayName("缺失项只写了名称时也能用——它只是给人看的提示")
    void toleratesPartialMissingItem() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                <<<<<<< MISSING
                - 订单表结构
                >>>>>>> MISSING
                """);

        assertThat(review.missing()).singleElement().satisfies(item -> {
            assertThat(item.what()).isEqualTo("订单表结构");
            assertThat(item.severity()).isEqualTo(PlanReview.MissingItem.Severity.UNKNOWN);
            assertThat(item.impact()).isEmpty();
            assertThat(item.fallback()).isEmpty();
            // 界面上那行不能是空白：得说清「它没给默认值」
            assertThat(item.fallbackOrDefault()).contains("按常规做法");
        });
    }

    @Test
    @DisplayName("模型还按老的三段格式写时，后两段按老位置摆，不会整体错位一格")
    void toleratesThreeFieldLegacyFormat() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                <<<<<<< MISSING
                订单表结构 | 需要知道 orders 有哪些字段才能写 SQL | 把建表语句粘贴进上下文
                >>>>>>> MISSING
                """);

        assertThat(review.missing()).singleElement().satisfies(item -> {
            assertThat(item.what()).isEqualTo("订单表结构");
            // 「需要知道…」不是严重度词，所以这一行按老格式解释：未标 + 技术影响 + 建议默认值
            assertThat(item.severity()).isEqualTo(PlanReview.MissingItem.Severity.UNKNOWN);
            assertThat(item.impact()).contains("写 SQL");
            assertThat(item.fallback()).as("老格式的第三段是「怎么补」，该落在建议默认值上")
                    .contains("粘贴");
        });
    }

    @Test
    @DisplayName("新格式只写了三段时按新位置摆——第二栏是严重度，不能当成老格式")
    void keepsNewFormatWhenOnlyThreeFields() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                <<<<<<< MISSING
                订单表结构 | 阻断 | 没有字段名就写不出 SQL
                >>>>>>> MISSING
                """);

        assertThat(review.missing()).singleElement().satisfies(item -> {
            assertThat(item.severity()).isEqualTo(PlanReview.MissingItem.Severity.BLOCKING);
            assertThat(item.impact()).isEqualTo("没有字段名就写不出 SQL");
            assertThat(item.fallback()).isEmpty();
        });
    }

    @Test
    @DisplayName("块前后的解释性文字不影响解析")
    void ignoresSurroundingProse() {
        PlanReview review = parser.parse("""
                好的，我先分析一下这个需求。

                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW

                以上就是我的计划，请确认。
                """);

        assertThat(review.flowchart()).isEqualTo("flowchart TD\n    A[入口] --> B[出口]");
    }

    @Test
    @DisplayName("流程图被 markdown 围栏包住时自动剥掉")
    void stripsFlowchartFence() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                ```mermaid
                flowchart TD
                    A[入口] --> B[出口]
                ```
                >>>>>>> FLOW
                """);

        assertThat(review.flowchart()).startsWith("flowchart TD").doesNotContain("```");
    }

    @Test
    @DisplayName("结束标记少了名字也能认出来")
    void toleratesBareEndMarker() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>>
                """);

        assertThat(review.flowchart()).contains("A[入口]");
    }

    @Test
    @DisplayName("没有 FLOW 块时直接报错——那是唯一不可缺的东西")
    void failsWhenFlowchartMissing() {
        assertThatThrownBy(() -> parser.parse("""
                <<<<<<< SUMMARY
                我打算加一个接口。
                >>>>>>> SUMMARY
                """))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("FLOW");
    }

    @Test
    @DisplayName("响应为空时报错")
    void failsOnEmptyResponse() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("为空");
    }
}
