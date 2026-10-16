package com.specflow.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * <b>长任务的唯一过程口</b>：起环境、编译、跑 Agent——它们说给自己的每一句话都从这里出去。
 *
 * <p>为什么要一个接口而不是各自直接写事件日志：上一版有两条独立的进度通道，
 * 结果「起环境」的进度写进了 A 通道，而界面读的是 B 通道 —— 屏幕上什么都没有，
 * 用户的原话是「我只听到电脑风扇一直转」。这一版只有一条：
 * {@link EventProgress} 把过程写成事件，界面读的就是那份事件流。
 *
 * <p>「停下」也走这里：{@link #cancelled()} 是长任务<b>唯一</b>该问的问题。
 * 立标记 + 杀进程树由 {@link Jobs} 负责，长任务每一段之间看一眼就够了。
 */
public interface Progress {

    /** 一步开始（`command` 可以空；`timeoutSeconds<=0` = 不限时）。 */
    void step(String name, String command, long timeoutSeconds);

    /** 这一步跑完了（成功）。 */
    void stepDone(String name);

    /** 子进程输出的<b>原始一行</b>。 */
    void line(String text);

    /** 一句提醒。 */
    void warn(String text);

    /**
     * 失败：`raw` 是<b>原始错误全文</b>，不许总结、不许只留第一行
     * ——真正有用的那几行（缺哪个包、哪一行语法错）经常正好在被总结掉的地方。
     */
    void fail(String text, String raw);

    /** 这一条长任务收场（成功）。 */
    void done(String text, Map<String, Object> extra);

    boolean cancelled();

    long elapsedSeconds();

    /** 已经跑过的步骤（收场载荷里那一栏）；形状 `{name, command, ok, seconds}`。 */
    List<Map<String, Object>> steps();

    /** 没人看的时候用它（命令行、单元测试）。 */
    static Progress quiet() {
        return new Progress() {
            private long started = System.currentTimeMillis();

            @Override
            public void step(String name, String command, long timeoutSeconds) {
            }

            @Override
            public void stepDone(String name) {
            }

            @Override
            public void line(String text) {
            }

            @Override
            public void warn(String text) {
            }

            @Override
            public void fail(String text, String raw) {
            }

            @Override
            public void done(String text, Map<String, Object> extra) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public long elapsedSeconds() {
                return (System.currentTimeMillis() - started) / 1000;
            }

            @Override
            public List<Map<String, Object>> steps() {
                return List.of();
            }
        };
    }
}
