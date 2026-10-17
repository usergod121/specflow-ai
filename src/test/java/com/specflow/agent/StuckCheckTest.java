package com.specflow.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原地打转的检测：连续 3 次一样的调用之后打断。
 *
 * <p>对应的事故是"连着 7 轮 {@code sleep 240} 看日志"——每轮都成功、每轮都毫无进展，
 * 7 轮烧掉 17.5 分钟。轮次预算拦不住它（它没失败），所以要有这一条。
 */
class StuckCheckTest {

    /** 一个记账的假工具：返回 success，并把自己被调用了几次记下来。 */
    private static final class Fake implements Tool {
        private int calls;

        @Override
        public String name() {
            return "bash";
        }

        @Override
        public String description() {
            return "假的";
        }

        @Override
        public Map<String, Param> params() {
            return Map.of();
        }

        @Override
        public ToolResult run(Map<String, Object> args) {
            calls++;
            return ToolResult.ok(Map.of("n", calls));
        }
    }

    @Test
    @DisplayName("分级处置：第 4 次只提醒（拦下这一次），第 6 次才判死并让它收场")
    void nudgesThenKills() {
        Fake fake = new Fake();
        Tool guarded = StuckCheck.wrap(fake);
        Map<String, Object> same = Map.of("command", "sleep 240; tail -3 build.log");

        // 前 3 次照常
        for (int at = 0; at < StuckCheck.NUDGE_AT - 1; at++) {
            assertThat(guarded.run(same).ok()).isTrue();
        }
        // 第 4 次：提醒，且这次不执行
        ToolResult nudged = guarded.run(same);
        assertThat(nudged.ok()).isFalse();
        assertThat(nudged.reason()).contains("原地打转").contains("换一种做法");
        assertThat(fake.calls).as("被提醒的那一次不该真的执行").isEqualTo(StuckCheck.NUDGE_AT - 1);

        // 继续同一条 → 到 HARD_AT 就判死，话术变成"该收场"
        ToolResult killed = null;
        for (int at = StuckCheck.NUDGE_AT; at < StuckCheck.HARD_AT; at++) {
            killed = guarded.run(same);
        }
        assertThat(killed).isNotNull();
        assertThat(killed.reason()).contains("卡死").contains("ERROR").contains("这一轮");
    }

    @Test
    @DisplayName("⑤ 传「这条用例」：判死的话术点名标哪一条；不传就按「这一轮」（①~④ 没有用例）")
    void subjectChangesTheWording() {
        Map<String, Object> same = Map.of("command", "sleep 240");
        Tool forCase = StuckCheck.wrap(new Fake(), StuckCheck.CASE);
        Tool forRound = StuckCheck.wrap(new Fake());

        ToolResult caseKilled = null;
        ToolResult roundKilled = null;
        for (int at = 0; at < StuckCheck.HARD_AT; at++) {
            caseKilled = forCase.run(same);
            roundKilled = forRound.run(same);
        }

        assertThat(caseKilled.reason()).contains("这条用例");
        assertThat(roundKilled.reason()).contains("这一轮").doesNotContain("这条用例");
    }

    @Test
    @DisplayName("换一条命令就重新开始计数（换方向不算打转）")
    void differentCallResetsTheCounter() {
        Tool guarded = StuckCheck.wrap(new Fake());
        Map<String, Object> one = Map.of("command", "ls");
        Map<String, Object> two = Map.of("command", "cat pom.xml");

        for (int at = 0; at < StuckCheck.NUDGE_AT - 1; at++) {
            assertThat(guarded.run(one).ok()).isTrue();
        }
        // 换一条 → 计数归零
        for (int at = 0; at < StuckCheck.NUDGE_AT - 1; at++) {
            assertThat(guarded.run(two).ok()).isTrue();
        }
        assertThat(guarded.run(two).ok()).as("新命令攒到 NUDGE_AT 才提醒").isFalse();
    }

    @Test
    @DisplayName("包装之后名字与说明原样透出去（注册表按名字找工具）")
    void keepsTheToolIdentity() {
        Fake fake = new Fake();
        Tool guarded = StuckCheck.wrap(fake);

        assertThat(guarded.name()).isEqualTo("bash");
        assertThat(guarded.description()).isEqualTo("假的");
        assertThat(guarded.params()).isEmpty();
        assertThat(List.of(guarded).get(0).name()).isEqualTo("bash");
    }
}
