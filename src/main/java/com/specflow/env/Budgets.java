package com.specflow.env;

/**
 * <b>「这一步我愿意等多久」——只有一个来源</b>。
 *
 * <h2>为什么要有这么一个类（一次真实的谎）</h2>
 * 上一版的 {@code Compiler} 在 Javadoc 里写着「与起环境共用 {@code pullTimeoutMinutes} 那个旋钮」，
 * 方法体却是 {@code return 30 * 60;}——而界面上的「改超时」只把那个数字记进 facts、再回显给用户看，
 * <b>没有任何一处读它来设置真正的超时</b>。于是用户把 30 改成 90，什么都不会发生：
 * 编译照样在 30 分钟被掐、测试照样在 600 秒被掐。这是一种最难查的错——
 * 文档说有、界面给了、代码没接。
 *
 * <p>现在：界面上的分钟数 → {@code flow.json} 的 {@code pullTimeoutMinutes} ←
 * 这里是它<b>唯一</b>的换算处；起环境、编译、⑤ 的容器与单条命令、⑤ 的整轮，全从这一个数来。
 *
 * <h2>两个边界</h2>
 * <ul>
 *   <li><b>下限 1 分钟</b>：写 0 或负数没有意义（那是"别等"，而长任务没有"别等"这一档）；</li>
 *   <li><b>上限 4 小时</b>：接口那一层已经拦了 1440 分钟，这里再兜一次——一个手改坏的
 *       {@code flow.json} 不该让某条命令挂 3 天。</li>
 * </ul>
 */
public final class Budgets {

    /** 用户没设过时的默认值（分钟）。与界面上那一栏的默认值同一个数。 */
    public static final int DEFAULT_MINUTES = 30;

    /** 分钟数的合法范围（界面上那一栏、以及手改 flow.json 的情况，都按这个范围收）。 */
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 240;

    private Budgets() {
    }

    /** 分钟数 → 秒；超出范围就夹到边界上。 */
    public static long secondsFrom(int minutes) {
        int sane = minutes < MIN_MINUTES ? DEFAULT_MINUTES : Math.min(minutes, MAX_MINUTES);
        return sane * 60L;
    }

    /** 没给分钟数时的那一份（编译、起环境、一轮测试的兜底）。 */
    public static long defaultSeconds() {
        return secondsFrom(DEFAULT_MINUTES);
    }

    /** 界面/日志里给人看的说法。 */
    public static String describe(int minutes) {
        long seconds = secondsFrom(minutes);
        return seconds % 3600 == 0 ? (seconds / 3600) + " 小时" : (seconds / 60) + " 分钟";
    }
}
