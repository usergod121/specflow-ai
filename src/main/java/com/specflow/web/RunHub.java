package com.specflow.web;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前这次运行的进度缓冲区。
 *
 * <p>界面用「轮询 + 游标」而不是 SSE 来拿进度。理由：这里真正的延迟在模型调用上，
 * 一次几秒；400 毫秒的轮询延迟根本感知不到，而它换来的是一条没有长连接、
 * 没有分块编码、可以随时刷新页面的普通 HTTP 请求。
 *
 * <p>线程安全靠 {@code synchronized}：事件由运行线程写、HTTP 线程读，
 * 方法体都只有几行，不存在争用问题。
 *
 * <p>{@code runId} 是给界面用的护栏：如果界面带着上一次的游标来问，
 * 而服务端已经开了新的一次运行，界面凭 runId 不一致就能发现并从头拉取，
 * 而不是默默漏掉开头几条。
 */
public final class RunHub {

    /** 事件上限。只为防住病态的重试循环，正常一次运行只有几十条。 */
    private static final int MAX_EVENTS = 500;

    private final List<RunEvent> events = new ArrayList<>();
    private long nextId = 1;
    private String runId = "";
    private boolean running;

    /**
     * 开始一次新的运行：清空上一轮的事件，换一个新的 runId。
     *
     * @throws IllegalStateException 已有任务在跑
     */
    public synchronized String startRun(String newRunId) {
        if (running) {
            throw new IllegalStateException("已有任务正在运行");
        }
        events.clear();
        nextId = 1;
        runId = newRunId;
        running = true;
        return runId;
    }

    public synchronized RunEvent publish(String level, int round, String text) {
        RunEvent event = RunEvent.log(nextId++, level, round, text);
        append(event);
        return event;
    }

    public synchronized void publishResult(Object payload) {
        append(RunEvent.result(nextId++, payload));
    }

    public synchronized void finish() {
        running = false;
    }

    public synchronized boolean running() {
        return running;
    }

    public synchronized String runId() {
        return runId;
    }

    /**
     * 取出 {@code id >= from} 的全部事件。
     *
     * @param from 游标；界面第一次拉取传 0
     */
    public synchronized RunView view(long from) {
        List<RunEvent> pending = events.stream().filter(event -> event.id() >= from).toList();
        return new RunView(runId, running, pending);
    }

    private void append(RunEvent event) {
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /**
     * 一次增量拉取的返回值。
     *
     * @param runId   本次运行的标识，与界面记录的不一致时应重新从 0 开始拉
     * @param running 是否仍在运行；false 表示界面可以停止轮询
     * @param events  新事件，按 id 升序
     */
    public record RunView(String runId, boolean running, List<RunEvent> events) {
    }
}
