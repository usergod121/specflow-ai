package com.specflow.web;

import com.specflow.env.EnvRegistration;
import com.specflow.env.TestEnvironment;
import com.specflow.project.ContextLibrary;
import com.specflow.project.ProjectConfig;
import com.specflow.project.ProjectConfigLoader;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.task.TaskStore;
import com.specflow.template.TemplateStore;
import com.specflow.util.SafePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 一个「已打开的项目」。
 *
 * <p>服务一个项目需要好几样东西：模板、任务草稿、文件索引、运行记录、项目配置。
 * 它们原先是散在 {@link WebServer} 的字段里的，于是「换项目」就变成了
 * 「记得把每一个都重建一遍，别漏、别错顺序」——漏一个，界面上就会出现
 * 「文件树是 A 项目的、模板还是 B 项目的」这种四不像。
 *
 * <p>收进这一个对象之后，换项目就是换一个 {@code OpenProject}：一次装配，一次替换。
 *
 * <p>实现 {@link AutoCloseable} 是因为里面的 {@link RunService} 带一个运行线程池。
 * 旧项目被换下去时如果不收掉，那个线程会一直挂着，而它监听的进度缓冲区也没人再看了。
 */
public final class OpenProject implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenProject.class);

    private final Path root;
    private final ProjectConfig config;
    private final TemplateStore templates;
    private final WorkspaceApi workspace;
    private final ProjectIndex index;
    private final RunService runs;
    private final TestEnvironment environment;

    /**
     * 打开这个项目时收掉的残局（十五.8 的第三件）。
     *
     * <p>为什么要留着它：收残局是<b>在背后发生</b>的（用户没点任何东西），
     * 而它动的是 docker 里的东西。不说一句，用户下次看到容器没了会以为是工具出了错；
     * 说一句「收掉了上一轮留下的 2 件东西」，这件事就有了交代。
     */
    private final EnvRegistration cleanup;

    /**
     * 已经被换下去了。
     *
     * <p>换项目就是「把新的装上去、把旧的收掉」，收掉之后旧的那个上面不该再落下任何写入——
     * 而请求体是慢慢发过来的，读它可能耗上几秒，这期间用户完全可能已经换了项目。
     * 那时候还把模板、草稿写进去，界面上显示的是「保存成功」，东西却在用户已经不看的那一侧。
     */
    private volatile boolean closed;

    private OpenProject(Path root, ProjectConfig config, TemplateStore templates,
                        ProjectIndex index, RunService runs, TestEnvironment environment,
                        EnvRegistration cleanup) {
        this.root = root;
        this.config = config;
        this.templates = templates;
        this.workspace = new WorkspaceApi(templates, new TaskStore(root),
                new ContextLibrary(root), this::requireOpen);
        this.index = index;
        this.runs = runs;
        this.environment = environment;
        this.cleanup = cleanup;
    }

    /**
     * 装配一个项目。
     *
     * <p>项目配置在这里现读：换项目它也得跟着换，因为编译命令和模型地址都是项目级的。
     * 读不到就用默认值——{@code .specflow/project.yaml} 不是必需品，没有也能用，
     * 只是不做编译校验、也没有针对这个项目的模板。
     */
    public static OpenProject open(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        ProjectConfig config = new ProjectConfigLoader().load(normalized);
        SafePathResolver resolver = new SafePathResolver(normalized);
        // 打开项目时先把上一次留下的残局收一下：没写完的快照目录、写了一半的临时文件。
        // 放在这里而不是每次运行前：这是「进这个项目」的唯一入口，收一次就够
        WorkspaceSnapshot.cleanUp(resolver, resolver.resolve(config.snapshot().dir()));
        TemplateStore templates = new TemplateStore(normalized.resolve(TemplateStore.DEFAULT_DIR));
        // 测试环境的残局（上一轮没清掉的容器/网络/卷）也在这里收，走的是同一个「进项目」的时机：
        // §11 那套清理管的是磁盘上的残留，这一套管的是容器里的，两者都不该等到运行时才发现
        TestEnvironment environment = TestEnvironment.of(normalized);
        EnvRegistration cleanup = cleanupEnvironment(environment);
        return new OpenProject(normalized, config, templates,
                new ProjectIndex(normalized),
                new RunService(normalized, config, templates.directory(), environment),
                environment, cleanup);
    }

    /**
     * 收测试环境的残局；出任何问题都只记一条警告。
     *
     * <p>为什么不让异常冒出去：这是「打开项目」这条路上顺手做的事，而它依赖 docker 这个
     * 外部东西（可能没装、可能是 daemon 没起、也可能刚好在重启）。让它把项目打开搞失败，
     * 等于「Docker 出问题就打不开项目」——用户会以为整个工具坏了。
     */
    private static EnvRegistration cleanupEnvironment(TestEnvironment environment) {
        try {
            // 没声明环境时<b>一次进程都不起</b>：没有 env.yaml 的项目（绝大多数）不该为这个
            // 功能付出任何代价，连探测 docker 的那一次都不要
            if (!environment.declared()) {
                return null;
            }
            return environment.cleanupLeftovers();
        } catch (RuntimeException e) {
            log.warn("收测试环境残局失败（不影响打开项目）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 这个项目还是「当前那个」吗；不是就当场拒绝。
     *
     * <p>凡是「先读一段可能很慢的输入、再动手」的地方都要先问这一句。
     * 判据只有一条：被换下去的那个已经收掉了（{@link #close()}）。
     */
    public void requireOpen() {
        if (closed) {
            throw new IllegalStateException("项目已经切换，这次操作没有生效，请重试");
        }
    }

    public Path root() {
        return root;
    }

    public ProjectConfig config() {
        return config;
    }

    public TemplateStore templates() {
        return templates;
    }

    public WorkspaceApi workspace() {
        return workspace;
    }

    public ProjectIndex index() {
        return index;
    }

    public RunService runs() {
        return runs;
    }

    /** 打开项目时收掉的残局；没收到东西（或没声明环境）时是 {@code null}。 */
    public EnvRegistration cleanup() {
        return cleanup;
    }

    /** 有没有任务正在跑。换项目之前要问一句——跑到一半换掉会把进度和文件去哪都搞乱。 */
    public boolean isRunning() {
        return runs.hub().running();
    }

    /**
     * 收掉这个项目：停运行线程池，<b>并把测试环境关掉</b>。
     *
     * <p>关项目就 {@code down -v}（十五.5 里那三种时机之一）：留着容器的理由是「下一次还要用」，
     * 而项目都关了，「下一次」就是下一次打开——那时候重新起一次更干净，
     * 也不用担心一个跨天活着的容器里攒了什么。
     *
     * <p>它必须是 best-effort：关项目/关服务这条路上抛异常，会把一次正常的切换
     * 变成一次界面错误，而环境没关掉其实只是脏。
     */
    @Override
    public void close() {
        closed = true;
        closeEnvironment();
        runs.shutdown();
    }

    private void closeEnvironment() {
        try {
            // 没声明、或者一次都没初始化过：一个进程都不起（见 cleanupEnvironment）
            if (environment.declared() && environment.activeComposeFile() != null) {
                environment.down(true);
            }
        } catch (RuntimeException e) {
            log.warn("关项目时收测试环境失败（不影响关闭）：{}", e.getMessage());
        }
    }
}
