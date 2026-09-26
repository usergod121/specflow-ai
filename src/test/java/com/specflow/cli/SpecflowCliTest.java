package com.specflow.cli;

import com.specflow.SpecflowCli;
import com.specflow.project.SnapshotConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令行端到端测试。
 *
 * <p>覆盖到 {@code validate} / {@code templates} / {@code init} 三条不联网的命令。
 * {@code run} 需要真实模型，不在这里测——它的编排逻辑由
 * {@code DevelopmentAgentTest} 用假模型覆盖。
 */
@DisplayName("命令行")
class SpecflowCliTest {

    @TempDir
    Path root;

    private String project;

    @BeforeEach
    void setUp() {
        project = root.toString();
    }

    @Test
    @DisplayName("validate 通过时返回 0")
    void validateSucceedsOnGoodSpec() throws Exception {
        writeSpec("""
                prompt: 给 Foo 加一行日志
                targets: [Foo.java]
                """);

        assertThat(SpecflowCli.execute("validate", "-p", project)).isZero();
    }

    @Test
    @DisplayName("validate 失败时返回 1，且不改动任何文件")
    void validateFailsOnBadSpec() throws Exception {
        writeSpec("""
                prompt: x
                targets: [../escape.txt]
                """);

        assertThat(SpecflowCli.execute("validate", "-p", project)).isEqualTo(1);
    }

    @Test
    @DisplayName("spec 文件不存在时返回 1 而不是崩溃")
    void validateFailsWhenSpecMissing() {
        assertThat(SpecflowCli.execute("validate", "-p", project, "-s", "nope.yaml")).isEqualTo(1);
    }

    @Test
    @DisplayName("validate 会加载模板并报出它的标签")
    void validateLoadsTemplate() throws Exception {
        Path templates = root.resolve(".specflow/templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("t.yaml"), """
                name: t
                tags: [java, mybatis]
                system: 你是后端工程师
                """);

        writeSpec("""
                template: t
                prompt: 做点事
                targets: [Foo.java]
                """);
        assertThat(SpecflowCli.execute("validate", "-p", project)).isZero();
    }

    @Test
    @DisplayName("引用了不存在的模板时 validate 返回 1")
    void validateFailsOnUnknownTemplate() throws Exception {
        writeSpec("""
                template: 不存在的模板
                prompt: 做点事
                targets: [Foo.java]
                """);

        assertThat(SpecflowCli.execute("validate", "-p", project)).isEqualTo(1);
    }

    @Test
    @DisplayName("templates 在没有任何模板时也返回 0")
    void templatesSucceedsWhenEmpty() {
        assertThat(SpecflowCli.execute("templates", "-p", project)).isZero();
    }

    @Test
    @DisplayName("init 生成配置骨架，且不覆盖已有文件")
    void initScaffoldsProject() throws Exception {
        assertThat(SpecflowCli.execute("init", "-p", project)).isZero();

        assertThat(root.resolve(".specflow/project.yaml")).exists();
        assertThat(root.resolve(".specflow/templates/spring-backend.yaml")).exists();
        assertThat(root.resolve(".specflow/templates/fix-bug.yaml")).exists();
        assertThat(root.resolve("spec.yaml")).exists();

        Path impl = root.resolve(".specflow/templates/spring-backend.yaml");
        String before = Files.readString(impl);
        Files.writeString(impl, before + "\n# 用户自己的改动\n");

        assertThat(SpecflowCli.execute("init", "-p", project)).isZero();
        assertThat(Files.readString(impl)).endsWith("# 用户自己的改动\n");
    }

    @Test
    @DisplayName("init 生成的项目可以直接通过 validate")
    void initOutputIsValid() {
        assertThat(SpecflowCli.execute("init", "-p", project)).isZero();

        assertThat(SpecflowCli.execute("validate", "-p", project)).isZero();
    }

    @Test
    @DisplayName("没有子命令时打印帮助并返回 0")
    void printsUsageWithoutSubcommand() {
        assertThat(SpecflowCli.execute()).isZero();
    }

    @Test
    @DisplayName("没有待处置的改动时 accept 与 rollback 都是 0，不做任何事")
    void decidingWithNothingPendingSucceeds() {
        assertThat(SpecflowCli.execute("accept", "-p", project)).isZero();
        assertThat(SpecflowCli.execute("rollback", "-p", project)).isZero();
    }

    @Test
    @DisplayName("rollback 按快照把文件恢复原样，并把快照清掉")
    void rollbackRestoresFilesFromSnapshot() throws Exception {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "old");
        markPendingSnapshot(file);
        Files.writeString(file, "new");

        assertThat(SpecflowCli.execute("rollback", "-p", project)).isZero();

        assertThat(Files.readString(file)).isEqualTo("old");
        assertThat(undisposedSnapshots()).isEmpty();
    }

    @Test
    @DisplayName("accept 保留磁盘上的改动，只把快照清掉")
    void acceptKeepsFilesAndClearsSnapshot() throws Exception {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "old");
        markPendingSnapshot(file);
        Files.writeString(file, "new");

        assertThat(SpecflowCli.execute("accept", "-p", project)).isZero();

        assertThat(Files.readString(file)).isEqualTo("new");
        assertThat(undisposedSnapshots()).isEmpty();
    }

    @Test
    @DisplayName("没有挂起的运行时 continue 返回 0，并说清没有东西可接着跑")
    void continueWithoutSuspendedRunIsFine() throws Exception {
        writeSpec("""
                prompt: 给 Foo 加一行日志
                targets: [Foo.java]
                """);

        assertThat(SpecflowCli.execute("continue", "-p", project)).isZero();
    }

    /** 造一份「校验通过、等人处置」的快照，模拟上一次运行留下的东西。 */
    private void markPendingSnapshot(Path file) {
        WorkspaceSnapshot.capture(new SafePathResolver(root),
                        root.resolve(SnapshotConfig.DEFAULT_DIR), List.of(file))
                .markPending();
    }

    private List<WorkspaceSnapshot> undisposedSnapshots() {
        return WorkspaceSnapshot.undisposed(new SafePathResolver(root),
                root.resolve(SnapshotConfig.DEFAULT_DIR));
    }

    private void writeSpec(String yaml) throws Exception {
        Files.writeString(root.resolve("spec.yaml"), yaml);
    }
}
