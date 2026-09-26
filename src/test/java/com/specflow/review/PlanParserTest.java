package com.specflow.review;

import com.specflow.exception.SpecflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    // ---------- 施工单 ----------

    @Test
    @DisplayName("施工单按五栏拆开：序号、做什么、文件、怎么算做完、自洽还是中间态")
    void parsesSteps() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW

                <<<<<<< STEPS
                1 | 给 Foo 加一个按编号查询的方法 | src/main/java/demo/Foo.java | Foo 能编译，查不到返回空集合 | 自洽
                2 | 让 BarService 调用它 | src/main/java/demo/BarService.java, src/main/java/demo/Foo.java | BarService 能编译 | 中间态
                3 | 在控制层暴露出去 | src/main/java/demo/FooController.java | 项目整体编译通过 | 自洽
                >>>>>>> STEPS
                """);

        assertThat(review.steps()).hasSize(3);
        PlanStep first = review.steps().get(0);
        assertThat(first.index()).isEqualTo(1);
        assertThat(first.goal()).isEqualTo("给 Foo 加一个按编号查询的方法");
        assertThat(first.files()).containsExactly("src/main/java/demo/Foo.java");
        assertThat(first.check()).contains("查不到返回空集合");
        assertThat(first.intermediate()).isFalse();
        assertThat(review.steps().get(1).files()).as("逗号分隔的多个文件都要认出来")
                .containsExactly("src/main/java/demo/BarService.java", "src/main/java/demo/Foo.java");
        assertThat(review.steps().get(1).intermediate()).isTrue();
        assertThat(review.steps().get(2).intermediate()).isFalse();
    }

    @Test
    @DisplayName("中文逗号、顿号分隔的文件也要拆开——模型不会只用英文逗号")
    void splitsFilesOnAnyListSeparator() {
        assertThat(parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                <<<<<<< STEPS
                1 | 做一件事 | a/Foo.java、b/Bar.java | 能编译 | 自洽
                >>>>>>> STEPS
                """).steps().get(0).files()).containsExactly("a/Foo.java", "b/Bar.java");
    }

    @Test
    @DisplayName("认不出的行不能悄悄消失：整行当成这一步做什么，让机器去报步数越界")
    void keepsUnparsedStepLines() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                <<<<<<< STEPS
                先把实体类写完再说
                1 | 第二步 | a/Foo.java | 能编译 | 自洽
                >>>>>>> STEPS
                """);

        assertThat(review.steps()).as("一行都不能丢").hasSize(2);
        assertThat(review.steps().get(0).goal()).isEqualTo("先把实体类写完再说");
        assertThat(review.steps().get(0).index()).as("没写序号就按行号补一个").isEqualTo(1);
        assertThat(review.steps().get(0).files()).isEmpty();
        assertThat(review.steps().get(1).index()).isEqualTo(1);
    }

    @Test
    @DisplayName("markdown 表格的分隔行是排版不是内容，不能当成一步")
    void skipsTableRuleLines() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                <<<<<<< STEPS
                | 序号 | 做什么 | 文件 | 怎么算做完 | 自洽或中间态 |
                |---|---|---|---|---|
                1 | 第一步 | a/Foo.java | 能编译 | 自洽
                2 | 第二步 | a/Bar.java | 能编译 | 自洽
                3 | 第三步 | a/Baz.java | 能编译 | 自洽
                >>>>>>> STEPS
                """);

        assertThat(review.steps()).as("表头那行是有内容的，保留；分隔行丢掉").hasSize(4);
        assertThat(review.steps()).extracting(PlanStep::goal)
                .containsExactly("做什么", "第一步", "第二步", "第三步");
    }

    @Test
    @DisplayName("「中间态」的判法：只有明说才算，默认是自洽")
    void treatsUnknownCompletenessAsSelfContained() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                <<<<<<< STEPS
                1 | 第一步 | a/Foo.java | 能编译 | 不确定
                2 | 第二步 | a/Bar.java | 能编译 | 中间态
                >>>>>>> STEPS
                """);

        // 判反了的后果不对称：把自洽当成中间态，编译失败会被当成「按约定继续」，
        // 于是一路带着编不过的代码往下走；反过来最坏只是多回滚一次
        assertThat(review.steps().get(0).intermediate()).isFalse();
        assertThat(review.steps().get(1).intermediate()).isTrue();
    }

    @Test
    @DisplayName("没有 STEPS 块时是空列表，不是报错——调用方本来就准备好了退化")
    void emptyStepsWhenBlockMissing() {
        PlanReview review = parser.parse("""
                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW
                """);

        assertThat(review.steps()).isEmpty();
    }

    @Test
    @DisplayName("只产施工单的轻协议：没有 FLOW 块也能只取施工单")
    void parsesStepsWithoutFlowchart() {
        List<PlanStep> steps = parser.parseSteps("""
                好的，我拆一下。

                <<<<<<< STEPS
                1 | 第一步 | a/Foo.java | 能编译 | 自洽
                2 | 第二步 | a/Bar.java | 能编译 | 自洽
                3 | 第三步 | a/Baz.java | 能编译 | 自洽
                >>>>>>> STEPS
                """);

        assertThat(steps).hasSize(3);
        assertThat(steps.get(2).goal()).isEqualTo("第三步");
    }

    @Test
    @DisplayName("轻协议下什么都没有时返回空，不抛异常")
    void parseStepsNeverThrows() {
        assertThat(parser.parseSteps("我拆不开。")).isEmpty();
        assertThat(parser.parseSteps("")).isEmpty();
        assertThat(parser.parseSteps(null)).isEmpty();
    }
}
