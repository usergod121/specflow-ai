package com.specflow.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「叫停」这一条链的底盘：登记 → 标已取消 → 杀掉它起的进程。
 *
 * <p>用户实报过两件事，各对应这里的一条用例：
 * <ul>
 *   <li><b>找不到叫停</b>——声明层（{@code Actions}）的事，见 {@code ActionsTest}；</li>
 *   <li><b>叫了也不停</b>——就是本文件第三条：光立标记没用，等在那条命令上的那一段
 *       必须因为「进程真的死了」而立刻返回，而不是继续等它自己超时。</li>
 * </ul>
 */
class JobsTest {

    @Test
    @DisplayName("没活在跑时叫停返回 0：调用方据此报 409，而不是回一句「已停下」")
    void cancelWithNothingRunningIsZero() {
        assertThat(Jobs.running()).isZero();
        assertThat(Jobs.cancelAll()).isZero();
    }

    @Test
    @DisplayName("叫停把每一条已登记的长任务标成已取消，登记摘掉就不会误伤")
    void cancelMarksEveryRegisteredTask(@TempDir Path dir) throws Exception {
        EventProgress env = new EventProgress(new EventLog(dir.resolve("env.jsonl")), "env");
        EventProgress dev = new EventProgress(new EventLog(dir.resolve("dev.jsonl")), "dev");
        try (AutoCloseable one = Jobs.register(env); AutoCloseable two = Jobs.register(dev)) {
            assertThat(Jobs.running()).isEqualTo(2);
            assertThat(env.cancelled()).isFalse();

            assertThat(Jobs.cancelAll()).isEqualTo(2);
            assertThat(env.cancelled()).as("① 那条没被标上").isTrue();
            assertThat(dev.cancelled()).as("④ 那条没被标上").isTrue();
        }
        assertThat(Jobs.running()).as("摘掉登记后不该还在跑").isZero();
    }

    @Test
    @DisplayName("叫停真的杀进程：等命令的那一段立刻收场，且算「按你的意思停下」而不是「超时」")
    void cancelKillsTheProcessesItStarted(@TempDir Path dir) throws Exception {
        EventProgress task = new EventProgress(new EventLog(dir.resolve("run.jsonl")), "env");
        AtomicReference<Cmd.Result> result = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try (AutoCloseable lease = Jobs.register(task)) {
                result.set(Cmd.run(List.of("ping", "-n", "60", "127.0.0.1"), null, null, 120,
                        line -> started.set(true)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "jobs-test-worker");
        worker.setDaemon(true);
        worker.start();

        // 等它真的把子进程拉起来（第一行输出 = 已经 attach 过了）
        for (int at = 0; at < 100 && !started.get(); at++) {
            Thread.sleep(100);
        }
        assertThat(started).as("10 秒内没有输出，这条用例没法继续").isTrue();

        long before = System.currentTimeMillis();
        assertThat(Jobs.cancelAll()).isEqualTo(1);

        worker.join(15_000);
        assertThat(worker.isAlive()).as("叫停之后 15 秒还没收场：这就是「叫了也不停」").isFalse();
        assertThat(System.currentTimeMillis() - before)
                .as("叫停之后还等了 %d 毫秒：进程没被杀掉，只是在等它自己超时",
                        System.currentTimeMillis() - before)
                .isLessThan(15_000);
        assertThat(task.cancelled()).as("长任务那一侧没看到取消标记").isTrue();
        assertThat(result.get()).isNotNull();
        assertThat(result.get().timedOut())
                .as("被杀被写成了「超时」：用户会以为是环境慢，而不是他叫停的").isFalse();
    }
}
