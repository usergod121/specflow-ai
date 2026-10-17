package com.specflow.env;

import com.specflow.core.Atomic;
import com.specflow.core.Cmd;
import com.specflow.core.Progress;
import com.specflow.core.Project;
import com.specflow.core.SpecException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 环境的生命周期：<b>起一次、每次跑前重置、该走的时候走干净</b>。
 *
 * <p>它只管四件事：渲染 compose → {@code docker compose up -d --wait} → 逐条跑 init/reset → 失败收起环境。
 * 它不知道测试是什么（那是 ⑤ 的事），也不读 flow 包（当前流程 id 只从 {@code layout.current()} 那一行文本读）。
 *
 * <p>四条口径，都是踩出来的：
 * <ol>
 *   <li>{@code init}/{@code reset} <b>不写进 compose</b>（见 {@link Compose}），由这里逐条执行、失败即停；</li>
 *   <li>命令原文一律从<b>标准输入</b>喂给容器里的 {@code sh -s}，不走 argv——Windows 上
 *       {@code docker.exe} 会把参数里的引号吃掉一层，用户的 SQL 在容器里散架；</li>
 *   <li>每条命令<b>都带超时</b>，而且超时和「命令失败」在话术里分开说；</li>
 *   <li>失败时把环境<b>收干净</b>（{@code down -v}）：起了一半的环境比干净地没起来糟得多，
 *       下一轮 {@code up} 会去复用那些没人知道来历的半成品。</li>
 * </ol>
 *
 * <p>过程走 {@link Progress} 一个口（不变量 4），原文同时落在 {@code flow/<id>/env/<时间戳>/compose.log}。
 */
public final class Environment {

    /** 引擎渲染出来的 compose：固定名字，清理与留档都按它找。 */
    public static final String COMPOSE_FILE = "compose.yaml";

    /** 起环境 / 重置的原始输出。 */
    public static final String LOG_FILE = "compose.log";

    /** 读不到当前流程 id 时的落点。 */
    public static final String STANDALONE = "standalone";

    /** 起环境 + 跑 init 的时限：包含拉镜像，国内拉几十个镜像一小时不奇怪。 */
    private static final long UP_TIMEOUT_SECONDS = Budgets.defaultSeconds();

    /** 收环境的时限（删卷可以很慢）。 */
    private static final long DOWN_TIMEOUT_SECONDS = 3 * 60;

    /** 只是问一句话（列容器、列卷）的时限。 */
    private static final long QUERY_TIMEOUT_SECONDS = 60;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 跑一条命令。抽成这一个接口，是为了整条生命周期能在没有 Docker 的机器上被完整测出来
     * （测试注入一个逐条记账的假的）；默认实现就是 {@link Cmd#run}。
     */
    @FunctionalInterface
    public interface Runner {

        Cmd.Result run(List<String> argv, Path workdir, String stdin, long timeoutSeconds,
                       Consumer<String> onLine);
    }

    private final Project project;
    private final Runner runner;
    private long upTimeoutSeconds = UP_TIMEOUT_SECONDS;

    /** 生产路径：真的去起进程。 */
    public Environment(Project project) {
        this(project, Cmd::run);
    }

    /** 命令执行器由调用方给（测试用假的把整条路跑出来）。 */
    public Environment(Project project, Runner runner) {
        this.project = project;
        this.runner = runner == null ? Cmd::run : runner;
    }

    /**
     * 「等多久算超时」（分钟）：界面上那一栏改的就是它，别的判据一个字不动。
     *
     * <p>init 用的是<b>同一个数</b>：对用户来说这是同一句话——「这一步我愿意等多久」。
     * 分两个旋钮的结果是实测踩到的那样：用户把时间调大了，init 照样在 5 分钟被掐死。
     */
    public Environment withPullTimeoutMinutes(long minutes) {
        if (minutes > 0) {
            upTimeoutSeconds = minutes * 60;
        }
        return this;
    }

    // ---------- 起 ----------

    /**
     * 备好 app 的依赖缓存卷（{@code <项目名>-home}，compose 里声明成 external）。
     *
     * <p>为什么引擎自己动手建：命名卷第一次创建时属主是 root，而 app 以 {@code 1000:1000} 跑——
     * 不 chown 的话构建工具会在自己的缓存目录上报 Permission denied，而那看起来像"代码的问题"。
     *
     * <p>chown 只在<b>新建</b>时做：缓存里动辄几万个文件，每次 up 都 chown 一遍纯属浪费。
     */
    public static void ensureHomeVolume(String volume, String image, Path workdir) {
        boolean exists = Cmd.run(List.of("docker", "volume", "inspect", volume), workdir, null,
                QUERY_TIMEOUT_SECONDS, null).ok();
        if (exists) {
            return;
        }
        Cmd.run(List.of("docker", "volume", "create", volume), workdir, null, QUERY_TIMEOUT_SECONDS, null);
        Cmd.Result owned = Cmd.run(List.of("docker", "run", "--rm", "-u", "0", "-v",
                        volume + ":/cache", image, "chown", "-R", Compose.SANDBOX_USER, "/cache"),
                workdir, null, QUERY_TIMEOUT_SECONDS, null);
        if (!owned.ok()) {
            throw new SpecException("依赖缓存卷 " + volume + " 建好了，但改不了属主（" + owned.summary()
                    + "）。按理这不该发生；原始输出：\n" + owned.output());
        }
    }

    /**
     * <b>在 app 容器里</b>跑一条命令——① 的能力校验、⑤ 的预检都走这里。
     *
     * <p>它与 {@link Compiler} 的分工，正是这一版吃过亏的那件事：{@code Compiler} 在<b>宿主</b>上跑
     * （"这台机器怎么构建这个项目"），这一条跑在<b>容器</b>里（"下游真要用的时候能不能用"）。
     * ① 的闸问的必须是后一个问题——不然容器里连 mvn 都没有，① 照样亮绿灯（实测踩过）。
     *
     * <p>命令原文走 stdin 交给容器里的 {@code sh -s}，与 ⑤ 的 bash 是同一条通道。
     */
    public Cmd.Result inApp(String command, long timeoutSeconds, Progress progress) {
        Path file = activeCompose();
        if (file == null) {
            return new Cmd.Result(125, "环境还没起过：找不到引擎渲染出来的 " + COMPOSE_FILE
                    + "，先在 ① 点「起环境」", false, 0);
        }
        Run run = new Run(progress == null ? Progress.quiet() : progress, file.getParent());
        try {
            return run.step("在容器里跑：" + commandSummary(command),
                    run.argv("exec", "-T", Compose.APP_SERVICE, "sh", "-s"), command, timeoutSeconds);
        } finally {
            run.flush();
        }
    }

    /** 一句话的命令摘要（进事件流那一栏用；长命令只留第一行前 60 字）。 */
    private static String commandSummary(String command) {
        String first = command == null ? "" : command.lines().findFirst().orElse("").strip();
        return first.length() <= 60 ? first : first.substring(0, 60) + "…";
    }

    /** 起环境：渲染 compose → {@code up -d --wait} → 逐条 init（失败即 {@code down -v} 并抛）。 */
    public Registration up(Progress progress) {
        EnvConfig config = declared();
        Run run = new Run(progress, runDir(stamp()));
        try {
            run.writeCompose(config);
            // 缓存卷先备好：compose 里声明成了 external，不存在的话 up 会直接报错
            ensureHomeVolume(Compose.homeVolume(Compose.projectName(project.root())), config.image(),
                    project.root());
            Cmd.Result up = run.step("起容器（拉镜像 + 等健康检查）", run.argv("up", "-d", "--wait"),
                    null, upTimeoutSeconds);
            stopIfCancelled(progress, run);
            if (!up.ok()) {
                run.down();
                throw failed("起环境", run.commandLine("up", "-d", "--wait"), up, upTimeoutSeconds,
                        "已经 down -v 收干净了（不留半成品）。多半是镜像名写错、拉不到，"
                                + "或者某个依赖的健康检查一直不过——按上面的原文处理。");
            }
            progress.stepDone("容器已经起来了");

            List<String> containers = containers(run);
            List<String> ran = new ArrayList<>();
            List<EnvConfig.Step> init = config.init();
            for (int index = 0; index < init.size(); index++) {
                EnvConfig.Step step = init.get(index);
                String label = "跑第 " + (index + 1) + "/" + init.size() + " 条初始化命令"
                        + (step.inApp() ? "" : "（在「" + step.service() + "」容器里）");
                ran.add(describe(step));
                Cmd.Result result = run.step(label, run.argv(exec(step).toArray(String[]::new)),
                    run.script(step), upTimeoutSeconds);
                stopIfCancelled(progress, run);
                if (!result.ok()) {
                    run.down();
                    throw failed(label, step.command(), result, upTimeoutSeconds,
                            "已经 down -v 收干净了（不留半成品）。这条命令要在容器里成功跑完，"
                                    + "环境才算好了；可以先手工进容器调它：docker compose exec -T "
                                    + service(step) + " sh");
                }
                progress.stepDone(label);
            }
            String note = "环境已就绪" + (ran.isEmpty() ? "" : "（跑了 " + ran.size() + " 条初始化命令）")
                    + "：" + containers.size() + " 个容器";
            progress.done(note, Map.of("containers", containers, "commands", ran));
            return new Registration("READY", containers, volumes(run), run.composeFile().toString(),
                    note + "（" + String.join("、", containers) + "）");
        } catch (SpecException e) {
            progress.fail(headline(e.getMessage()), e.getMessage());
            throw e;
        } finally {
            run.flush();
        }
    }

    // ---------- 每次跑前重置 ----------

    /** 跑 reset：把数据恢复到一个已知状态（环境本身照旧，失败了也按上面那条口径收干净）。 */
    public Registration reset(Progress progress) {
        EnvConfig config = declared();
        Path file = activeCompose();
        if (file == null) {
            throw new SpecException("环境还没起过：先点「起环境」（找不到引擎渲染出来的 "
                    + COMPOSE_FILE + "）");
        }
        Run run = new Run(progress, file.getParent());
        try {
            List<EnvConfig.Step> steps = config.reset();
            for (int index = 0; index < steps.size(); index++) {
                EnvConfig.Step step = steps.get(index);
                String label = "跑第 " + (index + 1) + "/" + steps.size() + " 条重置命令"
                        + (step.inApp() ? "" : "（在「" + step.service() + "」容器里）");
                Cmd.Result result = run.step(label, run.argv(exec(step).toArray(String[]::new)),
                    run.script(step), upTimeoutSeconds);
                stopIfCancelled(progress, run);
                if (!result.ok()) {
                    run.down();
                    throw failed(label, step.command(), result, upTimeoutSeconds,
                            "数据没重置成功，环境已经 down -v 收掉了（半重置的数据不可信）。"
                                    + "先手工跑一遍上面那条命令看它报什么，改好 env.yaml 里的 reset 再起来。");
                }
                progress.stepDone(label);
            }
            String note = steps.isEmpty()
                    ? "没有配 reset：这一轮直接用上一轮留下的数据"
                    : "已重置数据（" + steps.size() + " 条命令）";
            progress.done(note, Map.of("commands", steps.stream().map(EnvConfig.Step::command).toList()));
            return new Registration("READY", containers(run), volumes(run),
                    run.composeFile().toString(), note);
        } catch (SpecException e) {
            progress.fail(headline(e.getMessage()), e.getMessage());
            throw e;
        } finally {
            run.flush();
        }
    }

    // ---------- 收与看 ----------

    /** 收环境：{@code down -v}（连卷一起）。返回一句给人看的话。 */
    public String down() {
        Path file = activeCompose();
        if (file == null) {
            // 从没起过就别去跑 docker：那是拼一条指向不存在文件的命令，
            // 然后拿 docker 的「找不到文件」当成「收环境失败」报给用户——一句假话
            return "环境没起过：找不到引擎渲染出来的 " + COMPOSE_FILE + "，没什么可收的";
        }
        Run run = new Run(Progress.quiet(), file.getParent());
        try {
            Cmd.Result result = run.exec(run.argv("down", "-v"), null, DOWN_TIMEOUT_SECONDS);
            if (result.ok()) {
                return "环境已关闭（down -v）：" + (result.output().isBlank()
                        ? "没有容器需要收" : one(result.output()));
            }
            return "收环境没成功：" + why(result, DOWN_TIMEOUT_SECONDS)
                    + "。原始输出：" + result.output() + notFoundHint(result);
        } finally {
            run.flush();
        }
    }

    /** 给人看的一行：容器名 / 「没声明」/ 「没起过」。 */
    public String status() {
        Optional<EnvConfig> declared;
        try {
            declared = EnvConfigLoader.load(project.layout());
        } catch (SpecException e) {
            return headline(e.getMessage());
        }
        if (declared.isEmpty()) {
            return "没声明环境：项目里没有 " + project.layout().show(project.layout().envYaml());
        }
        Path file = activeCompose();
        if (file == null) {
            return "没起过：还没渲染出 " + COMPOSE_FILE + "（点「起环境」）";
        }
        Run run = new Run(Progress.quiet(), file.getParent());
        Cmd.Result ps = run.exec(run.argv("ps", "--format", "{{.Name}}"), null, QUERY_TIMEOUT_SECONDS);
        if (!ps.ok()) {
            return "问不到容器：" + why(ps, QUERY_TIMEOUT_SECONDS) + notFoundHint(ps);
        }
        List<String> names = lines(ps.output());
        return names.isEmpty()
                ? "环境没起来（compose 文件在 " + project.layout().show(file) + "）"
                : "环境活着：" + String.join("、", names);
    }

    /** 容器名、卷、网络（compose 项目名）、compose 文件路径 —— 收场载荷里那一栏。 */
    public record Registration(String state, List<String> containers, String volumes,
                              String composeFile, String summarize) {

        public Registration {
            containers = containers == null ? List.of() : List.copyOf(containers);
            volumes = volumes == null ? "" : volumes;
            composeFile = composeFile == null ? "" : composeFile;
            if (summarize == null || summarize.isBlank()) {
                summarize = state + "：" + containers.size() + " 个容器"
                        + (volumes.isBlank() ? "" : "、卷 " + volumes);
            }
        }

        /** 不带那句话的写法（那句话按前四项拼）。 */
        public Registration(String state, List<String> containers, String volumes, String composeFile) {
            this(state, containers, volumes, composeFile, null);
        }
    }

    // ---------- 里面那些 ----------

    /** 声明；没声明就抛（正文告诉用户该往哪儿写）。 */
    private EnvConfig declared() {
        return EnvConfigLoader.load(project.layout()).orElseThrow(() -> new SpecException(
                "这个项目没声明环境：在 " + project.layout().show(project.layout().envYaml())
                        + " 里写清 image（项目跑在什么镜像里），再点「起环境」"));
    }

    /** 一条 init/reset 命令的 argv：跑在哪个容器里就写在 {@code -T} 后面（身份仍是容器的非 root）。 */
    private static List<String> exec(EnvConfig.Step step) {
        return List.of("exec", "-T", service(step), "sh", "-s");
    }

    private static String service(EnvConfig.Step step) {
        return step.inApp() ? Compose.APP_SERVICE : step.service();
    }

    /** 留档与界面那一栏用：「在哪个容器里、跑的是什么」一眼看得出差别。 */
    private static String describe(EnvConfig.Step step) {
        return step.inApp() ? step.command() : "[" + step.service() + "] " + step.command();
    }

    /**
     * 用户按了「停下」：当场收场。
     *
     * <p>为什么起环境的半路被叫停还要收：那个类注释里的判据没变——起了一半的环境比干净地
     * 没起来糟得多，下一轮 {@code up} 会去复用一个没人知道来历的环境。
     */
    private void stopIfCancelled(Progress progress, Run run) {
        if (!progress.cancelled()) {
            return;
        }
        run.down();
        throw new SpecException("已叫停（等了 " + progress.elapsedSeconds() + " 秒）："
                + "这次起了一半的东西已经 down -v 收掉了，改完再点一次「起环境」");
    }

    /** 失败原话：<b>超时</b>和<b>退出码</b>分开说，原始输出一个字都不删。 */
    private static SpecException failed(String what, String command, Cmd.Result result, long timeout,
                                         String todo) {
        return new SpecException(what + "没成功：" + why(result, timeout) + "\n"
                + "命令：" + command + "\n"
                + "原始输出（全文）：\n" + result.output() + "\n"
                + todo + notFoundHint(result));
    }

    /** 超时不是「命令失败」：它只说这条命令没跑完，拿它去报「镜像拉不到」就是一句假话。 */
    private static String why(Cmd.Result result, long timeout) {
        return result.timedOut()
                ? "超过 " + timeout + " 秒被强制终止（不是命令失败，是它没跑完）"
                : "退出码 " + result.exit();
    }

    /** docker 都不在这台机器上时，给一句人能照着做的话（别把「起不了任何命令」都说成它）。 */
    private static String notFoundHint(Cmd.Result result) {
        String output = result.output() == null ? "" : result.output();
        return output.contains("起不了这条命令")
                ? "\n没找到 docker 命令：装 Docker Desktop，或者把 docker 加进 PATH（装完重开一个终端）。"
                : "";
    }

    /** 已经起着的容器名；问不到就是空的（不编一个名字出来）。 */
    private List<String> containers(Run run) {
        Cmd.Result ps = run.exec(run.argv("ps", "--format", "{{.Name}}"), null, QUERY_TIMEOUT_SECONDS);
        return ps.ok() ? lines(ps.output()) : List.of();
    }

    /** 这个 compose 项目下的卷（按 compose 打的标签筛，问不到就是空串）。 */
    private String volumes(Run run) {
        Cmd.Result listed = run.exec(List.of("docker", "volume", "ls", "--filter",
                "label=com.docker.compose.project=" + run.name, "--format", "{{.Name}}"),
                null, QUERY_TIMEOUT_SECONDS);
        return listed.ok() ? String.join("、", lines(listed.output())) : "";
    }

    /** 这次落在哪条流程下：读 {@code flow/current} 那一行；读不到或不像目录名就用 standalone。 */
    private String flowId() {
        String text = Atomic.read(project.layout().current(), "").strip();
        int end = text.indexOf('\n');
        if (end >= 0) {
            text = text.substring(0, end).strip();
        }
        // 它会变成目录名，所以只认这一套字符：从文件里读来的东西不能直接拼路径
        return text.matches("[A-Za-z0-9._-]{1,64}") ? text : STANDALONE;
    }

    /** 这一次的落点：{@code flow/<id>/env/<时间戳>}（时间戳而不是随机串：人去翻的时候看得懂）。 */
    private Path runDir(String stamp) {
        return project.layout().envRuns(flowId()).resolve(stamp);
    }

    /** 上一次渲染出来的 compose（收环境与看状态用它）；从来没有过就是 {@code null}。 */
    private Path activeCompose() {
        Path root = project.layout().envRuns(flowId());
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.map(dir -> dir.resolve(COMPOSE_FILE))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparing(file -> file.getParent().getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String stamp() {
        return LocalDateTime.now().format(STAMP);
    }

    private static List<String> lines(String output) {
        return output == null ? List.of()
                : output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static String one(String output) {
        List<String> rows = lines(output);
        return rows.isEmpty() ? "" : rows.get(0) + (rows.size() > 1 ? "（共 " + rows.size() + " 行）" : "");
    }

    private static String headline(String text) {
        return text == null || text.isBlank() ? "出错了" : text.lines().findFirst().orElse(text);
    }

    /**
     * 这一次「起 / 重置 / 收」的落点、日志与命令出口。
     *
     * <p>日志先攒在内存里、收场时一次落盘：一条命令一行一次原子写的话，拉镜像那几万行
     * 会把磁盘写成 O(n²)。
     */
    private final class Run {

        private final Progress progress;
        private final String name;
        private final Path dir;
        private final StringBuilder log = new StringBuilder();

        Run(Progress progress, Path dir) {
            this.progress = progress;
            this.name = Compose.projectName(project.root());
            this.dir = dir;
        }

        Path composeFile() {
            return dir.resolve(COMPOSE_FILE);
        }

        /** 渲染 + 落盘（原子写：半截文件会让下一次 up 报一句与真实原因无关的 YAML 语法错）。 */
        void writeCompose(EnvConfig config) {
            String yaml = Compose.render(config, project.root(), name);
            Atomic.write(composeFile(), yaml);
            log.append("# ").append(composeFile()).append("　（引擎按 env.yaml 渲染，这次起环境的依据）\n")
                    .append(yaml).append('\n');
        }

        /** {@code -p} 与 {@code -f} 只有这一处拼：命令里没有第二个地方能写错项目名或文件。 */
        List<String> argv(String... args) {
            List<String> argv = new ArrayList<>(
                    List.of("docker", "compose", "-p", name, "-f", composeFile().toString()));
            argv.addAll(List.of(args));
            return argv;
        }

        String commandLine(String... args) {
            return String.join(" ", argv(args));
        }

        /** 跑一条命令：输出逐行推给进度口，原文同时留一份进 compose.log。 */
        Cmd.Result exec(List<String> argv, String stdin, long timeoutSeconds) {
            log.append("$ ").append(String.join(" ", argv)).append('\n');
            if (stdin != null) {
                log.append("  （命令原文 ").append(stdin.length()).append(" 字节，从标准输入进去）\n");
            }
            Cmd.Result result = runner.run(argv, project.root(), stdin, timeoutSeconds, progress::line);
            String output = result.output() == null ? "" : result.output();
            log.append(output);
            if (!output.isEmpty() && !output.endsWith("\n")) {
                log.append('\n');
            }
            log.append("→ ").append(result.ok() ? "成功" : why(result, timeoutSeconds))
                    .append("（").append(result.millis()).append(" 毫秒）\n\n");
            return result;
        }

        Cmd.Result step(String label, List<String> argv, String stdin, long timeoutSeconds) {
            progress.step(label, String.join(" ", argv), timeoutSeconds);
            return exec(argv, stdin, timeoutSeconds);
        }

        Cmd.Result down() {
            return exec(argv("down", "-v"), null, DOWN_TIMEOUT_SECONDS);
        }

        /**
         * 把「项目里的那个文件」喂给命令：在容器里落一份临时文件再重定向。
         *
         * <p>为什么不直接把路径写进命令：那个文件在你项目里、不在依赖容器里（依赖容器没挂项目目录），
         * 所以必须由引擎搬。文件读不出来时照跑——脚本里那句 {@code cat} 会失败、命令拿到空输入，
         * 真正发生了什么由容器的输出说，比这里编一句「文件不存在」准。
         */
        String script(EnvConfig.Step step) {
            if (step.stdin().isBlank()) {
                return step.command();
            }
            String content = Atomic.read(project.root().resolve(step.stdin().replace('\\', '/')).normalize(), "");
            String marker = "SPECFLOW_STDIN_EOF";
            // 内容里万一带了同名结束标记，换一个——不然脚本会在那儿被截断
            while (content.contains(marker)) {
                marker = marker + "_";
            }
            StringBuilder script = new StringBuilder();
            script.append("cat > /tmp/specflow-stdin <<'").append(marker).append("'\n").append(content);
            if (!content.endsWith("\n")) {
                script.append('\n');
            }
            script.append(marker).append('\n')
                    .append(step.command()).append(" < /tmp/specflow-stdin\n");
            return script.toString();
        }

        void flush() {
            Atomic.write(dir.resolve(LOG_FILE), log.toString());
        }
    }
}
