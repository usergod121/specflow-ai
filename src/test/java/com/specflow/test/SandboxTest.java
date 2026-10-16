package com.specflow.test;

import com.specflow.agent.Shell;
import com.specflow.core.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 容器沙箱的 <b>fail closed</b>：容器不在时，一条命令都不许落到宿主上。
 *
 * <p>这里<b>不引 docker</b>：用的是「一份假的 compose + 一个不在的容器」。
 * 真容器那条路由端到端脚本（T8）去验——单测里起容器，意味着这台机器上没装 docker 就跑不了测试。
 */
class SandboxTest {

    @TempDir
    Path tmp;

    /** ① 渲染出来的那种 compose（顶上带 name:，所以项目名认得出来）。 */
    private Path compose(String name) throws IOException {
        Path file = tmp.resolve("env-" + name).resolve("compose.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                name: "%s"
                services:
                  app:
                    image: "busybox"
                    command: ["sleep", "infinity"]
                """.formatted(name), StandardCharsets.UTF_8);
        return file;
    }

    private static List<String> filesUnder(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            List<String> out = new ArrayList<>();
            stream.filter(Files::isRegularFile).forEach(one -> out.add(root.relativize(one).toString()));
            return out;
        }
    }

    @Test
    void noContainerMeansNoCommandOnTheHost() throws IOException {
        Sandbox sandbox = new Sandbox(Project.open(tmp), compose("sf-test-000000"), "app");

        assertThat(sandbox.available()).isFalse();

        List<String> before = filesUnder(tmp);
        Shell.Result result = sandbox.run(
                "echo done > 宿主上的证明.txt && touch /work/容器里的证明", "/work", 5, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.exit()).isEqualTo(-1);
        assertThat(result.output()).contains("容器没起来");
        assertThat(result.output()).contains("这条命令没有执行，也没有落到宿主上");
        // 最要紧的一条：什么都没落到宿主上
        assertThat(filesUnder(tmp)).isEqualTo(before);
        assertThat(Files.exists(tmp.resolve("宿主上的证明.txt"))).isFalse();
    }

    @Test
    void missingComposeFileIsAlsoFailClosed() {
        Sandbox sandbox = new Sandbox(Project.open(tmp), tmp.resolve("根本没有这份.yaml"), "app");

        assertThat(sandbox.available()).isFalse();

        Shell.Result result = sandbox.run("ls -la", null, 5, null);

        assertThat(result.exit()).isEqualTo(-1);
        assertThat(result.output()).contains("这条命令没有执行，也没有落到宿主上");
    }

    @Test
    void blankCommandIsRefusedBeforeAnythingIsSent() {
        Sandbox sandbox = new Sandbox(Project.open(tmp), tmp.resolve("x.yaml"), "app");

        Shell.Result result = sandbox.run("   ", "/work", 5, null);

        assertThat(result.exit()).isEqualTo(-1);
        assertThat(result.output()).contains("命令是空的");
    }

    @Test
    void projectNameComesFromTheRenderedCompose() throws IOException {
        Sandbox sandbox = new Sandbox(Project.open(tmp), compose("sf-demo-9f8e7d"), "app");

        // 用起容器时那一个项目名，才命得中那个容器
        assertThat(sandbox.composeName()).isEqualTo("sf-demo-9f8e7d");
    }

    @Test
    void projectNameCanBeGivenByTheAssembler() throws IOException {
        Sandbox sandbox = new Sandbox(Project.open(tmp), compose("ignored"), "app", "sf-from-env");

        assertThat(sandbox.composeName()).isEqualTo("sf-from-env");
    }

    @Test
    void projectNameFallsBackToTheFolderName() throws IOException {
        Path file = tmp.resolve("hand-made.yaml");
        Files.writeString(file, "services:\n  app:\n    image: \"busybox\"\n", StandardCharsets.UTF_8);

        Sandbox sandbox = new Sandbox(Project.open(tmp), file, "app");

        // 兜底：没有 name: 就按目录名归一化（这个临时目录名一定是 [a-z0-9-] 里的字符）
        assertThat(sandbox.composeName()).startsWith("sf-").doesNotContain("_").doesNotContain(" ");
    }

    @Test
    void constructorRefusesNonsense() throws IOException {
        Path file = compose("sf-x-000000");

        assertThatThrownBy(() -> new Sandbox(null, file, "app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project.open");
        assertThatThrownBy(() -> new Sandbox(Project.open(tmp), null, "app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compose");
        assertThatThrownBy(() -> new Sandbox(Project.open(tmp), file, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("服务名是空的");
    }
}
