package com.specflow.history;

import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.exception.SpecflowException;
import com.specflow.review.PlanReview;
import com.specflow.tests.TestOutcome;
import com.specflow.verify.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「停用 / 恢复一条用例」（用户的原话是「删掉它」）落在留档上的那一摊。
 *
 * <p>为什么这一栏非测不可：停用会让一条用例<b>退出所有分母</b>——通过率、溯源连线、
 * 回喂、覆盖核对都不再算它。而它的记录方式是<b>一条追加式流水</b>（停用记一条、恢复也记一条），
 * 当下的状态从流水里折出来。这份数据要是错了，表现是「界面上停着、引擎里没停」：
 * 一条用例既不进分母、又照样被要求实现——两边各自的测试都还是绿的。
 */
@DisplayName("停用 / 恢复用例")
class CaseSwitchTest {

    @TempDir
    Path root;

    // ---------- 流水的折叠 ----------

    /**
     * 停用 → 恢复 → 再停用：<b>最后一次动作说了算</b>，而且每一步都留在流水里。
     *
     * <p>「只追加、不改写」是这一栏的全部意义：恢复要是把那条停用记录抹掉，
     * 事后翻记录的人就答不出「是谁、什么时候把它停过的」——而留档存在的理由就是这个。
     */
    @Test
    @DisplayName("停用→恢复→再停用：折叠结果跟着最后一次动作走，每一步都留着")
    void foldsDisableRestoreAndDisableAgain() {
        List<RunRecord.CaseSwitch> disabled = RunRecord.CaseSwitch.append(
                List.of(), List.of(3), true, "2026-10-03T10:00");
        assertThat(disabled).singleElement().satisfies(step -> {
            assertThat(step.index()).isEqualTo(3);
            assertThat(step.disabled()).isTrue();
            assertThat(step.at()).isEqualTo("2026-10-03T10:00");
            assertThat(step.by()).as("「谁停的」照实记操作系统账号——本机工具没有登录这一回事")
                    .isEqualTo(RunRecord.CaseSwitch.actor())
                    .isNotBlank();
        });
        assertThat(RunRecord.CaseSwitch.disabledIn(disabled)).containsExactly(3);

        List<RunRecord.CaseSwitch> restored = RunRecord.CaseSwitch.append(
                disabled, List.of(3), false, "2026-10-03T10:05");
        assertThat(restored).extracting(RunRecord.CaseSwitch::disabled)
                .as("恢复也是一步，不是把上面那条抹掉").containsExactly(true, false);
        assertThat(RunRecord.CaseSwitch.disabledIn(restored)).isEmpty();

        List<RunRecord.CaseSwitch> again = RunRecord.CaseSwitch.append(
                restored, List.of(3), true, "2026-10-03T10:10");
        assertThat(RunRecord.CaseSwitch.disabledIn(again))
                .as("最后一次动作说了算").containsExactly(3);
        assertThat(again).hasSize(3);

        // 几条一起停：折叠结果是升序的一份集合，界面按它重画那一栏
        List<RunRecord.CaseSwitch> many = RunRecord.CaseSwitch.append(
                again, List.of(5, 1), true, "2026-10-03T10:15");
        assertThat(RunRecord.CaseSwitch.disabledIn(many)).containsExactly(1, 3, 5);
        assertThat(RunRecord.CaseSwitch.disabledIn(null)).as("没有流水 = 一条都没停过").isEmpty();
    }

    /**
     * 同一条用例最后一步已经是这个状态时<b>不重复记</b>。
     *
     * <p>界面重画一次就多一条流水的话，真正的动作会被噪声埋掉——而那一栏是给人看的
     * 「谁什么时候把它停过」。
     */
    @Test
    @DisplayName("同状态不重复追加：连点两次停用只留一条")
    void doesNotAppendTheSameStateTwice() {
        List<RunRecord.CaseSwitch> once = RunRecord.CaseSwitch.append(
                null, List.of(3), true, "2026-10-03T10:00");
        List<RunRecord.CaseSwitch> twice = RunRecord.CaseSwitch.append(
                once, List.of(3), true, "2026-10-03T10:30");

        assertThat(twice).as("第二次点没有改变任何东西，不该多一条").hasSize(1);
        assertThat(twice.get(0).at()).as("时间还是第一次那一刻——它才是「什么时候停的」")
                .isEqualTo("2026-10-03T10:00");

        List<RunRecord.CaseSwitch> restored = RunRecord.CaseSwitch.append(
                once, List.of(3), false, "2026-10-03T10:40");
        assertThat(RunRecord.CaseSwitch.append(restored, List.of(3), false, "2026-10-03T10:45"))
                .as("恢复也一样：同状态不重复记").hasSize(2);

        // 认不出来的编号不进流水：记一条清单上没有的编号，等于在留档里留一个指向空处的记号
        assertThat(RunRecord.CaseSwitch.append(restored, List.of(0, -1), true, "2026-10-03T10:50"))
                .hasSize(2);
        assertThat(RunRecord.CaseSwitch.append(restored, null, true, "2026-10-03T10:50"))
                .hasSize(2);
    }

    // ---------- 留档上的停用 ----------

    /**
     * 跑完一次真运行之后停用第 2 条：写进的是<b>留档里那条记录</b>，写盘读回来还在。
     *
     * <p>为什么非要落盘：停用是冲着「那一轮冻结的那份清单」说的（编号只在那份清单里有意义），
     * 记在别处就会拿今天的停用状态去解释当时的通过率。所以写回来之后必须能再读出来。
     */
    @Test
    @DisplayName("停用第 2 条：只在那条留档上追加，写盘的读回来还在；恢复再记一条")
    void recordsTheSwitchIntoTheRunArchive() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        recordRunWithCases(store);
        String id = store.latestId();

        RunRecord updated = store.disable("", List.of(2), true);

        assertThat(updated.id()).as("id 空串 = 界面上正看着的那一次，按最新那条落").isEqualTo(id);
        assertThat(updated.disabledIndexes()).containsExactly(2);
        assertThat(updated.caseSwitches()).singleElement().satisfies(step -> {
            assertThat(step.index()).isEqualTo(2);
            assertThat(step.disabled()).isTrue();
            assertThat(step.by()).isEqualTo(RunRecord.CaseSwitch.actor()).isNotBlank();
            assertThat(step.at()).isNotBlank();
        });
        // 写盘之后再读一遍：留档是这套工具唯一的事后依据，只在内存里对没有意义
        RunRecord reloaded = new RunStore(root.resolve(RunStore.DEFAULT_DIR)).load(id);
        assertThat(reloaded.disabledIndexes()).containsExactly(2);
        assertThat(reloaded.caseSwitches()).hasSize(1);

        // 恢复：状态回到「没停用」，而流水里两条都在（谁什么时候停过它，还查得到）
        RunRecord restored = store.disable(id, List.of(2), false);
        assertThat(restored.disabledIndexes()).isEmpty();
        assertThat(restored.caseSwitches()).hasSize(2);
        assertThat(store.load(id).caseSwitches()).extracting(RunRecord.CaseSwitch::disabled)
                .as("停用与恢复各一条，读回来也在").containsExactly(true, false);
    }

    /** 编号不在那一轮冻结的清单里：当场拒，别在留档里留一个指向空处的记号。 */
    @Test
    @DisplayName("清单外的编号：抛异常并说清是哪个编号，留档一个字节都没动")
    void refusesIndexesOutsideTheFrozenList() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        recordRunWithCases(store);
        String id = store.latestId();

        assertThatThrownBy(() -> store.disable("", List.of(9), true))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("9")
                .hasMessageContaining("不在这次运行冻结的用例清单里");

        assertThat(store.load(id).caseSwitches()).as("被拒的那一次不留痕").isNull();
        assertThat(store.load(id).disabledIndexes()).isEmpty();

        assertThatThrownBy(() -> store.disable("", List.of(), true))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("没说是哪几条用例");
    }

    /**
     * 回喂那道闸：勾了<b>停用着</b>的用例 → 当场拒。
     *
     * <p>拦在这一处而不是默默滤掉：用户勾的是它，而悄悄少喂一条的「下一轮」
     * 看起来和正常的一模一样——白烧一轮真实调用的代价比一句拒绝大得多。
     * {@code --refeed all} 那条路（{@link RunStore#failingCases()}）同理：
     * 它不该把用户明确说过「别再算了」的用例再捞回来。
     */
    @Test
    @DisplayName("回喂：勾停用着的编号当场拒；failingCases() 不再带停用的那几条")
    void refusesToRefeedDisabledCases() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        recordRunWithCases(store);
        assertThat(store.failingCases()).as("没停用之前，上一轮红着的那条就是可回喂的")
                .containsExactly(2);

        store.disable("", List.of(2), true);

        assertThat(store.failingCases()).as("停用的用例已经退出分母，`--refeed all` 不该再捞它")
                .isEmpty();
        assertThatThrownBy(() -> store.refeed(List.of(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("停用")
                .hasMessageContaining("不参与回喂")
                .hasMessageContaining("用例 2");
    }

    /**
     * 老记录里没有 {@code caseSwitches} 这一栏：读出来是 {@code null}，折出来是空集合。
     *
     * <p>读不出来是<b>静默跳过</b>的（见 {@code RunStore.read}），所以这一类问题的表现
     * 只是「历史里少了一条」——没有一条点名的断言，它可以在很久以后才被发现。
     *
     * <p>同一份老记录里的 {@code coverage} 也只有三栏（那时还没有「用例被停用」这一说）：
     * 它<b>必须照样读得出来</b>，{@code disabledOnly} 兜成空表、原来那两栏一个都不许丢。
     * 这条断言是刻意钉在<b>原始 JSON</b> 上的：兼容靠的是规范构造器把缺的那一栏兜成
     * {@code null} 再归一成空表（见 {@code AcceptanceCoverage.Report}），
     * 而不是靠一个三栏的兼容构造器——哪天有人为了「看着稳妥」加回那一个，
     * 这条断言会告诉他老记录本来就走得通。
     */
    @Test
    @DisplayName("老记录没有 caseSwitches、coverage 只有三栏：都读得出来")
    void readsLegacyRecordWithoutCaseSwitches() throws IOException {
        Path dir = root.resolve(RunStore.DEFAULT_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("20260103-000000-000.json"), """
                {
                  "id": "20260103-000000-000",
                  "startedAt": "2026-01-03T00:00",
                  "status": "TESTS_FAILED",
                  "prompt": "老需求",
                  "acceptance": ["A1：a 能变成 2"],
                  "targets": ["a.txt"],
                  "attempts": 1,
                  "detail": "那一次还没有停用这回事",
                  "coverage": {
                    "criteria": ["A1：a 能变成 2"],
                    "uncovered": ["A1：a 能变成 2"],
                    "unmappedMust": []
                  }
                }
                """);

        RunRecord record = new RunStore(dir).load("20260103-000000-000");

        assertThat(record.caseSwitches()).as("老记录里没有这一项").isNull();
        assertThat(record.disabledIndexes()).as("一条都没停用过").isEmpty();
        assertThat(record.coverage().uncovered()).as("老记录那两栏一个都不许丢")
                .containsExactly("A1：a 能变成 2");
        assertThat(record.coverage().disabledOnly())
                .as("老记录里没有这一栏：兜成空表，而不是读不出来").isEmpty();
    }

    // ---------- 辅助 ----------

    /** 这份清单：第 1 条过、第 2 条没过（回喂那两条测试要靠它）。 */
    private static List<PlanReview.TestCase> cases() {
        return List.of(
                new PlanReview.TestCase(1, "a 能变成 2", "读 Foo.java 里的 a",
                        PlanReview.TestCase.Level.MUST, "a == 2", "A1：a 能变成 2"),
                new PlanReview.TestCase(2, "b 能变成 3", "读 Foo.java 里的 b",
                        PlanReview.TestCase.Level.SHOULD, "b == 3", "无"));
    }

    /**
     * 造一条「跑过测试、第 2 条没过」的运行记录——带用例清单，才有可停用的编号。
     *
     * <p>走真的是 {@code RunRecorder}：留档的形状（清单、测试结论、流水三栏怎么落）
     * 只有它说了算，手搓一个 RunRecord 就测不到「写入时有没有带上这一栏」。
     */
    private void recordRunWithCases(RunStore store) {
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                PlanReview.of("做点事", "", List.of(), List.of(), cases()), AgentListener.NOOP);
        recorder.testsFinished(tests());
        recorder.finished(AgentResult.testsFailed(1, List.of(), List.of(), "第 2 条没过"));
    }

    private static TestOutcome tests() {
        return new TestOutcome("tools/20260930-120000", List.of(), 1, 1,
                VerificationResult.failed("测试脚本", "run", "第 2 条没过"),
                List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "2",
                        "b == 3", "b == 4", "code is wrong")),
                List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, false)), List.of());
    }
}
