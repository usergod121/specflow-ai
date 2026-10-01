package com.specflow.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specflow.tests.TestArtifacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试环境的生命周期：<b>起一次、每次跑前重置、常驻复用、该走的时候走干净</b>（十五.5/15.8）。
 *
 * <p>它管四件事，别的都不管：
 * <ol>
 *   <li><b>起</b>（{@link #up()}）：写 compose → {@code up -d --wait} → 跑 {@code init}。
 *       {@code --wait} 是死的，<b>不许用 sleep 等</b>：睡眠时长是在猜这台机器的速度，
 *       而健康检查是容器自己说的「我好了」；</li>
 *   <li><b>重置</b>（{@link #reset()}）：跑 {@code reset} 命令。每次跑测试之前都要跑，
 *       不能省——上一轮留下的数据会让这一轮的断言时对时错，而那看起来像被测代码不稳定；</li>
 *   <li><b>收</b>（{@link #down(boolean)}）：{@code down -v}，连卷一起。
 *       只在环境坏了 / 关项目 / 用户手动时做，容器平时留着复用；</li>
 *   <li><b>收残局</b>（{@link #cleanupLeftovers()}）：打开项目时把上一轮没清掉的东西收掉。</li>
 * </ol>
 *
 * <p><b>分层在哪。</b>它不知道测试是什么、也不跑测试脚本——那是 {@code TestAgent} 的事。
 * 它只回答一个问题：这套环境现在能不能用；不能用的话，卡在哪、原始错误是什么、该怎么办。
 * 这条边界让它能在没有 Docker 的机器上被完整测出来（{@link CommandRunner} 是可以换的）。
 *
 * <p><b>失败一律落成 {@link EnvProblem}</b>，不落成异常堆栈：起不来、拉不到、健康检查超时、
 * init/reset 失败，都是「环境问题」这一档，都要说清是哪条命令、原始错误是什么、
 * 接下来该干什么（十五.5）。
 */
public final class TestEnvironment {

    private static final Logger log = LoggerFactory.getLogger(TestEnvironment.class);

    /** 给测试脚本的环境变量：compose 项目名、compose 文件、容器里的项目目录。见 {@link #variables()}。 */
    public static final String COMPOSE_PROJECT_VAR = "SPECFLOW_COMPOSE_PROJECT";
    public static final String COMPOSE_FILE_VAR = "SPECFLOW_COMPOSE_FILE";
    public static final String WORKDIR_VAR = "SPECFLOW_WORKDIR";

    /**
     * 起环境的时限：默认 10 分钟。
     *
     * <p>这么长是因为它包含<b>拉镜像</b>——第一次在一台干净机器上拉一个几 G 的镜像，
     * 十分钟不算多。超时之后的收场是「杀掉 + 报环境问题」，用户还能重来；
     * 时限给短了，一次正常的初始化会被判成失败，那才是真耽误事。
     */
    private static final long UP_TIMEOUT_SECONDS = 10 * 60;

    /** 跑一条 init/reset 命令的时限：容器已经起来了，这一步通常只有几秒。 */
    private static final long EXEC_TIMEOUT_SECONDS = 5 * 60;

    /** 查询（列表、状态）的时限：它只是问一句话，慢就说明 docker 那边有问题了。 */
    private static final long QUERY_TIMEOUT_SECONDS = 60;

    /** 收环境的时限。 */
    private static final long DOWN_TIMEOUT_SECONDS = 3 * 60;

    /**
     * 残留到了几件就该说一句。
     *
     * <p>残留本身不稀奇（崩一次就有一件），但<b>攒到五件</b>说明收尾这件事已经反复没做成——
     * 那时候用户该知道的不是「有几件残留」，而是「这套环境一直在漏」。
     */
    public static final int LEFTOVER_WARN_THRESHOLD = 5;

    /**
     * 这个件数要不要告警。
     *
     * <p>阈值判据只有这一处：界面、CLI、以及任何后来的调用方都用它。
     * 各写一遍的话，「攒到五件」这句注释和某个 `>` 之间的距离，就是一条永远不响的告警
     * （实测踩过：一处写成 `>`，正好五件时哑火，而注释说的是「攒到五件」）。
     */
    public static boolean shouldWarnAboutLeftovers(int count) {
        return count >= LEFTOVER_WARN_THRESHOLD;
    }

    /**
     * 读 docker 给出来的 JSON（{@code {{json .Mounts}}}）。
     *
     * <p>用 Jackson 而不是自己切字符串：它是这个项目里读 JSON 的唯一一份实现，
     * 而挂载信息里那些路径在 Windows 上带反斜杠和空格，手切必然出错。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path projectRoot;
    private final CommandRunner runner;

    /** 探测过一次就记住：{@code docker version} 每次都要起进程，界面一次打开会问好几遍。 */
    private DockerProbe.Result probe;

    /** 上一次探测用的那条命令。用户在 {@code env.yaml} 里改了它，就得重探（见 {@link #docker(boolean)}）。 */
    private String probedCommand;

    public TestEnvironment(Path projectRoot, CommandRunner runner) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.runner = runner;
    }

    /** 生产路径：真的去起进程。 */
    public static TestEnvironment of(Path projectRoot) {
        return new TestEnvironment(projectRoot, new ProcessCommandRunner());
    }

    // ---------- 声明与探测 ----------

    /**
     * 这份声明；没写 {@code env.yaml} 时为空。
     *
     * <p><b>每次现读，不缓存</b>：这份文件是用户在编辑器里改的，而「改完刷新页面还是老样子」
     * 是最容易让人以为工具坏了的一种表现。读一个几百字节的 yaml 是微秒级的事，
     * 真正贵的是 {@code docker version}（要起进程）——那一个才缓存。
     */
    public Optional<EnvConfig> config() {
        return EnvConfigLoader.load(projectRoot);
    }

    /** 有没有声明测试环境。 */
    public boolean declared() {
        return config().isPresent();
    }

    /** 探一次（带缓存）。 */
    public DockerProbe.Result docker() {
        return docker(false);
    }

    /**
     * 探一次。
     *
     * @param refresh 无视缓存重探。用户刚把 Docker 启动起来时会用到它——
     *                缓存是为了别反复起进程，而「我刚开好」这件事只有用户自己知道
     */
    public DockerProbe.Result docker(boolean refresh) {
        String command = config().map(EnvConfig::dockerCommand).orElse(null);
        // 声明里那条命令变了也要重探：换了命令还用旧结论，等于把用户刚改的那一行当没写
        if (probe == null || refresh || !java.util.Objects.equals(probedCommand, command)) {
            probe = DockerProbe.probe(runner, command);
            probedCommand = command;
            log.info("Docker 探测：{}", probe.describe());
        }
        return probe;
    }

    /** compose 项目名。没声明时也给得出（它按项目目录算），清理时要靠它。 */
    public String composeProject() {
        return ComposeFile.projectName(projectRoot);
    }

    // ---------- 状态 ----------

    /**
     * 现在这套环境是什么状态。
     *
     * @param state        见 {@link EnvRegistration.State}
     * @param registration 这一档状态下的登记（容器/网络/卷/目录），给界面和留档用
     * @param leftovers    没人管的残留件数
     * @param todo         用户下一步该做什么；没有下一步时是空串
     */
    public record Status(boolean declared, DockerProbe.Result docker, EnvRegistration.State state,
                         EnvRegistration registration, int leftovers, String todo) {

        /** 能跑集成测试了。 */
        public boolean usable() {
            return state == EnvRegistration.State.READY;
        }

        /** 现在能不能点「初始化环境」——没声明、或者 docker 用不了，点了也是白点。 */
        public boolean canInit() {
            return declared && docker != null && docker.ready();
        }

        /** 探测到的是哪一档（{@code COMMAND_NOT_FOUND} / {@code DAEMON_DOWN} / {@code READY}）；没探过是空串。 */
        public String dockerState() {
            return docker == null ? "" : docker.state().name();
        }

        /** 探测那一段话（说清哪一档、凭什么）；没探过是空串。 */
        public String dockerLabel() {
            return docker == null ? "" : docker.describe();
        }
    }

    /** 现在是什么状态。它会去问 docker，所以别在循环里调。 */
    public Status status() {
        if (!declared()) {
            // 先看有没有声明，再决定要不要探：反过来的话，每个只跑单元测试的项目
            // 打开一次界面就要起两次 docker 进程——而那份声明大多数项目根本没有
            return new Status(false, null, EnvRegistration.State.NOT_DECLARED,
                    registration(EnvRegistration.State.NOT_DECLARED, List.of(), List.of(), false,
                            "项目里没有 " + EnvConfigLoader.relativePath() + "：只能跑单元测试"),
                    0, "要跑集成测试就先写 " + EnvConfigLoader.relativePath()
                            + "（写清测试镜像、依赖和连接信息），再初始化");
        }
        DockerProbe.Result docker = docker();
        if (!docker.ready()) {
            // 命令找不到 / daemon 没起：两档分开说，待办也不同（见 DockerProbe）
            return new Status(true, docker, EnvRegistration.State.NOT_READY,
                    registration(EnvRegistration.State.NOT_READY, List.of(), List.of(), false,
                            docker.describe()),
                    0, docker.todo());
        }
        if (activeComposeFile() == null) {
            // 没初始化过，但可能还留着上一轮的东西——那也要报出来，而且要先收掉再初始化
            EnvRegistration found = leftovers();
            return new Status(true, docker, EnvRegistration.State.NOT_READY, found, found.size(),
                    found.size() == 0
                            ? "环境还没初始化：点「初始化测试环境」起一次，"
                                    + "之后每次跑测试只会重置数据、不重建容器"
                            : "环境还没初始化，而且发现了 " + found.size() + " 件上一轮留下的东西："
                                    + "点「清空测试环境」收拾干净再初始化");
        }
        List<String> containers = containers();
        if (containers.isEmpty()) {
            EnvRegistration found = leftovers();
            return new Status(true, docker, EnvRegistration.State.DOWN, found, found.size(),
                    "环境关掉了（compose 文件还在 " + relative(activeComposeFile())
                            + "）：点「初始化测试环境」重新起一次");
        }
        EnvRegistration now = registration(EnvRegistration.State.READY, containers, List.of(), false,
                "环境活着（" + containers.size() + " 个容器），容器留着复用");
        return new Status(true, docker, EnvRegistration.State.READY, now, 0, "");
    }

    // ---------- 起 ----------

    /**
     * 起环境：写 compose → {@code up -d --wait} → 跑 {@code init}。
     *
     * <p><b>失败就当场把东西收掉</b>：起了一半的环境（网络建了、容器起了一个）
     * 比干净地没起来糟得多——下一轮 {@code up} 会去复用那些半成品，而没人知道它们是半成品。
     * 所以每一条失败路径都先 {@code down -v}，再抛 {@link EnvProblem}。
     *
     * <p>{@code init} 只在<b>这一次</b>跑：它是「起完跑一次」的东西（装依赖、建表）。
     * 每次跑测试之前要重来的是 {@code reset}。
     */
    public EnvRegistration up() {
        EnvConfig declared = requireDeclared();
        requireDocker();
        // 闸门放在最前面：一条高危命令要在写文件、起容器之前就被拒——
        // 起完再拒，用户已经等了几分钟，而且磁盘上多了一份没人管的 compose
        forbid(ComposeFile.render(declared, projectRoot), "生成的 compose 内容");
        forbidCommands(declared);

        Path file = ComposeFile.write(projectRoot, ComposeFile.newRunId(), declared);
        // 旧的那一份 compose 文件不再是指挥部了，新的这一份才是。留着旧目录只会让
        // 「打开项目收残局」分不清哪份是活的
        cleanOldDirectories(file.getParent());
        List<String> upCommand = compose(file, "up", "-d", "--wait");
        CommandRunner.Result up = run(upCommand, UP_TIMEOUT_SECONDS);
        if (!up.ok()) {
            // 起不来 → 把已经建出来的东西收掉，别留半成品（见方法注释）
            downQuietly(file);
            throw new EnvProblem(EnvProblem.START, commandLine(upCommand), raw(up),
                    startTodo(up));
        }

        List<String> ran = new ArrayList<>();
        for (String command : declared.init()) {
            ran.add(command);
            CommandRunner.Result result = run(execCommand(file, command), EXEC_TIMEOUT_SECONDS);
            if (!result.ok()) {
                // init 失败 = 这套环境不可用，同样收干净（卷一起删：init 写进去的可能是半截数据）
                downQuietly(file);
                throw new EnvProblem(EnvProblem.INIT, commandLine(execCommand(file, command)),
                        raw(result),
                        "这条命令要在容器里成功跑完，环境才算好了。可以手工进容器调它："
                                + System.lineSeparator() + "    "
                                + commandLine(compose(file, "exec", ComposeFile.APP_SERVICE, "sh")));
            }
        }
        EnvRegistration done = registration(EnvRegistration.State.READY, containers(), ran, true,
                "环境已就绪" + (ran.isEmpty() ? "" : "（跑了 " + ran.size() + " 条初始化命令）"));
        log.info("测试环境已就绪：{}", done.summarize());
        return done;
    }

    /**
     * 起不来的待办：从 docker 的原话里认出<b>是哪一类</b>起不来。
     *
     * <p>为什么值得分：这三类的下一步动作完全不同——镜像拿不到要去改镜像名或查网络，
     * 依赖没等到健康要去改健康检查（或者去查那个服务为什么起不来），超时要去看看它卡在哪。
     * 一律给一句「按原始错误处理」，用户拿着一条 compose 的长输出还是不知道从哪儿下手。
     *
     * <p>判据<b>只认 docker 自己说过的话</b>（unhealthy / pull access denied …），
     * 不做任何推测：认不出来就退回那句「按原始错误处理」，不编。
     */
    private static String startTodo(CommandRunner.Result up) {
        String output = up.output() == null ? "" : up.output().toLowerCase(java.util.Locale.ROOT);
        if (up.timedOut()) {
            return "这一步超时了（" + UP_TIMEOUT_SECONDS / 60 + " 分钟）：多半是镜像太大，"
                    + "或者健康检查一直不过。先手工跑一遍上面那条命令，看它卡在哪一步";
        }
        if (output.contains("unhealthy") || output.contains("dependency failed to start")
                || output.contains("health_status")) {
            // 实测的原话长这样：dependency failed to start: container sf-x-broken-1 is unhealthy
            return "有依赖没等到健康就超时了（上面那条命令里点着它的名字）。"
                    + "去查它 healthcheck 里那条命令：进容器手工跑一遍，看它报什么；"
                    + "或者那个服务本来就起得慢——那就把 healthcheck 的 retries / start-period 调大";
        }
        if (output.contains("pull access denied") || output.contains("manifest unknown")
                || output.contains("no such image") || output.contains("failed to resolve")
                || output.contains("not found") || output.contains("denied")) {
            return "镜像拿不到（上面那条原始错误里点着是哪一个）。"
                    + "检查 env.yaml 里的 image 名字和标签写对没有，以及这台机器连不连得上镜像仓库";
        }
        return "按上面的原始错误处理（镜像名写错、拉不到、端口或资源冲突都会在这一步暴露），"
                + "改完 " + EnvConfigLoader.relativePath() + " 再点一次初始化";
    }

    // ---------- 每次跑前重置 ----------

    /**
     * 跑 {@code reset}：把数据恢复到一个已知状态。
     *
     * <p><b>它不能省。</b>上一轮测试写进去的数据会留在库里，而下一轮的断言是照着
     * 「干净的数据」写的——不重置的话，同一份代码会一会儿过一会儿不过，
     * 而那看起来像被测代码不稳定，不像环境脏了。
     *
     * <p>失败<b>不</b>收环境：容器是好的，坏的只是那条命令（写错了、或者要的表不存在）。
     * 把容器删掉只会让用户多等一次镜像拉取，而问题还在那儿。
     */
    public EnvRegistration reset() {
        EnvConfig declared = requireDeclared();
        requireDocker();
        Path file = requireComposeFile();
        // 闸门也在这里判一遍：init 走过的那一道，reset 自己走一遍才算数。
        // 少了这一句，`specflow env reset`（以及收场时的「清环境数据」）就成了一条
        // 不设防的执行入口——而闸门的意义正是「交给引擎执行的命令必须过一遍」
        forbidCommands(declared);
        List<String> ran = new ArrayList<>();
        for (String command : declared.reset()) {
            ran.add(command);
            CommandRunner.Result result = run(execCommand(file, command), EXEC_TIMEOUT_SECONDS);
            if (!result.ok()) {
                throw new EnvProblem(EnvProblem.RESET, commandLine(execCommand(file, command)),
                        raw(result),
                        "先手工跑一遍上面那条命令，看它报什么；改好 "
                                + EnvConfigLoader.relativePath() + " 里的 reset 再来。"
                                + "环境本身没删：容器和卷都还留着，只是数据没重置成功");
            }
        }
        EnvRegistration done = registration(EnvRegistration.State.READY, containers(), ran, true,
                ran.isEmpty() ? "没有配 reset：这一轮直接用上一轮留下的数据"
                        : "已重置数据（" + ran.size() + " 条命令）");
        log.info("测试环境重置完成：{}", done.detail());
        return done;
    }

    // ---------- 收 ----------

    /**
     * 关掉环境，连卷一起（{@code down -v}）。
     *
     * <p>只在三种时候做：环境坏了、关项目、用户手动点「清空测试环境」（十五.5）。
     * 平时容器留着复用——每次跑完都重建容器，会让「跑一次测试」变成「等一次镜像启动」。
     *
     * @param volumes 要不要连卷一起删。默认口径是要：留着卷，下一轮的「干净数据」就是个假象
     */
    public EnvRegistration down(boolean volumes) {
        requireDocker();
        Path file = activeComposeFile();
        List<String> ran = new ArrayList<>();
        if (file != null) {
            List<String> command = compose(file, volumes
                    ? new String[]{"down", "-v", "--remove-orphans"}
                    : new String[]{"down", "--remove-orphans"});
            ran.add(commandLine(command));
            CommandRunner.Result result = run(command, DOWN_TIMEOUT_SECONDS);
            if (!result.ok()) {
                // 关不掉是<b>要报出来的</b>：用户点了「清空测试环境」，却什么都没被告知，
                // 他会以为环境真被清掉了——而它还在，下一轮还会去复用那些东西
                throw new EnvProblem(EnvProblem.DOWN, commandLine(command), raw(result),
                        "先按上面的原始错误处理（容器正被别的进程占用、docker 恰好不可用都会这样），"
                                + "也可以手工跑一遍上面那条命令");
            }
            deleteDirectory(file.getParent());
        }
        EnvRegistration left = leftovers();
        EnvRegistration done = new EnvRegistration(EnvRegistration.State.DOWN,
                commandLine(probeCommand()), composeProject(), "", services(), List.of(), List.of(),
                List.of(), directoryStrings(), ran,
                left.size() == 0 ? "环境已关闭" : "环境已关闭，但还剩 " + left.size() + " 件残留");
        log.info("测试环境已关闭：{}", done.summarize());
        return done;
    }

    /**
     * 打开项目时收残局（十五.8 的第三件，扩展 §11 已有的清理机制）。
     *
     * <p>什么时候会有残局：进程被杀在 {@code up} 中间、上次收尾时 docker 恰好不可用、
     * 用户手工删了 {@code .specflow/env} 目录。这些东西的特点是「<b>没人管但它们还在</b>」——
     * 攒着占磁盘、占端口，还会让下一次 {@code up} 去复用一个没人知道来历的容器。
     *
     * <p>判据只有一条：<b>没有 compose 文件的东西，就是残局</b>。
     * 有 compose 文件 = 这套环境有人管（要么活着、要么能干净地关掉），那就不是残留。
     * 所以「打开项目」不会拆掉一个正用得好好的环境——那正是「容器常驻复用」要的。
     *
     * <p>返回清理的登记，交给调用方去告警（见 {@link #LEFTOVER_WARN_THRESHOLD}）。
     */
    public EnvRegistration cleanupLeftovers() {
        if (!declared() || !docker().ready()) {
            // 没声明、或者 docker 用不了：没有可收的东西，也不该为此拦着项目打开
            return registration(EnvRegistration.State.NOT_DECLARED, List.of(), List.of(), false,
                    "docker 不可用或没声明环境，没收残局");
        }
        Path file = activeComposeFile();
        if (file != null) {
            // 有把手：这套环境是活的（或者能被 down 干净），不算残留。
            // 顺手把更早的那几份 compose 目录收掉——同一个项目名，容器是同一批
            cleanOldDirectories(file.getParent());
            return registration(EnvRegistration.State.READY, containers(), List.of(), false,
                    "这套环境有人管，没动它");
        }
        EnvRegistration found = leftovers();
        if (found.size() == 0) {
            return found;
        }
        log.warn("打开项目时发现 {} 件残留，开始清理：{}", found.size(), found.summarize());
        return removeLeftovers(found);
    }

    /**
     * 现在属于这个项目、但<b>没人管</b>的东西。
     *
     * <p>见 {@link #cleanupLeftovers()} 里那条判据：有 compose 文件就不是残留。
     * 所以这个方法在环境活着时返回空——问它「有没有残留」和问「要不要清理」是同一件事。
     */
    public EnvRegistration leftovers() {
        if (!docker().ready() || activeComposeFile() != null) {
            return registration(EnvRegistration.State.READY, List.of(), List.of(), false, "没有残留");
        }
        List<String> containers = containers();
        List<String> volumes = volumesOf(containers);
        List<String> networks = networks();
        // 这一档记 DOWN：环境本身没在跑（有人管的那份 compose 文件不在），
        // 而这些是它上次没收拾干净的余党。件数由 size() 报出来，界面据此告警
        return new EnvRegistration(EnvRegistration.State.DOWN, commandLine(probeCommand()),
                composeProject(), "", services(), containers, networks, volumes,
                directoryStrings(), List.of(),
                containers.size() + volumes.size() + networks.size() == 0
                        ? "没有残留"
                        : "上一轮留下的东西：容器 " + containers.size() + "、卷 " + volumes.size()
                                + "、网络 " + networks.size());
    }

    /**
     * 按登记清干净（十五.8 的第二件）。
     *
     * <p>顺序是 <b>容器 → 卷 → 网络</b>，正好和创建顺序相反：
     * 卷还挂在一个活着的容器上时删不掉，网络上有容器连着时也删不掉。
     * 反过来做，删不掉的会被留在那儿，而报出来的话是「清理失败」——看不出其实是顺序错了。
     *
     * <p>收不掉的东西如实报出来，不假装收干净了（十五.9：清理做不到 100%）。
     */
    private EnvRegistration removeLeftovers(EnvRegistration found) {
        List<String> removed = new ArrayList<>();
        remove("rm", List.of("-f"), found.containers(), removed);
        remove("volume", List.of("rm"), found.volumes(), removed);
        remove("network", List.of("rm"), found.networks(), removed);
        for (String directory : found.directories()) {
            deleteDirectory(projectRoot.resolve(directory));
            removed.add(directory);
        }
        EnvRegistration after = leftovers();
        String detail = after.size() == 0
                ? "收掉了上一轮留下的 " + removed.size() + " 件东西"
                : "收掉了 " + removed.size() + " 件，还剩 " + after.size()
                        + " 件没收掉（多半是容器还在跑：先手工停掉它）";
        return new EnvRegistration(after.size() == 0
                ? EnvRegistration.State.DOWN : EnvRegistration.State.BROKEN,
                commandLine(probeCommand()), composeProject(), "", services(), after.containers(),
                after.networks(), after.volumes(), List.of(), List.of(), detail);
    }

    /** 按名字删一类资源；成不成看 docker 说什么。 */
    private void remove(String group, List<String> verb, List<String> names, List<String> removed) {
        if (names.isEmpty()) {
            return;
        }
        List<String> command = new ArrayList<>(probeCommand());
        command.add(group);
        command.addAll(verb);
        command.addAll(names);
        CommandRunner.Result result = run(command, QUERY_TIMEOUT_SECONDS);
        if (result.ok()) {
            removed.addAll(names);
        } else {
            log.warn("删不掉（{}）：{}", commandLine(command), raw(result));
        }
    }

    // ---------- 给测试脚本的连接信息 ----------

    /**
     * 给测试脚本的那一组环境变量：用户的 {@code env:} <b>原样</b>，加上引擎自己那三个。
     *
     * <p>为什么原样给：这条链上最贵的一种错是「测试代码把连接串写死了」——
     * 换一台机器、换一个端口就全错，而错的方式看起来像功能坏了。把连接信息
     * <b>作为环境变量送进模型上下文</b>（见 {@code TestProtocol}），它就没有理由去猜；
     * 协议里再把「必须读环境变量」写成硬规则。
     *
     * <p>引擎那三个变量是给脚本用的把手：真正要验的东西要进容器里跑
     * （服务名只在容器网络里解析得到），而「怎么进容器」只有引擎知道——
     * compose 项目名和文件路径都是引擎生成的。让脚本自己去猜，它必然猜错。
     */
    public Map<String, String> variables() {
        EnvConfig declared = config().orElseThrow();
        Map<String, String> all = new LinkedHashMap<>(declared.variables());
        Path file = activeComposeFile();
        all.put(COMPOSE_PROJECT_VAR, composeProject());
        all.put(COMPOSE_FILE_VAR, file == null ? "" : file.toString());
        all.put(WORKDIR_VAR, declared.workdir());
        return all;
    }

    /** 现在这一份 compose 文件（新的那份）；没初始化过返回 {@code null}。 */
    public Path activeComposeFile() {
        List<Path> dirs = environmentDirectories();
        if (dirs.isEmpty()) {
            return null;
        }
        // 目录名是时间戳，排序就是先后。取最新的一份：重新初始化之后，旧的那份不是指挥部了
        Path file = dirs.get(dirs.size() - 1).resolve(ComposeFile.NAME);
        return Files.isRegularFile(file) ? file : null;
    }

    // ---------- 内部 ----------

    private EnvConfig requireDeclared() {
        return config().orElseThrow(() -> new EnvProblem(EnvProblem.CHECK, "",
                "项目里没有 " + EnvConfigLoader.relativePath(),
                "要跑集成测试就先写这份环境声明（测试镜像、依赖、连接信息），再初始化"));
    }

    private void requireDocker() {
        DockerProbe.Result docker = docker();
        if (!docker.ready()) {
            throw new EnvProblem(EnvProblem.CHECK, commandLine(docker.command()),
                    docker.state() == DockerProbe.State.COMMAND_NOT_FOUND
                            ? "试过这些命令：" + docker.output() : docker.output(),
                    docker.todo());
        }
    }

    private Path requireComposeFile() {
        Path file = activeComposeFile();
        if (file == null) {
            throw new EnvProblem(EnvProblem.CHECK, "", "没有找到生成出来的 compose 文件",
                    "环境还没初始化：先初始化一次，之后的每一步都从这份文件出发");
        }
        return file;
    }

    /** {@code .specflow/env} 下每一个 run 的目录，按名字（= 时间戳）升序。 */
    private List<Path> environmentDirectories() {
        Path root = ComposeFile.rootOf(projectRoot);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("读环境目录失败 {}：{}", root, e.getMessage());
            return List.of();
        }
    }

    private List<String> directoryStrings() {
        return environmentDirectories().stream().map(this::relative).toList();
    }

    /** 除了「留着的那一份」之外，把更早的 compose 目录收掉。 */
    private void cleanOldDirectories(Path keep) {
        for (Path dir : environmentDirectories()) {
            if (!dir.equals(keep)) {
                deleteDirectory(dir);
            }
        }
    }

    private List<String> compose(Path file, String... args) {
        List<String> command = new ArrayList<>(probeCommand());
        command.add("compose");
        command.add("-p");
        command.add(composeProject());
        command.add("-f");
        command.add(file.toString());
        command.addAll(List.of(args));
        return command;
    }

    /** 在常驻容器里跑一条命令。{@code -T} 必须有：引擎这边没有终端，不给它就会报「not a TTY」。 */
    private List<String> execCommand(Path file, String command) {
        return compose(file, "exec", "-T", ComposeFile.APP_SERVICE, "sh", "-c", command);
    }

    /** 属于这个项目的容器名（包含已经退出的：退出的那些正是要收的残留）。 */
    private List<String> containers() {
        List<String> tail = new ArrayList<>(List.of("ps", "-a",
                "--filter", "label=com.docker.compose.project=" + composeProject()));
        Path file = activeComposeFile();
        if (file != null) {
            // 有把手时再加一道：只要**这个目录**起出来的容器。
            // 判据写成 docker 的 --filter 而不是 --format 模板里的字符串比较，是因为
            // ProcessBuilder 传不了参数里的双引号（实测：docker 会因为引号被吃掉而把模板里的
            // 字符串当成函数名，报 `function "com" not defined`）；而 filter 的值不是模板，
            // 没有引号，照样能精确匹配。
            tail.add("--filter");
            tail.add("label=com.docker.compose.project.working_dir=" + file.getParent());
        }
        tail.add("--format");
        tail.add("{{.Names}}");
        return list(tail);
    }

    private List<String> volumes() {
        return list(List.of("volume", "ls",
                "--filter", "label=com.docker.compose.project=" + composeProject(),
                "--format", "{{.Name}}"));
    }

    /**
     * 这个项目名下的卷：标签查得到的，加上<b>容器身上挂着的</b>。
     *
     * <p>为什么两个来源都要：镜像自带的 {@code VOLUME}（redis 的 {@code /data} 就是）
     * 会让 compose 建一个<b>匿名卷</b>，而那种卷<b>没有</b> compose 的项目标签
     * （实测：标签只有 {@code com.docker.volume.anonymous:}）——只看标签的话，
     * 它既进不了登记，也永远不会被残留清理收掉，一轮一轮地攒在机器上
     * （这条是真的漏掉过：清完之后机器上多了两个匿名卷）。
     * 而「哪个容器挂着它」是比名字更硬的归属证据：容器已经确认是我们的了。
     *
     * <p>{@code down -v} 那条路不受影响：compose 自己知道匿名卷属于哪个容器，会一起删掉
     * （实测也确认了）。这一条补的是「没有 compose 文件」时的按名字清理。
     */
    private List<String> volumesOf(List<String> containers) {
        List<String> all = new ArrayList<>(volumes());
        for (String mounted : mountedVolumes(containers)) {
            if (!all.contains(mounted)) {
                all.add(mounted);
            }
        }
        return List.copyOf(all);
    }

    /**
     * 问容器：你们身上挂了哪些卷。
     *
     * <p>用 {@code {{json .Mounts}}} 而不是模板里的 {@code {{if eq .Type "volume"}}}：
     * 后者带双引号，而 ProcessBuilder 在 Windows 上<b>传不了参数里的双引号</b>
     * （实测报 {@code function "volume" not defined}）。JSON 里没有引号，解析交给 Jackson。
     */
    private List<String> mountedVolumes(List<String> containers) {
        if (containers.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>(probeCommand());
        command.add("inspect");
        command.add("--format");
        command.add("{{json .Mounts}}");
        command.addAll(containers);
        CommandRunner.Result result = run(command, QUERY_TIMEOUT_SECONDS);
        if (!result.ok()) {
            log.debug("问不出来容器挂了哪些卷：{}", result.firstLine());
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String line : result.output().split("\\R")) {
            found.addAll(volumeNamesIn(line.strip()));
        }
        return List.copyOf(found);
    }

    /**
     * 一段 {@code {{json .Mounts}}} 输出里的卷名。
     *
     * <p>认不出来就当没有：它只用来<b>多认几件自己的东西</b>，
     * 认不出来时退回「标签 + 名字」那两道，不会因此删错。
     */
    static List<String> volumeNamesIn(String json) {
        if (json == null || !json.startsWith("[")) {
            return List.of();
        }
        try {
            JsonNode mounts = JSON.readTree(json);
            List<String> names = new ArrayList<>();
            for (JsonNode mount : mounts) {
                if ("volume".equals(mount.path("Type").asText())) {
                    String name = mount.path("Name").asText("");
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
            return List.copyOf(names);
        } catch (IOException e) {
            log.debug("读不懂容器的挂载信息：{}", e.getMessage());
            return List.of();
        }
    }

    private List<String> networks() {
        return list(List.of("network", "ls",
                "--filter", "label=com.docker.compose.project=" + composeProject(),
                "--format", "{{.Name}}"));
    }

    /**
     * 跑一条列表命令，只留下<b>确定是自己项目</b>的那些。
     *
     * <p>两道核对，缺一不可：
     * <ol>
     *   <li>docker 按 compose 打的标签过滤（{@code com.docker.compose.project}，
     *       有 compose 文件时再加一道 {@code ...project.working_dir}）；</li>
     *   <li><b>名字也像这个项目</b>——{@code sf-<项目>} 前缀。</li>
     * </ol>
     * 后一道不是装饰：项目名里带着从项目绝对路径算出来的短哈希，所以「名字对得上」
     * 就等于「是同一个目录」，而这是唯一能删错东西的地方（见 {@link ComposeFile#projectName}）。
     *
     * <p>查询失败一律当「没有残留」：把「查不出来」当成「有一堆残留」，
     * 下一步就是照着猜出来的名字去删东西。
     */
    private List<String> list(List<String> tail) {
        List<String> command = new ArrayList<>(probeCommand());
        command.addAll(tail);
        CommandRunner.Result result = run(command, QUERY_TIMEOUT_SECONDS);
        if (!result.ok()) {
            log.debug("列举资源失败（{}）：{}", commandLine(command), result.firstLine());
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String line : result.output().split("\\R")) {
            String name = line.strip();
            if (!name.isEmpty() && ours(name, composeProject())) {
                found.add(name);
            }
        }
        return List.copyOf(found);
    }

    /** 这个名字像不像这个项目的东西。compose v2 里容器/网络用 {@code -} 分隔，卷用 {@code _}。 */
    static boolean ours(String name, String project) {
        return name.equals(project)
                || name.startsWith(project + "-")
                || name.startsWith(project + "_");
    }

    /** compose 里的服务名：app 加上各个依赖。 */
    private List<String> services() {
        List<String> all = new ArrayList<>();
        all.add(ComposeFile.APP_SERVICE);
        config().ifPresent(declared -> all.addAll(declared.dependencies().keySet()));
        return List.copyOf(all);
    }

    /** 这次用的 docker 命令；没探过（或没找到）就退回 {@code docker}。 */
    private List<String> probeCommand() {
        List<String> command = probe == null ? List.of() : probe.command();
        return command.isEmpty() ? List.of("docker") : command;
    }

    /** 登记：给界面和运行留档的那一份。 */
    private EnvRegistration registration(EnvRegistration.State state, List<String> containers,
                                         List<String> ran, String detail) {
        return registration(state, containers, ran, true, detail);
    }

    private EnvRegistration registration(EnvRegistration.State state, List<String> containers,
                                         List<String> ran, boolean query, String detail) {
        Path file = activeComposeFile();
        return new EnvRegistration(state, commandLine(probeCommand()), composeProject(),
                file == null ? "" : relative(file), services(), containers,
                query ? networks() : List.of(), query ? volumesOf(containers) : List.of(),
                directoryStrings(), ran, detail);
    }

    private void downQuietly(Path file) {
        List<String> command = compose(file, "down", "-v", "--remove-orphans");
        CommandRunner.Result result = run(command, DOWN_TIMEOUT_SECONDS);
        if (!result.ok()) {
            log.warn("收掉起了一半的环境时失败（{}）：{}", commandLine(command), raw(result));
        }
        deleteDirectory(file.getParent());
    }

    private void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // 删不掉只是脏，不是错：目录在项目里看得见，用户也能自己删
            log.warn("清理环境目录失败 {}：{}", dir, e.getMessage());
        }
    }

    /**
     * 高危写法这一道闸（复用第一批那道，见 {@link TestArtifacts#forbidden}）。
     *
     * <p>为什么在这里再判一次：{@code env.yaml} 里的命令最终由引擎<b>执行</b>，而它是用户写的
     * 文件——「用户自己写的就由他自己负责」在这套工具里不成立，因为命令是在
     * <b>挂着项目目录的容器</b>里跑的，一条 {@code rm -rf} 足够把源码删光。
     * 真正的结构性兜底是容器隔离（十五.9），但隔离不是「不用看」的理由。
     */
    private void forbid(String content, String what) {
        String evidence = TestArtifacts.forbidden(content);
        if (evidence != null) {
            throw new EnvProblem(EnvProblem.CHECK, evidence,
                    what + "里有高危写法：「" + evidence + "」",
                    "测试环境只该跑测试：提权、挂宿主目录、删源码这类写法一律不接受。改 "
                            + EnvConfigLoader.relativePath() + " 里那一行再来");
        }
    }

    /**
     * {@code init} / {@code reset} 里那些命令过闸。
     *
     * <p>它们和 compose 内容用的是<b>同一张高危表</b>，但「删东西」那一条按<b>容器内</b>的
     * 命名空间判（见 {@link TestArtifacts#forbiddenInContainer}）：清库那种正当命令
     * （{@code rm -rf /data/*}——MySQL/Redis 容器的数据目录）不能再被误拒，
     * 而挂载进来的项目目录一个字都不放宽。
     *
     * <p>{@link #up} 与 {@link #reset} <b>都要</b>调它：只在起环境时判，
     * {@code specflow env reset} 和收场时的「清环境数据」就成了一条绕开闸门的路。
     */
    private void forbidCommands(EnvConfig declared) {
        declared.init().forEach(command ->
                forbidInContainer(command, "init 命令", declared.workdir()));
        declared.reset().forEach(command ->
                forbidInContainer(command, "reset 命令", declared.workdir()));
    }

    private void forbidInContainer(String content, String what, String workdir) {
        String evidence = TestArtifacts.forbiddenInContainer(content, workdir);
        if (evidence != null) {
            throw new EnvProblem(EnvProblem.CHECK, evidence,
                    what + "里有高危写法：「" + evidence + "」",
                    "测试环境只该跑测试：提权、写块设备、挂宿主目录、删项目里的源码这类写法"
                            + "一律不接受。改 " + EnvConfigLoader.relativePath() + " 里那一行再来");
        }
    }

    private CommandRunner.Result run(List<String> command, long timeoutSeconds) {
        log.debug("跑：{}", commandLine(command));
        return runner.run(command, Map.of(), projectRoot, timeoutSeconds);
    }

    private String relative(Path file) {
        return projectRoot.relativize(file).toString().replace('\\', '/');
    }

    private static String raw(CommandRunner.Result result) {
        if (result.timedOut()) {
            return result.output();
        }
        return result.output().isBlank() ? "退出码 " + result.exit() : result.output();
    }

    private static String commandLine(List<String> command) {
        return String.join(" ", command);
    }
}
