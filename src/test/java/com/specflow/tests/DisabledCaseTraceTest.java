package com.specflow.tests;

import com.specflow.review.PlanReview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>需求原话</b>：停用的用例<b>不再要求被实现</b>。
 *
 * <p>这一段是「停用退出所有分母」在溯源核对上的落点：引擎把 {@code approved.live(disabled)}
 * 交给开发阶段（见 {@code RunService.execute}），所以 {@link CaseTraceCheck} 拿到的清单里
 * 压根没有那条用例——它不再报「漏实现」，也不会在失败清单里被算成「没验」。
 *
 * <p>为什么这一条要直接跑通（而不是只断言 {@code PlanReview.live} 过滤对了）：
 * 「过滤出来了、但核对那一层用的还是完整那份」这种断线，两层的测试各自都是绿的，
 * 而它的表现正是要堵住的那件事——界面上说这条不算了，引擎照样要求它被实现。
 */
@DisplayName("停用的用例：不再要求被实现")
class DisabledCaseTraceTest {

    @Test
    @DisplayName("live 过滤之后：第 2 条没实现也不算漏，对照组照样报漏实现")
    void doesNotRequireDisabledCasesToBeImplemented() {
        PlanReview plan = PlanReview.of("做点事", "", List.of(), List.of(), List.of(
                testCase(1, "a == 2"), testCase(2, "b == 3")));
        // 产物里只实现了第 1 条：第 2 条被用户停用了，所以它不在「要验的那几条」里
        Map<String, String> contents = Map.of("tools/x/UnitTest.java",
                "// CASE 1\n// expect: a == 2\n");

        CaseTraceCheck.Report live = CaseTraceCheck.check(plan.live(Set.of(2)).cases(), contents);

        assertThat(live.findings()).as(live.summarize()).isEmpty();
        assertThat(live.ok()).as("停用的用例不再要求被实现——这一条是需求原话").isTrue();
        assertThat(live.links()).extracting(CaseTraceCheck.Link::index).containsExactly(1);

        // 对照组：不过滤（也就是停用这件事没接上引擎）时，第 2 条会被报成漏实现
        CaseTraceCheck.Report full = CaseTraceCheck.check(plan.cases(), contents);
        assertThat(full.ok()).isFalse();
        assertThat(full.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(CaseTraceCheck.Kind.MISSING);
            assertThat(finding.index()).isEqualTo(2);
        });
        assertThat(full.summarize()).contains("漏实现").contains("用例 2");
    }

    /**
     * 停用的那条用例的期望也不必再对：它压根不该出现在「要验的清单」里。
     *
     * <p>顺带钉住一个边界：过滤之后，产物里那一处<b>旧锚点</b>反倒会被报成「清单外乱写」。
     * 这不是这一段的问题——核对跑在<b>这一次新生成</b>的产物上，而新产物是照活着的清单写的
     * （见下一条里的假模型：它只给活着的那几条写锚点）。真正要防的是
     * 「拿一条已经不算数的用例去拦这一次运行」，所以过滤后不该再出现 {@code CHANGED}。
     */
    @Test
    @DisplayName("停用那条的期望对不上：不再拿它去拦这一次运行")
    void ignoresTheExpectationOfADisabledCase() {
        PlanReview plan = PlanReview.of("做点事", "", List.of(), List.of(), List.of(
                testCase(1, "a == 2"), testCase(2, "b == 3")));
        Map<String, String> contents = Map.of("tools/x/UnitTest.java",
                "// CASE 1\n// expect: a == 2\n// CASE 2\n// expect: 别的写法\n");

        assertThat(CaseTraceCheck.check(plan.cases(), contents).findings())
                .as("没过滤时它是一条「偷偷改期望」").extracting(CaseTraceCheck.Finding::kind)
                .containsExactly(CaseTraceCheck.Kind.CHANGED);
        assertThat(CaseTraceCheck.check(plan.live(Set.of(2)).cases(), contents).findings())
                .as("过滤之后不再有「偷偷改期望」那一条（只剩「清单外乱写」这个旧锚点）")
                .extracting(CaseTraceCheck.Finding::kind)
                .containsExactly(CaseTraceCheck.Kind.EXTRA);
    }

    private static PlanReview.TestCase testCase(int index, String expected) {
        return new PlanReview.TestCase(index, "用例 " + index, "怎么验",
                PlanReview.TestCase.Level.MUST, expected, "无");
    }
}
