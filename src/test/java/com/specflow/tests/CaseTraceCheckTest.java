package com.specflow.tests;

import com.specflow.review.PlanReview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 溯源连线核对（四条机器判据）。
 *
 * <p>这一层是纯文本扫描，所以测试直接把「产物长什么样」写成字符串喂进去——
 * 不编译、不执行、不要真项目。它是这条链上唯一「拦在跑之前」的东西，
 * 判错了的代价不对称：放过一条，跑出来的结论就是假的；错杀一条，人多花一次重新生成。
 */
@DisplayName("溯源连线核对")
class CaseTraceCheckTest {

    @Test
    @DisplayName("四种语言注释符下的锚点都认：内容格式统一，引导符随语言")
    void readsTheAnchorUnderEveryCommentLead() {
        // 四个文件、四条用例，各写各的：这正是「一条用例一处」的样子
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("tools/x/OrderTest.java", "// CASE 1\n// expect: 期望 1\n");
        contents.put("tools/x/order_test.py", "# CASE 2\n# expect: 期望 2\n");
        contents.put("tools/x/order_test.sql", "-- CASE 3\n-- expect: 期望 3\n");
        contents.put("tools/x/run.cmd", "REM CASE 4\r\nREM expect: 期望 4\r\n");

        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(4), contents);

        assertThat(report.findings()).as(report.summarize()).isEmpty();
        assertThat(report.ok()).isTrue();
        assertThat(report.links()).hasSize(4);
        assertThat(report.links()).extracting(CaseTraceCheck.Link::file)
                .containsExactly("tools/x/OrderTest.java", "tools/x/order_test.py",
                        "tools/x/order_test.sql", "tools/x/run.cmd");
        assertThat(report.links()).allSatisfy(link ->
                assertThat(link.line()).as("锚点在第 1 行").isEqualTo(1));
    }

    @Test
    @DisplayName("① 清单里有、代码里没扫到：报漏实现，并说清是第几条")
    void missesACaseThatIsNotInTheCode() {
        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(2),
                Map.of("tools/x/UnitTest.java", "// CASE 1\n// expect: 期望 1\n"));

        assertThat(report.ok()).isFalse();
        assertThat(report.links()).hasSize(1);
        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(CaseTraceCheck.Kind.MISSING);
            assertThat(finding.index()).isEqualTo(2);
            assertThat(finding.detail()).contains("没有扫到 CASE 2");
        });
        assertThat(report.summarize()).contains("漏实现 1 条").contains("用例 2");
    }

    /** 锚点写了一半也算没接上：只有 CASE、没有 expect，清单里那句期望就没法核对。 */
    @Test
    @DisplayName("只写了 CASE 没写 expect：算漏实现（锚点不完整）")
    void treatsAHalfAnchorAsMissing() {
        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(1),
                Map.of("tools/x/UnitTest.java", "// CASE 1\nint result = policy.rate(9999);\n"));

        assertThat(report.ok()).isFalse();
        assertThat(report.links()).as("没接上的不进「已连线」").isEmpty();
        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(CaseTraceCheck.Kind.MISSING);
            assertThat(finding.detail()).contains("没有跟着一行 expect");
        });
    }

    @Test
    @DisplayName("② 代码里有、清单里没有：报清单外乱写")
    void reportsAnAnchorOutsideTheList() {
        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(1),
                Map.of("tools/x/UnitTest.java",
                        "// CASE 1\n// expect: 期望 1\n// CASE 9\n// expect: 它自己想验的\n"));

        assertThat(report.ok()).isFalse();
        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(CaseTraceCheck.Kind.EXTRA);
            assertThat(finding.index()).isEqualTo(9);
            assertThat(finding.detail()).contains("清单里没有编号 9");
        });
        assertThat(report.summarize()).contains("清单外乱写 1 条");
    }

    @Test
    @DisplayName("③ 同一个文件里同一个编号出现两次：报重复实现，两个位置都摆出来")
    void reportsADuplicatedAnchor() {
        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(1),
                Map.of("tools/x/UnitTest.java",
                        "// CASE 1\n// expect: 期望 1\n\n// CASE 1\n// expect: 期望 1\n"));

        assertThat(report.ok()).isFalse();
        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(CaseTraceCheck.Kind.DUPLICATE);
            assertThat(finding.index()).isEqualTo(1);
            assertThat(finding.detail()).contains("同一个文件 tools/x/UnitTest.java 里出现了 2 次")
                    .contains("tools/x/UnitTest.java:1").contains("tools/x/UnitTest.java:4");
        });
    }

    /**
     * 重复<b>按文件判</b>：同一条用例在单元与集成两个入口里各写一遍不算重复。
     *
     * <p>旧实现按「整批产物里出现两次」判，于是单元与集成各写一遍<自己>这条正常的写法被当成重复，
     * 而它的后果是<b>整批测试一次都不跑</b>（十五.4 的两个入口本来就是两条路）。
     * 真正要挡的是「同一个文件里把同一条用例写了两遍」——那才会出现一处过一处不过的局面。
     */
    @Test
    @DisplayName("③ 同一个编号在两个文件里各写一遍：不算重复（单元与集成本来就是两条路）")
    void allowsTheSameAnchorInTwoFiles() {
        // 用 LinkedHashMap 而不是 Map.of：产物是按写入顺序扫的（TestAgent 给的就是有序表），
        // 而「连到哪一份」的答案依赖那个顺序——用无序表这条断言会时绿时红
        Map<String, String> contents = new java.util.LinkedHashMap<>();
        contents.put("tools/x/UnitTest.java", "// CASE 1\n// expect: 期望 1\n");
        contents.put("tools/x/IntegrationTest.java", "// CASE 1\n// expect: 期望 1\n");

        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(1), contents);

        assertThat(report.ok()).as("跨文件不算重复，这批照跑：%s", report.summarize()).isTrue();
        assertThat(report.findings()).isEmpty();
        assertThat(report.links()).singleElement().satisfies(link -> {
            assertThat(link.index()).isEqualTo(1);
            assertThat(link.file()).as("连线按第一个出现的位置记").isEqualTo("tools/x/UnitTest.java");
        });
    }

    /** 同一个文件里写三遍：报一条，位置把三处都列出来。 */
    @Test
    @DisplayName("③ 同一个文件里写三遍：一条差异里三处位置都给出来")
    void reportsEveryDuplicateInOneFile() {
        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(1),
                Map.of("tools/x/UnitTest.java",
                        "// CASE 1\n// expect: 期望 1\n// CASE 1\n// expect: 期望 1\n"
                                + "// CASE 1\n// expect: 期望 1\n"));

        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(CaseTraceCheck.Kind.DUPLICATE);
            assertThat(finding.detail()).contains("出现了 3 次")
                    .contains("tools/x/UnitTest.java:1").contains("tools/x/UnitTest.java:3")
                    .contains("tools/x/UnitTest.java:5");
        });
    }

    @Test
    @DisplayName("④ expect 与清单不一致：报偷偷改期望，两边的话都摆出来")
    void reportsAChangedExpectation() {
        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(1),
                Map.of("tools/x/UnitTest.java", "// CASE 1\n// expect: 0.01\n"));

        assertThat(report.ok()).isFalse();
        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(CaseTraceCheck.Kind.CHANGED);
            assertThat(finding.index()).isEqualTo(1);
            assertThat(finding.detail())
                    .contains("清单里写的是「期望 1」").contains("代码里写的是「0.01」");
        });
    }

    /**
     * 归一化只碰空白：排版差异不是「改了期望」，而一个标点的差别是。
     * 这条判据的整个意义就是「逐字照抄」，所以它不能宽到把真改动放过去。
     */
    @Test
    @DisplayName("空白差异不算改期望；改一个标点就算")
    void comparesVerbatimAfterCollapsingWhitespace() {
        String declaredText = "返回 0.00，且不抛异常";
        assertThat(CaseTraceCheck.check(List.of(testCase(1, declaredText)),
                Map.of("tools/x/UnitTest.java", "// CASE 1\n// expect:   返回\u30000.00，且不抛异常 \n"))
                .ok()).as("全角空格与多余空白都算同一个期望").isTrue();
        assertThat(CaseTraceCheck.check(List.of(testCase(1, declaredText)),
                Map.of("tools/x/UnitTest.java", "// CASE 1\n// expect: 返回 0.0，且不抛异常\n"))
                .findings()).singleElement()
                .satisfies(finding -> assertThat(finding.kind())
                        .isEqualTo(CaseTraceCheck.Kind.CHANGED));
    }

    /** 清单里那一栏空着时不做比较：没有「照抄」的对象，判它改了期望是替清单背锅。 */
    @Test
    @DisplayName("清单里没写期望：不判「改期望」（没有可照抄的原文）")
    void skipsTheComparisonWhenTheListHasNoExpectation() {
        CaseTraceCheck.Report report = CaseTraceCheck.check(List.of(testCase(1, "")),
                Map.of("tools/x/UnitTest.java", "// CASE 1\n// expect: 0.01\n"));

        assertThat(report.ok()).isTrue();
        assertThat(report.links()).hasSize(1);
    }

    /**
     * 锚点必须<b>整行只有这一句</b>：范围描述（{@code CASE 1..8}）和顺带一句话的写法
     * 都不算——否则「这一行到底实现了几条」是说不清的，而它正是这四条判据要回答的问题。
     */
    @Test
    @DisplayName("整行只有这一句才算锚点：CASE 1..8 这种范围描述不算")
    void requiresTheAnchorToOwnItsLine() {
        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(1),
                Map.of("tools/x/run.cmd",
                        "REM 下面这 1 条用例分别由 CASE 1..8 覆盖\r\nREM CASE 1：金额分档\r\n"));

        assertThat(report.ok()).isFalse();
        assertThat(report.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.kind())
                        .isEqualTo(CaseTraceCheck.Kind.MISSING));
    }

    /** 四条判据可以同时出现，报告要把它们分开说（界面上是四行不同的差异）。 */
    @Test
    @DisplayName("四条判据同时出现：各报各的，一行一句结论")
    void reportsEveryKindAtOnce() {
        Map<String, String> contents = Map.of("tools/x/UnitTest.java",
                "// CASE 1\n// expect: 被改过的期望\n// CASE 2\n// CASE 2\n// expect: 期望 2\n"
                        + "// CASE 7\n// expect: 清单外的\n");

        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(3), contents);

        assertThat(report.findings()).extracting(CaseTraceCheck.Finding::kind)
                .containsExactlyInAnyOrder(CaseTraceCheck.Kind.CHANGED, CaseTraceCheck.Kind.DUPLICATE,
                        CaseTraceCheck.Kind.MISSING, CaseTraceCheck.Kind.EXTRA);
        assertThat(report.summarize())
                .contains("漏实现").contains("清单外乱写").contains("重复实现").contains("偷偷改期望");
    }

    /** 产物一个文件都没有（模型什么都没写）：清单上每一条都是漏实现，不能算「通过」。 */
    @Test
    @DisplayName("一个产物文件都没有：每条都是漏实现")
    void reportsEverythingWhenThereIsNoArtifact() {
        CaseTraceCheck.Report report = CaseTraceCheck.check(declared(2), Map.of());

        assertThat(report.ok()).isFalse();
        assertThat(report.findings()).hasSize(2)
                .allSatisfy(finding -> assertThat(finding.kind())
                        .isEqualTo(CaseTraceCheck.Kind.MISSING));
    }

    // ---------- 辅助 ----------

    private static List<PlanReview.TestCase> declared(int count) {
        List<PlanReview.TestCase> cases = new java.util.ArrayList<>();
        for (int index = 1; index <= count; index++) {
            cases.add(testCase(index, "期望 " + index));
        }
        return cases;
    }

    private static PlanReview.TestCase testCase(int index, String expected) {
        return new PlanReview.TestCase(index, "用例 " + index, "怎么验",
                PlanReview.TestCase.Level.MUST, expected, "验收 " + index);
    }
}
