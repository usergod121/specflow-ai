package com.specflow.test;

/**
 * 高危命令问谁（合同 §6.6）。
 *
 * <p>为什么是一个只有<b>一个方法</b>的接口：工具那一层只该回答一个问题——
 * 「这条命令到底许不许跑」。至于那个"人"是界面上的 y/n、还是终端里的一行输入、
 * 还是测试里早就准备好的答案，与工具无关。
 *
 * <p>默认 {@link #allowAll()}：命令行与单元测试里没有人可以问，
 * 停在那里等超时比不问更糟。Web 那条路上挂的是 {@link Approvals}。
 *
 * <h2>为什么回答有三种而不是两种</h2>
 * "人不许"和"没人回答"对模型要说两件不同的话：前者是"换一种做法"，
 * 后者是"这条先放下、回头用「重试这一条」重跑"。上一版把它们都说成"被用户拒绝了"，
 * 于是模型会以为用户在看、反复换写法试探——实测里就绕着这条转了。
 */
@FunctionalInterface
public interface Approval {

    /** 一次审批的三种结局。 */
    enum Decision {
        /** 人点了允许。 */
        ALLOW,
        /** 人点了不允许。 */
        DENY,
        /** 时限内没人回答（按拒处理，但话术与 {@link #DENY} 不同）。 */
        TIMEOUT;

        public boolean allowed() {
            return this == ALLOW;
        }
    }

    /**
     * 停下来问一句（<b>阻塞</b>到有人回答或到时限）。
     *
     * @param command 命令原文（一字不改地摆给人看）
     * @param reason  为什么要问（来自 {@code env.CommandGuard.highRisk}，是一句人话）
     * @return 三种结局之一（见 {@link Decision}）
     */
    Decision decide(String command, String reason);

    /** 只要"许不许"的写法（老调用点与测试用）。 */
    default boolean ask(String command, String reason) {
        return decide(command, reason).allowed();
    }

    static Approval allowAll() {
        return (command, reason) -> Decision.ALLOW;
    }

    static Approval denyAll() {
        return (command, reason) -> Decision.DENY;
    }
}
