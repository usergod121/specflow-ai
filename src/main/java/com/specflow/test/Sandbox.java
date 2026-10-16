package com.specflow.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.specflow.agent.Shell;
import com.specflow.core.Cmd;
import com.specflow.core.Json;
import com.specflow.core.Project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * <b>测试 Agent 在容器里跑命令的唯一入口</b>（{@link com.specflow.agent.Shell} 的实现）。
 *
 * <p>它是这条链上唯一一个「执行模型写的命令」的地方，所以边界不在提示词里，全在这个类里：
 *
 * <h2>一、容器没起来 = 一条命令都不跑（fail closed）</h2>
 * {@link #available()} 为假时 {@link #run} 立刻返回一份「没跑起来」的结果，
 * 输出第一行就是原因和待办。<b>这个类里没有任何一条路可以把命令交给宿主</b>——
 * 没有 {@code ProcessBuilder}，没有 {@code cmd /c}，发出去的只有 {@code docker} 客户端本身。
 * 宁可这一轮什么也没验（用户看到「先点起环境」），也不许一条模型写的命令落到宿主的项目树上。
 *
 * <h2>二、命令走 stdin，不走 argv</h2>
 * 发出去的东西只有一条：{@code docker compose -p <项目名> -f <compose> exec -T [-w <目录>]
 * <service> sh -s}，命令原文从标准输入喂进去。理由（上一版在 Windows 上实测踩过）：
 * 命令作为 argv 传给 {@code docker.exe} 时，参数里的双引号会被它的命令行解析吃掉一层，
 * {@code printf '<%s>\n' "A B"} 到容器里只剩 {@code <A>}，而报错方向完全指错。
 * {@code sh -s} 从字节流读脚本，不经过任何命令行解析，引号原样到达容器里的 shell。
 *
 * <p><b>这条 argv 里没有 {@code -u}</b>：跑的是模型写的命令，所以它落在容器默认的身份上
 * （{@code env.Compose} 给 app 写死 {@code 1000:1000}）。加上 {@code -u root} 就等于
 * 把「非 root」这条边界从里面拆掉，而且是悄悄拆掉。
 *
 * <h2>三、失败是结果，不是异常</h2>
 * 容器不在、docker 没起来、镜像里没有 {@code sh}、超时——全是 {@link Result}，
 * 因为调用方是 Agent 的循环，异常从工具里冒出去会把整轮打断（而模型连「为什么」都看不到）。
 *
 * <h2>四、超时要两面说清</h2>
 * 宿主侧那棵进程树由 {@link Cmd} 收；<b>容器里那一半不在宿主的进程树里</b>，
 * 所以超时之后容器里的脚本可能还在跑、还在写产物。这一轮的结果因此<b>不算数</b>，
 * 话术里必须说出来——不然模型会把「被杀之前的半份输出」当成结论。
 */
public final class Sandbox implements Shell {

    /** 调用方没给时限（{@code <=0}）时用它：「不限时」对一条模型写的命令不是个好选项。 */
    public static final long DEFAULT_TIMEOUT_SECONDS = 120;

    /** 问「容器在不在」的时限：这是一次探活，不该拖住界面。 */
    private static final long PROBE_TIMEOUT_SECONDS = 20;

    /** fail closed 那句话的后半截：每个「没跑起来」的出口都要带上它。 */
    private static final String NO_HOST = "这条命令没有执行，也没有落到宿主上";

    /** 项目名归一化的上限（compose 自己的限制，和 env.Compose 取同一个数）。 */
    private static final int MAX_NAME = 40;

    private static final List<Refusal> REFUSALS = List.of(
            new Refusal("no such service",
                    "compose 里没有这个服务：先在 ① 点「起环境」重新起一次，让它按最新的 env.yaml 渲染 compose"),
            new Refusal("is not running",
                    "容器没在跑：先在 ① 点「起环境」"),
            new Refusal("no such container",
                    "容器不在了（被删掉或者被重建过）：重新起一次环境"),
            new Refusal("cannot connect to the docker daemon",
                    "docker 用不了（daemon 没起来）：先启动 Docker Desktop 再试"),
            new Refusal("error during connect",
                    "docker 用不了（daemon 没起来）：先启动 Docker Desktop 再试"),
            new Refusal("failed to connect to the docker api",
                    "docker 用不了（daemon 没起来）：先启动 Docker Desktop 再试"),
            new Refusal("executable file not found",
                    "这个镜像里没有 sh，命令没法在容器里执行（引擎不会退到宿主上跑）：换一个带 sh 的镜像"),
            new Refusal("not found in $path",
                    "这个镜像里没有 sh，命令没法在容器里执行（引擎不会退到宿主上跑）：换一个带 sh 的镜像"));

    private final Project project;
    private final Path composeFile;
    private final String service;
    private final String composeName;

    /**
     * @param project     被测项目（命令的工作目录就是它的根——宿主上只有 docker 客户端在那里跑）
     * @param composeFile ① 渲染出来的那份 compose（不是用户自己的 compose 文件）
     * @param service     compose 里那个常驻服务（{@code env.Compose.APP_SERVICE}，也就是 {@code app}）
     */
    public Sandbox(Project project, Path composeFile, String service) {
        this(project, composeFile, service, null);
    }

    /**
     * 项目名由装配方明说时用这个（{@code env.Compose.projectName(root)} 算出来的那个）。
     *
     * <p>为什么要留这一手：{@code docker compose exec} 必须用<b>起容器时那一个</b>项目名，
     * 而项目名是按项目根的指纹算出来的。三参构造是从渲染出来的 compose 顶上的 {@code name:}
     * 读（① 渲染时写进去了，那就权威）；这条兜底是给「compose 是别处生成、顶上没有 name:」的情形。
     */
    public Sandbox(Project project, Path composeFile, String service, String composeName) {
        if (project == null) {
            throw new IllegalArgumentException("没有项目就没有沙箱：先 Project.open(root)");
        }
        if (composeFile == null) {
            throw new IllegalArgumentException("没有 compose 文件就起不了容器：把 ① 渲染出来的那一份给它");
        }
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("要在哪个服务里跑命令？服务名是空的（一般是 app）");
        }
        this.project = project;
        this.composeFile = composeFile;
        this.service = service.strip();
        this.composeName = nameOf(project, composeFile, composeName);
    }

    /** 命令会去哪个 compose 项目里跑（接线时对账用：它必须等于 ① 起容器时用的那个名字）。 */
    public String composeName() {
        return composeName;
    }

    /** compose 文件在哪（界面上显示「这份环境是哪来的」用）。 */
    public Path composeFile() {
        return composeFile;
    }

    /**
     * 容器在不在。不在的时候 {@link #run} 一条命令都不发出去。
     *
     * <p>判据是「compose 说这个服务有一个在跑的容器」：只认 {@code ps -q} 打印出来的容器 id
     * （十六进制），因为 {@code ps} 的告警行（时间戳、缺变量之类）也走同一个输出流，
     * 把它当容器 id 会让「没起来」被读成「起来了」。
     */
    public boolean available() {
        if (!Files.isRegularFile(composeFile)) {
            return false;
        }
        Cmd.Result probe = Cmd.run(probeArgv(), project.root(), null, PROBE_TIMEOUT_SECONDS, null);
        return probe.ok() && runningId(probe.output());
    }

    /**
     * 跑一条命令。<b>不抛异常</b>：没容器、docker 不在、超时，都是 {@link Result} 里的一件事。
     *
     * @param command        命令原文（多行也照原样进容器里的 {@code sh}）
     * @param workdir        容器里的工作目录；空 = 用容器默认的（{@code env.yaml} 的 workdir）
     * @param timeoutSeconds 超时秒数；{@code <=0} = 按 {@value #DEFAULT_TIMEOUT_SECONDS} 算
     * @param onLine         每一行原始输出（{@code null} = 不看）
     */
    @Override
    public Result run(String command, String workdir, long timeoutSeconds, Consumer<String> onLine) {
        if (command == null || command.isBlank()) {
            return notStarted("命令是空的：没有东西可跑");
        }
        if (!available()) {
            return notStarted("测试容器没起来（" + service + " 不在跑，或者这份 compose 还没起过）："
                    + "先在 ① 点「起环境」把容器拉起来再试；" + NO_HOST);
        }
        List<String> argv = new ArrayList<>(List.of("docker", "compose", "-p", composeName,
                "-f", composeFile.toString(), "exec", "-T"));
        if (workdir != null && !workdir.isBlank()) {
            argv.add("-w");
            argv.add(workdir.strip());
        }
        // 不加 -u：模型写的命令落在容器默认的非 root 身份上（这是边界，不是偏好）
        argv.add(service);
        argv.add("sh");
        argv.add("-s");

        long seconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        Cmd.Result raw = Cmd.run(argv, project.root(), command, seconds, onLine);
        if (raw.timedOut()) {
            return new Result(-1, "命令超过 " + seconds + " 秒没结束，已经在宿主侧强制终止"
                    + "（容器里那一半可能还在跑、还在写产物，所以这一轮的结果不算数）：\n" + raw.output(),
                    true);
        }
        if (raw.exit() == -1) {
            // 没超时却是 -1：Cmd 连进程都没起来（docker 不在 PATH / daemon 没开 / 这次运行被叫停）
            return notStarted("docker 命令起不来（Docker Desktop 没开、docker 不在 PATH 上，"
                    + "或者这次运行被叫停）：" + firstLine(raw.output()) + "；" + NO_HOST);
        }
        String refusal = refusalOf(raw);
        if (refusal != null) {
            return notStarted(refusal + "；" + NO_HOST + "\n（docker 的原话）\n" + raw.output());
        }
        return new Result(raw.exit(), raw.output(), false);
    }

    // ---------- 内部 ----------

    /**
     * 「这条命令没跑起来」的结果。
     *
     * <p>{@code Shell.Result} 没有 started 这一栏，所以约定是：<b>{@code exit == -1} 且没超时
     * = 没跑起来</b>（超时也是 -1，但 {@code timedOut} 为真，两件事必须分得开）。
     */
    private static Result notStarted(String reason) {
        return new Result(-1, reason, false);
    }

    private List<String> probeArgv() {
        return List.of("docker", "compose", "-p", composeName, "-f", composeFile.toString(),
                "ps", "-q", service);
    }

    /** 输出里有没有一行像容器 id（短 id 是十二位十六进制；告警行不是）。 */
    private static boolean runningId(String output) {
        for (String line : output.split("\\R")) {
            if (line.strip().matches("[0-9a-f]{12,64}")) {
                return true;
            }
        }
        return false;
    }

    /**
     * docker 说了「这条命令跑不了」吗。
     *
     * <p>只在退出码非 0 时才认：命令自己打印的 {@code no such file or directory}
     * 是<b>命令的结果</b>，把它翻译成「环境坏了」会让人去查一个根本没坏的东西。
     */
    private static String refusalOf(Cmd.Result raw) {
        if (raw.exit() == 0) {
            return null;
        }
        String output = raw.output().toLowerCase(Locale.ROOT);
        for (Refusal refusal : REFUSALS) {
            if (output.contains(refusal.pattern())) {
                return refusal.todo();
            }
        }
        return null;
    }

    /** 报错话术里给人看的那一句（输出第一行）。 */
    private static String firstLine(String output) {
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (!text.isEmpty()) {
                return text.length() > 300 ? text.substring(0, 300) + "…" : text;
            }
        }
        return "";
    }

    /** 项目名：明说的 > 渲染出来的 compose 顶上的 {@code name:} > 按目录名猜一个（只是兜底）。 */
    private static String nameOf(Project project, Path composeFile, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        String declared = declaredName(composeFile);
        if (!declared.isBlank()) {
            return declared;
        }
        return "sf-" + slug(project.root());
    }

    /**
     * compose 顶上的 {@code name:}（{@code env.Compose.render} 写进去的那一行）。
     *
     * <p>读它而不是自己算：{@code docker compose exec} 必须命中<b>起容器时</b>那一个项目名，
     * 而那个名字是 ① 算的；① 的算法要变（换指纹、换前缀），这里跟着它就对了，
     * 不需要两个包各维护一套算法然后再也对不上。
     */
    private static String declaredName(Path composeFile) {
        try {
            JsonNode root = Json.readYaml(composeFile, JsonNode.class);
            JsonNode name = root == null ? null : root.get("name");
            return name == null || name.isNull() ? "" : name.asText("").strip();
        } catch (RuntimeException e) {
            // 读不出来不是「名字算不出来」，是「这份 compose 用不了」——available() 会去问 docker
            return "";
        }
    }

    /** 目录名 → compose 认的字符集（{@code [a-z0-9-]}），全中文的目录名会清空。 */
    private static String slug(Path root) {
        Path folder = root.getFileName();
        StringBuilder out = new StringBuilder();
        for (char ch : (folder == null ? "" : folder.toString()).toLowerCase(Locale.ROOT).toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                out.append(ch);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        if (out.length() > MAX_NAME) {
            out.setLength(MAX_NAME);
        }
        return out.length() == 0 ? "project" : out.toString();
    }

    /** 一类「docker 不肯跑」的原话，和遇到它时该对用户说的话。 */
    private record Refusal(String pattern, String todo) {
    }
}
