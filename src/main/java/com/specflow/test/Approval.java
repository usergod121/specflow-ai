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
 */
@FunctionalInterface
public interface Approval {

    /**
     * 停下来问一句（<b>阻塞</b>到有人回答或到时限）。
     *
     * @param command 命令原文（一字不改地摆给人看）
     * @param reason  为什么要问（来自 {@code env.CommandGuard.highRisk}，是一句人话）
     * @return {@code true} = 许跑
     */
    boolean ask(String command, String reason);

    static Approval allowAll() {
        return (command, reason) -> true;
    }

    static Approval denyAll() {
        return (command, reason) -> false;
    }
}
