package com.specflow.tests;

import com.specflow.review.PlanReview;
import com.specflow.verify.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分档的判据与逐条用例的账。
 *
 * <p>这是本批里唯一「机器自己下判断」的地方，所以每条判据都要有个反向的例子钉在旁边：
 * 判成环境问题会<b>回滚</b>（把编译通过的改动一起撤掉），判成测试代码问题只是标一下。
 * 判错的代价不对称，规则就得写窄。
 */
@DisplayName("测试结果分档")
class TestReportTest {

    @Test
    @DisplayName("退出码 0 就是通过：没有失败清单")
    void passesOnZeroExitCode() {
        TestOutcome outcome = TestReport.conclude(
                new TestScriptVerifier.ScriptResult(
                        VerificationResult.passed("测试脚本", "run.cmd", "PASS | 1\n全部通过\n"), 0),
                "tools/20260930-120000", List.of("tools/20260930-120000/run.cmd"), 1);

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.detail()).contains("没有报出失败");
    }

    @Test
    @DisplayName("有 FAIL 行就是断言失败：哪条、期望、实际、它认为，四要素一条不少")
    void readsAssertionFailures() {
        TestOutcome outcome = conclude(1, """
                跑 3 条用例…
                FAIL | 2 | 查不到时返回空集合 | 返回了 null | 代码错了：没做空值处理
                1 条没过
                """);

        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.ASSERTION);
            assertThat(failure.testCase()).isEqualTo("2");
            assertThat(failure.expected()).isEqualTo("查不到时返回空集合");
            assertThat(failure.actual()).isEqualTo("返回了 null");
            assertThat(failure.opinion()).contains("代码错了");
        });
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.ASSERTION);
        assertThat(outcome.environmental()).as("断言没过不改代码也能解决，不许当环境问题回滚").isFalse();
    }

    @Test
    @DisplayName("多条 FAIL 行各算一条，顺序保持原样")
    void readsEveryFailureLine() {
        TestOutcome outcome = conclude(1, """
                FAIL | 1 | 返回 id=1 | 返回了 id=2 | 代码错了
                FAIL | 3 | 状态码 400 | 状态码 500 | 代码错了
                """);

        assertThat(outcome.failures()).extracting(TestOutcome.Failure::testCase)
                .containsExactly("1", "3");
    }

    /**
     * 这一条是「与产品代码错分开标记」的落点：非 0 退出、却一行 FAIL 都没有，
     * 说明它压根没跑到断言——多半是测试代码自己编译不过。
     */
    @Test
    @DisplayName("非 0 退出但一行 FAIL 都没有 → 测试代码问题，不是产品代码错")
    void treatsSilentFailureAsTestCodeProblem() {
        TestOutcome outcome = conclude(1, """
                Check.java:12: error: cannot find symbol
                  symbol: method findByCode(java.lang.String)
                1 error
                """);

        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
            assertThat(failure.actual()).contains("cannot find symbol");
        });
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
        assertThat(outcome.detail()).contains("测试代码本身").contains("不是产品代码");
    }

    /**
     * 「测试代码引用了不存在的 API」不能收进环境问题：那会触发回滚。
     * 编译器的原话长得就像缺东西，所以这条要单独钉住。
     */
    @Test
    @DisplayName("测试代码里的「程序包不存在 / 找不到符号」不算环境问题")
    void compileErrorsAreNotEnvironment() {
        assertThat(TestReport.environmentEvidence("Check.java:3: error: 程序包 com.demo 不存在")).isNull();
        assertThat(TestReport.environmentEvidence("error: cannot find symbol")).isNull();
        TestOutcome outcome = conclude(1, "Check.java:3: error: 程序包 com.demo 不存在\n");
        assertThat(outcome.environmental()).isFalse();
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
    }

    @Test
    @DisplayName("脚本打印 BLOCKED 就是环境问题：它自己说连跑都没跑起来")
    void readsBlockedLineAsEnvironment() {
        TestOutcome outcome = conclude(2,
                "BLOCKED | 没有找到 pytest，装上再跑：pip install pytest\n");

        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.ENVIRONMENT);
            assertThat(failure.actual()).contains("没有找到 pytest");
        });
        assertThat(outcome.environmental()).isTrue();
        assertThat(outcome.detail()).contains("环境问题");
    }

    @Test
    @DisplayName("输出里有「命令不存在 / 连不上」也算环境问题（脚本忘了打印 BLOCKED 时的兜底）")
    void readsEnvironmentEvidenceFromOutput() {
        assertThat(TestReport.environmentEvidence("'javac' 不是内部或外部命令，也不是可运行的程序"))
                .contains("不是内部或外部命令");
        assertThat(TestReport.environmentEvidence("sh: 1: python3: command not found"))
                .contains("command not found");
        assertThat(TestReport.environmentEvidence("java.net.ConnectException: Connection refused"))
                .contains("Connection refused");

        TestOutcome outcome = conclude(1, "'mvn' 不是内部或外部命令，也不是可运行的程序\n");
        assertThat(outcome.environmental()).isTrue();
    }

    /**
     * 兜底判据要<b>保守</b>：这些字样出现在用例自己的日志里是很正常的事
     * （验「连不上时该报什么」就会把它们原样打出来），而判成环境问题会回滚。
     */
    @Test
    @DisplayName("用例自己打的「环境字样」不算环境问题：没有报错的样子就不算")
    void doesNotTreatTestNarrationAsEnvironment() {
        assertThat(TestReport.environmentEvidence("case 5: expect ECONNREFUSED handling")).isNull();
        assertThat(TestReport.environmentEvidence("assert that Connection refused is mapped to 503"))
                .isNull();
        assertThat(TestReport.environmentEvidence("command not found")).isNull();
        assertThat(TestReport.environmentEvidence("if (err.code === 'ECONNREFUSED') return null"))
                .isNull();
    }

    /**
     * <b>顺序就是本条批次的头号修复。</b>旧代码先看环境字样、后看 FAIL 行，
     * 于是「有一条 FAIL 行、而它正好提到连接被拒」会被判成环境问题：
     * 编译通过的改动被整次回滚，用户还被告知「改测试代码解决不了」——方向从一开始就错了。
     */
    @Test
    @DisplayName("有 FAIL 行就是断言失败：哪怕那一行里写着 Connection refused")
    void failLineWinsOverEnvironmentEvidence() {
        TestOutcome outcome = conclude(1, """
                FAIL | 1 | 期望 | 实际 | 代码错了
                Connection refused: 连不上 127.0.0.1:3306
                """);

        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.ASSERTION);
        assertThat(outcome.environmental()).as("有断言失败就不许判环境问题（判错会回滚）").isFalse();
        assertThat(outcome.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.kind())
                        .isEqualTo(TestOutcome.Failure.Kind.ASSERTION));

        TestOutcome insideFailLine = conclude(1,
                "FAIL | 5 | 连不上要返回 503 | Connection refused leaked out | 代码错了\n");
        assertThat(insideFailLine.worst()).isEqualTo(TestOutcome.Failure.Kind.ASSERTION);
        assertThat(insideFailLine.environmental()).isFalse();
    }

    /**
     * 退出码不是全部事实：模型经常打一堆 FAIL 行、最后 {@code exit /b 0}。
     * 旧代码见退出码 0 就直接返回空失败清单，于是那一次在留档里是「测试通过」，
     * 而失败清单就在旁边——自相矛盾，而且是往好的那边错。
     */
    @Test
    @DisplayName("退出码 0 但打了 FAIL 行：不算通过，失败清单照样读出来")
    void zeroExitWithFailLinesIsNotAPass() {
        // 这里刻意造一份「执行器说通过」的结果：真实的执行器碰到这两行时也不会再说通过
        // （见 TestScriptVerifierTest.doesNotPassOnZeroExitWithFailLines），
        // 但判据本身要在这里钉住——顺序反了（先看环境字样、或只看退出码）就会漏掉它
        TestOutcome outcome = TestReport.conclude(
                new TestScriptVerifier.ScriptResult(
                        VerificationResult.passed("测试脚本", "run.cmd",
                                "ALL TESTS PASSED\nFAIL | 1 | 期望 A | 得到 B | 代码错了\n"), 0),
                "tools/20260930-120000", List.of(), 1);

        assertThat(outcome.passed()).as("退出码 0 掩盖不了它自己打的 FAIL 行").isFalse();
        assertThat(outcome.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.kind())
                        .isEqualTo(TestOutcome.Failure.Kind.ASSERTION));
    }

    @Test
    @DisplayName("退出码 0 但打了 BLOCKED 行：算环境问题，不是通过")
    void zeroExitWithBlockedLineIsEnvironment() {
        TestOutcome outcome = TestReport.conclude(
                new TestScriptVerifier.ScriptResult(
                        VerificationResult.passed("测试脚本", "run.cmd", "BLOCKED | javac not found\n"), 0),
                "tools/20260930-120000", List.of(), 1);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.environmental()).isTrue();
    }

    /**
     * 超时是单独一档：它既不是「环境问题」（环境弄好了也还是慢），
     * 也不是「断言失败」（它压根没跑到结论）。并进环境问题会让一次本来就慢的测试
     * 把编译通过的改动一起回滚掉，而报告里写的原因是「缺命令、连不上」——对不上的话。
     */
    @Test
    @DisplayName("超时单独一档：不是环境问题，原话与「超时」都写进结论")
    void timeoutIsItsOwnKind() {
        TestOutcome outcome = TestReport.conclude(
                new TestScriptVerifier.ScriptResult(VerificationResult.failed("测试脚本", "run.cmd",
                        "测试脚本超过 300 秒未结束，已强制终止（要么它真的慢，要么它卡住了）。",
                        VerificationResult.Kind.TIMEOUT), TestScriptVerifier.NO_EXIT_CODE),
                "tools/x", List.of(), 1);

        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.TIMEOUT);
        assertThat(outcome.environmental()).as("超时不触发回滚那一档").isFalse();
        assertThat(outcome.detail()).contains("测试超时").contains("300 秒");
        assertThat(outcome.exit()).isEqualTo(TestScriptVerifier.NO_EXIT_CODE);
    }

    @Test
    @DisplayName("脚本压根起不来（引擎看见的）也是环境问题，原话要带着")
    void keepsEngineSideEnvironmentFailure() {
        TestOutcome outcome = TestReport.conclude(
                new TestScriptVerifier.ScriptResult(VerificationResult.failed("测试脚本",
                        "tools/x/run.cmd", "入口脚本起不来：Cannot run program \"cmd.exe\"",
                        VerificationResult.Kind.ENVIRONMENT), TestScriptVerifier.NO_EXIT_CODE),
                "tools/x", List.of(), 1);

        assertThat(outcome.environmental()).isTrue();
        assertThat(outcome.exit()).isEqualTo(TestScriptVerifier.NO_EXIT_CODE);
        assertThat(outcome.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.actual()).contains("起不来"));
    }

    /**
     * 分母不能丢：失败清单只说得出「哪几条没过」，说不出「一共验了几条」。
     * 少了这一步，脚本一条用例都没跑、只打一句「all passed」就退出 0 的那种运行
     * 会显示成满分——而那是<u>最坏</u>的一档虚高，分母大的时候它看着还挺可信。
     */
    @Test
    @DisplayName("声明 3 条、脚本只报 1 条：不算通过，没跑的两条也进账")
    void coverageCountsTheCasesThatNeverRan() {
        TestOutcome outcome = TestReport.coverage(conclude(0, "all passed\n"), declared(3), List.of());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.cases()).extracting(TestOutcome.CaseResult::index)
                .containsExactly(1, 2, 3);
        assertThat(outcome.cases()).allSatisfy(caseResult -> assertThat(caseResult.passed()).isFalse());
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
        assertThat(outcome.environmental()).as("漏跑用例不是环境问题，不许连累回滚").isFalse();
        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
            assertThat(failure.actual()).contains("只报了 0 条").contains("第 1、2、3 条");
        });
        assertThat(outcome.detail()).contains("没验不等于验过了");
    }

    @Test
    @DisplayName("脚本逐条报了结论：声明数与实跑数对上就是通过，逐条下场也留着")
    void coverageKeepsPerCaseOutcome() {
        TestOutcome outcome = TestReport.coverage(
                conclude(0, "PASS | 1\nPASS | 2\nPASS | 3\n"), declared(3), List.of());

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.cases()).hasSize(3)
                .allSatisfy(caseResult -> assertThat(caseResult.passed()).isTrue());
    }

    @Test
    @DisplayName("报了一半：过了的算过，没报的算没过，账要分开记")
    void coverageMarksOnlyWhatTheScriptReported() {
        TestOutcome outcome = TestReport.coverage(
                conclude(1, "PASS | 1\nFAIL | 2 | 期望 | 实际 | 代码错了\n"), declared(3), List.of());

        assertThat(outcome.cases()).extracting(TestOutcome.CaseResult::passed)
                .containsExactly(true, false, false);
        assertThat(outcome.failures()).hasSize(2);
    }

    @Test
    @DisplayName("同一条既报 PASS 又报 FAIL：按没过算（宁可让人来看一眼）")
    void coverageDoesNotLetAPassLineHideAFailure() {
        TestOutcome outcome = TestReport.coverage(
                conclude(0, "PASS | 1\nFAIL | 1 | 期望 | 实际 | 代码错了\n"), declared(1), List.of());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.cases()).singleElement()
                .satisfies(caseResult -> assertThat(caseResult.passed()).isFalse());
    }

    /**
     * 脚本报出来的编号必须在清单里。
     *
     * <p>旧实现把它<b>静默丢掉</b>了：真模型实测那次，集成脚本自己加了用例 9、10 并全部通过，
     * 而清单只有 1~8——界面上、留档里一个字都没有。丢掉等于让脚本自己给自己加用例、
     * 自己给自己算通过率，所以现在把它单独报成一条「清单外用例」：
     * 它既不算通过也不算没过，只是「清单对不上」这个事实。
     */
    @Test
    @DisplayName("脚本报了清单外的编号：单独报一条，不许静默丢")
    void coverageReportsCasesOutsideTheList() {
        TestOutcome outcome = TestReport.coverage(
                conclude(0, "PASS | 1\nPASS | 9\n"), declared(1), List.of());

        assertThat(outcome.passed()).as("清单对不上就不算通过（人要先看一眼）").isFalse();
        assertThat(outcome.cases()).as("清单外的编号不进通过率的分母")
                .extracting(TestOutcome.CaseResult::index).containsExactly(1);
        assertThat(outcome.failures()).anySatisfy(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
            assertThat(failure.testCase()).isEqualTo("9");
            assertThat(failure.actual()).contains("不在用例清单里");
        });
    }

    /** 清单外的编号同样不许把「没报的那几条」挤掉：两件事一起说。 */
    @Test
    @DisplayName("清单外用例与没报的用例同时出现：两条都报")
    void coverageKeepsBothOutsideAndMissing() {
        TestOutcome outcome = TestReport.coverage(
                conclude(0, "PASS | 9\n"), declared(2), List.of());

        assertThat(outcome.cases()).extracting(TestOutcome.CaseResult::passed)
                .containsExactly(false, false);
        assertThat(outcome.failures()).hasSize(2);
        assertThat(outcome.detail()).contains("不在用例清单里").contains("没验不等于验过了");
    }

    /**
     * 溯源差异最多列 20 条（模型写歪时一次可能报几十条），但<b>不许静默截断</b>：
     * 剩下的有几条必须说出来——「没列出来」和「不存在」是两件事。
     */
    @Test
    @DisplayName("溯源差异超过上限：只列前 20 条，剩下多少条明说")
    void traceRefusedCapsTheRowsButSaysHowManyAreLeft() {
        List<CaseTraceCheck.Finding> many = new java.util.ArrayList<>();
        for (int index = 1; index <= 25; index++) {
            many.add(new CaseTraceCheck.Finding(CaseTraceCheck.Kind.MISSING, index, "",
                    "测试代码里没有扫到 CASE " + index + " 这一段"));
        }
        TestOutcome outcome = TestReport.traceRefused(1, "tools/20260930-120000",
                List.of("tools/20260930-120000/run.cmd"), declared(25),
                new CaseTraceCheck.Report(List.of(), many));

        assertThat(outcome.failures()).hasSize(21);
        assertThat(outcome.failures().get(20).actual())
                .contains("还有 5 处差异没有列出来").contains("一共 25 处");
        assertThat(outcome.cases()).hasSize(25)
                .allSatisfy(caseResult -> assertThat(caseResult.passed()).isFalse());
        assertThat(outcome.verification().skipped()).as("脚本没跑：不许记成通过或失败").isTrue();
        assertThat(outcome.directory()).as("产物留着给人看差异，收场时才删").isEqualTo("tools/20260930-120000");
    }

    @Test
    @DisplayName("没跑到脚本就收场（产物被拒）也算一条失败，标成测试代码问题")
    void rejectedGenerationIsATestCodeFailure() {
        TestOutcome outcome = TestReport.rejected(1, "第 2 个补丁块想写 src/Foo.java");

        assertThat(outcome.exit()).isEqualTo(TestScriptVerifier.NO_EXIT_CODE);
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
        assertThat(outcome.directory()).as("产物已经清掉了，别再指向一个不存在的地方").isEmpty();
        assertThat(outcome.verification().skipped()).as("脚本没跑，不能记成通过或失败").isTrue();
        assertThat(outcome.detail()).contains("没有被执行");
    }

    @Test
    @DisplayName("失败行的字段少写几个也能读出来——宁可缺一栏，也不要整条失败消失")
    void toleratesShortFailureLines() {
        List<TestOutcome.Failure> failures = TestReport.failures("FAIL | 3 | 期望值");

        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure.testCase()).isEqualTo("3");
            assertThat(failure.expected()).isEqualTo("期望值");
            assertThat(failure.actual()).isEmpty();
        });
    }

    @Test
    @DisplayName("看着像 FAIL 但不是的（FAILURE、failed）不能当成失败行")
    void onlyReadsTheExactMarker() {
        assertThat(TestReport.failures("FAILURE: 构建失败")).isEmpty();
        assertThat(TestReport.failures("0 failures, 3 passed")).isEmpty();
    }

    @Test
    @DisplayName("逐条结论按编号去重排序：PASS 与 FAIL 都算「跑过」")
    void readsPerCaseOutcome() {
        assertThat(TestReport.reported("PASS | 1\nPASS | 2\nFAIL | 3 | a | b | c\n"))
                .extracting(TestOutcome.CaseResult::index)
                .containsExactly(1, 2, 3);
        assertThat(TestReport.reported("没有任何结论行")).isEmpty();
    }

    private static TestOutcome conclude(int exit, String output) {
        VerificationResult result = exit == 0
                ? VerificationResult.passed("测试脚本", "run.cmd", output)
                : VerificationResult.failed("测试脚本", "run.cmd", output, VerificationResult.Kind.NONE);
        return TestReport.conclude(new TestScriptVerifier.ScriptResult(result, exit),
                "tools/20260930-120000", List.of(), 1);
    }

    private static List<PlanReview.TestCase> declared(int count) {
        List<PlanReview.TestCase> cases = new java.util.ArrayList<>();
        for (int index = 1; index <= count; index++) {
            cases.add(new PlanReview.TestCase(index, "用例 " + index, "怎么验",
                    PlanReview.TestCase.Level.MUST, "期望 " + index, "验收"));
        }
        return cases;
    }
}
