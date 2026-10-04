package com.specflow.tests;

import com.specflow.TestSpecs;
import com.specflow.env.ComposeFile;
import com.specflow.env.EnvConfigLoader;
import com.specflow.env.FakeCommandRunner;
import com.specflow.env.TestEnvironment;
import com.specflow.project.ProjectConfig;
import com.specflow.spec.Spec;
import com.specflow.verify.VerificationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这次测试脚本在哪儿跑」这一处判断的测试。
 *
 * <p>三条路径都要走一遍，因为它们的后果完全不一样：
 * <ul>
 *   <li><b>Docker 可用 + 环境活着</b> → 单元与集成都进容器（十五.5 的默认形态）：</li>
 *   <li><b>Docker 用不了</b>（没装 / daemon 没起）→ 单元回退宿主，而且<b>结果与留档里要写明</b>
 *       「在宿主执行（未用容器）」——宿主上只剩高危字符串那一道闸，用户有权知道；</li>
 *   <li><b>环境还没初始化</b>（Docker 好着呢，但 compose 文件不在）→ 同样回退宿主。
 *       这一条最容易被写成「有 docker 就进容器」，而那样做的结果是每次跑测试
 *       都要先初始化一次环境，或者直接以一个和代码无关的理由失败。</li>
 * </ul>
 *
 * <p>每条断言都做过反向验证：把实现改坏 → 这条测试变红 → 改回来（见交接记录）。
 * 只看「它现在是绿的」不算数——绿可能是它压根没验到东西。
 */
@DisplayName("执行位置（单元在容器里还是宿主上）")
class ExecutionLocationTest {

    @TempDir
    Path root;

    /** 声明：app 用一个小镜像，workdir 定死在 {@code /work}（命令里要出现它）。 */
    private static final String DECLARATION = """
            image: "busybox:latest"
            workdir: "/work"
            """;

    private static final String ENTRY = "tools/20260930-120000/";

    // ---------- 1. 容器可用 → 单元也在容器里 ----------

    @Test
    @DisplayName("Docker 可用 + 环境已初始化：单元也进容器（入口是 run.sh，命令走 docker compose exec）")
    void runsUnitInTheContainerWhenTheEnvironmentIsUp() throws IOException {
        declare(DECLARATION);
        Path compose = initialized();
        TestEnvironment environment = new TestEnvironment(root, dockerReady());

        ExecutionLocation location = ExecutionLocation.of(environment);

        assertThat(location.inContainer()).as("环境活着就该进容器").isTrue();
        assertThat(location.unitEntryName())
                .as("容器是 Linux：入口不能是宿主那一套 .cmd")
                .isEqualTo("run.sh");
        assertThat(location.integrationEntryName()).isEqualTo("run-it.sh");
        assertThat(location.command(ENTRY + "run.sh")).containsExactlyElementsOf(List.of(
                "docker", "compose", "-p", ComposeFile.projectName(root), "-f", compose.toString(),
                "exec", "-T", ComposeFile.APP_SERVICE, "sh", "-c",
                "cd \"/work\" && sh \"" + ENTRY + "run.sh\""));
        assertThat(location.label()).contains("容器").doesNotContain("未用容器");
    }

    /**
     * 超时之后要收掉容器里那一半——宿主上杀进程树杀不到它。
     *
     * <p>2026-10-04 的真容器实测：宿主侧报「已强制终止」之后，容器里的脚本又跑了 25～77 秒
     * 才收工，把产物写了个遍。脚本是容器里的 PID 1 领起来的，宿主上只看得见一个
     * {@code docker compose exec} 客户端，所以「重启那个 app 服务」是唯一不依赖镜像里有
     * 什么工具、又不把环境留在 DOWN 上的收法（留在 DOWN 会让下一轮悄悄回退到宿主）。
     */
    @Test
    @DisplayName("超时收尾：容器里是「重启 app 服务」，宿主上是空表（没有容器可收）")
    void stopsTheContainerOnTimeout() throws IOException {
        declare(DECLARATION);
        Path compose = initialized();

        List<String> stop = ExecutionLocation.of(new TestEnvironment(root, dockerReady()))
                .stopCommand();

        assertThat(stop).containsExactlyElementsOf(List.of(
                "docker", "compose", "-p", ComposeFile.projectName(root), "-f", compose.toString(),
                "restart", ComposeFile.APP_SERVICE));
        assertThat(ExecutionLocation.host().stopCommand())
                .as("宿主上没有容器可收，命令是空的——调用方据此判断「要不要多收一步」")
                .isEmpty();
    }

    /**
     * 入口脚本的名字必须跟着执行位置走。
     *
     * <p>这一条要是错了，表现特别绕：引擎让模型写 {@code run.sh}，自己却去找 {@code run.cmd}
     * （或者反过来），于是产物明明在、报出来的是「产物里没有入口脚本」——一句与事实不符的话。
     */
    @Test
    @DisplayName("产物目录里的入口脚本名跟着执行位置：容器里是 run.sh / run-it.sh")
    void namesTheEntryAfterTheLocation() throws IOException {
        declare(DECLARATION);
        initialized();

        TestArtifacts inside = TestArtifacts.create(root,
                ExecutionLocation.of(new TestEnvironment(root, dockerReady())));
        TestArtifacts onHost = TestArtifacts.create(root, ExecutionLocation.host());

        assertThat(inside.entry()).endsWith("/run.sh");
        assertThat(inside.integrationEntry()).endsWith("/run-it.sh");
        assertThat(onHost.entry()).as("宿主上还是本机平台那一个").endsWith("/" + EntryScripts.name());
        assertThat(inside.entry()).isNotEqualTo(onHost.entry());
    }

    /**
     * 进容器的命令要真的送进容器，而且要在<b>项目挂进去的那个目录</b>里跑。
     *
     * <p>{@code -T} 少了，docker 会因为「not a TTY」直接拒绝——而这里根本没终端；
     * {@code cd <workdir>} 少了，脚本会在容器的根目录里跑，它旁边的产物一个都找不到。
     */
    @Test
    @DisplayName("进容器的命令：-T、compose 项目名与文件、cd 到 workdir，一个都不能少")
    void theContainerCommandCarriesEverything() throws IOException {
        declare(DECLARATION);
        Path compose = initialized();

        List<String> command = ExecutionLocation.of(new TestEnvironment(root, dockerReady()))
                .command(ENTRY + "run.sh");

        assertThat(command).containsSubsequence("compose", "-p", "-f", "exec", "-T");
        assertThat(command).contains(compose.toString());
        assertThat(command.get(command.size() - 1)).startsWith("cd \"/work\"");
        assertThat(command)
                .as("宿主那条命令的样子（cmd /c …、sh -c …）在容器这条路上一个都不该出现")
                .doesNotContain("cmd.exe").doesNotContain("/bin/sh");
    }

    // ---------- 2. Docker 用不了 → 单元回退宿主，而且要说出来 ----------

    @Test
    @DisplayName("Docker daemon 没起：单元回退宿主，且结果里写明「在宿主执行（未用容器）」")
    void fallsBackToTheHostWhenDockerIsDown() throws IOException {
        declare(DECLARATION);
        initialized();
        // daemon 没起（Windows 上占绝大多数的那一档）：命令在，但用不了
        TestEnvironment environment = new TestEnvironment(root,
                new FakeCommandRunner().fail("version", 1,
                        "Cannot connect to the Docker daemon at unix:///var/run/docker.sock"));

        ExecutionLocation location = ExecutionLocation.of(environment);

        assertThat(location.inContainer()).isFalse();
        assertThat(location.label())
                .as("回退宿主必须写在留档里，措辞要说清「未用容器」")
                .isEqualTo("在宿主执行（未用容器）");
        assertThat(location.unitEntryName()).isEqualTo(EntryScripts.name());
        assertThat(location.command(ENTRY + EntryScripts.name()))
                .as("宿主上就是拿本机 shell 跑这个脚本")
                .hasSize(3)
                .doesNotContain("compose");
    }

    /**
     * 回退宿主不是「什么都能跑」：动作本身照样跑得起来，而结论里先写着位置。
     *
     * <p>这一条同时钉住那两件事：宿主那条路还是通的（脚本真跑了，退出码是真的），
     * 以及 {@link com.specflow.verify.VerificationResult#command()}——它进留档、也进界面，
     * 是「这次没用容器」唯一会被用户看见的地方。
     */
    @Test
    @DisplayName("宿主这条路真的跑得起来，而且留档那一栏先写执行位置")
    void stillRunsOnTheHostAndSaysSoInTheArchive() throws IOException {
        declare(DECLARATION);
        initialized();
        ExecutionLocation location = ExecutionLocation.of(new TestEnvironment(root,
                new FakeCommandRunner().fail("version", 1, "daemon 没起来")));
        TestArtifacts artifacts = TestArtifacts.create(root, location);
        Path script = root.resolve(artifacts.entry());
        Files.createDirectories(script.getParent());
        Files.writeString(script, EntryScripts.body(0, "PASS | 1"), StandardCharsets.UTF_8);

        TestScriptVerifier.ScriptResult run = new TestScriptVerifier(root, artifacts.entry(),
                60, Map.of(), location).run(context());

        assertThat(run.exit()).as("宿主上真的跑完了").isZero();
        assertThat(run.verification().command())
                .as("结果与留档（RunRecord 存的就是这一栏）里必须写着执行位置")
                .startsWith("在宿主执行（未用容器）：")
                .endsWith(Path.of(artifacts.entry()).toString());
    }

    // ---------- 3. 环境没初始化 → 同样回退宿主 ----------

    @Test
    @DisplayName("Docker 好着呢、但环境没初始化：回退宿主（不许硬往容器里塞）")
    void fallsBackToTheHostBeforeInitialization() throws IOException {
        declare(DECLARATION);
        // 一份 compose 文件都不写 = 从没初始化过，这时候进容器的命令必然失败
        TestEnvironment environment = new TestEnvironment(root, dockerReady());
        assertThat(environment.docker().ready()).as("前提：Docker 本身是好的").isTrue();

        ExecutionLocation location = ExecutionLocation.of(environment);

        assertThat(location.inContainer()).isFalse();
        assertThat(location.label()).isEqualTo("在宿主执行（未用容器）");
        assertThat(location.unitEntryName()).isEqualTo(EntryScripts.name());
    }

    /**
     * 环境关了（compose 文件还在、容器没了）也走回退。
     *
     * <p>和上一条不同的地方在于：这份环境「初始化过」，只是现在不在跑。
     * 这时候硬进容器，一次本来跑得好好的单元测试会变成一个「环境问题」——
     * 看起来像代码坏了，而实际上只是有人把容器关了。
     */
    @Test
    @DisplayName("环境初始化过但容器已经关了：同样回退宿主")
    void fallsBackToTheHostWhenTheContainersAreGone() throws IOException {
        declare(DECLARATION);
        initialized();
        // compose 文件在，但 ps -a 一件都不报（容器被关掉了）
        TestEnvironment environment = new TestEnvironment(root, dockerReady().ok("ps -a", ""));

        ExecutionLocation location = ExecutionLocation.of(environment);

        assertThat(location.inContainer()).isFalse();
        assertThat(location.label()).isEqualTo("在宿主执行（未用容器）");
    }

    @Test
    @DisplayName("项目根本没声明环境：宿主（连 docker 都不探一次）")
    void staysOnTheHostWithoutAnyDeclaration() {
        FakeCommandRunner runner = new FakeCommandRunner();

        ExecutionLocation location = ExecutionLocation.of(new TestEnvironment(root, runner));

        assertThat(location.inContainer()).isFalse();
        assertThat(location.label()).isEqualTo("在宿主执行（未用容器）");
        assertThat(runner.lines()).as("没声明就不该为它起进程").isEmpty();
    }

    @Test
    @DisplayName("没有环境这一说（null）：宿主，不抛异常")
    void aMissingEnvironmentIsTheHost() {
        assertThat(ExecutionLocation.of(null).inContainer()).isFalse();
        assertThat(ExecutionLocation.host().label()).isEqualTo("在宿主执行（未用容器）");
    }

    // ---------- 协议：脚本里能不能调 docker，取决于它被送到哪儿 ----------

    /**
     * 脚本已经在容器里跑的时候，协议<b>不许</b>再教它自己 {@code docker compose exec}。
     *
     * <p>那种错很隐蔽：模型照做了，而测试镜像里没有 docker 命令，脚本会打印
     * 「命令不存在」——看起来像环境没装好，实际是协议说反了。
     */
    @Test
    @DisplayName("协议：进容器时不让脚本自己调 docker，回退宿主时才把 exec 的拼法教给它")
    void theProtocolTellsItWhereTheScriptRuns() {
        String inside = TestProtocol.instructions("tools/20260930-120000",
                new TestProtocol.Entries(ENTRY + "run.sh", ENTRY + "run-it.sh", true),
                Map.of("DB_HOST", "db"));
        String outside = TestProtocol.instructions("tools/20260930-120000",
                new TestProtocol.Entries(ENTRY + EntryScripts.name(), null, false),
                Map.of("DB_HOST", "db"));

        assertThat(inside).contains("就在容器里跑").doesNotContain("docker compose -p <");
        assertThat(inside).contains("不要在你的脚本里再调 docker");
        assertThat(outside).contains("本机（宿主）")
                .contains("docker compose -p <SPECFLOW_COMPOSE_PROJECT>");
        assertThat(outside).as("宿主那条路上，脚本就是本机平台那一个名字")
                .contains(ENTRY + EntryScripts.name());
    }

    // ---------- 辅助 ----------

    /** 造一份「已经初始化过」的环境：compose 文件在。 */
    private Path initialized() throws IOException {
        Path compose = ComposeFile.fileOf(root, "20260101-000000");
        Files.createDirectories(compose.getParent());
        Files.writeString(compose, "services: {}\n", StandardCharsets.UTF_8);
        return compose;
    }

    /**
     * Docker 一切都好：命令在、daemon 活着，而且这个项目名下有一个活着的容器。
     *
     * <p>容器名按 compose 的项目名拼——{@code status()} 就是按名字认「这是不是我们的东西」，
     * 随便给一个名字它只会当成本项目的残留，那个状态不是 READY。
     */
    private FakeCommandRunner dockerReady() {
        return new FakeCommandRunner()
                .ok("version", "fake docker 1.0")
                .ok("ps -a", ComposeFile.projectName(root) + "-app-1");
    }

    private void declare(String source) throws IOException {
        Path file = root.resolve(EnvConfigLoader.relativePath());
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private VerificationContext context() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        return new VerificationContext(root, spec, ProjectConfig.DEFAULT);
    }
}
