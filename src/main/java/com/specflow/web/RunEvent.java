package com.specflow.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 界面轮询到的一条运行事件。
 *
 * <p>只有两种类型，因为界面需要区分的事情只有两件：
 * <ul>
 *   <li>{@code log} —— 时间线上的一行：「第 2 轮：调用模型…」</li>
 *   <li>{@code result} —— 终态与改动明细，{@code payload} 里是完整的执行结果</li>
 * </ul>
 *
 * <p>{@code id} 单调递增，界面靠它做增量拉取：记住最后一条的 id，下次只取比它大的。
 *
 * @param id      事件序号
 * @param type    {@code log} 或 {@code result}
 * @param level   {@code info} / {@code warn} / {@code error}，仅 log 有意义
 * @param round   第几轮，未开始时为 0
 * @param text    面向人的一行说明
 * @param payload 结构化数据，仅 result 事件有值
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunEvent(
        long id,
        String type,
        String level,
        int round,
        String text,
        Object payload
) {

    public static final String TYPE_LOG = "log";
    public static final String TYPE_RESULT = "result";

    public static RunEvent log(long id, String level, int round, String text) {
        return new RunEvent(id, TYPE_LOG, level, round, text, null);
    }

    public static RunEvent result(long id, Object payload) {
        return new RunEvent(id, TYPE_RESULT, "info", 0, "任务结束", payload);
    }

    public boolean isResult() {
        return TYPE_RESULT.equals(type);
    }
}
