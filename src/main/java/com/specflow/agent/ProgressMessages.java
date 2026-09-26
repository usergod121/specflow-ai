package com.specflow.agent;

import com.specflow.exception.PatchConflictException;
import com.specflow.patch.PatchApplier;
import com.specflow.review.PlanStep;
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

    /** 施工单定下来时那一行：把「几步、从哪来、为它花了多少调用」一次说清。 */
    public static String stepsResolved(List<PlanStep> steps, AgentListener.StepsSource source,
                                       int probeCalls) {
        // 花了调用就一定要说出来：它不算轮次，但一样是用户掏的钱
        String cost = probeCalls == 0 ? "" : "（为了拿它多花了 " + probeCalls + " 次模型调用）";
        return source.label() + "，共 " + steps.size() + " 步" + cost;
    }

    public static String stepStarted(PlanStep step) {
        return step.title() + "（涉及 " + (step.files().isEmpty()
                ? "未注明文件" : String.join("、", step.files())) + "）";
    }

    /**
     * 一步结束时那一行。
     *
     * <p>「标的是中间态、实际编译通过了」单独说一句：这是好消息，但事后看时间线时
     * 如果只有一句「成功」，就会以为「它说这一步编不过」是句空话。
     * 而它其实是个有用的信号——说明施工单把这一步切得比必要的还碎。
     */
    public static String stepFinished(PlanStep step, AgentListener.StepState state) {
        if (state == AgentListener.StepState.SUCCESS && step.intermediate()) {
            return step.title() + "：标的是中间态，实际编译通过了";
        }
        return step.title() + "：" + state.label();
    }

    public static String stepRestored(PlanStep step, String reason) {
        return step.title() + "：" + reason + "，已回滚到该步开始前的状态";
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
