package com.specflow.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>统一归档日志</b>：一条流程一份 {@code events.jsonl}，一行一条事件。
 *
 * <h2>三条铁律（这一版最要紧的设计，别改）</h2>
 * <ol>
 *   <li><b>只有一个写者</b>：所有事件（日志、工具调用、收场、闸）都从这里 append。
 *       上一版两个类各写一个文件、各数一套 id，界面按 {@code ?from=} 拉取时重复/跳过；</li>
 *   <li><b>{@code id} 恒等于行号</b>：追加前现读行数、盖号、写一行。于是「游标」与
 *       「第几条」是同一个数，重启之后接着数（上一版每次启动从 1 起，重启后界面上历史全没了）；</li>
 *   <li><b>盘上是唯一的真相</b>：读一律以盘上内容为准。内存里存的那一份只是它的副本 ——
 *       每次都用 (大小, 修改时间) 对一遍，对不上就整份重读（见下面的 {@link #CACHE}）。</li>
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

    /**
     * 每份文件一份"行快照"，用 <b>(大小, 修改时间)</b> 当校验和。
     *
     * <p><b>这算不算破掉第三条铁律</b>：不算。铁律要防的是"两份真相不一致"，
     * 而这里缓存的是<b>盘上那份内容的一个副本 + 一个校验和</b>：校验和对不上就整份重读，
     * 盘上永远是唯一的真相。谁要是手工改了 {@code events.jsonl}、或者别的进程追加了，
     * 大小/时间立刻变，下一次读就重来。
     *
     * <p><b>为什么必须加它</b>：界面改成 SSE 之后每 250 毫秒推一次，每次都要"从第 N 行读到末尾"。
     * 不缓存的话，一条跑了几千条事件的流程等于每秒把整个文件读四遍——
     * 这不是理论问题，是把轮询改成推送之后立刻会撞上的问题。
     */
    private static final Map<Path, Snapshot> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 一次读盘的产物：内容 + 当时的 (大小, 修改时间)。 */
    private record Snapshot(long size, long mtime, List<String> rows) {
    }

    private final Path file;

    public EventLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** 追加一条：现读行数 → 盖 id → 写一行 → 返回盖好号的那条。 */
    public synchronized Event append(Event event) {
        Event stamped = event.withId(rows().size() + 1L);
        String row = Json.line(stamped);
        Atomic.appendLine(file, row);
        // 写完顺手把快照接上：追加于是从 O(总行数) 变回 O(1)，也不用再读一次盘
        //（我们刚写的就是最后一行）。对不上（别处也写过）也无所谓——校验和会让它下次重读。
        extend(row);
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
        List<String> rows = rows();
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
        return rows().size();
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
        List<String> kept = new ArrayList<>(rows().subList(0, (int) Math.max(0, keepLines)));
        Atomic.write(file, kept.isEmpty() ? "" : String.join("\n", kept) + "\n");
        CACHE.remove(file);
        return archive;
    }

    // ---------- 里面：盘上那份内容的快照 ----------

    /** 盘上的行（必要时重读）。**这是唯一的真相入口。** */
    private List<String> rows() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        Snapshot cached = CACHE.get(file);
        long size;
        long mtime;
        try {
            size = Files.size(file);
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("归档日志读不出来：" + file + "（" + e.getMessage() + "）", e);
        }
        if (cached != null && cached.size() == size && cached.mtime() == mtime) {
            return cached.rows();
        }
        List<String> fresh;
        try {
            fresh = List.copyOf(Files.readAllLines(file));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("归档日志读不出来：" + file + "（" + e.getMessage() + "）", e);
        }
        CACHE.put(file, new Snapshot(size, mtime, fresh));
        return fresh;
    }

    /** 刚追加了一行：把快照接上（校验和按追完之后的文件重取，取不到就丢掉缓存让下次重读）。 */
    private void extend(String row) {
        List<String> before = CACHE.containsKey(file) ? CACHE.get(file).rows() : null;
        if (before == null) {
            // 没有快照说明还没人读过：不主动建，交给下一次读（省一次无用的 readAllLines）
            return;
        }
        try {
            List<String> after = new ArrayList<>(before.size() + 1);
            after.addAll(before);
            after.add(row);
            CACHE.put(file, new Snapshot(Files.size(file), Files.getLastModifiedTime(file).toMillis(),
                    List.copyOf(after)));
        } catch (java.io.IOException e) {
            // 取不到校验和就别猜：让下一次读重来
            CACHE.remove(file);
        }
    }
}
