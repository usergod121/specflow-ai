package com.specflow.env;

import com.specflow.exception.SpecflowException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 这台机器上有没有可用的 Docker——<b>三个结果分开报</b>（十五.5）。
 *
 * <p>为什么要分三档而不是「能用 / 不能用」：这三件事用户要做的事完全不一样。
 * <ul>
 *   <li><b>命令找不到</b> —— 没装 Docker，或者装了但 PATH 里没有。要去装 / 去填命令；</li>
 *   <li><b>daemon 没起</b> —— 命令在，但 Docker Desktop 没开（Windows 上占绝大多数）。
 *       点一下图标就行，跟「没装」差着十万八千里；</li>
 *   <li><b>可用</b> —— 可以起环境。</li>
 * </ul>
 * 合成一句「Docker 不可用」，用户第一件事就是去重装一个他本来就装好的东西。
 *
 * <p><b>怎么探</b>：直接跑 {@code docker version}。不引 SDK、不连 socket——CLI 自己会读
 * {@code DOCKER_HOST}、context、{@code .docker/config.json} 这些设置，而进程继承环境变量就够了。
 * 自己实现一份等价逻辑，等于把这些设置各重写一遍，还只能覆盖自己想到的那几种。
 *
 * <p><b>找命令的顺序</b>（前一条说明「这台机器上没有它」时才试下一条）：
 * <ol>
 *   <li><b>用户手填的命令</b>（{@code env.yaml} 里的 {@code docker.command}，例如 {@code wsl docker}）——
 *       它是用户对<b>这台机器</b>的明确交代，排在猜测之前。写这一行的人要么知道
 *       PATH 上那条不能用，要么是要接一个包装脚本；把它排在后面，等于让一个猜测
 *       去否决一句明确的交代；</li>
 *   <li>{@code docker}——PATH 上有就是它，绝大多数机器走这一级；</li>
 *   <li><b>常见安装路径</b>——Docker Desktop 装完不一定把 bin 放进 PATH。</li>
 * </ol>
 *
 * <p>顺序上「先听交代、再猜」这一点值得说清楚，因为它是本节里唯一一处偏离
 * 十五.5 字面顺序的地方（那边写的是 PATH → 常见路径 → 用户手填，且限定在
 * 「命令找不到时」）。偏离的理由有两个，都实测过：一是用户明明填了命令却被 PATH 上
 * 那条否决掉，「手填」这个功能就等于没有；二是没有别的办法把 Docker 换成一个假命令——
 * 而「用假的 docker 把生命周期与清理测出来」正是这批的验收方式。
 * 前一条说明「找不到」时才轮到下一条；「找到了但用不了」是另一档，不会再去换命令。
 */
public final class DockerProbe {

    /** {@code docker version} 最长等多久。daemon 半死不活时它会挂住，等久了界面就没反应了。 */
    private static final long TIMEOUT_SECONDS = 20;

    /** 探一次用的子命令。它就是「命令在不在、daemon 活不活」这一个问题的答案。 */
    private static final List<String> VERSION = List.of("version");

    /** 这台机器上「命令找不到」长什么样。三个系统各有一套说法。 */
    private static final List<String> NOT_FOUND_HINTS = List.of(
            "is not recognized as an internal or external command",
            "不是内部或外部命令",
            "no such file or directory",
            "command not found",
            "cannot find the file",
            "系统找不到指定的文件",
            "系统找不到指定的路径");

    /** 探测结果的三档。 */
    public enum State {

        /** 这台机器上找不到 docker 命令（三级都试过了）。 */
        COMMAND_NOT_FOUND("找不到 docker 命令"),
        /** 找到了命令，但它用不了——最常见的是 daemon 没启动。 */
        DAEMON_DOWN("daemon 没起来"),
        /** 可用。 */
        READY("可用");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一次探测。
     *
     * @param state   三档之一
     * @param command 真正用起来的那条命令（找不到时是空表）；{@code docker} 与
     *                {@code wsl docker} 是两条不同的命令，界面上要说清用的是哪条
     * @param output  命令的原始输出（找不到时是「试过哪些」）。<b>原始错误必须留着</b>——
     *                十五.5 要的是「立刻停 + 原始错误 + 待办」，掐掉它就没法让人自己判断了
     */
    public record Result(State state, List<String> command, String output) {

        public Result {
            command = command == null ? List.of() : List.copyOf(command);
            output = output == null ? "" : output;
        }

        public boolean ready() {
            return state == State.READY;
        }

        /** 界面上那一行：说清三档里的哪一档，以及凭什么。 */
        public String describe() {
            return switch (state) {
                case READY -> "Docker 可用（" + shown() + "）";
                case DAEMON_DOWN -> "Docker 命令在（" + shown() + "），但 daemon 没起来："
                        + firstLine();
                case COMMAND_NOT_FOUND -> "找不到 docker 命令。试过：" + output;
            };
        }

        /** 用户该做什么——只有三档，所以待办也就三种。 */
        public String todo() {
            return switch (state) {
                case READY -> "";
                case DAEMON_DOWN -> "把 Docker 启动起来（Windows 上是打开 Docker Desktop，等它状态变绿），"
                        + "然后再初始化测试环境";
                case COMMAND_NOT_FOUND -> "装上 Docker，或者在 .specflow/env.yaml 里写一行 "
                        + "docker: command: 把你机器上 docker 的完整命令填进去"
                        + "（Docker 装在 WSL 里的话，可以写 \"wsl docker\"）";
            };
        }

        private String shown() {
            return command.isEmpty() ? "?" : String.join(" ", command);
        }

        private String firstLine() {
            for (String line : output.split("\\R")) {
                String text = line.strip();
                if (!text.isEmpty()) {
                    return text.length() > 300 ? text.substring(0, 300) + "…" : text;
                }
            }
            return "（没有任何输出）";
        }
    }

    private DockerProbe() {
    }

    /**
     * 探一次。
     *
     * @param runner      怎么跑命令。真实路径传 {@link ProcessCommandRunner}，
     *                    测试传一个假的——Docker 这台机器上没有，整批逻辑不能因此测不出来
     * @param userCommand 用户手填的命令（{@code env.yaml} 里的 {@code docker.command}）；没有传 {@code null}
     */
    public static Result probe(CommandRunner runner, String userCommand) {
        List<String> tried = new ArrayList<>();
        for (List<String> candidate : candidates(userCommand)) {
            CommandRunner.Result result = runner.run(concat(candidate, VERSION), Map.of(), null,
                    TIMEOUT_SECONDS);
            tried.add(shown(candidate));
            if (!found(result)) {
                // 这一条命令在这台机器上不存在：继续试下一条候选。
                // 注意只有「找不到」才继续——「找到了但用不了」是另一档结果，不能靠换命令解决：
                // 换一条命令试，会把「Docker 没启动」这种事变成一句「找不到 docker」，方向就错了
                continue;
            }
            // 命令在，但没跑出 0：三个结果里没有第四档，所以归「daemon 没起来」这一档，
            // 但 raw 输出原样带出去。确实是连不上 daemon 时用户点一下图标就好；
            // 是别的错（context 配错、权限不够）时他也能从原文里看出来——
            // 而塞进「找不到命令」那一档，他会去重装一个本来就装好的东西
            return new Result(result.ok() ? State.READY : State.DAEMON_DOWN, candidate,
                    result.ok() ? "" : raw(result));
        }
        return new Result(State.COMMAND_NOT_FOUND, List.of(), String.join("；", tried));
    }

    /**
     * 按顺序要试的那几条命令。
     *
     * <p>手填的命令拆成多个参数（{@code "wsl docker"} → {@code [wsl, docker]}）：
     * 用户写的就是一条命令行，而 {@code ProcessBuilder} 要的是参数表。不经 shell 拆，
     * 就没有引号转义可错。
     *
     * <p>顺序见类注释：先听用户的交代，再猜 PATH 和常见安装路径。
     */
    static List<List<String>> candidates(String userCommand) {
        List<List<String>> all = new ArrayList<>();
        if (userCommand != null && !userCommand.isBlank()) {
            all.add(splitCommand(userCommand));
        }
        all.add(List.of("docker"));
        all.addAll(COMMON_INSTALLS);
        return all;
    }

    /** 常见安装路径。装了但没进 PATH，是 Windows 上 Docker Desktop 的常态。 */
    private static final List<List<String>> COMMON_INSTALLS = installs();

    private static List<List<String>> installs() {
        List<List<String>> found = new ArrayList<>();
        if (windows()) {
            // Docker Desktop 的默认位置（任务书里点名的那一个）
            addIfExists(found, "C:/Program Files/Docker/Docker/resources/bin/docker.exe");
            // 它的另一个 bin：命令行从「开始菜单」进过之后，会在用户目录里放一份
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                addIfExists(found, local.replace('\\', '/') + "/Docker/wsl-cli/docker");
            }
        } else {
            addIfExists(found, "/usr/bin/docker");
            addIfExists(found, "/usr/local/bin/docker");
            addIfExists(found, "/opt/homebrew/bin/docker");
        }
        return found;
    }

    /**
     * 只在文件真的在时才把它排进候选。
     *
     * <p>不存在的路径排进去，只是让探测多花一次「起进程、立刻失败」的时间——
     * 而失败信息还会混进「试过哪些」那一栏，让用户以为工具在他机器上乱翻。
     */
    private static void addIfExists(List<List<String>> found, String path) {
        Path file = Path.of(path);
        if (Files.isRegularFile(file)) {
            found.add(List.of(path));
        }
    }

    /** 这一次是不是「命令不存在」。 */
    private static boolean found(CommandRunner.Result result) {
        if (!result.started()) {
            return false;
        }
        if (result.ok()) {
            return true;
        }
        String text = lower(result.output());
        for (String hint : NOT_FOUND_HINTS) {
            if (text.contains(lower(hint))) {
                // 找到了一个「自己说找不到自己」的东西：`wsl docker` 在没装 docker 的发行版里
                // 就是这个样子（wsl 起来了，docker 不存在）——继续往下试
                return false;
            }
        }
        return true;
    }

    /**
     * 给用户看的原始错误。
     *
     * <p>超时时输出里已经有一句「超过 N 秒没有回话」，直接用；否则docker 没打任何东西时
     * 至少把退出码报出来——空着会让人以为命令成功了。
     */
    private static String raw(CommandRunner.Result result) {
        if (result.timedOut()) {
            return result.output();
        }
        return result.output().isBlank() ? "退出码 " + result.exit() : result.output();
    }

    private static List<String> concat(List<String> command, List<String> args) {
        List<String> all = new ArrayList<>(command);
        all.addAll(args);
        return all;
    }

    private static String shown(List<String> command) {
        return String.join(" ", command);
    }

    /**
     * 把用户写的一行命令拆成参数。
     *
     * <p>只按空白拆，不认引号：用户填的是「怎么调 docker」这件事的前缀
     * （{@code wsl docker}、{@code docker.exe}），它不含带空格的参数。
     * 认引号会引入一套半吊子的解析规则，而它没法被穷尽测到。
     */
    static List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        for (String part : command.strip().split("\\s+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            throw new SpecflowException("docker 命令是空的");
        }
        return parts;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
