package com.specflow.verify;

import com.specflow.exception.SpecflowException;
import com.specflow.project.BuildConfig;
import com.specflow.project.ProjectConfig;
import com.specflow.spec.Spec;
import com.specflow.spec.VerifySpec;
import com.specflow.util.ProcessOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 编译校验：跑一遍项目自己的编译命令，看改完的东西还能不能编过。
 *
 * <p>为什么这件事归「代码 Agent」而不是「测试 Agent」：
 * 它是<b>最便宜且最强</b>的一道自检。编译器给出的错误带着文件名、行号和原因，
 * 模型拿回去几乎总能自己修好。而它只需要一条项目上本来就存在的命令。
 * 测试校验成本高得多，收益也集中在行为正确性上，因此拆成独立阶段。
 *
 * <p>结果输出会被截断后喂回给模型，所以截断策略保留<b>尾部</b>：
 * Maven / Gradle 都把编译错误打在输出的后半段，开头是下载依赖之类的噪声。
 *
 * <p>进程输出重定向到临时文件而不是管道：管道有缓冲区，进程写满后会阻塞，
 * 而我们在等进程结束——互相等，就是死锁。写文件没有这个问题。
 */
public final class CompileVerifier implements Verifier {

    private static final Logger log = LoggerFactory.getLogger(CompileVerifier.class);

    private static final String NAME = "编译校验";
    private static final int MAX_OUTPUT_CHARS = 20_000;
    private static final long TIMEOUT_MINUTES = 5;

    /** 保留下来的失败日志用的文件名时间戳。 */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 输出里指「完整日志在哪」的那句话的开头。
     *
     * <p>编译输出是给模型看的，会被截断；而给人类看的那段解释只摘「原话」。
     * 指路的这一句得能被认出来，否则用户知道缺依赖、却不知道去哪儿看全。
     */
    static final String LOG_HINT = "（完整日志已保留：";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public VerificationResult verify(VerificationContext context) {
        VerifySpec verify = context.spec().verify();
        if (!verify.compile()) {
            return VerificationResult.skipped(NAME, "spec.verify.compile = false，已跳过");
        }

        String command = resolveCommand(context);
        if (command == null) {
            return VerificationResult.skipped(NAME,
                    "未配置编译命令；请在 .specflow/project.yaml 的 build.compile 中指定，"
                            + "例如 \"mvn -q -DskipTests compile\"");
        }

        return run(command, context.projectRoot());
    }

    /**
     * spec 的 {@code verify.compile-command} 优先于项目配置，
     * 这样个别任务临时换一条更快的命令不需要改项目配置。
     */
    private String resolveCommand(VerificationContext context) {
        return compileCommandOf(context.spec(), context.project());
    }

    /**
     * 这一次真正会跑的那条编译命令：spec 里写了就用 spec 的，否则用 {@code project.yaml} 的。
     *
     * <p><b>公开出来，是为了让「这条命令从哪来」只有一处。</b>用它的一共两处：编译校验自己，
     * 以及测试生成那一段（见 {@code TestProtocol.buildNotice}）。后者是 2026-10-04 才接上的——
     * 在那之前，引擎天天用这条命令编译这个项目，却一个字都没告诉写测试的那个模型，
     * 真项目上于是表现成「它把第三方 jar 的路径猜成一个约定，再要求用户把 jar 放进去」。
     *
     * <p>两处各解析一遍的话，以后再加一种「命令从哪来」的来源，必然只改一处。
     *
     * <p><b>它对什么输入都要给得出答案</b>（{@code spec} 可为 null，{@code ProjectConfig.build()}
     * 也可能是 null——手工 {@code new} 出来的配置就是这样，{@code ProjectConfig.of()} 才负责补默认值）。
     * 这不是替谁擦屁股：调用方里有测试生成那一条，它拿到的是调用方给的配置，
     * 没有理由要求对方先归一化过。
     *
     * @return 命令（已去掉首尾空白）；两处都没配时返回 {@code null}
     */
    public static String compileCommandOf(Spec spec, ProjectConfig project) {
        String fromSpec = spec == null ? null : spec.verify().compileCommand();
        if (fromSpec != null && !fromSpec.isBlank()) {
            return fromSpec.strip();
        }
        BuildConfig build = project == null ? null : project.build();
        String fromProject = build == null ? null : build.compile();
        return fromProject == null || fromProject.isBlank() ? null : fromProject.strip();
    }

    /**
     * 跑命令、读输出。
     *
     * <p>除了成败，还有两件事必须做对：
     * <ul>
     *   <li><b>失败时把日志留给用户</b>——以前无论成败都删，于是「到底错在哪一行」这个最有价值的
     *       信息随临时文件一起没了，用户只能自己回项目里手跑一遍命令。</li>
     *   <li><b>读不出日志也不能炸成「运行中断」</b>——那会让这一轮既没有结论、又不回滚。
     *       读不出来就算一次失败（谁的责任说不清，交给人处理），不抛异常。</li>
     * </ul>
     */
    private VerificationResult run(String command, Path projectRoot) {
        Path log = createLogFile();
        boolean keep = false;
        try {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
            builder.directory(projectRoot.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());

            Process process = start(builder, command);
            boolean finished = await(process);

            String output;
            try {
                output = truncate(ProcessOutput.read(log), command);
            } catch (IOException e) {
                keep = keepLog(log, projectRoot) != null;
                return VerificationResult.failed(NAME, command,
                        "读不出编译输出（" + e.getMessage() + "）。原始日志见：.specflow/logs/",
                        VerificationResult.Kind.ENVIRONMENT);
            }

            if (!finished) {
                process.destroyForcibly();
                Path kept = keepLog(log, projectRoot);
                keep = true;
                return VerificationResult.failed(NAME, command,
                        "编译命令超过 " + TIMEOUT_MINUTES + " 分钟未结束，已强制终止。"
                                + System.lineSeparator() + "完整日志：" + shown(projectRoot, kept)
                                + System.lineSeparator() + output,
                        VerificationResult.Kind.ENVIRONMENT);
            }
            if (process.exitValue() == 0) {
                return VerificationResult.passed(NAME, command, output);
            }
            Path kept = keepLog(log, projectRoot);
            keep = true;
            return VerificationResult.failed(NAME, command,
                    output + System.lineSeparator() + LOG_HINT + shown(projectRoot, kept) + "）",
                    CompileFailure.classify(output));
        } finally {
            if (!keep) {
                deleteLog(log);
            }
        }
    }

    /**
     * 把失败日志搬进项目，返回它的路径；搬不动返回 {@code null}。
     *
     * <p>放 {@code .specflow/logs/}——和快照、运行记录同一个地方（都在 .gitignore 里）。
     * 临时目录会被系统清掉，而排查依赖问题时人往往要回头看这份日志。
     */
    private Path keepLog(Path tempLog, Path projectRoot) {
        try {
            Path dir = projectRoot.resolve(".specflow").resolve("logs");
            Files.createDirectories(dir);
            Path target = dir.resolve("compile-" + LocalDateTime.now().format(STAMP) + ".log");
            Files.move(tempLog, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            log.warn("编译日志搬不进项目目录：{}", e.getMessage());
            return null;
        }
    }

    private static String shown(Path projectRoot, Path keptLog) {
        if (keptLog == null) {
            return "（临时文件，可能已被系统回收）";
        }
        return projectRoot.toAbsolutePath().normalize()
                .relativize(keptLog.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private Process start(ProcessBuilder builder, String command) {
        try {
            return builder.start();
        } catch (IOException e) {
            throw new SpecflowException("无法启动编译命令 '" + command + "'：" + e.getMessage(), e);
        }
    }

    private boolean await(Process process) {
        try {
            return process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SpecflowException("等待编译命令结束时被中断", e);
        }
    }

    private Path createLogFile() {
        try {
            return Files.createTempFile("specflow-compile-", ".log");
        } catch (IOException e) {
            throw new SpecflowException("无法创建编译日志临时文件：" + e.getMessage(), e);
        }
    }

    private void deleteLog(Path log) {
        try {
            Files.deleteIfExists(log);
        } catch (IOException e) {
            // 临时文件清理失败不影响校验结论，交给操作系统的临时目录回收即可。
            log.toFile().deleteOnExit();
        }
    }

    /**
     * 保留尾部。截断时显式说明「前面被丢掉了」，避免模型以为这就是全部输出。
     */
    private String truncate(String output, String command) {
        String text = output == null ? "" : output;
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        int dropped = text.length() - MAX_OUTPUT_CHARS;
        return "…（命令 `" + command + "` 的输出前 " + dropped + " 个字符已省略）\n"
                + text.substring(dropped);
    }

    /** Windows 走 cmd，其余走 sh。项目配置里写的是完整命令行，交给 shell 解析比自己做分词可靠。 */
    private List<String> shellCommand(String command) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }
}
