package com.specflow.core;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>业务侧的计数</b>：跑了几段活、失败几次、调了几次模型、失败几条命令、问了几次审批。
 *
 * <p>为什么光有日志不够：日志回答"刚才发生了什么"，计数回答"整体上正常吗"——
 * 出了 bug 的时候你要先知道**是个例还是每次都这样**，否则会去修一个只发生过一次的东西。
 * 它们通过 Actuator 暴露在 `GET /api/actuator/metrics/specflow.runs` 这些名字下。
 *
 * <p><b>没有 registry 时也不炸</b>（单元测试里没有 Spring）：全部走 {@link #noop()} 那一档，
 * 这样业务代码不必到处判空。
 */
public final class Metrics {

    /** 名字前缀，免得与框架的指标混在一起。 */
    private static final String PREFIX = "specflow.";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger();

    private Metrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static Metrics of(MeterRegistry registry) {
        return new Metrics(registry);
    }

    public static Metrics noop() {
        return new Metrics(null);
    }

    /** 一段活开工：`specflow.runs{node=env,action=env-up}` 加一，并记下"现在有几段在跑"。 */
    public void runStarted(String node, String action) {
        count("runs", "node", node, "action", action);
        running.incrementAndGet();
        gauge("running", running.get());
    }

    /** 一段活收场（status 用 DONE / FAILED / CANCELLED）。 */
    public void runFinished(String node, String status, long seconds) {
        count("runs.finished", "node", node, "status", status);
        running.updateAndGet(now -> Math.max(0, now - 1));
        gauge("running", running.get());
        timer("run.seconds", seconds, "node", node, "status", status);
    }

    /** 一次模型调用（最贵的一步：它花了多少、失败几次要看得出来）。 */
    public void modelCall(String runName, boolean ok, long millis) {
        count("model.calls", "run", runName, "ok", String.valueOf(ok));
        timer("model.seconds", millis / 1000.0, "run", runName);
    }

    /** 一条命令跑完（起进程那条路）。 */
    public void command(String what, boolean ok, long millis) {
        count("commands", "what", what, "ok", String.valueOf(ok));
        timer("command.seconds", millis / 1000.0, "what", what);
    }

    /** 问了一次高危命令审批（被拒的也要记：被拒得多说明提示词该改）。 */
    public void approval(boolean allowed) {
        count("approvals", "answer", allowed ? "allow" : "deny");
    }

    /** 闸拦下了一次动作（哪个节点的哪一种被拦最多，一眼看得出来提示顺序有问题）。 */
    public void gateBlocked(String node) {
        count("gate.blocked", "node", node);
    }

    private void count(String name, String... tags) {
        if (registry == null) {
            return;
        }
        String key = name + String.join(",", tags);
        counters.computeIfAbsent(key, ignored -> Counter.builder(PREFIX + name)
                .tags(tags)
                .register(registry)).increment();
    }

    private void timer(String name, double seconds, String... tags) {
        if (registry == null) {
            return;
        }
        Timer.builder(PREFIX + name).tags(tags).register(registry).record(
                java.time.Duration.ofMillis((long) (seconds * 1000)));
    }

    private void gauge(String name, int value) {
        if (registry == null) {
            return;
        }
        registry.gauge(PREFIX + name, value);
    }
}
