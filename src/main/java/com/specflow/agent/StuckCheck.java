package com.specflow.agent;

import java.util.Map;

/**
 * <b>原地打转的检测</b>：同一条调用连着做太多次，就打断它并回喂一句"你在原地打转"。
 *
 * <h2>它为什么存在（实测）</h2>
 * 用户那个项目的 ⑤ 里，Agent 连着 7 轮发 {@code sleep 240} 去看构建日志——
 * 每一轮都"成功"，每一轮都毫无进展，7 轮烧掉 17.5 分钟。轮次预算拦不住这种：
 * 它没有失败，它只是在原地转。
 *
 * <h2>分寸</h2>
 * 只做<b>一条</b>判据：<b>连续 {@value #REPEATS} 次完全相同的调用</b>（工具名 + 参数原文）。
 * 这条判据不误伤"反复读同一个文件"吗？会误伤一点点——但那种情况本来就该被提醒
 * （"你已经读过三次了"）。更复杂的三条（输出无变化、只读不写、只写不验）留到以后，
 * <b>这一版只做这一条，宁可少做也不做半截</b>。
 *
 * <h2>为什么是装饰器而不是改 {@code Loop}</h2>
 * {@code Loop} 的时序被 13 条测试钉着（每次调用推两条事件、callId 成对……）。
 * 装饰器同样的效果，零风险，而且能单独测。
 */
public final class StuckCheck implements Tool {

    /** 连续几次之后打断。3 次是折中：一次重复可能是重试，两次可能是碰巧，三次就是转了。 */
    public static final int REPEATS = 3;

    private final Tool inner;
    private String last;
    private int same;

    private StuckCheck(Tool inner) {
        this.inner = inner;
    }

    /** 包一层（可以和其他装饰器叠着用，顺序不影响语义）。 */
    public static Tool wrap(Tool inner) {
        return new StuckCheck(inner);
    }

    @Override
    public String name() {
        return inner.name();
    }

    @Override
    public String description() {
        return inner.description();
    }

    @Override
    public Map<String, Param> params() {
        return inner.params();
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String key = name() + " " + String.valueOf(args);
        if (key.equals(last)) {
            same++;
        } else {
            last = key;
            same = 1;
        }
        if (same > REPEATS) {
            // 把这一条也算进去，所以从第 REPEATS+1 次开始拦
            return ToolResult.failure("同一条调用你已经连着做了 " + same + " 次，每次的结果都一样："
                    + "这是原地打转，不是推进。换一种做法——"
                    + "（1）换一种命令或换一个方向；（2）如果确实卡住了，就把这条用例标 ERROR 说清卡在哪，"
                    + "把轮次留给别的用例；（3）如果是环境缺东西，那不是你该修的，回 ① 改 env.yaml。");
        }
        return inner.run(args);
    }
}
