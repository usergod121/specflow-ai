package com.specflow.agent;

/**
 * 工具调用的看门人（合同 §4.1）：<b>执行前</b>喊一声、<b>执行后</b>报结果。
 *
 * <p>为什么必须两档：用户原话「最后才一次性展示没有任何意义」。{@code bash} 可能跑十分钟，
 * 等结果再推就等于没有流式——界面上的 chip 是「一边跑一边冒」的。
 *
 * <p><b>{@code callId} 的配对由实现方负责</b>：循环只保证「一次调用恰好一前一后、
 * 同一个线程、顺序发生」（工具本体可能跑在另一个线程上，这两声永远在循环那个线程里）。
 */
public interface Watcher {

    /** 执行之前。 */
    void starting(int round, String tool, String argsJson);

    /**
     * 执行之后。
     *
     * @param summary    一行摘要（界面 chip 上那一行）
     * @param resultFull 结果全文（注入模型的那一份，已按上限截断）
     * @param ok         成败
     * @param millis     这一次调用花掉的毫秒
     */
    void finished(int round, String tool, String argsJson, String summary,
                  String resultFull, boolean ok, long millis);

    /** 没人看的时候用它。 */
    static Watcher none() {
        return new Watcher() {
            @Override
            public void starting(int round, String tool, String argsJson) {
            }

            @Override
            public void finished(int round, String tool, String argsJson, String summary,
                                 String resultFull, boolean ok, long millis) {
            }
        };
    }
}
