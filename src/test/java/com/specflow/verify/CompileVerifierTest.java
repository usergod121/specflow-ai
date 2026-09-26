package com.specflow.verify;

import com.specflow.TestSpecs;
import com.specflow.project.BuildConfig;
import com.specflow.project.LlmConfig;
import com.specflow.project.ProjectConfig;
import com.specflow.project.SnapshotConfig;
import com.specflow.spec.Spec;
import com.specflow.spec.VerifySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编译校验器的测试。
 *
 * <p>用 {@code exit 0} / {@code exit 1} 这类命令来模拟构建结果，
 * 而不是真的去跑一次 Maven：这条测试要验证的是「退出码怎么解释、命令从哪来」，
 * 不是 Maven 能不能编译。
 */
@DisplayName("编译校验")
class CompileVerifierTest {

    @TempDir
    Path root;

    private final CompileVerifier verifier = new CompileVerifier();

    @Test
    @DisplayName("spec 关闭 compile 时跳过，而不是当成通过")
    void skipsWhenDisabledInSpec() {
        VerificationResult result = verifier.verify(context(
                TestSpecs.spec(List.of("Foo.java"),
                        new VerifySpec(false, "exit 0", 0, VerifySpec.AUTO_ROUNDS)),
                ProjectConfig.DEFAULT));

        assertThat(result.skipped()).isTrue();
        assertThat(result.output()).contains("spec.verify.compile = false");
    }

    @Test
    @DisplayName("未配置编译命令时跳过，并告诉用户该配在哪")
    void skipsWhenNoCommandConfigured() {
        VerificationResult result = verifier.verify(context(
                TestSpecs.spec(List.of("Foo.java")), ProjectConfig.DEFAULT));

        assertThat(result.skipped()).isTrue();
        assertThat(result.output()).contains("build.compile");
    }

    @Test
    @DisplayName("命令返回 0 视为通过")
    void passesOnZeroExitCode() {
        VerificationResult result = verifier.verify(context(specWithCommand("exit 0"), ProjectConfig.DEFAULT));

        assertThat(result.passed()).isTrue();
        assertThat(result.command()).isEqualTo("exit 0");
    }

    @Test
    @DisplayName("命令返回非 0 视为失败，输出被保留下来喂回模型")
    void failsOnNonZeroExitCode() {
        VerificationResult result = verifier.verify(context(specWithCommand("exit 1"), ProjectConfig.DEFAULT));

        assertThat(result.failed()).isTrue();
    }

    @Test
    @DisplayName("命令输出会被捕获，供回喂与排查")
    void capturesCommandOutput() {
        VerificationResult result = verifier.verify(context(specWithCommand("echo specflow-marker"),
                ProjectConfig.DEFAULT));

        assertThat(result.output()).contains("specflow-marker");
    }

    @Test
    @DisplayName("命令的工作目录是项目根目录")
    void runsInProjectRoot() {
        verifier.verify(context(specWithCommand("echo hi > specflow-cwd-check.txt"), ProjectConfig.DEFAULT));

        assertThat(root.resolve("specflow-cwd-check.txt")).exists();
    }

    @Test
    @DisplayName("spec 里的 compile-command 优先于项目配置")
    void specCommandOverridesProjectConfig() {
        ProjectConfig project = projectWithCompileCommand("exit 1");

        VerificationResult result = verifier.verify(context(specWithCommand("exit 0"), project));

        assertThat(result.command()).isEqualTo("exit 0");
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("spec 未指定命令时回退到项目配置")
    void fallsBackToProjectConfig() {
        ProjectConfig project = projectWithCompileCommand("exit 0");

        VerificationResult result = verifier.verify(context(
                TestSpecs.spec(List.of("Foo.java")), project));

        assertThat(result.command()).isEqualTo("exit 0");
        assertThat(result.passed()).isTrue();
    }

    // ---------- 辅助 ----------

    private Spec specWithCommand(String command) {
        return TestSpecs.spec(List.of("Foo.java"),
                new VerifySpec(true, command, 0, VerifySpec.AUTO_ROUNDS));
    }

    private ProjectConfig projectWithCompileCommand(String command) {
        return new ProjectConfig(new BuildConfig(command, null, null),
                LlmConfig.DEFAULT, SnapshotConfig.DEFAULT);
    }

    private VerificationContext context(Spec spec, ProjectConfig project) {
        return new VerificationContext(root, spec, project);
    }

    @Test
    @DisplayName("临时日志文件不会残留在项目里")
    void leavesNoTemporaryFilesBehind() throws IOException {
        verifier.verify(context(specWithCommand("exit 0"), ProjectConfig.DEFAULT));

        try (var files = Files.list(root)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith("specflow-compile-"));
        }
    }

    @Test
    @DisplayName("编译失败时把日志留在项目里，并在输出里给出它的路径")
    void keepsLogOnFailure() throws IOException {
        VerificationResult result = verifier.verify(context(
                specWithCommand("echo specflow-kept-marker & exit 1"), ProjectConfig.DEFAULT));

        assertThat(result.failed()).isTrue();
        assertThat(result.output()).contains("完整日志已保留").contains(".specflow/logs/compile-");

        Path logs = root.resolve(".specflow/logs");
        try (var files = Files.list(logs)) {
            List<Path> kept = files.toList();
            assertThat(kept).hasSize(1);
            assertThat(Files.readString(kept.get(0))).contains("specflow-kept-marker");
        }
    }

    @Test
    @DisplayName("编译通过时不往项目里丢日志")
    void keepsNoLogOnSuccess() {
        verifier.verify(context(specWithCommand("exit 0"), ProjectConfig.DEFAULT));

        assertThat(root.resolve(".specflow/logs")).doesNotExist();
    }

    @Test
    @DisplayName("缺依赖的失败被标成环境问题，上层据此停下而不是继续烧轮次")
    void marksMissingDependencyAsEnvironment() {
        VerificationResult result = verifier.verify(context(
                specWithCommand("echo Could not resolve dependencies for project demo & exit 1"),
                ProjectConfig.DEFAULT));

        assertThat(result.kind()).isEqualTo(VerificationResult.Kind.ENVIRONMENT);
    }

    @Test
    @DisplayName("普通编译错误仍是代码问题，喂回去让模型自己修")
    void marksOrdinaryCompileErrorAsCode() {
        VerificationResult result = verifier.verify(context(
                specWithCommand("echo error: cannot find symbol & exit 1"), ProjectConfig.DEFAULT));

        assertThat(result.kind()).isEqualTo(VerificationResult.Kind.CODE);
    }

    /**
     * 真实事故的回归：中文 Windows 上 javac 的报错是 GBK 字节，
     * 按 UTF-8 硬读会抛 {@code MalformedInputException: Input length = 1}，
     * 用户看到「运行中断：无法读取编译日志」，而真正该看到的错在哪一行没了。
     */
    @Test
    @DisplayName("中文报错（GBK 字节）能读出来，不会再炸成编码异常")
    void readsLocalizedFailureOutput() {
        VerificationResult result = verifier.verify(context(
                specWithCommand("echo [ERROR] 程序包 com.demo 不存在 & exit 1"), ProjectConfig.DEFAULT));

        assertThat(result.failed()).isTrue();
        // 认得出「程序包不存在」，说明 GBK 那段真的被读成了中文，而不是替换字符
        assertThat(result.kind()).isEqualTo(VerificationResult.Kind.ENVIRONMENT);
    }
}
