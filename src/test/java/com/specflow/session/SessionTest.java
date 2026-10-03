package com.specflow.session;

import com.specflow.history.RunRecord;
import com.specflow.review.AcceptanceCoverage;
import com.specflow.review.PlanReview;
import com.specflow.tests.TestOutcome;
import com.specflow.verify.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话的折叠（§19）：把同一个会话 id 下的 N 条运行留档折成
 * 「第几轮、两个通过率、能不能撤、连着几轮还有失败」。
 *
 * <p>这里全部是<b>纯折叠</b>，一行磁盘都不碰。为什么值得单独钉住：这几个数
 * 是界面上那句「第 3 轮 · 本轮通过率 · 会话累计通过率」的全部依据，而它们错了的表现
 * 是<b>安静地错</b>——分母多算一条、被撤回的轮次还算进累计、软提示该来不来，
 * 每一种看起来都像「正常显示」。
 *
 * <p>另一件被钉住的是<b>快照归谁的</b>：一轮的快照按时间窗口认（{@code startedAt} 到
 * 下一轮的 {@code startedAt} 之间）。这条判据同时回答两个问题——
 * 「撤回本轮撤哪一份」和「磁盘上挂着的快照算不算挡路」；判错的后果是撤到别人的改动。
 */
@DisplayName("会话：把 N 轮折成一句话")
class SessionTest {

    private static final String SESSION = "20261003-100000-000";

    // ---------- 轮次与快照 ----------

    @Test
    @DisplayName("按轮次序号折：每轮认领自己那段时间里拍的那份快照")
    void foldsRoundsAndAssignsSnapshots() {
        List<RunRecord> records = List.of(
                round(1, "2026-10-03T10:00:00.000", "2026-10-03T10:01:00.000", "SUCCESS_UNVERIFIED"),
                round(2, "2026-10-03T10:10:00.000", "2026-10-03T10:11:00.000", "TESTS_FAILED"),
                round(3, "2026-10-03T10:20:00.000", "2026-10-03T10:21:00.000", "SUCCESS_UNVERIFIED"));
        Session session = Session.of(SESSION, records, List.of(
                "20261003-100000-000.pending",
                "20261003-100500-000.pending",   // 第一轮窗口里多出来的一份（取最后那份）
                "20261003-101000-000.pending",
                "20261003-102000-000.pending",
                "20260901-090000-000.pending"));  // 会话之前留下的：谁的都不是

        assertThat(session.rounds()).extracting(Session.Round::round).containsExactly(1, 2, 3);
        assertThat(session.round()).as("现在是第 3 轮").isEqualTo(3);
        assertThat(session.rounds()).extracting(Session.Round::snapshot).containsExactly(
                "20261003-100500-000.pending", "20261003-101000-000.pending",
                "20261003-102000-000.pending");
        assertThat(session.owns("20261003-101000-000.pending")).as("第 2 轮那份是会话自己的").isTrue();
        assertThat(session.owns("20260901-090000-000.pending"))
                .as("会话之前留下的那份不归它管——它要是被算成自己人的，"
                        + "「上一次的改动还没处置」这道门禁就被绕过去了").isFalse();
        assertThat(session.newestFirst()).extracting(Session.Round::round).containsExactly(3, 2, 1);
    }

    @Test
    @DisplayName("没拍到快照的那一轮：不是「还留着改动」，也不是「被撤回」")
    void roundWithoutSnapshotLeftNothing() {
        List<RunRecord> records = List.of(
                round(1, "2026-10-03T10:00:00.000", "2026-10-03T10:01:00.000", "SUCCESS_UNVERIFIED"),
                round(2, "2026-10-03T10:10:00.000", "2026-10-03T10:11:00.000", "FAILED"));
        // 第 2 轮跑失败，引擎自己把改动回滚了：它的快照已经被丢掉，磁盘上只剩第 1 轮那份
        Session session = Session.of(SESSION, records, List.of("20261003-100000-000.pending"));

        Session.Round failed = session.current().orElseThrow();
        assertThat(failed.removed()).as("它自己回滚了：磁盘上没留下东西").isTrue();
        assertThat(failed.live()).isFalse();
        assertThat(failed.undone()).as("「自己回滚」和「被人撤回」是两件事，说法也不同").isFalse();
        assertThat(session.live()).extracting(Session.Round::round).containsExactly(1);
        assertThat(session.canUndoRound())
                .as("最新那一轮没留下改动：这会儿没有可撤的东西").isFalse();
        assertThat(session.undoRoundWhy()).contains("没在磁盘上留下改动");
    }

    @Test
    @DisplayName("撤回过的轮次：不再算数，也不会被再撤一次")
    void undoneRoundIsOutOfTheChain() {
        RunRecord.Settlement undone = new RunRecord.Settlement(
                RunRecord.Settlement.UNDO_ROUND, "2026-10-03T10:30:00", List.of());
        List<RunRecord> records = List.of(
                round(1, "2026-10-03T10:00:00.000", "2026-10-03T10:01:00.000", "SUCCESS_UNVERIFIED"),
                round(2, "2026-10-03T10:10:00.000", "2026-10-03T10:11:00.000", "FAILED", undone));
        Session session = Session.of(SESSION, records, List.of("20261003-100000-000.pending"));

        assertThat(session.chain()).extracting(Session.Round::round)
                .as("被撤回的那一轮不在链上").containsExactly(1);
        assertThat(session.live()).extracting(Session.Round::round).containsExactly(1);
        assertThat(session.canUndoRound()).as("它已经撤过了").isFalse();
        assertThat(session.undoRoundWhy()).contains("已经撤过");
    }

    // ---------- 两个通过率 ----------

    @Test
    @DisplayName("通过率：停用的不进分母，被撤回的轮次不进累计")
    void passRatesSkipDisabledAndUndoneRounds() {
        // 第 1 轮：清单 3 条，第 2 条被停用 → 分母 2，过了 1 条
        List<PlanReview.TestCase> cases = List.of(
                testcase(1, PlanReview.TestCase.Level.MUST),
                testcase(2, PlanReview.TestCase.Level.SHOULD),
                testcase(3, PlanReview.TestCase.Level.OPTIONAL));
        TestOutcome first = new TestOutcome("tools/1", List.of(), 1, 1,
                VerificationResult.failed("测试脚本", "run", "两条没过"),
                List.of(), List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, true),
                        new TestOutcome.CaseResult(3, false)), List.of());
        TestOutcome second = new TestOutcome("tools/2", List.of(), 1, 1,
                VerificationResult.passed("测试脚本", "run", "全过"),
                List.of(), List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, true),
                        new TestOutcome.CaseResult(3, true)), List.of());

        Session session = Session.of(SESSION, List.of(
                full(1, "2026-10-03T10:00:00.000", "2026-10-03T10:01:00.000", "TESTS_FAILED",
                        cases, first, null, List.of(2)),
                full(2, "2026-10-03T10:10:00.000", "2026-10-03T10:11:00.000", "SUCCESS_UNVERIFIED",
                        cases, second, null, List.of())),
                List.of("20261003-100000-000.pending", "20261003-101000-000.pending"));

        Session.Round one = session.rounds().get(0);
        assertThat(one.total()).as("停用的第 2 条退出分母").isEqualTo(2);
        assertThat(one.passed()).as("第 3 条没跑到：没验不能算过").isEqualTo(1);
        assertThat(session.round()).isEqualTo(2);
        assertThat(session.total()).as("累计的分母是两轮各 3 条（这一轮一条都没停用）").isEqualTo(5);
        assertThat(session.passed()).isEqualTo(4);

        // 第 2 轮被撤回：它不再进累计（磁盘上已经没有它的改动了）
        RunRecord.Settlement undone = new RunRecord.Settlement(
                RunRecord.Settlement.UNDO_ROUND, "2026-10-03T10:20:00", List.of());
        Session after = Session.of(SESSION, List.of(
                full(1, "2026-10-03T10:00:00.000", "2026-10-03T10:01:00.000", "TESTS_FAILED",
                        cases, first, null, List.of(2)),
                full(2, "2026-10-03T10:10:00.000", "2026-10-03T10:11:00.000", "SUCCESS_UNVERIFIED",
                        cases, second, undone, List.of())),
                List.of("20261003-100000-000.pending"));
        assertThat(after.total()).as("撤回的那一轮不再进累计").isEqualTo(2);
        assertThat(after.passed()).isEqualTo(1);
    }

    // ---------- 连续三轮那条软提示 ----------

    @Test
    @DisplayName("连着三轮还有失败：给一条软提示；两轮不给")
    void softHintAfterThreeFailingRounds() {
        Session two = Session.of(SESSION, List.of(
                failed(1, "2026-10-03T10:00:00.000"),
                failed(2, "2026-10-03T10:10:00.000")), List.of());
        assertThat(two.consecutiveFailing()).isEqualTo(2);
        assertThat(two.softHint()).as("两轮还不说：说早了就成了噪声").isEmpty();

        RunRecord.Settlement undone = new RunRecord.Settlement(
                RunRecord.Settlement.UNDO_ROUND, "2026-10-03T10:15:00", List.of());
        Session three = Session.of(SESSION, List.of(
                failed(1, "2026-10-03T10:00:00.000"),
                round(2, "2026-10-03T10:10:00.000", "2026-10-03T10:11:00.000", "SUCCESS", undone),
                failed(3, "2026-10-03T10:20:00.000"),
                failed(4, "2026-10-03T10:30:00.000")), List.of());
        assertThat(three.consecutiveFailing())
                .as("被撤回的那一轮不算：从那儿往后数没有意义").isEqualTo(3);
        assertThat(three.softHint())
                .as("软提示必须说清它不挡任何事——用户的口径是「一直到满意」")
                .contains("连着 3 轮").contains("不影响你接着点「下一轮」");
    }

    // ---------- 会话开没开 ----------

    @Test
    @DisplayName("接受过就是收场：留一个撤回档在最后一轮上也不影响这个判断")
    void closedLooksAtEveryRoundNotJustTheLast() {
        // 真会出现的样子：第 3 轮先被人撤回（UNDO_ROUND），然后用户接受整个会话——
        // 撤回档要保留（那一轮真的被撤了），于是「最后那一轮」顶着的不是出口那一档
        List<RunRecord> records = List.of(
                round(1, "2026-10-03T10:00:00.000", "2026-10-03T10:01:00.000", "SUCCESS_UNVERIFIED",
                        new RunRecord.Settlement(RunRecord.Settlement.ACCEPT,
                                "2026-10-03T10:40:00", List.of())),
                round(2, "2026-10-03T10:10:00.000", "2026-10-03T10:11:00.000", "SUCCESS_UNVERIFIED",
                        new RunRecord.Settlement(RunRecord.Settlement.UNDO_ROUND,
                                "2026-10-03T10:20:00", List.of())));

        assertThat(Session.closed(records)).isTrue();
        assertThat(Session.closed(List.of(records.get(1))))
                .as("只有撤回档、没有出口那一档：会话还开着").isFalse();
    }

    // ---------- 辅助 ----------

    private static PlanReview.TestCase testcase(int index, PlanReview.TestCase.Level level) {
        return new PlanReview.TestCase(index, "用例" + index, "", level, "期望" + index, "无");
    }

    private static RunRecord round(int number, String startedAt, String finishedAt, String status) {
        return round(number, startedAt, finishedAt, status, null);
    }

    private static RunRecord round(int number, String startedAt, String finishedAt, String status,
                                   RunRecord.Settlement settlement) {
        return full(number, startedAt, finishedAt, status, null, null, settlement, List.of());
    }

    /** 一条「还有失败」的轮次：终态不是成功那两档。 */
    private static RunRecord failed(int number, String startedAt) {
        return full(number, startedAt, startedAt, "FAILED", null, null, null, List.of());
    }

    private static RunRecord full(int number, String startedAt, String finishedAt, String status,
                                  List<PlanReview.TestCase> cases, TestOutcome tests,
                                  RunRecord.Settlement settlement, List<Integer> disabled) {
        String id = "20261003-10" + String.format("%02d", number) + "00-000";
        List<RunRecord.CaseSwitch> switches = RunRecord.CaseSwitch.append(List.of(), disabled,
                true, startedAt);
        return new RunRecord(id, startedAt, status, null, "把 a 改成 2", List.of(), List.of(),
                null, List.of("Foo.java"), 3, "结论", List.of(),
                List.of(new RunRecord.Change("Foo.java", false, 10, "-a\n+b")),
                List.of(), List.of(), null, cases, tests, null,
                List.of(), settlement, null, List.of(),
                AcceptanceCoverage.check(List.of(), cases, RunRecord.CaseSwitch.disabledIn(switches)),
                null, null, switches.isEmpty() ? null : switches,
                new RunRecord.SessionRef(SESSION, number), finishedAt);
    }
}
