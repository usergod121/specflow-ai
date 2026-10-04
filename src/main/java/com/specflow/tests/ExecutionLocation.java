package com.specflow.tests;

import com.specflow.env.ComposeFile;
import com.specflow.env.EnvConfig;
import com.specflow.env.TestEnvironment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 这次测试脚本在<b>哪儿</b>跑：容器里，还是宿主上。
 *
 * <p><b>这条判断只出现在这一处</b>（{@link #of}）。别的类一律照它给的名字、命令和那句话办事——
 * 入口脚本的名字、引擎要执行的那条命令、留档里写的执行位置，三样必须来自同一个判断。
 * 各判各的迟早分岔，而分岔的表现是「脚本明明写了、引擎说没给」或者
 * 「命令跑起来了、跑的环境却不是那个环境」。
 *
 * <p>判据只有一条（十五.5）：<b>Docker 可用，而且这套环境已经初始化、容器还活着</b>
 * → 单元与集成都在容器里跑；其余（没装 Docker、daemon 没起、没初始化、容器已经关了）
 * 一律回退宿主。<b>回退不是免费的</b>：宿主上只剩高危字符串那一道闸（见 {@link TestArtifacts}），
 * 而它挡不住变体（十五.9）。所以回退这件事必须写在结果和留档里（见 {@link #label()}），
 * 不能悄悄发生——用户以为在容器里跑、实际在宿主上跑，是最不该有的一种误会。
 *
 * <p><b>为什么不按语言分支。</b>这里一个语言名都没有：项目用什么语言、容器里有没有那套工具链，
 * 全在用户配的测试镜像里（十五.1「引擎零知识」）。引擎只回答「你的脚本在哪跑」，
 * 并把它说清楚。
 */
public final class ExecutionLocation {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    /** 容器里那一个入口脚本的名字。容器是 Linux，所以永远是 POSIX 那一份。 */
    private static final String CONTAINER_UNIT = "run.sh";
    private static final String CONTAINER_INTEGRATION = "run-it.sh";

    /** 宿主上那一个入口脚本的名字——按本机平台。 */
    private static final String HOST_UNIT = WINDOWS ? "run.cmd" : "run.sh";
    private static final String HOST_INTEGRATION = WINDOWS ? "run-it.cmd" : "run-it.sh";

    /** 回退宿主时写在结果与留档里的那句话。措辞是判据的一部分：它要说清「没用容器」。 */
    private static final String HOST_LABEL = "在宿主执行（未用容器）";

    /** 进容器执行时的那句话。 */
    private static final String CONTAINER_LABEL = "在容器里执行（" + ComposeFile.APP_SERVICE + " 服务）";

    /** 宿主。它没有环境可用时的全部形态，所以做成一个常量。 */
    private static final ExecutionLocation ON_HOST =
            new ExecutionLocation(false, List.of(), "", null, "");

    private final boolean inContainer;
    private final List<String> docker;
    private final String composeProject;
    private final Path composeFile;
    private final String workdir;

    private ExecutionLocation(boolean inContainer, List<String> docker, String composeProject,
                              Path composeFile, String workdir) {
        this.inContainer = inContainer;
        this.docker = List.copyOf(docker);
        this.composeProject = composeProject == null ? "" : composeProject;
        this.composeFile = composeFile;
        this.workdir = workdir == null ? "" : workdir;
    }

    /** 宿主上跑：没有环境、或者环境现在用不了。 */
    public static ExecutionLocation host() {
        return ON_HOST;
    }

    /**
     * <b>唯一的那一处判断</b>：这次该在容器里跑还是回退宿主。
     *
     * <p>为什么问的是「环境现在能不能用」而不是「有没有写过 env.yaml」：写了声明却还没初始化、
     * 或者容器已经关掉时，{@code docker compose exec} 会当场失败——一次本来跑得好好的
     * 单元测试会变成「环境问题」，看起来像代码坏了。那种情况下回退宿主，并在留档里写明，
     * 才是诚实的做法（用户看得见发生了什么）。
     *
     * @param environment 项目的测试环境；{@code null} 表示这个项目没有环境这一说
     */
    public static ExecutionLocation of(TestEnvironment environment) {
        if (environment == null) {
            return ON_HOST;
        }
        TestEnvironment.Status status = environment.status();
        if (!status.usable()) {
            return ON_HOST;
        }
        Path file = environment.activeComposeFile();
        Optional<EnvConfig> declared = environment.config();
        // 三样缺一不可：compose 文件（进哪套环境）、声明里的 workdir（容器里从哪儿跑）、
        // 探测到的那条 docker 命令（用哪一条命令进）。缺任何一样都只能回退宿主
        if (file == null || declared.isEmpty() || status.docker() == null
                || status.docker().command().isEmpty()) {
            return ON_HOST;
        }
        return new ExecutionLocation(true, status.docker().command(),
                environment.composeProject(), file, declared.get().workdir());
    }

    /** 这次在不在容器里跑。 */
    public boolean inContainer() {
        return inContainer;
    }

    /**
     * 执行位置那句话——<b>结果与留档里写的就是它</b>。
     *
     * <p>回退宿主时必须说出来：宿主上那道闸只是字符串匹配，挡不住变体，
     * 用户有权知道这次没有容器兜底。
     */
    public String label() {
        return inContainer ? CONTAINER_LABEL : HOST_LABEL;
    }

    /** 单元入口脚本的文件名。 */
    public String unitEntryName() {
        return inContainer ? CONTAINER_UNIT : HOST_UNIT;
    }

    /** 集成入口脚本的文件名。两个入口同在一个位置跑，所以它也跟着 {@link #inContainer()} 走。 */
    public String integrationEntryName() {
        return inContainer ? CONTAINER_INTEGRATION : HOST_INTEGRATION;
    }

    /**
     * 入口脚本<b>在这个位置上的写法</b>。
     *
     * <p>容器里一律正斜杠：宿主这边拼出来的反斜杠在 Linux 里是文件名的一部分。
     * 宿主上按本机分隔符——它是给人看的，也是给本机 shell 用的。
     *
     * <p>留档里那一栏用的就是这个写法：写着「在容器里执行」却跟一条 Windows 路径，
     * 看的人第一反应是引擎在哪儿把路径搞错了。
     */
    public String path(String entry) {
        return inContainer ? entry.replace('\\', '/') : Path.of(entry).toString();
    }

    /**
     * 引擎要执行的那条命令。
     *
     * <p>宿主上就是「拿本机 shell 跑这个脚本」；容器里是
     * {@code docker compose ... exec -T app sh -c "cd <workdir> && sh <脚本>"}。
     * {@code -T} 必须有：引擎这边没有终端，不给它 docker 会报「not a TTY」。
     *
     * @param entry 入口脚本相对项目根的路径
     */
    public List<String> command(String entry) {
        String shown = path(entry);
        if (!inContainer) {
            return WINDOWS
                    ? List.of("cmd.exe", "/c", shown)
                    : List.of("/bin/sh", "-c", shown);
        }
        return compose("exec", "-T", ComposeFile.APP_SERVICE, "sh", "-c",
                "cd \"" + workdir + "\" && sh \"" + shown + "\"");
    }

    /** 这条命令实际长什么样——失败时原样写给用户看，别让人猜引擎到底跑了什么。 */
    public String commandLine(String entry) {
        return String.join(" ", command(entry));
    }

    /**
     * 超时（或被中断）之后，用来收掉<b>容器里那一半</b>的命令；宿主执行时是空表。
     *
     * <p><b>为什么必须有它。</b>宿主上杀进程树杀不到容器里：脚本是容器里的 PID 1 领起来的，
     * 宿主上只看得见一个 {@code docker compose exec} 客户端。2026-10-04 的真容器实测抓住了这一点
     * ——宿主侧报「已强制终止」之后，容器里的脚本又跑了 25～77 秒才收工，把产物写了个遍。
     *
     * <p><b>为什么是 {@code restart} 而不是 {@code kill}/{@code stop}，也不是进容器里找进程杀：</b>
     * <ul>
     *   <li>进容器杀得靠 {@code pkill}/{@code ps} 这类工具，而镜像是用户随便挑的
     *       （busybox、distroless、scratch……），想找一个「什么镜像都有」的办法是缘木求鱼；</li>
     *   <li>{@code restart} 一个容器内工具都不用，而且<b>文件系统不动</b>：预热过的依赖、
     *       上一步的编译产物都还在，容器回来接着用；</li>
     *   <li>用完就 {@code stop} 会把环境留在 DOWN 上，下一轮会因此<b>悄悄回退到宿主</b>去跑——
     *       那是一次没人知道的降级（见 {@link #of}）。停掉再起来，环境还是活的。</li>
     * </ul>
     *
     * <p>不带 {@code -t}：那个参数各版本支持不一，而默认就是「先礼后兵」。这条只在超时那条路上跑，
     * 多等几秒不值得拿兼容性去换。
     */
    public List<String> stopCommand() {
        return inContainer ? compose("restart", ComposeFile.APP_SERVICE) : List.of();
    }

    private List<String> compose(String... args) {
        List<String> command = new ArrayList<>(docker);
        command.add("compose");
        command.add("-p");
        command.add(composeProject);
        command.add("-f");
        command.add(composeFile.toString());
        command.addAll(List.of(args));
        return command;
    }
}
