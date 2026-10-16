package com.specflow.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>正在跑的长任务登记处</b>：叫停时要能回答两个问题——
 * 「现在有什么在跑」「要杀谁」。
 *
 * <p>为什么不打断线程：杀进程之后子进程很快退出、{@code waitFor} 随即返回，
 * 长任务那一侧按 {@link Progress#cancelled()} 收场成「已按你的意思停下」。
 * 用 {@code interrupt} 的话，等在那里的那一层会先抛一个"等待被中断"的异常，
 * 把「是你让停的」变成一句看起来像故障的话——而那正是用户在界面上最不想看到的。
 *
 * <p>进程按<b>起它的那个线程</b>登记：一条长任务跑在自己的线程上，
 * 叫停时只杀这条线程起的进程，不会误伤别的正在跑的活。
 */
public final class Jobs {

    /** 线程 → 它在跑的那些进程。 */
    private static final Map<Thread, Set<Process>> RUNNING = new ConcurrentHashMap<>();

    /** 活着的长任务（谁在跑、跑什么）。 */
    private static final Set<Progress> TASKS = ConcurrentHashMap.newKeySet();

    private Jobs() {
    }

    static void attach(Process process, Thread owner) {
        RUNNING.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(process);
    }

    static void detach(Process process) {
        long pid = process.pid();
        for (Set<Process> mine : RUNNING.values()) {
            mine.removeIf(one -> one.pid() == pid);
        }
    }

    /** 登记一条长任务（它自己会 {@code close()} 摘掉自己）。 */
    public static AutoCloseable register(Progress task) {
        TASKS.add(task);
        return () -> TASKS.remove(task);
    }

    /** 现在有几条长任务在跑。 */
    public static int running() {
        return TASKS.size();
    }

    /**
     * 叫停：把所有长任务标记成已取消，并<b>杀掉它们起的进程树</b>。
     *
     * <p>先立标记再杀：反过来的话，进程被杀那一刻长任务可能已经把"退出码非 0"
     * 当成一次失败收场了，用户看到的是「失败了」而不是「是你让停的」。
     *
     * @return 停了几条（0 = 现在没有东西可停，调用方据此报 409）
     */
    public static int cancelAll() {
        List<Progress> tasks = List.copyOf(TASKS);
        for (Progress task : tasks) {
            if (task instanceof EventProgress event) {
                event.markCancelled();
            }
        }
        int killed = 0;
        for (Map.Entry<Thread, Set<Process>> entry : RUNNING.entrySet()) {
            for (Process process : entry.getValue()) {
                if (process.isAlive()) {
                    Cmd.killTree(process);
                    killed++;
                }
            }
        }
        return tasks.isEmpty() ? 0 : tasks.size();
    }
}
