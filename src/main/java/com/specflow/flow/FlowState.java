package com.specflow.flow;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code flow.json} 的形状：五节点状态 + 每一步的产物（facts）。
 *
 * <p><b>facts 是扁平字符串表</b>（`docs/API.md` §2）：值只有 {@code "true"} / {@code "false"} /
 * 数字 / 短文本。为什么不是任意 JSON：界面把它当徽章读（一个键一枚），值一旦可以是长文档，
 * 它就变成了第二份「产物」——而产物有它自己的落点（probe.json / cases.json / summary.md）。
 *
 * <p>{@link #states()} 里 {@code DONE} 是记下来的「做过并冻结了」，{@code READY}/{@code LOCKED}
 * 是从前置推出来的（算法在 {@code FlowService.refresh}，只此一处）。
 */
public final class FlowState {

    /** 一栏值的上限：facts 是徽章的值，不是文档（长文本有它自己的落点）。 */
    private static final int MAX_VALUE = 500;

    private final String id;
    private final Map<Node, State> states = new EnumMap<>(Node.class);
    private final Map<Node, Map<String, String>> facts = new EnumMap<>(Node.class);

    private FlowState(String id) {
        this.id = id == null ? "" : id;
    }

    /** 新开一条：① 可以动，其余锁着。 */
    public static FlowState fresh(String id) {
        FlowState state = new FlowState(id);
        for (Node node : Node.values()) {
            state.states.put(node, node.previous() == null ? State.READY : State.LOCKED);
        }
        return state;
    }

    public String id() {
        return id;
    }

    public State stateOf(Node node) {
        return states.getOrDefault(node, State.LOCKED);
    }

    public void setState(Node node, State state) {
        states.put(node, state == null ? State.LOCKED : state);
    }

    /** 某一格上某一栏的值；没有 → 空串。 */
    public String fact(Node node, String key) {
        Map<String, String> bag = facts.get(node);
        if (bag == null || key == null) {
            return "";
        }
        String value = bag.get(key);
        return value == null ? "" : value;
    }

    /** {@code "true"}（大小写不管）才算真——界面写下来的也是这个形状。 */
    public boolean flag(Node node, String key) {
        return "true".equalsIgnoreCase(fact(node, key).strip());
    }

    /** 数字那一栏（{@code caseCount} 之类）；没有或者不是数字 → 0。 */
    public long number(Node node, String key) {
        try {
            return Long.parseLong(fact(node, key).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 记几栏产物（同一格多次记是合并；后写的盖前面的）。 */
    public void note(Node node, Map<String, String> values) {
        if (node == null || values == null || values.isEmpty()) {
            return;
        }
        Map<String, String> bag = facts.computeIfAbsent(node, key -> new LinkedHashMap<>());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            bag.put(entry.getKey().strip(), shortText(entry.getValue()));
        }
    }

    /** 一格上的产物全清掉（回退作废用它，{@link FlowService#gotoNode}）。 */
    public void clearFacts(Node node) {
        facts.remove(node);
    }

    public Map<Node, State> states() {
        return Collections.unmodifiableMap(new EnumMap<>(states));
    }

    public Map<Node, Map<String, String>> facts() {
        Map<Node, Map<String, String>> copy = new EnumMap<>(Node.class);
        facts.forEach((node, bag) -> copy.put(node, Map.copyOf(bag)));
        return Collections.unmodifiableMap(copy);
    }

    /** 一句话摘要（留档那一行用）。 */
    public String summarize() {
        StringBuilder text = new StringBuilder("流程 ").append(id).append("：");
        for (Node node : Node.values()) {
            if (text.charAt(text.length() - 1) != '：') {
                text.append(' ');
            }
            text.append(node.key()).append('=').append(stateOf(node).name());
        }
        return text.toString();
    }

    /** 值一律按短文本收：换行压平、超长截断（见 {@link #MAX_VALUE}）。 */
    private static String shortText(String value) {
        String flat = value == null ? "" : value.strip().replaceAll("\\R", " ");
        return flat.length() <= MAX_VALUE ? flat : flat.substring(0, MAX_VALUE) + "…";
    }

    @Override
    public String toString() {
        return summarize();
    }
}
