package com.specflow.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第一段那份用例清单的<b>两个形状</b>：五栏（现在）与六栏（老写法）。
 *
 * <p>五栏是第一段现在的形状——「怎么测」不再在这一步产出（那时一行代码都没有，
 * 写出来的只能是照它脑子里的实现猜的），留给第二段（见 {@code CaseHowStage}）。
 *
 * <p>六栏必须继续认：<b>用户自己的模板</b>（{@code .specflow/templates} 下那份）里
 * 可能还写着老协议，「怎么测」会被模型老实填在第 3 栏。两种形状必须<b>按栏数分道</b>——
 * 只按五栏认的话，从第 3 栏起整体错位一格：分级会读到「怎么测」那句话、期望会读到「必须过」，
 * 每一栏都是隔壁的内容。这种错位读出来照样是「一份像模像样的清单」，比读不出来糟得多。
 */
@DisplayName("用例清单：五栏与六栏两种形状")
class PlanParserCaseColumnsTest {

    private final PlanParser parser = new PlanParser();

    @Test
    @DisplayName("五栏（现在的形状）：怎么测留空，分级/期望/验收按下标 2/3/4")
    void readsTheFiveColumnShape() {
        List<PlanReview.TestCase> cases = parser.parseCases("""
                <<<<<<< CASES
                1 | 按编号查订单能查到 | 必须过 | 返回的那条 id 等于传入的编号 | A1：订单能按编号查询
                2 | 查不到的编号不抛异常 | 建议过 | 返回空集合而不是抛异常 | 无
                >>>>>>> CASES
                """);

        assertThat(cases).hasSize(2);
        assertThat(cases.get(0)).satisfies(one -> {
            assertThat(one.index()).isEqualTo(1);
            assertThat(one.what()).isEqualTo("按编号查订单能查到");
            assertThat(one.how()).as("第一段不产这一栏：它由第二段看过 diff 之后补").isEmpty();
            assertThat(one.level()).isEqualTo(PlanReview.TestCase.Level.MUST);
            assertThat(one.expected()).isEqualTo("返回的那条 id 等于传入的编号");
            assertThat(one.acceptance()).isEqualTo("A1：订单能按编号查询");
        });
        assertThat(cases.get(1).level()).isEqualTo(PlanReview.TestCase.Level.SHOULD);
    }

    @Test
    @DisplayName("六栏（老模板还在用）：怎么测按第 3 栏读，其余各栏不整体错位一格")
    void readsTheLegacySixColumnShape() {
        List<PlanReview.TestCase> cases = parser.parseCases("""
                <<<<<<< CASES
                1 | 按编号查订单能查到 | 用已存在的编号查一次 | 必须过 | 返回的那条 id 等于传入的编号 | A1：订单能按编号查询
                >>>>>>> CASES
                """);

        assertThat(cases).singleElement().satisfies(one -> {
            assertThat(one.how()).as("老形状里这一栏是模型自己写的").isEqualTo("用已存在的编号查一次");
            assertThat(one.level()).as("错位一格的话这里会读到「用已存在的编号查一次」")
                    .isEqualTo(PlanReview.TestCase.Level.MUST);
            assertThat(one.expected()).as("错位一格的话这里会读到「必须过」")
                    .isEqualTo("返回的那条 id 等于传入的编号");
            assertThat(one.acceptance()).isEqualTo("A1：订单能按编号查询");
        });
    }

    /**
     * 同一句话、两种栏数：结果必须不一样——这正是「按栏数分道」的证据。
     *
     * <p>拿同一段内容只改栏数来比，是因为单看五栏那条（上一类里）说明不了「它认识五栏」，
     * 也说明不了「它没有把五栏当六栏读」。
     */
    @Test
    @DisplayName("同一段内容按五栏与六栏读出来是不同的东西：没有整体错位")
    void theTwoShapesAreNotInterchangeable() {
        List<PlanReview.TestCase> five = parser.parseCases("""
                <<<<<<< CASES
                1 | a 能变成 2 | 必须过 | a == 2 | A1
                >>>>>>> CASES
                """);
        List<PlanReview.TestCase> six = parser.parseCases("""
                <<<<<<< CASES
                1 | a 能变成 2 | 读 Foo.java 里的 a | 必须过 | a == 2 | A1
                >>>>>>> CASES
                """);

        assertThat(five).singleElement()
                .satisfies(one -> assertThat(one.expected()).isEqualTo("a == 2"));
        assertThat(six).singleElement()
                .satisfies(one -> assertThat(one.expected()).isEqualTo("a == 2"));
        assertThat(five.get(0).how()).as("五栏里第 3 栏是分级，不是「怎么测」").isEmpty();
        assertThat(six.get(0).how()).isEqualTo("读 Foo.java 里的 a");
        assertThat(five.get(0).level()).isEqualTo(PlanReview.TestCase.Level.MUST);
        assertThat(six.get(0).level()).isEqualTo(PlanReview.TestCase.Level.MUST);
    }
}
