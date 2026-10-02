package com.specflow.tests;

import com.specflow.review.PlanReview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「回喂给开发」那一段（十五.7 的固定模板 + 这一批真做出来的那条路）。
 *
 * <p>在它之前，这条路上只有留档里一个标签（「开发 AI 错了（已回喂）」），而那个「已回喂」是假的：
 * 引擎里根本没有把失败交给开发的路，界面只能自己拼一段文本塞进请求里。
 *
 * <p>给什么、不给什么是这一段最要紧的事：<b>给</b>用例的语义、失败类型、期望 vs 实际、
 * 涉及的目标文件；<b>不给</b>测试代码与断言源码——给了断言，模型最省事的做法就是照着断言改代码，
 * 而那份断言本身可能才是错的。
 */
@DisplayName("回喂给开发的那一段")
class RefeedTest {

    private static final List<String> TARGETS = List.of("src/main/java/demo/OrderLedger.java");

    private static PlanReview.TestCase testCase(int index, String what, String how, String expected) {
        return new PlanReview.TestCase(index, what, how, PlanReview.TestCase.Level.MUST, expected, "无");
    }

    private static TestOutcome tests(TestOutcome.Failure... failures) {
        return new TestOutcome("tools/20261001-200000",
                List.of("tools/20261001-200000/run.cmd"), 1, 1,
                com.specflow.verify.VerificationResult.failed("测试脚本", "run.cmd", "退出码 1"),
                List.of(failures),
                List.of(new TestOutcome.CaseResult(7, false), new TestOutcome.CaseResult(8, false)),
                List.of());
    }

    private static Refeed refeed(List<Integer> picked) {
        List<PlanReview.TestCase> declared = List.of(
                testCase(7, "连续写两条记账后第二次返回 2", "写两条再读文件", "文件两行"),
                testCase(8, "LEDGER_FILE 没设时抛 IllegalStateException", "清掉变量再调", "抛异常"));
        TestOutcome outcome = tests(
                new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "7",
                        "two appends succeed", "InaccessibleObjectException", "代码错了"),
                new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "8",
                        "IllegalStateException", "InaccessibleObjectException", ""));
        return Refeed.of(declared, outcome, TARGETS, picked);
    }

    /** 四要素齐：语义描述、失败类型、期望 vs 实际、涉及的目标文件（十五.7）。 */
    @Test
    @DisplayName("四要素齐：用例的语义 + 失败类型 + 期望 vs 实际 + 涉及的目标文件")
    void carriesTheFourThings() {
        Refeed refeed = refeed(List.of(7, 8));

        assertThat(refeed.present()).isTrue();
        assertThat(refeed.cases()).containsExactly(7, 8);
        assertThat(refeed.text())
                .startsWith(Refeed.HEADING)
                .contains("用例 7「连续写两条记账后第二次返回 2」")
                .contains("失败类型：断言没过")
                // 期望取自**冻结清单**那一栏（唯一真源），不是脚本自己打印的那句
                .contains("期望 文件两行")
                .contains("实际 InaccessibleObjectException")
                .contains("它怎么验的：写两条再读文件")
                .contains(TARGETS.get(0));
    }

    /**
     * 「编号 ↔ 语义」对照：回喂是按编号说的，而编号只在那一轮冻结的清单里有意义。
     *
     * <p>实测里张冠李戴的那一次，喂出去的就是一条「编号对不上语义」的话：
     * 开发 Agent 收到的是一段自相矛盾的回喂（既没失败、也没期望/实际）。
     */
    @Test
    @DisplayName("回喂段里带「编号 ↔ 语义」对照，编号与语义出自同一份冻结清单")
    void carriesTheNumberToMeaningTable() {
        Refeed refeed = refeed(List.of(7, 8));

        assertThat(refeed.text())
                .contains("编号 ↔ 语义对照")
                .contains("7 = 连续写两条记账后第二次返回 2")
                .contains("8 = LEDGER_FILE 没设时抛 IllegalStateException");
    }

    /**
     * 逐项校验：对不上就在<b>开工前</b>拦下（见 {@code RunStore.refeed}）。
     *
     * <p>三种对不上各报各的，不合并成一句「回喂失败」——人要知道该去改哪一条。
     */
    @Test
    @DisplayName("逐项校验：编号不在冻结清单里 / 那一轮没失败 / 期望与实际取不到")
    void reportsWhatCannotBeFed() {
        List<PlanReview.TestCase> declared = List.of(
                testCase(7, "记两笔", "写两条", "文件两行"),
                testCase(8, "没设变量时抛异常", "清掉变量", "抛异常"));
        TestOutcome outcome = new TestOutcome("tools/x", List.of(), 1, 1,
                com.specflow.verify.VerificationResult.failed("测试脚本", "run.cmd", "退出码 1"),
                List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "8",
                        "IllegalStateException", "只有一行", "代码错了")),
                List.of(new TestOutcome.CaseResult(7, true), new TestOutcome.CaseResult(8, false)),
                List.of());

        assertThat(Refeed.problems(declared, outcome, List.of(7, 8, 99)))
                .as("7 那一轮过了（不在失败清单里）、99 压根不在清单里")
                .hasSize(2)
                .anySatisfy(problem -> assertThat(problem)
                        .contains("第 7 条").contains("失败清单里没有"))
                .anySatisfy(problem -> assertThat(problem)
                        .contains("第 99 条").contains("不在那一轮冻结的用例清单里"));
        assertThat(Refeed.problems(declared, outcome, List.of(8)))
                .as("这一条对得上：编号在清单里、也真的失败了").isEmpty();
        assertThat(Refeed.problems(declared, outcome, List.of()))
                .as("一条都没勾：不是「下一轮」，没什么可核的").isEmpty();
    }

    /** 失败行没写实际（或者清单没写期望、脚本也没写）时也拦下：喂过去的两栏都是空的。 */
    @Test
    @DisplayName("逐项校验：期望与实际都取不到时当场拦")
    void reportsWhenNothingCanBeQuoted() {
        List<PlanReview.TestCase> declared = List.of(testCase(7, "记两笔", "写两条", ""));
        TestOutcome outcome = new TestOutcome("tools/x", List.of(), 1, 1,
                com.specflow.verify.VerificationResult.failed("测试脚本", "run.cmd", "退出码 1"),
                List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "7", "", "", "")),
                List.of(new TestOutcome.CaseResult(7, false)), List.of());

        assertThat(Refeed.problems(declared, outcome, List.of(7)))
                .hasSize(2)
                .anySatisfy(problem -> assertThat(problem).contains("取不到期望"))
                .anySatisfy(problem -> assertThat(problem).contains("取不到实际"));
    }

    /**
     * 不给测试代码、不给断言源码——这是防过拟合的那一道。
     *
     * <p>实测过一次相反的写法（界面把失败清单拼成一段内联上下文）：模型最省事的做法就是
     * 照着断言改代码，而那份断言本身可能才是错的。
     */
    @Test
    @DisplayName("不给测试代码、不给断言源码（给了它就会照着断言改代码）")
    void neverLeaksTheTestCode() {
        Refeed refeed = Refeed.of(
                List.of(testCase(7, "连续写两条记账", "写两条再读文件", "文件两行")),
                new TestOutcome("tools/x", List.of(), 1, 1,
                        com.specflow.verify.VerificationResult.failed("测试脚本", "run.cmd", "退出码 1"),
                        List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "7",
                                "文件两行", "只有一行", "代码错了")),
                        List.of(new TestOutcome.CaseResult(7, false)), List.of()),
                TARGETS, List.of(7));

        assertThat(refeed.text())
                .doesNotContain("assert")
                .doesNotContain("echo")
                .doesNotContain("run.cmd")
                .contains("测试代码与断言源码不给你");
    }

    @Test
    @DisplayName("一条都没勾：空的回喂（提示词里不加那一段）")
    void isEmptyWithoutPickedCases() {
        assertThat(refeed(List.of()).present()).isFalse();
        assertThat(refeed(null).present()).isFalse();
        assertThat(Refeed.none().present()).isFalse();
        assertThat(Refeed.none().text()).isEmpty();
    }

    /** 编号去重、排序：界面发什么顺序、有没有重复，拼出来的那一段必须是稳定的。 */
    @Test
    @DisplayName("编号去重并排序：同一份勾选两次拼出来的话一模一样")
    void deduplicatesAndSortsTheIndexes() {
        assertThat(refeed(List.of(8, 7, 8)).cases()).containsExactly(7, 8);
        assertThat(refeed(List.of(8, 7, 8)).text()).isEqualTo(refeed(List.of(7, 8)).text());
    }

    /** 清单里没有那条用例、或者它没在这份失败清单里：如实说，不编一句。 */
    @Test
    @DisplayName("清单里找不到 / 失败清单里找不到：如实说，不编")
    void saysWhenSomethingCannotBeFound() {
        Refeed refeed = refeed(List.of(99));

        assertThat(refeed.text()).contains("用例 99「（清单里没有这一条）」")
                .contains("失败类型：没在这份失败清单里");
    }

    /** 清单里那条没写期望时，退回失败清单里脚本自己那句话——总比留空强。 */
    @Test
    @DisplayName("清单里没写期望：退回失败清单里那句，而不是留空")
    void fallsBackToTheScriptsOwnWords() {
        Refeed refeed = Refeed.of(List.of(testCase(7, "记两笔", "写两条", "")),
                tests(new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "7",
                        "two appends succeed", "只有一行", "")),
                TARGETS, List.of(7));

        assertThat(refeed.text()).contains("期望 two appends succeed");
    }

    /**
     * 「它没有改动」的判据：只有这一轮<b>真回喂了</b>、而且每一处改动的 diff 都是空白，才说得出口。
     *
     * <p>实测里那一轮回喂，它把文件原样再交了一遍（写入内容与旧版逐字相同、diff 为空），
     * 而留档只写着「已写入 1 个文件」——和真改过长得一模一样。
     */
    @Test
    @DisplayName("「它没有改动」只在真回喂、且每一处 diff 都为空时才成立")
    void detectsUnchangedRounds() {
        Refeed refeed = refeed(List.of(7));
        assertThat(Refeed.unchanged(refeed, List.of()))
                .as("一处改动都没有：当然没改").isTrue();
        assertThat(Refeed.unchanged(refeed, List.of(change(""))))
                .as("交了一份内容逐字相同的文件（diff 为空）").isTrue();
        assertThat(Refeed.unchanged(refeed, List.of(change(""), change(null))))
                .as("diff 是 null 也算没差异").isTrue();
        assertThat(Refeed.unchanged(refeed, List.of(change("-int a = 1;\n+int a = 2;"))))
                .as("有一处真差异就不算没改").isFalse();
        assertThat(Refeed.unchanged(Refeed.none(), List.of(change(""))))
                .as("不是回喂的那一轮不给这个结论（普通运行没改文件是另一件事）").isFalse();
        assertThat(Refeed.unchanged(null, List.of(change("")))).isFalse();
    }

    private static com.specflow.patch.PatchApplier.FileChange change(String diff) {
        return new com.specflow.patch.PatchApplier.FileChange(
                java.nio.file.Path.of("Foo.java"), "Foo.java", false, 10, diff);
    }
}
