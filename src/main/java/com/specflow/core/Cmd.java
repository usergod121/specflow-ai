package com.specflow.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * <b>起进程的唯一入口</b>（宿主与容器都走它）。
 *
 * <p>一个进程要处理好的四件事，少一件都会变成用户看见的 bug：
 * <ol>
 *   <li><b>超时</b>：这是唯一的保证。没有它，一条卡住的命令会把整条链挂死，
 *       而界面上只是"一直在跑"；</li>
 *   <li><b>超时要真的杀干净</b>：Windows 上 {@code docker}/{@code mvn} 都是"启动器 + 真正干活的子进程"，
 *       只 destroy 外面那个，里面那个会活下来占着端口/文件锁（上一版实测踩过）；</li>
 *   <li><b>逐行回调</b>：长任务的输出要能一边跑一边推上去（"拉镜像时看得到它在拉什么"）；</li>
 *   <li><b>编码</b>：中文 Windows 上 {@code javac}/{@code mvn} 的输出是 GBK，
 *       按 UTF-8 硬读会得到一串问号——那比不显示还糟。</li>
 * </ol>
 */
public final class Cmd {

    /** 逐行保留的上限（完整输出另有落盘的一份，见 {@code Environment}）。 */
    private static final int MAX_KEPT_LINES = 4000;

    private Cmd() {
    }

    /**
     * 一条命令跑完的结果。
     *
     * @param exit     退出码；超时被杀时是 -1
     * @param output   完整输出（到上限为止，按行）
     * @param timedOut 是不是被超时掐掉的（不是"命令失败"，要在话术里分开说）
     */
    public record Result(int exit, String output, boolean timedOut, long millis) {

        public boolean ok() {
            return !timedOut && exit == 0;
        }

        /** 一行结论（报错话术里给人看的那种）。 */
        public String summary() {
            return timedOut ? "超过时限被强制终止" : "退出码 " + exit;
        }
    }

    public static Result run(List<String> argv, Path workdir, long timeoutSeconds) {
        return run(argv, workdir, null, timeoutSeconds, null);
    }

    /**
     * 跑一条命令。
     *
     * @param argv           命令与参数（第一个元素是程序名）
     * @param workdir        工作目录；{@code null} = 服务进程的工作目录
     * @param stdin          喂给它的标准输入；{@code null} = 不喂（立刻 EOF）
     * @param timeoutSeconds 超时秒数；{@code <=0} = 不限（只该给"确定会结束"的短命令用）
     * @param onLine         每一行原始输出（{@code null} = 不看）
     */
    public static Result run(List<String> argv, Path workdir, String stdin, long timeoutSeconds,
                             Consumer<String> onLine) {
        List<String> command = wrap(argv);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workdir != null) {
            builder.directory(workdir.toFile());
        }
        builder.redirectErrorStream(true);
        // docker 的 compose 输出带进度条，给它一个不缓冲的环境变量省得卡住
        Map<String, String> env = builder.environment();
        env.putIfAbsent("COMPOSE_PROGRESS", "plain");

        long started = System.currentTimeMillis();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Result(-1, "起不了这条命令：" + e.getMessage(), false,
                    System.currentTimeMillis() - started);
        }
        Jobs.attach(process, Thread.currentThread());
        try {
            if (stdin != null) {
                try {
                    var out = process.getOutputStream();
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                    out.close();
                } catch (IOException e) {
                    // 写不进去通常意味着进程已经退出了：**不当作失败**，
                    // 让下面照常等它、把它自己的输出与退出码带回去（那才是真原因）
                }
            } else {
                try {
                    process.getOutputStream().close();
                } catch (IOException ignored) {
                    // 关不上就算了：进程读不到东西会自己 EOF
                }
            }
            StringBuilder kept = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    List<Byte> pending = new ArrayList<>();
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        for (int at = 0; at < read; at++) {
                            byte value = buffer[at];
                            if (value == '\n') {
                                emit(pending, kept, onLine);
                            } else if (value != '\r') {
                                pending.add(value);
                            }
                        }
                    }
                    emit(pending, kept, onLine);
                } catch (IOException ignored) {
                    // 进程被杀时读端会报错，这是正常收场的一部分
                }
            }, "cmd-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = timeoutSeconds <= 0
                    ? waitForever(process)
                    : process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
                reader.join(1500);
                return new Result(-1, kept + System.lineSeparator()
                        + "（超过 " + timeoutSeconds + " 秒没有结束，已强制终止）", true,
                        System.currentTimeMillis() - started);
            }
            reader.join(2000);
            return new Result(process.exitValue(), kept.toString(), false,
                    System.currentTimeMillis() - started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            return new Result(-1, "等待被中断（多半是用户叫停）", false,
                    System.currentTimeMillis() - started);
        } finally {
            Jobs.detach(process);
        }
    }

    private static boolean waitForever(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    /** 攒够一行就交出去（同时按行数上限留一份）。 */
    private static void emit(List<Byte> pending, StringBuilder kept, Consumer<String> onLine) {
        if (pending.isEmpty()) {
            return;
        }
        byte[] bytes = new byte[pending.size()];
        for (int at = 0; at < bytes.length; at++) {
            bytes[at] = pending.get(at);
        }
        pending.clear();
        String line = decode(bytes);
        if (kept.length() < 4_000_000) {
            kept.append(line).append('\n');
        } else if (kept.length() < 4_000_100) {
            kept.append("…（输出过长，只留了前面这些）\n");
        }
        if (onLine != null) {
            onLine.accept(line);
        }
    }

    /**
     * 一行字节 → 字符串。
     *
     * <p>先按 UTF-8 严格解：解得出来就是 UTF-8（我们自己的 {@code docker} 输出、以及大多数现代工具）。
     * 解不出来（中文 Windows 上 {@code javac}/{@code mvn} 的 GBK 输出）就按本机默认编码再解一次
     * ——那一串问号对排查毫无帮助，而这类输出恰恰是最需要看清原文的地方。
     */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, Charset.defaultCharset());
        }
    }

    /**
     * Windows 上 {@code npm} / {@code mvn} 是 {@code .cmd}：{@code ProcessBuilder} 直接调它会失败
     * （"不是可执行文件"）。所以按后缀交给 cmd。
     */
    private static List<String> wrap(List<String> argv) {
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("要跑什么？argv 是空的");
        }
        String head = argv.get(0).toLowerCase(java.util.Locale.ROOT);
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (windows && (head.endsWith(".cmd") || head.endsWith(".bat"))) {
            List<String> wrapped = new ArrayList<>(argv.size() + 2);
            wrapped.add("cmd");
            wrapped.add("/c");
            wrapped.addAll(argv);
            return wrapped;
        }
        return argv;
    }

    /**
     * <b>整棵进程树</b>杀掉。
     *
     * <p>为什么不是 {@code destroyForcibly} 就完事：Windows 上它只杀直接子进程，
     * 而 {@code docker compose} / {@code mvn} 都会再起一层——那一层会活下来继续占着容器与文件锁。
     * {@code taskkill /T} 是微软给的那把刀；它不在时退回 {@code destroyForcibly}（干得少，但不会更糟）。
     */
    public static void killTree(Process process) {
        long pid = process.pid();
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (windows) {
            try {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(10, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        process.destroyForcibly();
    }
}
