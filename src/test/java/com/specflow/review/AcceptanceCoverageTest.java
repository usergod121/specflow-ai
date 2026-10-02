package com.specflow.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「每条验收标准到底有没有用例覆盖」的机器核对——§14.8 提过、§15 漏掉的那一半。
 *
 * <p>起因是真模型实测里当场漏掉的一条：10 条验收标准里 A4 一条用例都没覆盖、
 * A9 只是被并进另一条用例而追溯字段没填，而引擎<b>什么都没报</b>——
 * 「验收标准写完了就没人管」这件事一直静悄悄地发生。
 *
 * <p>两个数都该是 0：零覆盖的验收标准、无对应验收标准的必须过用例。
 * 它<b>不拦人</b>（用例是给人看的证据），但人得看得见。
 */
@DisplayName("验收覆盖核对")
class AcceptanceCoverageTest {

    private static PlanReview.TestCase testCase(int index, String acceptance) {
        return testCase(index, acceptance, PlanReview.TestCase.Level.MUST);
    }

    private static PlanReview.TestCase testCase(int index, String acceptance,
                                                PlanReview.TestCase.Level level) {
        return new PlanReview.TestCase(index, "用例 " + index, "怎么验", level, "期望 " + index,
                acceptance);
    }

    /**
     * 现场重现：A4 一条用例都没提到它。
     *
     * <p>用例那一栏按协议是「照抄那一条」，所以照抄全文、或者只写编号（{@code A4}）都算接上了。
     */
    @Test
    @DisplayName("现场重现：某一条验收标准一条用例都没覆盖 → 必须报出来")
    void catchesAnAcceptanceNothingCovers() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(
                List.of("A1：金额 9999 分返回 0.00", "A4：50000 分返回 0.28", "A9：每行是 JSON"),
                List.of(testCase(1, "A1：金额 9999 分返回 0.00"),
                        testCase(2, "A9：每行是 JSON")));

        assertThat(report.ok()).isFalse();
        assertThat(report.uncoveredCount()).isEqualTo(1);
        assertThat(report.uncovered()).containsExactly("A4：50000 分返回 0.28");
        assertThat(report.summarize()).contains("1 条验收标准一条用例都没覆盖").contains("A4");
    }

    @Test
    @DisplayName("每条验收标准都有用例提到：两个数都是 0，一句话说得清")
    void passesWhenEverythingIsCovered() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(
                List.of("A1：金额 9999 分返回 0.00", "A4：50000 分返回 0.28"),
                List.of(testCase(1, "A1：金额 9999 分返回 0.00"),
                        testCase(2, "A4：50000 分返回 0.28")));

        assertThat(report.ok()).isTrue();
        assertThat(report.uncoveredCount()).isZero();
        assertThat(report.unmappedMustCount()).isZero();
        assertThat(report.summarize()).contains("覆盖核对通过").contains("2 条验收标准");
    }

    /**
     * <b>正常路径不许误报</b>：模型那一栏的写法本来就是多样的——
     * 照抄全文、只写编号、把两条并在一格里、中间多几个全角空格。
     *
     * <p>为什么专门钉这一条：这条核对的灵敏度是被实测量过的（照抄旧验收时它当场报了 5/5），
     * 而它的代价其实是<b>假红</b>——把接上的说成没接上，人就会去改一份本来就对的东西，
     * 改两次之后没人再看这两个数。所以真实写法那几种都得算「接上了」。
     */
    @Test
    @DisplayName("正常路径不误报：照抄全文 / 只写编号 / 一格里写两条 / 全角空格都算接上")
    void doesNotCryWolfOnTheNormalPath() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(
                List.of("A1：金额四档分别返回 0.00、0.05、0.10、0.28",
                        "A2：会员加成叠加后封顶 0.30",
                        "A3：返回保留两位小数",
                        "A4：非法入参抛 IllegalArgumentException"),
                List.of(
                        // ① 照抄全文
                        testCase(1, "A1：金额四档分别返回 0.00、0.05、0.10、0.28"),
                        // ② 只写编号（验收标准常常写成「A2：……」，而它只会写 A2）
                        testCase(2, "A2"),
                        // ③ 一格里写两条
                        testCase(3, "A2：会员加成叠加后封顶 0.30；A3：返回保留两位小数"),
                        // ④ 全角空格只算排版差异
                        testCase(4, "A4：非法入参抛\u3000IllegalArgumentException")));

        assertThat(report.uncovered()).as("一条都不该报").isEmpty();
        assertThat(report.unmappedMust()).isEmpty();
        assertThat(report.ok()).isTrue();
        assertThat(report.summarize()).contains("覆盖核对通过");
    }

    /**
     * <b>实测那一次：照抄的是上一版验收文本 → 当场全报零覆盖。</b>
     *
     * <p>现场是这样的：我在两次「检查」之间把 A1~A5 的措辞改了（同义，但字变了），
     * 而模型那一栏照抄的是<b>上一版</b>的原文——它报了 {@code uncovered: 5/5}。
     * 这个数是准的：屏幕上的验收标准确实一条都没被提到，旧文本对不回任何一条新标准。
     * 这一条把它钉住——「验收文本变了要报 uncovered」是这套核对存在的理由之一。
     */
    @Test
    @DisplayName("照抄上一版验收文本：当场全报零覆盖（实测里那一次 uncovered 5/5）")
    void reportsCopiedOldAcceptanceTextAsUncovered() {
        // 新一版验收标准（措辞改过，还加了 A6/A7）
        List<String> criteria = List.of(
                "A1：金额分档按分位取值，依次为 0.00、0.05、0.10、0.28",
                "A2：会员等级在其中叠加，GOLD 加 0.10 后封顶 0.30",
                "A3：返回值的两位小数用四舍五入",
                "A4：非法入参一律抛 IllegalArgumentException",
                "A5：标签前缀超过 8 个字符时截短并加省略号");
        // 用例那一栏照抄的是上一版的原文：同义，但一个字都不一样
        List<PlanReview.TestCase> cases = List.of(
                testCase(1, "金额四档：0.00/0.05/0.10/0.28"),
                testCase(2, "会员加成叠加，封顶 0.30"),
                testCase(3, "返回保留两位小数"),
                testCase(4, "非法入参抛异常"),
                testCase(5, "标签前缀截短"));

        AcceptanceCoverage.Report report = AcceptanceCoverage.check(criteria, cases);

        assertThat(report.uncoveredCount()).as("5 条新验收标准一条都没被提到").isEqualTo(5);
        assertThat(report.uncovered()).containsExactlyElementsOf(criteria);
        assertThat(report.unmappedMust())
                .as("同一个错位在两个数上都露出来：这 5 条必须过用例的指向也对不回新标准")
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(report.summarize()).contains("5 条验收标准一条用例都没覆盖");
    }

    /**
     * 宽松的那一侧（<b>有意的</b>）：旧文本正好是新文本的一段时，仍算接上。
     *
     * <p>为什么允许：判据是「双向包含」，「那一栏只写编号 / 只写前半句」正是它要认的写法
     * （见 {@link AcceptanceCoverage} 的类注释）。收紧成「必须逐字相等」会让
     * 「A3」这种写法全变成假红——而假红的代价是这两个数没人再看。
     * 这一条只是把边界写下来，不是缺陷：要连「半句也算没接上」都报，就得换一套判据。
     */
    @Test
    @DisplayName("形如「两位小数」这种旧文本是新文本的一段：仍算接上（宽松是有意的）")
    void treatsAStaleFragmentAsCoveredOnPurpose() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(
                List.of("A3：返回值的两位小数用四舍五入"),
                List.of(testCase(1, "两位小数")));

        assertThat(report.uncovered()).isEmpty();
        assertThat(report.ok()).isTrue();
    }

    /**
     * 只写编号也算接上：验收标准常常写成「A1：……」，而模型那一栏只会写 {@code A1}。
     * 只认全文照抄的话，这条判据会被假红淹掉——而假红的代价是没人再看它。
     */
    @Test
    @DisplayName("那一栏只写编号也算接上（A1 ⇄ A1：……）；空白差异不算没接")
    void matchesByNumberOrVerbatimText() {
        assertThat(AcceptanceCoverage.check(List.of("A1：金额分档"),
                List.of(testCase(1, "A1"))).ok()).isTrue();
        assertThat(AcceptanceCoverage.check(List.of("A1：金额 分档"),
                List.of(testCase(1, "A1：金额\u3000分档"))).ok())
                .as("全角空格只算排版差异").isTrue();
        assertThat(AcceptanceCoverage.check(List.of("A1：金额分档"),
                List.of(testCase(1, "A9"))).ok())
                .as("对不上的编号不能算接上").isFalse();
    }

    /** 那一栏空着、写「无」、写「/」都等于没写：没有指向就是没有指向，不替它猜。 */
    @Test
    @DisplayName("那一栏空着 / 写「无」/ 写「/」：都不算接上")
    void treatsBlankOrNoneAsNoMapping() {
        for (String none : List.of("", "   ", "无", "None", "/", "—")) {
            AcceptanceCoverage.Report report = AcceptanceCoverage.check(List.of("A1：金额分档"),
                    List.of(testCase(1, none)));
            assertThat(report.ok()).as("「%s」不算接上", none).isFalse();
            assertThat(report.unmappedMust()).as("必须过的用例没写对应验收，要被点出来")
                    .containsExactly(1);
        }
    }

    /**
     * 只点「必须过」那一档：建议过／可选本来就可以是补充性的（十五.3 的分级口径），
     * 拿它们也去要求「必须挂上一条验收标准」，等于把优先级当成义务。
     */
    @Test
    @DisplayName("只点必须过那一档：建议过 / 可选的用例没写对应验收不算问题")
    void onlyCountsMustLevelCases() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(
                List.of("A1：金额分档"),
                List.of(testCase(1, "A1：金额分档"),
                        testCase(2, "无", PlanReview.TestCase.Level.SHOULD),
                        testCase(3, "无", PlanReview.TestCase.Level.OPTIONAL),
                        testCase(4, "无", PlanReview.TestCase.Level.UNKNOWN)));

        assertThat(report.unmappedMust()).isEmpty();
        assertThat(report.ok()).isTrue();
    }

    @Test
    @DisplayName("没写验收标准：没什么可核的，两个数都是 0（界面据此不画那两枚计数）")
    void nothingToCheckWithoutAcceptance() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(List.of(),
                List.of(testCase(1, "无")));

        assertThat(report.criteria()).isEmpty();
        assertThat(report.ok()).isTrue();
        assertThat(report.summarize()).contains("覆盖核对通过");
        assertThat(AcceptanceCoverage.check(null, null).ok()).isTrue();
    }

    /** 一条用例可以覆盖多条验收标准，也可以一条都不覆盖——两个方向都别判错。 */
    @Test
    @DisplayName("一条用例提到两条验收标准：两条都算覆盖上了")
    void oneCaseCanCoverSeveralCriteria() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(
                List.of("A1：金额分档", "A2：会员叠加"),
                List.of(testCase(1, "A1：金额分档；A2：会员叠加")));

        assertThat(report.ok()).isTrue();
        assertThat(report.uncoveredCount()).isZero();
    }

    /** 差异可能几十条：只列前几条原文，但条数如实报出来（不静默截断）。 */
    @Test
    @DisplayName("零覆盖的那几条只列前三条，但总数如实报出来")
    void capsTheListedCriteria() {
        AcceptanceCoverage.Report report = AcceptanceCoverage.check(
                List.of("A1：一", "A2：二", "A3：三", "A4：四", "A5：五"), List.of());

        assertThat(report.uncoveredCount()).isEqualTo(5);
        assertThat(report.summarize()).contains("5 条验收标准").contains("A1：一").contains("…");
    }
}
