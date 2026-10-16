package com.specflow.agent;

import java.util.List;

/**
 * 一次 ReAct 运行的结果。
 *
 * @param status      收场档（只有 {@link #DONE} 算成功）
 * @param statusLabel 给人看的那几个字（状态名固定几档，不许自由发挥）
 * @param rounds      实际用掉几轮
 * @param modelCalls  真实的模型调用次数（每一次 HTTP 调用算一次，含「认不出来」的补问）
 * @param reason      为什么这么收场（成功时是空串）
 * @param answer      最终答复（交差时是那条 note；没有就是空串）
 * @param calls       这一次跑过的每一次工具调用，按时间顺序
 */
public record Outcome(String status, String statusLabel, int rounds, int modelCalls,
                      String reason, String answer, List<Call> calls) {

    public static final String DONE = "DONE";
    public static final String ROUND_LIMIT = "ROUND_LIMIT";
    public static final String TOOL_LIMIT = "TOOL_LIMIT";
    public static final String UNPARSABLE = "UNPARSABLE";
    public static final String MODEL_FAILED = "MODEL_FAILED";
    public static final String CANCELLED = "CANCELLED";

    /**
     * 一次工具调用。
     *
     * @param round  第几轮
     * @param tool   工具名
     * @param args   参数原文
     * @param ok     成败
     * @param result 一行摘要（全文在事件流的 {@code resultFull} 里）
     */
    public record Call(int round, String tool, String args, boolean ok, String result) {
    }

    public Outcome {
        status = status == null || status.isBlank() ? MODEL_FAILED : status;
        calls = calls == null ? List.of() : List.copyOf(calls);
        reason = reason == null ? "" : reason;
        answer = answer == null ? "" : answer;
    }

    public boolean ok() {
        return DONE.equals(status);
    }

    /** 状态名的那几个字（界面与收场载荷共用一处，别各处各写一份）。 */
    public static String label(String status) {
        return switch (status == null ? "" : status) {
            case DONE -> "已完成";
            case ROUND_LIMIT -> "轮次用尽";
            case TOOL_LIMIT -> "工具用尽";
            case UNPARSABLE -> "认不出来";
            case CANCELLED -> "已叫停";
            default -> "模型没回来";
        };
    }
}
