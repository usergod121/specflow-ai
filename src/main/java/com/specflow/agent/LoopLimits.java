package com.specflow.agent;

/**
 * 一次 ReAct 运行的上限。
 *
 * @param maxRounds          最多几轮（问一次模型算一轮）
 * @param maxInstalls        一次运行最多执行几次工具调用（跑飞的最后一道闸）
 * @param toolTimeoutSeconds 单次工具调用的超时秒数；{@code <=0} = 不限
 * @param toolAttempts       单次工具调用最多试几次（默认 1：失败原因带回去让模型自己改，
 *                           引擎不替它重试——调用方点名要试才试）
 */
public record LoopLimits(int maxRounds, int maxInstalls, long toolTimeoutSeconds, int toolAttempts) {

    /** 单次输出上限（注入模型的那一份、以及 {@code bash} 留的那一份）。 */
    public static final int MAX_OUTPUT_CHARS = 20_000;

    public static LoopLimits defaults() {
        return new LoopLimits(12, 60, 180, 1);
    }

    public LoopLimits {
        maxRounds = maxRounds <= 0 ? 1 : maxRounds;
        maxInstalls = maxInstalls <= 0 ? 1 : maxInstalls;
        toolAttempts = toolAttempts <= 0 ? 1 : toolAttempts;
    }
}
