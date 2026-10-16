package com.specflow.flow;

import com.specflow.core.Atomic;
import com.specflow.core.Json;
import com.specflow.core.Layout;
import com.specflow.core.Project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code flow/current} 与 {@code flow/<id>/flow.json} 的读写。
 *
 * <p>落点只有 {@link Layout} 一处定义（不变量 3），写一律走 {@link Atomic}（不变量 6）：
 * 这两个文件是「这条流程现在到哪一步了」的唯一真相，写坏一半比没写更糟——
 * 用户打开它看到的是一份读不出来的状态，而没有任何地方告诉他这是崩溃留下的。
 *
 * <p>写指针的顺序不能反：<b>先有 flow.json，再写 current</b>。反过来的话，
 * 断电恰好卡在中间会留下一个指向不存在目录的指针，而这个坏法要等到下次打开项目才现形。
 */
public final class Store {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Project project;

    public Store(Project project) {
        if (project == null) {
            throw new IllegalArgumentException("流程存储要有一个项目");
        }
        this.project = project;
    }

    public Layout layout() {
        return project.layout();
    }

    /** 现在这条流程；盘上没有（或者指针指丢了）就新开一条并把指针写上。 */
    public FlowState current() {
        return open(currentId());
    }

    /** 现在这条流程的 id；没有就新开一条。 */
    public String currentId() {
        String pointer = Atomic.read(layout().current(), "").strip();
        int line = pointer.indexOf('\n');
        if (line >= 0) {
            pointer = pointer.substring(0, line).strip();
        }
        if (pointer.isEmpty() || !Files.isRegularFile(layout().flowJson(pointer))) {
            return newFlow().id();
        }
        return pointer;
    }

    /** 新开一条流程（`POST /api/flow/new`）：写一份空的 flow.json，然后把指针指过去。 */
    public FlowState newFlow() {
        String id = freshId();
        FlowState state = FlowState.fresh(id);
        save(state);
        Atomic.write(layout().current(), id + "\n");
        return state;
    }

    /** 读一条已有的流程；文件不在时给一份空的（**不猜内容**，也顺手不建目录）。 */
    public FlowState open(String id) {
        String clean = id == null ? "" : id.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("流程 id 是空的");
        }
        String text = Atomic.read(layout().flowJson(clean), "");
        if (text.isBlank() || "null".equals(text.strip())) {
            return FlowState.fresh(clean);
        }
        try {
            return parse(clean, text);
        } catch (RuntimeException e) {
            // 不静默、也不悄悄重来：这份文件是「现在到哪一步了」的唯一真相，
            // 按一份读不出来的文件往下走，用户看到的状态会是凭空来的
            throw new IllegalStateException("flow.json 读不出来（" + layout().flowJson(clean)
                    + "）：" + e.getMessage(), e);
        }
    }

    /** 存一份（原子写）。 */
    public void save(FlowState state) {
        if (state == null || state.id().isBlank()) {
            throw new IllegalArgumentException("要存的流程状态没有 id");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", state.id());
        Map<String, Object> states = new LinkedHashMap<>();
        state.states().forEach((node, value) -> states.put(node.key(), value.name()));
        root.put("states", states);
        Map<String, Object> facts = new LinkedHashMap<>();
        state.facts().forEach((node, bag) -> facts.put(node.key(), bag));
        root.put("facts", facts);
        Atomic.write(layout().flowJson(state.id()), Json.write(root) + "\n");
    }

    /** 盘上已有的流程 id（新的在前）——界面上的「历史流程」那一栏用得上。 */
    public List<String> ids() {
        Path dir = layout().flows();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        try (var children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .forEach(one -> found.add(one.getFileName().toString()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("流程目录读不出来：" + dir + "（" + e.getMessage() + "）", e);
        }
        found.sort(java.util.Comparator.reverseOrder());
        return List.copyOf(found);
    }

    private FlowState parse(String id, String text) {
        Map<String, Object> root = Json.map(text);
        FlowState state = FlowState.fresh(id);
        Object states = root.get("states");
        if (states instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Node node = Node.of(String.valueOf(entry.getKey()));
                state.setState(node, State.valueOf(String.valueOf(entry.getValue()).strip().toUpperCase(java.util.Locale.ROOT)));
            }
        }
        Object facts = root.get("facts");
        if (facts instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Node node = Node.of(String.valueOf(entry.getKey()));
                if (!(entry.getValue() instanceof Map<?, ?> bag)) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> one : bag.entrySet()) {
                    Object value = one.getValue();
                    values.put(String.valueOf(one.getKey()), value == null ? "" : String.valueOf(value));
                }
                state.note(node, values);
            }
        }
        return state;
    }

    /** 一个没用过的 id（秒级时间戳撞了就加序号：同一个人在同一个项目里连点两下是常事）。 */
    private String freshId() {
        String base = STAMP.format(LocalDateTime.now());
        String id = base;
        int attempt = 1;
        while (Files.exists(layout().flow(id))) {
            attempt++;
            id = base + "-" + attempt;
        }
        return id;
    }
}
