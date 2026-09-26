package com.specflow.agent;

import com.specflow.exception.PatchConflictException;
import com.specflow.patch.PatchApplier;
import com.specflow.review.PlanStep;
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
 * 而这里传的是结构化对象（第几轮、第几步、哪些文件、校验结果如何），
 * 调用方可以据此渲染进度条、diff 面板或做别的处理。
 *
 * <p><b>两级粒度</b>：{@code round} 是「调了一次模型」，{@code step} 是施工单上的一步
 * （一步里可能调好几次模型）。两者都在回调里，因为界面两种都要：
 * 轮级回答「现在在干什么」，步级回答「整件事做到哪了」。
 * 没有施工单时（单步执行）只有轮级回调，步级一个都不发。
 *
 * <p>所有方法都是 {@code default} 实现：关心哪个就覆盖哪个。
 * 一个都不关心的调用方直接传 {@link #NOOP}。
 */
public interface AgentListener {

    /** 什么都不做、也不会叫停的实现，用于 CLI 等不关心进度的场景。 */
    AgentListener NOOP = new AgentListener() {
    };

    /**
     * 施工单是从哪来的。
     *
     * <p>它是「这次运行和别人不一样在哪」的唯一答案，所以必须看得见：
     * 走检查和不走检查，除了这几条以外行为完全一致（同一套循环、同一套回滚）。
     */
    enum StepsSource {

        /** 检查阶段产出的，人看过、点过确认的那一份。 */
        APPROVED("检查阶段产出的施工单"),
        /** 没跑过检查，开工前花一次调用现生成的。 */
        GENERATED("没有检查结果，开工前现生成的施工单"),
        /**
         * 上一次挂起的运行已经定下来的那一份，这次接着用。
         *
         * <p>它不等于 {@link #APPROVED}：那一份是人看过的，这一份只是「已经付过钱的」。
         * 混成一个值，界面就会说错这次的单子是哪来的。
         */
        RESUMED("接着上一次那条留档里的施工单"),
        /** 拿不到可用的施工单，按单步执行（= 不升格分步的老行为）。 */
        SINGLE("没有可用的施工单，按单步执行");

        private final String label;

        StepsSource(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一步的终态。
     *
     * <p>「未做」和「进行中」不在这里：未做 = 施工单里有它、记录里没有它；
     * 进行中 = 收到了 {@link #stepStarted} 还没收到 {@link #stepFinished}。
     * 再给这两个单独造两个值，等于同一件事有两种说法。
     */
    enum StepState {

        /** 这一步做完了，而且整个项目编译通过。 */
        SUCCESS("成功"),
        /**
         * 这一步声明为「中间态」，编译没过——按约定不算失败，继续下一步。
         *
         * <p>为什么单独给一个状态而不是塞进 SUCCESS：它意味着<b>此刻磁盘上的代码是坏的</b>，
         * 界面上必须看得出来，否则用户会以为中间那次编译失败是误报。
         */
        INTERMEDIATE("中间态（编译未通过）"),
        /** 这一步重试耗尽，整个运行已经回滚到起点。 */
        FAILED("失败");

        private final String label;

        StepState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 是否已被要求停止。
     *
     * <p>Agent 在<b>每一轮开始之前、以及步与步之间</b>各问一次，为 {@code true} 就停下并回滚。
     * 之所以只能轮询而不能真正打断：正在飞行的模型调用没有干净的取消方式，
     * 硬断会留下一个说不清状态的连接。因此中断的粒度是「轮」而不是「秒」。
     *
     * <p>施工单把一次运行变成几十次模型调用之后，「步与步之间」这一问是必需品——
     * 只问轮的话，用户按下停止之后还要等一整步跑完。
     */
    default boolean cancelled() {
        return false;
    }

    /**
     * 施工单定下来了（在第一次调用模型之前）。
     *
     * <p>整份一次性给出来，而不是一步一条：界面要能一眼看出「一共几步、现在在第几步、还差什么」，
     * 一步步喂的话它在最后一步之前都不知道总共有几步。
     *
     * @param steps      按执行顺序排好的步子；退化单步执行时是只有一步的列表
     * @param source     它是从哪来的
     * @param probeCalls 为了拿到它额外花了多少次模型调用（检查阶段给的就 <b>0</b> 次）。
     *                   单独说出来是因为它<b>不算轮次</b>：轮次是「装配一次上下文写一次代码」，
     *                   而这是问一句话。混在一起，用户看到的「共 N 轮」就对不上了
     */
    default void stepsResolved(List<PlanStep> steps, StepsSource source, int probeCalls) {
    }

    /** 一步开始，即将把这一小步的施工指令追加进对话。 */
    default void stepStarted(PlanStep step) {
    }

    /**
     * 一步结束。
     *
     * @param step  刚结束的那一步
     * @param state 终态
     */
    default void stepFinished(PlanStep step, StepState state) {
    }

    /**
     * 这一步失败，已回滚到<b>该步开始前</b>的状态，准备重试。
     *
     * <p>和 {@link #workspaceRestored} 分开：那个是整个运行回滚（已经做完的步骤一起丢），
     * 这个只丢当前这一步。两者说成同一句话，用户就分不清「刚才那次失败损失了多少」。
     */
    default void stepRestored(PlanStep step, int round, String reason) {
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
