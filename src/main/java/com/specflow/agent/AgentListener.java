package com.specflow.agent;

import com.specflow.exception.PatchConflictException;
import com.specflow.patch.PatchApplier;
import com.specflow.verify.VerificationResult;

import java.util.List;

/**
 * Agent 运行过程的观察者，同时也是它的「刹车」。
 *
 * <p>存在的理由：{@link DevelopmentAgent} 做的事天生是"慢"的——调模型、跑编译，
 * 一轮几秒到几十秒。调用方（CLI、Web UI）需要在这期间把「正在干什么」显示出来，
 * 而不是干等到最后一次性拿到结果。
 *
 * <p>用回调而不是让调用方去读日志，是因为日志是给人看的字符串，
 * 而这里传的是结构化对象（第几轮、哪些文件、校验结果如何），
 * 调用方可以据此渲染进度条、diff 面板或做别的处理。
 *
 * <p>所有方法都是 {@code default} 实现：关心哪个就覆盖哪个。
 * 一个都不关心的调用方直接传 {@link #NOOP}。
 */
public interface AgentListener {

    /** 什么都不做、也不会叫停的实现，用于 CLI 等不关心进度的场景。 */
    AgentListener NOOP = new AgentListener() {
    };

    /**
     * 是否已被要求停止。
     *
     * <p>Agent 在<b>每一轮开始之前</b>询问一次，为 {@code true} 就停下并回滚。
     * 之所以只能轮询而不能真正打断：正在飞行的模型调用没有干净的取消方式，
     * 硬断会留下一个说不清状态的连接。因此中断的粒度是「轮」而不是「秒」。
     */
    default boolean cancelled() {
        return false;
    }

    /** 一轮开始，即将调用模型。 */
    default void roundStarted(int round) {
    }

    /** 模型给出了补丁，但本地校验拒绝落盘（锚点不对、越界、重叠等）。此时磁盘未改动。 */
    default void planRejected(int round, PatchConflictException failure) {
    }

    /** 改动已写入磁盘。 */
    default void filesApplied(int round, List<PatchApplier.FileChange> changes) {
    }

    /** 校验跑完了（可能通过，也可能失败）。 */
    default void verificationFinished(int round, List<VerificationResult> results) {
    }

    /** 校验未通过，工作区已回滚到本轮开始前的状态。 */
    default void workspaceRestored(int round, String reason) {
    }

    /** 任务结束，无论成败都会触发一次。 */
    default void finished(AgentResult result) {
    }
}
