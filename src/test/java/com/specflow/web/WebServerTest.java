package com.specflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.env.ComposeFile;
import com.specflow.env.FakeDocker;
import com.specflow.history.RunRecord;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.project.ProjectConfig;
import com.specflow.project.RecentProjects;
import com.specflow.project.SnapshotConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.template.TemplateRegistry;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 接口层的端到端测试。
 *
 * <p>真起一个服务、真发 HTTP 请求——这一层最容易出的问题是「路由接错了」
 * 和「异常没翻译成状态码」，而那两样只有打真请求才测得出来。
 *
 * <p>不测「运行成功」那条路：它需要真实模型密钥。会走到模型之前的校验分支
 * （targets 为空、路径越界、请求体不合法）都在这里覆盖到了。
 */
@DisplayName("Web 接口")
class WebServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private WebServer server;
    private HttpClient http;
    private RecentProjects recent;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("src/main/java/com/demo"));
        Files.writeString(root.resolve("src/main/java/com/demo/Demo.java"), "class Demo {}\n");
        Files.writeString(root.resolve("README.md"), "# demo\n");

        Path templates = root.resolve(TemplateRegistry.DEFAULT_DIR);
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("implement.yaml"), """
                name: implement
                tags: [class, java]
                description: 实现功能
                system: s
                """);

        // 「最近打开」默认写在用户目录下；测试把它指到临时目录，别去动真实的那份
        recent = new RecentProjects(root.resolve("recent.json"));
        server = WebServer.start(root, 0, recent);
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("根路径返回界面页面")
    void servesPage() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/html");
        assertThat(response.body()).contains("specflow").contains("目标文件");
    }

    @Test
    @DisplayName("模板下拉框里有「＋ 新建模板…」——不然模板永远只有 init 铺的那两个")
    void pageOffersNewTemplateEntry() throws Exception {
        String page = get("/").body();

        assertThat(page).contains("＋ 新建模板…");
        // 这个值必须是 TemplateStore 拒绝当名字的那种，否则界面能造出一个永远选不中的模板
        assertThat(page).contains("'__new__'");
    }

    @Test
    @DisplayName("界面目录下的白名单文件能打开，别的一律 404")
    void servesWhitelistedPagesOnly() throws Exception {
        // 样式自检页：改完样式靠它肉眼过一遍，打不开就等于没有
        HttpResponse<String> demo = get("/style-demo.html");
        assertThat(demo.statusCode()).isEqualTo(200);
        assertThat(demo.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(demo.body()).as("自检页得是那一页，不是别的东西").contains("样式自检");

        // 白名单是白名单：同目录下没登记的东西不给出去
        assertThat(get("/nope.html").statusCode()).isEqualTo(404);
        // 首页走的是自己那条路（/ 与 /index.html），不是静态白名单
        assertThat(get("/index.html").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("请求体是 JSON 字面量 null 时返回 400，而不是 0 字节断连")
    void nullBodyGetsAResponse() throws Exception {
        // JSON 的 null 会被正常反序列化成 Java 的 null，不进 catch，
        // 于是调用方一声不响地 return、连接被关掉——客户端只看到 0 字节响应
        for (String path : List.of("/api/templates", "/api/run", "/api/review",
                                   "/api/tasks", "/api/template-source", "/api/template-parse")) {
            HttpResponse<String> response = post(path, "null");

            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(response.body()).as(path).contains("请求体是空的");
        }
    }

    @Test
    @DisplayName("目录里有一份坏模板时，界面照常打得开，并把它列出来")
    void brokenTemplateDoesNotTakeDownThePage() throws Exception {
        Files.writeString(root.resolve(TemplateRegistry.DEFAULT_DIR).resolve("zzbroken.yaml"),
                "name: zzbroken\nsystem: [not: valid\n");

        JsonNode config = body(get("/api/config"));

        assertThat(config.path("templates").findValuesAsText("name")).contains("implement");
        assertThat(config.path("brokenTemplates")).singleElement()
                .satisfies(node -> assertThat(node.path("name").asText()).isEqualTo("zzbroken"));
        // 从界面里删得掉——否则界面起不来就没地方救它了
        assertThat(delete("/api/templates?name=zzbroken").statusCode()).isEqualTo(200);
        assertThat(body(get("/api/config")).path("brokenTemplates")).isEmpty();
    }

    @Test
    @DisplayName("模板列表的顺序稳定——界面默认选中的是第一个，顺序每次都变就白记了")
    void templateOrderIsStable() throws Exception {
        for (String name : List.of("charlie", "alpha", "bravo")) {
            post("/api/templates", "{\"name\":\"" + name + "\",\"system\":\"s\"}");
        }

        assertThat(body(get("/api/config")).path("templates").findValuesAsText("name"))
                .containsExactly("alpha", "bravo", "charlie", "implement");
    }

    @Test
    @DisplayName("template-parse 只解析不落盘——切视图不该顺手把东西存了")
    void templateParseDoesNotSave() throws Exception {
        String source = "---\nname: \"implement\"\nsystem: \"改过的\"\n";

        HttpResponse<String> parsed = post("/api/template-parse",
                "{\"name\":\"implement\",\"source\":" + quote(source) + "}");

        assertThat(parsed.statusCode()).isEqualTo(200);
        assertThat(body(parsed).path("system").asText()).isEqualTo("改过的");
        // 磁盘上还是原来那份
        assertThat(body(get("/api/config")).path("templates").get(0).path("system").asText())
                .isEqualTo("s");

        assertThat(post("/api/template-parse",
                "{\"name\":\"implement\",\"source\":\"name: x\\n\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("没打开项目时，界面照常打得开，但项目相关的接口返回 409 而不是 500")
    void projectEndpointsNeedAnOpenProject() throws Exception {
        try (WebServer bare = WebServer.start(null, 0,
                new RecentProjects(root.resolve("bare-recent.json")))) {
            HttpClient client = HttpClient.newHttpClient();

            assertThat(client.send(HttpRequest.newBuilder(URI.create(bare.url() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

            HttpResponse<String> config = client.send(
                    HttpRequest.newBuilder(URI.create(bare.url() + "/api/config")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(config.statusCode()).isEqualTo(409);
            assertThat(config.body()).contains("还没有打开项目");
        }
    }

    @Test
    @DisplayName("跨站的 Origin 被挡在 403——不然你浏览别的网页时，它就能让本机去改你的代码")
    void rejectsCrossSiteRequest() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(server.url() + "/api/config"))
                        .header("Origin", "https://evil.com").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("只接受本机页面");
    }

    @Test
    @DisplayName("伪造 Host 的请求被挡在 403——那是 DNS 重绑定，只看 Origin 挡不住")
    void rejectsForgedHost() throws Exception {
        // java.net.http 不允许自己设 Host 头，只能手写一份请求打过去
        assertThat(rawRequest("GET /api/config HTTP/1.1\r\nHost: 127.0.0.1.evil.com\r\n"
                + "Connection: close\r\n\r\n")).contains("403");
    }

    @Test
    @DisplayName("本机页面自己发的请求照常放行")
    void allowsSameOriginRequest() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(server.url() + "/api/config"))
                        .header("Origin", "http://" + server.url().replace("http://", "")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    /** 手写一个原始请求打过去，用来构造 java.net.http 不让我们设的请求头。 */
    private String rawRequest(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("只监听回环地址——这个服务能改你的文件，不能暴露到局域网")
    void bindsToLoopbackOnly() {
        assertThat(server.url()).startsWith("http://127.0.0.1:");
    }

    @Test
    @DisplayName("配置接口返回模板的元信息与字段声明，界面据此渲染表单")
    void configExposesTemplates() throws Exception {
        JsonNode config = body(get("/api/config"));

        assertThat(config.path("templates")).hasSize(1);
        JsonNode template = config.path("templates").get(0);
        assertThat(template.path("name").asText()).isEqualTo("implement");
        assertThat(template.path("tags").get(0).asText()).isEqualTo("class");
        assertThat(template.path("system").asText()).isNotBlank();
    }

    @Test
    @DisplayName("文件接口返回可点选的文件列表")
    void filesExposesProjectFiles() throws Exception {
        JsonNode files = body(get("/api/files")).path("files");

        assertThat(files).isNotEmpty();
        assertThat(files).anySatisfy(node -> assertThat(node.asText()).isEqualTo("README.md"));
        assertThat(files).anySatisfy(node ->
                assertThat(node.asText()).isEqualTo("src/main/java/com/demo/Demo.java"));
        // 目录也一起给：空包只能靠它显示出来（树是从文件路径推的）
        assertThat(dirs(body(get("/api/files")))).contains("src/main/java/com/demo");
    }

    @Test
    @DisplayName("文件树不吃缓存：你在 IDE 里加了文件或建了个空包，下一次请求就看得见")
    void filesAreNotCached() throws Exception {
        assertThat(files(body(get("/api/files")))).doesNotContain("NewFile.java");
        assertThat(dirs(body(get("/api/files")))).doesNotContain("brand-new-pkg");

        // 模拟「在 IDE 里加了个包，又加了个文件」
        Files.createDirectories(root.resolve("brand-new-pkg/inner"));
        Files.writeString(root.resolve("NewFile.java"), "class NewFile {}\n");

        JsonNode after = body(get("/api/files"));

        assertThat(files(after)).contains("NewFile.java");
        assertThat(dirs(after)).contains("brand-new-pkg", "brand-new-pkg/inner");
    }

    /** 把 JSON 里的字符串数组取成 List。 */
    private static List<String> files(JsonNode body) {
        return strings(body.path("files"));
    }

    private static List<String> dirs(JsonNode body) {
        return strings(body.path("dirs"));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    @DisplayName("spec 不合法时返回 400 与逐条问题")
    void runRejectsInvalidSpec() throws Exception {
        HttpResponse<String> response = post("/api/run", """
                {"prompt": "改点东西", "targets": []}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = body(response);
        assertThat(body.path("problems")).isNotEmpty();
        assertThat(body.path("problems").get(0).asText()).contains("targets");
    }

    @Test
    @DisplayName("targets 越界时返回 400")
    void runRejectsEscapingPath() throws Exception {
        HttpResponse<String> response = post("/api/run", """
                {"prompt": "改点东西", "targets": ["../outside.java"]}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("越出项目根目录");
    }

    @Test
    @DisplayName("缺密钥时立刻返回 400 并指明是哪个环境变量，而不是等运行到一半才失败")
    void runReportsMissingApiKeyImmediately() throws Exception {
        String keyEnv = ProjectConfig.DEFAULT.llm().apiKeyEnv();
        assumeTrue(System.getenv(keyEnv) == null, "本机已设置 " + keyEnv + "，跳过");

        HttpResponse<String> response = post("/api/run", """
                {"prompt": "改点东西", "targets": ["README.md"]}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains(keyEnv);
    }

    @Test
    @DisplayName("空名字保存模板时，报错要能看懂——不能把 Jackson 的类名端给用户")
    void templateErrorIsReadable() throws Exception {
        HttpResponse<String> response = post("/api/templates",
                "{\"name\":\"\",\"system\":\"你是工程师\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("模板名不能为空");
        // Jackson 的包装层对用户没有任何意义
        assertThat(response.body())
                .doesNotContain("Cannot construct instance")
                .doesNotContain("StreamReadFeature");
    }

    @Test
    @DisplayName("请求体不是合法 JSON 时返回 400，而不是把连接掐掉")
    void runRejectsMalformedBody() throws Exception {
        HttpResponse<String> response = post("/api/run", "{ 这不是 json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("请求体解析失败");
    }

    @Test
    @DisplayName("未知字段返回 400——界面字段名和后端对不上时必须立刻可见")
    void runRejectsUnknownField() throws Exception {
        HttpResponse<String> response = post("/api/run", """
                {"prompt": "x", "targets": ["README.md"], "target": ["README.md"]}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("运行接口只接受 POST")
    void runRejectsNonPost() throws Exception {
        assertThat(get("/api/run").statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("还没跑过任何任务时，事件列表为空且不在运行中")
    void eventsStartEmpty() throws Exception {
        JsonNode body = body(get("/api/events?from=0"));

        assertThat(body.path("running").asBoolean()).isFalse();
        assertThat(body.path("events")).isEmpty();
    }

    @Test
    @DisplayName("界面「自由输入」提交的字段集合必须原样被接受——多了少了都在这里暴露")
    void acceptsExactUiPayloadForFreePrompt() throws Exception {
        HttpResponse<String> response = post("/api/run", """
                {
                  "prompt": "给 Console 加一个 error 方法",
                  "targets": [],
                  "constraints": ["不引入新的第三方依赖"],
                  "verifyCompile": true,
                  "maxRetry": 6
                }
                """);

        // 字段能解析过去，才会走到 targets 的业务校验；
        // 一旦界面多发了后端不认识的字段，这里会变成「请求体解析失败」
        assertThat(response.body())
                .doesNotContain("请求体解析失败")
                .contains("targets 不能为空");
    }

    @Test
    @DisplayName("界面「选模板」提交的字段集合同样被接受")
    void acceptsExactUiPayloadForTemplate() throws Exception {
        HttpResponse<String> response = post("/api/run", """
                {
                  "template": "implement",
                  "variables": {"requirement": "加一个接口"},
                  "targets": [],
                  "constraints": [],
                  "context": [],
                  "verifyCompile": true,
                  "maxRetry": 6
                }
                """);

        assertThat(response.body())
                .doesNotContain("请求体解析失败")
                .contains("targets 不能为空");
    }

    @Test
    @DisplayName("界面提交的上下文依赖（引用与粘贴两种形态）被正确解析")
    void acceptsUiContextItems() throws Exception {
        HttpResponse<String> response = post("/api/run", """
                {
                  "prompt": "加一个接口",
                  "targets": [],
                  "constraints": [],
                  "context": [
                    {"name": "UserController.java", "ref": "README.md", "note": "照它的风格写"},
                    {"name": "订单表结构", "text": "CREATE TABLE orders (id BIGINT)", "note": ""}
                  ],
                  "verifyCompile": true,
                  "maxRetry": 6
                }
                """);

        // 上下文条目本身合法，所以错误只会来自 targets
        assertThat(response.body())
                .doesNotContain("请求体解析失败")
                .doesNotContain("上下文条目")
                .contains("targets 不能为空");
    }

    @Test
    @DisplayName("同时给 ref 和 text 的上下文条目被拒绝，错误信息说清楚该怎么改")
    void rejectsAmbiguousUiContextItem() throws Exception {
        HttpResponse<String> response = post("/api/run", """
                {
                  "prompt": "加一个接口",
                  "targets": ["README.md"],
                  "context": [{"ref": "README.md", "text": "一段文本"}],
                  "verifyCompile": true
                }
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("同时给了 ref 和 text");
    }

    @Test
    @DisplayName("检查接口只接受 POST")
    void reviewRejectsNonPost() throws Exception {
        assertThat(get("/api/review").statusCode()).isEqualTo(405);
    }

    /**
     * 施工单那份机器审查必须真的发到界面上——这是<b>灰盒</b>的一条：
     * 打真 HTTP 请求、走真的引擎、真的假模型，断言响应体里那一块在。
     *
     * <p>真试跑里就是这么漏掉的：引擎判出来了（{@code ReviewOutcome.stepAudit}）、
     * 那一条链的测试也全绿（它只断言到 {@code RunService} 的返回值），
     * 而 {@code /api/review} 只发了 {@code plan} 和 {@code audit}——界面永远读到空，
     * 「施工单三条硬拦」于是完全不生效。修复前后差的只有这一个字段，
     * 所以断言也必须落在响应体上。
     *
     * <p>这里给的是「第 2 步要动清单外的文件」的单子：机器判它「执行不了」。
     * 以前这里用的是一份<b>只有 2 步</b>的单子（当时的判定是「步数太少」），
     * 而下限降到 2 之后那种单子已经合格了——拿它当失败样本，这条用例就再也不验任何东西。
     */
    @Test
    @DisplayName("检查接口把施工单那份机器审查一起发出去（少了它，界面那条硬拦就静默失效）")
    void reviewSendsTheStepAuditToThePage() throws Exception {
        try (StubModelServer model = StubModelServer.answering(OUT_OF_LIST_STEP_ANSWER)) {
            Path projectRoot = stubbedProject(model);
            try (WebServer reviewed = WebServer.start(projectRoot, 0,
                    new RecentProjects(root.resolve("review-recent.json")))) {
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(reviewed.url() + "/api/review"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"prompt": "加一个按编号查询", "targets": ["src/main/java/com/demo/Foo.java"],
                                 "verifyCompile": false}
                                """))
                        .build(), HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode body = body(response);
                assertThat(body.path("plan").path("steps")).as("方案照旧发").hasSize(2);
                JsonNode stepAudit = body.path("stepAudit");
                assertThat(stepAudit.isMissingNode())
                        .as("响应体里必须有 stepAudit——没有它，界面上那三条硬拦一条都不会生效")
                        .isFalse();
                assertThat(stepAudit.path("findings")).as("第 2 步要动清单外的文件：机器判它执行不了")
                        .isNotEmpty();
                assertThat(stepAudit.path("findings").get(0).path("step").asInt())
                        .as("要点出是哪一步").isEqualTo(2);
                assertThat(stepAudit.path("findings").get(0).path("reason").asText())
                        .as("说清那一步为什么做不了")
                        .contains("Bar.java").contains("不在目标文件清单里");
            }
        }
    }

    /**
     * 用例清单是下一批界面要画的东西，也是测试阶段启动的依据（没有它就不跑测试）。
     * 它必须真的发到页面上——「引擎解析出来了、响应体里没有」这件事在施工单那次已经发生过
     * （见上面 {@code stepAudit} 那条），代价是整条链静默失效。
     *
     * <p>断言刻意落在<b>字段名</b>上，而不只是「有没有 cases 这个键」：下一批的界面读的是
     * {@code plan.cases[].index/what/how/level/expected/acceptance}，名字对不上就是读不到，
     * 而两边的测试还会各自是绿的。
     */
    @Test
    @DisplayName("检查接口把用例清单原样发出去：编号、要测什么、怎么测、分级、期望、验收标准")
    void reviewSendsTestCasesToThePage() throws Exception {
        try (StubModelServer model = StubModelServer.answering(CASES_ANSWER)) {
            Path projectRoot = stubbedProject(model);
            try (WebServer reviewed = WebServer.start(projectRoot, 0,
                    new RecentProjects(root.resolve("review-recent.json")))) {
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(reviewed.url() + "/api/review"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"prompt": "加一个按编号查询", "targets": ["src/main/java/com/demo/Foo.java"],
                                 "verifyCompile": false}
                                """))
                        .build(), HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode cases = body(response).path("plan").path("cases");
                assertThat(cases.isArray()).as("plan.cases 要是数组，缺了它界面上一张用例卡片都画不出来")
                        .isTrue();
                assertThat(cases).hasSize(2);

                JsonNode first = cases.get(0);
                assertThat(first.path("index").asInt()).as("编号是失败清单对回用例的键").isEqualTo(1);
                assertThat(first.path("what").asText()).isEqualTo("按编号查订单能查到");
                assertThat(first.path("how").asText()).contains("已存在的编号");
                assertThat(first.path("level").asText())
                        .as("分级发枚举名，红/橙/灰由界面自己映射")
                        .isEqualTo("MUST");
                assertThat(first.path("expected").asText()).isEqualTo("返回的那条的 id 等于 1");
                assertThat(first.path("acceptance").asText()).isEqualTo("订单能按编号查询");
                assertThat(cases.get(1).path("level").asText()).isEqualTo("SHOULD");
            }
        }
    }

    /**
     * 续跑那条链的灰盒：{@code /api/run} 挂起一次，{@code /api/continue?force=1} 接着跑。
     *
     * <p>两件事一起钉，因为它们在同一条路上：
     * <ul>
     *   <li><b>施工单不重生成</b>——挂起的那次已经花调用定过单子，留档里存着，
     *       续跑再问一遍就是白花的钱（真试跑里花了 2 次）；</li>
     *   <li><b>「直接放行」时提示词里没有那个出口</b>——回话那一句是硬的，
     *       系统提示词却还带着「缺料就认输」那一段，它就会又停下来要料。</li>
     * </ul>
     *
     * <p>两件事都只能从<b>假模型收到的请求</b>里看出来：运行结果上，「问了一遍施工单」
     * 和「没问」长得一模一样，而那句狠话与协议自相矛盾时，两边各自的断言都还是绿的。
     */
    @Test
    @DisplayName("续跑：不重新生成施工单，「直接放行」时提示词里也没有那个出口")
    void continueReusesTheRecordedScheduleAndDropsTheOutlet() throws Exception {
        try (StubModelServer model = StubModelServer.answering(
                // 第一次运行：开工前现生成施工单 → 第 1 步第一轮就喊缺料，挂起
                THREE_STEP_ANSWER, "NEED_CONTEXT: 缺东西",
                // 接着跑：三步各一轮，不再有「只产施工单」那一次
                patch("int a = 1;", "int a = 2;"),
                patch("int a = 2;", "int a = 3;"),
                patch("int a = 3;", "int a = 4;"))) {
            Path projectRoot = stubbedProject(model);
            RunStore store = new RunStore(projectRoot.resolve(RunStore.DEFAULT_DIR));
            try (WebServer running = WebServer.start(projectRoot, 0,
                    new RecentProjects(root.resolve("continue-recent.json")))) {
                HttpClient client = HttpClient.newHttpClient();
                String spec = """
                        {"prompt": "把 a 改成 4", "targets": ["src/main/java/com/demo/Foo.java"],
                         "verifyCompile": false}
                        """;

                post(client, running, "/api/run", spec);
                waitUntilIdle(client, running);

                assertThat(store.suspended()).as("模型说缺料：这次运行挂着等人").isPresent();
                assertThat(store.suspended().orElseThrow().planSteps())
                        .as("留档里存着那份完整施工单——这是续跑不必再问一遍的全部依据")
                        .hasSize(3);

                post(client, running, "/api/continue?force=1", spec);
                waitUntilIdle(client, running);

                assertThat(model.askedForStepsOnly(0)).as("第一次运行确实花了一次调用现生成施工单")
                        .isTrue();
                assertThat(model.askedForStepsOnly(1)).as("那一轮是给补丁的").isFalse();
                assertThat(model.stepsOnlyCalls())
                        .as("整条链上只生成过一次施工单：续跑直接用了留档里那一份")
                        .isEqualTo(1);
                assertThat(model.calls()).as("五次：生成施工单 1 次 + 喊缺料 1 次 + 三步各一轮")
                        .isEqualTo(5);

                assertThat(model.systemOf(1)).as("第一次运行没确认过方案：出口还在")
                        .contains("NEED_CONTEXT");
                assertThat(model.systemOf(2)).as("「直接放行」那一次的协议里不该再有那个出口——"
                        + "回话却说着「不要再要求补充信息」，两句话不能自相矛盾")
                        .contains("<<<<<<< SEARCH")
                        .doesNotContain("NEED_CONTEXT")
                        .doesNotContain("信息不足");

                RunRecord resumed = store.load(store.list().get(0).id());
                assertThat(resumed.stepsSource()).as("留档里说清这份单子是接着上一次的")
                        .isEqualTo("RESUMED");
                assertThat(resumed.planSteps()).as("续跑那次自己也留了一份，再续还能接着用")
                        .hasSize(3);
                assertThat(resumed.status()).isEqualTo("SUCCESS_UNVERIFIED");
            }
        }
    }

    /**
     * 续跑时留档里那份施工单对不上<b>现在</b>的清单：拒绝开工，而且挂着的那次还接得上。
     *
     * <p>两件事一起钉，缺一条这条检查就白做：
     * <ul>
     *   <li><b>拒绝要说出来</b>——那份单子里的三步都在引用 Foo.java，而用户把它换成了别的文件。
     *       清单是硬白名单，照旧跑就是让越界的步静默执行：白烧调用再整轮回滚；</li>
     *   <li><b>拒绝不能把挂起的那次挤掉</b>——{@code RunStore.suspended()} 只看最新一条记录，
     *       所以这次拒绝不能留档。留了的话，界面上刚说「把文件加回清单再来一次」，
     *       那次挂起的运行却已经找不到了。</li>
     * </ul>
     */
    @Test
    @DisplayName("续跑：留档里的施工单对不上现在的清单时拒绝开工，挂起的那次不被挤掉")
    void continueRefusesWhenTheRecordedScheduleOutlivedTheTargets() throws Exception {
        try (StubModelServer model = StubModelServer.answering(
                THREE_STEP_ANSWER, "NEED_CONTEXT: 缺东西")) {
            Path projectRoot = stubbedProject(model);
            RunStore store = new RunStore(projectRoot.resolve(RunStore.DEFAULT_DIR));
            try (WebServer running = WebServer.start(projectRoot, 0,
                    new RecentProjects(root.resolve("continue-stale-recent.json")))) {
                HttpClient client = HttpClient.newHttpClient();

                post(client, running, "/api/run", """
                        {"prompt": "把 a 改成 4", "targets": ["src/main/java/com/demo/Foo.java"],
                         "verifyCompile": false}
                        """);
                waitUntilIdle(client, running);
                assertThat(store.suspended()).isPresent();

                // 用户补料时把清单换成了另一个文件：留档里那三步全都在引用 Foo.java
                post(client, running, "/api/continue?force=1", """
                        {"prompt": "把 a 改成 4", "targets": ["src/main/java/com/demo/Other.java"],
                         "verifyCompile": false}
                        """);
                waitUntilIdle(client, running);

                JsonNode result = resultEvent(client, running);
                assertThat(result.path("payload").path("status").asText())
                        .as("界面上要看到的是一句明确的拒绝，而不是一句「失败」")
                        .isEqualTo("PLAN_OUTDATED");
                assertThat(result.path("payload").path("attempts").asInt()).isZero();
                assertThat(result.path("payload").path("detail").asText())
                        .as("要说清是哪个文件、哪一步对不上")
                        .contains("Foo.java").contains("不在目标文件清单里");
                assertThat(model.calls()).as("模型一次都没调：白烧一次调用正是这条检查要挡的")
                        .isEqualTo(2);
                assertThat(store.suspended()).as("拒绝之后那次还挂着：改回清单就能接着跑")
                        .isPresent();
            }
        }
    }

    /** 这次运行的最后一条 result 事件；界面就是靠它画结果的。 */
    private JsonNode resultEvent(HttpClient client, WebServer target) throws Exception {
        JsonNode events = body(client.send(HttpRequest.newBuilder(
                        URI.create(target.url() + "/api/events?from=0"))
                .GET().build(), HttpResponse.BodyHandlers.ofString())).path("events");
        for (JsonNode event : events) {
            if ("result".equals(event.path("type").asText())) {
                return event;
            }
        }
        throw new AssertionError("整条事件流里没有 result 事件：" + events);
    }

    /** 一个配好假模型的项目：检查与续跑两条链都要真的走到模型调用。 */
    private Path stubbedProject(StubModelServer model) throws IOException {
        Path projectRoot = root.resolve("stubbed");
        Files.createDirectories(projectRoot.resolve("src/main/java/com/demo"));
        Files.writeString(projectRoot.resolve("src/main/java/com/demo/Foo.java"), "class Foo {\n    int a = 1;\n}\n");
        Files.createDirectories(projectRoot.resolve(".specflow"));
        Files.writeString(projectRoot.resolve(".specflow/project.yaml"), """
                llm:
                  base-url: %s
                  model: stub
                  api-key-env: SPECFLOW_TEST_KEY
                """.formatted(model.baseUrl()));
        // 密钥走 .specflow/local.env：真模型密钥不进配置文件，这条规矩测试里也一样
        Files.writeString(projectRoot.resolve(".specflow/local.env"), "SPECFLOW_TEST_KEY=sk-test\n");
        return projectRoot;
    }

    private void post(HttpClient client, WebServer target, String path, String json)
            throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create(target.url() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
    }

    /** 等到这次运行结束（界面也是这么轮询的）。 */
    private void waitUntilIdle(HttpClient client, WebServer target) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> events = client.send(HttpRequest.newBuilder(
                            URI.create(target.url() + "/api/events?from=0"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (!body(events).path("running").asBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("运行一直没结束");
    }

    private static String patch(String search, String replace) {
        return "<<<<<<< SEARCH src/main/java/com/demo/Foo.java\n" + search + "\n=======\n"
                + replace + "\n>>>>>>> REPLACE\n";
    }

    /** 一份「第 2 步要动清单外的文件」的施工单：机器会拦，因为那一步物理上做不了。 */
    private static final String OUT_OF_LIST_STEP_ANSWER = """
            <<<<<<< SUMMARY
            分两步做。
            >>>>>>> SUMMARY

            <<<<<<< FLOW
            flowchart TD
                A[入口] --> B[出口]
            >>>>>>> FLOW

            <<<<<<< STEPS
            1 | 先给 Foo 加一个方法 | src/main/java/com/demo/Foo.java | 能编译 | 自洽
            2 | 再接上调用（要动清单外的那个类） | src/main/java/com/demo/Bar.java | 能编译 | 自洽
            >>>>>>> STEPS
            """;

    /** 合法的一份三步施工单，给「开工前现生成」那条路用。 */
    private static final String THREE_STEP_ANSWER = """
            <<<<<<< STEPS
            1 | 先给 Foo 加一个方法 | src/main/java/com/demo/Foo.java | 能编译 | 自洽
            2 | 接着把 a 改成 3 | src/main/java/com/demo/Foo.java | 能编译 | 自洽
            3 | 最后收尾 | src/main/java/com/demo/Foo.java | 能编译 | 自洽
            >>>>>>> STEPS
            """;

    /** 一份带用例清单的检查结果：检查接口要把它原样发给界面。 */
    private static final String CASES_ANSWER = """
            <<<<<<< SUMMARY
            加一个按编号查询的方法。
            >>>>>>> SUMMARY

            <<<<<<< FLOW
            flowchart TD
                A[入口] --> B[出口]
            >>>>>>> FLOW

            <<<<<<< CASES
            1 | 按编号查订单能查到 | 用已存在的编号查一次 | 必须过 | 返回的那条的 id 等于 1 | 订单能按编号查询
            2 | 查不到的编号不抛异常 | 用不存在的编号查一次 | 建议过 | 返回空集合而不是抛异常 | 无
            >>>>>>> CASES
            """;

    @Test
    @DisplayName("检查接口同样先做 spec 校验，不合法时返回 400 与逐条问题")
    void reviewValidatesSpecFirst() throws Exception {
        HttpResponse<String> response = post("/api/review", """
                {"prompt": "改点东西", "targets": []}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).path("problems")).isNotEmpty();
    }

    @Test
    @DisplayName("界面读到什么就能原样存回去：模板的读-改-存整条链路必须闭合")
    void templateRoundTripsThroughApi() throws Exception {
        JsonNode fromConfig = body(get("/api/config")).path("templates").get(0);

        // 界面就是这么做的：读出来、改几个字段、原样存回去
        assertThat(post("/api/templates", fromConfig.toString()).statusCode()).isEqualTo(200);

        JsonNode reloaded = body(get("/api/config")).path("templates").get(0);
        assertThat(reloaded.path("tags")).hasSize(2);
        assertThat(reloaded.path("system").asText()).isNotBlank();
    }

    @Test
    @DisplayName("新建模板之后立刻能在配置里看到——服务不缓存模板")
    void newTemplateIsVisibleImmediately() throws Exception {
        assertThat(post("/api/templates", """
                {"name":"接口1","tags":["class","java"],"description":"新增接口",
                 "system":"你是工程师"}
                """).statusCode()).isEqualTo(200);

        assertThat(body(get("/api/config")).path("templates"))
                .anySatisfy(node -> assertThat(node.path("name").asText()).isEqualTo("接口1"));
    }

    @Test
    @DisplayName("源码视图里改 name 会被拦住——放任它分叉，那个模板就既删不掉也读不出来")
    void templateSourceRejectsRenamedSource() throws Exception {
        String source = body(get("/api/template-source?name=implement")).path("source").asText();
        String renamed = source.replaceFirst("(?m)^name:.*$", "name: \"改名了\"");

        HttpResponse<String> rejected = post("/api/template-source",
                "{\"name\":\"implement\",\"source\":" + quote(renamed) + "}");

        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.body()).contains("对不上").contains("改名了");
        // 磁盘上不该多出那个名字对不上的模板
        assertThat(body(get("/api/config")).path("templates").findValuesAsText("name"))
                .contains("implement")
                .doesNotContain("改名了");
    }

    @Test
    @DisplayName("模板源码视图能读能写；写坏了在应用那一刻就被拒绝")
    void templateSourceRoundTrips() throws Exception {
        String source = body(get("/api/template-source?name=implement")).path("source").asText();
        assertThat(source).contains("tags").contains("system");

        String broken = source.replace("system:", "systemX:");
        HttpResponse<String> rejected = post("/api/template-source",
                "{\"name\":\"implement\",\"source\":" + quote(broken) + "}");
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.body()).contains("模板 implement 不合法");

        assertThat(post("/api/template-source",
                "{\"name\":\"implement\",\"source\":" + quote(source) + "}").statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("模板名带路径穿越时被拒绝")
    void templateNameIsGuarded() throws Exception {
        HttpResponse<String> response = post("/api/templates", """
                {"name":"../../evil","system":"s"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("模板名非法");
    }

    @Test
    @DisplayName("任务草稿：存进去、列出来、载回来，形状与提交时一致")
    void taskRoundTrips() throws Exception {
        assertThat(post("/api/tasks", """
                {"name":"订单查询","spec":{"template":"implement","prompt":"查询订单列表",
                 "variables":{"requirement":"查询订单列表"},
                 "targets":["README.md"],"constraints":["不引入新依赖"],
                 "verifyCompile":true,"maxRetry":4}}
                """).statusCode()).isEqualTo(200);

        assertThat(body(get("/api/tasks")).path("tasks"))
                .anySatisfy(node -> assertThat(node.asText()).isEqualTo("订单查询"));

        JsonNode loaded = body(get("/api/task?name=" + encode("订单查询")));
        assertThat(loaded.path("template").asText()).isEqualTo("implement");
        assertThat(loaded.path("variables").path("requirement").asText()).isEqualTo("查询订单列表");
        assertThat(loaded.path("targets").get(0).asText()).isEqualTo("README.md");
        assertThat(loaded.path("maxRetry").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("不存在的草稿：载入、删除都给出 400 与原因")
    void missingTaskIsReported() throws Exception {
        String missing = encode("不存在");
        assertThat(get("/api/task?name=" + missing).statusCode()).isEqualTo(400);
        assertThat(delete("/api/tasks?name=" + missing).statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("还没导出过上下文时清单是空的，不是报错")
    void contextListStartsEmpty() throws Exception {
        HttpResponse<String> response = get("/api/context");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("bundles")).isEmpty();
    }

    @Test
    @DisplayName("导出上下文：返回名字与相对路径，文件真的落到磁盘上，再读回来条目还在")
    void contextExportWritesTheFile() throws Exception {
        HttpResponse<String> saved = post("/api/context", """
                {"name":"订单上下文","items":[
                  {"name":"UserController.java","ref":"README.md","note":"照它的风格写"},
                  {"name":"订单表结构","text":"CREATE TABLE orders (id BIGINT)"}]}
                """);

        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(body(saved).path("name").asText()).isEqualTo("订单上下文");
        // 相对项目根的 POSIX 路径，界面直接显示成「已导出到 …」
        assertThat(body(saved).path("path").asText())
                .isEqualTo(".specflow/context/订单上下文.yaml");
        assertThat(Files.isRegularFile(root.resolve(".specflow/context/订单上下文.yaml"))).isTrue();

        // 界面要拿这一份做导入预览，所以 GET 必须带上条目本身
        JsonNode bundles = body(get("/api/context")).path("bundles");
        assertThat(bundles).singleElement().satisfies(bundle -> {
            assertThat(bundle.path("name").asText()).isEqualTo("订单上下文");
            assertThat(bundle.path("project").asText()).isEqualTo(root.getFileName().toString());
            assertThat(bundle.path("items")).hasSize(2);
            assertThat(bundle.path("items").get(0).path("ref").asText()).isEqualTo("README.md");
            assertThat(bundle.path("items").get(1).path("text").asText())
                    .contains("CREATE TABLE orders");
        });
    }

    @Test
    @DisplayName("导出上下文时名字非法返回 400 与人话，磁盘上不会多出文件")
    void contextExportRejectsBadName() throws Exception {
        HttpResponse<String> response = post("/api/context", """
                {"name":"../../evil","items":[{"name":"a","text":"文本"}]}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("上下文名非法");
        // Jackson 的包装层对用户没有任何意义
        assertThat(response.body())
                .doesNotContain("Cannot construct instance")
                .doesNotContain("StreamReadFeature");
        assertThat(root.resolve("evil.yaml")).doesNotExist();
        assertThat(body(get("/api/context")).path("bundles")).isEmpty();
    }

    @Test
    @DisplayName("上下文接口只接受 GET / POST")
    void contextRejectsOtherMethods() throws Exception {
        HttpResponse<String> response = put("/api/context",
                "{\"name\":\"x\",\"items\":[{\"name\":\"a\",\"text\":\"文本\"}]}");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).contains("该接口只接受 GET / POST");
    }

    @Test
    @DisplayName("没有任务在跑时「停止」返回 409——界面据此知道没有东西可停")
    void cancelWithoutRunningTaskReturns409() throws Exception {
        HttpResponse<String> response = post("/api/cancel", "");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("现在没有在跑的任务");
        // 没有东西可停的时候，GET 也不该被当成一次叫停
        assertThat(get("/api/cancel").statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("「最近打开」里有一条 path 为 null 的记录时，欢迎页照常打得开")
    void aRecentEntryWithoutPathDoesNotTakeDownTheWelcomePage() throws Exception {
        // 这三种写法都会给出 null（条目本身或它的路径），而 Path.of(null) 是 NPE——
        // 不挡下来就是整页 500，连「移除这条」都点不到
        for (String broken : List.of("[{\"path\":null,\"name\":\"x\"}]",
                                     "[{\"name\":\"x\"}]",
                                     "[null]")) {
            Files.writeString(root.resolve("recent.json"), broken);

            HttpResponse<String> state = get("/api/state");

            assertThat(state.statusCode()).as(broken).isEqualTo(200);
            // 没有路径的记录不是「打开过的项目」：它既打不开也没法删，列出来只会让人莫名其妙
            assertThat(body(state).path("recent")).as(broken).isEmpty();
        }
    }

    @Test
    @DisplayName("空路径的记录不会被当成「当前工作目录」——删它不该牵连另一个真实项目")
    void aBlankRecentEntryIsNotTheWorkingDirectory() throws Exception {
        Files.writeString(root.resolve("recent.json"), """
                [{"path":"","name":"坏的"},{"path":"%s","name":"真的"}]
                """.formatted(root.toString().replace("\\", "\\\\")));

        JsonNode recent = body(get("/api/state")).path("recent");
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).path("exists").asBoolean()).isTrue();

        // 空路径只和空路径相等；删它不该把「真的」那条一起带走
        assertThat(delete("/api/recent?path=").statusCode()).isEqualTo(200);
        assertThat(body(get("/api/state")).path("recent").findValuesAsText("name"))
                .containsExactly("真的");
    }

    @Test
    @DisplayName("未知路径返回 404")
    void unknownPathReturns404() throws Exception {
        assertThat(get("/api/nope").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("路径里有非法字符时说人话——那是打错了，不是服务端故障")
    void invalidPathCharactersAreRejectedAsBadInput() throws Exception {
        for (String bad : List.of("C:\\aa\"bb", "C:\\aa*b", "\u0000")) {
            HttpResponse<String> browse = get("/api/browse?path=" + encode(bad));
            assertThat(browse.statusCode()).as(bad).isEqualTo(400);
            // 界面上是直接显示这段文字的，Java 的异常类名对用户没有任何意义
            assertThat(browse.body()).as(bad)
                    .doesNotContain("InvalidPathException")
                    .doesNotContain("Exception");

            HttpResponse<String> open = post("/api/open",
                    "{\"path\":" + quote(bad) + "}");
            assertThat(open.statusCode()).as(bad).isEqualTo(400);
            assertThat(open.body()).as(bad).doesNotContain("InvalidPathException");
        }
    }

    @Test
    @DisplayName("「最近打开」里有一条坏记录时欢迎页照常打得开，那条显示成已不在磁盘上")
    void aBrokenRecentEntryDoesNotTakeDownTheWelcomePage() throws Exception {
        // 这个文件是我们自己写的，但用户能改它，也可能被别的工具改坏
        Files.writeString(root.resolve("recent.json"),
                "[{\"path\":\"C:\\\\bad\\u0001x\",\"name\":\"x\",\"lastOpened\":\"t\"}]");

        HttpResponse<String> state = get("/api/state");

        assertThat(state.statusCode()).isEqualTo(200);
        JsonNode recent = body(state).path("recent");
        assertThat(recent).hasSize(1);
        // 灰掉那一条就够了，用户还能点它右边的「×」把自己救出来
        assertThat(recent.get(0).path("exists").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("待处置的改动：查得到、会挡住下一次运行、撤回之后文件真的回到原样")
    void pendingChangesBlockRunsAndCanBeRolledBack() throws Exception {
        Path foo = root.resolve("src/main/java/com/demo/Foo.java");
        Files.writeString(foo, "class Foo { int a = 1; }\n");
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(
                new SafePathResolver(root), root.resolve(SnapshotConfig.DEFAULT_DIR), List.of(foo));
        Files.writeString(foo, "class Foo { int a = 2; }\n");
        snapshot.markPending();

        JsonNode pending = body(get("/api/pending"));
        assertThat(pending.path("present").asBoolean()).isTrue();
        assertThat(pending.path("canAccept").asBoolean()).isTrue();
        assertThat(pending.path("summary").asText()).isEqualTo("1 个文件：新增 0、修改 1");
        assertThat(pending.path("files").get(0).path("path").asText())
                .isEqualTo("src/main/java/com/demo/Foo.java");
        assertThat(pending.path("files").get(0).path("diff").asText())
                .contains("+class Foo { int a = 2; }");

        // 引擎自己也会拦，这里要的是「点下的瞬间」就拿到 409
        HttpResponse<String> run = post("/api/run", "{\"prompt\":\"做点什么\"}");
        assertThat(run.statusCode()).isEqualTo(409);
        assertThat(run.body()).contains("还没处置");

        assertThat(post("/api/rollback", "{}").statusCode()).isEqualTo(200);
        assertThat(Files.readString(foo)).isEqualTo("class Foo { int a = 1; }\n");
        assertThat(body(get("/api/pending")).path("present").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("保留改动：快照被删掉，磁盘上的改动一动不动")
    void acceptingKeepsTheChangeOnDisk() throws Exception {
        Path foo = root.resolve("src/main/java/com/demo/Foo.java");
        Files.writeString(foo, "class Foo { int a = 1; }\n");
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(
                new SafePathResolver(root), root.resolve(SnapshotConfig.DEFAULT_DIR), List.of(foo));
        Files.writeString(foo, "class Foo { int a = 2; }\n");
        snapshot.markPending();

        assertThat(post("/api/accept", "{}").statusCode()).isEqualTo(200);

        assertThat(Files.readString(foo)).isEqualTo("class Foo { int a = 2; }\n");
        assertThat(body(get("/api/pending")).path("present").asBoolean()).isFalse();
    }

    /**
     * 收场那一行要随 200 一起回去（十五.8）。
     *
     * <p>为什么不能只回一个 {@code done:true}：收场不只是「文件留没留」——还有删掉的测试产物、
     * 重置过的环境数据、以及「这次是带着几条失败用例接受的」。界面自己拼一句「已保留改动」，
     * 用户就永远看不到后面那三样，而它们正是事后要对的账。
     */
    @Test
    @DisplayName("接受：返回体里带着收场那一行（删了哪些产物、清没清环境数据）")
    void acceptingReportsWhatTheTeardownDid() throws Exception {
        Path foo = root.resolve("src/main/java/com/demo/Foo.java");
        Files.writeString(foo, "class Foo { int a = 1; }\n");
        Files.createDirectories(root.resolve("tools/20260930-170000"));
        Files.writeString(root.resolve("tools/20260930-170000/run.cmd"), "echo PASS\n");
        WorkspaceSnapshot.capture(new SafePathResolver(root),
                root.resolve(SnapshotConfig.DEFAULT_DIR), List.of(foo)).markPending();
        new RunStore(root.resolve(RunStore.DEFAULT_DIR)).save(recordWithTests(
                "tools/20260930-170000"));
        Files.writeString(foo, "class Foo { int a = 2; }\n");

        HttpResponse<String> response = post("/api/accept", "{}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(body(response).path("teardown").asText())
                .contains("已接受").contains("测试产物");
        assertThat(root.resolve("tools/20260930-170000"))
                .as("产物当着用户的面删掉，而且这事儿得说得出来").doesNotExist();
        // 留档里那一栏：怎么收的场 + 当时带着几条失败（这次没跑过用例，所以是 0）
        JsonNode settlement = body(get("/api/run-detail?id=" + encode(latestRunId())))
                .path("settlement");
        assertThat(settlement.path("choice").asText()).isEqualTo("ACCEPT");
        assertThat(settlement.path("summary").asText()).contains("已接受");
    }

    /** 一条「跑过测试、留了产物」的记录，用来验收场要按留档删产物。 */
    private RunRecord recordWithTests(String directory) {
        return new RunRecord("20260930-170000", "2026-09-30T17:00", "TESTS_FAILED", null, "做点什么",
                List.of(), List.of(), null, List.of("src/main/java/com/demo/Foo.java"), 1, "一条没过",
                List.of(), List.of(), List.of(), List.of(), null, List.of(),
                new com.specflow.tests.TestOutcome(directory, List.of(directory + "/run.cmd"), 1, 1,
                        com.specflow.verify.VerificationResult.failed("测试脚本", "run", "一条没过"),
                        List.of(),
                        List.of(new com.specflow.tests.TestOutcome.CaseResult(1, false)), List.of()),
                null, null, null, null, List.of(),
                // 覆盖核对、回喂、「它没有改动」：这一条只关心收场怎么删产物，三笔都留空。
                // 最后那一栏是「谁什么时候停用过哪几条」，同样空着
                null, null, null, null);
    }

    private String latestRunId() {
        return new RunStore(root.resolve(RunStore.DEFAULT_DIR)).latestId();
    }

    @Test
    @DisplayName("没有待处置的改动时处置接口报 409，并且只接受 POST")
    void decidingWithoutPendingIsAConflict() throws Exception {
        assertThat(get("/api/pending").statusCode()).isEqualTo(200);
        assertThat(body(get("/api/pending")).path("present").asBoolean()).isFalse();

        assertThat(get("/api/accept").statusCode()).isEqualTo(405);
        assertThat(post("/api/accept", "{}").statusCode()).isEqualTo(409);
        assertThat(post("/api/rollback", "{}").statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("挂起的运行：查得到它说了什么；没有挂起时接口各报各的错")
    void suspendedRunIsVisible() throws Exception {
        assertThat(body(get("/api/suspended")).path("present").asBoolean()).isFalse();
        assertThat(get("/api/continue").statusCode()).as("接着跑只接受 POST").isEqualTo(405);
        assertThat(post("/api/continue", "{\"prompt\":\"做点什么\"}").statusCode()).isEqualTo(409);

        writeRunRecord("20260101-000000-001", "NEEDS_CONTEXT", "NEED_CONTEXT: 我需要 OrderMapper.java");

        JsonNode suspended = body(get("/api/suspended"));
        assertThat(suspended.path("present").asBoolean()).isTrue();
        assertThat(suspended.path("runId").asText()).isEqualTo("20260101-000000-001");
        assertThat(suspended.path("need").asText()).contains("OrderMapper");
        assertThat(suspended.path("repeated").asInt()).isEqualTo(1);
    }

    /** 直接写一份留档，不经过模型：界面要看的是「挂着的那次说了什么」，没必要真跑一次。 */
    private void writeRunRecord(String id, String status, String detail) throws IOException {
        Path directory = root.resolve(RunStore.DEFAULT_DIR);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(id + ".json"), """
                {"id":"%s","startedAt":"t","status":"%s","prompt":"p",
                 "targets":["src/main/java/com/demo/Demo.java"],"attempts":1,"detail":"%s"}
                """.formatted(id, status, detail));
    }

    /**
     * 一份手改坏的上下文文件只该让<b>那一份</b>读不出来。
     *
     * <p>这些文件是能手改的，手滑一个字符就长这样。以前任意一份抛异常，整次 GET 就是 400，
     * 界面上是一套上下文都不显示——连「把那份坏的删掉」的入口都没有。
     * 分寸照 {@code TemplateStore} 读模板那套：跳过、但把「哪一份、为什么」报回来。
     */
    @Test
    @DisplayName("一份手改坏的上下文文件不让整次读取失败：跳过它，并说清是哪一份")
    void aBrokenContextFileDoesNotTakeDownTheWholeList() throws Exception {
        assertThat(post("/api/context", """
                {"name":"好的","items":[{"name":"订单表结构","text":"CREATE TABLE orders (id BIGINT)"}]}
                """).statusCode()).isEqualTo(200);
        Path directory = root.resolve(".specflow/context");
        Files.writeString(directory.resolve("坏的.yaml"), "name: 坏的\nitems: [这不是: 合法 yaml\n");

        HttpResponse<String> response = get("/api/context");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = body(response);
        assertThat(body.path("bundles")).singleElement().satisfies(bundle ->
                assertThat(bundle.path("name").asText()).isEqualTo("好的"));
        // 坏的那份不能悄悄消失：不然用户只会看到自己导出过的那套不见了
        assertThat(body.path("broken")).singleElement().satisfies(broken -> {
            assertThat(broken.path("name").asText()).isEqualTo("坏的");
            assertThat(broken.path("reason").asText()).contains("坏的");
        });
        // 这条提示是要摆在界面上的，所以不能带着 Jackson 的类名和源码位置
        assertThat(response.body())
                .doesNotContain("Cannot construct instance")
                .doesNotContain("through reference chain")
                .doesNotContain("com.specflow");
        assertThat(body.path("broken").get(0).path("reason").asText())
                .contains("上下文条目既没有 ref");

        // 修好之后（或者删掉之后）它就该回到清单里：跳过只是这一次的事
        Files.delete(directory.resolve("坏的.yaml"));
        assertThat(body(get("/api/context")).path("broken")).isEmpty();
    }

    /**
     * 项目根下明文躺着的密钥文件。这个服务能做的只有「认出来、说一句」：
     * 不引依赖就没有跨平台的密钥库，而删改用户的文件是越权。
     */
    @Test
    @DisplayName("项目根下的明文密钥文件会随配置报给界面，好让界面提醒一句「别提交」")
    void plaintextKeyFilesAreReported() throws Exception {
        assertThat(body(get("/api/config")).path("plaintextKeyFiles")).isEmpty();

        Files.writeString(root.resolve("key.env"), "key: sk-abcdef\n");

        JsonNode files = body(get("/api/config")).path("plaintextKeyFiles");
        assertThat(files).singleElement()
                .satisfies(node -> assertThat(node.asText()).isEqualTo("key.env"));
    }

    /**
     * 关服务时当前项目也要收掉。
     *
     * <p>它带着一个运行线程池：那个池子是单线程的、核心线程不超时，
     * 不提掉就一直挂着一条线程等下一次运行——而服务已经没了。
     * 换项目和关服务是同一件事的两种结束方式，收口的动作也该是同一个。
     *
     * <p>判据用真线程：服务停了就没有接口可问了，而「项目收没收到」只体现在
     * 它的运行线程有没有停下来。盯的是<b>这一次</b>新起来的那条线程，
     * 不看总数——别的测试留下的线程来去都不该影响这条断言。
     */
    @Test
    @DisplayName("关掉服务时当前项目一并收掉：它的运行线程不留在那儿")
    void closingTheServerAlsoClosesTheOpenProject() throws Exception {
        // setUp 那个服务先收掉：这条要自己起一个、自己关一个，才看得出「关」做了什么
        server.close();
        Files.createDirectories(root.resolve(".specflow"));
        Files.writeString(root.resolve(".specflow/local.env"), "SPECFLOW_API_KEY=fake\n");
        // 模型地址指向一个没人听的端口：这次运行必然失败，但线程池照样起来了——
        // 而「池子起没起、收没收」正是这条要看的
        Files.writeString(root.resolve(".specflow/project.yaml"),
                "llm:\n  base-url: \"http://127.0.0.1:1\"\n  max-retries: 0\n");

        Set<Thread> before = runThreads();
        WebServer own = WebServer.start(root, 0,
                new RecentProjects(root.resolve("recent-close.json")));
        Thread started;
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create(own.url() + "/api/run"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"prompt\":\"做点什么\",\"targets\":[\"README.md\"]}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            started = awaitNewRunThread(before);
            assertThat(started).as("提交运行之后线程池真的起了一条线程").isNotNull();
        } finally {
            own.close();
        }

        assertThat(awaitStopped(started)).as("关服务之后那条运行线程不在了").isTrue();
    }

    /** 现在活着的运行线程（名字是 specflow-run）。 */
    private static Set<Thread> runThreads() {
        Set<Thread> threads = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("specflow-run".equals(thread.getName())) {
                threads.add(thread);
            }
        }
        return threads;
    }

    /** 等一条不属于 {@code before} 的运行线程出现。 */
    private static Thread awaitNewRunThread(Set<Thread> before) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (Thread thread : runThreads()) {
                if (!before.contains(thread)) {
                    return thread;
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    /** 等某条线程结束。关闭是「中断 + 当前任务收尾」，不是瞬间的事。 */
    private static boolean awaitStopped(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!thread.isAlive()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    // ---------- 测试环境（十五.5 / 15.8） ----------

    /**
     * 没写 {@code env.yaml} 的项目：界面上只能跑单元测试，而且**一次 docker 都不探**。
     *
     * <p>「一个字节都没变」这条老口径就落在这儿：绝大多数项目没有那份声明，
     * 它们不该为这个功能付出任何代价。
     */
    @Test
    @DisplayName("没声明测试环境：报「只能跑单元测试」，集成测试勾不了")
    void reportsNoEnvironmentDeclaration() throws Exception {
        JsonNode env = body(get("/api/env"));

        assertThat(env.path("declared").asBoolean()).isFalse();
        assertThat(env.path("state").asText()).isEqualTo("NOT_DECLARED");
        assertThat(env.path("usable").asBoolean()).isFalse();
        assertThat(env.path("docker").asText())
                .as("没声明就不该探 docker：这一栏空着就是「一次进程都没起」的凭据")
                .isEmpty();
        assertThat(env.path("dockerLabel").asText()).isEmpty();
        assertThat(env.path("leftovers").asInt()).isZero();
        assertThat(env.path("todo").asText()).contains("env.yaml");
    }

    /** {@code env.yaml} 写错了：把带行号的问题原样给界面（用户不必等到起容器才发现）。 */
    @Test
    @DisplayName("env.yaml 写错：把哪一行错了报给界面")
    void reportsEnvironmentConfigProblems() throws Exception {
        declare("""
                image: "x:1"
                workdir: "work"
                """);

        JsonNode env = body(get("/api/env"));

        assertThat(env.path("declared").asBoolean()).isTrue();
        assertThat(env.path("configError")).hasSize(1);
        assertThat(env.path("configError").get(0).asText())
                .contains("第 2 行").contains("绝对路径");
    }

    /**
     * 初始化测试环境：走真的 HTTP、真的起进程——只不过「docker」是一个脚本。
     *
     * <p>这台机器上不能真的起容器（会拉镜像、会占资源），而这条链要验的正是
     * 「点了初始化之后发生了什么」：compose 落盘、容器起来、状态变成可用。
     * 把 docker 换成脚本之后这三件事全都能验，而且不留任何东西。
     */
    @Test
    @DisplayName("初始化测试环境：真起进程（docker 是脚本），回来后状态变成「可跑集成」")
    void initializesTheEnvironment() throws Exception {
        FakeDocker fake = FakeDocker.create(root.resolve(".specflow/fake-docker"));
        fake.respond("version", 0, "fake docker 1.0")
                .respond("up -d --wait", 0, "started")
                .respond("exec -T", 0)
                .respond("ps -a", 0, project() + "-app-1")
                .respond("volume ls", 0)
                .respond("network ls", 0)
                // 收环境那条命令：关不掉是要报出来的，所以桩里也得让它成功
                .respond("down", 0);
        declare("""
                docker:
                  command: "%s"
                image: "eclipse-temurin:17"
                env:
                  DB_HOST: "db"
                init:
                  - "python -m build-db"
                """.formatted(fake.script().toString().replace('\\', '/')));

        HttpResponse<String> response = post("/api/env/init", "{}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(body(response).path("state").asText()).isEqualTo("READY");
        // compose 落在引擎自己的目录里，项目自己的 compose 文件一个字都没动
        assertThat(root.resolve(".specflow/env")).isDirectory();
        assertThat(fake.calls()).anySatisfy(call -> assertThat(call).contains("up -d --wait"));
        assertThat(fake.calls()).anySatisfy(call -> assertThat(call).contains("build-db"));

        JsonNode env = body(get("/api/env"));
        assertThat(env.path("usable").asBoolean()).isTrue();
        assertThat(env.path("dockerLabel").asText()).contains("可用");
        assertThat(env.path("registration").path("containers")).isNotEmpty();
        assertThat(env.path("registration").path("summary").asText()).contains("已就绪");
    }

    /** 清空测试环境：用户手动收环境的入口，连卷一起。 */
    @Test
    @DisplayName("清空测试环境：down -v，之后状态回到「还没初始化」")
    void clearsTheEnvironment() throws Exception {
        FakeDocker fake = FakeDocker.create(root.resolve(".specflow/fake-docker"));
        fake.respond("version", 0, "fake docker 1.0")
                .respond("up -d --wait", 0)
                .respond("ps -a", 0, project() + "-app-1")
                .respond("volume ls", 0)
                .respond("network ls", 0)
                // 收环境那条命令：关不掉是要报出来的，所以桩里也得让它成功
                .respond("down", 0);
        declare("""
                docker:
                  command: "%s"
                image: "x:1"
                """.formatted(fake.script().toString().replace('\\', '/')));
        post("/api/env/init", "{}");

        HttpResponse<String> response = post("/api/env/clear", "{}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(fake.calls()).anySatisfy(call ->
                assertThat(call).contains("down -v --remove-orphans"));
        assertThat(root.resolve(".specflow/env")).isEmptyDirectory();
        assertThat(body(get("/api/env")).path("usable").asBoolean()).isFalse();
    }

    /**
     * 勾了集成测试、声明也在、只是还没起来：<b>不再拦。</b>
     *
     * <p>开发一开跑引擎就会异步把它起起来（预热），到测试阶段多半已经好了；
     * 真起不来也只记一句「环境不可用」、跳过集成，产品改动照旧留着。
     * 旧行为是当场 409，用户要先去点「初始化测试环境」、等半分钟再回来点运行——
     * 白等一整轮开发调用，而失败看起来还像「代码写错了」。
     *
     * <p>该拦的那一条（<b>没声明</b>环境就勾集成）没变，见
     * {@link #refusesIntegrationWithoutDeclaration()}。
     */
    @Test
    @DisplayName("勾了集成测试但环境还没起来：受理（引擎会先预热），不再当场拒")
    void acceptsIntegrationBeforeTheEnvironmentIsWarmedUp() throws Exception {
        declare("""
                image: "x:1"
                """);

        HttpResponse<String> response = post("/api/run",
                "{\"prompt\":\"做点什么\",\"targets\":[\"README.md\"],\"integration\":true}");

        assertThat(response.statusCode()).as(response.body()).isNotEqualTo(409);
        assertThat(response.body()).as("拦的该是「没声明环境」，而不是「环境还没起来」")
                .doesNotContain("集成测试");
    }

    /** 没声明环境却勾了集成测试：说清缺的是那份声明文件。 */
    @Test
    @DisplayName("没声明环境却勾了集成测试：说清先写 env.yaml")
    void refusesIntegrationWithoutDeclaration() throws Exception {
        HttpResponse<String> response = post("/api/run",
                "{\"prompt\":\"做点什么\",\"targets\":[\"README.md\"],\"integration\":true}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(body(response).path("error").asText()).contains("env.yaml");
    }

    /**
     * 「谁错了」写进运行留档：接口收的是<b>完整的一份集合</b> + 判的是哪一档，
     * 存下来之后翻记录还看得见（刷新一次不该把人的判断抹掉）。
     */
    @Test
    @DisplayName("判决落档：接口写进去，运行详情读得回来")
    void recordsVerdictsIntoTheArchive() throws Exception {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder.start(store, TestSpecs.spec(List.of("README.md")), null, AgentListener.NOOP)
                .finished(AgentResult.failed(1, List.of(), List.of(), "测试没过"));
        String id = store.latestId();

        HttpResponse<String> response = post("/api/tests/judge",
                "{\"id\":" + quote(id) + ",\"cases\":[2,1],\"owner\":\"CODE\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode detail = body(get("/api/run-detail?id=" + encode(id)));
        assertThat(detail.path("verdicts")).hasSize(2);
        assertThat(detail.path("verdicts").get(0).path("index").asInt()).isEqualTo(1);
        assertThat(detail.path("verdicts").get(0).path("owner").asText())
                .as("留档里要说清这一判是「谁错了」").isEqualTo("CODE");

        // 不带 id（刷新过页面之后界面手里没有它）：按最新那条落
        assertThat(post("/api/tests/judge", "{\"cases\":[3],\"owner\":\"KNOWN\"}")
                .statusCode()).isEqualTo(200);
        JsonNode after = body(get("/api/run-detail?id=" + encode(id))).path("verdicts");
        assertThat(after).as("两条判断并存：一条是「开发错了」，一条是「不重要」").hasSize(3);
        assertThat(after.get(2).path("owner").asText()).isEqualTo("KNOWN");
    }

    /** 一条运行记录都没有时写判决：409 说清「先跑一次」。 */
    @Test
    @DisplayName("没有运行记录时写判决：409，而不是静默丢掉这个判断")
    void refusesVerdictsWithoutAnyRun() throws Exception {
        HttpResponse<String> response = post("/api/tests/judge", "{\"cases\":[1],\"owner\":\"KNOWN\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(body(response).path("error").asText()).contains("跑一次");
    }

    private void declare(String source) throws IOException {
        Path file = root.resolve(".specflow/env.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    /**
     * 这个临时项目算出来的 compose 项目名。
     *
     * <p>直接问引擎要，不在这里自己拼：名字里还挂着一个从绝对路径算出来的短哈希，
     * 而「名字像不像这个项目」正是守着那一堆容器/卷的判据——
     * 拼错了，它们会被判成别人的东西（那是安全口径，只是这些断言就看不到效果了）。
     */
    private String project() {
        return ComposeFile.projectName(root);
    }

    // ---------- 辅助 ----------

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server.url() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server.url() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server.url() + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String jsonBody) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server.url() + path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    /** 把一段文本塞进 JSON 字符串里，省得在测试里手写转义。 */
    private String quote(String text) {
        try {
            return JSON.writeValueAsString(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 中文参数要放进 query，必须按 UTF-8 转义。 */
    private String encode(String text) throws Exception {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
