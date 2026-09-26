package com.specflow.web;

import com.specflow.exception.SpecValidationException;
import com.specflow.exception.SpecflowException;
import com.specflow.project.ProjectInitializer;
import com.specflow.project.ProjectScanner;
import com.specflow.project.RecentProjects;
import com.specflow.review.ReviewOutcome;
import com.specflow.template.PromptTemplate;
import com.specflow.template.Tags;
import com.specflow.template.TemplateStore;
import com.specflow.util.UserPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 内置的本地 Web 服务。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver}：这套工具到目前为止只有五个依赖，
 * 为了一个只服务本机、只有一张页面的界面去引入 Spring 或 Jetty 并不划算。
 *
 * <p><b>只监听回环地址。</b>这个服务能读写你项目里的文件，
 * 绑到 {@code 0.0.0.0} 就意味着同网段的人都能用它改你的代码。这不是可配置项。
 *
 * <p>这个类只做两件事：<b>路由</b>，以及把异常翻译成状态码。
 * 业务在 {@link RunService} 与 {@link WorkspaceApi} 里，收发在 {@link Http} 里。
 *
 * <p>模板与任务草稿都是<b>每次用到时才从磁盘读</b>，不在内存里缓存。
 * 缓存会让「在界面上改了模板，运行起来还是旧的」变成一个必须靠重启解决的问题，
 * 而重读几个 yaml 文件的代价可以忽略。
 */
public final class WebServer implements AutoCloseable {

    private static final String PAGE_RESOURCE = "/web/index.html";

    /** 界面目录下允许直接访问的文件。白名单而不是扫目录，避免把路径拼出界。 */
    private static final Map<String, String> STATIC_RESOURCES = Map.of(
            "/flowchart.js", "application/javascript; charset=utf-8",
            "/flowchart-demo.html", "text/html; charset=utf-8",
            // 样式自检页：把颜色、间距、各个状态摆一页，改样式之后用它肉眼过一遍
            "/style-demo.html", "text/html; charset=utf-8");

    private final HttpServer server;
    private final ExecutorService httpPool;
    private final ProjectBrowser browser = new ProjectBrowser();
    private final FolderPicking picking = new FolderPicking(new FolderPicker());
    private final RecentProjects recentProjects;

    /**
     * 当前打开的项目。{@code null} 表示还没打开——这时候服务只提供欢迎页和「打开项目」。
     *
     * <p>用 {@code volatile} 是为了换项目时这次替换对处理线程立刻可见。
     * 每个请求进来只读一次（见 {@link #route}），所以不会出现
     * 「文件树用的是新项目、模板还是旧项目」这种读了一半的状态。
     */
    private volatile OpenProject open;

    private WebServer(HttpServer server, ExecutorService httpPool, OpenProject open,
                      RecentProjects recentProjects) {
        this.server = server;
        this.httpPool = httpPool;
        this.open = open;
        this.recentProjects = recentProjects;
    }

    /** 起一个不打开任何项目的服务：进去是欢迎页。 */
    public static WebServer start(int port) {
        return start(null, port);
    }

    /**
     * 起服务。
     *
     * @param projectRoot 启动时就打开的项目；传 {@code null} 表示先进欢迎页
     * @param port        端口；传 0 表示由系统分配（测试用）
     */
    public static WebServer start(Path projectRoot, int port) {
        return start(projectRoot, port, new RecentProjects());
    }

    /**
     * 同 {@link #start(Path, int)}，只是把「最近打开」那份全局状态指到别处。
     *
     * <p>它默认写在用户目录下，测试不能去动真实的那个文件。
     */
    static WebServer start(Path projectRoot, int port, RecentProjects recentProjects) {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            ExecutorService pool = newHttpPool();
            server.setExecutor(pool);

            OpenProject initial = projectRoot == null ? null : OpenProject.open(projectRoot);
            if (initial != null) {
                recentProjects.remember(initial.root());
            }
            WebServer web = new WebServer(server, pool, initial, recentProjects);
            server.createContext("/", web::route);
            server.start();
            return web;
        } catch (IOException e) {
            throw new SpecflowException("无法启动 Web 服务（端口 " + port + "）：" + e.getMessage(), e);
        }
    }

    /**
     * HTTP 线程池。
     *
     * <p>不能是固定 4 个线程：「先检查」是<b>同步等模型</b>的，一次十几秒到几十秒，
     * 四个这样的请求就能把池占满，连 {@code /api/config} 都得排在后面——
     * 界面上看着就是「点了没反应」，测出来能卡 3 秒以上，真模型几十秒更久。
     *
     * <p>所以用「核心 4 + 忙了就临时加」的弹性池：平时只占一两个线程，
     * 等模型的时候才多开。上限 64 只是防病态情况，本机单用户根本到不了。
     */
    private static ExecutorService newHttpPool() {
        return new ThreadPoolExecutor(4, 64, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "specflow-http");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    @Override
    public void close() {
        // 还开着的那个「选择文件夹」窗口要一起收掉：服务没了它还留在桌面上，
        // 而它背后的进程已经没人管了
        picking.abort();
        server.stop(0);
        httpPool.shutdownNow();
        try {
            httpPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 路由 ----------

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            // 先看来源。这个服务没有认证又能改文件，跨站请求必须挡在门外，
            // 否则你浏览任意网页时，那个网页就能让本机的 specflow 去动你的代码。
            if (!LocalOnly.allows(exchange)) {
                Http.sendJson(exchange, 403, Map.of("error",
                        "只接受本机页面的请求（Host / Origin 不是 127.0.0.1 或 localhost）"));
                return;
            }
            if (STATIC_RESOURCES.containsKey(path)) {
                Http.send(exchange, 200, STATIC_RESOURCES.get(path), Http.resource(path));
                return;
            }
            if ("/".equals(path) || "/index.html".equals(path)) {
                Http.send(exchange, 200, "text/html; charset=utf-8", Http.resource(PAGE_RESOURCE));
                return;
            }
            if (handleGlobal(exchange, path)) {
                return;
            }

            // 一次请求只读一次当前项目：换项目换到一半时，不能让这个请求
            // 一半用旧项目一半用新项目
            OpenProject now = open;
            if (now == null) {
                Http.sendJson(exchange, 409, Map.of("error", "还没有打开项目，请先在欢迎页打开一个"));
                return;
            }
            switch (path) {
                case "/api/config" -> Http.sendJson(exchange, 200, config(now));
                case "/api/files" -> Http.sendJson(exchange, 200, filesOf(now));
                case "/api/review" -> review(now, exchange);
                case "/api/run" -> startRun(now, exchange);
                case "/api/events" -> Http.sendJson(exchange, 200, events(now, exchange));
                case "/api/runs" ->
                        Http.sendJson(exchange, 200, Map.of(
                                "runs", now.runs().history().list(),
                                // 「它标的阻断最后真的阻断了吗」那一行汇总要的数据
                                "missingStats", now.runs().history().missingStats()));
                case "/api/cancel" -> cancelRun(now, exchange);
                case "/api/pending" -> Http.sendJson(exchange, 200, now.runs().pending());
                case "/api/accept" -> decidePending(now, exchange, false);
                case "/api/rollback" -> decidePending(now, exchange, true);
                case "/api/run-detail" -> Http.sendJson(exchange, 200,
                        now.runs().history().load(Http.query(exchange, "id", "")));
                case "/api/templates" -> now.workspace().templates(exchange);
                case "/api/template-source" -> now.workspace().templateSource(exchange);
                case "/api/template-parse" -> now.workspace().templateParse(exchange);
                case "/api/tasks" -> now.workspace().tasks(exchange);
                case "/api/task" -> now.workspace().task(exchange);
                case "/api/context" -> now.workspace().contextLibrary(exchange);
                case "/api/init" -> initializeProject(now, exchange);
                default -> Http.sendJson(exchange, 404, Map.of("error", "未知路径 " + path));
            }
        } catch (SpecValidationException e) {
            Http.sendJson(exchange, 400, Map.of("problems", e.problems()));
        } catch (SpecflowException e) {
            Http.sendJson(exchange, 400, Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            Http.sendJson(exchange, 409, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            Http.sendJson(exchange, 500, Map.of("error", "服务端错误：" + e));
        } finally {
            exchange.close();
        }
    }

    /**
     * 处理不需要「已打开项目」的那几个接口。
     *
     * <p>它们正是欢迎页要用的：现在什么状态、翻目录挑一个、打开、关掉。
     *
     * @return 已经处理掉了就返回 {@code true}
     */
    private boolean handleGlobal(HttpExchange exchange, String path) throws IOException {
        switch (path) {
            case "/api/state" -> Http.sendJson(exchange, 200, state());
            case "/api/browse" ->
                    Http.sendJson(exchange, 200, browser.browse(Http.query(exchange, "path", "")));
            case "/api/open" -> openProject(exchange);
            case "/api/pick-folder" -> pickFolder(exchange);
            case "/api/close" -> closeProject(exchange);
            case "/api/recent" -> forgetRecent(exchange);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * 当前状态：有没有打开项目、打开的是哪个、最近打开过哪些。
     *
     * <p>界面一进来就问它，然后决定是进欢迎页还是进主界面。
     */
    private Map<String, Object> state() {
        OpenProject now = open;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("open", now != null);
        state.put("root", now == null ? null : now.root().toString());
        // 挑目录的窗口可能正开着（用户刷了页面、或者换了标签页）：界面据此把等待状态接着显示下去，
        // 而不是给一个可以再点的按钮——再点一次就是第二个窗口
        state.put("picking", describe(picking.current()));
        state.put("recent", recent());
        return state;
    }

    private List<Map<String, Object>> recent() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (RecentProjects.Entry entry : recentProjects.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.path());
            item.put("name", entry.name());
            item.put("lastOpened", entry.lastOpened());
            // 项目可能被删了或者移走了。界面上要看得出来，而不是点了才发现。
            item.put("exists", isDirectory(entry.path()));
            items.add(item);
        }
        return items;
    }

    /**
     * 清单是我们自己写的，但用户可以改它，也可能被别的工具改坏。
     * 一条坏记录只该让那一条显示成「已不在磁盘上」，不该让整个欢迎页打不开——
     * 那样连「移除这条」都点不到，用户只能自己去翻那个 json。
     *
     * <p>空路径要单独挡掉：{@code Path.of("")} 是合法的，而且指的就是进程当前工作目录，
     * 于是那条坏记录会显示成「存在、可点」——点它的「×」还会顺手删掉另一个真实项目。
     */
    private static boolean isDirectory(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            return Files.isDirectory(UserPath.parse(path));
        } catch (SpecflowException e) {
            return false;
        }
    }

    /** 从「最近打开」里去掉一条。项目被删了、移走了，或者你只是不想再看到它。 */
    private void forgetRecent(HttpExchange exchange) throws IOException {
        if (!Http.requireDelete(exchange)) {
            return;
        }
        recentProjects.forget(Http.query(exchange, "path", ""));
        Http.sendJson(exchange, 200, Map.of("recent", recent()));
    }

    /** 打开一个项目：换掉当前那个，并返回这个目录扫出来的项目信息。 */
    private void openProject(HttpExchange exchange) throws IOException {
        Payloads.OpenProject request = Http.readJson(exchange, Payloads.OpenProject.class);
        if (request == null) {
            return;
        }
        if (request.path() == null || request.path().isBlank()) {
            throw new SpecflowException("没有给路径");
        }
        Path root = requireDirectory(UserPath.parse(request.path()));
        Http.sendJson(exchange, 200, switchAndDescribe(root));
    }

    /**
     * 让服务去弹系统的「选择文件夹」窗口，并把「现在到哪一步」告诉界面。
     *
     * <p>这个接口<b>立刻返回</b>，不等用户挑完——挑目录是人在操作，可能几十秒。
     * 把请求挂那么久，中途刷新页面、换个标签页、或者连接被谁掐了，界面就再也说不清
     * 「那个窗口还开着吗」，于是又点一次、又多出一个窗口。状态放在服务端之后，
     * 界面每隔一会儿来问一次就够了。
     *
     * <p>已经在挑了的时候，POST 返回的是<b>当前</b>状态，不会再弹一个窗口——
     * 这是「重复弹窗」那个问题的结构性答案。
     */
    private void pickFolder(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod().toUpperCase()) {
            case "POST" -> Http.sendJson(exchange, 200, describe(picking.start()));
            case "GET" -> Http.sendJson(exchange, 200, describe(picking.current()));
            // 界面处理完结果之后来认领一次，状态回到「没有窗口」。
            // 不认领的话，PICKED 会一直挂着：用户下次切回欢迎页，页面又会拿它去打开那个项目。
            case "DELETE" -> Http.sendJson(exchange, 200, describe(picking.consume()));
            default -> Http.sendJson(exchange, 405,
                    Map.of("error", "该接口只接受 POST / GET / DELETE"));
        }
    }

    private static Map<String, Object> describe(FolderPicking.State state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", state.status().name().toLowerCase(Locale.ROOT));
        if (state.path() != null) {
            body.put("path", state.path());
        }
        if (state.error() != null) {
            body.put("error", state.error());
        }
        return body;
    }

    /** 换项目、记一笔，再把「这是什么项目」告诉界面——打开目录的两条路都用这一份。 */
    private Map<String, Object> switchAndDescribe(Path root) {
        switchTo(root);
        recentProjects.remember(root);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("root", root.toString());
        body.put("scan", new ProjectScanner().scan(root));
        return body;
    }

    private static Path requireDirectory(Path root) {
        if (!Files.isDirectory(root)) {
            throw new SpecflowException("不是一个目录：" + root);
        }
        return root;
    }

    private void closeProject(HttpExchange exchange) throws IOException {
        if (!Http.requirePost(exchange)) {
            return;
        }
        synchronized (this) {
            requireNotRunning(open);
            replace(open, null);
        }
        Http.sendJson(exchange, 200, Map.of("open", false));
    }

    /**
     * 给当前项目铺配置骨架。
     *
     * <p>只对<b>已经打开</b>的项目做——界面上是「打开一个陌生目录，它提示你没配过，
     * 问你要不要初始化」。不在浏览的时候就往人家目录里写东西。
     *
     * <p>整段活都在锁里：写文件只要几毫秒，但「先看一眼再写」之间要是被换项目挤进来，
     * 东西就落到用户已经不看的那一侧了。
     */
    private void initializeProject(OpenProject project, HttpExchange exchange) throws IOException {
        if (!Http.requirePost(exchange)) {
            return;
        }
        ProjectScanner scanner = new ProjectScanner();
        ProjectInitializer.Result result;
        synchronized (this) {
            project.requireOpen();
            requireNotRunning(project);
            result = new ProjectInitializer()
                    .initialize(project.root(), scanner.scan(project.root()).compileCommand());

            // 项目配置是在装配那一刻读的，刚写下去的这份不重读就还是旧的
            reloadCurrent();
        }
        Http.sendJson(exchange, 200, Map.of("written", result.written()));
    }

    /**
     * 换一个当前项目。
     *
     * <p>{@code synchronized} 是必要的：两个请求同时打开时，
     * 后写的那个会把先写的那个对象挤掉，而它的运行线程池就没人收了。
     */
    private synchronized void switchTo(Path root) {
        OpenProject previous = open;
        if (previous != null) {
            requireNotRunning(previous);
            if (previous.root().equals(root)) {
                return;
            }
        }
        replace(previous, OpenProject.open(root));
    }

    /** 重新装配当前项目：它的配置是在装配时读的，改过就要重来一遍。 */
    private synchronized void reloadCurrent() {
        OpenProject previous = open;
        if (previous == null) {
            return;
        }
        requireNotRunning(previous);
        replace(previous, OpenProject.open(previous.root()));
    }

    /** 换成新的那个，并把旧的收掉——它的运行线程池不收就一直挂着。 */
    private void replace(OpenProject previous, OpenProject next) {
        open = next;
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * 有任务在跑就不许换。
     *
     * <p>换掉之后那次运行会把文件写到旧项目、进度也没人再看——
     * 等于白跑一趟还留一地垃圾。至于「切了就中断」，
     * 那会丢掉已经改了一半的文件，比不让切糟得多。
     */
    private static void requireNotRunning(OpenProject project) {
        if (project != null && project.isRunning()) {
            throw new IllegalStateException("有任务正在运行，等它结束再换项目");
        }
    }

    /**
     * 界面启动时的一份快照：模板的完整定义（表单视图要拿它来编辑）、编译命令。
     *
     * <p>每次都现读，所以界面上改完模板刷新一下就能看到新内容。
     */
    private Map<String, Object> config(OpenProject project) {
        TemplateStore.Loaded loaded = project.workspace().loadedTemplates();
        List<Map<String, Object>> items = new ArrayList<>();
        for (PromptTemplate template : loaded.templates().values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", template.name());
            item.put("tags", template.tags());
            item.put("description", template.description());
            item.put("system", template.system());
            item.put("context", template.context());
            items.add(item);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("root", project.root().toString());
        // 这个目录是什么项目：界面据此提示「还没配过」并建议一条编译命令
        config.put("scan", new ProjectScanner().scan(project.root()));
        config.put("templates", items);
        // 读不回来的那几份照样告诉界面：藏起来的话，用户只会看到模板少了一个，
        // 然后对着「找不到模板」猜。界面把它们列出来，并说明是哪个文件。
        config.put("brokenTemplates", loaded.broken());
        config.put("compileCommand", project.config().build().compile());
        // 标签只是输入提示，不是白名单——用户打任何词都行
        config.put("commonTags", Tags.COMMON);
        return config;
    }

    /**
     * 文件树的数据：文件 + 目录。
     *
     * <p>目录也要给：界面上的树是从<b>文件路径</b>推出来的，所以只给文件的话，
     * 你在 IDE 里新建一个还没放东西的包，页面上永远看不见它——不是没刷新，是没东西可显示。
     */
    private static Map<String, Object> filesOf(OpenProject project) {
        ProjectIndex.Entries entries = project.index().entries();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", entries.files());
        body.put("dirs", entries.directories());
        return body;
    }

    /**
     * 检查阶段：同步返回一份实现方案。
     *
     * <p>分成两块发出去：{@code plan} 是模型说的，{@code audit} 是机器查出来的
     * 「执行不了的地方」。界面上只有 {@code audit} 有资格拦人——模型的自评只配当提示。
     */
    private void review(OpenProject project, HttpExchange exchange) throws IOException {
        RunRequest request = Http.readJson(exchange, RunRequest.class);
        if (request == null) {
            return;
        }
        project.requireOpen();
        ReviewOutcome outcome = project.runs().review(request);
        Http.sendJson(exchange, 200, Map.of("plan", outcome.plan(), "audit", outcome.audit()));
    }

    private void startRun(OpenProject project, HttpExchange exchange) throws IOException {
        RunRequest request = Http.readJson(exchange, RunRequest.class);
        if (request == null) {
            return;
        }
        String runId;
        // 注册运行必须和换项目互斥。否则换项目那边收掉旧项目的运行线程池时，
        // 这次提交恰好落在池子关门之后——界面上是一个 500 挂着内部类名；
        // 提交抢在前面则更糟：返回 200 加一个永远不会开始的任务，点完什么都不会发生。
        synchronized (this) {
            project.requireOpen();
            runId = project.runs().start(request);
        }
        Http.sendJson(exchange, 200, Map.of("runId", runId));
    }

    /**
     * 叫停当前这次运行。
     *
     * <p>没有任务时报 409 而不是 200：界面据此能确定「现在没有东西可停」，
     * 而不是显示一个永远不会兑现的「正在停止」。
     *
     * <p>返回 200 只表示「叫停这件事收到了」。真正的停止发生在<b>下一轮开始之前</b>
     * ——正在飞行的模型调用没法干净地掐掉，见 {@link RunService#cancel()}。
     */
    private void cancelRun(OpenProject project, HttpExchange exchange) throws IOException {
        if (!Http.requirePost(exchange)) {
            return;
        }
        if (!project.runs().hub().running()) {
            throw new IllegalStateException("现在没有在跑的任务");
        }
        project.runs().cancel();
        Http.sendJson(exchange, 200, Map.of("cancelling", true));
    }

    /**
     * 处置上一次留下的改动：接受（保留）或撤回（恢复原样）。
     *
     * <p>没有待处置的改动时抛 {@link IllegalStateException} → 409：界面据此能确定
     * 「现在没有东西可处置」，而不是显示一个永远不会兑现的结果。
     */
    private void decidePending(OpenProject project, HttpExchange exchange, boolean rollback)
            throws IOException {
        if (!Http.requirePost(exchange)) {
            return;
        }
        if (rollback) {
            project.runs().rollback();
        } else {
            project.runs().accept();
        }
        Http.sendJson(exchange, 200, Map.of("done", true));
    }

    private RunHub.RunView events(OpenProject project, HttpExchange exchange) {
        return project.runs().hub().view(Http.queryLong(exchange, "from", 0L));
    }
}
