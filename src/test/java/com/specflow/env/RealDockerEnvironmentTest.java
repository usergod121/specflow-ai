package com.specflow.env;

import com.specflow.history.RunRecord;
import com.specflow.history.RunStore;
import com.specflow.project.ProjectConfig;
import com.specflow.project.SnapshotConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.tests.Teardown;
import com.specflow.tests.TestOutcome;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.VerificationResult;
import com.specflow.web.RunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>真 Docker</b> 上的生命周期验证：真的起 compose、真的等健康检查、真的 exec、真的 down -v。
 *
 * <p>为什么要这一份：桩能证明「命令拼对了」，证明不了「docker 认这套命令」——
 * 而这一批的产物恰恰是<b>交给 docker 执行的东西</b>（compose 文件、exec 的参数、
 * down 的范围）。这台机器上没有 Docker 时它整类跳过（{@link #requireDocker}），
 * 所以它不会给别的环境添麻烦。
 *
 * <p><b>安全红线（本类每个测试都守）：</b>
 * <ul>
 *   <li>只用<b>本机已有</b>的小镜像（busybox 6.8MB / redis:7-alpine 57MB），不拉大镜像；
 *       {@link #requireImages} 会在镜像不在时跳过，而不是默默去网上拉；</li>
 *   <li>项目根是 JUnit 的临时目录，compose 项目名 = {@code sf-<目录名>-<路径短哈希>}，
 *       容器/网络/卷全挂在这个名字下——碰不到用户自己的 {@code kust-*}、{@code toonflow} 那些；</li>
 *   <li>每个测试结束都 {@code down -v} 并<b>自检残留</b>（{@link #assertNothingLeft}）；
 *       跑不出「清干净了」就红，绝不留下没人认领的容器/卷/网络。</li>
 * </ul>
 */
@DisplayName("真 Docker 上的测试环境生命周期")
class RealDockerEnvironmentTest {

    @TempDir
    Path root;

    private final ProcessCommandRunner docker = new ProcessCommandRunner();
    private final List<String> createdProjects = new ArrayList<>();

    /**
     * 声明：app 用 busybox（本机已有、6.8MB、自带 sh/sleep/nslookup），依赖用 redis。
     *
     * <p>init/reset 各写一个文件到<b>挂载进来的项目目录</b>里：这既证明 exec 真的在容器里跑了，
     * 也证明「项目目录挂进 workdir」这件事真的成立（否则文件不会出现在宿主这边）。
     * init 里还顺手解析一次依赖的服务名——容器之间靠服务名互连、一个宿主端口都不开。
     */
    private static final String DECLARATION = """
            image: "busybox:latest"
            workdir: "/work"

            dependencies:
              cache:
                image: "redis:7-alpine"
                healthcheck:
                  test: "redis-cli ping"
                  interval: "1s"
                  timeout: "2s"
                  retries: "30"
                  start-period: "1s"

            env:
              CACHE_HOST: "cache"

            init:
              - "echo init-ran > /work/init.marker"
              - "nslookup cache > /work/dns.txt"

            reset:
              - "echo reset-ran > /work/reset.marker"
            """;

    @AfterEach
    void cleanUp() {
        // 无论测试成没成，都把这个项目名下的东西收干净——这台机器上还跑着用户自己的容器，
        // 一条没人认领的残留都不该留下
        for (String project : createdProjects) {
            docker.run(List.of("compose", "-p", project, "down", "-v", "--remove-orphans"),
                    Map.of(), root, 120);
            removeByName(project);
        }
        createdProjects.clear();
    }

    // ---------- 1. 起、等健康检查、exec、重置、收 ----------

    @Test
    @DisplayName("真起一次：up -d --wait 等到依赖健康、exec 跑 init/reset、down -v 收干净")
    void walksTheWholeLifecycleAgainstRealDocker() throws IOException {
        requireDocker();
        requireImages("busybox:latest", "redis:7-alpine");
        declare(DECLARATION);
        TestEnvironment environment = TestEnvironment.of(root);
        String project = environment.composeProject();
        createdProjects.add(project);

        // ---- 起：写 compose → up -d --wait → 逐条跑 init ----
        EnvRegistration up = environment.up();
        assertThat(up.state()).isEqualTo(EnvRegistration.State.READY);

        // --wait 真的等到了健康：它返回的那一刻，依赖的 healthcheck 已经是 healthy。
        // 这一条只有真 docker 能给：桩只会回一句「成功」
        String redis = containerOf(project, "cache");
        assertThat(redis).as("依赖容器起来了：%s", namesOf(project)).isNotNull();
        assertThat(healthOf(redis))
                .as("up --wait 返回时依赖是 healthy，不是 merely running")
                .isEqualTo("healthy");

        // app 常驻：sleep infinity 在这个镜像上真的活着（否则 up --wait 会直接失败）
        String app = containerOf(project, "app");
        assertThat(app).isNotNull();
        assertThat(stateOf(app)).isEqualTo("running");

        // ---- init 的痕迹：文件出现在宿主这边的项目目录里 ----
        assertThat(read("init.marker")).isEqualTo("init-ran");
        assertThat(read("dns.txt"))
                .as("容器之间用服务名互连（host 上并没有任何端口映射）")
                .contains("cache");

        // 没有暴露宿主端口：compose 文件里那一栏一个都没有
        assertThat(Files.readString(environment.activeComposeFile())).doesNotContain("ports:");

        // ---- 登记里看得见容器（清理靠它） ----
        assertThat(up.containers()).as("登记要报出这个项目名下的容器").isNotEmpty();

        // ---- 每次跑前重置：真的在容器里跑，也真的碰得到挂载的项目目录 ----
        EnvRegistration reset = environment.reset();
        assertThat(reset.commands()).hasSize(1);
        assertThat(read("reset.marker")).isEqualTo("reset-ran");
        assertThat(containerOf(project, "app"))
                .as("重置不重建容器：还是同一个（容器常驻复用）")
                .isEqualTo(app);

        // ---- 收：down -v，连卷一起 ----
        EnvRegistration down = environment.down(true);
        assertThat(down.state()).isEqualTo(EnvRegistration.State.DOWN);
        assertThat(environment.status().usable()).isFalse();
        assertThat(environment.status().leftovers()).as("收完自检：一件残留都不该有").isZero();
        assertNothingLeft(project);
    }

    // ---------- 2. 失败分档（真拉不到镜像 / 真健康检查不过） ----------

    @Test
    @DisplayName("真拉不到镜像：算环境问题，原始错误是 docker 的原话，而且不留半成品")
    void reportsAMissingImageAsAnEnvironmentProblem() throws IOException {
        requireDocker();
        declare("""
                image: "sf-specflow-does-not-exist/nope:1"
                workdir: "/work"
                """);
        TestEnvironment environment = TestEnvironment.of(root);
        String project = environment.composeProject();
        createdProjects.add(project);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.START);
                    assertThat(problem.command()).contains("up -d --wait");
                    // 原始错误必须带着 docker 自己的话（哪条命令、什么原因）
                    assertThat(problem.output().toLowerCase())
                            .as("原始错误：%s", problem.output())
                            .containsAnyOf("pull access denied", "not found", "manifest unknown",
                                    "failed to resolve", "denied");
                });

        // 起不来就不许留半成品：网络/容器一个都不该剩
        assertThat(environment.status().leftovers()).isZero();
        assertThat(ComposeFile.rootOf(root)).as("那份 compose 目录也收掉了").isEmptyDirectory();
        assertNothingLeft(project);
    }

    @Test
    @DisplayName("真健康检查不过：up -d --wait 因它失败，算环境问题（不是「容器起来了就算好」）")
    void reportsAnUnhealthyDependencyAsAnEnvironmentProblem() throws IOException {
        requireDocker();
        requireImages("busybox:latest", "redis:7-alpine");
        // 健康检查命令永远失败（读一个不存在的文件）→ 容器 running 但 unhealthy
        declare("""
                image: "busybox:latest"
                workdir: "/work"
                dependencies:
                  broken:
                    image: "redis:7-alpine"
                    healthcheck:
                      test: "cat /no/such/file"
                      interval: "1s"
                      timeout: "1s"
                      retries: "2"
                      start-period: "1s"
                """);
        TestEnvironment environment = TestEnvironment.of(root);
        String project = environment.composeProject();
        createdProjects.add(project);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.START);
                    assertThat(problem.output().toLowerCase())
                            .as("原始错误：%s", problem.output())
                            .contains("health");
                    // 待办要认出「是依赖不健康」这一类，而不是笼统一句「按原始错误处理」
                    assertThat(problem.todo()).contains("没等到健康").contains("healthcheck");
                });

        assertThat(environment.status().leftovers()).isZero();
        assertNothingLeft(project);
    }

    @Test
    @DisplayName("真 init 失败：报环境问题并说清是哪条命令，容器也收掉")
    void reportsAFailingInitCommand() throws IOException {
        requireDocker();
        requireImages("busybox:latest");
        declare("""
                image: "busybox:latest"
                workdir: "/work"
                init:
                  - "echo 挂了 >&2; exit 3"
                """);
        TestEnvironment environment = TestEnvironment.of(root);
        String project = environment.composeProject();
        createdProjects.add(project);

        assertThatThrownBy(environment::up).isInstanceOf(EnvProblem.class)
                .satisfies(thrown -> {
                    EnvProblem problem = (EnvProblem) thrown;
                    assertThat(problem.step()).isEqualTo(EnvProblem.INIT);
                    assertThat(problem.command()).contains("exec -T app");
                    assertThat(problem.output()).contains("挂了");
                });

        assertThat(environment.status().leftovers()).isZero();
        assertNothingLeft(project);
    }

    // ---------- 2b. 执行位置：单元入口真的进容器里跑 ----------

    /**
     * 环境活着的时候，<b>单元入口脚本也是被送进容器里跑的</b>（十五.5）。
     *
     * <p>怎么证明它真的在容器里、而不是在宿主上「恰好跑通了」：让脚本把 {@code uname -s}
     * 写进挂载出来的项目目录。这台机器是 Windows，答案只可能是容器给的 {@code Linux}——
     * 宿主上压根没有 {@code uname}，而宿主的答案也不会是 Linux。
     *
     * <p>顺带钉住入口脚本的名字：进容器时它是 {@code run.sh}（容器是 Linux），
     * 宿主上那套 {@code run.cmd} 在容器里一个字都跑不了。
     */
    @Test
    @DisplayName("执行位置（真 docker）：环境活着 → 单元入口在容器里跑，入口叫 run.sh")
    void runsTheUnitEntryInsideTheRealContainer() throws IOException {
        requireDocker();
        requireImages("busybox:latest");
        declare("""
                image: "busybox:latest"
                workdir: "/work"
                """);
        TestEnvironment environment = TestEnvironment.of(root);
        createdProjects.add(environment.composeProject());
        environment.up();

        com.specflow.tests.ExecutionLocation location =
                com.specflow.tests.ExecutionLocation.of(environment);
        assertThat(location.inContainer()).as("环境活着就该进容器").isTrue();

        com.specflow.tests.TestArtifacts artifacts =
                com.specflow.tests.TestArtifacts.create(root, location);
        assertThat(artifacts.entry()).endsWith("/run.sh");
        Path script = root.resolve(artifacts.entry());
        Files.createDirectories(script.getParent());
        Files.writeString(script, String.join("\n",
                "#!/bin/sh",
                // 这一行是全部证据：宿主机上既没有 uname，答案也不会是 Linux
                "uname -s > /work/where.txt",
                "echo PASS \\| 1",
                "exit 0",
                ""), StandardCharsets.UTF_8);

        com.specflow.tests.TestScriptVerifier.ScriptResult run =
                new com.specflow.tests.TestScriptVerifier(root, artifacts.entry(), 120,
                        Map.<String, String>of(), location).run(
                        new com.specflow.verify.VerificationContext(root, spec(),
                                com.specflow.project.ProjectConfig.DEFAULT));

        assertThat(run.exit()).as("容器里跑完，退出码是脚本给的：%s", run.verification().output())
                .isZero();
        assertThat(read("where.txt"))
                .as("这个文件是容器里的脚本写出来的（项目目录挂在 /work）")
                .isEqualTo("Linux");
        assertThat(run.verification().command())
                .as("结果与留档里写着执行位置")
                .startsWith("在容器里执行")
                .contains(artifacts.entry());
    }

    // ---------- 3. 残留自检 + 打开项目收残局 ----------

    /**
     * 上一轮留下了没人管的东西（compose 文件被删了，容器/网络/卷还活着），
     * 「打开项目」要真的把它们收掉。
     *
     * <p>这是十五.8 的第三件，也是<b>唯一</b>会按名字删资源的那条路
     * （{@code docker rm -f} / {@code volume rm} / {@code network rm}），
     * 所以它必须真跑一次：判据错了就会删到别人的东西。
     */
    @Test
    @DisplayName("收残局（真 docker）：compose 文件没了，打开项目要把容器/网络/卷按名字收掉")
    void collectsRealLeftoversWhenTheProjectIsOpened() throws IOException {
        requireDocker();
        requireImages("busybox:latest", "redis:7-alpine");
        declare("""
                image: "busybox:latest"
                workdir: "/work"
                dependencies:
                  cache:
                    image: "redis:7-alpine"
                    healthcheck: "redis-cli ping"
                """);
        TestEnvironment environment = TestEnvironment.of(root);
        String project = environment.composeProject();
        createdProjects.add(project);
        environment.up();

        List<String> containers = namesOf(project);
        List<String> volumes = volumesOf(project);
        assertThat(containers).as("起完之后名下确实有容器").isNotEmpty();
        assertThat(volumes).as("依赖带 VOLUME，所以卷是真的存在（匿名卷也在）").isNotEmpty();

        // 把「把手」毁掉：模拟上一轮崩在收尾上——容器还在，但没人管它们了
        deleteDirectory(ComposeFile.rootOf(root));
        TestEnvironment reopened = TestEnvironment.of(root);

        EnvRegistration before = reopened.leftovers();
        assertThat(before.size()).as("残留自检要看得见这些东西：%s", before.summarize())
                .isGreaterThanOrEqualTo(containers.size());

        EnvRegistration cleaned = reopened.cleanupLeftovers();

        assertThat(cleaned.detail()).as("收的结果：%s", cleaned.detail()).contains("收掉了");
        assertThat(namesOf(project)).as("容器一个不剩").isEmpty();
        assertThat(volumesOf(project)).as("卷也删掉了（不清的话下一轮拿到的不是干净数据）").isEmpty();
        assertThat(networksOf(project)).as("网络也收掉了").isEmpty();
        assertThat(reopened.leftovers().size()).as("自检：零残留").isZero();
        assertNothingLeft(project);
    }

    @Test
    @DisplayName("别人的东西一件都不碰：只认自己项目名下的资源")
    void neverTouchesOtherProjects() throws IOException {
        requireDocker();
        declare("""
                image: "busybox:latest"
                """);
        TestEnvironment environment = TestEnvironment.of(root);

        // 用户自己的容器在这个项目名下？当然不是——列出来的必须先是「名字像这个项目」的。
        // 这里用真实 daemon 的数据验：整台机器上属于本项目的资源就是零
        EnvRegistration leftovers = environment.leftovers();

        assertThat(leftovers.composeProject()).startsWith("sf-");
        assertThat(leftovers.containers())
                .as("这台机器上跑着用户自己的容器，但一件都不该被认成我们的")
                .isEmpty();
        assertThat(leftovers.volumes()).isEmpty();
        assertThat(leftovers.networks()).isEmpty();
    }

    // ---------- 4. 引擎那一侧的收场（环境问题 → 立刻停 + 保留现场 + 原始错误） ----------

    /**
     * 环境问题走到引擎那一侧：状态是 {@code NEEDS_ENVIRONMENT}、<b>改动不回滚</b>、
     * 原始错误原样交给人——而不是回喂给模型让它改代码。
     *
     * <p>这次用的是一次<b>真的 reset 失败</b>：环境先正常起来（真容器），
     * 然后往 {@code env.yaml} 里塞一条一定失败的重置命令——引擎每次跑测试前都要 reset，
     * 那一句会带着 docker 的退出码原样冒上来。
     *
     * <p><b>「不改动磁盘」是用户 2026-10-02 拍板的那一条</b>：硬判据只停下，产品改动与测试产物
     * 都留着进「待处置」。旧口径在这里会把 {@code Foo.java} 恢复原样，于是人手里什么都没剩。
     */
    @Test
    @DisplayName("真环境问题走到引擎：状态是「需要环境」、改动未回滚、docker 的原始错误在结论里")
    void surfacesARealEnvironmentProblemThroughTheAgent() throws IOException {
        requireDocker();
        requireImages("busybox:latest");
        declare("""
                image: "busybox:latest"
                workdir: "/work"
                """);
        TestEnvironment environment = TestEnvironment.of(root);
        createdProjects.add(environment.composeProject());
        environment.up();

        // 环境能用了，但重置命令是坏的：引擎那一步会撞上它
        declare("""
                image: "busybox:latest"
                workdir: "/work"
                reset:
                  - "echo reset-exploded >&2; exit 7"
                """);
        Files.writeString(root.resolve("Foo.java"), "class Foo { int a = 1; }\n");

        com.specflow.agent.AgentResult result = new com.specflow.agent.DevelopmentAgent(
                root, com.specflow.project.ProjectConfig.DEFAULT,
                com.specflow.template.TemplateRegistry.empty(),
                new ScriptedPatches(), List.of(),
                com.specflow.agent.AgentListener.NOOP,
                new com.specflow.tests.TestSettings(true), environment)
                .run(spec(), planWithCases());

        assertThat(result.status())
                .as("结论：%s", result.detail())
                .isEqualTo(com.specflow.agent.AgentResult.Status.NEEDS_ENVIRONMENT);
        assertThat(result.detail())
                .contains("环境起不来").contains("reset-exploded")
                .as("硬判据只停下：这句话要说清改动还在、等谁处置")
                .contains("改动未回滚").contains("等你处置");
        assertThat(Files.readString(root.resolve("Foo.java")))
                .as("改动留在磁盘上等人处置——不再被机器收掉")
                .isEqualTo("class Foo { int a = 2; }\n");
        assertThat(namesOf(environment.composeProject()))
                .as("重置失败不收环境（容器是好的，坏的只是那条命令）")
                .isNotEmpty();
    }

    /**
     * 没初始化就勾集成：<b>引擎自己把它起起来</b>（预热），不再当场拦人。
     *
     * <p>旧行为是整次运行被拒（界面上 409），用户得先去点「初始化测试环境」、等半分钟，
     * 再回来点运行。现在开发一开跑就异步起环境（十五.5 的那套 up -d --wait + init），
     * 轮到测试时它多半已经好了——而这是一台真 docker 上唯一能证明「它真起来了」的地方。
     */
    @Test
    @DisplayName("没初始化就勾集成（真 docker）：引擎先把它起起来，不再拦人")
    void prewarmsTheEnvironmentInsteadOfRefusing() throws IOException {
        requireDocker();
        requireImages("busybox:latest");
        declare("""
                image: "busybox:latest"
                """);
        Files.writeString(root.resolve("Foo.java"), "class Foo { int a = 1; }\n");
        TestEnvironment environment = TestEnvironment.of(root);
        createdProjects.add(environment.composeProject());

        com.specflow.agent.AgentResult result = new com.specflow.agent.DevelopmentAgent(
                root, com.specflow.project.ProjectConfig.DEFAULT,
                com.specflow.template.TemplateRegistry.empty(),
                new ScriptedPatches(), List.of(),
                com.specflow.agent.AgentListener.NOOP,
                new com.specflow.tests.TestSettings(true), environment)
                .run(spec(), planWithCases());

        assertThat(environment.status().usable())
                .as("预热真的把容器起起来了：%s", namesOf(environment.composeProject())).isTrue();
        assertThat(result.status())
                .as("不再按「需要环境」停下：%s", result.detail())
                .isNotEqualTo(com.specflow.agent.AgentResult.Status.NEEDS_ENVIRONMENT);
        // 假模型给的是产品补丁、没有测试产物：测试阶段会在「产物只能写在 tools/ 下」那道闸上被拒，
        // 而那是正常结果——产品改动照旧留在磁盘上等人处置（这一档不回滚）
        assertThat(Files.readString(root.resolve("Foo.java")))
                .as("产品改动留着：环境这一摊的事不改动它的归宿")
                .isEqualTo("class Foo { int a = 2; }\n");
    }

    // ---------- 2c. 收场与清库（真容器） ----------

    /**
     * 收场（十五.8）在真容器上走一遍：<b>删产物、清数据、容器留着</b>。
     *
     * <p>三条断言各有一个只有真 docker 能给的答案：
     * <ul>
     *   <li>{@code reset} 真的在容器里跑了——它写的 marker 出现在宿主的项目目录里（说明挂载真的通）；</li>
     *   <li><b>容器还在</b>：收场不是关环境（十五.5：容器常驻复用）。写坏这一个的代价是
     *       「每接受一次就重建一次容器」，用户看到的是每次跑测试都要重新等镜像；</li>
     *   <li>收场之后自检零残留——收场没顺手留下一堆没人管的东西。</li>
     * </ul>
     */
    @Test
    @DisplayName("收场（真 docker）：reset 在容器里真的跑了、产物删了、容器留着复用")
    void settlesOnARealContainer() throws IOException {
        requireDocker();
        requireImages("busybox:latest");
        declare("""
                image: "busybox:latest"
                workdir: "/work"
                reset:
                  - "echo reset-ran > /work/reset.marker"
                """);
        TestEnvironment environment = TestEnvironment.of(root);
        String project = environment.composeProject();
        createdProjects.add(project);
        environment.up();

        // 一次「跑完了、产物还在、改动等着人处置」的运行
        Path artifacts = root.resolve("tools/20260930-120000");
        Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("run.cmd"), "echo PASS\n");
        Files.writeString(root.resolve("Foo.java"), "class Foo { int a = 1; }\n");
        WorkspaceSnapshot.capture(new SafePathResolver(root),
                root.resolve(SnapshotConfig.DEFAULT_DIR), List.of(root.resolve("Foo.java")))
                .markPending();
        new RunStore(root.resolve(RunStore.DEFAULT_DIR)).save(recordWith(artifacts));

        String before = containerOf(project, "app");
        RunService runs = new RunService(root, ProjectConfig.DEFAULT,
                root.resolve(".specflow/templates"), environment);
        Teardown.Done done = runs.accept();
        runs.shutdown();

        assertThat(done.settled()).isTrue();
        assertThat(read("reset.marker")).as("清环境数据真的进了容器跑（文件出现在挂载出来的目录里）")
                .isEqualTo("reset-ran");
        assertThat(artifacts).as("产物删掉、记录留下").doesNotExist();
        assertThat(containerOf(project, "app")).as("容器留着复用：还是同一个，没有被重建")
                .isEqualTo(before);
        assertThat(stateOf(before)).isEqualTo("running");
        assertThat(new RunStore(root.resolve(RunStore.DEFAULT_DIR))
                .load(new RunStore(root.resolve(RunStore.DEFAULT_DIR)).latestId())
                .settlement().summarize())
                .as("留档里写下这次是怎么收的场").contains("已接受");
        assertThat(environment.status().leftovers()).as("收场不制造残留").isZero();
        // 这个测试要的正是「容器还留着」，所以最后由它自己真收一次，再自检零残留：
        // 一条没人认领的容器不该在这台机器上过夜（上面那几条断言都看过了它还在）
        assertNothingLeftAfterDown(environment, project);
    }

    /**
     * 清库那种正当命令（{@code rm -rf /data/*}）在真容器里跑得通，而且<b>只碰容器自己的东西</b>。
     *
     * <p>这一条是「受控例外」的实测：闸门放行它，前提是它碰不到挂载进来的项目目录——
     * 而这件事只能用真容器证明（假 runner 只会说「命令我收到了」）。
     * 证据有两头：容器里的 {@code /data} 真的空了，宿主上的 {@code Keep.java} 还在。
     */
    @Test
    @DisplayName("清库命令（真 docker）：rm -rf /data/* 放行、清掉容器里的数据、项目目录一个字节没动")
    void clearsContainerDataWithoutTouchingTheProject() throws IOException {
        requireDocker();
        requireImages("busybox:latest");
        declare("""
                image: "busybox:latest"
                workdir: "/work"
                init:
                  - "mkdir -p /data && echo seeded > /data/seed.txt"
                reset:
                  - "rm -rf /data/*"
                """);
        TestEnvironment environment = TestEnvironment.of(root);
        String project = environment.composeProject();
        createdProjects.add(project);

        EnvRegistration up = environment.up();

        assertThat(up.state()).as("这条 reset 被闸门放行了（受控例外）")
                .isEqualTo(EnvRegistration.State.READY);
        assertThat(exec(environment, "cat /data/seed.txt")).as("init 造出来的数据在容器里")
                .contains("seeded");
        Files.writeString(root.resolve("Keep.java"), "class Keep {}\n");

        environment.reset();

        assertThat(exec(environment, "ls /data")).as("容器里的数据被清掉了").doesNotContain("seed.txt");
        assertThat(root.resolve("Keep.java")).as("挂载进来的项目目录一个字节都没动").exists();
        assertThat(environment.activeComposeFile()).as("重置不重建容器：把手还在").exists();
        assertThat(namesOf(project)).as("容器也还在（reset 不是 down）").isNotEmpty();
        assertNothingLeftAfterDown(environment, project);
    }

    /** 收尾：真收一次环境，再自检零残留。 */
    private void assertNothingLeftAfterDown(TestEnvironment environment, String project) {
        environment.down(true);
        assertThat(environment.status().leftovers()).isZero();
        assertNothingLeft(project);
    }

    /** 在常驻容器里跑一条命令（引擎那套参数：{@code compose exec -T app sh -c}）。 */
    private String exec(TestEnvironment environment, String command) {
        List<String> full = new ArrayList<>(List.of("docker", "compose", "-p",
                environment.composeProject(), "-f", environment.activeComposeFile().toString(),
                "exec", "-T", "app", "sh", "-c", command));
        CommandRunner.Result result = docker.run(full, Map.of(), root, 60);
        assertThat(result.exit()).as("容器里这条命令要成功：%s", result.output()).isZero();
        return result.output();
    }

    /** 一条「跑过测试、留了产物」的运行记录，给收场用。 */
    private RunRecord recordWith(Path artifacts) {
        return new RunRecord("20260930-120000", "2026-09-30T12:00", "TESTS_FAILED", null, "做点什么",
                List.of(), List.of(), null, List.of("Foo.java"), 1, "一条没过", List.of(),
                List.of(), List.of(), List.of(), null, List.of(),
                new TestOutcome(root.relativize(artifacts).toString().replace('\\', '/'),
                        List.of(), 1, 1,
                        VerificationResult.failed("测试脚本", "run", "一条没过"),
                        List.of(), List.of(new TestOutcome.CaseResult(1, false)), List.of()),
                // 覆盖核对、回喂、「它没有改动」：这一条只关心收场怎么删产物，三笔都留空。
                // 最后那一栏是「谁什么时候停用过哪几条」，同样空着
                null, null, null, null, List.of(), null, null, null, null);
    }

    // ---------- 辅助：真 docker 查询 ----------

    /** 没装 Docker / daemon 没起时整类跳过（这台机器上有，所以它真的会跑）。 */
    private void requireDocker() {
        CommandRunner.Result result = docker.run(List.of("docker", "version"), Map.of(), root, 30);
        assumeTrue(result.ok(), "本机没有可用的 Docker，跳过真容器验证");
    }

    /** 镜像不在本机时跳过，而不是自己去网上拉（这台机器上两个都在）。 */
    private void requireImages(String... images) {
        for (String image : images) {
            CommandRunner.Result result = docker.run(List.of("docker", "image", "inspect", image),
                    Map.of(), root, 30);
            assumeTrue(result.ok(), "本机没有镜像 " + image + "，跳过真容器验证（不拉新镜像）");
        }
    }

    private List<String> namesOf(String project) {
        return lines(List.of("docker", "ps", "-a",
                "--filter", "label=com.docker.compose.project=" + project,
                "--format", "{{.Names}}"));
    }

    /**
     * 这个项目名下的卷：标签查得到的，加上<b>容器身上挂着的</b>。
     *
     * <p>匿名卷（镜像自带的 {@code VOLUME}，比如 redis 的 {@code /data}）<b>没有</b>
     * compose 的项目标签——真 docker 实测过。自检要是只看标签，就看不见它们，
     * 而「看不见」正是它上一轮真的漏掉两个卷的原因。
     */
    private List<String> volumesOf(String project) {
        List<String> all = new ArrayList<>(lines(List.of("docker", "volume", "ls",
                "--filter", "label=com.docker.compose.project=" + project,
                "--format", "{{.Name}}")));
        List<String> containers = namesOf(project);
        if (!containers.isEmpty()) {
            List<String> command = new ArrayList<>(List.of("docker", "inspect",
                    "--format", "{{json .Mounts}}"));
            command.addAll(containers);
            for (String line : lines(command)) {
                TestEnvironment.volumeNamesIn(line).forEach(name -> {
                    if (!all.contains(name)) {
                        all.add(name);
                    }
                });
            }
        }
        return List.copyOf(all);
    }

    private List<String> networksOf(String project) {
        return lines(List.of("docker", "network", "ls",
                "--filter", "label=com.docker.compose.project=" + project,
                "--format", "{{.Name}}"));
    }

    private List<String> lines(List<String> command) {
        CommandRunner.Result result = docker.run(command, Map.of(), root, 60);
        if (!result.ok()) {
            throw new IllegalStateException("docker 查询失败：" + String.join(" ", command)
                    + System.lineSeparator() + result.output());
        }
        return result.output().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /** 这个项目里某个服务的容器名。 */
    private String containerOf(String project, String service) {
        String suffix = "-" + service + "-1";
        return namesOf(project).stream().filter(name -> name.endsWith(suffix)).findFirst()
                .orElse(null);
    }

    private String healthOf(String container) {
        return inspect(container, "{{.State.Health.Status}}");
    }

    private String stateOf(String container) {
        return inspect(container, "{{.State.Status}}");
    }

    private String inspect(String container, String format) {
        CommandRunner.Result result = docker.run(
                List.of("docker", "inspect", container, "--format", format), Map.of(), root, 60);
        return result.ok() ? result.output().strip() : "(inspect 失败：" + result.firstLine() + ")";
    }

    /**
     * 自检：这个项目名下（容器/卷/网络）一件都不剩。
     *
     * <p>它和 {@code leftovers()} 不同：那个只在「没有 compose 文件」时才报残留
     * （那是它的判据），而这里是**无论如何都不许留东西**的兜底检查。
     */
    private void assertNothingLeft(String project) {
        assertThat(containersAllOf(project)).as("容器残留").isEmpty();
        assertThat(volumesOf(project)).as("卷残留").isEmpty();
        assertThat(networksOf(project)).as("网络残留").isEmpty();
    }

    /** 含已退出的容器（{@code ps -a}）：退出的那些同样是残留。 */
    private List<String> containersAllOf(String project) {
        return namesOf(project);
    }

    /** 兜底清理：万一某条路没走完，按名字把它删掉（只删自己项目名下的）。 */
    private void removeByName(String project) {
        List<String> containers = containersAllOf(project);
        if (!containers.isEmpty()) {
            docker.run(concat(List.of("docker", "rm", "-f"), containers), Map.of(), root, 120);
        }
        List<String> volumes = volumesOf(project);
        if (!volumes.isEmpty()) {
            docker.run(concat(List.of("docker", "volume", "rm", "-f"), volumes), Map.of(), root, 120);
        }
        List<String> networks = networksOf(project);
        if (!networks.isEmpty()) {
            docker.run(concat(List.of("docker", "network", "rm"), networks), Map.of(), root, 120);
        }
    }

    private static List<String> concat(List<String> head, List<String> tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(tail);
        return all;
    }

    private String read(String name) throws IOException {
        return Files.readString(root.resolve(name), StandardCharsets.UTF_8).strip();
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void declare(String source) throws IOException {
        Path file = root.resolve(EnvConfigLoader.relativePath());
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    // ---------- 辅助：一个最小的假模型（只回一个补丁 + 一次施工单） ----------

    private static com.specflow.spec.Spec spec() {
        return com.specflow.TestSpecs.spec(List.of("Foo.java"));
    }

    private static com.specflow.review.PlanReview planWithCases() {
        return com.specflow.review.PlanReview.of("做点事", "flowchart TD\n    A-->B", List.of(),
                List.of(new com.specflow.review.PlanStep(1, "改一处", List.of("Foo.java"), "能编译", false)),
                List.of(new com.specflow.review.PlanReview.TestCase(1, "a 变成 2", "读 Foo.java",
                        com.specflow.review.PlanReview.TestCase.Level.MUST, "a == 2", "无")));
    }

    /**
     * 假模型：回一个改 Foo.java 的补丁。
     *
     * <p>它必须让开发那一轮跑成功、一路走到测试阶段——因为要验的正是「测试阶段发现环境起不来」
     * 这一条收场路径（{@code NEEDS_ENVIRONMENT} + 回滚）。它一次模型调用都不花。
     */
    private static final class ScriptedPatches implements com.specflow.llm.LlmClient {

        @Override
        public String complete(List<com.specflow.llm.ChatMessage> messages) {
            return "<<<<<<< SEARCH Foo.java\nint a = 1;\n=======\nint a = 2;\n>>>>>>> REPLACE\n";
        }
    }
}
