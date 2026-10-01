package com.specflow.env;

import com.specflow.tests.TestArtifacts;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 测试环境的生命周期：起、重置、收、收残局（十五.5 / 15.8）。
 *
 * <p>这台机器上<b>不能真的起容器</b>——那要拉镜像、要跑几分钟、还会污染机器上跑着的
 * 别的东西。所以这一整套用两种方式测：
 * <ul>
 *   <li><b>白盒</b>（{@link FakeCommandRunner}）：命令一条条对出来，顺序、参数、
 *       失败时的收场全都钉得住，毫秒级；</li>
 *   <li><b>灰盒</b>（{@link FakeDocker}）：把「docker」换成一个真会被执行的脚本，
 *       走完整条进程链（起进程、传参、读输出、退出码），再回头核对它收到的参数。
 *       白盒证明意图，灰盒证明事实。</li>
 * </ul>
 */
@DisplayName("测试环境生命周期")
class TestEnvironmentTest {

    @TempDir
    Path root;

    private static final String DECLARATION = """
            image: "eclipse-temurin:17"
            workdir: "/work"

            dependencies:
              db:
                image: "mysql:8.0"
                healthcheck: "mysqladmin ping"

            env:
              DB_HOST: "db"
              DB_PORT: "3306"

            init:
              - "python -m build-db"

            reset:
              - "python -m clean-db"
            """;

    // ---------- 状态 ----------

    @Test
    @DisplayName("没声明环境：只能跑单元测试，而且一条命令都不起（没写就不该为它付出任何代价）")
    void declaresNothingWhenThereIsNoDeclaration() {
        FakeCommandRunner runner = new FakeCommandRunner();
        TestEnvironment environment = new TestEnvironment(root, runner);

        TestEnvironment.Status status = environment.status();

        assertThat(status.declared()).isFalse();
        assertThat(status.state()).isEqualTo(EnvRegistration.State.NOT_DECLARED);
        assertThat(status.usable()).isFalse();
        assertThat(status.canInit()).as("没声明就没什么可初始化的").isFalse();
        assertThat(status.todo()).contains("env.yaml");
        assertThat(runner.calls()).as("连 docker 都不该探一次").isEmpty();
    }

    @Test
    @DisplayName("找不到 docker 命令：三档里的第一档，待办指向「装 Docker 或填命令」")
    void reportsMissingDocker() throws IOException {
        declare(DECLARATION);
        TestEnvironment environment = new TestEnvironment(root, new FakeCommandRunner());

        TestEnvironment.Status status = environment.status();

        assertThat(status.declared()).isTrue();
        assertThat(status.docker().state()).isEqualTo(DockerProbe.State.COMMAND_NOT_FOUND);
        assertThat(status.state()).isEqualTo(EnvRegistration.State.NOT_READY);
        assertThat(status.canInit()).as("docker 都用不了，点了初始化也是白点").isFalse();
        assertThat(status.todo()).contains("docker");
        assertThat(status.registration().detail()).contains("找不到 docker 命令");
    }

    @Test
    @DisplayName("没初始化过：说清「点一下初始化」，并说明之后只会重置数据、不重建容器")
    void asksToInitializeWhenDockerIsReady() throws IOException {
        declare(DECLARATION);
        TestEnvironment environment = new TestEnvironment(root, dockerReady());

        TestEnvironment.Status status = environment.status();

        assertThat(status.state()).isEqualTo(EnvRegistration.State.NOT_READY);
        assertThat(status.canInit()).isTrue();
        assertThat(status.usable()).isFalse();
        assertThat(status.todo()).contains("初始化").contains("重置数据");
        assertThat(status.leftovers()).isZero();
    }

    // ---------- 起环境 ----------

    @Test
    @DisplayName("起环境：写 compose → up -d --wait → 逐条跑 init；登记看得见容器与目录")
    void bringsTheEnvironmentUp() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady()
                .ok("up -d --wait", "Container sf-app-1 Started")
                .ok("exec -T app sh -c python -m build-db", "")
                .ok("ps -a", project() + "-app-1")
                .ok("network ls", project() + "_default")
                .ok("volume ls", project() + "_data");
        TestEnvironment environment = new TestEnvironment(root, runner);

        EnvRegistration done = environment.up();

        assertThat(done.state()).isEqualTo(EnvRegistration.State.READY);
        // compose 文件落位：.specflow/env/<时间戳>/compose.yaml（不碰项目自己的 compose）
        Path compose = environment.activeComposeFile();
        assertThat(compose).exists();
        assertThat(root.relativize(compose).toString().replace('\\', '/'))
                .startsWith(ComposeFile.ROOT + "/").endsWith(ComposeFile.NAME);
        assertThat(done.composeFile()).isEqualTo(root.relativize(compose).toString().replace('\\', '/'));

        // 起环境那条命令：项目名、compose 文件、--wait 一个都不能少。
        // 少了 -p，容器会挂到别的项目名下，下一轮就找不到它们
        String up = runner.first("up -d --wait");
        assertThat(up).contains("-p " + project());
        assertThat(up).contains("-f " + compose);
        assertThat(up).as("不许 sleep 等服务：健康检查才是「它好了」的判据")
                .doesNotContain("sleep");

        // init 在起来之后跑，而且是在 app 容器里跑（-T：引擎这边没有终端）
        assertThat(runner.indexOf("up -d --wait")).isLessThan(runner.indexOf("exec -T app"));
        assertThat(runner.first("exec -T app sh -c python -m build-db")).as("init 在 app 容器里跑").isNotNull();

        assertThat(done.containers()).containsExactly(project() + "-app-1");
        assertThat(done.volumes()).containsExactly(project() + "_data");
        assertThat(done.directories()).hasSize(1);
        assertThat(done.commands()).containsExactly("python -m build-db");
        assertThat(done.summarize()).contains("已就绪").contains("容器");
    }

    @Test
    @DisplayName("up 起不来：立刻把起了一半的东西收掉，并把原始错误与待办交出来")
    void cleansUpWhenUpFails() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady()
                .fail("up -d --wait", 1,
                        "Error response from daemon: pull access denied for eclipse-temurin, "
                                + "repository does not exist");
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.START);
                    assertThat(problem.command()).contains("up -d --wait");
                    // 原始错误原样带出去：掐掉它，用户只能猜是哪一步出的问题
                    assertThat(problem.output()).contains("pull access denied");
                    assertThat(problem.todo()).contains("原始错误");
                    assertThat(problem.detail()).contains("原始错误");
                });

        // 半成品环境比干净地没起来糟得多：下一轮 up 会去复用它
        assertThat(runner.ran("down -v --remove-orphans")).isTrue();
        // 那份 compose 目录被收掉了（.specflow/env 这一层留着不碍事，里面必须是空的）
        assertThat(ComposeFile.rootOf(root)).isEmptyDirectory();
    }

    @Test
    @DisplayName("up 超时：待办说的是「先手工跑一遍看卡在哪」，而不是「镜像名写错了」")
    void explainsTimeoutSeparately() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady()
                .timeout("up -d --wait", "超过 600 秒没有回话");
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.todo()).contains("超时");
                    assertThat(problem.detail()).contains("600");
                });
    }

    /**
     * 待办要认出<b>是哪一类</b>起不来：容器起不来和依赖不健康，下一步动作完全不同。
     *
     * <p>原始错误用的是真 docker 的原话（真跑出来的那句 {@code dependency failed to start:
     * container … is unhealthy}），判据也只认 docker 自己说过的话。
     */
    @Test
    @DisplayName("待办分得清：镜像拉不到 / 依赖不健康 / 认不出来时退回原话")
    void explainsTheRightKindOfStartFailure() throws IOException {
        declare(DECLARATION);

        EnvProblem missing = startFailure(dockerReady().fail("up -d --wait", 1,
                "Error response from daemon: pull access denied for sf-nope/nope, "
                        + "repository does not exist or may require 'docker login'"));
        assertThat(missing.todo()).contains("镜像拿不到").contains("连不连得上镜像仓库");

        EnvProblem unhealthy = startFailure(dockerReady().fail("up -d --wait", 1,
                "Container sf-x-cache-1 Waiting\n"
                        + "Error dependency cache failed to start\n"
                        + "dependency failed to start: container sf-x-cache-1 is unhealthy"));
        assertThat(unhealthy.todo()).contains("没等到健康").contains("healthcheck");

        EnvProblem unknown = startFailure(dockerReady().fail("up -d --wait", 1,
                "some brand new docker error we have never seen"));
        assertThat(unknown.todo()).as("认不出来就退回那句笼统的，不编")
                .contains("按上面的原始错误处理");
    }

    private EnvProblem startFailure(FakeCommandRunner runner) {
        TestEnvironment environment = new TestEnvironment(root, runner);
        try {
            environment.up();
        } catch (EnvProblem problem) {
            return problem;
        }
        throw new AssertionError("这次 up 本该失败");
    }

    @Test
    @DisplayName("init 失败：报出是哪条命令，并把它收拾干净（环境不可用，不能留着）")
    void reportsTheFailingInitCommand() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady()
                .ok("up -d --wait", "")
                .fail("exec -T app sh -c python -m build-db", 2,
                        "ModuleNotFoundError: No module named 'build_db'");
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.INIT);
                    // 用户最需要的就是这一条命令：他可以自己拿它去跑一遍
                    assertThat(problem.command()).contains("python -m build-db");
                    assertThat(problem.output()).contains("ModuleNotFoundError");
                    assertThat(problem.todo()).contains("手工进容器");
                });

        assertThat(runner.ran("down -v --remove-orphans")).isTrue();
        assertThat(ComposeFile.rootOf(root)).isEmptyDirectory();
    }

    @Test
    @DisplayName("init 的前一条失败：后面的不再跑（逐条、失败即停）")
    void stopsAtTheFirstFailingInitCommand() throws IOException {
        declare("""
                image: "x:1"
                init:
                  - "first-step"
                  - "second-step"
                """);
        FakeCommandRunner runner = dockerReady()
                .ok("up -d --wait", "")
                .fail("first-step", 1, "boom");
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class);
        assertThat(runner.ran("second-step")).as("前一条就挂了，后一条不该跑").isFalse();
    }

    // ---------- 每次跑前重置 ----------

    @Test
    @DisplayName("reset：在容器里逐条跑，并登记「这次跑过哪几条」")
    void resetsBeforeEachRun() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady()
                .ok("up -d --wait", "")
                .ok("exec -T app", "")
                .ok("ps -a", project() + "-app-1");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();

        EnvRegistration reset = environment.reset();

        assertThat(runner.first("exec -T app sh -c python -m clean-db")).as("reset 也在 app 容器里跑").isNotNull();
        assertThat(reset.commands()).containsExactly("python -m clean-db");
        assertThat(reset.detail()).contains("已重置数据");
        // 重置**不**重建容器：容器常驻复用，每次跑前只重置数据（十五.5）
        assertThat(runner.all("up -d --wait")).hasSize(1);
    }

    @Test
    @DisplayName("没配 reset：说清这一轮会直接用上一轮的数据，而不是假装重置过")
    void saysSoWhenThereIsNoReset() throws IOException {
        declare("""
                image: "x:1"
                """);
        FakeCommandRunner runner = dockerReady().ok("up -d --wait", "").ok("ps -a", "");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();

        EnvRegistration reset = environment.reset();

        assertThat(reset.commands()).isEmpty();
        assertThat(reset.detail()).contains("没有配 reset");
        assertThat(runner.ran("exec -T app")).as("没配就不该往容器里跑东西").isFalse();
    }

    @Test
    @DisplayName("reset 失败：报出那条命令，但**不**收环境（容器是好的，坏的只是那条命令）")
    void keepsTheEnvironmentWhenResetFails() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady()
                .ok("up -d --wait", "")
                .fail("exec -T app sh -c python -m clean-db", 1, "table does not exist");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();

        assertThatThrownBy(environment::reset).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.RESET);
                    assertThat(problem.command()).contains("python -m clean-db");
                    assertThat(problem.todo()).contains("环境本身没删");
                });

        assertThat(runner.ran("down")).as("把容器删掉只会让用户多等一次镜像拉取，而问题还在")
                .isFalse();
    }

    @Test
    @DisplayName("没初始化就想重置：当场拒，并说清先初始化")
    void refusesResetBeforeInit() throws IOException {
        declare(DECLARATION);
        TestEnvironment environment = new TestEnvironment(root, dockerReady());

        assertThatThrownBy(environment::reset).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> assertThat(((EnvProblem) thrown).todo()).contains("初始化"));
    }

    // ---------- 安全闸 ----------

    @Test
    @DisplayName("env.yaml 里的高危命令：在写文件、起容器**之前**就被拒")
    void refusesDangerousCommandsBeforeAnythingRuns() throws IOException {
        declare("""
                image: "x:1"
                init:
                  - "sudo apt-get install -y curl"
                """);
        FakeCommandRunner runner = dockerReady();
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.CHECK);
                    assertThat(problem.output()).contains("sudo");
                    assertThat(problem.todo()).contains("env.yaml");
                });

        assertThat(runner.ran("up -d --wait")).as("起完再拒，用户已经等了几分钟").isFalse();
        assertThat(ComposeFile.rootOf(root)).as("一个字节都不该落盘").doesNotExist();
    }

    @Test
    @DisplayName("reset 里的高危命令同样被拒（闸门不只挡 init）")
    void refusesDangerousResetCommands() throws IOException {
        declare("""
                image: "x:1"
                reset:
                  - "rm -rf / --no-preserve-root"
                """);
        FakeCommandRunner runner = dockerReady();
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> assertThat(((EnvProblem) thrown).output()).contains("rm"));
        assertThat(runner.ran("up -d --wait")).isFalse();
    }

    @Test
    @DisplayName("闸门和测试产物用的是同一张高危表（一处改、两处生效）")
    void sharesTheForbiddenList() {
        // 这一条是在钉「复用」，不是钉某一条模式：两处各维护一张表，迟早有一处少一条
        assertThat(TestArtifacts.forbidden("docker run --privileged x")).isNotNull();
        assertThat(TestArtifacts.forbidden("cat /etc/passwd > /dev/sda")).isNotNull();
        assertThat(TestArtifacts.forbidden("python -m build-db")).isNull();
    }

    /**
     * 清库那种正当命令要放行——而且放行的是「碰不到项目目录」那一类。
     *
     * <p>{@code workdir} 就是项目目录挂进容器里的位置，所以例外的边界跟着它走：
     * 挂到 {@code /data} 上时，{@code rm -rf /data/*} 删的正是用户的源码，必须拒。
     */
    @Test
    @DisplayName("容器内例外的边界就是挂载点：workdir=/data 时 rm -rf /data/* 一样被拒")
    void refusesClearingTheMountedProjectDirectory() throws IOException {
        declare("""
                image: "x:1"
                workdir: "/data"
                reset:
                  - "rm -rf /data/*"
                """);
        FakeCommandRunner runner = dockerReady();
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.CHECK);
                    assertThat(problem.output()).contains("rm -rf /data/*");
                });
        assertThat(runner.ran("up -d --wait")).as("拒在起容器之前").isFalse();
    }

    @Test
    @DisplayName("容器内的数据目录放行：reset 里 rm -rf /data/* 起得来，也真的进了容器")
    void allowsClearingContainerData() throws IOException {
        declare("""
                image: "x:1"
                workdir: "/work"
                reset:
                  - "rm -rf /data/*"
                """);
        FakeCommandRunner runner = dockerReady().ok("up -d --wait", "").ok("exec -T app", "");
        TestEnvironment environment = new TestEnvironment(root, runner);

        environment.up();
        EnvRegistration reset = environment.reset();

        assertThat(reset.commands()).containsExactly("rm -rf /data/*");
        assertThat(runner.ran("exec -T app sh -c rm -rf /data/*"))
                .as("命令进的是容器：宿主上这条命令是「删 /data」，一个字都不该发生")
                .isTrue();
    }

    /**
     * 闸门也要管 {@code reset} 这条路：{@code specflow env reset} 与收场时的「清环境数据」
     * 都只调 {@code reset()}，不经过 {@code up()}。
     *
     * <p>不判它，这条命令就成了绕开闸门的一条路——场景很实在：环境起好之后，
     * 用户把 {@code env.yaml} 里的 reset 改成了一条会删源码的命令。
     */
    @Test
    @DisplayName("环境起好之后改了 env.yaml：reset 自己再判一遍，不许绕开闸门")
    void rechecksResetCommandsOnEveryReset() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady().ok("up -d --wait", "").ok("exec -T app", "");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();

        // 起环境之后把 reset 改成一条冲着项目目录去的命令
        declare("""
                image: "eclipse-temurin:17"
                workdir: "/work"
                reset:
                  - "rm -rf /work/src"
                """);

        assertThatThrownBy(environment::reset).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.CHECK);
                    assertThat(problem.output()).contains("rm -rf /work/src");
                });
        assertThat(runner.ran("exec -T app sh -c rm -rf /work/src"))
                .as("拒了就不许执行").isFalse();
    }

    // ---------- 收环境 ----------

    @Test
    @DisplayName("down：连卷一起删（down -v），compose 目录一并收掉")
    void tearsTheEnvironmentDown() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady().ok("up -d --wait", "").ok("exec -T app", "");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();
        Path compose = environment.activeComposeFile();
        assertThat(compose).exists();

        EnvRegistration done = environment.down(true);

        assertThat(runner.ran("down -v --remove-orphans")).isTrue();
        assertThat(done.state()).isEqualTo(EnvRegistration.State.DOWN);
        assertThat(compose).as("把环境关掉之后，那份 compose 文件不再是把手了")
                .doesNotExist();
    }

    /**
     * 关不掉是要报出来的：用户点了「清空测试环境」却什么都没被告知，
     * 他会以为环境真被清掉了——而它还在，下一轮还会去复用那些东西。
     */
    @Test
    @DisplayName("down 失败：报出来（原始错误 + 那条命令），不假装关掉了")
    void reportsWhenDownFails() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady()
                .ok("up -d --wait", "")
                .fail("down -v", 1, "container is in use by another process");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();

        assertThatThrownBy(() -> environment.down(true)).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.DOWN);
                    assertThat(problem.command()).contains("down -v --remove-orphans");
                    assertThat(problem.output()).contains("in use by another process");
                });
    }

    @Test
    @DisplayName("关环境时 docker 用不了：说清是 docker 的问题，而不是静默什么都没做")
    void reportsWhenDockerIsGoneOnDown() throws IOException {
        declare(DECLARATION);
        TestEnvironment environment = new TestEnvironment(root, new FakeCommandRunner());

        assertThatThrownBy(() -> environment.down(true)).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.CHECK);
                    assertThat(problem.todo()).contains("docker");
                });
    }

    // ---------- 收残局 ----------

    @Test
    @DisplayName("收残局：没有 compose 文件的容器/卷/网络按<b>容器 → 卷 → 网络</b>逆序清掉")
    void collectsLeftoversInReverseOrder() throws IOException {
        declare(DECLARATION);
        String prefix = project();
        FakeCommandRunner runner = dockerReady()
                .ok("ps -a", prefix + "-app-1")
                .ok("volume ls", prefix + "_data")
                .ok("network ls", prefix + "_default")
                .ok("rm -f", prefix + "-app-1")
                .ok("volume rm", "")
                .ok("network rm", "");
        // 东西真的被删掉了：删完之后那几个查询就不该再报出它们
        runner.on("rm -f", runner::clear);
        TestEnvironment environment = new TestEnvironment(root, runner);

        EnvRegistration before = environment.leftovers();
        assertThat(before.size()).isEqualTo(3);
        assertThat(before.detail()).contains("上一轮留下的东西");

        EnvRegistration cleaned = environment.cleanupLeftovers();

        // 顺序不能反：卷还挂在活着的容器上时删不掉，网络上有容器连着时也删不掉
        assertThat(runner.indexOf("rm -f " + prefix + "-app-1"))
                .isLessThan(runner.indexOf("volume rm " + prefix + "_data"));
        assertThat(runner.indexOf("volume rm " + prefix + "_data"))
                .isLessThan(runner.indexOf("network rm " + prefix + "_default"));
        assertThat(cleaned.detail()).contains("收掉了上一轮留下的 3 件东西");
        assertThat(cleaned.state()).isEqualTo(EnvRegistration.State.DOWN);
    }

    @Test
    @DisplayName("收不掉的如实报出来，不假装收干净了（十五.9：清理做不到 100%）")
    void reportsWhatItCouldNotRemove() throws IOException {
        declare(DECLARATION);
        String prefix = project();
        FakeCommandRunner runner = dockerReady()
                .ok("ps -a", prefix + "-app-1")
                .fail("rm -f", 1, "container is running");
        TestEnvironment environment = new TestEnvironment(root, runner);

        EnvRegistration cleaned = environment.cleanupLeftovers();

        assertThat(cleaned.detail()).contains("还剩").contains("没收掉");
        assertThat(cleaned.state()).as("还有残留就不是「已关闭」").isEqualTo(EnvRegistration.State.BROKEN);
    }

    @Test
    @DisplayName("收残局只动「名字也像自己项目」的东西：别人起的容器一根汗毛都不碰")
    void neverTouchesOtherProjects() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady()
                .ok("ps -a", "someone-elses-app-1\nkust-mysql")
                .ok("volume ls", "other_data")
                .ok("network ls", "bridge");
        TestEnvironment environment = new TestEnvironment(root, runner);

        EnvRegistration leftovers = environment.leftovers();

        assertThat(leftovers.size()).as("名字对不上就不认，宁可不收也不能删错").isZero();
        environment.cleanupLeftovers();
        assertThat(runner.ran("rm -f")).isFalse();
        assertThat(runner.ran("volume rm")).isFalse();
        assertThat(runner.ran("network rm")).isFalse();
    }

    /**
     * 别人同名目录起的东西：靠 docker 的 filter 挡在门外。
     *
     * <p>两个同名目录（比如两份 {@code backend}）本来会算出同一个 compose 项目名，
     * 而那是唯一会「删到别人东西」的情形。现在有两道：
     * ①项目名里带着从<b>绝对路径</b>算出来的短哈希（同名目录也不会撞，见 {@code ComposeFileTest}）；
     * ②有 compose 文件时再加一道 {@code working_dir} 过滤，只认这个目录起出来的。
     *
     * <p>这里钉的是②真的被发出了（判据在 docker 那边执行，桩看不出来）；①由
     * 「名字前缀」那一关兜着——名字像、但项目名对不上的东西一律不认。
     */
    @Test
    @DisplayName("同名目录起的容器：查询里带着 working_dir 过滤，名字对不上的一律不认")
    void ignoresContainersFromAnotherDirectory() throws IOException {
        declare(DECLARATION);
        String prefix = project();
        FakeCommandRunner runner = dockerReady()
                .ok("up -d --wait", "")
                .ok("ps -a", prefix + "-app-1\nsf-another-project-app-1");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();

        assertThat(environment.status().registration().containers())
                .as("名字前缀对不上的（别的项目）不认")
                .containsExactly(prefix + "-app-1");
        assertThat(runner.first("ps -a")).contains("working_dir=");
    }

    @Test
    @DisplayName("老版本 compose 不打目录标签：按名字前缀认（一律不认的话残留永远收不掉）")
    void acceptsContainersWithoutTheDirectoryLabel() throws IOException {
        declare(DECLARATION);
        String prefix = project();
        FakeCommandRunner runner = dockerReady().ok("ps -a", prefix + "-app-1");
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThat(environment.leftovers().containers()).containsExactly(prefix + "-app-1");
    }

    /**
     * 有 compose 文件时，查询要加上「只认这个目录起出来的」那一道过滤。
     *
     * <p>为什么用 {@code --filter} 而不是格式模板里的字符串比较：真 docker 实测过——
     * ProcessBuilder 传不了参数里的双引号，模板里写 {@code {{.Label "x.y"}}} 会被吃掉引号，
     * docker 于是报 {@code function "com" not defined}，整个查询一条都返回不了。
     * 而 filter 的值不是模板，没有引号，照样精确。
     */
    @Test
    @DisplayName("有 compose 文件时：容器查询带上 working_dir 过滤（判据不带引号，真 docker 认）")
    void filtersContainersByTheComposeDirectory() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady().ok("up -d --wait", "").ok("ps -a", "");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();

        assertThat(runner.first("ps -a")).satisfies(command -> {
            assertThat(command).contains("--filter label=com.docker.compose.project=");
            assertThat(command)
                    .as("这一道是「只要这个目录起出来的」")
                    .contains("--filter label=com.docker.compose.project.working_dir="
                            + environment.activeComposeFile().getParent());
            assertThat(command).as("格式里不许出现双引号").doesNotContain("\"");
        });
    }

    @Test
    @DisplayName("没有 compose 文件的残局：只按项目标签查（名字里带着路径哈希，够精了）")
    void queriesByProjectLabelOnlyForLeftovers() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady().ok("ps -a", "");
        TestEnvironment environment = new TestEnvironment(root, runner);

        environment.leftovers();

        assertThat(runner.first("ps -a")).satisfies(command -> {
            assertThat(command).contains("--filter label=com.docker.compose.project=");
            assertThat(command).doesNotContain("working_dir");
            assertThat(command).doesNotContain("\"");
        });
    }

    /**
     * 镜像自带的 {@code VOLUME}（redis 的 {@code /data}）会生成<b>匿名卷</b>，
     * 而匿名卷<b>没有</b> compose 的项目标签（真 docker 实测：标签只有
     * {@code com.docker.volume.anonymous:}）。只看标签的话它永远收不掉——一轮攒一个
     * （真的漏过：清完之后机器上多了两个匿名卷）。
     */
    @Test
    @DisplayName("匿名卷没有 compose 标签：靠「哪个容器挂着它」认出来，别让它一轮攒一个")
    void findsAnonymousVolumesThroughTheirContainer() throws IOException {
        declare(DECLARATION);
        String prefix = project();
        String anonymous = "8".repeat(64);
        FakeCommandRunner runner = dockerReady()
                .ok("ps -a", prefix + "-cache-1")
                .ok("volume ls", "")
                .ok("inspect", mountJson(anonymous));
        TestEnvironment environment = new TestEnvironment(root, runner);

        EnvRegistration leftovers = environment.leftovers();

        assertThat(leftovers.volumes())
                .as("标签查不到，但容器身上挂着它——那是最硬的归属证据")
                .containsExactly(anonymous);
        assertThat(leftovers.summarize()).contains("卷");
    }

    /** {@code docker inspect --format {{json .Mounts}}} 回来的那一行（真 docker 的形状）。 */
    private static String mountJson(String volume) {
        return "[{\"Type\":\"volume\",\"Name\":\"" + volume + "\",\"Source\":\"/var/lib/docker/volumes/"
                + volume + "/_data\",\"Destination\":\"/data\",\"Driver\":\"local\",\"Mode\":\"\","
                + "\"RW\":true,\"Propagation\":\"\"}]";
    }

    @Test
    @DisplayName("挂载信息读不懂时：不认那几件卷，也不报错（宁可不收，不能删错）")
    void ignoresUnreadableMountInfo() {
        assertThat(TestEnvironment.volumeNamesIn("template parsing error: ...")).isEmpty();
        assertThat(TestEnvironment.volumeNamesIn("[]")).isEmpty();
        assertThat(TestEnvironment.volumeNamesIn("")).isEmpty();
        // 只有 bind 挂载（没有匿名卷）时也不该冒出什么名字
        assertThat(TestEnvironment.volumeNamesIn(
                "[{\"Type\":\"bind\",\"Source\":\"E:/proj\",\"Destination\":\"/work\"}]")).isEmpty();
    }

    @Test
    @DisplayName("有 compose 文件 = 这套环境有人管：收残局不拆它（那正是「容器常驻复用」）")
    void keepsAManagedEnvironmentAlone() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady().ok("up -d --wait", "").ok("exec -T app", "")
                .ok("ps -a", project() + "-app-1");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();

        EnvRegistration result = environment.cleanupLeftovers();

        assertThat(result.detail()).contains("有人管");
        assertThat(runner.ran("rm -f")).isFalse();
        assertThat(runner.ran("down")).isFalse();
    }

    @Test
    @DisplayName("重新初始化：更早的那几份 compose 目录收掉，只留最新那一份当把手")
    void keepsOnlyTheNewestComposeDirectory() throws IOException {
        declare(DECLARATION);
        Path old = ComposeFile.fileOf(root, "20200101-000000");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "services: {}\n", StandardCharsets.UTF_8);
        FakeCommandRunner runner = dockerReady().ok("up -d --wait", "").ok("exec -T app", "");
        TestEnvironment environment = new TestEnvironment(root, runner);

        environment.up();

        assertThat(old).as("同一个项目名，容器是同一批：旧的那份已经没有意义").doesNotExist();
        assertThat(environment.activeComposeFile()).exists();
    }

    @Test
    @DisplayName("残留数量报得出来（超阈值告警要用它）")
    void countsLeftovers() throws IOException {
        declare(DECLARATION);
        String prefix = project();
        FakeCommandRunner runner = dockerReady()
                .ok("ps -a", prefix + "-1\n" + prefix + "-2\n" + prefix + "-3")
                .ok("volume ls", prefix + "_a\n" + prefix + "_b")
                .ok("network ls", prefix + "_net");
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThat(environment.leftovers().size())
                .isGreaterThan(TestEnvironment.LEFTOVER_WARN_THRESHOLD - 1);
    }

    /**
     * 阈值判据的边界：注释说的是「攒到五件」，那么第五件就该响。
     *
     * <p>这一条是实测踩出来的：判据写成 {@code >} 时正好五件哑火，而两处调用各写一遍
     * 就只能靠人去盯——现在它是这一个方法，界面和 CLI 都问它。
     */
    @Test
    @DisplayName("残留告警的阈值：正好到阈值就响（不是「超过」才响）")
    void warnsExactlyAtTheThreshold() {
        assertThat(TestEnvironment.shouldWarnAboutLeftovers(
                TestEnvironment.LEFTOVER_WARN_THRESHOLD - 1)).isFalse();
        assertThat(TestEnvironment.shouldWarnAboutLeftovers(
                TestEnvironment.LEFTOVER_WARN_THRESHOLD)).isTrue();
        assertThat(TestEnvironment.shouldWarnAboutLeftovers(
                TestEnvironment.LEFTOVER_WARN_THRESHOLD + 1)).isTrue();
    }

    @Test
    @DisplayName("查询命令失败：当成「没有残留」，绝不当成「有残留」（判断错的方向不能是乱删）")
    void treatsFailedQueriesAsNoLeftovers() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady().fail("ps -a", 1, "daemon hiccup");
        TestEnvironment environment = new TestEnvironment(root, runner);

        assertThat(environment.leftovers().size()).isZero();
    }

    // ---------- 连接信息 ----------

    @Test
    @DisplayName("连接信息原样给出去，再加上引擎那三个把手（脚本不用猜怎么进容器）")
    void handsOverTheConnectionInfo() throws IOException {
        declare(DECLARATION);
        FakeCommandRunner runner = dockerReady().ok("up -d --wait", "").ok("exec -T app", "")
                .ok("ps -a", project() + "-app-1");
        TestEnvironment environment = new TestEnvironment(root, runner);
        environment.up();

        Map<String, String> variables = environment.variables();

        assertThat(variables).containsEntry("DB_HOST", "db").containsEntry("DB_PORT", "3306");
        assertThat(variables).containsEntry(TestEnvironment.COMPOSE_PROJECT_VAR, project());
        assertThat(variables.get(TestEnvironment.COMPOSE_FILE_VAR))
                .isEqualTo(environment.activeComposeFile().toString());
        assertThat(variables).containsEntry(TestEnvironment.WORKDIR_VAR, "/work");
    }

    // ---------- 灰盒：真起进程 ----------

    @Test
    @DisplayName("灰盒：整条生命周期真的起进程跑一遍（docker 换成一个真脚本）")
    void walksTheLifecycleThroughRealProcesses() throws IOException {
        // `docker: command:` 指到一个真实的脚本上——它就是那个「用户手填的命令」，
        // 于是整条链（写 compose → up → init → reset → ps → down）都真的会起进程
        FakeDocker fake = FakeDocker.create(root.resolve("fake"));
        String prefix = project();
        fake.respond("compose", 0, "ok")
                .respond("exec -T", 0)
                .respond("ps -a", 0, prefix + "-app-1")
                .respond("volume ls", 0, prefix + "_data")
                .respond("network ls", 0, prefix + "_default")
                .respond("version", 0, "fake docker 1.0");
        declare("""
                docker:
                  command: "%s"
                image: "eclipse-temurin:17"
                env:
                  DB_HOST: "db"
                init:
                  - "python -m build-db"
                reset:
                  - "python -m clean-db"
                """.formatted(fake.script().toString().replace('\\', '/')));

        TestEnvironment environment = new TestEnvironment(root, new ProcessCommandRunner());
        EnvRegistration done = environment.up();
        environment.reset();

        assertThat(done.state()).isEqualTo(EnvRegistration.State.READY);
        List<String> calls = fake.calls();
        assertThat(calls.get(0)).as("先探一次 docker version").contains("version");
        // compose 命令的真样子：项目名、compose 文件、--wait 一个都不能少
        assertThat(calls).anySatisfy(call -> assertThat(call)
                .contains("compose -p " + prefix)
                .contains("-f " + environment.activeComposeFile())
                .contains("up -d --wait"));
        // init/reset 是在 app 容器里跑的（-T，且命令作为一整个参数传下去）
        assertThat(calls).anySatisfy(call -> assertThat(call)
                .contains("compose").contains("exec -T app sh -c").contains("build-db"));
        assertThat(calls).anySatisfy(call -> assertThat(call).contains("clean-db"));

        environment.down(true);
        assertThat(fake.calls()).anySatisfy(call ->
                assertThat(call).contains("down -v --remove-orphans"));
    }

    @Test
    @DisplayName("灰盒：没填 docker 命令时走 PATH（这条路由测试里的路径重写模拟）")
    void walksTheLifecycleWithThePathCandidate() throws IOException {
        FakeDocker fake = FakeDocker.create(root.resolve("fake"));
        fake.respond("up -d --wait", 0).respond("version", 0, "fake docker 1.0");
        declare("""
                image: "eclipse-temurin:17"
                """);

        TestEnvironment environment = new TestEnvironment(root, fake.runner());
        EnvRegistration done = environment.up();

        assertThat(done.composeProject()).isEqualTo(project());
        assertThat(fake.calls()).anySatisfy(call -> assertThat(call).contains("up -d --wait"));
        assertThat(fake.calls().get(0)).as("PATH 上的 docker 探得通").contains("version");
    }

    // ---------- 辅助 ----------

    /** 假 runner：探得到 docker、查询类命令一律空输出（没有残留）、DECLARATION 的 init 成功。 */
    private static FakeCommandRunner dockerReady() {
        return new FakeCommandRunner()
                .ok("version", "fake docker 1.0")
                .ok("ps -a", "")
                .ok("volume ls", "")
                .ok("network ls", "")
                .ok("down", "")
                // DECLARATION 里那一条 init：绝大多数测试只关心它成功；
                // 需要它失败的测试会再压一条更具体的规则（后加的先生效）
                .ok("exec -T app sh -c python -m build-db", "");
    }

    /**
     * compose 项目名。
     *
     * <p>用例里那些容器/卷名要和它对得上：名字不像这个项目的资源一律不认（安全口径），
     * 所以这里不能自己拼一个近似值，得用引擎算出来的那一个。
     */
    private String project() {
        return ComposeFile.projectName(root);
    }

    private void declare(String source) throws IOException {
        Path file = root.resolve(EnvConfigLoader.relativePath());
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
