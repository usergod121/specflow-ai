package com.specflow.env;

import com.specflow.exception.SpecflowException;
import com.specflow.util.ProcessOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 真的去起一个进程。整个环境这一摊里唯一碰操作系统的地方。
 *
 * <p>做法照抄 {@link com.specflow.verify.CompileVerifier} 与
 * {@link com.specflow.tests.TestScriptVerifier}，理由也一样：
 * <ul>
 *   <li><b>输出重定向到文件，不用管道</b>——管道有缓冲区，写满就死锁，而我们正在等它结束。
 *       {@code docker compose up --wait} 的输出完全可能把缓冲区写满；</li>
 *   <li><b>读输出走 {@link ProcessOutput}</b>——那是这个项目里唯一一份认得 BOM/GBK 的解码实现，
 *       中文 Windows 上 docker 的报错不会炸成编码异常；</li>
 *   <li><b>超时收整棵进程树</b>——{@code 12 秒没回话就杀掉}只杀直接子进程是不够的，
 *       compose 会再起一堆子进程。</li>
 * </ul>
 *
 * <p>命令一律以<b>数组</b>形式传进去（不经 shell）：{@code wsl docker} 这种带前缀的写法
 * 会自然拆成两个参数，而用户写的路径里带空格时也不会被拆错——这正是
 * 「用户手填命令」这条路上最容易出的一种错。
 */
public final class ProcessCommandRunner implements CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessCommandRunner.class);

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    /** 收进程树那条命令等多久：它是系统自带的，正常在毫秒级。 */
    private static final long KILL_TIMEOUT_SECONDS = 10;

    @Override
    public Result run(List<String> command, Map<String, String> environment, Path workdir,
                      long timeoutSeconds) {
        if (command == null || command.isEmpty()) {
            throw new SpecflowException("没有要跑的命令");
        }
        Path outputFile;
        try {
            // 放在系统临时目录，不放进项目：这条命令的输出是中间过程，不属于项目里的任何东西。
            // 它在 finally 里删掉（删不掉也不留话柄——那不在用户的目录里）
            outputFile = Files.createTempFile("specflow-cmd-", ".log");
        } catch (IOException e) {
            throw new SpecflowException("建不了命令输出文件：" + e.getMessage(), e);
        }
        try {
            return execute(command, environment, workdir, timeoutSeconds, outputFile);
        } finally {
            delete(outputFile);
        }
    }

    private Result execute(List<String> command, Map<String, String> environment, Path workdir,
                           long timeoutSeconds, Path outputFile) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workdir != null) {
            builder.directory(workdir.toFile());
        }
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(outputFile.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            // 命令不存在、没有执行权限、路径不合法都落在这里。
            // 它和「跑起来但退出码非 0」是两件事：前者说明这台机器上根本没有这个命令，
            // 后者说明命令跑了但没成功——处理方式完全不同
            return Result.notStarted(e.getMessage() == null ? "" : e.getMessage());
        }

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new SpecflowException("等待命令结束时被中断：" + String.join(" ", command), e);
        }
        if (!finished) {
            killTree(process);
            return new Result(true, Result.NOT_STARTED, true,
                    "超过 " + timeoutSeconds + " 秒没有回话，已经强制终止。到那一刻为止的输出："
                            + System.lineSeparator() + read(outputFile));
        }
        return Result.finished(process.exitValue(), read(outputFile));
    }

    private static String read(Path file) {
        try {
            return ProcessOutput.read(file);
        } catch (IOException e) {
            log.warn("读命令输出失败 {}：{}", file, e.getMessage());
            return "";
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("删命令输出文件失败 {}：{}", file, e.getMessage());
            file.toFile().deleteOnExit();
        }
    }

    /**
     * 收掉整棵进程树。
     *
     * <p>只杀直接子进程是不够的：{@code docker compose} 会再起一堆子进程，
     * 而它们继续占着端口和文件。Windows 走 {@code taskkill /T}（系统自带的树杀）；
     * POSIX 没有等价命令，就<b>先抓一次子孙的句柄</b>再杀——父进程一死，
     * 子进程会被过继给 init，那时候再想找回来就没门了。
     */
    private void killTree(Process process) {
        if (WINDOWS) {
            if (!taskKill(process.pid())) {
                log.debug("taskkill 没能收掉进程树（pid={}），退回只杀直接子进程", process.pid());
            }
        } else {
            List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
        process.destroyForcibly();
    }

    private boolean taskKill(long pid) {
        try {
            Process killer = new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return killer.waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS) && killer.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
