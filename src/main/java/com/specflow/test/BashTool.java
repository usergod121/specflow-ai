package com.specflow.test;

import com.specflow.agent.Shell;
import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;
import com.specflow.core.Progress;
import com.specflow.env.CommandGuard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code bash}：在副本里跑一条命令（经 {@link Shell} 进容器）。
 *
 * <h2>它比"转发一条命令"多做的三件事</h2>
 * <ol>
 *   <li><b>拦住一律不许的写法</b>（{@code CommandGuard.forbidden}）：把宿主挂进容器、
 *       借宿主网络栈、往块设备写——这一类直接失败，问都不问；</li>
 *   <li><b>高危的停下来问</b>（{@code CommandGuard.highRisk} + {@link Approval}）：
 *       被拒时返回一份说清原因、模型可以继续的失败结果（不是异常、也不是整段崩）；</li>
 *   <li><b>逐行推给事件流</b>：{@code docker}/{@code javac} 的输出就是这么变成
 *       界面上那一行一行的——用户要的"看得见它在干什么"落在这里。</li>
 * </ol>
 */
public final class BashTool implements Tool {

    /** 单条命令的默认时限：15 分钟。**这个数必须可配**——写死 300 秒的那一版把长构建掐了两次。 */
    public static final long DEFAULT_TIMEOUT_SECONDS = 15 * 60;

    private final Shell shell;
    private final Path sandboxRoot;
    private final Approval approval;
    private final Progress progress;
    private final long timeoutSeconds;

    public BashTool(Shell shell, Path sandboxRoot, Approval approval, Progress progress) {
        this(shell, sandboxRoot, approval, progress, DEFAULT_TIMEOUT_SECONDS);
    }

    /** @param timeoutSeconds 单条命令最多等多久（由调用方按"这一步我愿意等多久"给） */
    public BashTool(Shell shell, Path sandboxRoot, Approval approval, Progress progress,
                    long timeoutSeconds) {
        this.shell = shell;
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
        this.approval = approval == null ? Approval.allowAll() : approval;
        this.progress = progress == null ? Progress.quiet() : progress;
        this.timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在容器的副本里跑一条命令（看目录、编译核对、跑脚本都算）。"
                + "工作目录默认是副本根；单条命令有超时。不许碰宿主。";
    }

    @Override
    public Map<String, Param> params() {
        Map<String, Param> params = new LinkedHashMap<>();
        params.put("command", Param.required("string", "要执行的命令原文"));
        params.put("workdir", Param.optional("string", "工作目录（相对副本根）；不写就是副本根"));
        return params;
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String command = String.valueOf(args.getOrDefault("command", "")).strip();
        if (command.isEmpty()) {
            return ToolResult.failure("command 是空的：要跑什么？");
        }
        String forbidden = CommandGuard.forbidden(command);
        if (forbidden != null) {
            return ToolResult.failure(forbidden);
        }
        // 提权：容器里不可能成功（uid 1000 + cap_drop ALL + 没有 sudo），所以**不问**，直接拒。
        // 那一版问过：用户等了 187 秒回答"允许"，然后 `sudo: not found`。
        String privilege = CommandGuard.privilege(command);
        if (privilege != null) {
            return ToolResult.failure(privilege);
        }
        // 给自己装工具链：那是 env.yaml 的事。拒掉并指名去 ① 改环境——
        // 不拦的话它会花 20 轮下载 Maven，而容器是一次性的，下一轮全白干（实测）
        String bootstrap = CommandGuard.bootstrap(command);
        if (bootstrap != null) {
            return ToolResult.failure(bootstrap);
        }
        String risk = CommandGuard.highRisk(command);
        if (risk != null && !approval.ask(command, risk)) {
            return ToolResult.failure("这条命令被用户拒绝了，没有执行：`" + command + "`。"
                    + "原因：" + risk + " 换一条不含那种写法的命令，或者换一种做法达到同样的目的。");
        }
        String workdir = String.valueOf(args.getOrDefault("workdir", "")).strip();
        String inside = workdir.isEmpty() ? null : workdir;
        progress.step("bash：" + command, command, 0);
        List<String> tail = new ArrayList<>();
        Shell.Result result = shell.run(command, inside, timeoutSeconds, line -> {
            progress.line(line);
            if (tail.size() < 40) {
                tail.add(line);
            }
        });
        if (result.timedOut()) {
            return ToolResult.failure("这条命令超时被强制终止了：`" + command + "`\n"
                    + String.join("\n", tail));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exit", result.exit());
        data.put("output", clip(result.output()));
        if (result.ok()) {
            progress.stepDone("bash（退出码 0）");
            return ToolResult.ok(data);
        }
        return ToolResult.failure("退出码 " + result.exit() + "：\n" + clip(result.output()));
    }

    /** 交给模型的那一份别太长（完整输出在事件流与留档里）。 */
    private static String clip(String output) {
        String text = output == null ? "" : output;
        List<String> rows = List.of(text.split("\n"));
        if (rows.size() <= 200) {
            return text;
        }
        return String.join("\n", rows.subList(rows.size() - 200, rows.size()))
                + "\n…（只留了最后 200 行，完整输出在留档里）";
    }
}
