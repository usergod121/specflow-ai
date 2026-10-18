package com.specflow.web;

import com.specflow.core.Event;
import com.specflow.core.EventLog;
import com.specflow.core.Json;
import com.specflow.core.Project;
import com.specflow.env.Capabilities;
import com.specflow.env.Compiler;
import com.specflow.env.EnvConfig;
import com.specflow.env.EnvConfigLoader;
import com.specflow.env.Environment;
import com.specflow.flow.Facts;
import com.specflow.flow.FlowService;
import com.specflow.flow.Node;
import com.specflow.flow.NodeView;
import com.specflow.flow.Runs;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>全部路由都在这里</b>（`docs/API.md` 那张表的实现）。
 *
 * <p>这一层只做三件事：<b>取参数</b>、<b>调下面那一层</b>、<b>拼响应</b>。
 * 判据一条都不许在这里写——闸在 {@code flow.Gates}、事件在 {@code flow}/{@code env}、
 * 编译在 {@code env.Compiler}。路由里出现 {@code if (state == ...)} 就说明判据漏上来了。
 *
 * <p>另外两条：
 * <ul>
 *   <li><b>长任务立刻返回</b>（`202` + 游标），界面按 {@code ?from=} 轮询；只有
 *       「起环境」是同步的（它的语义就是"点一下，等它好"，而它会一边跑一边推事件）；</li>
 *   <li>写盘的接口一律**原子写**（{@code Atomic}），不许裸 {@code Files.write}。</li>
 * </ul>
 */
@RestController
public class Api {

    private final Session session;

    Api(Session session) {
        this.session = session;
    }

    // ---------- 外壳 ----------

    @GetMapping("/api/state")
    public Map<String, Object> state() {
        return session.state();
    }

    @PostMapping("/api/open")
    public Map<String, Object> open(@RequestBody Map<String, Object> body) {
        String path = text(body, "path");
        if (path.isBlank()) {
            throw ApiException.badRequest("没给路径");
        }
        return session.open(Path.of(path));
    }

    @GetMapping("/api/browse")
    public Map<String, Object> browse(@RequestParam(name = "path", defaultValue = "") String path) {
        return session.browse(path).get(0);
    }

    @GetMapping("/api/recent")
    public Map<String, Object> recent() {
        return Map.of("recent", session.recent());
    }

    @DeleteMapping("/api/recent")
    public Map<String, Object> forget(@RequestParam(name = "path", defaultValue = "") String path) {
        return Map.of("recent", session.forget(path));
    }

    // ---------- 流程 ----------

    @GetMapping("/api/flow")
    public Map<String, Object> flow() {
        return flowView();
    }

    @PostMapping("/api/flow/new")
    public Map<String, Object> flowNew() {
        session.flow().newFlow();
        return flowView();
    }

    @PostMapping("/api/flow/goto")
    public Map<String, Object> flowGoto(@RequestBody Map<String, Object> body) {
        Node node = parseNode(text(body, "node"));
        List<String> invalidated = session.flow().gotoNode(node);
        Map<String, Object> answer = new LinkedHashMap<>(flowView());
        answer.put("invalidated", invalidated);
        return answer;
    }

    @PostMapping("/api/flow/freeze")
    public Map<String, Object> flowFreeze(@RequestBody Map<String, Object> body) {
        Node node = parseNode(text(body, "node"));
        session.flow().freeze(node);
        // 冻住 ④ = "这些改动我认了"：那一份写前快照到此为止，删掉（accept）。
        // 不删的话每跑一次开发就在盘上留一份原文副本，攒着没人处置，而「回滚」永远只指向最近一份。
        if (node == Node.DEV) {
            acceptSnapshot();
        }
        Map<String, Object> answer = new LinkedHashMap<>(flowView());
        answer.put("node", node.key());
        answer.put("state", session.flow().state().stateOf(node).name());
        return answer;
    }

    /** 把 ④ 留下的写前快照处置掉（{@code accept}）。失败不许拦住冻结——它只是清一份副本。 */
    private void acceptSnapshot() {
        try {
            com.specflow.flow.Snapshot snapshot = com.specflow.flow.Snapshot.latest(session.project());
            if (snapshot == null) {
                return;
            }
            com.specflow.flow.Snapshot.Report report = snapshot.accept();
            session.events().say(Node.DEV.key(), "info", report.summarize());
        } catch (RuntimeException e) {
            session.events().warn(Node.DEV.key(), "写前快照没处置掉（不影响继续）：" + e.getMessage());
        }
    }

    // SSE 的三个节奏：推一次（250ms 够快，又不至于把文件读成风扇声）、心跳（15s）、空闲多久自己收场（30 分钟）
    private static final long STREAM_TICK_MILLIS = 250;
    private static final int STREAM_HEARTBEAT_TICKS = 60;
    private static final long STREAM_IDLE_MILLIS = 30 * 60 * 1000L;

    /**
     * <b>同一条事件流的 SSE 版本</b>（`docs/API.md` 里那一行 `GET /api/node/{key}/stream`）。
     *
     * <h2>为什么改 SSE（而不是继续轮询）</h2>
     * 轮询是 1.2 秒一次：拉镜像那种"每几百毫秒冒一行"的过程永远慢半拍，而空轮询还在持续读盘。
     * SSE 是"服务端有新行就推"，界面的感受从"跳着出现"变成"顺着出来"。
     *
     * <h2>为什么它不会变成第二个真相</h2>
     * 推的载荷与 {@code ?from=} 那条<b>完全同形</b>（{@code events/next/running/stage}），
     * 游标语义也一样（{@code id ≡ 行号}）。所以断线重连 = 带上最后的 id 再订一次，
     * 客户端不需要补偿逻辑，服务端也没有第二份状态。
     *
     * <p>收场条件是"没有在跑的活"：推一帧 {@code done} 之后主动关闭——
     * 不关的话浏览器的 EventSource 会自动重连，界面就会永远"显示成在跑"。
     */
    @GetMapping(value = "/api/node/{key}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter nodeStream(
            @PathVariable("key") String key,
            @RequestParam(name = "from", defaultValue = "0") long from) {
        parseNode(key);
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(STREAM_IDLE_MILLIS);
        Thread worker = new Thread(() -> streamTo(from, emitter), "sse-" + key);
        worker.setDaemon(true);
        emitter.onCompletion(worker::interrupt);
        emitter.onError(e -> worker.interrupt());
        emitter.onTimeout(() -> {
            worker.interrupt();
            emitter.complete();
        });
        worker.start();
        return emitter;
    }

    /** SSE 那一条的后台推送：有新行就推，闲时心跳，没活就收场。 */
    private void streamTo(long from,
                          org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        long cursor = Math.max(0, from);
        int quiet = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Map<String, Object> view = eventsView(cursor);
                Object rows = view.get("events");
                boolean fresh = rows instanceof List<?> list && !list.isEmpty();
                if (fresh) {
                    cursor = ((Number) view.getOrDefault("next", cursor)).longValue();
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name("events").data(view, MediaType.APPLICATION_JSON));
                    quiet = 0;
                } else if (++quiet % STREAM_HEARTBEAT_TICKS == 0) {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name("ping").data(Map.of("at", Event.now()), MediaType.APPLICATION_JSON));
                }
                if (!Boolean.TRUE.equals(view.get("running"))) {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name("done").data(view, MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }
                Thread.sleep(STREAM_TICK_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception e) {
            // 客户端关页面/换路由 = 正常断开，不是错误；别往事件流里写噪音
            emitter.complete();
        }
    }

    /**
     * <b>跨流程看一眼</b>：这个项目下每条流程的状态与结论数（只读扫描，不是查询语言）。
     *
     * <p>一次读 {@code flow/<id>/flow.json} 与 {@code cases.json} 各一份小文件，
     * 按 id 倒序（id 是时间戳，字典序就是时间序）。坏文件跳过，不让整页报错。
     */
    @GetMapping("/api/flows")
    public Map<String, Object> flows(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        java.util.List<com.specflow.flow.FlowIndex> rows = session.opened()
                ? com.specflow.flow.FlowIndex.scan(session.layout(), limit)
                : java.util.List.of();
        return Map.of("flows", rows, "current", session.opened() ? session.flow().state().id() : "");
    }

    /** 五格共用的一条事件流（界面按 `?from=` 增量拉）。 */
    @GetMapping("/api/node/{key}/events")
    public Map<String, Object> nodeEvents(@PathVariable("key") String key,
                                          @RequestParam(name = "from", defaultValue = "0") long from) {
        parseNode(key);
        return eventsView(from);
    }

    @PostMapping("/api/node/cancel")
    public Map<String, Object> cancel() {
        Runs runs = session.runs();
        if (!runs.running()) {
            throw ApiException.conflict("现在没有在跑的东西：没有东西可以停");
        }
        runs.cancel();
        return Map.of("cancelling", true, "cursor", eventsView(0).get("next"));
    }

    // ---------- ① 环境 ----------

    /** 保存环境声明：env.yaml 与编译命令**各写各的落点**（缺一样 ① 就永远冻结不了）。 */
    @PostMapping("/api/node/env/save")
    public Map<String, Object> envSave(@RequestBody Map<String, Object> body) {
        Project project = session.project();
        String yaml = text(body, "envYaml");
        String compile = text(body, "compileCommand");
        if (yaml.isBlank() && compile.isBlank()) {
            throw ApiException.badRequest("env.yaml 与编译命令都是空的：至少写一样再保存");
        }
        String written = "";
        if (!yaml.isBlank()) {
            EnvConfig parsed = EnvConfigLoader.parse(yaml);
            if (parsed == null) {
                throw ApiException.badRequest("这份 env.yaml 读不出来");
            }
            com.specflow.core.Atomic.write(project.layout().envYaml(), yaml);
            written = project.layout().show(project.layout().envYaml());
        }
        String compileWritten = "";
        if (!compile.isBlank()) {
            compileWritten = writeCompile(project, compile);
        }
        session.flow().note(Node.ENV, Map.of(
                "compileDeclared", compile.isBlank() ? "false" : "true",
                "compileCommand", compile));
        session.events().say(Node.ENV.key(), "info", "环境声明已保存："
                + (written.isBlank() ? "（env.yaml 没动）" : written)
                + (compile.isBlank() ? "；编译命令：（没写）"
                : "；编译命令 → " + (compileWritten.isBlank() ? "（原来就是这条）" : compileWritten)));
        return Map.of("written", written, "compileWritten", compileWritten,
                "flow", session.flow().view());
    }

    @GetMapping("/api/node/env/view")
    public Map<String, Object> envView() {
        Project project = session.project();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("envYaml", com.specflow.core.Atomic.read(project.layout().envYaml(), ""));
        body.put("compileCommand", project.compile());
        body.put("declared", java.nio.file.Files.isRegularFile(project.layout().envYaml()));
        body.put("needed", !session.flow().state().flag(Node.ENV, "envNotNeeded"));
        return body;
    }

    /**
     * 起环境：拉镜像 + init + <b>在容器里构建一次</b>。
     *
     * <p><b>同步</b>（与别的动作不同）：它的语义就是"点一下，等它好"，而过程会一边跑
     * 一边进事件流——用户看得到它在拉哪个镜像，所以挂着等不是"卡住"。
     *
     * <p>最后那一步是这一版的针对性修复：<b>"起来了"不等于"能用"</b>。原先起完就记
     * {@code envReady}，而"能不能构建"没人验——容器里没有 Maven 也照样绿灯，⑤ 一进去就撞墙
     * （实测烧掉 20 轮 / 30 分钟）。现在起完立刻问容器一句：这个项目在你里面构建得起来吗。
     */
    @PostMapping("/api/node/env/up")
    public Map<String, Object> envUp() {
        Project project = session.project();
        session.flow().requireReachable(Node.ENV, "起环境");
        com.specflow.core.EventProgress progress = new com.specflow.core.EventProgress(session.events(), Node.ENV.key());
        try (AutoCloseable ignored = com.specflow.core.Jobs.register(progress)) {
            // 「这一步我愿意等多久」只有一个来源：界面上那一栏（flow.json 的 pullTimeoutMinutes）。
            // 起环境、容器内构建、编译、⑤ 的容器与单条命令，全按这一个数走（见 env.Budgets）。
            long wait = com.specflow.env.Budgets.secondsFrom(pullTimeout());
            Environment environment = new Environment(project).withPullTimeoutMinutes(pullTimeout());
            Environment.Registration registration = environment.up(progress);
            session.flow().note(Node.ENV, Map.of(Facts.ENV_READY, "true", "envState", registration.state()));

            Capabilities.Report capability = Capabilities.verify(environment, project, wait, progress);
            Map<String, String> learned = new LinkedHashMap<>();
            learned.put(Facts.CONTAINER_BUILD, String.valueOf(capability.ok()));
            learned.put(Facts.CONTAINER_DETAIL, capability.detail());
            learned.put("containerBuildSeconds", String.valueOf(capability.buildSeconds()));
            if (!capability.tool().isBlank()) {
                learned.put(Facts.CONTAINER_TOOL, capability.tool());
            }
            session.flow().note(Node.ENV, learned);
            if (!capability.fixedCommand().isBlank()) {
                // 引擎在容器里试出来的写法就是唯一真相：写回盘上，④ 与 ⑤ 才不会又撞一次
                String where = writeCompile(project, capability.fixedCommand());
                session.events().warn(Node.ENV.key(), "编译命令里 Windows 风格的反斜杠在容器里跑不通，"
                        + "已替你改成 `" + capability.fixedCommand() + "`（写进了 " + where + "）。"
                        + "同一条命令要在宿主机和 Linux 容器里都跑，`\\` 在容器里是转义字符。");
            }
            if (!capability.ok()) {
                // 常驻一条 error：这句话最容易被忽略，而下游一定撞上它
                session.events().error(Node.ENV.key(), "能力校验没过：" + capability.detail()
                        + (capability.raw().isBlank() ? "" : "\n" + capability.raw()));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("seconds", progress.elapsedSeconds());
            body.put("containers", registration.containers());
            body.put("composeFile", registration.composeFile());
            Map<String, Object> cap = new LinkedHashMap<>();
            cap.put("ok", capability.ok());
            cap.put("tool", capability.tool());
            cap.put("toolOk", capability.toolOk());
            cap.put("buildOk", capability.buildOk());
            cap.put("seconds", capability.buildSeconds());
            cap.put("detail", capability.detail());
            body.put("capability", cap);
            return body;
        } catch (RuntimeException e) {
            // 失败**常驻**：error 事件已经由 progress 写进流里了，这里只把它交回响应
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("error", e.getMessage());
            body.put("seconds", progress.elapsedSeconds());
            return body;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** 编译（秒级；与「起环境」是两条路）。结论落进 ① 的 facts——界面那枚徽章读它。 */
    @PostMapping("/api/node/env/compile")
    public Map<String, Object> envCompile() {
        Project project = session.project();
        session.flow().requireReachable(Node.ENV, "编译");
        com.specflow.core.EventProgress progress = new com.specflow.core.EventProgress(session.events(), Node.ENV.key());
        Compiler.Result result;
        try (AutoCloseable ignored = com.specflow.core.Jobs.register(progress)) {
            result = Compiler.compile(project, progress,
                    com.specflow.env.Budgets.secondsFrom(pullTimeout()));
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        session.flow().note(Node.ENV, Map.of(
                "compilePassed", String.valueOf(result.passed()),
                "compileStatus", result.status(),
                "compileSeconds", String.valueOf(result.seconds())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.passed());
        body.put("status", result.status());
        body.put("command", result.command());
        body.put("output", result.output());
        body.put("seconds", result.seconds());
        return body;
    }

    /** 显式声明「这个项目不需要环境」（① 的闸另一条出路）。 */
    @PostMapping("/api/node/env/skip")
    public Map<String, Object> envSkip() {
        session.flow().requireReachable(Node.ENV, "声明不需要环境");
        session.flow().note(Node.ENV, Map.of("envNotNeeded", "true"));
        session.events().warn(Node.ENV.key(), "你显式声明了「这个项目不需要环境」：① 的门槛因此只剩编译命令那一条");
        return Map.of("envNotNeeded", true, "flow", session.flow().view());
    }

    @GetMapping("/api/node/env/settings")
    public Map<String, Object> envSettingsView() {
        return Map.of("pullTimeoutMinutes", pullTimeout());
    }

    @PostMapping("/api/node/env/settings")
    public Map<String, Object> envSettings(@RequestBody Map<String, Object> body) {
        Integer minutes = number(body, "pullTimeoutMinutes");
        if (minutes == null || minutes < 1 || minutes > 1440) {
            throw ApiException.badRequest("超时只能是 1~1440 分钟，收到：" + minutes);
        }
        session.flow().note(Node.ENV, Map.of("pullTimeoutMinutes", String.valueOf(minutes)));
        return Map.of("pullTimeoutMinutes", minutes);
    }

    // ---------- ② 探针 ----------

    @PostMapping("/api/node/probe/brief")
    public Map<String, Object> probeBrief(@RequestBody Map<String, Object> body) {
        String requirement = text(body, "requirement");
        String design = text(body, "design");
        if (requirement.isBlank()) {
            throw ApiException.badRequest("需求是必填的：探针照它去读项目、判断该动哪些文件");
        }
        session.flow().note(Node.PROBE, Map.of("requirement", requirement, "design", design));
        session.events().say(Node.PROBE.key(), "info", "需求与设计已保存");
        return Map.of("flow", session.flow().view());
    }

    @GetMapping("/api/node/probe/view")
    public Map<String, Object> probeView() {
        return readProbe();
    }

    @PostMapping("/api/node/probe/save")
    public Map<String, Object> probeSave(@RequestBody Map<String, Object> body) {
        session.flow().requireReachable(Node.PROBE, "保存探针产物");
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("files", lines(body.get("files")));
        probe.put("changes", lines(body.get("changes")));
        probe.put("context", lines(body.get("context")));
        probe.put("prompt", text(body, "prompt"));
        com.specflow.core.Atomic.write(session.layout().probe(session.flow().state().id()),
                Json.write(probe));
        // 与「跑探针」那一处同一个口径：闸判「有没有」= "true"，数量另开一栏
        session.flow().note(Node.PROBE, Map.of(
                com.specflow.flow.Facts.PROBE_FILES,
                String.valueOf(!((List<?>) probe.get("files")).isEmpty()),
                com.specflow.flow.Facts.PROBE_PROMPT,
                String.valueOf(!((String) probe.get("prompt")).isBlank()),
                "probeFileCount", String.valueOf(((List<?>) probe.get("files")).size())));
        session.events().say(Node.PROBE.key(), "info", "探针产物已保存");
        return Map.of("probe", probe, "flow", session.flow().view());
    }

    // ---------- ③ 用例 ----------

    @GetMapping("/api/node/cases")
    public Map<String, Object> casesView() {
        com.specflow.test.CaseList list = caseList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cases", list.cases());
        body.put("tags", com.specflow.test.CaseList.TAGS);
        body.put("pending", list.pending());
        body.put("missingTags", list.missingTags());
        body.put("problems", list.problems());
        return body;
    }

    @PostMapping("/api/node/cases/save")
    public Map<String, Object> casesSave(@RequestBody Map<String, Object> body) {
        session.flow().requireReachable(Node.CASES, "改用例");
        List<com.specflow.test.CaseList.Case> parsed = new ArrayList<>();
        Object raw = body.get("cases");
        if (raw instanceof List<?> items) {
            int at = 0;
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> one)) {
                    continue;
                }
                at++;
                parsed.add(new com.specflow.test.CaseList.Case(
                        string(one.get("id")).isBlank() ? "C" + at : string(one.get("id")),
                        string(one.get("what")),
                        lines(one.get("tags")),
                        string(one.get("expect")),
                        com.specflow.test.CaseList.Status.PENDING, "", ""));
            }
        }
        caseList().save(parsed);
        session.flow().note(Node.CASES, Map.of("caseCount", String.valueOf(parsed.size())));
        session.events().say(Node.CASES.key(), "info", "用例已保存：一共 " + parsed.size() + " 条");
        return casesView();
    }

    // ---------- 跑起来的那几段（后台跑，界面按游标轮询）----------

    /** ① 探测环境：只读地读一遍项目，产出 env.yaml 全文 + 一条编译命令。 */
    @PostMapping("/api/node/env/agent")
    public Map<String, Object> envAgent(@RequestBody(required = false) Map<String, Object> body) {
        FlowService flow = session.flow();
        flow.requireReachable(Node.ENV, "探测环境");
        String note = text(body == null ? Map.of() : body, "note");
        String runId = session.runs().start(Node.ENV, "env-agent", Map.of(), progress -> {
            com.specflow.agent.EnvAgent agent = new com.specflow.agent.EnvAgent(
                    session.project(), com.specflow.core.Llm.from(session.project()), note, progress,
                    new com.specflow.agent.EventWatcher(session.events(), Node.ENV.key()), 30);
            com.specflow.agent.EnvAgent.Result result = agent.run();
            if (!result.ok()) {
                progress.fail("探测环境没成（" + result.status() + "）：" + result.reason(), null);
                return;
            }
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("envYaml", result.draft().envYaml());
            draft.put("compileCommand", result.draft().compileCommand());
            draft.put("notes", result.draft().notes());
            // **探测完就写下来**（原来只把草稿丢回界面，用户以为"探测环境"会生成 env.yaml，
            // 结果盘上什么都没有，界面上却挂着"环境已就绪"）。文本框仍然可改，改完点「保存」覆盖。
            StringBuilder wrote = new StringBuilder();
            if (!result.draft().envYaml().isBlank()) {
                com.specflow.core.Atomic.write(session.project().layout().envYaml(),
                        result.draft().envYaml());
                wrote.append("env.yaml ").append(result.draft().envYaml().split("\n").length).append(" 行");
            }
            if (!result.draft().compileCommand().isBlank()) {
                writeCompile(session.project(), result.draft().compileCommand());
                wrote.append(wrote.isEmpty() ? "" : "、").append("编译命令「")
                        .append(result.draft().compileCommand()).append("」");
            }
            flow.note(Node.ENV, Map.of(
                    com.specflow.flow.Facts.ENV_DECLARED, "true",
                    "compileDeclared", String.valueOf(!result.draft().compileCommand().isBlank()),
                    "compileCommand", result.draft().compileCommand()));
            progress.done("探测完成，已写回 " + wrote + "（接下来点「起环境」）",
                    Map.of("draft", draft,
                            "rounds", result.loop() == null ? 0 : result.loop().rounds(),
                            "modelCalls", result.loop() == null ? 0 : result.loop().modelCalls()));
        });
        return accepted(runId, Node.ENV);
    }

    /** ② 跑探针：只读地读项目，交出四样产物。 */
    @PostMapping("/api/node/probe/agent")
    public Map<String, Object> probeAgent(@RequestBody(required = false) Map<String, Object> body) {
        FlowService flow = session.flow();
        flow.requireReachable(Node.PROBE, "跑探针");
        String requirement = flow.state().fact(Node.PROBE, "requirement");
        String design = flow.state().fact(Node.PROBE, "design");
        if (requirement.isBlank()) {
            throw ApiException.badRequest("需求还是空的：探针照它去读项目、判断该动哪些文件。先在 ② 把需求写上");
        }
        String runId = session.runs().start(Node.PROBE, "probe-agent", Map.of(), progress -> {
            com.specflow.agent.Probe probe = new com.specflow.agent.Probe(session.project(),
                    com.specflow.core.Llm.from(session.project()), progress,
                    new com.specflow.agent.EventWatcher(session.events(), Node.PROBE.key()), 30);
            com.specflow.agent.Probe.Result result = probe.run(requirement, design);
            if (!result.ok()) {
                // 说清"旧产物没动"：用户报过"重跑探针，旧的没被覆盖"——那时其实是被校验驳回了，
                // 盘上那份当然还是上一版的。不说这一句，看起来就像"引擎没写"。
                progress.fail("探针没交出产物（" + result.status() + "）：" + result.reason()
                        + "\n\n（磁盘上旧的那份 probe.json 没动：这一轮没能交出一份合法的产物）", null);
                return;
            }
            com.specflow.core.Atomic.write(session.layout().probe(flow.state().id()),
                    com.specflow.core.Json.write(result.artifacts().view()));
            // 把"要新建的文件"单独点出来：这类白名单条目在盘上还不存在，是 ④ 用 write_file 建出来的。
            List<String> creates = new ArrayList<>();
            for (String one : result.artifacts().changes()) {
                if (!java.nio.file.Files.isRegularFile(session.project().root().resolve(one))) {
                    creates.add(one);
                }
            }
            if (!creates.isEmpty()) {
                progress.warn("要新建的文件 " + creates.size() + " 个（④ 会用 write_file 建）："
                        + String.join("、", creates));
            }
            // 闸判的是「有没有」= "true"，不是数量：数量另开一栏给界面用。
            // （实测踩到：写成数量时闸读到的不是 "true"，于是"跑完了却还说没有可读文件"）
            flow.note(Node.PROBE, Map.of(
                    com.specflow.flow.Facts.PROBE_FILES,
                    String.valueOf(!result.artifacts().files().isEmpty()),
                    com.specflow.flow.Facts.PROBE_PROMPT,
                    String.valueOf(!result.artifacts().prompt().isBlank()),
                    "probeFileCount", String.valueOf(result.artifacts().files().size()),
                    "probeChangeCount", String.valueOf(result.artifacts().changes().size())));
            progress.done("探针产物已落盘：可读 " + result.artifacts().files().size()
                            + " 个、要改 " + result.artifacts().changes().size() + " 个",
                    Map.of("probe", result.artifacts().view(),
                            "rounds", result.loop() == null ? 0 : result.loop().rounds(),
                            "modelCalls", result.loop() == null ? 0 : result.loop().modelCalls()));
        });
        return accepted(runId, Node.PROBE);
    }

    /** ③ 让模型写一版用例（**不落盘**，交回界面给人改）。 */
    @PostMapping("/api/node/cases/agent")
    public Map<String, Object> casesAgent() {
        FlowService flow = session.flow();
        flow.requireReachable(Node.CASES, "让 AI 写用例");
        String requirement = flow.state().fact(Node.PROBE, "requirement");
        if (requirement.isBlank()) {
            throw ApiException.badRequest("需求还是空的：用例照需求写。先在 ② 把需求写上");
        }
        com.specflow.core.EventProgress progress =
                new com.specflow.core.EventProgress(session.events(), Node.CASES.key());
        try (AutoCloseable ignored = com.specflow.core.Jobs.register(progress)) {
            com.specflow.agent.CasesAgent agent = new com.specflow.agent.CasesAgent(
                    com.specflow.core.Llm.from(session.project()), progress);
            java.util.Optional<Map<String, Object>> probe = readProbeFile(flow.state().id());
            List<com.specflow.agent.CasesAgent.One> generated = agent.generate(requirement,
                    flow.state().fact(Node.PROBE, "design"),
                    probe.map(one -> lines(one.get("files"))).orElse(List.of()),
                    probe.map(one -> lines(one.get("context"))).orElse(List.of()));
            List<Map<String, Object>> cases = new ArrayList<>();
            for (com.specflow.agent.CasesAgent.One one : generated) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", one.id());
                item.put("what", one.what());
                item.put("tags", one.tags());
                item.put("expect", one.expect());
                cases.add(item);
            }
            return Map.of("cases", cases, "tags", com.specflow.test.CaseList.TAGS);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** ④ 跑开发：先拍快照，再让开发 Agent 在白名单内改真项目。 */
    @PostMapping("/api/node/dev/run")
    public Map<String, Object> devRun() {
        FlowService flow = session.flow();
        flow.requireReachable(Node.DEV, "跑开发");
        Map<String, Object> probe = readProbeFile(flow.state().id()).orElseThrow(() ->
                ApiException.badRequest("② 的产物还没有：开发 Agent 的 prompt 与写白名单都来自它"));
        List<String> changes = lines(probe.get("changes"));
        if (changes.isEmpty()) {
            throw ApiException.badRequest("② 还没挑出「要改的文件」：写白名单就是它，"
                    + "空名单等于让它对着整个项目猜该动哪儿");
        }
        String prompt = String.valueOf(probe.getOrDefault("prompt", ""));
        // ② 的另外两样产物也要真的喂给 ④：files 告诉它先读哪儿、context 告诉它为什么相关
        List<String> files = lines(probe.get("files"));
        List<String> context = lines(probe.get("context"));
        String feedback = flow.state().fact(Node.DEV, "feedback");
        String runId = session.runs().start(Node.DEV, "dev-run", Map.of(), progress -> {
            com.specflow.flow.Snapshot snapshot = com.specflow.flow.Snapshot.capture(
                    session.project(), changes.stream().map(one ->
                            session.project().root().resolve(one)).toList());
            if (snapshot != null) {
                snapshot.hold();
                progress.warn("回滚点已拍好（" + snapshot.files() + " 个文件）：" + snapshot.summarize());
            }
            com.specflow.agent.Dev dev = new com.specflow.agent.Dev(session.project(),
                    com.specflow.core.Llm.from(session.project()), progress,
                    new com.specflow.agent.EventWatcher(session.events(), Node.DEV.key()), 40);
            com.specflow.agent.Dev.Result result = dev.run(prompt, changes, files, context, feedback);
            flow.note(Node.DEV, Map.of(
                    "compilePassed", String.valueOf(result.compilePassed()),
                    "compileStatus", result.compilePassed() ? "PASSED" : "FAILED",
                    "submits", String.valueOf(result.submits())));
            if (!result.compilePassed()) {
                progress.fail("这一段开发没交出编译通过的代码（" + result.status() + "）："
                        + result.reason(), result.compileOutput());
                return;
            }
            progress.done("开发这一段完成：改了 " + changes.size() + " 个文件、交差 "
                            + result.submits() + " 次、编译通过",
                    Map.of("compilePassed", true, "rounds", result.rounds(),
                            "modelCalls", result.last() == null ? 0 : result.last().modelCalls()));
        });
        return accepted(runId, Node.DEV);
    }

    /** ④ 回滚这一次改动。 */
    @PostMapping("/api/node/dev/rollback")
    public Map<String, Object> devRollback() {
        FlowService flow = session.flow();
        flow.requireReachable(Node.DEV, "回滚改动");
        com.specflow.flow.Snapshot snapshot = com.specflow.flow.Snapshot.latest(session.project());
        if (snapshot == null) {
            throw ApiException.conflict("现在没有可回滚的快照：④ 跑过之后才有回滚点");
        }
        com.specflow.flow.Snapshot.Report report = snapshot.rollback();
        session.events().warn(Node.DEV.key(), "已回滚这一次改动（" + report.files() + " 个文件）："
                + report.summarize());
        return Map.of("ok", report.ok(), "files", report.files(),
                "problems", report.problems(), "summary", report.summarize());
    }

    // ---------- ⑤ 测试 ----------

    /** ⑤ 跑测试：拍副本 → 起容器 → 逐条验 → 结论写回清单。 */
    @PostMapping("/api/node/test/run")
    public Map<String, Object> testRun() {
        FlowService flow = session.flow();
        flow.requireReachable(Node.TEST, "跑测试");
        com.specflow.test.CaseList list = caseList();
        if (list.count() == 0) {
            throw ApiException.badRequest("③ 的用例清单是空的：测试就是照它逐条验的");
        }
        return startTestRun(list, "");
    }

    /**
     * <b>重试这一条</b>：把一条已经到终态的用例改回 {@code PENDING}，再起一轮只验它的运行。
     *
     * <p>为什么值得单独一个入口：⑤ 的每用例预算会把"验不动的那一条"变成 {@code SKIPPED}，
     * 而不是把整轮烧光。用户看到某条被跳过、又把环境修好了（比如换了镜像），
     * 他想做的就是"再验这一条"——不该被迫把 12 条全部重跑一遍。
     */
    @PostMapping("/api/node/test/case/retry")
    public Map<String, Object> testRetryCase(@RequestBody Map<String, Object> body) {
        String id = text(body, "id");
        if (id.isBlank()) {
            throw ApiException.badRequest("要重试哪一条？请求体里带上用例编号（比如 {\"id\":\"C3\"}）");
        }
        FlowService flow = session.flow();
        flow.requireReachable(Node.TEST, "重试这一条");
        com.specflow.test.CaseList list = caseList();
        String wanted = id.strip();
        boolean exists = list.cases().stream().anyMatch(one -> one.id().equals(wanted));
        if (!exists) {
            throw ApiException.badRequest("清单里没有编号 " + wanted + " 的用例（现在有：" + list.ids() + "）");
        }
        // **重试要有上限**：跳过可以回来重做，但不能无限重做——否则"一条用例反复重试"
        // 会把整轮预算和你的时间一起吃掉。计数落在流程产物里（`flow.json` 的 facts），
        // 界面上如实说还剩几次。
        String key = "retries:" + wanted;
        String raw = flow.state().fact(Node.TEST, key);
        int used = 0;
        try {
            used = raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw.strip());
        } catch (NumberFormatException ignored) {
            used = 0;
        }
        if (used >= MAX_RETRIES_PER_CASE) {
            throw ApiException.conflict(wanted + " 已经重试过 " + used + " 次了（上限 " + MAX_RETRIES_PER_CASE
                    + "）：再重试下去也只会烧轮次。要么改 ③ 的用例/期望，要么在 ⑤ 里给它写 ERROR 说明卡在哪，"
                    + "把这一轮收场。");
        }
        flow.note(Node.TEST, Map.of(key, String.valueOf(used + 1)));
        list.reopen(wanted);
        session.events().warn(Node.TEST.key(), "把 " + wanted + " 改回 PENDING：这一轮只验它（其余用例不动）"
                + "（第 " + (used + 1) + "/" + MAX_RETRIES_PER_CASE + " 次重试）");
        return startTestRun(list, wanted);
    }

    /** 单条用例最多重试几次（再多就是在烧轮次，而不是在推进）。 */
    private static final int MAX_RETRIES_PER_CASE = 3;

    /** 起一轮测试（两条入口共用：整轮跑 / 单条重试 / 从断点续跑）。 */
    private Map<String, Object> startTestRun(com.specflow.test.CaseList list, String focus) {
        FlowService flow = session.flow();
        // 同一个旋钮：容器起、单条命令、整轮，都按「这一步我愿意等多久」走
        long wait = com.specflow.env.Budgets.secondsFrom(pullTimeout());
        String flowId = flow.state().id();
        java.nio.file.Path checkpointFile = session.layout().checkpoint(flowId);
        String runId = session.runs().start(Node.TEST,
                focus.isBlank() ? "test-run" : "test-retry-" + focus, Map.of(), progress -> {
                    // 运行级断点：跑到哪、下一步干什么（存"状态 + 下一步"，不存对话历史——
                    // 那是两万字符一条的东西，存下来就是第二份真相。抄的是 LangGraph 最省的那一档
                    // durability="exit"：一步做完才落一次）
                    Runnable saveCheckpoint = () -> com.specflow.flow.Checkpoint.write(checkpointFile,
                            new com.specflow.flow.Checkpoint(Node.TEST.key(), "current",
                                    com.specflow.flow.Checkpoint.now(),
                                    "接着验还没到终态的那几条（" + list.pending() + " 条）",
                                    list.cases().stream()
                                            .filter(one -> one.status() != com.specflow.test.CaseList.Status.PENDING)
                                            .map(com.specflow.test.CaseList.Case::id).toList(),
                                    progress.elapsedSeconds() > 0 ? (int) progress.elapsedSeconds() : 0,
                                    "RUNNING"));
                    saveCheckpoint.run();
                    try (com.specflow.test.TestSandbox sandbox = com.specflow.test.TestSandbox.prepare(
                            session.project(), "t" + progress.elapsedSeconds() + "-" + progress.hashCode(),
                            progress, wait)) {
                        // 开工前先问一句：这个容器能构建吗？缺东西就当场失败并指名 ①
                        //（实测"让它自己装"那条路花了 28 轮 / 15 分钟）
                        com.specflow.test.Preflight.Verdict preflight = com.specflow.test.Preflight.check(
                                sandbox.shell(), session.project().compile());
                        if (!preflight.ok()) {
                            com.specflow.test.Preflight.report(progress, preflight);
                            flow.note(Node.TEST, Map.of(Facts.TEST_RUN, "true", Facts.FAILED, "true"));
                            com.specflow.flow.Checkpoint.write(checkpointFile, new com.specflow.flow.Checkpoint(
                                    Node.TEST.key(), "current", com.specflow.flow.Checkpoint.now(),
                                    "修好环境（改 env.yaml 的 image 或 init）再回来", List.of(), 0, "BLOCKED"));
                            throw new IllegalStateException("预检没过：" + preflight.message());
                        }
                        int perCase = com.specflow.test.CaseBudget.DEFAULT_PER_CASE;
                        int totalRounds = Math.max(60, list.count() * perCase + 20);
                        com.specflow.test.TestAgent agent = new com.specflow.test.TestAgent(
                                com.specflow.core.Llm.from(session.project()), progress,
                                new com.specflow.agent.EventWatcher(session.events(), Node.TEST.key()),
                                sandbox.shell(), list, session.approvals(), sandbox.root(),
                                flow.state().fact(Node.PROBE, "requirement"), totalRounds, perCase)
                                .withContainerRoot(com.specflow.env.EnvConfigLoader.load(session.project().layout())
                                        .map(com.specflow.env.EnvConfig::workdir).orElse("/work"))
                                .withWaitSeconds(wait)
                                .withFocus(focus)
                                .withOnCaseStart(() -> sandbox.reset(progress))
                                .withOnCaseDone(() -> {
                                    try {
                                        saveCheckpoint.run();
                                    } catch (RuntimeException ignored) {
                                        // 断点是提示，不是真相：落不下也不影响验证
                                    }
                                });
                        com.specflow.test.TestAgent.Result result = agent.run();
                        long skipped = list.cases().stream()
                                .filter(one -> one.status() == com.specflow.test.CaseList.Status.SKIPPED).count();
                        flow.note(Node.TEST, Map.of(Facts.TEST_RUN, "true",
                                Facts.CASE_PENDING, String.valueOf(list.pending()),
                                "verdicts", String.valueOf(result.verdicts()),
                                "skipped", String.valueOf(skipped)));
                        if (!result.ok()) {
                            progress.fail("这一段测试没验完（" + result.status() + "）：" + result.reason()
                                    + "（已写下 " + result.verdicts() + " 条结论）", null);
                            com.specflow.flow.Checkpoint.write(checkpointFile, new com.specflow.flow.Checkpoint(
                                    Node.TEST.key(), "current", com.specflow.flow.Checkpoint.now(),
                                    "点「跑测试」从断点接着验（已到终态的不会重验）",
                                    list.cases().stream()
                                            .filter(one -> one.status() != com.specflow.test.CaseList.Status.PENDING)
                                            .map(com.specflow.test.CaseList.Case::id).toList(),
                                    result.rounds(), result.status()));
                            return;
                        }
                        com.specflow.flow.Checkpoint.clear(checkpointFile);
                        // 收场那句话要把「跳过几条」说出来：Dify 的 workflow_finished 把
                        // 「兜底过/部分成功」单列成一个状态与计数（task_entities.py:233-254），
                        // 而不是笼统报"完成"——我们照这个口径：全到终态，但跳过 N 条要显眼
                        String done = skipped == 0
                                ? "测试验完了：清单 " + list.count() + " 条全部到终态"
                                : "测试验完了，但**有 " + skipped + " 条被跳过**（每用例预算用尽）："
                                + "它们不是结论，可以逐条点「重试这一条」";
                        progress.done(done,
                                Map.of("verdicts", result.verdicts(), "skipped", skipped,
                                        "partial", skipped > 0, "cases", list.cases(),
                                        "rounds", result.rounds(), "sandbox", sandbox.root().toString()));
                    }
                });
        return accepted(runId, Node.TEST);
    }

    /** ⑤ 的断点：跑到哪、下一步干什么（没有就是 {@code {"present":false}}）。 */
    @GetMapping("/api/node/test/checkpoint")
    public Map<String, Object> testCheckpoint() {
        com.specflow.flow.Checkpoint checkpoint = com.specflow.flow.Checkpoint.read(
                session.layout().checkpoint(session.flow().state().id()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("present", checkpoint != null);
        if (checkpoint != null) {
            body.put("summary", checkpoint.summarize());
            body.put("next", checkpoint.next());
            body.put("status", checkpoint.status());
            body.put("doneCases", checkpoint.doneCases());
            body.put("at", checkpoint.at());
        }
        return body;
    }

    /**
     * 从断点接着跑。
     *
     * <p>它<b>不做任何魔法</b>：已到终态的用例本来就不会再被验（{@code TestAgent} 只认 PENDING），
     * 所以"接着跑"就是再跑一次。这个入口存在的意义是<b>把话说清</b>——
     * 让用户看到"上次停在哪儿、已经完成几条"，而不是对着一个 409 猜。
     */
    @PostMapping("/api/node/test/resume")
    public Map<String, Object> testResume() {
        FlowService flow = session.flow();
        flow.requireReachable(Node.TEST, "从断点接着跑");
        com.specflow.test.CaseList list = caseList();
        if (list.count() == 0) {
            throw ApiException.badRequest("③ 的用例清单是空的：没有东西可接着验");
        }
        com.specflow.flow.Checkpoint checkpoint = com.specflow.flow.Checkpoint.read(
                session.layout().checkpoint(flow.state().id()));
        session.events().say(Node.TEST.key(), "info", checkpoint == null
                ? "没有找到断点：按清单从头验（已到终态的本来就不会重验）"
                : "从断点接着跑 —— " + checkpoint.summarize());
        return startTestRun(list, "");
    }

    /** ⑤ 待回答的高危命令。 */
    @GetMapping("/api/node/test/approvals")
    public Map<String, Object> testApprovals() {
        List<Map<String, Object>> items = session.approvals().pending();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvals", items);
        body.put("waitSeconds", com.specflow.test.Approvals.WAIT_SECONDS);
        body.put("note", items.isEmpty() ? "现在没有等你回答的命令"
                : "有 " + items.size() + " 条高危命令停下来等你点 y / n");
        return body;
    }

    /** ⑤ 回答一条待审批。 */
    @PostMapping("/api/node/test/approve")
    public Map<String, Object> testApprove(@RequestBody Map<String, Object> body) {
        String id = text(body, "id");
        if (id.isBlank()) {
            throw ApiException.badRequest("要回答哪一条？请求体里带上 id");
        }
        boolean allow = Boolean.TRUE.equals(body.get("allow"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("allow")));
        if (!session.approvals().answer(id, allow)) {
            throw ApiException.conflict("这一条待审批已经不在了（可能超时被撤掉了）：" + id);
        }
        session.events().warn(Node.TEST.key(), "你对一条高危命令回答了「" + (allow ? "允许" : "不允许") + "」");
        return Map.of("id", id, "allow", allow, "answered", true);
    }

    /** ⑤ 提交：清单到终态 + 一段总结（总结回喂给 ④）。 */
    @PostMapping("/api/node/test/submit")
    public Map<String, Object> testSubmit(@RequestBody Map<String, Object> body) {
        FlowService flow = session.flow();
        flow.requireReachable(Node.TEST, "提交测试结果");
        String summary = text(body, "summary");
        if (summary.isBlank()) {
            throw ApiException.badRequest("提交要附一段总结：它会回喂给开发，写清「现在还有什么缺陷」");
        }
        for (String word : List.of("用例", "测试用例")) {
            if (summary.contains(word)) {
                throw ApiException.badRequest("这段总结里出现了「" + word + "」：它会原样回喂给开发，"
                        + "而开发看到这个词就会去猜断言、照着那一次测量改——"
                        + "「改到那条断言恰好过为止」看起来和真的修好了完全一样。"
                        + "换个说法：只说「现在还有什么缺陷、什么行为不对」");
            }
        }
        List<String> problems = caseList().problems();
        if (!problems.isEmpty()) {
            throw ApiException.conflict("清单还过不了验收（" + problems.size() + " 项），不能提交："
                    + String.join("；", problems));
        }
        com.specflow.core.Atomic.write(session.layout().summary(flow.state().id()), summary + "\n");
        flow.note(Node.TEST, Map.of("submitted", "true", "summary", summary));
        flow.note(Node.DEV, Map.of("feedback", summary));
        session.events().say(Node.TEST.key(), "info", "已提交：清单每条都有结论，总结 "
                + summary.length() + " 字（它会回喂给开发）");
        return Map.of("summary", summary, "flow", flow.view());
    }

    /** ⑤ 接受并完成（闭环）。 */
    @PostMapping("/api/node/test/accept")
    public Map<String, Object> testAccept() {
        FlowService flow = session.flow();
        flow.freeze(Node.TEST);
        session.events().say(Node.TEST.key(), "info", "你接受了这一版：工作流闭环完成");
        return Map.of("done", true, "flow", flow.view());
    }

    /** ⑤ 再优化一轮：把上一轮的总结当目标回喂 ④，③ 不再生成。 */
    @PostMapping("/api/node/test/optimize")
    public Map<String, Object> testOptimize() {
        FlowService flow = session.flow();
        flow.requireReachable(Node.TEST, "再优化一轮");
        String feedback = flow.state().fact(Node.DEV, "feedback");
        if (feedback.isBlank()) {
            feedback = com.specflow.core.Atomic.read(session.layout().summary(flow.state().id()), "").strip();
        }
        if (feedback.isBlank()) {
            throw ApiException.conflict("还没有「这一轮要改什么」：先把测试跑完并提交一段总结");
        }
        Map<String, Object> probe = readProbeFile(flow.state().id()).orElseThrow(() ->
                ApiException.badRequest("② 的产物没有了：这一轮回到 ④ 重跑，白名单来自 ②"));
        List<String> changes = lines(probe.get("changes"));
        String prompt = String.valueOf(probe.getOrDefault("prompt", ""));
        // ② 的另外两样产物也要真的喂给 ④：files 告诉它先读哪儿、context 告诉它为什么相关
        List<String> files = lines(probe.get("files"));
        List<String> context = lines(probe.get("context"));
        String goal = feedback;   // lambda 里要用它，而 feedback 上面被重新赋过值（不是 effectively final）
        String runId = session.runs().start(Node.TEST, "optimize", Map.of(), progress -> {
            com.specflow.flow.Snapshot snapshot = com.specflow.flow.Snapshot.capture(
                    session.project(), changes.stream().map(one ->
                            session.project().root().resolve(one)).toList());
            if (snapshot != null) {
                snapshot.hold();
            }
            com.specflow.agent.Dev dev = new com.specflow.agent.Dev(session.project(),
                    com.specflow.core.Llm.from(session.project()), progress,
                    new com.specflow.agent.EventWatcher(session.events(), Node.TEST.key()), 40);
            com.specflow.agent.Dev.Result devResult = dev.run(prompt, changes, files, context, goal);
            if (!devResult.compilePassed()) {
                progress.fail("优化这一轮开发没过编译：" + devResult.reason(), devResult.compileOutput());
                return;
            }
            progress.warn("开发这一段过了编译，接着按同一份清单再验一遍");
            try (com.specflow.test.TestSandbox sandbox = com.specflow.test.TestSandbox.prepare(
                    session.project(), "o" + progress.elapsedSeconds() + "-" + progress.hashCode(), progress)) {
                com.specflow.test.CaseList list = caseList();
                com.specflow.test.TestAgent agent = new com.specflow.test.TestAgent(
                        com.specflow.core.Llm.from(session.project()), progress,
                        new com.specflow.agent.EventWatcher(session.events(), Node.TEST.key()),
                        sandbox.shell(), list, session.approvals(), sandbox.root(),
                        flow.state().fact(Node.PROBE, "requirement"), 60);
                com.specflow.test.TestAgent.Result result = agent.run();
                if (!result.ok()) {
                    progress.fail("优化这一轮的测试没验完（" + result.status() + "）：" + result.reason(), null);
                    return;
                }
                progress.done("优化这一轮完成：清单 " + list.count() + " 条全部到终态",
                        Map.of("verdicts", list.count(), "cases", list.cases()));
            }
        });
        return accepted(runId, Node.TEST);
    }

    /** ④ 开工要用的那几样：写白名单、可读文件、prompt、上一轮的反馈、编译命令。 */
    @GetMapping("/api/node/dev/view")
    public Map<String, Object> devView() {
        FlowService flow = session.flow();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> probe = readProbeFile(flow.state().id()).orElse(Map.of());
        body.put("whitelist", lines(probe.get("changes")));
        body.put("readable", lines(probe.get("files")));
        body.put("prompt", String.valueOf(probe.getOrDefault("prompt", "")));
        body.put("context", lines(probe.get("context")));
        body.put("feedback", flow.state().fact(Node.DEV, "feedback"));
        body.put("compileCommand", session.project().compile());
        body.put("compilePassed", flow.state().flag(Node.DEV,
                com.specflow.flow.Facts.COMPILE_PASSED));
        body.put("probeFile", session.layout().show(session.layout().probe(flow.state().id())));
        return body;
    }
    // ---------- 内部 ----------

    /** 一个动作受理之后的回执（界面拿 `cursor` 继续轮询）。 */
    private Map<String, Object> accepted(String runId, Node node) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("node", node.key());
        body.put("runId", runId);
        body.put("cursor", Math.max(0, session.events().next() - 1));
        body.put("running", session.runs().running());
        return body;
    }

    /** 读 ② 的产物（没有就是空）。 */
    private java.util.Optional<Map<String, Object>> readProbeFile(String flowId) {
        String raw = com.specflow.core.Atomic.read(session.layout().probe(flowId), "");
        if (raw.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(com.specflow.core.Json.map(raw));
        } catch (RuntimeException e) {
            throw ApiException.conflict("② 的产物读不出来（" + e.getMessage() + "）："
                    + session.layout().show(session.layout().probe(flowId)) + " 可能被手改坏了");
        }
    }

    private Map<String, Object> flowView() {
        FlowService flow = session.flow();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeView one : flow.view()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("key", one.node().key());
            node.put("title", one.node().title());
            node.put("state", one.state().name());
            node.put("blockedReason", one.blockedReason());
            node.put("facts", one.facts());
            // 这一格的按钮（`flow/Actions` 那一份声明）：**必须随 view 下发**，否则界面一个按钮都没有。
            // 原来这里漏了这一行：`NodeView` 里有 actions、`Actions.TABLE` 也写了，可路由没往外掏——
            // 现象是五个页面全都"只有状态、没有按钮"，而且不报任何错。
            node.put("actions", one.actions());
            nodes.add(node);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowId", flow.state().id());
        body.put("running", session.runs().running());
        body.put("stage", session.runs().stage());
        body.put("cursor", flow.events().next());
        body.put("nodes", nodes);
        return body;
    }

    private Map<String, Object> eventsView(long from) {
        EventLog log = session.events();
        List<Event> events = log.read(from, 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", session.runs().running());
        body.put("stage", session.runs().stage());
        body.put("events", events);
        body.put("next", events.isEmpty() ? from : events.get(events.size() - 1).id() + 1);
        return body;
    }

    private Map<String, Object> readProbe() {
        Path file = session.layout().probe(session.flow().state().id());
        String raw = com.specflow.core.Atomic.read(file, "");
        Map<String, Object> body = new LinkedHashMap<>();
        if (raw.isBlank()) {
            body.put("files", List.of());
            body.put("changes", List.of());
            body.put("context", List.of());
            body.put("prompt", "");
        } else {
            body.putAll(Json.map(raw));
        }
        body.put("requirement", session.flow().state().fact(Node.PROBE, "requirement"));
        body.put("design", session.flow().state().fact(Node.PROBE, "design"));
        return body;
    }

    private com.specflow.test.CaseList caseList() {
        return new com.specflow.test.CaseList(session.layout().cases(session.flow().state().id()));
    }

    private int pullTimeout() {
        String raw = session.flow().state().fact(Node.ENV, "pullTimeoutMinutes");
        try {
            return raw.isBlank() ? 30 : Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /** 把编译命令写进 `project.yaml` 的 `build.compile`（只换那一行，注释与别的段不动）。 */
    private String writeCompile(Project project, String command) {
        Path file = project.layout().projectYaml();
        String existing = com.specflow.core.Atomic.read(file, "");
        String updated = CompileYaml.withCompileCommand(existing, command);
        if (updated.equals(existing)) {
            return "";
        }
        com.specflow.core.Atomic.write(file, updated);
        return project.layout().show(file);
    }

    private static Node parseNode(String key) {
        try {
            return Node.of(key);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private static String text(Map<String, Object> body, String field) {
        return body == null ? "" : string(body.get(field));
    }

    private static String string(Object raw) {
        return raw == null ? "" : String.valueOf(raw).strip();
    }

    private static Integer number(Map<String, Object> body, String field) {
        Object raw = body == null ? null : body.get(field);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return raw == null ? null : Integer.valueOf(String.valueOf(raw).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把"一串"变成若干条：<b>认得 List，也认得字符串</b>。
     *
     * <p>为什么必须认得字符串：界面上「上下文」那一栏是 textarea，提交上来的是<b>一整段文本</b>
     * （一行一条）。原来这里只认 {@code List}，于是字符串直接落成空表 ——
     * 用户每保存一次，就把上下文<b>静默清空一次</b>。这条是核对"文本替换那条链路"时问出来的真 bug。
     */
    private static List<String> lines(Object raw) {
        if (raw instanceof String text) {
            return java.util.Arrays.stream(text.split("\\R"))
                    .map(String::strip).filter(one -> !one.isEmpty()).toList();
        }
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (Object one : items) {
            String text = string(one);
            if (!text.isBlank()) {
                found.add(text);
            }
        }
        return List.copyOf(found);
    }

    /** 这一层用得到的那点编译命令写入逻辑（只换 `build.compile` 那一行）。 */
    static final class CompileYaml {

        private CompileYaml() {
        }

        static String withCompileCommand(String source, String command) {
            String text = source == null ? "" : source;
            String eol = text.contains("\r\n") ? "\r\n" : "\n";
            List<String> rows = new ArrayList<>(List.of(text.split("\r?\n", -1)));
            String quoted = '"' + command.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            int buildAt = -1;
            for (int at = 0; at < rows.size(); at++) {
                String row = rows.get(at);
                if (!row.isEmpty() && !Character.isWhitespace(row.charAt(0)) && row.startsWith("build:")) {
                    buildAt = at;
                    break;
                }
            }
            if (buildAt < 0) {
                if (!rows.isEmpty() && !rows.get(rows.size() - 1).isBlank()) {
                    rows.add("");
                }
                rows.add("build:");
                rows.add("  compile: " + quoted);
                return join(rows, eol);
            }
            int end = rows.size();
            for (int at = buildAt + 1; at < rows.size(); at++) {
                String row = rows.get(at);
                if (row.isBlank() || row.stripLeading().startsWith("#")) {
                    continue;
                }
                if (!Character.isWhitespace(row.charAt(0))) {
                    end = at;
                    break;
                }
            }
            for (int at = buildAt + 1; at < end; at++) {
                if (rows.get(at).strip().startsWith("compile:")) {
                    rows.set(at, "  compile: " + quoted);
                    return join(rows, eol);
                }
            }
            rows.add(end, "  compile: " + quoted);
            return join(rows, eol);
        }

        private static String join(List<String> rows, String eol) {
            String joined = String.join(eol, rows);
            return joined.endsWith(eol) ? joined : joined + eol;
        }
    }
}
