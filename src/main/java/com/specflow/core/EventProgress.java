package com.specflow.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Progress} 的实现：把过程写成<b>事件</b>进归档日志。
 *
 * <p>逐行输出有上限（默认 2000 行）：{@code docker compose up} 拉几十个镜像能吐几万行，
 * 而日志是给人扫的。到顶之后补一句"还有多少行没逐行推"，那几句仍然是真话。
 *
 * <p>它同时在内存里留一份 steps（收场载荷里那一栏要用）——那是**这一次运行**的短时记忆，
 * 不落盘、不跨运行，跟事件流（长期归档）是两件事。
 */
public final class EventProgress implements Progress {

    private static final int MAX_LINES = 2000;

    private final EventLog log;
    private final String node;
    private final long startedAt = System.currentTimeMillis();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final List<Runnable> onFail = new ArrayList<>();

    private int streamed;
    private String currentStep = "";
    private String currentCommand = "";
    private volatile boolean cancelled;

    public EventProgress(EventLog log, String node) {
        this.log = log;
        this.node = node == null ? "" : node;
    }

    /** 被叫停时（{@link Jobs#cancelAll()}）由外面标记。 */
    public void markCancelled() {
        cancelled = true;
    }

    /** 「失败时顺手做点什么」（比如把环境收干净）。 */
    public EventProgress onFailure(Runnable action) {
        onFail.add(action);
        return this;
    }

    @Override
    public void step(String name, String command, long timeoutSeconds) {
        currentStep = name == null ? "" : name;
        currentCommand = command == null ? "" : command;
        steps.add(step(name, currentCommand, false));
        StringBuilder text = new StringBuilder(currentStep);
        if (!currentCommand.isBlank()) {
            text.append("：").append(one(currentCommand));
        }
        if (timeoutSeconds > 0) {
            text.append("（最多等 ").append(timeoutSeconds / 60).append(" 分钟）");
        }
        log.say(node, "info", text.toString());
    }

    @Override
    public void stepDone(String name) {
        finishLast(true);
        log.say(node, "info", (name == null ? "" : name) + "（已等 " + elapsedSeconds() + " 秒）");
    }

    @Override
    public void line(String text) {
        String one = one(text);
        if (one.isEmpty()) {
            return;
        }
        if (streamed >= MAX_LINES) {
            if (streamed == MAX_LINES) {
                streamed++;
                log.say(node, "info", "…（输出超过 " + MAX_LINES + " 行，后面的不再逐行推）");
            }
            return;
        }
        streamed++;
        log.say(node, "info", one);
    }

    @Override
    public void warn(String text) {
        log.warn(node, text);
    }

    @Override
    public void fail(String text, String raw) {
        finishLast(false);
        StringBuilder body = new StringBuilder(text == null ? "这一步没成功" : text);
        if (raw != null && !raw.isBlank() && !body.toString().contains(raw.strip())) {
            body.append(System.lineSeparator()).append(raw);
        }
        log.error(node, body.toString());
        for (Runnable action : onFail) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn(node, "收场时出了点问题：" + e.getMessage());
            }
        }
    }

    @Override
    public void done(String text, Map<String, Object> extra) {
        finishLast(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "done");
        data.put("steps", steps());
        data.put("elapsedSeconds", elapsedSeconds());
        if (extra != null) {
            data.putAll(extra);
        }
        log.append(new Event(0, Event.LOG, null, node, "info", text, null, null, null, null, null,
                null, null, 0, data));
    }

    @Override
    public boolean cancelled() {
        return cancelled;
    }

    @Override
    public long elapsedSeconds() {
        return Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
    }

    @Override
    public List<Map<String, Object>> steps() {
        return List.copyOf(steps);
    }

    private Map<String, Object> step(String name, String command, boolean ok) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("name", name == null ? "" : name);
        one.put("command", command == null ? "" : command);
        one.put("ok", ok);
        one.put("seconds", elapsedSeconds());
        return one;
    }

    private void finishLast(boolean ok) {
        if (steps.isEmpty()) {
            return;
        }
        steps.set(steps.size() - 1, step(steps.get(steps.size() - 1).get("name").toString(),
                steps.get(steps.size() - 1).get("command").toString(), ok));
    }

    /** {@code docker} 的进度条用 {@code \r} 反复重画：只留最后一段，语义上正好是"屏幕上的那一帧"。 */
    static String one(String text) {
        if (text == null) {
            return "";
        }
        String line = text.replace("\r\n", "\n");
        if (line.indexOf('\r') >= 0) {
            String last = "";
            for (String part : line.split("\r", -1)) {
                if (!part.isBlank()) {
                    last = part;
                }
            }
            line = last;
        }
        return line.stripTrailing().strip();
    }
}
