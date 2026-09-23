package com.specflow.agent;

import com.specflow.patch.PatchApplier;
import com.specflow.verify.VerificationResult;

import java.util.List;

/**
 * 一次开发任务的结果。
 *
 * @param status       终态
 * @param attempts     模型调用次数（含最后一次）
 * @param changes      最终落在磁盘上的改动；失败时为已回滚的改动，仅供报告
 * @param verifications 各校验器的结果，按执行顺序排列
 * @param detail       面向人的说明：成功时是摘要，失败时是原因
 */
public record AgentResult(
        Status status,
        int attempts,
        List<PatchApplier.FileChange> changes,
        List<VerificationResult> verifications,
        String detail
) {

    public enum Status {
        /** 改动已落盘且全部校验通过。 */
        SUCCESS,
        /** 改动已落盘，但校验被跳过——不能算通过，需要人确认。 */
        SUCCESS_UNVERIFIED,
        /** 达到重试上限仍未通过，磁盘已回滚到初始状态。 */
        FAILED,
        /** 失败原因不是代码，而是依赖/环境——重试无用，磁盘已回滚，等人处理。 */
        NEEDS_ENVIRONMENT,
        /** 被人工中断，磁盘已回滚到初始状态。 */
        CANCELLED,
        /** 模型声明信息不足，未做任何改动。 */
        NEEDS_CONTEXT
    }

    public static AgentResult success(int attempts, List<PatchApplier.FileChange> changes,
                                      List<VerificationResult> verifications) {
        return new AgentResult(Status.SUCCESS, attempts, changes, verifications,
                "改动已落盘，校验通过");
    }

    public static AgentResult unverified(int attempts, List<PatchApplier.FileChange> changes,
                                         List<VerificationResult> verifications, String detail) {
        return new AgentResult(Status.SUCCESS_UNVERIFIED, attempts, changes, verifications, detail);
    }

    public static AgentResult failed(int attempts, List<PatchApplier.FileChange> changes,
                                     List<VerificationResult> verifications, String detail) {
        return new AgentResult(Status.FAILED, attempts, changes, verifications, detail);
    }

    /**
     * 校验失败的原因是依赖/环境，不是代码——再给模型几轮也修不好。
     *
     * <p>和 {@link #failed} 分开，是因为这两件事对用户的含义完全不同：
     * 「它没做对」要改需求或改上下文，而「缺依赖」要人去动项目配置。
     */
    public static AgentResult needsEnvironment(int attempts, List<PatchApplier.FileChange> changes,
                                               List<VerificationResult> verifications, String detail) {
        return new AgentResult(Status.NEEDS_ENVIRONMENT, attempts, changes, verifications, detail);
    }

    public static AgentResult cancelled(int attempts, List<PatchApplier.FileChange> changes,
                                        List<VerificationResult> verifications) {
        return new AgentResult(Status.CANCELLED, attempts, changes, verifications,
                "已被人工中断，磁盘已回滚到本次运行前");
    }

    public static AgentResult needsContext(int attempts, String detail) {
        return new AgentResult(Status.NEEDS_CONTEXT, attempts, List.of(), List.of(), detail);
    }

    public boolean succeeded() {
        return status == Status.SUCCESS;
    }
}
