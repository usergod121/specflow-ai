package com.specflow.web;

import com.specflow.core.Atomic;
import com.specflow.core.EventLog;
import com.specflow.core.Json;
import com.specflow.core.Layout;
import com.specflow.core.Project;
import com.specflow.flow.FlowService;
import com.specflow.flow.Runs;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>现在开着哪个项目</b>，以及这个项目上那几样东西（流程、事件流、后台运行）。
 *
 * <p>为什么要这么一个对象：换项目是这一版里唯一会「换掉一整套状态」的动作
 * （流程、事件流、正在跑的那一段都跟着换），把它收在一个地方，
 * 才不会出现「文件树是新项目、流程还是旧项目」这种读了一半的状态。
 *
 * <p>三条口径：
 * <ol>
 *   <li><b>没打开项目时，只有欢迎页那几条能答</b>，别的接口一律 409 + 一句人话；</li>
 *   <li><b>有活正在跑就不许换项目</b>：换掉之后那次运行会把文件写到旧项目、进度也没人再看；</li>
 *   <li>换项目时把旧项目收干净（关线程池、把还等着的审批答掉）。</li>
 * </ol>
 */
@Component
public class Session implements org.springframework.boot.ApplicationRunner {

    /** 最近打开的项目（放在用户目录下，不进项目、不进版本库）。 */
    private static final Path RECENT = Path.of(System.getProperty("user.home"), ".specflow", "recent.json");

    private Project project;
    private FlowService flow;
    private Runs runs;
    private final com.specflow.test.Approvals approvals = new com.specflow.test.Approvals();

    /**
     * 启动时把命令行给的项目打开（{@code specflow web -p <目录>}）。
     *
     * <p>为什么放在这里而不是 {@code App}：`App` 只管把服务拉起来，而"当前开着哪个项目"
     * 是 Session 的状态。启动参数先放在 {@code App.initialProject}，这里读一次——
     * 不读的话，`-p` 传了等于没传（实测：界面停在欢迎页，而命令行明明给了项目）。
     */
    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        Path initial = App.initialProject;
        if (initial != null) {
            try {
                open(initial);
            } catch (RuntimeException e) {
                // 打不开不是致命错：界面会停在欢迎页，用户自己挑一个即可
                org.slf4j.LoggerFactory.getLogger(Session.class)
                        .warn("命令行给的项目打不开（{}）：{}", initial, e.getMessage());
            }
        }
    }

    public synchronized Project project() {
        if (project == null) {
            throw ApiException.conflict("还没有打开项目：先在欢迎页选一个目录");
        }
        return project;
    }

    public synchronized boolean opened() {
        return project != null;
    }

    public synchronized Layout layout() {
        return project().layout();
    }

    public synchronized FlowService flow() {
        project();
        return flow;
    }

    public synchronized Runs runs() {
        project();
        return runs;
    }

    public synchronized EventLog events() {
        return flow().events();
    }

    /**
     * 高危命令的审批口（一个项目一份，见 {@code test.Approvals}）。
     *
     * <p>它是**全局唯一一份**、不随项目换：等在那上面的线程正跑在某个项目上，
     * 换项目时把待审批答掉（按拒）比换一份新的更安全——换新的会让那条线程永远等下去。
     */
    public com.specflow.test.Approvals approvals() {
        return approvals;
    }

    /** 打开一个项目（换项目也走它）。 */
    public synchronized Map<String, Object> open(Path root) {
        Path target = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            throw ApiException.badRequest("不是一个目录：" + target);
        }
        if (project != null && project.root().equals(target)) {
            return state();
        }
        if (runs != null && runs.running()) {
            throw ApiException.conflict("有活正在跑，等它结束（或者先点停下）再换项目");
        }
        closeQuietly();
        Project opened = Project.open(target);
        if (!opened.prepared()) {
            opened.bootstrap();
        }
        this.project = opened;
        this.flow = new FlowService(opened);
        this.runs = new Runs(flow);
        remember(target);
        return state();
    }

    /** 界面上那一份「现在什么状态」（欢迎页与顶栏都问它）。 */
    public synchronized Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("open", project != null);
        if (project != null) {
            Path root = project.root();
            state.put("root", root.toString());
            state.put("project", Map.of(
                    "name", root.getFileName() == null ? root.toString() : root.getFileName().toString(),
                    "path", root.toString()));
            state.put("hasModel", project.apiKey() != null && !project.apiKey().isBlank());
            state.put("flowId", flow().state().id());
        }
        state.put("recent", recent());
        return state;
    }

    public List<Map<String, Object>> recent() {
        List<Map<String, Object>> found = new ArrayList<>();
        for (String row : Atomic.read(RECENT, "").split("\r?\n")) {
            String path = row.strip();
            if (path.isEmpty()) {
                continue;
            }
            Path one = Path.of(path);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", path);
            item.put("name", one.getFileName() == null ? path : one.getFileName().toString());
            item.put("exists", Files.isDirectory(one));
            found.add(item);
        }
        return List.copyOf(found);
    }

    public synchronized List<Map<String, Object>> forget(String path) {
        List<String> kept = new ArrayList<>();
        for (String row : Atomic.read(RECENT, "").split("\r?\n")) {
            String one = row.strip();
            if (!one.isEmpty() && !one.equals(path)) {
                kept.add(one);
            }
        }
        Atomic.write(RECENT, kept.isEmpty() ? "" : String.join("\n", kept) + "\n");
        return recent();
    }

    /** 目录浏览（欢迎页「挑目录」那一栏）。 */
    public List<Map<String, Object>> browse(String raw) {
        Path dir;
        if (raw == null || raw.isBlank()) {
            dir = Path.of(System.getProperty("user.home"));
        } else {
            dir = Path.of(raw).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                throw ApiException.badRequest("不是一个目录：" + dir);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .filter(one -> !one.getFileName().toString().startsWith("."))
                    .sorted()
                    .limit(200)
                    .forEach(one -> items.add(Map.of(
                            "path", one.toString(),
                            "name", one.getFileName().toString(),
                            "prepared", Files.isDirectory(one.resolve(Layout.DIR)))));
        } catch (IOException e) {
            throw ApiException.badRequest("读不了这个目录：" + e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", dir.toString());
        body.put("parent", dir.getParent() == null ? "" : dir.getParent().toString());
        body.put("dirs", items);
        return List.of(body);
    }

    private void remember(Path root) {
        List<String> all = new ArrayList<>();
        all.add(root.toString());
        for (Map<String, Object> one : recent()) {
            String path = String.valueOf(one.get("path"));
            if (!path.equals(root.toString()) && all.size() < 10) {
                all.add(path);
            }
        }
        Atomic.write(RECENT, String.join("\n", all) + "\n");
    }

    private void closeQuietly() {
        if (runs != null) {
            runs.close();
        }
        // 还等着人回答的审批先答掉（按拒）：那条线程正阻塞在它上面，
        // 不收的话它会挂满十分钟，而这期间项目已经换了
        approvals.clear();
        runs = null;
        flow = null;
        project = null;
    }

    /** 记一笔便于排错的现场（不要给界面看）。 */
    public String describe() {
        return project == null ? "（没打开项目）" : Json.write(state());
    }
}
