package com.specflow.env;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 跑一条外部命令，把「起没起来、退出码、输出」原样交回来。
 *
 * <p>为什么要抽这一个接口：整个 Docker 环境这一批里，唯一<b>没法在这台机器上真跑</b>的东西
 * 就是 docker 本身（开发机上没有 Docker）。抽一层之后，生命周期与清理的每一条分支都能用
 * 一个假命令跑出来（白盒），同时又能用真的 {@code docker.cmd} 桩把「命令拼得对不对、
 * 参数顺序对不对」验出来（灰盒）——见 {@link ProcessCommandRunner}。
 *
 * <p>它<b>不抛异常</b>（除了进程被中断这种没得商量的情况）：命令起不来、超时、非 0 退出，
 * 都是这一层要回答的结果，而不是要炸掉调用栈的错误。把它们抛出去，调用方就只能靠
 * catch 来分支，而「命令不存在」和「命令跑失败了」恰恰是这套流程里最需要分开的两件事。
 */
public interface CommandRunner {

    /**
     * 一次执行的结果。
     *
     * @param started  进程起没起来。{@code false} 只有一种原因：这个命令在这台机器上不存在
     * @param exit     退出码；{@link #NOT_STARTED} 表示压根没跑起来
     * @param timedOut 超时被杀。它和「非 0 退出」分开：超时说明不了命令失败，只说它没跑完——
     *                 拿它当失败去报「镜像拉不到」就是一句假话
     * @param output   标准输出与错误输出的合并（顺序就是它们出现的顺序）
     */
    record Result(boolean started, int exit, boolean timedOut, String output) {

        /** 没跑起来时的退出码。用一个不可能是真退出码的值，免得被误读成「它退出了，码是 -1」。 */
        public static final int NOT_STARTED = -1;

        public static Result notStarted(String output) {
            return new Result(false, NOT_STARTED, false, output == null ? "" : output);
        }

        public static Result finished(int exit, String output) {
            return new Result(true, exit, false, output == null ? "" : output);
        }

        /** 跑完了、退出码 0，而且没超时。 */
        public boolean ok() {
            return started && !timedOut && exit == 0;
        }

        /** 输出里第一行有内容的——报错时给人看的那一句。 */
        public String firstLine() {
            for (String line : output.split("\\R")) {
                String text = line.strip();
                if (!text.isEmpty()) {
                    return text.length() > 300 ? text.substring(0, 300) + "…" : text;
                }
            }
            return "";
        }
    }

    /**
     * 执行一条命令。
     *
     * @param command        命令与参数（不含 shell：不经 shell 就没有引号转义可错）
     * @param environment    额外注入的环境变量
     * @param workdir        工作目录；{@code null} 表示不指定
     * @param timeoutSeconds 超时秒数
     */
    Result run(List<String> command, Map<String, String> environment, Path workdir,
               long timeoutSeconds);
}
