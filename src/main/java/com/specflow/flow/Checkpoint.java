package com.specflow.flow;

import com.specflow.core.Atomic;
import com.specflow.core.Json;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * <b>运行级断点</b>：这一次跑到哪、下一步该干什么。
 *
 * <h2>形状是抄来的，但只抄了最省的那一档</h2>
 * LangGraph 的 checkpointer 有"每个 superstep 全量快照 + 版本调度"那一套，它自己都被成本逼到
 * 另开一个 delta 通道（{@code libs/checkpoint/langgraph/checkpoint/base/__init__.py}）。
 * 我们用不到——它的代价来自"从版本差推导下一步"，而我们的下一步由<b>五站图 + 闸</b>显式决定。
 *
 * <p>所以这里只取它最省的那一档：<b>只在"一步做完"时落一次</b>（对应它 {@code durability="exit"}
 * 的语义，见 {@code libs/langgraph/langgraph/pregel/main.py}），并且存的是
 * <b>「状态 + 下一步该干什么」而不是状态历史链</b>。
 *
 * <h2>为什么不存消息历史</h2>
 * Temporal 那份研究里有一句很准的话：我们<b>已经付了 replay 九成的钱</b>——
 * 事件流 {@code id ≡ 行号}、工具调用 {@code callId} 成对、{@code Journal} 的
 * "有 BEGIN 没 END 就是被杀过"。缺的只有"模型对话历史"一项，而它正是最贵的
 * （两万字符一条的工具结果，存下来就是第二份真相）。
 *
 * <p>所以断点的语义是：<b>已到终态的用例是硬的（在 {@code cases.json} 里），
 * 对话历史不硬</b>。恢复 = 从还没到终态的那一条重新开始验，而不是把半截对话接着喂给模型。
 * 这与"每用例预算 + 单条重试"是同一套设计，不是两套。
 *
 * @param node       哪一站留下的断点（现在只有 ⑤ 会写）
 * @param runId      哪一次运行
 * @param at         什么时候写的（人能读的本地时间）
 * @param next       下一步该干什么（一句人话，恢复时原样给用户看）
 * @param doneCases  已经到终态的用例编号
 * @param roundsUsed 这一次已经花掉几轮（给人看成本，恢复时不继承）
 * @param status     最近一次的状态（{@code RUNNING} / {@code FAILED} / {@code DONE} / {@code INTERRUPTED}）
 */
public record Checkpoint(String node, String runId, String at, String next, List<String> doneCases,
                         int roundsUsed, String status) {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Checkpoint {
        node = node == null ? "" : node.strip();
        runId = runId == null ? "" : runId.strip();
        at = at == null ? "" : at.strip();
        next = next == null ? "" : next.strip();
        doneCases = doneCases == null ? List.of() : List.copyOf(doneCases);
        status = status == null || status.isBlank() ? "RUNNING" : status.strip();
    }

    /** 一条给人看的摘要（界面/事件流里那一行）。 */
    public String summarize() {
        return status + "：" + (doneCases.isEmpty() ? "还没有用例到终态"
                : "已到终态 " + doneCases.size() + " 条（" + String.join("、", doneCases) + "）")
                + "；下一步：" + (next.isBlank() ? "从这里接着跑" : next);
    }

    // ---------- 落点：flow/<id>/checkpoint.json（由 Layout 给路径，别处不许拼） ----------

    public static void write(java.nio.file.Path file, Checkpoint checkpoint) {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("node", checkpoint.node());
        root.put("runId", checkpoint.runId());
        root.put("at", checkpoint.at().isBlank() ? LocalDateTime.now().format(STAMP) : checkpoint.at());
        root.put("next", checkpoint.next());
        root.put("doneCases", checkpoint.doneCases());
        root.put("roundsUsed", checkpoint.roundsUsed());
        root.put("status", checkpoint.status());
        Atomic.write(file, Json.write(root) + "\n");
    }

    /** 读一份断点；没有（或读不出来）就 {@code null}——断点不该成为"打不开项目"的原因。 */
    public static Checkpoint read(java.nio.file.Path file) {
        String raw = Atomic.read(file, "");
        if (raw.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> root = Json.map(raw);
            Object done = root.get("doneCases");
            List<String> doneCases = done instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();
            return new Checkpoint(String.valueOf(root.getOrDefault("node", "")),
                    String.valueOf(root.getOrDefault("runId", "")),
                    String.valueOf(root.getOrDefault("at", "")),
                    String.valueOf(root.getOrDefault("next", "")),
                    doneCases,
                    root.get("roundsUsed") instanceof Number number ? number.intValue() : 0,
                    String.valueOf(root.getOrDefault("status", "RUNNING")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void clear(java.nio.file.Path file) {
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (java.io.IOException ignored) {
            // 删不掉不影响任何判断（读之前会先看它是不是存在）
        }
    }

    /** 现在的时间戳（写断点时用，格式固定成人能读的样子）。 */
    public static String now() {
        return LocalDateTime.now().format(STAMP);
    }
}
