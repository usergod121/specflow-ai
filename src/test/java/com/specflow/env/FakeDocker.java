package com.specflow.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一个<b>真的会被执行</b>的假 docker：脚本把每次调用的参数记进文件，按规则回话。
 *
 * <p>为什么光有 {@link FakeCommandRunner} 不够：它证明了「命令拼对了」，
 * 但那句话是<b>在 Java 里</b>拼的——参数里带空格、带引号、带路径分隔符时，
 * 真正传下去的东西可能和它以为的不一样。所以这一层把「docker」换成一个真脚本，
 * 让 {@link ProcessCommandRunner} 走完整条路（起进程、传参、读输出、拿退出码），
 * 再回头核对脚本收到的参数。<b>灰盒就是这一层</b>：白盒证明意图，灰盒证明事实。
 *
 * <p>脚本按平台生成两份实现（cmd / sh），断言的判据是同一份「参数记录文件」——
 * 两种实现写出来的东西一样，所以测试不用分平台写两套断言。
 */
public final class FakeDocker {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    /** 一条规则：参数里出现这段字面量时回什么。 */
    private record Rule(String fragment, int exit, List<String> output) {
    }

    private final Path directory;
    private final Path script;
    private final Path log;
    private final List<Rule> rules = new ArrayList<>();
    private final ProcessCommandRunner delegate = new ProcessCommandRunner();

    private FakeDocker(Path directory) {
        this.directory = directory;
        this.script = directory.resolve(WINDOWS ? "fake-docker.cmd" : "fake-docker.sh");
        this.log = directory.resolve("calls.log");
    }

    public static FakeDocker create(Path root) throws IOException {
        Files.createDirectories(root);
        FakeDocker fake = new FakeDocker(root);
        Files.writeString(fake.log, "", StandardCharsets.UTF_8);
        return fake;
    }

    /** 参数里含 {@code fragment} 时回这个结果。后加的规则先匹配。 */
    public FakeDocker respond(String fragment, int exit, String... output) throws IOException {
        rules.add(new Rule(fragment, exit, List.of(output)));
        write();
        return this;
    }

    /** 路径：把它填进 {@code env.yaml} 的 {@code docker: command:} 那一行。 */
    public Path script() {
        return script;
    }

    /** 每次调用记下来的参数，按发生顺序，一行一条。 */
    public List<String> calls() throws IOException {
        return Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /**
     * 一个把 {@code docker} 换成这个脚本的执行器——<b>其他一个字都不改</b>。
     *
     * <p>只换 argv[0] 而不换整个执行链，是为了让测试走的仍然是生产的
     * {@link ProcessCommandRunner}：超时、进程树、输出解码这些「真出过事的地方」
     * 在测试里也照样被走到。
     */
    public CommandRunner runner() {
        return (command, environment, workdir, timeoutSeconds) -> {
            List<String> rewritten = new ArrayList<>(command);
            if (!rewritten.isEmpty()) {
                rewritten.set(0, script.toString());
            }
            return delegate.run(rewritten, environment, workdir, timeoutSeconds);
        };
    }

    /**
     * 把脚本写出来。
     *
     * <p>规则用「参数里含某段字面量」来判，而不是全等：命令里带着项目路径和带时间戳的
     * compose 文件（测试写不出全等的常量）。判据够用——每条规则匹配的那一段都是命令里
     * 最有辨识度的那几个词（{@code up -d --wait}、{@code exec -T}）。
     */
    private void write() throws IOException {
        // 后加的规则先匹配（和 FakeCommandRunner 一个口径）：测试里「特例」总是写在
        // 「通例」之后，而两边顺序不一致的话，同一条断言在白盒和灰盒下会得到两个答案
        List<Rule> ordered = new ArrayList<>(rules);
        java.util.Collections.reverse(ordered);
        StringBuilder out = new StringBuilder();
        if (WINDOWS) {
            out.append("@echo off\r\n");
            out.append(">>\"").append(log).append("\" echo %*\r\n");
            for (Rule rule : ordered) {
                out.append("echo %* | findstr /C:\"").append(rule.fragment()).append("\" >nul && (\r\n");
                for (String line : rule.output()) {
                    out.append("  echo ").append(escapeCmd(line)).append("\r\n");
                }
                out.append("  exit /b ").append(rule.exit()).append("\r\n)\r\n");
            }
            // 没有规则命中：像一条「命令在、但做不了这件事」的 docker——退出码非 0、有输出
            out.append("echo fake docker: no rule matched %*\r\nexit /b 1\r\n");
        } else {
            out.append("#!/bin/sh\n");
            out.append("echo \"$@\" >> \"").append(log).append("\"\n");
            for (Rule rule : ordered) {
                out.append("case \"$*\" in\n  *\"").append(rule.fragment()).append("\"*)\n");
                for (String line : rule.output()) {
                    out.append("    echo '").append(line.replace("'", "'\\''")).append("'\n");
                }
                out.append("    exit ").append(rule.exit()).append("\n    ;;\nesac\n");
            }
            out.append("echo \"fake docker: no rule matched $*\"\nexit 1\n");
        }
        Files.writeString(script, out.toString(), StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
    }

    /**
     * cmd 的 {@code echo} 里要转义的字符。
     *
     * <p>{@code | & < > ^ % ( )} 在 cmd 里都有语法含义：不转义的话，我们自己的输出
     * 会把那条规则本身拆坏——而失败会表现成「规则没命中」，看着像被测代码的问题。
     */
    private static String escapeCmd(String text) {
        return text.replace("^", "^^").replace("|", "^|").replace("&", "^&")
                .replace("<", "^<").replace(">", "^>").replace("%", "%%");
    }
}
