package com.specflow.agent;

import java.util.function.Consumer;

/**
 * 沙箱里跑命令的入口。这一包只定义接口：真实现（容器里执行）由测试 Agent 注入。
 *
 * <p>为什么是一个接口而不是直接用 {@code Cmd}：模型写的命令<b>永远只在容器里跑</b>
 * （不变量 8），所以「在哪跑」必须由装配方决定，工具层不许自己起进程。
 */
public interface Shell {

    /**
     * 一条命令的结果。
     *
     * @param exit     退出码；超时被杀时是 -1
     * @param output   完整输出
     * @param timedOut 是不是被超时掐掉的（不是「命令失败」，话术里要分开说）
     */
    record Result(int exit, String output, boolean timedOut) {

        public boolean ok() {
            return !timedOut && exit == 0;
        }
    }

    /**
     * 跑一条命令。
     *
     * @param command        命令原文（由实现决定怎么交给 shell）
     * @param workdir        工作目录（容器内路径）
     * @param timeoutSeconds 超时秒数；{@code <=0} = 不限
     * @param onLine         每一行原始输出（{@code null} = 不看）
     */
    Result run(String command, String workdir, long timeoutSeconds, Consumer<String> onLine);
}
