package com.specflow.web;

import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.env.FakeCommandRunner;
import com.specflow.env.TestEnvironment;
import com.specflow.history.RunRecord;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.project.LlmConfig;
import com.specflow.project.ProjectConfig;
import com.specflow.project.SnapshotConfig;
import com.specflow.review.PlanStep;
import com.specflow.review.ReviewOutcome;
import com.specflow.review.StepAudit;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.tests.EntryScripts;
import com.specflow.tests.Teardown;
import com.specflow.tests.TestAgent;
import com.specflow.tests.TestOutcome;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「停止」这条链路上，只有服务这一环没有别的地方管：界面把请求发到
 * {@code /api/cancel}，路由调 {@link RunService#cancel()}，引擎每轮开头问
 * {@link RunService#cancelled()}。
 *
 * <p>引擎那一侧（问到真就回滚停下）由 {@code DevelopmentAgentTest} 覆盖，
 * 路由那一侧（没有任务时 409、方法不对 405）由 {@code WebServerTest} 覆盖。
 * 这里只钉住中间这一环——「请求过停止之后，引擎问到的就是真」，
 * 否则界面上的按钮点了等于没点，而两边各自的测试都还是绿的。
 */
@DisplayName("停止运行")
class RunServiceTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("没点过停止时，引擎问到的是假")
    void notCancelledByDefault() {
        RunService service = service();

        assertThat(service.cancelled()).isFalse();

        service.shutdown();
    }

    @Test
    @DisplayName("点过停止之后，引擎问到的就是真")
    void cancelAsksTheAgentToStop() {
        RunService service = service();

        service.cancel();

        assertThat(service.cancelled()).isTrue();

        service.shutdown();
    }

    /**
     * 步级事件是界面这批新拿到的东西，形状在这里钉住：
     * 施工单一次给全（含来源与额外花的调用），每一步的开始/结束是一条带 {@code step} 的日志。
     * 界面靠 {@code step} 把一段乱序的日志按步分组，也靠它做「某一步的 diff」。
     */
    @Test
    @DisplayName("施工单一次推全，后面每条日志都带着它属于第几步")
    void pushesPlanAndStepScopedEvents() {
        RunService service = service();
        PlanStep first = new PlanStep(1, "先加接口", List.of("Foo.java"), "能编译", true);
        PlanStep second = new PlanStep(2, "接上实现", List.of("Bar.java"), "能编译", false);

        service.stepsResolved(List.of(first, second), AgentListener.StepsSource.GENERATED, 1);
        service.stepStarted(first);
        service.roundStarted(1);
        service.stepFinished(first, AgentListener.StepState.INTERMEDIATE);
        service.stepStarted(second);
        service.stepFinished(second, AgentListener.StepState.SUCCESS);

        List<RunEvent> events = service.hub().view(0).events();
        assertThat(events).hasSize(6);

        RunEvent plan = events.get(0);
        assertThat(plan.type()).isEqualTo(RunEvent.TYPE_PLAN);
        assertThat(plan.step()).isZero();
        assertThat(plan.text()).contains("现生成的施工单").contains("共 2 步").contains("1 次模型调用");
        assertThat(plan.payload()).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) plan.payload();
        assertThat(payload.get("source")).isEqualTo("GENERATED");
        assertThat(payload.get("probeCalls")).isEqualTo(1);
        assertThat(payload.get("steps")).isEqualTo(List.of(first, second));

        assertThat(events).filteredOn(event -> event.type().equals(RunEvent.TYPE_LOG))
                .extracting(RunEvent::step).containsExactly(1, 1, 1, 2, 2);
        assertThat(events.get(1).text()).as("第 1 步的日志挂在第 1 步上").contains("第 1 步").contains("先加接口");
        assertThat(events.get(2).text()).contains("第 1 轮");
        assertThat(events.get(3).level()).as("中间态要显眼：此刻磁盘上的代码是坏的").isEqualTo("warn");
        assertThat(events.get(5).level()).isEqualTo("info");
        // 步态是结构化字段：步开始/结束各推一档，轮级那条不推（它不改变某一步的状态）
        assertThat(events).filteredOn(event -> event.stepState() != null)
                .extracting(RunEvent::stepState)
                .containsExactly(RunEvent.STEP_RUNNING, "INTERMEDIATE", RunEvent.STEP_RUNNING, "SUCCESS");
        assertThat(events.get(2).stepState()).as("「第 1 轮：调用模型…」不是步态").isNull();

        service.shutdown();
    }

    /**
     * 步态由引擎在事件里说清楚，界面不再拿中文文案去猜。
     *
     * <p>这一条盯的是「上游真的发了这个字段」：只改界面、上游不发，
     * 每一步都会停在「未做」——那看起来像界面没画，其实是没人告诉它。
     * 三步都要在：开始（进行中）、回滚去重试（又回到进行中）、结束（终态枚举名）。
     */
    @Test
    @DisplayName("步级事件的步态：开始是进行中，回滚后回到进行中，结束给终态枚举名")
    void pushesStructuredStepState() {
        RunService service = service();
        PlanStep first = new PlanStep(1, "先加接口", List.of("Foo.java"), "能编译", false);
        PlanStep second = new PlanStep(2, "接上实现", List.of("Bar.java"), "能编译", false);

        service.stepStarted(first);
        service.stepRestored(first, 1, "校验未通过");
        service.stepFinished(first, AgentListener.StepState.SUCCESS);
        service.stepStarted(second);
        service.stepFinished(second, AgentListener.StepState.FAILED);

        assertThat(service.hub().view(0).events()).extracting(RunEvent::stepState)
                .containsExactly(RunEvent.STEP_RUNNING, RunEvent.STEP_RUNNING, "SUCCESS",
                        RunEvent.STEP_RUNNING, "FAILED");

        service.shutdown();
    }

    // ---------- 处置时的清理（十五.8） ----------

    /**
     * 接受/中断都要把这一轮的<b>测试产物删掉</b>（十五.8）。
     *
     * <p>为什么必须删：那几份脚本是照着<b>这一轮改动</b>写的，而改动刚被人接受或撤回了。
     * 留着它们只让 {@code tools/} 一次比一次长——留档里已经把「验了什么、哪条没过、
     * 为什么」都记下了，产物本身没有第二次用处。
     *
     * <p>判据用留档里记的那个路径，所以这里先造一条运行记录 + 一个待处置的快照，
     * 走的是真接口（{@code accept()}）。
     */
    @Test
    @DisplayName("接受之后：留档里记的那份测试产物被删掉")
    void acceptClearsTestArtifacts() throws Exception {
        Path artifacts = root.resolve("tools/20260930-120000");
        Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("run.cmd"), "echo PASS\n");
        Files.writeString(root.resolve("Foo.java"), "old");
        markPendingSnapshot(root.resolve("Foo.java"));
        recordTestRun(artifacts);

        RunService service = service();
        service.accept();
        service.shutdown();

        assertThat(artifacts).as("产物是照着这一轮改动写的，而改动已经处置完了").doesNotExist();
    }

    @Test
    @DisplayName("撤回之后同样删掉测试产物（两条路的收尾是同一件事）")
    void rollbackClearsTestArtifacts() throws Exception {
        Path artifacts = root.resolve("tools/20260930-121500");
        Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("run.cmd"), "echo PASS\n");
        Files.writeString(root.resolve("Foo.java"), "old");
        markPendingSnapshot(root.resolve("Foo.java"));
        recordTestRun(artifacts);

        RunService service = service();
        service.rollback();
        service.shutdown();

        assertThat(artifacts).doesNotExist();
    }

    /**
     * 留档里那个路径不在 {@code tools/} 下：一个字节都不许动。
     *
     * <p>这条路径来自磁盘上的 JSON（用户可能手工改过，工具也可能被改坏），
     * 而删东西这件事只允许发生在产物目录里。
     */
    @Test
    @DisplayName("留档里的路径越界时：不删（这条路径是磁盘上的字符串，不能全信）")
    void refusesToDeleteOutsideTheArtifactsRoot() throws Exception {
        Path target = root.resolve("src/main/java/com/demo");
        Files.createDirectories(target);
        Files.writeString(target.resolve("Foo.java"), "class Foo {}\n");
        Files.writeString(root.resolve("Foo.java"), "old");
        markPendingSnapshot(root.resolve("Foo.java"));
        recordTestRun(target);

        RunService service = service();
        service.accept();
        service.shutdown();

        assertThat(target.resolve("Foo.java")).as("越界的路径不删").exists();
    }

    /**
     * 接受要走完整条收场（十五.8），而且要把「带着几条失败接受的」写进留档。
     *
     * <p>这一栏是这套工具最基本的诚实：失败清单还在记录里，但「人是<b>知道它红着</b>还接受了」
     * 只有它说得出来——过几天再翻，「带着一条红的被接受」和「全绿」在记录里长得一样。
     */
    @Test
    @DisplayName("接受之后：留档里写下「接受」与当时带着的那几条失败用例")
    void acceptRecordsWhatTheUserChose() throws Exception {
        Path artifacts = root.resolve("tools/20260930-122000");
        Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("run.cmd"), "echo FAIL\n");
        Files.writeString(root.resolve("Foo.java"), "old");
        markPendingSnapshot(root.resolve("Foo.java"));
        recordFailingRun(artifacts);

        RunService service = service();
        Teardown.Done done = service.accept();
        service.shutdown();

        assertThat(done.settled()).isTrue();
        assertThat(done.summarize()).contains("已接受").contains("测试产物");
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecord.Settlement settlement = store.load(store.latestId()).settlement();
        assertThat(settlement.choice()).isEqualTo(RunRecord.Settlement.ACCEPT);
        assertThat(settlement.failing()).containsExactly(2);
        assertThat(settlement.summarize()).contains("带着 1 条失败用例").contains("用例 2");
        assertThat(artifacts).doesNotExist();
    }

    /**
     * 收场只清数据，<b>不动容器</b>（十五.5：容器常驻复用）。
     *
     * <p>这条判据很容易被「顺手收干净」写坏：收场那一段里同时有 reset 和 down 两个方法，
     * 而调错一个的代价是「每接受一次就重建一次容器」——用户看到的是每次跑测试都要重新等镜像。
     */
    @Test
    @DisplayName("收场不动容器：环境活着只 reset 数据（down 一次都不许调）")
    void settlementNeverTearsTheEnvironmentDown() throws Exception {
        Files.createDirectories(root.resolve(".specflow"));
        Files.writeString(root.resolve(".specflow/env.yaml"), """
                image: "x:1"
                workdir: "/work"
                reset:
                  - "rm -rf /data/*"
                """);
        FakeCommandRunner docker = new FakeCommandRunner()
                .ok("version", "fake docker").ok("ps -a", "")
                .ok("volume ls", "").ok("network ls", "").ok("down", "")
                .ok("up -d --wait", "").ok("exec -T app", "");
        TestEnvironment environment = new TestEnvironment(root, docker);
        environment.up();
        Files.writeString(root.resolve("Foo.java"), "old");
        markPendingSnapshot(root.resolve("Foo.java"));
        Path artifacts = root.resolve("tools/20260930-123000");
        Files.createDirectories(artifacts);
        recordFailingRun(artifacts);

        RunService service = new RunService(root, ProjectConfig.DEFAULT,
                root.resolve(".specflow/templates"), environment);
        Teardown.Done done = service.rollback();
        service.shutdown();

        assertThat(done.reset()).as("环境活着：数据重置过").isTrue();
        assertThat(docker.ran("exec -T app sh -c rm -rf /data/*")).isTrue();
        assertThat(docker.ran("down"))
                .as("只有「环境坏了 / 关项目 / 用户手动」才 down -v，收场不是那个时候").isFalse();
    }

    /** 造一条「跑过测试、第 1 条过了、第 2 条没过」的运行记录。 */
    private void recordFailingRun(Path artifacts) {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        TestOutcome tests = new TestOutcome(root.relativize(artifacts).toString().replace('\\', '/'),
                List.of(), 1, 1, VerificationResult.failed("测试脚本", "run", "一条没过"),
                List.of(), List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, false)), List.of());
        AgentListener recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);
        ((RunRecorder) recorder).testsFinished(tests);
        recorder.finished(AgentResult.testsFailed(1, List.of(), List.of(), "一条没过"));
    }

    /**
     * 造一条<b>可以拿来回喂</b>的记录：带用例清单、带失败清单、带目标文件。
     *
     * <p>三样缺一不可：回喂那一段要「用例的语义描述」（清单里有）、「期望 vs 实际」
     * （失败清单里有）、「涉及的目标文件」（run 级 targets）。少了任何一样，
     * 喂回给开发的那几句话就会缺一栏，而它看起来照样像一份证据。
     */
    private void recordRefeedableRun() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        List<com.specflow.review.PlanReview.TestCase> cases = List.of(
                new com.specflow.review.PlanReview.TestCase(1, "a 变成 2", "读 Foo.java 里的 a",
                        com.specflow.review.PlanReview.TestCase.Level.MUST, "a == 2", "无"),
                new com.specflow.review.PlanReview.TestCase(2, "加完之后项目还能编译", "跑一次编译",
                        com.specflow.review.PlanReview.TestCase.Level.SHOULD, "编译通过", "无"));
        TestOutcome tests = new TestOutcome("tools/20260930-120000", List.of(), 1, 1,
                VerificationResult.failed("测试脚本", "run", "一条没过"),
                List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION, "2",
                        "compiled", "not compiled", "code is wrong")),
                List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, false)), List.of());
        AgentListener recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                com.specflow.review.PlanReview.of("做点事", "", List.of(), List.of(), cases),
                AgentListener.NOOP);
        ((RunRecorder) recorder).testsFinished(tests);
        recorder.finished(AgentResult.testsFailed(1, List.of(), List.of(), "一条没过"));
    }

    /** 造一条「跑过测试」的运行记录，产物目录按参数给。 */
    private void recordTestRun(Path artifacts) {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        TestOutcome tests = new TestOutcome(root.relativize(artifacts).toString().replace('\\', '/'),
                List.of(), 1, 0, VerificationResult.passed("测试脚本", "run", "PASS"),
                List.of(), List.of(), List.of());
        AgentListener recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);
        ((RunRecorder) recorder).testsFinished(tests);
        recorder.finished(AgentResult.unverified(1, List.of(), List.of(), "没配编译命令"));
    }
    private void markPendingSnapshot(Path file) {
        WorkspaceSnapshot.capture(new SafePathResolver(root),
                        root.resolve(SnapshotConfig.DEFAULT_DIR), List.of(file))
                .markPending();
    }

    private RunService service() {
        return new RunService(root, ProjectConfig.DEFAULT, root.resolve(".specflow/templates"));
    }

    /**
     * 回喂这条链（十五.6 第一条路）在服务这一环上是不是接上了。
     *
     * <p>界面只发编号，内容由引擎从上一轮那条留档里拼——所以这条测试盯的是三件事：
     * ①编号真的被解出来了；②拼出来的那一段<b>真的进了提示词</b>（不是留在某个字段里）；
     * ③它排在<b>最后</b>（需求 → 施工单 → 这段）。
     * 只测 `Refeed` 那一层的话，「解出来了但没发给模型」这种断线照样是绿的——
     * 而它的表现是「用户点了下一轮，开发什么都没收到」。
     */
    @Test
    @DisplayName("回喂：编号进来 → 从上一轮留档里拼出那一段 → 进提示词的最后")
    void feedsThePreviousFailuresBackIntoTheNextRound() throws Exception {
        Files.writeString(root.resolve("Foo.java"), "old\n");
        recordRefeedableRun();
        try (StubModelServer model = StubModelServer.answering(
                // 开工前那两次「现生成施工单」的探测（桩不认识 STEPS 块，于是引擎退化成单步）
                "这个需求我拆不开。",
                "这个需求我拆不开。",
                // 开发那一轮：把 Foo.java 改掉（这一条只关心它的提示词里有什么）。
                // 后面测试阶段那次调用会撞上「脚本已用尽」的 500——那不影响这一条要验的东西
                "<<<<<<< SEARCH Foo.java\nold\n=======\nnew\n>>>>>>> REPLACE\n")) {
            Files.createDirectories(root.resolve(".specflow"));
            Files.writeString(root.resolve(".specflow").resolve("local.env"),
                    "SPECFLOW_TEST_KEY=sk-test\n");
            ProjectConfig project = new ProjectConfig(null,
                    new LlmConfig(model.baseUrl(), "stub", "SPECFLOW_TEST_KEY", 5, 0.0, 0),
                    // 快照那一段必须有：开跑前要问一次「上一次的改动处置了没有」，
                    // 而 ProjectConfig.DEFAULT 里没有它——走真运行的那几条用例都得给一份
                    SnapshotConfig.DEFAULT);
            RunService service = new RunService(root, project, root.resolve(".specflow/templates"));

            RunRequest request = RunRequest.of(null, "把 a 改成 2", null, null, null,
                    List.of("Foo.java"), null, null, null, null, 0, 1, null, List.of(2));
            service.start(request);
            awaitIdle(service);
            service.shutdown();

            // 开发那一轮是**第一次**非「只产施工单」的调用（前面那几次是开工前现生成施工单的探测），
            // 而回喂那一段就在它这一条用户消息里
            int development = -1;
            for (int index = 0; index < model.calls(); index++) {
                if (!model.askedForStepsOnly(index)) {
                    development = index;
                    break;
                }
            }
            assertThat(development).as("开发那一轮调用过（一共 %s 次调用）", model.calls())
                    .isNotNegative();
            String user = model.userOf(development);
            assertThat(user).as("回喂那一段进了提示词：%s", user)
                    .contains("## 上一轮的测试失败")
                    .contains("用例 2「加完之后项目还能编译」")
                    .contains("期望 compiled")
                    .contains("实际 not compiled")
                    .contains("Foo.java");
            assertThat(user).as("它在需求与施工单之后").contains("## 需求");
            assertThat(user.indexOf("## 需求"))
                    .isLessThan(user.indexOf("## 上一轮的测试失败"));
            assertThat(user).as("不给测试代码与断言源码").doesNotContain("SEARCH Foo.java");
        }
    }

    /** 上一轮压根没跑过测试：回喂无从下手，当场说清，别开一轮什么都喂不进去的运行。 */
    @Test
    @DisplayName("回喂：上一轮没有测试结论时当场拒，说的清是为什么")
    void refusesRefeedWithoutAPreviousTestRun() throws Exception {
        Files.writeString(root.resolve("Foo.java"), "old\n");
        RunService service = service();

        RunRequest request = RunRequest.of(null, "把 a 改成 2", null, null, null,
                List.of("Foo.java"), null, null, null, null, 0, 1, null, List.of(2));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.start(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有可回喂的失败清单");
        service.shutdown();
    }

    /** 等到这次运行收场（它的终态事件发出来为止）。 */
    private static void awaitIdle(RunService service) throws InterruptedException {
        for (int attempt = 0; attempt < 400 && service.hub().running(); attempt++) {
            Thread.sleep(25);
        }
        assertThat(service.hub().running()).as("这一次运行收场了").isFalse();
    }

    /**
     * 检查阶段那条链——模型响应 → 解析 → 两块机器校验 → {@link ReviewOutcome}。
     *
     * <p>起一个假模型服务把整条链走通，而不是只单测 {@link StepAudit}：
     * 光测那一层的话，「核了但结果没接进返回体」这种断线照样是绿的。
     */
    @Test
    @DisplayName("检查的返回体里带着施工单和它的机器校验结果")
    void reviewReturnsPlanAndStepAudit() throws Exception {
        String answer = """
                <<<<<<< SUMMARY
                分三步做。
                >>>>>>> SUMMARY

                <<<<<<< FLOW
                flowchart TD
                    A[入口] --> B[出口]
                >>>>>>> FLOW

                <<<<<<< STEPS
                1 | 先改一个清单外的类 | src/main/java/demo/Nope.java | 能编译 | 自洽
                2 | 再把 a 改成 2 | src/main/java/demo/Foo.java | 能编译 | 自洽
                3 | 最后收尾 | src/main/java/demo/Foo.java | 能编译 | 中间态
                >>>>>>> STEPS
                """;
        try (StubModelServer model = StubModelServer.answering(answer)) {
            Files.createDirectories(root.resolve(".specflow"));
            Files.writeString(root.resolve(".specflow").resolve("local.env"),
                    "SPECFLOW_TEST_KEY=sk-test\n");
            ProjectConfig project = new ProjectConfig(null,
                    new LlmConfig(model.baseUrl(), "stub", "SPECFLOW_TEST_KEY", 5, 0.0, 0),
                    null);
            RunService service = new RunService(root, project, root.resolve(".specflow/templates"));

            ReviewOutcome outcome = service.review(RunRequest.of(null, "加一个接口", null, null, null,
                    List.of("src/main/java/demo/Foo.java"), null, null, null, null, null, null,
                    null, List.of()));
            service.shutdown();

            assertThat(outcome.plan().steps()).as("施工单解析出来了").hasSize(3);
            assertThat(outcome.plan().steps().get(1).goal()).isEqualTo("再把 a 改成 2");
            // 两条硬拦：清单外的文件、最后一步标中间态
            assertThat(outcome.stepAudit().blocking()).isTrue();
            assertThat(outcome.stepAudit().findings()).hasSize(2);
            assertThat(outcome.stepAudit().findings()).extracting(StepAudit.Finding::step)
                    .containsExactlyInAnyOrder(1, 3);
            // 覆盖核对跟着检查结果一起回来：这一份需求没写验收标准，所以没什么可覆盖的，
            // 两个计数都是 0（界面据此决定画不画那两枚 chip）
            assertThat(outcome.coverage().criteria()).isEmpty();
            assertThat(outcome.coverage().uncoveredCount()).isZero();
            assertThat(outcome.coverage().unmappedMustCount()).isZero();
        }
    }

    /**
     * 「重新生成」那条路的收口：<b>编不过时那句话真的要回给界面</b>。
     *
     * <p>这一条接的是 {@code TestAgent.generate} 与 {@code /api/tests/regenerate} 之间那一跳。
     * 只测引擎那一层的话，「引擎算出来了、但响应体里没这一栏」这种断线照样是绿的——
     * 而它的表现正是这次要消灭的那件事：人点完「重新生成」收到一句「已重新生成」，
     * 手里却是一批跑不起来的代码。
     *
     * <p>答案里那份产物<b>带锚点</b>（否则会被溯源核对拦下、压根不会去跑），
     * 而脚本一条 {@code PASS} / {@code FAIL} 都不打——那就是「跑不出结论」，也就是编不过。
     */
    @Test
    @DisplayName("重新生成：编不过时把「它的代码编不过 + 原始错误」回给界面（真接口 + 假模型）")
    void regenerationReportsACompileProblem() throws Exception {
        Files.writeString(root.resolve("Foo.java"), "class Foo { int a = 1; }\n");
        // 三份同样的答案：跑不出结论会自己重试到上限（独立预算 3 版）
        String broken = "<<<<<<< SEARCH {{ENTRY}}\n=======\n"
                + EntryScripts.anchored(1, regenerateCases(), "error: cannot find symbol")
                + ">>>>>>> REPLACE\n";
        try (StubModelServer model = StubModelServer.answering(broken, broken, broken)) {
            Files.createDirectories(root.resolve(".specflow"));
            Files.writeString(root.resolve(".specflow").resolve("local.env"),
                    "SPECFLOW_TEST_KEY=sk-test\n");
            ProjectConfig project = new ProjectConfig(null,
                    new LlmConfig(model.baseUrl(), "stub", "SPECFLOW_TEST_KEY", 5, 0.0, 0),
                    null);
            RunService service = new RunService(root, project, root.resolve(".specflow/templates"));

            Map<String, Object> payload = service.regenerateTests(RunRequest.of(null, "把 a 改成 2",
                    null, null, null, List.of("Foo.java"), null, null, planWithCases(),
                    null, null, null, null, List.of()));
            service.shutdown();

            assertThat(model.calls()).as("三版都跑不出结论，就换了三版").isEqualTo(3);
            assertThat(payload.get("directory")).asString().startsWith("tools/");
            assertThat(payload.get("trace")).as("这一批接了线（不然会被拒绝运行，跑都跑不到）")
                    .isNotNull();
            TestAgent.CompileProblem problem = (TestAgent.CompileProblem) payload.get("problem");
            assertThat(problem).as("编不过这件事必须回给界面，否则人以为已经换好了").isNotNull();
            assertThat(problem.text())
                    .contains(TestOutcome.Failure.Kind.UNRUNNABLE.label())
                    .contains("cannot find symbol");
            assertThat(problem.output()).as("原始错误一个字节都不掐").contains("cannot find symbol");
        }
    }

    /** 「重新生成」那条链上那份用例清单：一条必须过的用例，锚点得对得上。 */
    private static List<com.specflow.review.PlanReview.TestCase> regenerateCases() {
        return List.of(new com.specflow.review.PlanReview.TestCase(1, "a 变成 2", "读 Foo.java 里的 a",
                com.specflow.review.PlanReview.TestCase.Level.MUST, "a == 2", "无"));
    }

    private static com.specflow.review.PlanReview planWithCases() {
        return com.specflow.review.PlanReview.of("做点事", "", List.of(), List.of(),
                regenerateCases());
    }
}
