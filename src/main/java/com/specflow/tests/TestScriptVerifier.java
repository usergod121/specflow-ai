package com.specflow.tests;

import com.specflow.exception.SpecflowException;
import com.specflow.util.ProcessOutput;
import com.specflow.verify.VerificationContext;
import com.specflow.verify.VerificationResult;
import com.specflow.verify.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 测试脚本的执行器——「跑脚本、看退出码」这条链上唯一动手的那一环。
 *
 * <p>它<b>只做一件事</b>：在项目根目录下执行生成的入口脚本，然后看退出码。
 * 不判断谁对谁错、不解析失败清单、不回喂——那些是 {@link TestReport} 与上层的事。
 * 这条边界撑住了「引擎零知识」：它不知道项目是什么语言、测试用什么框架，
 * 因为「怎么跑」全写在那个脚本里了（十五.1）。
 *
 * <p>结构性做法照抄 {@link com.specflow.verify.CompileVerifier}：进程输出重定向到
 * <b>文件</b>而不是管道（管道有缓冲区，写满就死锁，而我们在等它结束）、
 * 工作目录锁在项目根、超时后强制终止、读输出走 {@link ProcessOutput}
 * （唯一一份认得 BOM/GBK 的解码实现，中文报错不会炸成编码异常）。
 *
 * <p><b>为什么要读原始输出、而不只看退出码。</b>退出码只答得出「有没有过」，
 * 而人要的是「哪条没过、期望什么、实际什么」。那份信息在脚本打印的那几行里，
 * 退出码只是它的一个索引（十五.4）。
 */
public final class TestScriptVerifier implements Verifier {

    private static final Logger log = LoggerFactory.getLogger(TestScriptVerifier.class);

    /** 校验器名字。它出现在 CLI 那一行、留档和给用户的结论里，所以是公开的。 */
    public static final String NAME = "测试脚本";

    /** 留给人看的输出长度上限。超了就掐头去尾保中间——两头都留着有用（见 shorten）。 */
    private static final int MAX_OUTPUT_CHARS = 20_000;

    /** 时限：超过它就算这次跑不完，收掉整棵进程树。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 5 * 60;

    /** 收进程树那条命令等多久：它是系统自带的，正常在毫秒级。 */
    private static final long KILL_TIMEOUT_SECONDS = 10;

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 保留下来的那份完整输出放在哪儿，和编译日志同一个目录（都在 .gitignore 里）。 */
    private static final String LOG_DIR = ".specflow/logs";

    /** 没拿到退出码（脚本起不来、超时被杀、生成就没成功）时 {@link ScriptResult#exit()} 的值。 */
    public static final int NO_EXIT_CODE = -1;

    private final Path projectRoot;
    private final String entry;
    private final long timeoutSeconds;

    /**
     * @param projectRoot 项目根目录，也是执行脚本的工作目录
     * @param entry       入口脚本相对项目根的路径（形如 {@code tools/20260930-120000/run.cmd}）
     */
    public TestScriptVerifier(Path projectRoot, String entry) {
        this(projectRoot, entry, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 时限可调的那一版，包内可见。
     *
     * <p>为什么要有这个口子：超时那条路（收整棵进程树）只有真跑到时限才走得到，
     * 而默认时限是 5 分钟——测试等不起。把时限压到秒级，那条路才验得了。
     * 生产代码一律用上面那个两参数构造器。
     */
    TestScriptVerifier(Path projectRoot, String entry, long timeoutSeconds) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        // 路径按本机分隔符交给 shell：cmd 对正斜杠虽然多半吃得下，但那是「多半」
        this.entry = Path.of(entry).toString();
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 跑一次的结果：结论 + 退出码本身。 */
    public record ScriptResult(VerificationResult verification, int exit) {
    }

    /**
     * {@link Verifier} 那一条路径：只给结论，不给退出码。
     *
     * <p>留着它是为了让这个类仍然是一个「校验器」——将来编排层要把测试挂进
     * {@code DevelopmentAgent} 的校验列表时，接口不用改。
     */
    @Override
    public VerificationResult verify(VerificationContext context) {
        return run(context).verification();
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 跑一次，连退出码一起交出来。
     *
     * <p>为什么退出码要单独给：它是这次运行的<b>原始证据</b>（脚本可能用不同的非 0 值
     * 表达不同的事），而 {@link VerificationResult} 只答得出「通过 / 未通过」。
     * 留档里存的是退出码本身。
     *
     * <p>「起不来」落成 {@link VerificationResult.Kind#ENVIRONMENT}：不是改测试代码能解决的
     * （脚本压根没跑起来），上层据此立刻停下、回滚，把原始错误交给人——
     * 和编译那边判「缺依赖」是同一个道理。
     *
     * <p>「超时」落成 {@link VerificationResult.Kind#TIMEOUT}，<b>不并进环境问题</b>：
     * 两者会被人当成同一件事，但收场方式不该一样——环境弄好了超时不会消失（可能它就是慢），
     * 而磁盘上那份改动也未必有错。混在一起的结果是「一次本来就慢的测试把编译通过的改动
     * 一起回滚掉」，报告里还写着「缺命令、连不上」这种对不上的原因。
     *
     * <p>脚本自己非 0 退出时，责任方<b>在这里判不了</b>（可能是断言没过，也可能是测试代码
     * 编译不过），所以 kind 记 {@link VerificationResult.Kind#NONE}（未判）。
     * 分档的判定要读失败清单，那是 {@link TestReport} 的活。
     */
    public ScriptResult run(VerificationContext context) {
        if (!Files.isRegularFile(projectRoot.resolve(entry))) {
            // 脚本本身就不在：它跑不起来，而这不是改测试代码能解决的——按环境问题收场。
            // 注意「生成那一步有没有产出它」已经单独查过一次（见 TestAgent），
            // 能走到这里说明是别的原因（被删了、路径对不上），那种事得有人去现场看
            return failed("入口脚本不存在：" + entry + "（引擎只会执行这一个文件）",
                    NO_EXIT_CODE, VerificationResult.Kind.ENVIRONMENT);
        }
        Path output = outputFile();
        boolean createdDir = createLogDir(output);
        try {
            return execute(output);
        } finally {
            if (createdDir) {
                removeDirIfEmpty(output.getParent());
            }
        }
    }

    private ScriptResult execute(Path outputFile) {
        ProcessBuilder builder = new ProcessBuilder(shellCommand(entry));
        builder.directory(projectRoot.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(outputFile.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            // 命令不存在、没有执行权限、路径不合法都落在这里：脚本还没跑，一条断言都没执行
            return failed("入口脚本起不来：" + e.getMessage() + System.lineSeparator()
                            + "  它应该在 " + entry + "；这台机器上会执行 "
                            + String.join(" ", shellCommand(entry)),
                    NO_EXIT_CODE, VerificationResult.Kind.ENVIRONMENT);
        }

        boolean finished = await(process);
        String output;
        try {
            output = ProcessOutput.read(outputFile);
        } catch (IOException e) {
            killTree(process);
            return failed("读不出测试脚本的输出（" + e.getMessage() + "）。原始日志见："
                            + LOG_DIR + "/",
                    NO_EXIT_CODE, VerificationResult.Kind.ENVIRONMENT);
        }

        if (!finished) {
            killTree(process);
            return failed("测试脚本超过 " + timeoutSeconds + " 秒未结束，已强制终止"
                            + "（要么它真的慢，要么它卡住了）。下面是它到那一刻为止的输出："
                            + System.lineSeparator() + shorten(output) + System.lineSeparator()
                            + kept(outputFile),
                    NO_EXIT_CODE, VerificationResult.Kind.TIMEOUT);
        }

        int exit = process.exitValue();
        if (isPlainPass(output, exit)) {
            // 真通过：这份输出没有留下看的价值（编译那边也是这个规矩），
            // 也不该让一次通过的运行在项目里留下东西
            deleteFile(outputFile);
            return new ScriptResult(VerificationResult.passed(NAME, entry, shorten(output)), 0);
        }
        // 失败时把完整输出留在项目里：给人看的那份是掐过头的，而「缺什么」常常正好在被掐掉的部分
        return new ScriptResult(VerificationResult.failed(NAME, entry,
                shorten(output) + System.lineSeparator() + kept(outputFile),
                VerificationResult.Kind.NONE), exit);
    }

    /**
     * 这一次是不是「真通过」：退出码 0，而且一行 {@code FAIL} / {@code BLOCKED} 都没打。
     *
     * <p>为什么不能只看退出码：脚本打了 FAIL 行却 {@code exit /b 0} 是模型常犯的写法
     * （协议里明说了不许，但它就是会这么写）。那种运行如果算通过，日志还会被顺手删掉——
     * 而失败清单的现场就在那份日志里。退出码在这条链上只是索引，输出才是事实。
     */
    private boolean isPlainPass(String output, int exit) {
        if (exit != 0) {
            return false;
        }
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.startsWith(TestProtocol.FAIL_PREFIX)
                    || text.startsWith(TestProtocol.BLOCKED_PREFIX)) {
                return false;
            }
        }
        return true;
    }

    private ScriptResult failed(String reason, int exit, VerificationResult.Kind kind) {
        return new ScriptResult(VerificationResult.failed(NAME, entry, reason, kind), exit);
    }

    private boolean await(Process process) {
        try {
            return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new SpecflowException("等待测试脚本结束时被中断", e);
        }
    }

    /**
     * 收掉整棵进程树——只杀直接子进程是不够的。
     *
     * <p>实测过一次：脚本里 {@code start /b powershell -Command "Start-Sleep 900"} 起的孙进程，
     * 在「超时已强制终止」之后还活着。{@code destroyForcibly()} 只对直接子进程下手，
     * 而它继续占着端口和文件——下一次运行就被它绊住，报告里却写着已经终止，
     * 谁也没法从那句话里看出还留了个东西在跑。
     *
     * <p>Windows 走 {@code taskkill /T}（系统自带的树杀，一次到位）；
     * POSIX 没有等价命令，就按进程树收：<b>先抓一次子孙的句柄</b>再杀——
     * 父进程一死，子进程会被过继给 init，那时候再想找回来就没门了。
     * 两边都不成时退回 {@code destroyForcibly()}：至少别把直接子进程留在那儿。
     */
    private void killTree(Process process) {
        if (WINDOWS) {
            if (!taskKill(process.pid())) {
                log.debug("taskkill 没能收掉进程树（pid={}），退回只杀直接子进程", process.pid());
            }
        } else {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        }
        process.destroyForcibly();
    }

    /** {@code taskkill /T /F /PID}：{@code /T} 就是「连子孙一起」。 */
    private boolean taskKill(long pid) {
        try {
            Process killer = new ProcessBuilder(
                    "taskkill", "/T", "/F", "/PID", String.valueOf(pid))
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

    /**
     * 保留下来的那份完整输出在哪儿（相对项目根）。
     *
     * <p>截断会丢掉两头之一，而两头都有用（开头是「命令不存在」这类原话），
     * 所以失败时把完整的留一份，并把路径写给用户。
     */
    private String kept(Path output) {
        return "（完整输出已保留：" + LOG_DIR + "/" + output.getFileName() + "）";
    }

    private Path outputFile() {
        return projectRoot.resolve(LOG_DIR)
                .resolve("test-" + LocalDateTime.now().format(STAMP) + ".log");
    }

    /**
     * 建输出目录。
     *
     * @return 这个目录是我们新建的吗——是的话，跑完要把它收掉：
     *         一次通过的运行不该在项目里留下任何东西（编译那边也是这个规矩）
     */
    private boolean createLogDir(Path output) {
        Path dir = output.getParent();
        if (Files.isDirectory(dir)) {
            return false;
        }
        try {
            Files.createDirectories(dir);
            return true;
        } catch (IOException e) {
            throw new SpecflowException("建不了测试输出目录 " + LOG_DIR + "：" + e.getMessage(), e);
        }
    }

    private void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 删不掉不影响结论：日志目录本来就在 .gitignore 里，下次失败还会写新的
            log.debug("测试输出清理失败：{}", e.getMessage());
            file.toFile().deleteOnExit();
        }
    }

    private void removeDirIfEmpty(Path dir) {
        try (var entries = Files.list(dir)) {
            if (entries.findAny().isPresent()) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            // 收不掉就留着：一个空目录不值得把一次通过的运行变成异常
            log.debug("空目录清理失败 {}：{}", dir, e.getMessage());
        }
    }

    /**
     * 掐头去尾保中间。
     *
     * <p>和编译那边专留尾部不一样，这里两边都要：<b>开头</b>是「找不到命令 / 连不上」
     * 这类压根没跑起来的原话，<b>结尾</b>是失败清单和总结。代价是中间可能被掐掉几行
     * FAIL——完整的那份留在日志文件里，路径就写在输出末尾。
     */
    private String shorten(String output) {
        String text = output == null ? "" : output;
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        int head = MAX_OUTPUT_CHARS / 2;
        int tail = MAX_OUTPUT_CHARS - head;
        int dropped = text.length() - MAX_OUTPUT_CHARS;
        return text.substring(0, head)
                + "\n…（中间 " + dropped + " 个字符已省略，完整的在下面那个文件里）…\n"
                + text.substring(text.length() - tail);
    }

    /** Windows 走 cmd，其余走 sh——和编译校验同一条规矩。 */
    private List<String> shellCommand(String command) {
        return WINDOWS
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
    }
}
