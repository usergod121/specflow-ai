package com.specflow.flow;

import com.specflow.core.Event;
import com.specflow.core.EventLog;
import com.specflow.core.EventProgress;
import com.specflow.core.Jobs;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>一段后台运行</b>：谁在跑、跑到哪、有没有在跑（`docs/ARCHITECTURE.md` §6 的形状）。
 *
 * <h2>三条它必须做到的事</h2>
 * <ol>
 *   <li><b>立刻返回</b>：{@code POST /api/node/<key>/<action>} 返回 202 + runId，
 *       活在自己的线程上跑，<b>不阻塞请求线程</b>（长任务一阻塞，界面只能干等）；</li>
 *   <li><b>同一时刻只许一段在跑</b>：第二次 {@link #start} 直接 409 人话。
 *       不拦的话两段活会同时改同一个项目，而「谁改的」在留档里也看不出；</li>
 *   <li><b>收场只有一条路</b>：不管成功、失败、被叫停，都往事件流写一条
 *       {@code type=result} 的载荷——界面画那一页就只有这一个来源（不变量 4）。</li>
 * </ol>
 *
 * <h2>收场载荷从哪来（为什么不是这里"算"出来的）</h2>
 * 轮次、模型调用次数、每次工具调用、机器验收的问题清单——这些只有跑的那段活知道，
 * 它通过 {@code progress.done(text, extra)} / {@code progress.fail(text, raw)} 说出来。
 * {@link Runs} 不猜也不补算：它读这一段事件（body 说的话都已经落在盘上），
 * 把 {@code API.md} §9 那几栏统一收口成一份载荷。抛出来的异常也<b>不吞</b>——
 * 翻成 {@code status=FAILED} 与那句原因，原文照旧留在日志里。
 *
 * <p>线程池只有一个线程，退出时 {@link #close()} 收得掉。
 */
public final class Runs {

    /** 一段活跑什么。 */
    public interface Body {
        void run(EventProgress progress) throws Exception;
    }

    /** §9 那几栏里允许由外面（{@code extra} 或 body 的 done）预置的那些。 */
    private static final List<String> PAYLOAD_KEYS =
            List.of("nextStep", "rounds", "modelCalls", "calls", "roundList", "problems",
                    // 各节点自己的产物：① 的草稿、② 的四样、③ 的清单、④ 的编译结论、⑤ 的沙箱。
                    // 界面画那一页靠的就是它们（`docs/API.md` §9）
                    "draft", "probe", "cases", "compilePassed", "verdicts", "sandbox", "summary");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final FlowService flow;
    private final ExecutorService pool;
    private final AtomicInteger seq = new AtomicInteger();

    private volatile boolean running;
    private volatile String runId = "";
    private volatile String what = "";
    private volatile long startCursor;
    private volatile String stage = "";

    public Runs(FlowService flow) {
        this.flow = Objects.requireNonNull(flow, "后台运行要有流程服务");
        this.pool = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "specflow-run");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 开一段活：立刻返回 runId（活在线程池里跑）。
     *
     * @param node  哪一格的活（事件与留档都记在它名下）
     * @param action 动作名（{@code run} / {@code agent} / {@code up}…；只进留档与人话）
     * @param extra  这一段活的附加说明（进崩溃恢复那本账；其中 §9 那几栏可当收场载荷的默认值）
     * @param body   真正跑的活；它说的话通过 {@link EventProgress} 出去
     * @throws FlowRejected 已经有一段在跑（409）
     */
    public String start(Node node, String action, Map<String, Object> extra, Body body) {
        Objects.requireNonNull(node, "开一段活要有节点");
        Objects.requireNonNull(body, "开一段活要有活");
        String actionText = action == null || action.isBlank() ? "run" : action.strip();
        EventLog log = flow.events();
        String id = node.key() + "-" + safe(actionText) + "-" + STAMP.format(LocalDateTime.now())
                + "-" + seq.incrementAndGet();
        synchronized (this) {
            if (running) {
                throw FlowRejected.conflict("还有一段在跑（" + what + "）：同一时刻只许一段，"
                        + "先等它跑完，或者点「叫停」。");
            }
            running = true;
            runId = id;
            what = node.title() + " · " + actionText;
            stage = "刚开始：" + what;
            // 游标要在写任何事件**之前**取：它等于这一段活第一条事件的 id，
            // 界面拿着它不是「错过第一行」，而是「从第一行开始读」
            startCursor = log.next();
        }
        EventProgress mine = new EventProgress(log, node.key());
        AutoCloseable lease = Jobs.register(mine);
        Journal journal = new Journal(flow.project());
        try {
            // 开工先写 BEGIN（不变量 7）：崩在这中间的运行，启动时扫得出来
            journal.begin(id, what + (extra == null || extra.isEmpty() ? "" : " · " + brief(extra)));
            log.append(Event.log(0, node.key(), "info", "开始：" + what));
            com.specflow.core.Metrics.installed().runStarted(node.key(), actionText);
            pool.submit(() -> run(node, log, mine, lease, journal, body, extra));
        } catch (RuntimeException e) {
            // 起不来（池关了、盘写不进去）：把账收干净再抛，
            // 别留下一个永远「在跑」的登记——那会让后面每一次动作都 409
            running = false;
            stage = "起不来：" + e.getMessage();
            try {
                journal.end(id, "FAILED", "这一段起不来：" + e.getMessage());
            } catch (RuntimeException ignored) {
                // 连账都收不成时，上面那句已经把真相带出去了
            }
            close(lease);
            throw new IllegalStateException("这一段活起不来（" + e.getMessage() + "）", e);
        }
        return id;
    }

    /** 现在有没有在跑。 */
    public boolean running() {
        return running;
    }

    /**
     * 最新一句进展。
     *
     * <p>从事件流的尾巴上现读，而不是在内存里攒一份：界面刷新之后不必回放整条历史，
     * 而攒一份就会出现「内存里那句」与「盘上那句」两个说法。
     */
    public String stage() {
        EventLog log = flow.events();
        List<Event> tail = log.read(Math.max(1, log.lines() - 20), 0);
        for (int at = tail.size() - 1; at >= 0; at--) {
            String text = tail.get(at).text();
            if (text != null && !text.isBlank()) {
                return one(text, 160);
            }
        }
        return stage;
    }

    /**
     * 叫停：立标记 + 杀掉这一段起的进程树（{@link Jobs#cancelAll()}），
     * 并且这一段会按「已停下」收场（不是「失败」——那是用户自己按的）。
     *
     * @throws FlowRejected 现在没有东西可停（409）
     */
    public void cancel() {
        if (!running && Jobs.running() == 0) {
            throw FlowRejected.conflict("现在没有在跑的东西，不必要叫停。");
        }
        int stopped = Jobs.cancelAll();
        stage = "叫停了：" + what + (stopped > 0 ? "（它起的进程正在被杀掉）" : "");
    }

    /**
     * 事件流的下一个游标。
     *
     * <p>正跑着时给的是<b>这一段活的第一条事件</b>的号（界面从它开始读，一行都不漏）；
     * 没跑时给的是下一行——「接着看」的意思在两种场合是同一个。
     */
    public long cursor() {
        return running ? startCursor : flow.events().next();
    }

    /** 收线程池（不杀正在跑的活：要停走 {@link #cancel()}）。 */
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 里面 ----------

    private void run(Node node, EventLog log, EventProgress mine, AutoCloseable lease,
                     Journal journal, Body body, Map<String, Object> extra) {
        long startedAt = System.currentTimeMillis();
        try {
            String thrown = null;
            try {
                body.run(mine);
            } catch (Throwable t) {
                thrown = human(t);
            }

            // 这一段说过什么，全在这一段事件里（done 的载荷、error 的原文）
            long doneId = -1;
            long errorId = -1;
            String doneText = "";
            String errorText = "";
            long saidSeconds = 0;
            Map<String, Object> said = new LinkedHashMap<>();
            if (extra != null) {
                for (String key : PAYLOAD_KEYS) {
                    if (extra.containsKey(key)) {
                        said.put(key, extra.get(key));
                    }
                }
            }
            for (Event event : log.read(startCursor, 0)) {
                Object payload = event.payload();
                if (payload instanceof Map<?, ?> map
                        && "done".equals(String.valueOf(map.get("kind")))) {
                    doneId = event.id();
                    doneText = event.text();
                    saidSeconds = number(map.get("elapsedSeconds"));
                    for (Map.Entry<?, ?> one : map.entrySet()) {
                        String key = String.valueOf(one.getKey());
                        if (PAYLOAD_KEYS.contains(key)) {
                            said.put(key, one.getValue());
                        }
                    }
                }
                if ("error".equals(event.level()) && event.text() != null && !event.text().isBlank()) {
                    errorId = event.id();
                    errorText = event.text();
                }
            }

            String status;
            String reason;
            List<String> problems = new ArrayList<>();
            if (mine.cancelled()) {
                // 叫停优先于一切：被杀的进程会留下非 0 退出码与一堆报错，
                // 那些都不是用户要看的结论——他要知道的是「是你让它停的」
                status = "CANCELLED";
                reason = "是你按「叫停」停下的，不是它自己出了问题。";
            } else if (thrown != null) {
                status = "FAILED";
                reason = thrown;
                problems.add(thrown);
            } else if (errorId > doneId) {
                status = "FAILED";
                reason = firstLine(errorText);
                problems.addAll(restLines(errorText));
            } else {
                status = "DONE";
                reason = doneId > 0 ? doneText : "跑完了。";
            }

            String label = switch (status) {
                case "DONE" -> "已完成";
                case "CANCELLED" -> "已停下";
                default -> "失败";
            };
            long rounds = number(said.get("rounds"));
            String trouble = remember(node, status, rounds);
            if (trouble != null) {
                problems.add(trouble);
            }
            long seconds = said.containsKey("seconds") ? number(said.get("seconds"))
                    : saidSeconds > 0 ? saidSeconds
                    : Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", status);
            payload.put("statusLabel", label);
            payload.put("reason", reason);
            payload.put("nextStep", text(said.get("nextStep"), nextStep(status)));
            payload.put("rounds", rounds);
            payload.put("modelCalls", number(said.get("modelCalls")));
            payload.put("seconds", seconds);
            payload.put("calls", list(said.get("calls")));
            payload.put("roundList", list(said.get("roundList")));
            payload.put("problems", List.copyOf(problems));
            // 各节点自己的产物（① 的 draft、② 的 probe、③ 的 cases…）原样带出去。
            // 不抄这一下的话：上面从 done 事件里按白名单取出来的值会**只进 said、不进载荷**，
            // 而界面画那一页靠的就是载荷——实测表现是"跑完了，但产物是空的"（端到端第一跑撞到）
            for (Map.Entry<String, Object> one : said.entrySet()) {
                payload.putIfAbsent(one.getKey(), one.getValue());
            }

            log.append(Event.result(0, node.key(), payload,
                    label + "：" + what + (reason.isBlank() ? "" : " · " + one(reason, 140))));
            journal.end(runId, status, label + "：" + one(reason, 140));
            // 计数器：/api/actuator/metrics 里 specflow.runs / runs.finished 就是它
            com.specflow.core.Metrics.installed().runFinished(node.key(), status, seconds);
            stage = label + "：" + what;
        } finally {
            running = false;
            close(lease);
        }
    }

    /**
     * 收场顺手把「这一段成没成」记进那一格的事实。
     *
     * <p>为什么记在盘上：界面那条状态条读的就是它（`lib/facts.js` 的 {@code failed}），
     * 而「上一次跑失败了」这件事在刷新之后必须还在。
     *
     * @return 记账没成时的一句人话（不是失败本身，收场不该被它掀掉）
     */
    private String remember(Node node, String status, long rounds) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(Facts.FAILED, "FAILED".equals(status) ? "true" : "false");
            if (rounds > 0) {
                values.put(Facts.ROUNDS, String.valueOf(rounds));
            }
            flow.note(node, values);
            return null;
        } catch (RuntimeException e) {
            return "收场时那一栏事实没记上：" + e.getMessage();
        }
    }

    private static String nextStep(String status) {
        return switch (status) {
            case "DONE" -> "看一眼这一步的产物；没问题就点「完成这步」（冻结），再去下一格。";
            case "FAILED" -> "按上面那条原因改一改，再跑一次。";
            default -> "想接着跑就再点一次「开始」；已经做出来的东西都留着。";
        };
    }

    /** 异常翻成人话：整条 cause 链都带上（真原因常在最后那一层）。 */
    private static String human(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        for (Throwable one = thrown; one != null; one = one.getCause()) {
            String message = one.getMessage();
            if (message != null && !message.isBlank()) {
                if (text.length() > 0) {
                    text.append(" ← ");
                }
                text.append(message.strip().replaceAll("\\R", " "));
            }
            if (one.getCause() == one) {
                break;
            }
        }
        return text.length() > 0 ? text.toString() : thrown.getClass().getSimpleName();
    }

    /** 一句话的头一行（{@code fail(text, raw)} 的形状：第一行是话，后面是原文）。 */
    private static String firstLine(String text) {
        return (text == null ? "" : text).split("\\R", 2)[0].strip();
    }

    /** 原文那几行（不许总结掉：真正有用的常在中间）。 */
    private static List<String> restLines(String text) {
        String[] rows = (text == null ? "" : text).split("\\R", 2);
        if (rows.length < 2 || rows[1].isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String row : rows[1].split("\\R")) {
            if (!row.isBlank()) {
                out.add(row.strip());
            }
        }
        return List.copyOf(out);
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? List.copyOf(items) : List.of();
    }

    private static String text(Object value, String fallback) {
        String given = value == null ? "" : String.valueOf(value).strip();
        return given.isEmpty() ? fallback : given;
    }

    /** 留档那一行里的附加说明（一节，够看懂是哪一段活）。 */
    private static String brief(Map<String, Object> extra) {
        StringBuilder text = new StringBuilder();
        extra.forEach((key, value) -> {
            if (text.length() > 0) {
                text.append('、');
            }
            text.append(key).append('=').append(one(String.valueOf(value), 40));
        });
        return one(text.toString(), 160);
    }

    private static String one(String text, int limit) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    /** 动作名进文件名与 runId：只留认得出来的字符。 */
    private static String safe(String action) {
        String cleaned = action.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return cleaned.isBlank() ? "run" : cleaned;
    }

    /** 摘掉「正在跑」的登记（{@link Jobs#cancelAll()} 之下留下的活不会误伤下一次）。 */
    private static void close(AutoCloseable lease) {
        if (lease == null) {
            return;
        }
        try {
            lease.close();
        } catch (Exception e) {
            // 摘登记失败影响不了这一段的结果；下一次 start 会重新登记
        }
    }
}
