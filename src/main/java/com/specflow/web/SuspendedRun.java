package com.specflow.web;

import com.specflow.history.RunRecord;

/**
 * 「有一次运行挂着等人补料」的界面视图。
 *
 * <p>模型上一次输出的那段 {@code NEED_CONTEXT} 原文就在运行留档里（{@code detail}），
 * 所以这里不另存一份：界面要显示的就是它当时说的那句话。
 *
 * @param present  现在有没有挂着的一次运行
 * @param runId    那份留档的 id，用于显示与「从哪一条接着跑」
 * @param need     模型当时输出的原文（要让人看懂它到底缺什么）
 * @param attempts 那次运行调用了几次模型
 * @param repeated 连着第几次说缺——第 2 次起界面要开始劝人补料或改需求
 */
public record SuspendedRun(
        boolean present,
        String runId,
        String need,
        int attempts,
        int repeated
) {

    public static SuspendedRun none() {
        return new SuspendedRun(false, null, "", 0, 0);
    }

    public static SuspendedRun of(RunRecord record, int repeated) {
        return new SuspendedRun(true, record.id(),
                record.detail() == null ? "" : record.detail(),
                record.attempts(), repeated);
    }
}
