package com.specflow.core;

import org.slf4j.MDC;

import java.util.concurrent.ThreadLocalRandom;

/**
 * <b>链路 id</b>：让「一次请求 / 一段运行」在日志里串得起来。
 *
 * <p>为什么需要它（这条是排错的地基）：一次「跑开发」会经过 HTTP → 后台线程 → 模型调用 →
 * 起进程 → 写文件，日志里几十行混在一起。没有 id 的时候，只能靠时间戳猜哪几行是同一件事的；
 * 出了 bug 回头看，那几分钟全花在"把日志分组"上。有了它，一条
 * {@code grep run=r-3f9a} 就把整段拉出来。
 *
 * <p>两个字段：
 * <ul>
 *   <li>{@code req} —— 一次 HTTP 请求（{@code TraceFilter} 放）；</li>
 *   <li>{@code run} —— 一段运行（起环境 / 跑探针 / 跑开发…，{@link #scope} 放）。
 *       长任务跑在后台线程上，所以它<b>必须跟着线程传下去</b>——这正是 MDC 的用法。</li>
 * </ul>
 *
 * <p>它<b>不是</b>业务数据：日志文件在用户目录下，不写进项目；密钥等敏感值一律不许进 MDC。
 */
public final class Logs {

    public static final String REQUEST = "req";
    public static final String RUN = "run";

    private Logs() {
    }

    /** 一个新的短 id（够短、肉眼能读，日志里一行不至于太长）。 */
    public static String shortId() {
        return Long.toString(ThreadLocalRandom.current().nextLong(0x1000000L, 0xFFFFFFFL), 16);
    }

    /** 一个运行 id（时间戳 + 短随机，既能排序又能区分同一秒里的两次）。 */
    public static String runId() {
        return "r-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss"))
                + "-" + shortId().substring(0, 3);
    }

    /** 把这段运行标进当前线程的日志上下文；{@code close()} 时摘掉。 */
    public static AutoCloseable scope(String field, String value) {
        String previous = MDC.get(field);
        if (value != null && !value.isBlank()) {
            MDC.put(field, value);
        }
        return () -> {
            if (previous == null) {
                MDC.remove(field);
            } else {
                MDC.put(field, previous);
            }
        };
    }

    /** 当前线程上的运行 id（没有就是空串）——给人看的那句日志里偶尔要带上它。 */
    public static String currentRun() {
        String value = MDC.get(RUN);
        return value == null ? "" : value;
    }

    /**
     * 参数进日志前的<b>脱敏</b>：密钥、token、password 一律换成 {@code ***}。
     *
     * <p>为什么放在这里而不是靠自觉：日志是排错用的，排错时人会把整条命令、整份配置打出去，
     * 而"别打密钥"这条纪律在赶时间的时候最容易破。写成函数，谁都能顺手过一遍。
     */
    public static String redact(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("(?i)(sk-[A-Za-z0-9_-]{4})[A-Za-z0-9_-]+", "$1***")
                .replaceAll("(?i)(api[_-]?key\\s*[=:]\\s*)\\S+", "$1***")
                .replaceAll("(?i)(password\\s*[=:]\\s*)\\S+", "$1***")
                .replaceAll("(?i)(token\\s*[=:]\\s*)\\S+", "$1***")
                .replaceAll("(?i)(-p)\\S+", "$1***");
    }
}
