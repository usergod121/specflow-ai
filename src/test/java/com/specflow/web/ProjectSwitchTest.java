package com.specflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specflow.project.RecentProjects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 打开与切换项目。
 *
 * <p>这个类专门盯一件事：<b>换项目之后，状态是不是真的跟着换了</b>。
 * 「文件树是 A 的、模板还是 B 的」这种四不像，单元测试看不出来——
 * 只有真起服务、真换一次、再把每个接口都问一遍才暴露得出来。
 */
@DisplayName("打开与切换项目")
class ProjectSwitchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private WebServer server;
    private HttpClient http;
    private Path alpha;
    private Path bravo;

    @BeforeEach
    void setUp() throws IOException {
        alpha = project("alpha", "MainAlpha.java", "tpl-alpha");
        bravo = project("bravo", "MainBravo.java", "tpl-bravo");
        server = WebServer.start(null, 0, new RecentProjects(temp.resolve("recent.json")));
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(FolderPicker.OVERRIDE);
        server.close();
    }

    /** 造一个有自己文件、自己模板的项目。 */
    private Path project(String name, String javaFile, String template) throws IOException {
        Path root = temp.resolve(name);
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src").resolve(javaFile), "class X {}\n");
        Path templates = root.resolve(".specflow").resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve(template + ".yaml"),
                "name: " + template + "\nsystem: 你是工程师\n");
        return root;
    }

    private String url(String path) {
        return server.url() + path;
    }

    private JsonNode get(String path) throws Exception {
        return body(http.send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private int status(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private void open(Path root) throws Exception {
        assertThat(post("/api/open", "{\"path\":" + JSON.writeValueAsString(root.toString()) + "}")
                .statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("一进来没有项目：状态是没打开，项目接口 409，欢迎页那几个能用")
    void startsAtWelcomePage() throws Exception {
        assertThat(get("/api/state").path("open").asBoolean()).isFalse();
        assertThat(get("/api/state").path("root").isNull()).isTrue();
        assertThat(status("/api/config")).isEqualTo(409);

        // 欢迎页自己要用的那几个必须在
        assertThat(status("/api/browse")).isEqualTo(200);
        assertThat(status("/api/state")).isEqualTo(200);
    }

    @Test
    @DisplayName("没给路径时列的是盘符，不是一片空白")
    void browseWithoutPathListsDrives() throws Exception {
        JsonNode listing = get("/api/browse");

        assertThat(listing.path("dirs")).isNotEmpty();
        assertThat(listing.path("path").isNull()).isTrue();
    }

    @Test
    @DisplayName("翻目录：给出子目录、上一级，以及这个目录是什么项目")
    void browseShowsChildrenAndScan() throws Exception {
        Files.writeString(alpha.resolve("pom.xml"), "<project/>");

        JsonNode listing = get("/api/browse?path=" + encode(alpha.toString()));

        assertThat(listing.path("dirs")).anySatisfy(node ->
                assertThat(node.path("name").asText()).isEqualTo("src"));
        assertThat(listing.path("parent").asText()).isEqualTo(temp.toString());
        assertThat(listing.path("scan").path("manifests").findValuesAsText("file"))
                .contains("pom.xml");
        assertThat(listing.path("scan").path("compileCommand").asText()).contains("mvn");
    }

    @Test
    @DisplayName("浏览一个不存在的目录时说清楚，而不是给个空列表")
    void browseRejectsMissingDirectory() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/browse?path=" + encode(
                        temp.resolve("nope").toString())))).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("不是一个目录");
    }

    @Test
    @DisplayName("打开之后就认得这个项目：状态、文件树、模板都跟上了")
    void openingAProjectMakesItCurrent() throws Exception {
        open(alpha);

        assertThat(get("/api/state").path("open").asBoolean()).isTrue();
        assertThat(get("/api/state").path("root").asText())
                .isEqualTo(alpha.toAbsolutePath().normalize().toString());
        assertThat(files()).contains("src/MainAlpha.java");
        assertThat(get("/api/config").path("templates").findValuesAsText("name"))
                .containsExactly("tpl-alpha");
    }

    @Test
    @DisplayName("换一个项目，文件树和模板都跟着换——这是最怕出四不像的地方")
    void switchingProjectsSwapsEverything() throws Exception {
        open(alpha);
        assertThat(files()).contains("src/MainAlpha.java").doesNotContain("src/MainBravo.java");
        assertThat(templates()).containsExactly("tpl-alpha");

        open(bravo);
        assertThat(files()).contains("src/MainBravo.java").doesNotContain("src/MainAlpha.java");
        assertThat(templates()).containsExactly("tpl-bravo");

        // 还能换回去
        open(alpha);
        assertThat(files()).contains("src/MainAlpha.java");
        assertThat(templates()).containsExactly("tpl-alpha");
    }

    @Test
    @DisplayName("打开过的会记进「最近打开」，最新的在前")
    void remembersRecentlyOpened() throws Exception {
        open(alpha);
        open(bravo);

        assertThat(get("/api/state").path("recent").findValuesAsText("name"))
                .containsExactly("bravo", "alpha");
        // 目录还在，界面上不该灰掉它
        assertThat(get("/api/state").path("recent").get(0).path("exists").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("关掉项目回到欢迎页，但「最近打开」里还留着——关掉不等于忘掉")
    void closingKeepsItInTheRecentList() throws Exception {
        open(alpha);

        assertThat(post("/api/close", "").statusCode()).isEqualTo(200);

        assertThat(get("/api/state").path("open").asBoolean()).isFalse();
        assertThat(status("/api/config")).isEqualTo(409);
        assertThat(get("/api/state").path("recent").findValuesAsText("name")).contains("alpha");
    }

    @Test
    @DisplayName("打开一个不存在的目录：400 说清楚，而且不会把原来那个项目搞丢")
    void refusesMissingDirectory() throws Exception {
        open(alpha);

        HttpResponse<String> response = post("/api/open",
                "{\"path\":" + JSON.writeValueAsString(temp.resolve("nope").toString()) + "}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("不是一个目录");
        // 原来那个还在
        assertThat(get("/api/state").path("root").asText()).contains("alpha");
    }

    @Test
    @DisplayName("重复打开同一个项目是幂等的，不会多记一条「最近打开」")
    void reopeningIsIdempotent() throws Exception {
        open(alpha);
        open(alpha);

        assertThat(get("/api/state").path("recent").findValuesAsText("name"))
                .containsExactly("alpha");
    }

    @Test
    @DisplayName("换项目时正在提交的那次运行被明确挡下，而不是 500 挂着一串内部类名")
    void anInFlightRunIsRejectedWhenTheProjectChanges() throws Exception {
        // 有密钥才走得到「注册这次运行」那一步；模型地址指向一个死端口，
        // 万一真跑起来也出不了网
        Files.writeString(alpha.resolve(".specflow").resolve("local.env"),
                "SPECFLOW_API_KEY=fake\n");
        Files.writeString(alpha.resolve(".specflow").resolve("project.yaml"),
                "llm:\n  base-url: \"http://127.0.0.1:1\"\n");
        open(alpha);

        byte[] body = "{\"prompt\":\"改点东西\",\"targets\":[\"src/MainAlpha.java\"]}"
                .getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/run HTTP/1.1\r\nHost: 127.0.0.1:" + server.port()
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            // 只发一半：服务端这时已经认定「当前项目是 alpha」，正卡在等剩下的请求体
            int half = body.length / 2;
            out.write(body, 0, half);
            out.flush();
            Thread.sleep(600);

            // 这期间换项目，旧项目的运行线程池就被收掉了——而那半截请求接着要做的，
            // 正是往那个已经关门的池子里提交任务
            open(bravo);

            out.write(body, half, body.length - half);
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            assertThat(response).startsWith("HTTP/1.1 409");
            assertThat(response).doesNotContain("RejectedExecutionException");
        }

        // 换过去的项目照常可用，也没留下一个永远不开始的幽灵任务
        assertThat(get("/api/state").path("root").asText()).contains("bravo");
        assertThat(get("/api/events?from=0").path("running").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("挑目录的接口立刻返回，窗口开没开着由服务端说了算")
    void pickingIsAsynchronous() throws Exception {
        // 这条命令磨蹭一会儿，好让「正在挑」这个状态稳定住
        Path counter = temp.resolve("pick-called.txt");
        System.setProperty(FolderPicker.OVERRIDE,
                "echo called >> " + counter + " & ping -n 3 127.0.0.1 >nul & echo " + bravo);

        long started = System.currentTimeMillis();
        HttpResponse<String> first = post("/api/pick-folder", "");
        long waited = System.currentTimeMillis() - started;

        // 请求本身必须立刻回来，而不是挂在那儿等用户挑完
        assertThat(waited).as("等了 " + waited + "ms").isLessThan(1500);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(body(first).path("status").asText()).isEqualTo("picking");

        // 窗口开着的时候再点：还是同一个「正在挑」，不会再弹一个
        assertThat(body(post("/api/pick-folder", "")).path("status").asText()).isEqualTo("picking");
        assertThat(body(post("/api/pick-folder", "")).path("status").asText()).isEqualTo("picking");

        // 界面靠 GET 问结果——刷新过页面也一样问得到
        awaitStatus("picked");
        assertThat(Files.readAllLines(counter)).as("弹窗口的次数").hasSize(1);
        String picked = get("/api/pick-folder").path("path").asText();
        assertThat(picked).isEqualTo(bravo.toString());
        assertThat(get("/api/state").path("picking").path("status").asText()).isEqualTo("picked");

        // 挑完只是挑完；打开走的是 /api/open，两条路各管各的
        assertThat(post("/api/open", "{\"path\":" + JSON.writeValueAsString(picked) + "}")
                .statusCode()).isEqualTo(200);
        assertThat(get("/api/state").path("root").asText()).contains("bravo");
    }

    @Test
    @DisplayName("挑目录：用户取消了就停在取消，什么都不动")
    void cancelledPickChangesNothing() throws Exception {
        open(alpha);
        System.setProperty(FolderPicker.OVERRIDE, "rem 用户把窗口关掉了");

        assertThat(post("/api/pick-folder", "").statusCode()).isEqualTo(200);
        awaitStatus("cancelled");

        assertThat(get("/api/state").path("root").asText()).contains("alpha");
    }

    @Test
    @DisplayName("挑目录：弹不出来时原因在状态里，界面照原话显示")
    void failedPickCarriesTheReason() throws Exception {
        System.setProperty(FolderPicker.OVERRIDE, "echo boom 1>&2 & exit 9");

        post("/api/pick-folder", "");
        awaitStatus("failed");

        String error = get("/api/pick-folder").path("error").asText();
        assertThat(error).contains("退出码 9").contains("boom").doesNotContain("Exception");
    }

    @Test
    @DisplayName("挑目录的结果被认领之后就回到「没有窗口」——免得下次进欢迎页又自己打开一次")
    void consumingThePickResetsIt() throws Exception {
        System.setProperty(FolderPicker.OVERRIDE, "echo " + bravo);
        post("/api/pick-folder", "");
        awaitStatus("picked");

        HttpResponse<String> consumed = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/pick-folder"))).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(consumed.statusCode()).isEqualTo(200);
        assertThat(body(consumed).path("status").asText()).isEqualTo("idle");
        assertThat(get("/api/state").path("picking").path("status").asText()).isEqualTo("idle");
    }

    @Test
    @DisplayName("挑目录只接受 POST / GET / DELETE")
    void pickingRejectsOtherMethods() throws Exception {
        assertThat(http.send(HttpRequest.newBuilder(URI.create(url("/api/pick-folder")))
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(405);
    }

    /** 等服务端那份状态变成期望的样子。 */
    private void awaitStatus(String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(get("/api/pick-folder").path("status").asText())) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等不到状态 " + expected + "，现在是 " + get("/api/pick-folder"));
    }

    @Test
    @DisplayName("只敲一个盘符字母时按盘根算，不是「当前目录下的 E」")
    void bareDriveLetterMeansTheDriveRoot() throws Exception {
        assumeTrue(File.separatorChar == '\\', "盘符是 Windows 的东西");

        JsonNode listing = get("/api/browse?path=E");

        assertThat(listing.path("path").asText()).isEqualTo("E:\\");
    }

    @Test
    @DisplayName("慢慢发来的模板保存：换项目之后就落地不了，而不是写进旧项目")
    void aSlowTemplateSaveIsRejectedAfterSwitching() throws Exception {
        open(alpha);

        byte[] body = "{\"name\":\"stale-tpl\",\"system\":\"s\"}".getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/templates HTTP/1.1\r\nHost: 127.0.0.1:" + server.port()
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            int half = body.length / 2;
            out.write(body, 0, half);
            out.flush();
            Thread.sleep(600);

            // 这期间换项目：那次保存还在等请求体，它盯着的那个项目已经不是当前项目了
            open(bravo);

            out.write(body, half, body.length - half);
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            assertThat(response).startsWith("HTTP/1.1 409");
        }

        // 两个项目里都不该多出这个模板：界面上会显示「保存成功」，东西却落在你看不见的那一侧
        assertThat(alpha.resolve(".specflow/templates/stale-tpl.yaml")).doesNotExist();
        assertThat(bravo.resolve(".specflow/templates/stale-tpl.yaml")).doesNotExist();
    }

    @Test
    @DisplayName("有任务在跑时点初始化：直接拒绝，而且盘上不该已经写进去半个骨架")
    void initIsRefusedBeforeItWritesAnything() throws Exception {
        // 模型地址指向一个只接连接、永远不回话的端口：运行会一直挂在等模型上，
        // 这样「有任务正在跑」这个状态是稳的，不用赌时间
        try (ServerSocket blackhole = new ServerSocket(0)) {
            Files.createDirectories(alpha.resolve(".specflow"));
            Files.writeString(alpha.resolve(".specflow").resolve("project.yaml"),
                    "llm:\n  base-url: \"http://127.0.0.1:" + blackhole.getLocalPort() + "\"\n");
            Files.writeString(alpha.resolve(".specflow").resolve("local.env"),
                    "SPECFLOW_API_KEY=fake\n");
            open(alpha);

            assertThat(post("/api/run",
                    "{\"prompt\":\"改点东西\",\"targets\":[\"src/MainAlpha.java\"]}").statusCode())
                    .isEqualTo(200);

            HttpResponse<String> refused = post("/api/init", "");

            assertThat(refused.statusCode()).isEqualTo(409);
            // 这条才是要害：拒绝了，就不能有任何东西已经写下去
            assertThat(alpha.resolve("spec.yaml")).doesNotExist();
        }
    }

    @Test
    @DisplayName("打开一个还没配过的目录：先告诉你它是什么项目，初始化之后编译命令就位了")
    void initializesUnconfiguredProject() throws Exception {
        Files.writeString(alpha.resolve("pom.xml"), "<project/>");
        open(alpha);

        JsonNode before = get("/api/config");
        assertThat(before.path("scan").path("configured").asBoolean()).isFalse();
        assertThat(before.path("scan").path("manifests").findValuesAsText("file"))
                .contains("pom.xml");
        assertThat(before.path("compileCommand").isNull()).isTrue();

        HttpResponse<String> created = post("/api/init", "");
        assertThat(created.statusCode()).isEqualTo(200);
        List<String> written = new ArrayList<>();
        body(created).path("written").forEach(node -> written.add(node.asText()));
        assertThat(written).contains(".specflow/project.yaml", "spec.yaml");

        // 关键：项目配置是装配那一刻读的，刚写下去的这份必须重读，否则界面上还是「没配过」
        JsonNode after = get("/api/config");
        assertThat(after.path("scan").path("configured").asBoolean()).isTrue();
        assertThat(after.path("compileCommand").asText()).contains("mvn");
        assertThat(after.path("templates").findValuesAsText("name")).isNotEmpty();
    }

    @Test
    @DisplayName("初始化不会覆盖已经存在的东西——把用户写了半天的配置冲掉是最不可原谅的")
    void initNeverOverwrites() throws Exception {
        open(alpha);
        Path spec = alpha.resolve("spec.yaml");
        Files.writeString(spec, "# 我自己写的\n");

        post("/api/init", "");
        post("/api/init", "");

        assertThat(Files.readString(spec)).isEqualTo("# 我自己写的\n");
    }

    @Test
    @DisplayName("项目没了可以从「最近打开」里去掉，而不是一直挂在列表里")
    void forgetsARecentProject() throws Exception {
        open(alpha);
        open(bravo);

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/recent?path=" + encode(bravo.toString()))))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("recent").findValuesAsText("name")).containsExactly("alpha");
    }

    private java.util.List<String> files() throws Exception {
        java.util.List<String> paths = new java.util.ArrayList<>();
        get("/api/files").path("files").forEach(node -> paths.add(node.asText()));
        return paths;
    }

    private java.util.List<String> templates() throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        get("/api/config").path("templates").forEach(node -> names.add(node.path("name").asText()));
        return names;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
