package com.specflow.agent;

import com.specflow.exception.PatchConflictException;
import com.specflow.patch.PatchApplier;
import com.specflow.verify.VerificationResult;

import java.util.List;

/**
 * 运行过程中那些「给人看的一句话」。
 *
 * <p>抽成单一来源，是因为同一批文案现在有两个消费者：<b>实时推送给界面</b>的
 * {@link AgentListener} 实现，和<b>落盘留档</b>的运行记录。
 * 各写一遍的话，两处迟早会漂移——界面上说「已回滚」，记录里说「已恢复」，
 * 事后翻日志的人就要靠猜。
 *
 * <p>纯静态、无状态：它只负责把结构化的事件翻译成句子，不决定谁来看、怎么看。
 */
public final class ProgressMessages {

    private ProgressMessages() {
    }

    public static String roundStarted(int round) {
        return "第 " + round + " 轮：调用模型…";
    }

    public static String planRejected(PatchConflictException failure) {
        return "补丁被拒绝，文件未改动——" + failure.getMessage();
    }

    public static String filesApplied(List<PatchApplier.FileChange> changes) {
        if (changes.isEmpty()) {
            return "已写入 0 个文件";
        }
        return "已写入 " + changes.size() + " 个文件："
                + String.join("、", changes.stream().map(PatchApplier.FileChange::relative).toList());
    }

    public static String verified(VerificationResult result) {
        return result.verifier() + "：" + switch (result.status()) {
            case PASSED -> "通过";
            case FAILED -> "未通过";
            case SKIPPED -> "已跳过（" + result.output() + "）";
        };
    }

    public static String restored(String reason) {
        return reason + "，已回滚到本次运行前的状态";
    }

    /** 校验失败时的级别：失败用 error，通过和跳过都是 info。 */
    public static String levelOf(VerificationResult result) {
        return result.failed() ? "error" : "info";
    }
}
