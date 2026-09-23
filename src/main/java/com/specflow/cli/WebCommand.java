package com.specflow.cli;

import com.specflow.project.ProjectConfig;
import com.specflow.template.TemplateRegistry;
import com.specflow.template.TemplateStore;
import com.specflow.web.WebServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;

/**
 * {@code specflow web} —— 起一个只服务本机的界面，在浏览器里点选文件、写需求、看结果。
 *
 * <p>存在的理由：spec.yaml 的字段、targets 的路径、补丁协议的行为，
 * 这些东西在命令行里都要靠记。界面把它们变成勾选框和一个输入框，
 * 而底下跑的仍然是同一套引擎——UI 只是换了个入口，没有另起一套逻辑。
 *
 * <p>命令会一直挂在前台，直到 Ctrl+C。这是刻意的：关掉终端就该关掉服务，
 * 不留一个还在监听端口、还能改你文件的进程。
 */
@Command(name = "web", description = "启动本地 Web 界面（浏览器里运行）")
public final class WebCommand implements Callable<Integer> {

    private static final int DEFAULT_PORT = 8770;

    @Option(names = {"-p", "--project"},
            description = "项目根目录；不传就先打开欢迎页，在界面上挑")
    Path projectDir;

    @Option(names = "--port", description = "监听端口（仅本机回环地址）",
            defaultValue = "" + DEFAULT_PORT)
    int port;

    @Option(names = "--no-open", description = "不自动打开浏览器")
    boolean noOpen;

    @Override
    public Integer call() {
        Path root = projectDir == null ? null : CommandSupport.resolveProject(projectDir);
        if (root != null) {
            reportBrokenTemplates(root);
        }

        WebServer server = WebServer.start(root, port);
        // 退出时立刻关掉监听、释放端口，而不是等进程被整个回收。
        // 端口没及时释放是重启时最容易被卡住的地方。
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        banner(server.url(), root);
        if (!noOpen) {
            openBrowser(server.url());
        }
        awaitInterrupt();
        return 0;
    }

    /**
     * 模板每次用到都现读，所以这里只是把「读不回来的那几份」提前报一下。
     *
     * <p>刻意不在这里失败退出：模板是用户手写的文件，一个手滑就能写坏，
     * 而这时候进不去界面，也就没有任何地方能把那个文件删掉——
     * 界面上会把它们列出来，用户可以直接删。
     */
    private void reportBrokenTemplates(Path root) {
        TemplateRegistry registry = TemplateRegistry.load(root.resolve(TemplateRegistry.DEFAULT_DIR));
        for (TemplateStore.BrokenTemplate broken : registry.broken()) {
            Console.warn("模板 %s 读不出来，已跳过：%s", broken.name(), broken.reason());
        }
    }

    /**
     * 把地址单独打出来。
     *
     * <p>不能只靠「已自动打开浏览器」——自动打开失败、或者用户在没有浏览器的会话里运行时，
     * 地址是唯一能自救的信息，它必须一眼可见、可以直接复制。
     *
     * <p>刻意不用制表符画边框：中文在终端里是双宽字符，用空格对齐一定错位，
     * 而框线字符在 GBK 控制台下还有变成乱码的风险。地址本身才是重点。
     */
    private void banner(String url, Path root) {
        Console.info("");
        Console.info("  在浏览器打开： %s", url);
        Console.info("");
        if (root == null) {
            Console.detail("还没选项目，界面里挑一个");
        } else {
            Console.detail("项目: %s", root);
            ProjectConfig project = CommandSupport.loadProjectConfig(root);
            if (project.build().compile() == null) {
                Console.warn("未配置 build.compile，运行后不会做编译校验");
            }
        }
        Console.detail("只监听本机回环地址；用完直接关掉这个窗口");
        Console.info("");
    }

    /**
     * 打开浏览器是锦上添花，不是这个命令的职责。
     *
     * <p>先试 {@link Desktop}（跨平台的标准做法），不行再退回系统命令。
     * 之所以要兜底：{@code Desktop.browse} 在 Windows 上经常一声不响地什么都不做，
     * 而用户看到的就是「命令跑起来了但界面没出来」。
     */
    private void openBrowser(String url) {
        if (tryDesktop(url) || trySystemOpener(url)) {
            return;
        }
        Console.detail("没能自动打开浏览器，请手动复制上面的地址");
    }

    private boolean tryDesktop(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (IOException | RuntimeException e) {
            // 没有桌面环境是常态，交给系统命令兜底
        }
        return false;
    }

    private boolean trySystemOpener(String url) {
        List<String> command;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            command = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (os.contains("mac")) {
            command = List.of("open", url);
        } else {
            command = List.of("xdg-open", url);
        }
        try {
            // 输出丢弃：既不占管道，也不会有子进程的输出混进这个命令的日志
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 挂住主线程；Ctrl+C 结束进程，服务随之退出。 */
    private void awaitInterrupt() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
