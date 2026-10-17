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

    /** 进程里那一份（装配时装进来；没装之前全部是空实现）。 */
    private static volatile Metrics installed = noop();

    private Metrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static Metrics of(MeterRegistry registry) {
        return new Metrics(registry);
    }

    public static Metrics noop() {
        return new Metrics(null);
    }

    /**
     * 装一份全局的（{@code App} 起来时调一次）。
     *
     * <h2>为什么是全局一份，而不是一路当参数传下去</h2>
     * 计数器要用在<b>很深的地方</b>：{@code Loop} 的每次模型调用、{@code Cmd} 的每条命令、
     * {@code Approvals} 的每次提问。一路传下去等于给每个构造函数多加一个"我其实不关心"的参数。
     * 它天生是全局的（Micrometer 自己也这么干），而且没有 registry 时是空实现——
     * 单测里什么都不用装。
     *
     * <p><b>这一条是补的</b>：{@code Metrics} 这个类此前写完<b>一个调用点都没有</b>，
     * 而 {@code App} 的注释和 {@code docs} 都写着「actuator 能看到我们自己的计数器」——
     * 也就是说那条能力一直在文档里、不在代码里。
     */
    public static void install(Metrics metrics) {
        installed = metrics == null ? noop() : metrics;
    }

    /** 现在那一份（永远不为 {@code null}）。 */
    public static Metrics installed() {
        return installed;
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
