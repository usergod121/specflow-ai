package com.specflow.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlanReview#live(Set)}：把被人停用的用例去掉，其余原样。
 *
 * <p>它是「停用的用例不参与任何分母」在引擎这一侧的<b>唯一落点</b>：测试阶段、溯源核对、
 * 通过率拿到的都是这一份，所以停用之后引擎不再要求那条用例被实现。
 * 留档与界面仍然拿完整那份清单（停用的用例要看得见、还能恢复，藏起来就成了删除）。
 *
 * <p>为什么连「空集合返回同一个对象」也要断言：这个方法的调用点都在热路径上，
 * 而「没有停用时就返回自己」是一个<b>有意的取舍</b>（少一次复制，也让调用方可以靠
 * {@code isSameAs} 判断有没有被过滤过）。写丢了它不会报错，只会安静地多复制一份。
 */
@DisplayName("活着的用例：live 过滤")
class PlanReviewLiveTest {

    @Test
    @DisplayName("去掉停用的那一条：其余字段原样，原对象不动")
    void removesTheDisabledCaseAndKeepsEverythingElse() {
        PlanReview plan = plan();
        // 过滤之后仍然拿得到「谁被停用了」的原话：停用的用例还在完整那份清单里
        PlanReview live = plan.live(Set.of(2));

        assertThat(live).isNotSameAs(plan);
        assertThat(live.cases()).extracting(PlanReview.TestCase::index).containsExactly(1, 3);
        assertThat(live.summary()).isEqualTo(plan.summary());
        assertThat(live.flowchart()).isEqualTo(plan.flowchart());
        assertThat(live.missing()).isEqualTo(plan.missing());
        assertThat(live.steps()).isEqualTo(plan.steps());
        assertThat(plan.cases()).as("界面与留档拿的是完整那份：停用要看得见、还能恢复")
                .hasSize(3);
    }

    @Test
    @DisplayName("没停用 / 停用的编号不在清单里：返回同一个对象")
    void returnsTheSameInstanceWhenNothingIsRemoved() {
        PlanReview plan = plan();

        assertThat(plan.live(Set.of())).isSameAs(plan);
        assertThat(plan.live(null)).as("null = 一条都没停用").isSameAs(plan);
        assertThat(plan.live(Set.of(99))).as("编号对不上任何用例：清单一个字节都没变").isSameAs(plan);
    }

    /** 停用两条、留一条：过滤是按集合做的，不是「一条一条减」。 */
    @Test
    @DisplayName("停用两条：只剩活着的那一条")
    void keepsOnlyTheLiveCases() {
        PlanReview live = plan().live(Set.of(1, 3));

        assertThat(live.cases()).singleElement()
                .satisfies(one -> assertThat(one.index()).isEqualTo(2));
    }

    private static PlanReview plan() {
        return PlanReview.of("做点事", "flowchart TD\n    A-->B",
                List.of(new PlanReview.MissingItem("订单表结构",
                        PlanReview.MissingItem.Severity.BLOCKING, "要写 SQL", "查询会错", "粘贴建表语句")),
                List.of(new PlanStep(1, "先加接口", List.of("Foo.java"), "能编译", false)),
                List.of(testCase(1), testCase(2), testCase(3)));
    }

    private static PlanReview.TestCase testCase(int index) {
        return new PlanReview.TestCase(index, "用例 " + index, "怎么验",
                PlanReview.TestCase.Level.MUST, "期望 " + index, "无");
    }
}
