package com.specflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specflow.history.RunRecord;
import com.specflow.history.RunStore;
import com.specflow.project.RecentProjects;
import com.specflow.review.PlanReview;
import com.specflow.tests.EntryScripts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 停用那条链的<b>灰盒</b>：真起 http、走真的引擎、真的假模型。
 *
 * <p>为什么非得起真服务：这一批最容易出的问题是「路由接错了」和「引擎算对了、响应体里没有」——
 * 两边的单元测试各自都是绿的，而界面上表现为「点了停用，什么都没变」。
 * 所以断言全部落在<b>响应体</b>与<b>假模型收到的请求</b>上，不停在
 * {@code RunService} 的返回值那一步。
 */
@DisplayName("停用用例的灰盒链路")
class CaseSwitchingWebTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    /**
     * {@code POST /api/tests/disable}：跑完一次留下记录之后，停用第 2 条 → 恢复。
     *
     * <p>三件事一起钉，因为它们在同一条路上：
     * <ul>
     *   <li>响应体里的 {@code disabled} 是<b>折出来的当下状态</b>（界面按它重画那一栏，
     *       猜错的后果是「界面上停着、引擎里没停」）；</li>
     *   <li>{@code caseSwitches} 是整条流水（停用与恢复各一条）——只回一个布尔值，
     *       界面就说不出「谁什么时候停过它」；</li>
     *   <li>恢复之后状态真的回到空：用户的原话是「删掉它」，而它必须是可拿回来的。</li>
     * </ul>
     */
    @Test
    @DisplayName("POST /api/tests/disable：停用第 2 条 → 200 且流水非空；再恢复 → 停用集合空了")
    void disablesAndRestoresThroughTheApi() throws Exception {
        try (StubModelServer model = StubModelServer.answering(answersFor(false))) {
            Path projectRoot = stubbedProject(model);
            RunStore store = new RunStore(projectRoot.resolve(RunStore.DEFAULT_DIR));
            try (WebServer server = WebServer.start(projectRoot, 0,
                    new RecentProjects(root.resolve("disable-recent.json")))) {
                HttpClient client = HttpClient.newHttpClient();

                post(client, server, "/api/run", runBody("[]"));
                waitUntilIdle(client, server);
                String id = store.latestId();
                assertThat(id).as("先跑一次，留档里得有可停用的清单").isNotEmpty();

                HttpResponse<String> stopped = postRaw(client, server, "/api/tests/disable",
                        // 编号里故意混进重复与认不出来的值：界面发的是它手上那份勾选，
                        // 顺序与重复是它的自由，而落进流水的必须是一份稳定的集合
                        "{\"id\":\"\",\"cases\":[2,2,0,-1],\"disabled\":true}");
                assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
                JsonNode body = JSON.readTree(stopped.body());
                assertThat(ints(body.path("disabled")))
                        .as("响应体里是折出来的当下状态，界面按它重画那一栏").containsExactly(2);
                assertThat(body.path("caseSwitches").size()).as("整条流水一起回去").isEqualTo(1);
                assertThat(body.path("caseSwitches").get(0).path("index").asInt()).isEqualTo(2);
                assertThat(body.path("caseSwitches").get(0).path("disabled").asBoolean()).isTrue();
                assertThat(body.path("caseSwitches").get(0).path("by").asText())
                        .as("「谁停的」也得回得去").isEqualTo(RunRecord.CaseSwitch.actor());
                // 读回磁盘那份：留档是这套工具唯一的事后依据
                assertThat(store.load(id).disabledIndexes()).containsExactly(2);

                HttpResponse<String> restored = postRaw(client, server, "/api/tests/disable",
                        "{\"id\":\"\",\"cases\":[2],\"disabled\":false}");
                assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
                JsonNode after = JSON.readTree(restored.body());
                assertThat(ints(after.path("disabled"))).as("恢复之后一条都不停用").isEmpty();
                assertThat(after.path("caseSwitches").size())
                        .as("流水是追加的：停用与恢复各一条，谁什么时候停过它还查得到").isEqualTo(2);
                assertThat(store.load(id).disabledIndexes()).isEmpty();

                // 不写 disabled 那一栏：这个接口的名字就是「停用」，缺省必须当停用。
                // 缺省当成「恢复」的话，一次误发的请求会把用户刚停掉的用例又放回分母里
                HttpResponse<String> defaulted = postRaw(client, server, "/api/tests/disable",
                        "{\"cases\":[2]}");
                assertThat(defaulted.statusCode()).as(defaulted.body()).isEqualTo(200);
                JsonNode off = JSON.readTree(defaulted.body());
                assertThat(ints(off.path("disabled"))).as("缺省当停用").containsExactly(2);
                assertThat(off.path("caseSwitches").get(off.path("caseSwitches").size() - 1)
                        .path("disabled").asBoolean()).isTrue();
            }
        }
    }

    /**
     * {@code /api/run} 带着 {@code disabled: [2]} 跑一次。
     *
     * <p>两件事一起钉：
     * <ul>
     *   <li><b>留档里记着这条流水</b>——不记的话，事后翻记录的人只看到「通过率的分母比清单短」，
     *       答不出分母里少了哪几条、谁停的；</li>
     *   <li><b>测试阶段看到的清单里没有第 2 条</b>——这一条只能从假模型收到的请求里断言。
     *       「过滤出来了、但发提示词时用的还是完整那份」这种断线，两层的单元测试都是绿的，
     *       而它的表现正是要堵住的那件事：界面上说这条不算了，引擎照样要求它被实现
     *       （{@code CaseTraceCheck} 会报「漏实现」）。</li>
     * </ul>
     */
    @Test
    @DisplayName("/api/run 带 disabled:[2]：留档记着流水，测试阶段的用例清单里没有第 2 条")
    void dropsDisabledCasesFromTheTestPhaseList() throws Exception {
        try (StubModelServer model = StubModelServer.answering(answersFor(true))) {
            Path projectRoot = stubbedProject(model);
            RunStore store = new RunStore(projectRoot.resolve(RunStore.DEFAULT_DIR));
            try (WebServer server = WebServer.start(projectRoot, 0,
                    new RecentProjects(root.resolve("run-disabled-recent.json")))) {
                HttpClient client = HttpClient.newHttpClient();

                post(client, server, "/api/run", runBody("[2]"));
                waitUntilIdle(client, server);

                // 找「测试阶段」那一次调用，再看发给它的那份清单里有什么。这是引擎有没有把停用的
                // 用例摘出去的<b>最直接</b>证据，而两种断线都落在它身上：
                // ① 摘出来了、但发提示词用的是完整那份 → 下面 doesNotContainPattern 当场红；
                // ② 压根没摘 → 第二段会报「用例 2 它没写」再要一次，桩的答案就此用尽，
                //    这一次运行在半路炸掉（落档那几条断言跟着红）。
                // 判据按「行首编号 + 竖线」，不按子串：用例名里本来就可能出现「2 | 别的什么」，
                // 第一次写的时候正是拿子串去判，把「a 变成 2 | 读 Foo…」当成了第 2 条
                int listCall = -1;
                for (int index = 0; index < model.calls(); index++) {
                    if (model.userOf(index).contains("## 用例清单")) {
                        listCall = index;
                        break;
                    }
                }
                assertThat(listCall).as("整条链上要真有一次测试阶段（一共 %s 次调用）", model.calls())
                        .isNotNegative();
                String list = caseListOf(model.userOf(listCall));
                assertThat(list).as("活着的那条照样在").containsPattern("(?m)^1\\s*\\|");
                assertThat(list).as("被停用的那条不许出现在测试阶段的清单里：%s", list)
                        .doesNotContainPattern("(?m)^2\\s*\\|");

                assertThat(store.latest()).as("这一次运行要收场并落档").isPresent();
                RunRecord record = store.latest().orElseThrow();
                assertThat(record.caseSwitches()).as("开工那一刻带着哪几条停用，要落进留档")
                        .extracting(RunRecord.CaseSwitch::index).containsExactly(2);
                assertThat(record.caseSwitches()).allSatisfy(step -> {
                    assertThat(step.disabled()).isTrue();
                    assertThat(step.by()).isEqualTo(RunRecord.CaseSwitch.actor());
                    assertThat(step.at()).isNotBlank();
                });
                assertThat(record.disabledIndexes()).containsExactly(2);
            }
        }
    }

    /**
     * <b>回归</b>：停用的用例不许从留档里消失，否则「恢复」就再也按不回来。
     *
     * <p>原来那个 bug 的样子：第二段（{@code CaseHowStage}）手里只有<b>活着的</b>那几条
     * （停用的不进测试阶段），而录制器直接拿它的产物当留档清单——于是第 2 条被停用之后
     * 从 {@code RunRecord.testCases} 里消失，{@code RunStore.disable} 再想恢复它只会回一句
     * 「用例 2 不在这次运行冻结的用例清单里」。停用于是变成了不可逆的删除，
     * 而这条链的两层各自的单元测试当时都是绿的（一层测的是「过滤对了」，一层测的是「写进去了」）。
     *
     * <p>所以这里走<b>整条真路</b>：跑一次带 {@code disabled:[2]} 的运行 → 查留档里两条都在 →
     * 按回来 → 再跑一次，并且这一次第 2 条必须真的回到测试阶段的清单里。
     */
    @Test
    @DisplayName("回归：停用的用例留在留档里，恢复成功，下一次运行它回到测试阶段的清单里")
    void keepsDisabledCasesSoTheyCanBeRestored() throws Exception {
        try (StubModelServer model = StubModelServer.answering(answersForTwoRuns())) {
            Path projectRoot = stubbedProject(model);
            RunStore store = new RunStore(projectRoot.resolve(RunStore.DEFAULT_DIR));
            try (WebServer server = WebServer.start(projectRoot, 0,
                    new RecentProjects(root.resolve("restore-recent.json")))) {
                HttpClient client = HttpClient.newHttpClient();

                post(client, server, "/api/run", runBody("[2]"));
                waitUntilIdle(client, server);
                String id = store.latestId();
                assertThat(id).isNotEmpty();

                RunRecord record = store.load(id);
                assertThat(record.testCases()).extracting(PlanReview.TestCase::index)
                        .as("留档要留完整那份：停用的用例从这儿消失，「恢复」就再也按不回来")
                        .containsExactly(1, 2);
                assertThat(record.testCases().get(0).how())
                        .as("活着的那条：怎么测就是第二段补的那一句").isEqualTo("读 Foo.java 里的 a");
                assertThat(record.testCases().get(1).how())
                        .as("停用的那条没进第二段，怎么测保持空").isEmpty();
                assertThat(record.caseSwitches()).extracting(RunRecord.CaseSwitch::index)
                        .containsExactly(2);

                // 终态事件里发给界面的那一份也必须是完整清单：界面拿它画用例 chip，
                // 少了被停用那条，人就看不见它、自然也点不到「恢复」
                JsonNode result = resultEvent(client, server);
                JsonNode shown = result.path("payload").path("testCases");
                assertThat(shown.size()).as("终态事件里要有这一栏（界面不刷新页面就该看到）").isEqualTo(2);
                assertThat(indexes(shown)).containsExactly(1, 2);

                // 恢复：这一步在过去正是会抛「不在这次运行冻结的用例清单里」的地方
                HttpResponse<String> restored = postRaw(client, server, "/api/tests/disable",
                        "{\"cases\":[2],\"disabled\":false}");
                assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
                JsonNode body = JSON.readTree(restored.body());
                assertThat(ints(body.path("disabled"))).as("恢复成功之后一条都不停用").isEmpty();
                assertThat(body.path("caseSwitches").size())
                        .as("留档里多一条恢复记录，停用那条也还在").isEqualTo(2);
                assertThat(body.path("caseSwitches").get(1).path("index").asInt()).isEqualTo(2);
                assertThat(body.path("caseSwitches").get(1).path("disabled").asBoolean())
                        .as("最后一步说的是「恢复」").isFalse();
                assertThat(store.load(id).disabledIndexes()).isEmpty();

                // 把这一次的改动处置掉（撤回，文件回到运行前），才能再跑一次
                post(client, server, "/api/rollback", "{}");

                post(client, server, "/api/run", runBody("[]"));
                waitUntilIdle(client, server);

                // 第二次运行：第 2 条不再是停用的，它必须回到测试阶段的清单里。
                // 取「最后一次」那份清单：第一次运行也有一次测试阶段
                int listCall = -1;
                for (int index = 0; index < model.calls(); index++) {
                    if (model.userOf(index).contains("## 用例清单")) {
                        listCall = index;
                    }
                }
                assertThat(listCall).as("一共 %s 次调用，两次运行各要有一次测试阶段", model.calls())
                        .isNotNegative();
                String list = caseListOf(model.userOf(listCall));
                assertThat(list).as("恢复之后它回到了要验的那几条里：%s", list)
                        .containsPattern("(?m)^1\\s*\\|")
                        .containsPattern("(?m)^2\\s*\\|");

                RunRecord second = store.latest().orElseThrow();
                assertThat(second.caseSwitches()).as("这一次一条都没停用").isNull();
                assertThat(second.testCases()).extracting(PlanReview.TestCase::index)
                        .containsExactly(1, 2);
                assertThat(second.testCases()).extracting(PlanReview.TestCase::how)
                        .as("两条的「怎么测」这一回都补上了").containsExactly(
                                "读 Foo.java 里的 a", "读 Foo.java 里的 b");
            }
        }
    }

    // ---------- 假模型的台词 ----------

    /** 两次运行的台词接起来：第一次带着 {@code disabled:[2]}，第二次是恢复之后跑的。 */
    private static String[] answersForTwoRuns() {
        List<String> all = new ArrayList<>(List.of(answersFor(true)));
        all.addAll(List.of(answersFor(false)));
        return all.toArray(String[]::new);
    }

    /**
     * 一次运行要的三份答案：开发那一轮、第二段的「怎么测」、测试阶段的产物。
     *
     * <p>顺序不能错：施工单由请求里的方案直接给定（{@code steps} 非空），所以开发那一次
     * 就是第一次调用——没有「开工前现生成施工单」的探测。
     *
     * @param secondCaseDisabled 第 2 条是不是被停用了。第二段只看得见活着的用例，
     *                           而它「一条不许少、一条不许多」——多写一条会被打回再要一次
     */
    private static String[] answersFor(boolean secondCaseDisabled) {
        List<PlanReview.TestCase> live = secondCaseDisabled
                ? List.of(caseOne())
                : List.of(caseOne(), caseTwo());
        String how = secondCaseDisabled
                ? "1 | 读 Foo.java 里的 a | a == 2\n"
                : "1 | 读 Foo.java 里的 a | a == 2\n2 | 读 Foo.java 里的 b | b == 3\n";
        List<String> verdicts = secondCaseDisabled ? List.of("PASS | 1")
                : List.of("PASS | 1", "PASS | 2");
        return new String[] {
                // 开发那一轮：把 a 改成 2
                "<<<<<<< SEARCH src/main/java/com/demo/Foo.java\nint a = 1;\n=======\nint a = 2;\n>>>>>>> REPLACE\n",
                how,
                // 测试阶段：入口脚本要带溯源锚点，否则会被拒绝运行（跑都跑不到）
                "<<<<<<< SEARCH {{ENTRY}}\n=======\n"
                        + EntryScripts.anchored(0, live, verdicts.toArray(String[]::new))
                        + ">>>>>>> REPLACE\n"
        };
    }

    /** 一次 {@code /api/run} 的请求体：方案里带一份单步施工单和两条用例（编号 1、2）。 */
    private static String runBody(String disabled) {
        return """
                {
                  "prompt": "把 a 改成 2",
                  "targets": ["src/main/java/com/demo/Foo.java"],
                  "verifyCompile": false,
                  "maxRounds": 3,
                  "integration": false,
                  "refeed": [],
                  "disabled": %s,
                  "approvedPlan": {
                    "summary": "把 a 改成 2",
                    "flowchart": "",
                    "missing": [],
                    "steps": [
                      {"index": 1, "goal": "把 a 改成 2",
                       "files": ["src/main/java/com/demo/Foo.java"],
                       "check": "能编译", "intermediate": false}
                    ],
                    "cases": [
                      {"index": 1, "what": "a 变成 2", "how": "", "level": "MUST",
                       "expected": "a == 2", "acceptance": "无"},
                      {"index": 2, "what": "b 变成 3", "how": "", "level": "SHOULD",
                       "expected": "b == 3", "acceptance": "无"}
                    ]
                  }
                }
                """.formatted(disabled);
    }

    /** 期望值刻意用 ASCII：入口脚本在 Windows 上按本机代码页读，中文会把命令拆坏（见 EntryScripts）。 */
    private static PlanReview.TestCase caseOne() {
        return new PlanReview.TestCase(1, "a 变成 2", "", PlanReview.TestCase.Level.MUST,
                "a == 2", "无");
    }

    private static PlanReview.TestCase caseTwo() {
        return new PlanReview.TestCase(2, "b 变成 3", "", PlanReview.TestCase.Level.SHOULD,
                "b == 3", "无");
    }

    // ---------- 辅助 ----------

    /** 一个配好假模型的项目（与 WebServerTest 里那份同一个形状）。 */
    private Path stubbedProject(StubModelServer model) throws IOException {
        Path projectRoot = root.resolve("stubbed");
        Files.createDirectories(projectRoot.resolve("src/main/java/com/demo"));
        Files.writeString(projectRoot.resolve("src/main/java/com/demo/Foo.java"),
                "class Foo {\n    int a = 1;\n}\n");
        Files.createDirectories(projectRoot.resolve(".specflow"));
        Files.writeString(projectRoot.resolve(".specflow/project.yaml"), """
                llm:
                  base-url: %s
                  model: stub
                  api-key-env: SPECFLOW_TEST_KEY
                """.formatted(model.baseUrl()));
        Files.writeString(projectRoot.resolve(".specflow/local.env"), "SPECFLOW_TEST_KEY=sk-test\n");
        return projectRoot;
    }

    private void post(HttpClient client, WebServer target, String path, String json)
            throws Exception {
        HttpResponse<String> response = postRaw(client, target, path, json);
        assertThat(response.statusCode()).as(path + "：" + response.body()).isEqualTo(200);
    }

    private HttpResponse<String> postRaw(HttpClient client, WebServer target, String path,
                                         String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(target.url() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 等到这次运行收场（界面也是这么轮询的）。 */
    private void waitUntilIdle(HttpClient client, WebServer target) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> events = client.send(HttpRequest.newBuilder(
                            URI.create(target.url() + "/api/events?from=0"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (!JSON.readTree(events.body()).path("running").asBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("运行一直没结束");
    }

    /** 这次运行的最后一条 result 事件；界面就是靠它画结果的。 */
    private JsonNode resultEvent(HttpClient client, WebServer target) throws Exception {
        JsonNode events = JSON.readTree(client.send(HttpRequest.newBuilder(
                        URI.create(target.url() + "/api/events?from=0"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).body()).path("events");
        for (JsonNode event : events) {
            if ("result".equals(event.path("type").asText())) {
                return event;
            }
        }
        throw new AssertionError("整条事件流里没有 result 事件：" + events);
    }

    /** 一份用例清单数组里的编号，按顺序。 */
    private static List<Integer> indexes(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path("index").asInt()));
        return values;
    }

    /** {@code ## 用例清单} 那一段的正文（它是提示词里最后一段，到下一个二级标题为止）。 */
    private static String caseListOf(String user) {
        int start = user.indexOf("## 用例清单");
        assertThat(start).as("测试阶段的提示词里要有那份清单：%s", user).isNotNegative();
        int next = user.indexOf("\n## ", start + 1);
        return next < 0 ? user.substring(start) : user.substring(start, next);
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asInt()));
        return values;
    }
}
