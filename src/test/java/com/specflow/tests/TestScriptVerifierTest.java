package com.specflow.tests;

import com.specflow.TestSpecs;
import com.specflow.project.ProjectConfig;
import com.specflow.spec.Spec;
import com.specflow.verify.VerificationContext;
import com.specflow.verify.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行器的测试。
 *
 * <p>和编译校验那边同一个做法：用 {@code exit 0} / {@code exit 1} 这类脚本模拟结果，
 * 而不是真去编译一个项目——这里要验的是「退出码怎么解释、工作目录在哪、日志留不留」。
 * 唯一一条真等时钟的是超时那一条（时限压到 10 秒）。
 */
@DisplayName("测试脚本执行器")
class TestScriptVerifierTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("退出码 0 就是通过，命令那一栏是本机分隔符的那条路径")
    void passesOnZeroExitCode() {
        String entry = script(0, "all-passed");

        VerificationResult result = verifier(entry).verify(context());

        assertThat(result.passed()).isTrue();
        assertThat(result.command()).isEqualTo(Path.of(entry).toString());
        assertThat(result.output()).contains("all-passed");
    }

    /**
     * 输出里的中文在这条测试里刻意换成 ASCII：{@code .cmd} 是按本机代码页读的，
     * 正文里的中文有可能把 {@code ^|} 的转义吃掉——那样测的就不是引擎，
     * 而是控制台的代码页（时灵时不灵，最坏的一种测试）。
     * 引擎读中文输出这一件事由 {@code ProcessOutputTest} 与编译校验那边钉着。
     */
    @Test
    @DisplayName("非 0 退出就是失败，输出原样带出来（失败清单在它里面）")
    void failsOnNonZeroExitCode() {
        String entry = script(1, "FAIL | 1 | expected-value | actual-value | code-is-wrong");

        TestScriptVerifier.ScriptResult run = verifier(entry).run(context());

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.verification().failed()).isTrue();
        assertThat(run.verification().output())
                .contains("FAIL | 1 | expected-value | actual-value | code-is-wrong");
        assertThat(run.verification().kind())
                .as("责任方在解析失败清单之前判不了，这里不许替它下结论")
                .isEqualTo(VerificationResult.Kind.NONE);
    }

    @Test
    @DisplayName("工作目录是项目根：脚本里写相对路径落在项目根上")
    void runsInProjectRoot() {
        String entry = script(0, "echo hi > specflow-cwd-check.txt");

        verifier(entry).verify(context());

        assertThat(root.resolve("specflow-cwd-check.txt")).exists();
    }

    @Test
    @DisplayName("入口脚本不存在时是环境问题，不是「测试没过」")
    void missingScriptIsEnvironment() {
        TestScriptVerifier.ScriptResult run =
                verifier("tools/20260930-120000/" + EntryScripts.name()).run(context());

        assertThat(run.verification().failed()).isTrue();
        assertThat(run.verification().kind()).isEqualTo(VerificationResult.Kind.ENVIRONMENT);
        assertThat(run.exit()).as("没拿到退出码").isEqualTo(TestScriptVerifier.NO_EXIT_CODE);
        assertThat(run.verification().output()).contains("不存在");
    }

    @Test
    @DisplayName("失败时把完整输出留在项目里，并写明它在哪")
    void keepsOutputOnFailure() throws IOException {
        String entry = script(1, "specflow-kept-marker");
        VerificationResult result = verifier(entry).verify(context());

        assertThat(result.failed()).isTrue();
        assertThat(result.output()).contains("完整输出已保留").contains(".specflow/logs/test-");
        try (var files = Files.list(root.resolve(".specflow/logs"))) {
            List<Path> kept = files.toList();
            assertThat(kept).hasSize(1);
            assertThat(Files.readString(kept.get(0))).contains("specflow-kept-marker");
        }
    }

    @Test
    @DisplayName("通过时不在项目里留任何东西（日志目录也不留）")
    void leavesNothingBehindOnSuccess() {
        verifier(script(0, "all-passed")).verify(context());

        assertThat(root.resolve(".specflow/logs")).doesNotExist();
        assertThat(root.resolve("tools")).isDirectory();
    }

    /**
     * 退出码 0 掩盖不了它自己打的 FAIL 行：模型经常写一堆 FAIL 行、最后 {@code exit /b 0}。
     * 旧代码见退出码 0 就返回「通过」并把日志删掉——失败清单的现场就没了。
     */
    @Test
    @DisplayName("退出码 0 但打了 FAIL 行：算未通过，而且日志要留下")
    void doesNotPassOnZeroExitWithFailLines() throws IOException {
        String entry = script(0, "FAIL | 1 | expected-value | actual-value | code-is-wrong");

        TestScriptVerifier.ScriptResult run = verifier(entry).run(context());

        assertThat(run.exit()).isZero();
        assertThat(run.verification().failed()).isTrue();
        assertThat(run.verification().output()).contains("expected-value");
        try (var files = Files.list(root.resolve(".specflow/logs"))) {
            assertThat(files).as("失败清单的现场就在那份日志里").isNotEmpty();
        }
    }

    /**
     * 超时那条路只有真跑到时限才走得到，所以这里把时限压到秒级。
     *
     * <p>这条断言抓的是实测出来的那个洞：{@code destroyForcibly()} 只杀<b>直接子进程</b>，
     * 脚本里 {@code start /b} 起的孙进程会活下来继续占着端口和文件，
     * 而报告里写的是「已强制终止」——谁也没法从那句话里看出还留了个东西在跑。
     */
    @Test
    @DisplayName("超时：单独一档，并且整棵进程树都被收掉（孙进程不许活下来）")
    void killsTheWholeProcessTreeOnTimeout() throws IOException {
        Path directory = root.resolve("tools/20260930-120000");
        Files.createDirectories(directory);
        Path grandPid = directory.resolve("grand.pid");
        Path entry = directory.resolve(EntryScripts.name());
        Files.writeString(entry, hangingScript());

        TestScriptVerifier.ScriptResult run =
                new TestScriptVerifier(root, "tools/20260930-120000/" + EntryScripts.name(), 10)
                        .run(context());

        assertThat(run.verification().kind()).as("超时是单独一档，不该并进环境问题")
                .isEqualTo(VerificationResult.Kind.TIMEOUT);
        assertThat(run.exit()).isEqualTo(TestScriptVerifier.NO_EXIT_CODE);
        assertThat(run.verification().output()).contains("未结束");
        assertThat(grandPid).as("孙进程先起了身，这条测试才不是空过").exists();
        long pid = Long.parseLong(Files.readString(grandPid).strip());
        assertThat(waitedGone(pid)).as("孙进程必须跟着一起死掉（pid=%d）", pid).isTrue();
    }

    /**
     * 入口脚本：先起一个**孙进程**（它把 pid 写进 grand.pid，好让我们事后找得到它），
     * 等到孙进程真的起来了才开始挂着不退出。为什么要等：不等的话有可能时限先到、
     * 孙进程还没起身，那条断言就成了空过（看着绿，其实什么都没验）。
     */
    private static String hangingScript() {
        if (EntryScripts.WINDOWS) {
            return String.join("\r\n",
                    "@echo off",
                    "start \"specflow-tree\" /b powershell -NoProfile -Command "
                            + "\"$PID | Out-File -Encoding ascii -FilePath '%~dp0grand.pid';"
                            + " Start-Sleep -Seconds 300\"",
                    ":wait",
                    "if exist \"%~dp0grand.pid\" goto hang",
                    "ping -n 2 127.0.0.1 >nul",
                    "goto wait",
                    ":hang",
                    "ping -n 400 127.0.0.1 >nul",
                    "exit /b 0",
                    "");
        }
        return String.join("\n",
                "#!/bin/sh",
                "sleep 300 &",
                "echo $! > \"$(dirname \"$0\")/grand.pid\"",
                "wait",
                "");
    }

    /** 等那个进程消失（最多几秒）：杀进程是异步的，立刻断言会假红。 */
    private static boolean waitedGone(long pid) {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (ProcessHandle.of(pid).isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ProcessHandle.of(pid).isEmpty();
    }

    @Test
    @DisplayName("同一个执行器跑两次结论一样（校验器约定：可以重复调用）")
    void isRepeatable() {
        String entry = script(1, "FAIL | 1 | expected-value | actual-value | code-is-wrong");
        TestScriptVerifier verifier = verifier(entry);

        TestScriptVerifier.ScriptResult first = verifier.run(context());
        TestScriptVerifier.ScriptResult second = verifier.run(context());

        assertThat(second.exit()).isEqualTo(first.exit());
        assertThat(second.verification().status()).isEqualTo(first.verification().status());
    }

    // ---------- 辅助 ----------

    /** 在 {@code tools/<时间戳>/} 下写一个入口脚本，返回它相对项目根的路径。 */
    private String script(int exit, String... lines) {
        TestArtifacts artifacts = TestArtifacts.create(root);
        // 直接落盘（不走白名单那一条）：这里测的是执行器，产物由用例自己安排
        Path file = root.resolve(artifacts.entry());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, EntryScripts.body(exit, lines));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return artifacts.entry();
    }

    private TestScriptVerifier verifier(String entry) {
        return new TestScriptVerifier(root, entry);
    }

    private VerificationContext context() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        return new VerificationContext(root, spec, ProjectConfig.DEFAULT);
    }
}
