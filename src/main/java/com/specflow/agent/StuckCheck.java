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

    /**
     * 连续第几次开始<b>提醒</b>。
     *
     * <p>为什么不是"第 3 次直接判死"（我们原来就是）：深读 OpenHands 的
     * <code>openhands/controller/stuck.py</code> 发现它是<b>分级处置</b>——第 3 次只注入一条纠正消息、
     * 第 4 次才判死。那一份研究里有句话很扎：<b>我们现在是在最有救的那一刻把用例判死</b>——
     * 模型第 3 次重复往往正是它快摸到原因的时候。
     *
     * <p>所以这里也分两级：第 4 次<b>提醒</b>（拦下这一次、把话说明白），
     * 第 6 次<b>判死</b>（告诉它该收场了，把轮次留给别的用例）。
     * 数字比 OpenHands 宽松，因为我们的度量单位是"一次工具调用"而不是"一个完整步骤"。
     */
    public static final int NUDGE_AT = 4;

    /** 连续第几次算"真的卡死了"。 */
    public static final int HARD_AT = 6;

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
        if (same >= HARD_AT) {
            return ToolResult.failure("同一条调用你已经连着做了 " + same + " 次，每次结果都一样——"
                    + "这是卡死，不是推进。现在该收场了：把这条用例标 ERROR 说清「卡在哪一步」"
                    + "（比如「构建到这里就没动静」），把剩下的轮次留给别的用例。"
                    + "用户看到结论后可以用「重试这一条」再试。");
        }
        if (same >= NUDGE_AT) {
            return ToolResult.failure("同一条调用你已经连着做了 " + same + " 次，结果都一样："
                    + "这是原地打转。换一种做法——换命令、换方向，或者直接读输出/日志找出真正的原因。"
                    + "（再连着来 " + (HARD_AT - same) + " 次我就当这条卡死、让你标 ERROR 收场。）");
        }
        return inner.run(args);
    }
}
