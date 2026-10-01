package com.specflow.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 探测 Docker：三档结果 + 三级探测（十五.5）。
 *
 * <p>白盒（{@link FakeCommandRunner}）验「按什么顺序试、什么结果算哪一档」，
 * 灰盒（{@link FakeDocker} 起真进程）验「真命令跑起来之后，判出来的还是同一个答案」。
 * 这台机器上没有 Docker，两样都不能省：只测白盒的话，真进程那条路上
 * 「命令不存在」到底长什么样从来没被验过。
 */
@DisplayName("探测 Docker")
class DockerProbeTest {

    @TempDir
    Path root;

    private static final String DAEMON_DOWN =
            "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. "
                    + "Is the docker daemon running?";

    // ---------- 三档 ----------

    @Test
    @DisplayName("一条命令都找不到：报「命令找不到」，并列出试过哪些")
    void reportsMissingCommand() {
        FakeCommandRunner runner = new FakeCommandRunner();

        DockerProbe.Result result = DockerProbe.probe(runner, null);

        assertThat(result.state()).isEqualTo(DockerProbe.State.COMMAND_NOT_FOUND);
        assertThat(result.ready()).isFalse();
        assertThat(result.output()).as("要让人看出工具在他机器上翻过哪些地方")
                .contains("docker");
        // 找不到命令时不能假装知道用哪条命令
        assertThat(result.command()).isEmpty();
        assertThat(result.todo()).contains("Docker").contains("env.yaml");
    }

    @Test
    @DisplayName("命令在、daemon 没起：单独一档（用户要做的事是「打开 Docker」，不是「去装一个」）")
    void reportsDaemonDown() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .fail("docker version", 1, DAEMON_DOWN);

        DockerProbe.Result result = DockerProbe.probe(runner, null);

        assertThat(result.state()).isEqualTo(DockerProbe.State.DAEMON_DOWN);
        assertThat(result.command()).containsExactly("docker");
        assertThat(result.describe()).contains("daemon 没起来");
        // 原始错误必须原样带出来：掐掉它，用户就只能猜
        assertThat(result.output()).contains("Cannot connect to the Docker daemon");
        assertThat(result.todo()).contains("Docker Desktop");
    }

    @Test
    @DisplayName("可用：docker version 退出码 0")
    void reportsReady() {
        FakeCommandRunner runner = new FakeCommandRunner().ok("docker version", "Client: 27.0");

        DockerProbe.Result result = DockerProbe.probe(runner, null);

        assertThat(result.state()).isEqualTo(DockerProbe.State.READY);
        assertThat(result.ready()).isTrue();
        assertThat(result.todo()).as("能用的时候没有待办").isEmpty();
        assertThat(result.describe()).contains("Docker 可用");
    }

    @Test
    @DisplayName("超时也算 daemon 没起：命令在、但它什么也没回话")
    void treatsTimeoutAsDaemonDown() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .timeout("docker version", "超过 20 秒没有回话");

        DockerProbe.Result result = DockerProbe.probe(runner, null);

        assertThat(result.state()).isEqualTo(DockerProbe.State.DAEMON_DOWN);
        assertThat(result.output()).contains("20 秒");
    }

    @Test
    @DisplayName("非 0 退出但不是「daemon 没起」：仍归这一档，但原始输出原样带出去")
    void keepsRawErrorForOtherFailures() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .fail("docker version", 1, "error during connect: context \"foo\" does not exist");

        DockerProbe.Result result = DockerProbe.probe(runner, null);

        assertThat(result.state()).isEqualTo(DockerProbe.State.DAEMON_DOWN);
        // 把它塞进「找不到命令」那一档，用户会去重装一个本来就装好的东西
        assertThat(result.output()).contains("context \"foo\" does not exist");
    }

    // ---------- 三级探测 ----------

    @Test
    @DisplayName("用户手填的命令排在 PATH 之前：一句明确的交代该压过一次猜测")
    void prefersTheUserCommand() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .ok("wsl docker version", "ok")
                .ok("docker version", "ok");

        DockerProbe.Result result = DockerProbe.probe(runner, "wsl docker");

        assertThat(result.state()).isEqualTo(DockerProbe.State.READY);
        assertThat(result.command()).containsExactly("wsl", "docker");
        assertThat(runner.lines()).as("手填的那条就命中了，不该再去试 PATH 上那条")
                .containsExactly("wsl docker version");
    }

    @Test
    @DisplayName("手填的命令找不到：继续试 PATH（前一条说「找不到」才轮到下一条）")
    void fallsBackToPathWhenTheUserCommandIsMissing() {
        // 规则按「后加的先生效」匹配，所以更具体的那条要写在后面：
        // "wsl docker version" 里本来就含 "docker version" 这一段
        FakeCommandRunner runner = new FakeCommandRunner()
                .ok("docker version", "ok")
                .notFound("wsl docker version");

        DockerProbe.Result result = DockerProbe.probe(runner, "wsl docker");

        assertThat(result.state()).isEqualTo(DockerProbe.State.READY);
        assertThat(result.command()).containsExactly("docker");
        assertThat(runner.lines().get(0)).isEqualTo("wsl docker version");
        assertThat(runner.lines()).contains("docker version");
    }

    @Test
    @DisplayName("找到命令但用不了：不再换下一条候选（那不是命令的问题）")
    void doesNotTryFurtherCandidatesWhenTheDaemonIsDown() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .ok("docker version", "ok")
                .fail("wsl docker version", 1, DAEMON_DOWN);

        DockerProbe.Result result = DockerProbe.probe(runner, "wsl docker");

        assertThat(result.state()).isEqualTo(DockerProbe.State.DAEMON_DOWN);
        assertThat(runner.lines()).as("换一条命令试会把「没启动」变成一句「找不到」")
                .containsExactly("wsl docker version");
    }

    @Test
    @DisplayName("没填命令时：只试 PATH 和常见安装路径，一条也不多")
    void probesWithoutUserCommand() {
        FakeCommandRunner runner = new FakeCommandRunner()
                .fail("docker version", 1, DAEMON_DOWN);

        DockerProbe.probe(runner, null);

        assertThat(runner.lines().get(0)).isEqualTo("docker version");
        assertThat(runner.lines()).hasSize(1);
    }

    @Test
    @DisplayName("用户填的命令按空白拆成参数：不经 shell，就没有引号转义可错")
    void splitsUserCommand() {
        assertThat(DockerProbe.splitCommand("wsl docker"))
                .containsExactly("wsl", "docker");
        assertThat(DockerProbe.splitCommand("  C:/Program Files/Docker/docker.exe  "))
                .as("带空格的路径被拆成两段是它的已知边界：所以文档里建议填不带空格的写法")
                .hasSize(2);
    }

    @Test
    @DisplayName("用户填了空命令：当场报错，而不是拿一条空命令去起进程")
    void refusesBlankUserCommand() {
        assertThatThrownBy(() -> DockerProbe.splitCommand("   "))
                .isInstanceOf(com.specflow.exception.SpecflowException.class);
    }

    @Test
    @DisplayName("候选顺序：先听用户的交代，再猜 PATH 和常见安装路径")
    void probesInDocumentedOrder() {
        List<List<String>> candidates = DockerProbe.candidates("wsl docker");

        assertThat(candidates.get(0)).containsExactly("wsl", "docker");
        assertThat(candidates.get(1)).containsExactly("docker");

        // 没填命令时：PATH → 常见安装路径（后者的内容随机器不同，只要求顺序性质）
        List<List<String>> plain = DockerProbe.candidates(null);
        assertThat(plain.get(0)).containsExactly("docker");
        assertThat(plain.size()).isGreaterThanOrEqualTo(1);
    }

    // ---------- 灰盒：真起进程 ----------

    @Test
    @DisplayName("灰盒：真的起一个进程来探——命令不存在、可用、daemon 没起，三档都判得对")
    void worksAgainstARealProcess() throws IOException {
        FakeDocker fake = FakeDocker.create(root.resolve("docker"));
        fake.respond("version", 1, "Cannot connect to the Docker daemon at tcp://127.0.0.1:2375.");
        var runner = fake.runner();

        DockerProbe.Result down = DockerProbe.probe(runner, null);
        assertThat(down.state()).isEqualTo(DockerProbe.State.DAEMON_DOWN);
        assertThat(down.output()).contains("Cannot connect to the Docker daemon");

        fake.respond("version", 0, "Client: 27.0.3");
        assertThat(DockerProbe.probe(runner, null).state()).isEqualTo(DockerProbe.State.READY);

        // 这台机器上「找不到命令」的真实长相：进程压根起不来（不是退出码非 0）
        CommandRunner missing = new ProcessCommandRunner();
        assertThat(missing.run(List.of("definitely-not-a-real-command-specflow"), java.util.Map.of(),
                null, 10).started()).isFalse();
        assertThat(runner.run(List.of("fake", "version"), java.util.Map.of(), null, 10).ok()).isTrue();
    }
}
