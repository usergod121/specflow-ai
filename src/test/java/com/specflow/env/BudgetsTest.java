package com.specflow.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>「这一步我愿意等多久」只有一个来源</b>。
 *
 * <p>这条测试守的是一次"文档撒谎"的事故：{@code Compiler} 的 Javadoc 写着「与起环境共用
 * {@code pullTimeoutMinutes} 旋钮」，方法体却是 {@code return 30 * 60;}——用户把 30 改成 90，
 * 编译照样在 30 分钟被掐。所以现在只要有人想再写一个"自己的超时"，
 * 他必须从 {@link Budgets} 拿（这条测试同时把边界钉死）。
 */
class BudgetsTest {

    @Test
    @DisplayName("分钟 → 秒：正常值原样换算")
    void convertsMinutes() {
        assertThat(Budgets.secondsFrom(30)).isEqualTo(30 * 60L);
        assertThat(Budgets.secondsFrom(1)).isEqualTo(60L);
        assertThat(Budgets.secondsFrom(240)).isEqualTo(240 * 60L);
    }

    @Test
    @DisplayName("没设过 / 写坏了：退回默认 30 分钟（不是 0，也不是无限）")
    void fallsBackToDefault() {
        assertThat(Budgets.secondsFrom(0)).isEqualTo(Budgets.defaultSeconds());
        assertThat(Budgets.secondsFrom(-5)).isEqualTo(Budgets.defaultSeconds());
        assertThat(Budgets.defaultSeconds()).isEqualTo(30 * 60L);
    }

    @Test
    @DisplayName("上限 4 小时：手改坏的 flow.json 不该让一条命令挂三天")
    void clampsTheUpperBound() {
        assertThat(Budgets.secondsFrom(10_000)).isEqualTo(240 * 60L);
    }

    @Test
    @DisplayName("给人看的说法：整小时说小时，否则说分钟")
    void describesInWords() {
        assertThat(Budgets.describe(120)).isEqualTo("2 小时");
        assertThat(Budgets.describe(30)).isEqualTo("30 分钟");
    }
}
