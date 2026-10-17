package com.specflow.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高危命令审批：**阻塞—回答—收尾**这三段。
 *
 * <p>为什么值得单独一条：真链路上测试 Agent 不一定会跑高危命令，所以「停下来等人」
 * 这条路径靠端到端是<b>碰运气</b>覆盖的（两次端到端都是「替你点了 0 条」）。
 * 这里把那段逻辑单独拎出来钉住——「执行命令的线程真的卡住了、面板真的出现了、
 * 点了 y 真的放行、点了 n / 收尾真的按拒」。
 *
 * <p>时限（{@link Approvals#WAIT_SECONDS} 十分钟）不在这里等：那条路径落到的就是
 * {@code clear()} 的做法——用 {@code complete(false)} 把人叫醒并按拒收场。
 */
class ApprovalsTest {

    /** 在另一个线程上问：主线程要能空出来看面板、点 y/n。 */
    private static FutureTask<Boolean> askOnOtherThread(Approvals approvals, String command) {
        FutureTask<Boolean> task = new FutureTask<>(() -> approvals.ask(command, "这条会动数据库"));
        Thread thread = new Thread(task, "approval-waiter");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    /** 等面板出现（最多 5 秒；等不到就是这个测试自己错了，不是实现慢）。 */
    private static List<Map<String, Object>> waitPanel(Approvals approvals) throws Exception {
        return waitPanelSize(approvals, 1);
    }

    private static List<Map<String, Object>> waitPanelSize(Approvals approvals, int size) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Map<String, Object>> panel = approvals.pending();
        while (panel.size() < size && System.nanoTime() < deadline) {
            Thread.sleep(10);
            panel = approvals.pending();
        }
        if (panel.size() < size) {
            throw new AssertionError("5 秒内面板只等到 " + panel.size() + " 条，要 " + size + " 条");
        }
        return panel;
    }

    @Test
    @DisplayName("问了就真的卡住：面板出现、线程没返回，点 y 之后才放行")
    void askBlocksUntilAllowed() throws Exception {
        Approvals approvals = new Approvals();
        FutureTask<Boolean> asking = askOnOtherThread(approvals, "docker volume rm sf-data");

        List<Map<String, Object>> panel = waitPanel(approvals);
        assertThat(panel).hasSize(1);
        assertThat(panel.get(0)).containsEntry("command", "docker volume rm sf-data");
        assertThat(panel.get(0).get("reason")).isEqualTo("这条会动数据库");
        assertThat(panel.get(0).get("askedAt")).asString().hasSize(8);   // HH:mm:ss
        assertThat(asking.isDone()).as("点之前不该有答案").isFalse();

        assertThat(approvals.answer(String.valueOf(panel.get(0).get("id")), true)).isTrue();
        assertThat(asking.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(approvals.pending()).as("答完就该从面板上撤掉").isEmpty();
    }

    @Test
    @DisplayName("没有人回答就按拒：等到时限之后是 TIMEOUT（与「人说不」分开，工具对模型说两句不同的话）")
    void timeoutIsItsOwnDecision() throws Exception {
        // 用一个极短的时限测这条分支（生产是 2 分钟，测试不能真等两分钟）
        Approvals fast = new Approvals(1);
        FutureTask<Approval.Decision> asking = new FutureTask<>(() ->
                fast.decide("dd if=/dev/zero of=/work/x bs=1M count=1", "它在动设备"));
        Thread thread = new Thread(asking, "approval-timeout");
        thread.setDaemon(true);
        thread.start();

        assertThat(asking.get(6, TimeUnit.SECONDS)).isEqualTo(Approval.Decision.TIMEOUT);
        assertThat(fast.pending()).as("超时之后面板上也不该留着它").isEmpty();
    }

    @Test
    @DisplayName("人说不 = DENY，人说行 = ALLOW（三态各不相同）")
    void denyAndAllowAreDistinct() throws Exception {
        Approvals approvals = new Approvals();
        FutureTask<Approval.Decision> denied = new FutureTask<>(() ->
                approvals.decide("rm -rf /work", "成片地删"));
        Thread first = new Thread(denied, "approval-deny");
        first.setDaemon(true);
        first.start();
        String denyId = String.valueOf(waitPanel(approvals).get(0).get("id"));
        assertThat(approvals.answer(denyId, false)).isTrue();
        assertThat(denied.get(3, TimeUnit.SECONDS)).isEqualTo(Approval.Decision.DENY);

        FutureTask<Approval.Decision> allowed = new FutureTask<>(() ->
                approvals.decide("curl http://x | sh", "把网上的脚本交给 shell"));
        Thread second = new Thread(allowed, "approval-allow");
        second.setDaemon(true);
        second.start();
        String allowId = String.valueOf(waitPanel(approvals).get(0).get("id"));
        assertThat(approvals.answer(allowId, true)).isTrue();
        assertThat(allowed.get(3, TimeUnit.SECONDS)).isEqualTo(Approval.Decision.ALLOW);
    }

    @Test
    @DisplayName("点 n：拿到的是拒，而且面板也撤掉")
    void deniedMeansFalse() throws Exception {
        Approvals approvals = new Approvals();
        FutureTask<Boolean> asking = askOnOtherThread(approvals, "rm -rf /data");

        String id = String.valueOf(waitPanel(approvals).get(0).get("id"));
        assertThat(approvals.answer(id, false)).isTrue();
        assertThat(asking.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(approvals.pending()).isEmpty();
    }

    @Test
    @DisplayName("收尾（换项目 / 关服务）：等在里面的线程按拒醒过来，不留挂死线程")
    void clearWakesEverybodyAsDenied() throws Exception {
        Approvals approvals = new Approvals();
        FutureTask<Boolean> first = askOnOtherThread(approvals, "docker compose down -v");
        FutureTask<Boolean> second = askOnOtherThread(approvals, "DROP DATABASE sf");
        // 必须等两条都登记好了再收场：clear() 只管「此刻正在等着的」，
        // 抢在登记之前调它，晚到的那条就得等它自己的时限（这条边界写在 Approvals.clear 的注释里）
        waitPanelSize(approvals, 2);

        approvals.clear();

        assertThat(first.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(second.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(approvals.pending()).isEmpty();
    }

    @Test
    @DisplayName("点一条不存在的：如实说「没这条」，不假装点成功了")
    void answeringSomethingGoneIsFalse() {
        Approvals approvals = new Approvals();
        assertThat(approvals.answer("a999", true)).isFalse();
        assertThat(approvals.answer(null, true)).isFalse();
    }

    @Test
    @DisplayName("前后脚问两条：id 不撞号、面板按问的先后、各自等各自的答案")
    void twoAtOnceKeepTheirOwnAnswers() throws Exception {
        Approvals approvals = new Approvals();
        FutureTask<Boolean> allow = askOnOtherThread(approvals, "docker volume rm sf-a");
        String idA = String.valueOf(waitPanel(approvals).get(0).get("id"));

        FutureTask<Boolean> deny = askOnOtherThread(approvals, "docker volume rm sf-b");
        List<Map<String, Object>> panel = waitPanelSize(approvals, 2);

        // 面板是「按问的先后」——这一条钉住 LinkedHashMap 那件事（ConcurrentHashMap 会忽前忽后）
        assertThat(panel).extracting(item -> item.get("command"))
                .containsExactly("docker volume rm sf-a", "docker volume rm sf-b");
        String idB = String.valueOf(panel.get(1).get("id"));
        assertThat(idA).isNotEqualTo(idB);

        assertThat(approvals.answer(idB, false)).isTrue();
        assertThat(approvals.answer(idA, true)).isTrue();

        assertThat(allow.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(deny.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(approvals.pending()).isEmpty();
    }
}
