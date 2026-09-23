package com.specflow.web;

import com.specflow.project.ProjectConfig;
import com.specflow.project.ProjectConfigLoader;
import com.specflow.task.TaskStore;
import com.specflow.template.TemplateStore;

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

    private final Path root;
    private final ProjectConfig config;
    private final TemplateStore templates;
    private final WorkspaceApi workspace;
    private final ProjectIndex index;
    private final RunService runs;

    /**
     * 已经被换下去了。
     *
     * <p>换项目就是「把新的装上去、把旧的收掉」，收掉之后旧的那个上面不该再落下任何写入——
     * 而请求体是慢慢发过来的，读它可能耗上几秒，这期间用户完全可能已经换了项目。
     * 那时候还把模板、草稿写进去，界面上显示的是「保存成功」，东西却在用户已经不看的那一侧。
     */
    private volatile boolean closed;

    private OpenProject(Path root, ProjectConfig config, TemplateStore templates,
                        ProjectIndex index, RunService runs) {
        this.root = root;
        this.config = config;
        this.templates = templates;
        this.workspace = new WorkspaceApi(templates, new TaskStore(root), this::requireOpen);
        this.index = index;
        this.runs = runs;
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
        TemplateStore templates = new TemplateStore(normalized.resolve(TemplateStore.DEFAULT_DIR));
        return new OpenProject(normalized, config, templates,
                new ProjectIndex(normalized),
                new RunService(normalized, config, templates.directory()));
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

    /** 有没有任务正在跑。换项目之前要问一句——跑到一半换掉会把进度和文件去哪都搞乱。 */
    public boolean isRunning() {
        return runs.hub().running();
    }

    @Override
    public void close() {
        closed = true;
        runs.shutdown();
    }
}
