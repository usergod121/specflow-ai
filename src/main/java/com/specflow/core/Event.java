package com.specflow.core;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 一条事件。归档日志的一行、界面上的「留档」一行、工具调用的一枚 chip——都是它。
 *
 * <p>形状与 `docs/API.md` §8 逐字对应。四栏只在工具调用时有值
 * （{@code callId / status / millis / resultFull}），其余用不带它们的工厂。
 *
 * <h2>为什么工具调用要两条事件（合同 §4.1）</h2>
 * 用户原话：「工具调用我要流式 chip 打印，**最后才一次性展示没有任何意义**」。
 * 所以执行<b>之前</b>先推一条 {@code status=running}，跑完再推一条 {@code status=ok|error}
 * 带耗时与结果全文，两条共用 {@code callId}——界面把两条合成<b>一枚</b> chip，
 * chip 因此是「一边跑一边冒」。
 *
 * @param id      序号，<b>恒等于它在归档日志里的行号</b>（重启后接着数，见 {@link EventLog}）
 * @param type    {@code log} 一行 / {@code tool} 一次工具调用 / {@code result} 一段的收场 / {@code gate} 闸拦下了
 * @param round   第几轮（工具调用与收场才有意义）
 * @param payload 结构化数据；{@code type=result} 时是整份收场载荷（界面画那一页就靠它）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Event(
        long id,
        String type,
        String at,
        String node,
        String level,
        String text,
        String tool,
        String args,
        String result,
        String callId,
        String status,
        Long millis,
        String resultFull,
        int round,
        Object payload
) {

    public static final String LOG = "log";
    public static final String TOOL = "tool";
    public static final String RESULT = "result";
    public static final String GATE = "gate";

    public static final String RUNNING = "running";
    public static final String OK = "ok";
    public static final String ERROR = "error";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public Event {
        type = type == null || type.isBlank() ? LOG : type;
        at = at == null || at.isBlank() ? now() : at;
        node = node == null ? "" : node;
        level = level == null || level.isBlank() ? "info" : level;
        text = text == null ? "" : text;
    }

    public static String now() {
        return LocalDateTime.now().format(STAMP);
    }

    /** 换个 id（{@link EventLog} 在追加前盖号用）。 */
    public Event withId(long newId) {
        return new Event(newId, type, at, node, level, text, tool, args, result, callId,
                status, millis, resultFull, round, payload);
    }

    public static Event log(long id, String node, String level, String text) {
        return new Event(id, LOG, null, node, level, text, null, null, null, null, null, null, null, 0, null);
    }

    /** 工具调用<b>开始</b>（执行之前）。 */
    public static Event toolStart(long id, String node, String callId, int round,
                                 String tool, String args) {
        return new Event(id, TOOL, null, node, "info", line(round, tool, args, null, null), tool,
                args == null ? "" : args, null, callId, RUNNING, null, null, round, null);
    }

    /** 同一次工具调用<b>结束</b>：结果、耗时、成败都在这里。 */
    public static Event toolEnd(long id, String node, String callId, int round, String tool,
                                String args, String summary, String full, boolean ok, long millis) {
        return new Event(id, TOOL, null, node, ok ? "info" : "error",
                line(round, tool, args, summary, millis), tool, args == null ? "" : args, summary,
                callId, ok ? OK : ERROR, millis, full, round, null);
    }

    /** 一段的收场：带上整份载荷。 */
    public static Event result(long id, String node, Object payload, String text) {
        return new Event(id, RESULT, null, node, "info", text, null, null, null, null, null, null,
                null, 0, payload);
    }

    /** 闸拦下了一次动作（事后翻得到"当时为什么不让点"）。 */
    public static Event gate(long id, String node, String text) {
        return new Event(id, GATE, null, node, "warn", text, null, null, null, null, null, null,
                null, 0, null);
    }

    private static String line(int round, String tool, String args, String summary, Long millis) {
        StringBuilder out = new StringBuilder();
        if (round > 0) {
            out.append("第 ").append(round).append(" 轮 · ");
        }
        out.append(tool == null ? "" : tool);
        out.append(" · 参数：").append(one(args, 400));
        if (summary != null) {
            out.append("  →  ").append(one(summary, 240));
        }
        if (millis != null) {
            out.append("（").append(millis).append("ms）");
        }
        return out.toString();
    }

    private static String one(String text, int limit) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
