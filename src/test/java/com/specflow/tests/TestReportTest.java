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
 * <p>这是本批里唯一「机器自己下判断」的地方，所以每条判据都要有个反向的例子钉在旁边。
 * 这一批把分档降了级：<b>只有两条硬判据是机器说了算的</b>——命令/环境起不来、超时；
 * 其余每一档（脚本说跑不起来、断言没过、它的代码编不过）都只摆事实，<b>不停机、不回滚、
 * 不自动回喂</b>。判错的代价不对称（实测：一次误判把编译通过的产品改动和测试代码一起收掉），
 * 规则就得写窄。
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
     * 说明它压根没跑到断言——多半是它写的测试代码编不过。
     *
     * <p>它<b>不是</b>环境问题（不回滚），也<b>不是</b>断言没过（人不用去翻产品代码），
     * 而引擎亲见的事实只有一件：<b>一条用例的结论都没跑出来</b>。所以标签按现象写，
     * 不替它判「编译不过」这个原因。
     */
    @Test
    @DisplayName("非 0 退出但一条结论都没有 → 「它的代码编不过」，不是产品代码错、也不回滚")
    void treatsNoConclusionAsUnrunnable() {
        TestOutcome outcome = conclude(1, """
                Check.java:12: error: cannot find symbol
                  symbol: method findByCode(java.lang.String)
                1 error
                """);

        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.UNRUNNABLE);
            assertThat(failure.kind().label()).isEqualTo("它的代码编不过");
            assertThat(failure.actual()).contains("cannot find symbol");
        });
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.UNRUNNABLE);
        assertThat(outcome.environmental()).as("这不是「环境起不来」，不许连累回滚").isFalse();
        assertThat(outcome.detail()).contains("它的代码编不过")
                .contains("一条用例的结论都没跑出来");
    }

    /**
     * 「测试代码引用了不存在的 API」不能收进硬判据：那一档会触发回滚。
     * 编译器的原话长得就像缺东西，所以这条要单独钉住。
     */
    @Test
    @DisplayName("测试代码里的「程序包不存在 / 找不到符号」不算环境起不来")
    void compileErrorsAreNotEnvironment() {
        assertThat(TestReport.environmentEvidence("Check.java:3: error: 程序包 com.demo 不存在")).isNull();
        assertThat(TestReport.environmentEvidence("error: cannot find symbol")).isNull();
        TestOutcome outcome = conclude(1, "Check.java:3: error: 程序包 com.demo 不存在\n");
        assertThat(outcome.environmental()).isFalse();
        assertThat(outcome.hardStopped()).isFalse();
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.UNRUNNABLE);
    }

    /**
     * 脚本打的那句 {@code BLOCKED} 是<b>它的说法</b>，引擎只转述。
     *
     * <p>实测里它把「我写的测试代码编不过」也写成了 {@code BLOCKED}，而旧实现无条件采信，
     * 于是整次回滚：编译通过的产品改动被撤掉、测试产物被删掉，用户两个都看不到。
     */
    @Test
    @DisplayName("脚本打印 BLOCKED：只记它的说法，不停机、不回滚")
    void readsBlockedLineAsTheScriptsOwnWord() {
        TestOutcome outcome = conclude(2,
                "BLOCKED | 没有找到 pytest，装上再跑：pip install pytest\n");

        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.BLOCKED);
            assertThat(failure.actual()).contains("没有找到 pytest");
        });
        assertThat(outcome.environmental()).as("它说的话不等于引擎亲见的环境起不来").isFalse();
        assertThat(outcome.hardStopped()).isFalse();
        assertThat(outcome.detail()).contains("脚本自己说它没跑起来").contains("改动没有回滚");
    }

    @Test
    @DisplayName("输出里有「命令不存在 / 连不上」也只算它的说法（脚本忘了打印 BLOCKED 时的兜底）")
    void readsEnvironmentEvidenceFromOutput() {
        assertThat(TestReport.environmentEvidence("'javac' 不是内部或外部命令，也不是可运行的程序"))
                .contains("不是内部或外部命令");
        assertThat(TestReport.environmentEvidence("sh: 1: python3: command not found"))
                .contains("command not found");
        assertThat(TestReport.environmentEvidence("java.net.ConnectException: Connection refused"))
                .contains("Connection refused");

        TestOutcome outcome = conclude(1, "'mvn' 不是内部或外部命令，也不是可运行的程序\n");
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.BLOCKED);
        assertThat(outcome.environmental()).as("字样是猜的，不是引擎亲见的那一档").isFalse();
    }

    /**
     * 这一批最要紧的一条：<b>机器说了算的只有两条</b>——环境起不来、超时。
     * 其余每一档都可能是机器看走眼，而判错的代价是「把改动收掉，人手里什么都不剩」。
     *
     * <p>而<b>停机不等于回滚</b>：这两档同样只说「别再往下跑了」，磁盘上的改动与测试产物
     * 都留着等人处置（用户 2026-10-02 拍板）。所以这两条各自还要钉住那句收场话——
     * 界面与留档读的都是它，它不说清「未回滚」，人就不知道去「待处置」里找那份改动。
     */
    @Test
    @DisplayName("硬判据只有两条：引擎亲见的起不来与超时；其余各档一律不停机")
    void onlyTwoKindsAreHard() {
        TestOutcome engineSawIt = TestReport.conclude(
                new TestScriptVerifier.ScriptResult(VerificationResult.failed("测试脚本", "run.cmd",
                        "入口脚本起不来：Cannot run program", VerificationResult.Kind.ENVIRONMENT),
                        TestScriptVerifier.NO_EXIT_CODE), "tools/x", List.of(), 1);
        assertThat(engineSawIt.hardStopped()).isTrue();
        assertThat(engineSawIt.environmental()).isTrue();
        assertThat(engineSawIt.detail())
                .as("硬判据那一档的收场话：停下 + 现场留着等人处置")
                .contains("环境起不来").contains("改动未回滚").contains("等你处置")
                .contains("入口脚本起不来");

        TestOutcome timedOut = TestReport.conclude(
                new TestScriptVerifier.ScriptResult(VerificationResult.failed("测试脚本", "run.cmd",
                        "超过 300 秒未结束", VerificationResult.Kind.TIMEOUT),
                        TestScriptVerifier.NO_EXIT_CODE), "tools/x", List.of(), 1);
        assertThat(timedOut.hardStopped()).isTrue();
        assertThat(timedOut.environmental()).as("超时不走「环境问题」那条路").isFalse();
        assertThat(timedOut.detail())
                .as("超时同样不回滚、现场留着").contains("没回滚").contains("等你处置");

        assertThat(conclude(2, "BLOCKED | 连不上库\n").hardStopped())
                .as("脚本说的跑不起来不是硬判据").isFalse();
        assertThat(conclude(1, "FAIL | 1 | a | b | 代码错了\n").hardStopped()).isFalse();
        assertThat(conclude(1, "error: cannot find symbol\n").hardStopped())
                .as("它的代码编不过也不停机：那正是要自动重试的那一档").isFalse();
        assertThat(TestReport.rejected(1, "路径越界").hardStopped()).isFalse();
    }

    /**
     * 自动重试那一条判据（见 {@code TestAgent}）就落在这个方法上：
     * <b>脚本跑了、交了退出码，却一条用例的结论都没报出来</b>。
     * 三条缺一不可，每一条漏掉都会让重试花在错的地方。
     */
    @Test
    @DisplayName("「一条结论都没报出来」：退出码拿得到、没有结论、也没命中硬判据")
    void detectsRunsThatReportedNothing() {
        assertThat(TestReport.ranWithoutConclusions(conclude(1, "error: cannot find symbol\n")))
                .as("跑完了、非 0 退出、一条结论都没有").isTrue();
        assertThat(TestReport.ranWithoutConclusions(conclude(1, "BLOCKED | 连不上库\n")))
                .as("脚本说自己跑不起来，同样是「没有结论」").isTrue();
        assertThat(TestReport.ranWithoutConclusions(conclude(1, "FAIL | 1 | a | b | 代码错了\n")))
                .as("交了结论：能跑但没过，那是交给人那一档，不自动重跑").isFalse();
        assertThat(TestReport.ranWithoutConclusions(conclude(0, "PASS | 1\n")))
                .as("过了的更不用重试").isFalse();
        assertThat(TestReport.ranWithoutConclusions(TestReport.rejected(1, "路径越界")))
                .as("脚本压根没被执行（退出码拿不到）：那是「没跑」，不是「跑不起来」").isFalse();
        assertThat(TestReport.ranWithoutConclusions(conclude(0, "all passed\n")))
                .as("退出 0 却一条都没报：它连结论都没给，也要重来一版").isTrue();
        assertThat(TestReport.ranWithoutConclusions(null)).isFalse();
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
    @DisplayName("退出码 0 但打了 BLOCKED 行：不算通过，但按「它的说法」记，不停机")
    void zeroExitWithBlockedLineIsNotAPass() {
        TestOutcome outcome = TestReport.conclude(
                new TestScriptVerifier.ScriptResult(
                        VerificationResult.passed("测试脚本", "run.cmd", "BLOCKED | javac not found\n"), 0),
                "tools/20260930-120000", List.of(), 1);

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.BLOCKED);
        assertThat(outcome.hardStopped()).isFalse();
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
    @DisplayName("脚本压根起不来（引擎看见的）才是硬判据：原话要带着")
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
    @DisplayName("有 FAIL 行就按断言没过记：哪怕它下面跟了一行 BLOCKED")
    void failLineWinsOverBlockedLine() {
        TestOutcome outcome = conclude(1, """
                FAIL | 1 | 期望 | 实际 | 代码错了
                BLOCKED | 后半段连不上库
                """);

        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.ASSERTION);
        assertThat(outcome.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.kind())
                        .isEqualTo(TestOutcome.Failure.Kind.ASSERTION));
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
