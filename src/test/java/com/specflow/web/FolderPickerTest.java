package com.specflow.web;

import com.specflow.exception.SpecflowException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 系统目录对话框的「管道」。
 *
 * <p>真对话框没法自动点，但三种结果都要有人管：<b>选中了</b>、<b>点了取消</b>、
 * <b>根本弹不出来</b>。第三种尤其要紧——服务可能是当后台服务起的，没有桌面，
 * 那时候必须给一句人话，然后让用户走手动输入那条路，而不是让他对着「点了没反应」猜。
 *
 * <p>用 {@link FolderPicker#OVERRIDE} 把「弹对话框」换成「跑一条命令」，三种结果都能造出来。
 */
@DisplayName("选择文件夹的对话框")
class FolderPickerTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(FolderPicker.OVERRIDE);
    }

    @Test
    @DisplayName("命令输出一行路径：原样拿回来")
    void returnsThePickedPath() {
        override("echo E:\\seckill");

        assertThat(new FolderPicker().pick()).isEqualTo("E:\\seckill");
    }

    @Test
    @DisplayName("输出里带着空行/空格也不受影响")
    void trimsSurroundingWhitespace() {
        override("echo.& echo   E:\\seckill  ");

        assertThat(new FolderPicker().pick()).isEqualTo("E:\\seckill");
    }

    @Test
    @DisplayName("什么都没有输出 = 用户点了取消，安静地返回 null")
    void emptyOutputMeansCancelled() {
        override("rem 用户把窗口关掉了");

        assertThat(new FolderPicker().pick()).isNull();
    }

    @Test
    @DisplayName("命令失败时说清楚原因，不是返回 null 冒充「取消」")
    void failureIsReported() {
        override("echo boom 1>&2 & exit 3");

        assertThatThrownBy(() -> new FolderPicker().pick())
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("退出码 3")
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("这台机器上根本没有那个命令时，报的也是听得懂的话")
    void missingCommandIsReported() {
        override("specflow-这个命令不存在");

        assertThatThrownBy(() -> new FolderPicker().pick())
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("失败");
    }

    @Test
    @DisplayName("中文系统上 PowerShell 默认写 GBK：选了带中文的目录也得读出来")
    void decodesTheNativeEncodingOfThisMachine() {
        // 认编码那套逻辑搬去了 ProcessOutput（编译日志那边同样要用），这里只保证这条路还接得上：
        // 中文 Windows 上「E:\秒杀」这种目录写出来不是 UTF-8，读不出来就会被当成「弹不出来」
        override("echo E:\\秒杀");

        assertThat(new FolderPicker().pick()).isEqualTo("E:\\秒杀");
    }

    @Test
    @DisplayName("候选里至少有一个是磁盘上真实存在的 shell——PATH 不干净时全靠它")
    void atLeastOneShellExistsOnDisk() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "只在 Windows 上找 PowerShell");

        // 实测：PATH 里没有 System32 时，ProcessBuilder("powershell") 直接
        // CreateProcess error=2，而同一个文件用绝对路径跑得好好的。
        // 所以候选里必须有一个绝对路径，否则就是「本该弹得出来却说弹不出来」的假故障。
        assertThat(FolderPicker.shells())
                .as("候选：" + FolderPicker.shells())
                .anySatisfy(shell -> assertThat(Files.isExecutable(Path.of(shell)))
                        .as(shell + " 应该是真实存在的可执行文件")
                        .isTrue());
    }

    @Test
    @DisplayName("系统对话框那条路全废了时，JVM 自己画的那个真的能弹出来")
    void theInProcessFallbackReallyShowsAWindow() throws Exception {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "没有桌面的环境不适用");

        // 自动点不了真窗口，但窗口是同一个进程里的：等它出现就把它关掉（等于用户点了取消）
        Thread closer = new Thread(() -> closeDialogWhenItAppears(LocalDateTime.now().plusSeconds(8)));
        closer.setDaemon(true);
        closer.start();

        String picked = FolderPicker.swingPick(List.of("测试：假装 PowerShell 起不来"));

        assertThat(picked).isNull();   // 关掉 = 取消
        closer.join();
    }

    /**
     * 等自己画的那个窗口出现，稍等它把目录列完，再关掉它。
     *
     * <p>必须等它自己稳定下来再关：窗口一 visible 就立刻 dispose，会撞上它正在加载目录列表，
     * Swing 内部会抛 {@code IndexOutOfBoundsException}（实测过）。真用户关得快也能撞上，
     * 所以这里照着「正常关窗口」的节奏来。
     *
     * <p>到点了还没等到窗口就关掉所有窗口：万一窗口压根没画出来，被测的
     * {@code invokeAndWait} 会一直挂住，那种失败连原因都看不出来。
     */
    private static void closeDialogWhenItAppears(LocalDateTime deadline) {
        LocalDateTime closedAfter = null;
        while (LocalDateTime.now().isBefore(deadline)) {
            boolean showing = false;
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()) {
                    showing = true;
                    if (closedAfter == null) {
                        closedAfter = LocalDateTime.now().plusSeconds(2);
                    }
                    if (LocalDateTime.now().isAfter(closedAfter)) {
                        SwingUtilities.invokeLater(dialog::dispose);
                        return;
                    }
                }
            }
            if (!showing) {
                closedAfter = null;
            }
            sleep(50);
        }
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                SwingUtilities.invokeLater(window::dispose);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void override(String command) {
        System.setProperty(FolderPicker.OVERRIDE, command);
    }
}
