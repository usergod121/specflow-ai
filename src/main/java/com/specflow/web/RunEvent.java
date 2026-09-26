package com.specflow.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 界面轮询到的一条运行事件。
 *
 * <p>三种类型，对应界面上三件不同的事：
 * <ul>
 *   <li>{@code log} —— 时间线上的一行：「第 2 轮：调用模型…」；</li>
 *   <li>{@code plan} —— 施工单定下来了，{@code payload} 里是完整的步子列表。
 *       单独一个类型，是为了让界面能<b>一次</b>把「一共几步、每步做什么」画出来，
 *       而不是一步步等出来——那样它在最后一步之前都不知道总共有几步；</li>
 *   <li>{@code result} —— 终态与改动明细，{@code payload} 里是完整的执行结果。</li>
 * </ul>
 *
 * <p>{@code id} 单调递增，界面靠它做增量拉取：记住最后一条的 id，下次只取比它大的。
 *
 * @param id      事件序号
 * @param type    {@code log} / {@code plan} / {@code result}
 * @param level   {@code info} / {@code warn} / {@code error}，仅 log 有意义
 * @param round   第几轮；不属于某一轮时为 0
 * @param step    第几步（施工单上的序号）；不属于某一步时为 0。
 *                它让界面能把一段乱序的日志按步分组，也能把某一步的改动单独展开
 * @param stepState 这条事件把 {@code step} 推到了哪个步态，只<b>在步级事件上</b>有值；
 *                其余事件为 {@code null}，意思是「不改变某一步的状态」。
 *                <p>为什么非要有这个字段：界面原先只能拿 {@code text} 去认
 *                {@code ProgressMessages} 里那几句中文句子来推步态，于是 Java 里改一句措辞，
 *                界面就<b>静默错位</b>——而且没有任何测试会红。步态是引擎知道的事实，
 *                就该由引擎说出来，而不是让人从措辞里反推
 * @param text    面向人的一行说明
 * @param payload 结构化数据，仅 plan 与 result 有值
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunEvent(
        long id,
        String type,
        String level,
        int round,
        int step,
        String stepState,
        String text,
        Object payload
) {

    public static final String TYPE_LOG = "log";
    public static final String TYPE_PLAN = "plan";
    public static final String TYPE_RESULT = "result";

    /**
     * 「这一步正在进行中」在事件里的名字。
     *
     * <p>它不是 {@code AgentListener.StepState} 的一员：那三档是<b>终态</b>，
     * 「未做」和「进行中」由界面按「有没有收到开始 / 结束」自己就知道（见那边的说明）。
     * 事件里需要一个名字，就用这一个——和界面 {@code STEP_STATES} 里的档位一一对应。
     */
    public static final String STEP_RUNNING = "RUNNING";

    /**
     * @param stepState 步态（{@link #STEP_RUNNING}、或 {@code StepState} 的枚举名）；
     *                  不是步级事件时传 {@code null}
     */
    public static RunEvent log(long id, String level, int round, int step, String stepState,
                               String text) {
        return new RunEvent(id, TYPE_LOG, level, round, step, stepState, text, null);
    }

    /** 施工单事件：一次给全，界面据此画出「每步状态」那张图。 */
    public static RunEvent plan(long id, String text, Object payload) {
        return new RunEvent(id, TYPE_PLAN, "info", 0, 0, null, text, payload);
    }

    public static RunEvent result(long id, Object payload) {
        return new RunEvent(id, TYPE_RESULT, "info", 0, 0, null, "任务结束", payload);
    }

    public boolean isResult() {
        return TYPE_RESULT.equals(type);
    }

    public boolean isPlan() {
        return TYPE_PLAN.equals(type);
    }
}
