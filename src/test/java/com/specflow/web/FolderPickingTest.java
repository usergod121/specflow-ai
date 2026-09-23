package com.specflow.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * 「正在挑目录」这件事的状态。
 *
 * <p>两条要害：<b>同时只可能有一个窗口</b>（重复点击不能多弹一个），
 * 以及<b>结果留在服务端</b>（界面刷新了也问得到）。
 */
@DisplayName("挑目录的进行状态")
class FolderPickingTest {

    @TempDir
    Path temp;

    @AfterEach
    void clearOverride() {
        System.clearProperty(FolderPicker.OVERRIDE);
    }

    /**
     * 让「弹窗口」这条命令把每次调用记到文件里，这样能数出到底弹了几次。
     *
     * @param output 命令最后打印的东西；空串表示「用户把窗口关了」
     */
    private Path countingCommand(String output) {
        Path counter = temp.resolve("called.txt");
        String tail = output.isEmpty() ? "rem 用户取消了" : "echo " + output;
        System.setProperty(FolderPicker.OVERRIDE, "echo called >> " + counter + " & " + tail);
        return counter;
    }

    private static FolderPicking picking() {
        return new FolderPicking(new FolderPicker());
    }

    /** 轮询等一个条件成立；超时就说清卡在哪儿——比 sleep 一个固定时长可靠。 */
    private static void waitUntil(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        throw new AssertionError("等不到：" + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("刚开始是「没有窗口」")
    void startsIdle() {
        assertThat(picking().current().status()).isEqualTo(FolderPicking.Status.IDLE);
    }

    @Test
    @DisplayName("用户选中之后，结果留在状态里等界面来问")
    void keepsThePickedPath() {
        countingCommand("E:\\seckill");
        FolderPicking picking = picking();

        picking.start();

        waitUntil("挑完", () -> picking.current().status() == FolderPicking.Status.PICKED);
        assertThat(picking.current().path()).isEqualTo("E:\\seckill");
    }

    @Test
    @DisplayName("窗口开着的时候再点几次：不会再弹一个，返回的是同一个状态")
    void startingAgainWhilePickingDoesNotOpenASecondWindow() throws IOException {
        // 这条命令要磨蹭一会儿，好让「正在挑」这个状态稳定住
        Path counter = temp.resolve("called.txt");
        System.setProperty(FolderPicker.OVERRIDE,
                "echo called >> " + counter + " & ping -n 3 127.0.0.1 >nul & echo E:\\seckill");
        FolderPicking picking = picking();

        FolderPicking.State first = picking.start();
        FolderPicking.State second = picking.start();
        FolderPicking.State third = picking.start();

        assertThat(first.status()).isEqualTo(FolderPicking.Status.PICKING);
        assertThat(second.status()).isEqualTo(FolderPicking.Status.PICKING);
        assertThat(third.status()).isEqualTo(FolderPicking.Status.PICKING);

        // 等这一轮跑完再数调用次数（那会儿文件才写得完）
        waitUntil("挑完", () -> picking.current().status() == FolderPicking.Status.PICKED);
        assertThat(Files.readAllLines(counter)).as("弹窗口的次数").hasSize(1);
    }

    @Test
    @DisplayName("用户点了取消：状态是取消，不是「选中了一个空路径」")
    void cancelIsItsOwnState() throws IOException {
        Path counter = countingCommand("");   // 什么都不输出 = 用户把窗口关了
        FolderPicking picking = picking();

        picking.start();

        waitUntil("用户取消", () -> picking.current().status() == FolderPicking.Status.CANCELLED);
        assertThat(picking.current().path()).isNull();
        assertThat(Files.readAllLines(counter)).as("窗口确实弹过一次").hasSize(1);
    }

    @Test
    @DisplayName("弹不出来：原因留在状态里，界面照原话说给用户听")
    void failureKeepsTheReason() {
        System.setProperty(FolderPicker.OVERRIDE, "echo boom 1>&2 & exit 7");
        FolderPicking picking = picking();

        picking.start();

        waitUntil("失败", () -> picking.current().status() == FolderPicking.Status.FAILED);
        assertThat(picking.current().error()).contains("退出码 7").contains("boom");
    }

    @Test
    @DisplayName("上一次挑完之后再点：那是一次新的挑目录，会重新弹窗口")
    void startingAfterAFinishedPickOpensANewWindow() throws IOException {
        Path counter = countingCommand("E:\\seckill");
        FolderPicking picking = picking();

        picking.start();
        waitUntil("挑完", () -> picking.current().status() == FolderPicking.Status.PICKED);

        picking.start();

        waitUntil("又弹了一次", () -> {
            try {
                return Files.readAllLines(counter).size() == 2;
            } catch (IOException e) {
                return false;
            }
        });
    }

    @Test
    @DisplayName("start() 立刻返回：它只是「开始挑」，不是「挑完」")
    void doesNotBlockTheCaller() {
        countingCommand("E:\\seckill");
        FolderPicking picking = picking();

        assertTimeout(Duration.ofMillis(500), picking::start);

        waitUntil("挑完", () -> picking.current().status() == FolderPicking.Status.PICKED);
    }

    @Test
    @DisplayName("命令根本起不来时也要有结束状态，不能一直挂在「正在挑」")
    void aBrokenCommandStillFinishes() {
        System.setProperty(FolderPicker.OVERRIDE, "specflow-这个命令不存在");
        FolderPicking picking = picking();

        picking.start();

        waitUntil("失败", () -> picking.current().status() == FolderPicking.Status.FAILED);
        assertThat(picking.current().error()).isNotBlank();
    }

    @Test
    @DisplayName("状态一旦有结果就不会自己变回去——界面晚点来问也拿得到")
    void theResultStaysUntilTheNextPick() {
        countingCommand("E:\\seckill");
        FolderPicking picking = picking();
        picking.start();
        waitUntil("挑完", () -> picking.current().status() == FolderPicking.Status.PICKED);

        sleep(300);

        assertThat(picking.current().status()).isEqualTo(FolderPicking.Status.PICKED);
        assertThat(picking.current().path()).isEqualTo("E:\\seckill");
    }

    @Test
    @DisplayName("弹窗口时抛的是 Error 也要有结果——不然状态永远停在「正在挑」，界面永久卡死")
    void anErrorStillEndsUpAsFailed() {
        // 精简运行时（jlink 出来的 JRE）没有 java.desktop，swingPick 会抛 NoClassDefFoundError。
        // 只接 RuntimeException 的话它穿过去，状态永远停在 PICKING：按钮永久禁用、
        // 没有窗口开着、也没有任何错误可看，只能重启服务。
        FolderPicking picking = new FolderPicking(() -> {
            throw new NoClassDefFoundError("java/awt/GraphicsEnvironment");
        });

        picking.start();

        waitUntil("失败", () -> picking.current().status() == FolderPicking.Status.FAILED);
        assertThat(picking.current().error()).contains("GraphicsEnvironment");
    }

    @Test
    @DisplayName("认领之后回到「没有窗口」——不然选好的目录会在下次进欢迎页时自己弹开")
    void consumingGoesBackToIdle() {
        countingCommand("E:\\seckill");
        FolderPicking picking = picking();
        picking.start();
        waitUntil("挑完", () -> picking.current().status() == FolderPicking.Status.PICKED);

        assertThat(picking.consume().status()).isEqualTo(FolderPicking.Status.IDLE);
        assertThat(picking.current().path()).isNull();
    }

    @Test
    @DisplayName("中途收掉窗口：状态回到「没有窗口」，还能重新开始")
    void abortStopsTheWindow() {
        FolderPicking picking = picking();

        picking.abort();

        assertThat(picking.current().status()).isEqualTo(FolderPicking.Status.IDLE);
    }

    @Test
    @DisplayName("没开始过就问状态：是「没有窗口」，不是异常")
    void askingWithoutStartingIsFine() {
        FolderPicking.State state = picking().current();

        assertThat(state.status()).isEqualTo(FolderPicking.Status.IDLE);
        assertThat(state.path()).isNull();
        assertThat(state.error()).isNull();
    }
}
