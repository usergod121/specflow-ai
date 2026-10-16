package com.specflow.agent.tools;

import com.specflow.agent.LoopLimits;
import com.specflow.agent.Shell;
import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;
import com.specflow.core.Progress;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code bash}：在容器沙箱里跑一条命令。
 *
 * <p>它<b>不自己起进程</b>：命令只从 {@link Shell} 出去（不变量 8）——模型写的命令永远只在容器里跑，
 * 装配时给的那个 {@code Shell} 决定「容器是哪一个」。
 *
 * <p>三件事都在这里兜住：<b>超时</b>（到点由实现杀进程树，这里报「不是命令失败，是超时」）、
 * <b>输出上限</b>（超过就截断并说清还有多少行没给）、<b>逐行进度</b>（{@code progress.line}——
 * 用户要的是「一边跑一边冒」，不是跑完再一次性给）。
 *
 * <p>输出上限比 {@link LoopLimits#MAX_OUTPUT_CHARS} 小一半：结果外面还包着一层 JSON，
 * 循环注入下一轮时还要截一道，留出余量免得被截掉的是最要紧的末尾几行报错。
 */
public final class BashTool implements Tool {

    private static final int OUTPUT_LIMIT = LoopLimits.MAX_OUTPUT_CHARS / 2;

    private final Shell shell;
    private final String workdir;
    private final long timeoutSeconds;
    private final Progress progress;

    /**
     * @param workdir        容器内的工作目录
     * @param timeoutSeconds 单条命令的默认超时；{@code <=0} = 不限（模型可以用 timeout 参数调小，调不大）
     */
    public BashTool(Shell shell, String workdir, long timeoutSeconds, Progress progress) {
        if (shell == null) {
            throw new IllegalArgumentException("Shell 不能为空：命令只能从容器里出去");
        }
        this.shell = shell;
        this.workdir = workdir == null ? "" : workdir;
        this.timeoutSeconds = timeoutSeconds;
        this.progress = progress == null ? Progress.quiet() : progress;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在容器里跑一条 shell 命令，返回退出码与输出。"
                + "编译、跑测试、看文件都靠它；命令只在容器里跑，跑不到宿主上。";
    }

    @Override
    public Map<String, Param> params() {
        return Map.of(
                "command", Param.required("string", "要跑的命令原文"),
                "timeout", Param.optional("int", "这条命令的秒数上限；只能比默认值小"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String command = Args.text(args, "command");
        long timeout = timeoutOf(Args.intOrNull(args, "timeout"));

        StringBuilder kept = new StringBuilder();
        int[] dropped = {0};
        Consumer<String> onLine = line -> {
            progress.line(line);
            if (kept.length() + line.length() + 1 <= OUTPUT_LIMIT) {
                kept.append(line).append('\n');
            } else {
                dropped[0]++;
            }
        };

        Shell.Result result = shell.run(command, workdir, timeout, onLine);
        if (result == null) {
            return ToolResult.failure("容器没有给出结果：这条命令不知道跑没跑");
        }
        if (dropped[0] > 0) {
            kept.append("…（输出超过 ").append(OUTPUT_LIMIT).append(" 个字符，还有 ")
                    .append(dropped[0]).append(" 行没给）\n");
        }
        String output = kept.toString();

        if (result.timedOut()) {
            return ToolResult.failure("命令超过 " + timeout + " 秒没有结束，已被终止\n--- 输出 ---\n" + output);
        }
        if (result.exit() != 0) {
            return ToolResult.failure("命令退出码 " + result.exit() + "\n--- 输出 ---\n" + output);
        }
        return ToolResult.ok(new Result(result.exit(), output, dropped[0] > 0));
    }

    /** 模型只能把超时调小：调大等于绕过装配方定的上限。 */
    private long timeoutOf(Integer requested) {
        long base = timeoutSeconds;
        if (requested == null) {
            return base;
        }
        long asked = Math.max(1, requested);
        return base <= 0 ? asked : Math.min(asked, base);
    }

    /**
     * @param truncated 输出是否被上限截断（截了多少行写在 output 尾巴上）
     */
    public record Result(int exit, String output, boolean truncated) {
    }
}
