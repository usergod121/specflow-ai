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
    @DisplayName("同一条命令第 4 次被拦下，而且话里给了三条出路")
    void stopsTheFourthIdenticalCall() {
        Fake fake = new Fake();
        Tool guarded = StuckCheck.wrap(fake);
        Map<String, Object> same = Map.of("command", "sleep 240; tail -3 build.log");

        assertThat(guarded.run(same).ok()).as("前 3 次照常").isTrue();
        assertThat(guarded.run(same).ok()).isTrue();
        assertThat(guarded.run(same).ok()).isTrue();

        ToolResult stopped = guarded.run(same);

        assertThat(stopped.ok()).isFalse();
        assertThat(stopped.reason()).contains("原地打转").contains("ERROR").contains("env.yaml");
        assertThat(fake.calls).as("被拦下的那次不该真的执行").isEqualTo(3);
    }

    @Test
    @DisplayName("换一条命令就重新开始计数（换方向不算打转）")
    void differentCallResetsTheCounter() {
        Tool guarded = StuckCheck.wrap(new Fake());
        Map<String, Object> one = Map.of("command", "ls");
        Map<String, Object> two = Map.of("command", "cat pom.xml");

        assertThat(guarded.run(one).ok()).isTrue();
        assertThat(guarded.run(one).ok()).isTrue();
        assertThat(guarded.run(one).ok()).isTrue();
        // 换一条 → 计数归零
        assertThat(guarded.run(two).ok()).isTrue();
        assertThat(guarded.run(two).ok()).isTrue();
        assertThat(guarded.run(two).ok()).isTrue();
        assertThat(guarded.run(two).ok()).as("新命令第 4 次才拦").isFalse();
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
