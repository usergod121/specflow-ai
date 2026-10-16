package com.specflow.agent;

import com.specflow.core.Event;
import com.specflow.core.EventLog;

/**
 * 把 {@link Watcher} 的两声落成归档日志里的两条 {@code type=tool} 事件。
 *
 * <p>合同 §4.1：执行前一条 {@code status=running}、执行后一条 {@code status=ok|error}
 * 带耗时与结果全文，两条<b>共用 callId</b>（界面把两条合成一枚 chip）。
 * 配对在<b>这里</b>做：循环只保证「恰好一前一后、同一个线程、顺序发生」，
 * 于是「上一条没配对的那一次」就是这一条要配的那一次。
 */
public final class EventWatcher implements Watcher {

    private final EventLog log;
    private final String node;
    private long seq;
    private String open;

    public EventWatcher(EventLog log, String node) {
        if (log == null) {
            throw new IllegalArgumentException("归档日志不能为空：工具事件必须落进那条唯一的流");
        }
        this.log = log;
        this.node = node == null ? "" : node;
    }

    @Override
    public synchronized void starting(int round, String tool, String argsJson) {
        open = "c" + (++seq);
        log.append(Event.toolStart(0, node, open, round, tool, argsJson));
    }

    @Override
    public synchronized void finished(int round, String tool, String argsJson, String summary,
                                     String resultFull, boolean ok, long millis) {
        // 没有配对的 starting（调用方没按规矩来）也照样落一条：chip 少一半比多一条更难看
        String callId = open != null ? open : "c" + (++seq);
        open = null;
        log.append(Event.toolEnd(0, node, callId, round, tool, argsJson, summary, resultFull, ok, millis));
    }
}
