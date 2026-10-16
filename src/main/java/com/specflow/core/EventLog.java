package com.specflow.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>统一归档日志</b>：一条流程一份 {@code events.jsonl}，一行一条事件。
 *
 * <h2>三条铁律（这一版最要紧的设计，别改）</h2>
 * <ol>
 *   <li><b>只有一个写者</b>：所有事件（日志、工具调用、收场、闸）都从这里 append。
 *       上一版两个类各写一个文件、各数一套 id，界面按 {@code ?from=} 拉取时重复/跳过；</li>
 *   <li><b>{@code id} 恒等于行号</b>：追加前现读行数、盖号、写一行。于是「游标」与
 *       「第几条」是同一个数，重启之后接着数（上一版每次启动从 1 起，重启后界面上历史全没了）；</li>
 *   <li><b>不缓存</b>：读一律回盘上读。内存里再存一份就又多了一个"真相"，
 *       而两份真相迟早不一致——这正是上一版里最难查的一类 bug。</li>
 * </ol>
 *
 * <p>一行读不出来（被人手改坏了）时：<b>不吞</b>，把那一行换成一条 warn 事件交出去，
 * 后面的行照读。丢掉一整段历史比看见一句"第 N 行读不出来"糟得多。
 *
 * <p>写不进去时<b>抛</b>（不是静默）：事件流是"让人看得见"的唯一通道，
 * 它坏了必须立刻炸出来，而不是让跑着的活安安静静地没有任何输出。
 */
public final class EventLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;

    public EventLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** 追加一条：现读行数 → 盖 id → 写一行 → 返回盖好号的那条。 */
    public synchronized Event append(Event event) {
        Event stamped = event.withId(lines() + 1);
        Atomic.appendLine(file, Json.line(stamped));
        return stamped;
    }

    /** 一行日志。 */
    public Event say(String node, String level, String text) {
        return append(Event.log(0, node, level, text));
    }

    public Event warn(String node, String text) {
        return say(node, "warn", text);
    }

    public Event error(String node, String text) {
        return say(node, "error", text);
    }

    /**
     * 从第 {@code from} 行（含）开始读。
     *
     * @param from  行号，从 1 起；{@code <=0} 当 1
     * @param limit 最多读几条；{@code <=0} 不限
     */
    public synchronized List<Event> read(long from, int limit) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> rows;
        try {
            rows = Files.readAllLines(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("归档日志读不出来：" + file + "（" + e.getMessage() + "）", e);
        }
        long start = Math.max(1, from);
        List<Event> found = new ArrayList<>();
        for (int at = 0; at < rows.size(); at++) {
            long id = at + 1L;
            if (id < start) {
                continue;
            }
            String row = rows.get(at).strip();
            if (!row.isEmpty()) {
                found.add(parse(id, row));
            }
            if (limit > 0 && found.size() >= limit) {
                break;
            }
        }
        return List.copyOf(found);
    }

    private static Event parse(long id, String row) {
        try {
            Event event = Json.read(row, Event.class);
            // 磁盘上的 id 与行号不一致时**以行号为准**：游标的定义就是行号
            return event.id() == id ? event : event.withId(id);
        } catch (RuntimeException e) {
            return Event.log(id, "", "warn", "第 " + id + " 行日志读不出来：" + e.getMessage());
        }
    }

    /** 现在有多少行（= 最后一条事件的 id）。 */
    public synchronized long lines() {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            return Files.readAllLines(file).size();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("归档日志读不出来：" + file, e);
        }
    }

    /** 下一条事件的 id。 */
    public synchronized long next() {
        return lines() + 1;
    }

    /**
     * 回退作废：把日志截到 {@code keepLines} 行之内，<b>截之前先留一份副本</b>。
     *
     * <p>为什么先留副本：回退作废的是「状态」，不是「记录」——用户点回退往往正是因为
     * 刚才那一步失败了，把失败原文一起删掉，他就再也说不出「刚才到底报的什么」。
     *
     * @return 副本路径；没有东西要截时 {@code null}
     */
    public synchronized Path truncateTo(long keepLines) {
        long total = lines();
        if (total <= keepLines) {
            return null;
        }
        Path archive = file.resolveSibling("events-invalidated-"
                + LocalDateTime.now().format(STAMP) + ".jsonl");
        Atomic.copy(file, archive);
        List<String> rows = readRows();
        Atomic.write(file, String.join("\n", rows.subList(0, (int) Math.max(0, keepLines))) + "\n");
        return archive;
    }

    private List<String> readRows() {
        try {
            return Files.readAllLines(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("归档日志读不出来：" + file, e);
        }
    }
}
