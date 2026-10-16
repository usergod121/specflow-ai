package com.specflow.test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * <b>高危命令停下来问用户 y/n</b>（合同 §6.6）。
 *
 * <p>「停下来」在同步循环里只有一个落法：<b>执行命令的那个线程阻塞住</b>，
 * 一直等到 HTTP 那边有人回答了，再把答案还给工具。所以这里三样东西：
 * 一张待回答的表（界面画 y/n 面板）、一个 {@link CompletableFuture}（线程在它上面等）、
 * 一条<b>时限</b>（等不到就按拒处理——用户关掉浏览器不该把一条线程挂死 15 分钟）。
 *
 * <p>判据（哪些命令算高危）不在这里，在 {@code env.CommandGuard.highRisk}——
 * 与「一律拒」那张表是同一张，两处不会各说一套。
 *
 * <p><b>一个项目一份</b>（挂在 web 的 Session 上）：换项目之后上一条链的待审批
 * 不该再出现在新项目的界面上。
 */
public final class Approvals implements Approval {

    /** 等不到回答就按拒处理：必须比 HTTP 客户端那些超时短。 */
    public static final long WAIT_SECONDS = 10 * 60;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 一把锁 + 一张有序表。
     *
     * <p>为什么不是 {@code ConcurrentHashMap}：面板的合同是<b>按问的先后</b>画，而它的迭代顺序
     * 不保证（同一条命会忽前忽后）；顺手还得让 {@code seq} 自增有锁——
     * 两条命令前后脚问的时候，「a1 / a2」不能撞号。
     */
    private final Object lock = new Object();

    private final Map<String, Waiting> waiting = new LinkedHashMap<>();

    private int seq;

    private record Waiting(String id, String command, String reason, String askedAt,
                           CompletableFuture<Boolean> answer) {
    }

    @Override
    public boolean ask(String command, String reason) {
        Waiting one;
        synchronized (lock) {
            one = new Waiting("a" + (++seq), command, reason,
                    LocalDateTime.now().format(STAMP), new CompletableFuture<>());
            waiting.put(one.id(), one);
        }
        try {
            return Boolean.TRUE.equals(one.answer().get(WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            synchronized (lock) {
                waiting.remove(one.id());
            }
        }
    }

    /** 界面要的那一栏（按问的先后）。 */
    public List<Map<String, Object>> pending() {
        synchronized (lock) {
            List<Map<String, Object>> found = new ArrayList<>();
            for (Waiting one : waiting.values()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", one.id());
                item.put("command", one.command());
                item.put("reason", one.reason());
                item.put("askedAt", one.askedAt());
                found.add(item);
            }
            return List.copyOf(found);
        }
    }

    /**
     * 用户点了 y/n。
     *
     * @return {@code true} = 这一条确实在等、已经答上了；{@code false} = 没有这一条
     *         （可能已经超时被撤掉了）——界面据此说"这一条已经不在了"，而不是假装点成功了
     */
    public boolean answer(String id, boolean allow) {
        Waiting one;
        synchronized (lock) {
            one = id == null ? null : waiting.get(id.strip());
        }
        // 先出锁再 complete：叫醒的是一个正卡在 get() 上、醒来后还要回来拿同一把锁的线程
        return one != null && one.answer().complete(allow);
    }

    /** 换项目 / 关服务时收掉：等在里面的线程要醒过来（按拒收场）。 */
    public void clear() {
        List<Waiting> all;
        synchronized (lock) {
            all = new ArrayList<>(waiting.values());
            waiting.clear();
        }
        for (Waiting one : all) {
            one.answer().complete(false);
        }
    }
}
