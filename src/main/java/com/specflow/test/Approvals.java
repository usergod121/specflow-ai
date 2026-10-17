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

    /**
     * 等不到回答就按拒处理。
     *
     * <h2>为什么从 10 分钟缩到 2 分钟（这一条是改过的）</h2>
     * 十分钟的意思是"你人不在的十分钟里，这一格什么都不做，而且你看不到它在等"。
     * 实测里那条 sudo 审批让用户等了 187 秒——而<b>问题根本不是等得久，是根本不该问</b>
     * （容器里没有 sudo）。所以：
     * <ul>
     *   <li>该问的只剩"真有可能正当"的那几种（{@code dd}、{@code curl | sh}、{@code chmod 777}、成片删）；</li>
     *   <li>问出来之后只等两分钟：等不到就<b>按拒</b>，并在回给模型的话里写清
     *       "这条用例请标 ERROR 说清'等审批超时'，用户回答后用「重试这一条」重跑"。</li>
     * </ul>
     * 这样一次审批最坏拿走两分钟，而不是十分钟；而"事后回答 + 单条重试"这条路
     * 已经能覆盖绝大多数真实场景（见 {@code docs/BOOK.html} 关于"可恢复中断"的那一节）。
     */
    public static final long WAIT_SECONDS = 2 * 60;

    /** 超时按拒时给模型看的那句话（三件事：发生了什么、现在该怎么办、用户怎么续跑）。 */
    public static String timedOutHint(String command) {
        return "`" + command + "` 停下来等你回答，等了 " + (WAIT_SECONDS / 60) + " 分钟没人回答，"
                + "已经按<b>拒绝</b>处理、没有执行。这不是命令失败。"
                + "现在这样做：换一条不需要审批的命令达到同样目的；"
                + "如果非它不可，就先把这条用例标 ERROR 并写明「等审批超时」，"
                + "用户看到后回答那条审批、再点「重试这一条」，从头验这一条即可。";
    }

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

    /** 这一次愿意等多久（秒）。默认 {@link #WAIT_SECONDS}；测试用它把 2 分钟缩成 1 秒。 */
    private final long waitSeconds;

    private int seq;

    public Approvals() {
        this(WAIT_SECONDS);
    }

    /** @param waitSeconds 等的时限（秒）；{@code <=0} 用默认。**只为测试留的**，生产用无参构造。 */
    public Approvals(long waitSeconds) {
        this.waitSeconds = waitSeconds <= 0 ? WAIT_SECONDS : waitSeconds;
    }

    private record Waiting(String id, String command, String reason, String askedAt,
                           CompletableFuture<Boolean> answer) {
    }

    @Override
    public Decision decide(String command, String reason) {
        Waiting one;
        synchronized (lock) {
            one = new Waiting("a" + (++seq), command, reason,
                    LocalDateTime.now().format(STAMP), new CompletableFuture<>());
            waiting.put(one.id(), one);
        }
        try {
            Boolean allowed = one.answer().get(waitSeconds, TimeUnit.SECONDS);
            // 没人回答时 future 由 clear() 以 false 收场，但那是"换项目/关服务"，
            // 与"用户点了 n" 一样都按拒——区别只在工具对模型说的话（见 Approval.Decision）
            return Boolean.TRUE.equals(allowed) ? Decision.ALLOW : Decision.DENY;
        } catch (java.util.concurrent.TimeoutException e) {
            return Decision.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.DENY;
        } catch (Exception e) {
            return Decision.DENY;
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

    /**
     * 换项目 / 关服务时收掉：等在里面的线程要醒过来（按拒收场）。
     *
     * <p><b>边界</b>：它只管「此刻正在等着的」那一批。抢在这次收场<b>之后</b>才登记进来的
     * 那一条不在内——它会一直等到自己的时限（{@link #WAIT_SECONDS}）。
     * 换项目时那条链路（{@code Runs}）已经在收尾，这个窗口是毫秒级；真要彻底消掉，
     * 得让 {@code ask} 带一个「这一轮已经作废」的记号，那是 {@code Runs} 那一层的事，
     * 这一版没做（写在 {@code docs/DELIVERY.md} 第四节）。
     */
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
