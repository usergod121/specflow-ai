package com.specflow.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 停用的用例在<b>覆盖核对</b>里怎么算，以及「无对应验收标准的用例不许是必须过」那条硬规则。
 *
 * <p>两件事放在一起，是因为它们说的是同一条口径：<b>用例的分量与它的来历必须对得上</b>。
 * 停用的用例不再算覆盖（它退出了分母），但也不能被说成「零覆盖」——「没人管」和
 * 「管事的那条被你停用了」对应的下一步完全不同（一条要去找人写用例，一条只要恢复就行）。
 */
@DisplayName("覆盖核对：停用的用例与分级硬规则")
class AcceptanceCoverageDisabledTest {

    /**
     * 验收标准本来有活着的用例管、只是那条被停用了：进 {@code disabledOnly}，<b>不进</b> {@code uncovered}。
     *
     * <p>这两栏要是合一，用户看到的是一句「一条用例都没覆盖」——他会以为清单里压根没写过这条验收标准，
     * 然后去补一条本来已经存在的用例。
     */
    @Test
    @DisplayName("被停用的用例盖住：进 disabledOnly、不进 uncovered，summarize 说得出")
    void reportsCriterionCoveredOnlyByADisabledCase() {
        List<String> criteria = List.of("A1：金额分档", "A2：会员叠加");
        List<PlanReview.TestCase> cases = List.of(
                testCase(1, "A1：金额分档", PlanReview.TestCase.Level.MUST),
                testCase(2, "A2：会员叠加", PlanReview.TestCase.Level.MUST));

        AcceptanceCoverage.Report stopped = AcceptanceCoverage.check(criteria, cases, Set.of(2));

        assertThat(stopped.uncovered()).as("这条标准有人管过，说成「零覆盖」会让人去找一条本来就有的用例")
                .isEmpty();
        assertThat(stopped.disabledOnly()).containsExactly("A2：会员叠加");
        assertThat(stopped.disabledOnlyCount()).isEqualTo(1);
        assertThat(stopped.ok()).as("此刻确实没人验它了——停用是人的选择，但结果是同一个").isFalse();
        assertThat(stopped.summarize()).contains("被你停用了").contains("A2：会员叠加");

        // 对照组：没停用时这条核对是通过的（不然上面那几条断言可能只是「它总是报错」）
        AcceptanceCoverage.Report alive = AcceptanceCoverage.check(criteria, cases);
        assertThat(alive.ok()).isTrue();
        assertThat(alive.disabledOnly()).isEmpty();

        // 两参数重载 = 三参数传空集：两条路的结论必须一模一样，否则界面与引擎各说一套
        AcceptanceCoverage.Report empty = AcceptanceCoverage.check(criteria, cases, Set.of());
        assertThat(empty.uncovered()).isEqualTo(alive.uncovered());
        assertThat(empty.disabledOnly()).isEqualTo(alive.disabledOnly());
        assertThat(empty.unmappedMust()).isEqualTo(alive.unmappedMust());
    }

    /** 停用的用例<b>压根没管</b>那条验收标准时，它仍然是「零覆盖」——停用只解释「有人管过」那一种情况。 */
    @Test
    @DisplayName("停用的用例本来就没覆盖它：仍然算零覆盖，不许拿停用当挡箭牌")
    void stillReportsUncoveredWhenTheDisabledCaseNeverCoveredIt() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(
                List.of("A3：非法入参抛异常"),
                List.of(testCase(4, "无", PlanReview.TestCase.Level.SHOULD)),
                Set.of(4));

        assertThat(report.uncovered()).containsExactly("A3：非法入参抛异常");
        assertThat(report.disabledOnly()).isEmpty();
    }

    /** {@code unmappedMust} 也不再看停用的用例：它已经不算数了，再去问它「怎么没挂验收」是白问。 */
    @Test
    @DisplayName("必须过的用例被停用：不再报「没写对应哪条验收标准」")
    void skipsDisabledCasesInUnmappedMust() {
        List<String> criteria = List.of("A1：金额分档");
        List<PlanReview.TestCase> cases = List.of(
                testCase(1, "A1：金额分档", PlanReview.TestCase.Level.MUST),
                testCase(2, "无", PlanReview.TestCase.Level.MUST));

        assertThat(AcceptanceCoverage.check(criteria, cases).unmappedMust())
                .as("没停用时就该点它").containsExactly(2);

        AcceptanceCoverage.Report stopped = AcceptanceCoverage.check(criteria, cases, Set.of(2));
        assertThat(stopped.unmappedMust()).as("停用的用例不参与分母，就不该再被追着要验收标准").isEmpty();
        assertThat(stopped.ok()).isTrue();
    }

    /**
     * <b>硬规则</b>：标了「必须过」却没对应任何验收标准的用例 → 降成 {@code SHOULD}，编号进 {@code downgraded}。
     *
     * <p>为什么是硬规则：「必须过」的含义是「不过就等于这次需求没做到」，而这个含义
     * <b>只有验收标准给得起</b>——它是从需求那一边来的。一条谁也不对应的用例标着「必须过」，
     * 是把模型自己的判断冒充成需求的分量：真红灯时人分不清「功能没做出来」和「它自己觉得重要」。
     */
    @Test
    @DisplayName("无验收的 MUST 降成 SHOULD 并进 downgraded；挂了验收的不动，只换分级一栏")
    void downgradesMustCasesWithoutAnAcceptanceCriterion() {
        List<PlanReview.TestCase> cases = List.of(
                testCase(1, "A1：金额分档", PlanReview.TestCase.Level.MUST),
                testCase(2, "无", PlanReview.TestCase.Level.MUST),
                testCase(3, "无", PlanReview.TestCase.Level.SHOULD));

        AcceptanceCoverage.Levelled levelled = AcceptanceCoverage.level(List.of("A1：金额分档"), cases);

        assertThat(levelled.downgraded()).containsExactly(2);
        assertThat(levelled.cases()).extracting(PlanReview.TestCase::level)
                .containsExactly(PlanReview.TestCase.Level.MUST, PlanReview.TestCase.Level.SHOULD,
                        PlanReview.TestCase.Level.SHOULD);
        PlanReview.TestCase before = cases.get(1);
        PlanReview.TestCase after = levelled.cases().get(1);
        assertThat(after.what()).isEqualTo(before.what());
        assertThat(after.how()).as("降级只改分级一栏，别的一个字节都不许动").isEqualTo(before.how());
        assertThat(after.expected()).isEqualTo(before.expected());
        assertThat(after.acceptance()).isEqualTo(before.acceptance());
        assertThat(after.index()).isEqualTo(before.index());
    }

    /**
     * 需求里一条验收标准都没写时<b>不降级</b>：那时候「对应哪条验收标准」这一栏没有可填的对象，
     * 一律降级等于惩罚「没写验收标准」这件事（老用法就是这么用的）。
     *
     * <p>这一条是刻意写下来的取舍，免得下一个人以为是漏判。
     */
    @Test
    @DisplayName("需求里没写验收标准：一条都不降级，原样返回")
    void doesNotDowngradeWhenThereIsNoAcceptanceAtAll() {
        List<PlanReview.TestCase> cases = List.of(
                testCase(1, "无", PlanReview.TestCase.Level.MUST),
                testCase(2, "无", PlanReview.TestCase.Level.MUST));

        AcceptanceCoverage.Levelled levelled = AcceptanceCoverage.level(List.of(), cases);
        assertThat(levelled.downgraded()).isEmpty();
        assertThat(levelled.cases()).extracting(PlanReview.TestCase::level)
                .containsOnly(PlanReview.TestCase.Level.MUST);

        AcceptanceCoverage.Levelled nulled = AcceptanceCoverage.level(null, cases);
        assertThat(nulled.downgraded()).as("null 与空表一个意思").isEmpty();
        assertThat(nulled.cases()).extracting(PlanReview.TestCase::level)
                .containsOnly(PlanReview.TestCase.Level.MUST);
    }

    /**
     * 那句话里必须说清「机器把它降成了建议过」。
     *
     * <p>{@code unmappedMust} 是在<b>降级之前</b>的清单上算出来的，而人看到的清单上那条
     * 已经是「建议过」了（见 {@code RunService.review}）——不说清，两句话看起来就是自相矛盾：
     * 报告说「这条标了必须过却没挂验收」，清单上那条却明明是「建议过」。
     */
    @Test
    @DisplayName("unmappedMust 那句话里说清「机器把它们降成了建议过」")
    void saysThatTheMachineDowngradedIt() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(
                List.of("A1：金额分档"),
                List.of(testCase(1, "A1：金额分档", PlanReview.TestCase.Level.MUST),
                        testCase(2, "无", PlanReview.TestCase.Level.MUST)));

        assertThat(report.unmappedMust()).containsExactly(2);
        assertThat(report.summarize())
                .contains("1 条用例标了「必须过」却没挂验收标准")
                .contains("机器把它们降成了「建议过」")
                .contains("用例 2");
    }

    private static PlanReview.TestCase testCase(int index, String acceptance,
                                                PlanReview.TestCase.Level level) {
        return new PlanReview.TestCase(index, "用例 " + index, "怎么验", level, "期望 " + index,
                acceptance);
    }
}
