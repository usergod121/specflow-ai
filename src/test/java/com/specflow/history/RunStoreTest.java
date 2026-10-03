package com.specflow.history;

import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.env.EnvRegistration;
import com.specflow.exception.PatchConflictException;
import com.specflow.exception.SpecflowException;
import com.specflow.patch.PatchApplier;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanStep;
import com.specflow.spec.ContextItem;
import com.specflow.spec.Spec;
import com.specflow.tests.CaseTraceCheck;
import com.specflow.tests.TestOutcome;
import com.specflow.verify.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行留档的测试。
 *
 * <p>重点不是「文件写出来了」，而是<b>失败的那次也留下了完整的过程</b>——
 * 代码回滚之后，磁盘上看不出模型做了什么，那份信息只在记录里。
 */
@DisplayName("运行留档")
class RunStoreTest {

    @TempDir
    Path root;

    private final PlanReview plan = PlanReview.of("加一个方法", "flowchart TD\n    A-->B",
            List.of(new PlanReview.MissingItem("订单表结构",
                    PlanReview.MissingItem.Severity.BLOCKING, "要写 SQL", "查询会错", "粘贴建表语句")));

    @Test
    @DisplayName("挂起：最新那条是「等补充信息」才算挂着，接着跑出来的新记录会把它顶掉")
    void suspendedIsOnlyTheNewestRecord() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        store.save(record("20260101-000000-001", "NEEDS_CONTEXT", "NEED_CONTEXT: 我要 OrderMapper.java"));

        assertThat(store.suspended()).isPresent();
        assertThat(store.suspended().orElseThrow().detail()).contains("OrderMapper");
        assertThat(store.repeatedNeedsContext()).isEqualTo(1);

        store.save(record("20260101-000000-002", "NEEDS_CONTEXT", "NEED_CONTEXT: 还是缺"));
        assertThat(store.repeatedNeedsContext()).isEqualTo(2);

        // 接着跑成功之后，挂在等人就是过去式了——否则界面会一直劝你接着跑
        store.save(record("20260101-000000-003", "SUCCESS", "改动已落盘，校验通过"));
        assertThat(store.suspended()).isEmpty();
        assertThat(store.repeatedNeedsContext()).isZero();
    }

    @Test
    @DisplayName("没有记录时：没有挂起、连着说缺的次数是 0")
    void suspendedIsEmptyWithoutRecords() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        assertThat(store.suspended()).isEmpty();
        assertThat(store.repeatedNeedsContext()).isZero();
    }

    /** 直接写记录，不经过 {@code RunRecorder}：它的 id 是毫秒时间戳，同一个测试里连写几条会撞名。 */
    private static RunRecord record(String id, String status, String detail) {
        return new RunRecord(id, "2026-01-01T00:00", status, null, "改点东西", List.of(),
                List.of(), null, List.of("Foo.java"), 1,
                // 成本明细留空：这一档测的是「cost 为 null 时怎么读」（写出去的 JSON 里是
                // "cost": null）。磁盘上真实的老留档连这个键都没有，那一种形状由
                // readsLegacyRecordWithoutNewFields 钉着——两者是不同的读法，都要认
                null, detail, List.of(), List.of(), List.of(),
                List.of(), null, List.of(), null, null,
                // 判决、收场、重新生成过哪几份产物：都是跑完之后人写的那几笔，直接造时留空。
                // 覆盖核对、回喂、「它没有改动」这三笔同理：这一条不涉及测试阶段。
                // 最后那一栏是「谁什么时候停用过哪几条」：这一条里一条都没停用。
                // 再后面两栏是会话（这一条不属于任何会话）与跑完时刻（这一条不关心耗时）
                null, null, null, List.of(), null, null, null, null, null, null);
    }

    @Test
    @DisplayName("失败的一次运行同样留下完整过程：改了哪些文件、为什么停、缺什么")
    void keepsRecordForFailedRun() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        Spec spec = TestSpecs.builder().template("implement").targets(List.of("Foo.java")).build();
        RunRecorder recorder = RunRecorder.start(store, spec, plan, AgentListener.NOOP);

        // 一轮完整的过程：写盘 → 编译失败 → 回滚 → 放弃
        PatchApplier.FileChange written = new PatchApplier.FileChange(
                root.resolve("Foo.java"), "Foo.java", false, 120, "-int a = 1;\n+int a = 2;");
        recorder.roundStarted(1);
        recorder.filesApplied(1, List.of(written));
        recorder.verificationFinished(1, List.of(VerificationResult.failed("编译校验", "mvn", "找不到符号")));
        recorder.workspaceRestored(1, "校验未通过");
        // Agent 失败时会把最后一轮的改动一起带回来（已经回滚，但差异本身还在）
        recorder.finished(AgentResult.failed(1, List.of(written), List.of(), "校验未通过且重试次数已用尽"));

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();

        assertThat(record.status()).isEqualTo("FAILED");
        assertThat(record.template()).isEqualTo("implement");
        // 留档里最该有的两项：当时要它做什么、怎样算做完
        assertThat(record.prompt()).isEqualTo("改点东西");
        assertThat(record.targets()).containsExactly("Foo.java");
        assertThat(record.missing()).singleElement()
                .satisfies(item -> assertThat(item.what()).isEqualTo("订单表结构"));

        // 差异在回滚之前就算好了，所以磁盘还原之后它还在记录里
        assertThat(record.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("Foo.java");
            assertThat(change.diff()).contains("-int a = 1;").contains("+int a = 2;");
        });

        assertThat(record.timeline()).extracting(RunRecord.Line::text)
                .anySatisfy(text -> assertThat(text).contains("调用模型"))
                .anySatisfy(text -> assertThat(text).contains("编译校验：未通过"))
                .anySatisfy(text -> assertThat(text).contains("已回滚"));
    }

    @Test
    @DisplayName("留档里带着当时的验收标准——它和需求一样是资产")
    void keepsAcceptance() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        Spec spec = TestSpecs.builder()
                .prompt("给订单加一个状态字段")
                .acceptance(List.of("老数据默认值不为 null", "不改动查询逻辑"))
                .build();
        RunRecorder recorder = RunRecorder.start(store, spec, null, AgentListener.NOOP);

        recorder.finished(AgentResult.failed(1, List.of(), List.of(), "结束"));

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();
        assertThat(record.prompt()).isEqualTo("给订单加一个状态字段");
        assertThat(record.acceptance()).containsExactly("老数据默认值不为 null", "不改动查询逻辑");
    }

    @Test
    @DisplayName("留档里带着这次的上下文依赖——「它当时依据什么」和需求一样是资产")
    void keepsContext() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        Spec spec = TestSpecs.builder()
                .context(List.of(
                        ContextItem.of("UserController.java", "README.md", null, "照它的风格写"),
                        ContextItem.of("订单表结构", null, "CREATE TABLE orders (id BIGINT)", "库里的定义")))
                .build();
        RunRecorder recorder = RunRecorder.start(store, spec, null, AgentListener.NOOP);

        recorder.finished(AgentResult.failed(1, List.of(), List.of(), "结束"));

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();
        assertThat(record.context()).hasSize(2);
        assertThat(record.context().get(0).ref()).isEqualTo("README.md");
        assertThat(record.context().get(0).note()).isEqualTo("照它的风格写");
        assertThat(record.context().get(1).text()).contains("CREATE TABLE orders");
    }

    /**
     * 内联上下文常常是整段建表语句、几千字，原样存进去会让每次运行都留下一份几 MB 的
     * 记录，而历史列表要把它读出来列在界面上。截断之后仍要能认出「是哪一段」，
     * 所以留下总字数。
     */
    @Test
    @DisplayName("超长的内联上下文被截成 200 字，并注明原文共多少字")
    void truncatesOverlongContextText() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        Spec spec = TestSpecs.builder()
                .context(List.of(ContextItem.of("订单表结构", null, "甲".repeat(350), "")))
                .build();
        RunRecorder recorder = RunRecorder.start(store, spec, null, AgentListener.NOOP);

        recorder.finished(AgentResult.failed(1, List.of(), List.of(), "结束"));

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();
        assertThat(record.context()).singleElement().satisfies(item ->
                assertThat(item.text()).isEqualTo("甲".repeat(200) + "…（共 350 字）"));
    }

    @Test
    @DisplayName("刚好 200 字的上下文原样保留，不加字数说明")
    void keepsTextAtTheLimit() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        Spec spec = TestSpecs.builder()
                .context(List.of(ContextItem.of("订单表结构", null, "乙".repeat(200), "")))
                .build();
        RunRecorder recorder = RunRecorder.start(store, spec, null, AgentListener.NOOP);

        recorder.finished(AgentResult.failed(1, List.of(), List.of(), "结束"));

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();
        assertThat(record.context()).singleElement()
                .satisfies(item -> assertThat(item.text()).isEqualTo("乙".repeat(200)));
    }

    /**
     * 加 {@code context} 字段之前写下的记录里没有这一项。读不出来会怎样：
     * {@code RunStore.read} 把异常吃掉返回空 → <b>那些历史直接从列表里消失</b>，
     * 用户只会觉得「怎么少了几条」，没有任何提示。
     */
    @Test
    @DisplayName("没有 context 字段的老记录仍然读得出来")
    void readsLegacyRecordWithoutContext() throws IOException {
        Path dir = root.resolve(RunStore.DEFAULT_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("20260102-000000-000.json"), """
                {
                  "id" : "20260102-000000-000",
                  "startedAt" : "2026-01-02 00:00:00",
                  "status" : "SUCCESS",
                  "template" : "implement",
                  "prompt" : "老需求",
                  "acceptance" : [ "能跑起来" ],
                  "requirementId" : "REQ-1",
                  "targets" : [ "Foo.java" ],
                  "attempts" : 1,
                  "detail" : "结束"
                }
                """);
        RunStore store = new RunStore(dir);

        assertThat(store.list()).as("老记录不能消失").hasSize(1);
        RunRecord record = store.load("20260102-000000-000");
        assertThat(record.prompt()).isEqualTo("老需求");
        assertThat(record.acceptance()).containsExactly("能跑起来");
        assertThat(record.requirementId()).isEqualTo("REQ-1");
        // 缺字段就是一个 null，不是读取失败
        assertThat(record.context()).isNull();
    }

    @Test
    @DisplayName("按时间倒序列出，最近的一次在最前")
    void listsNewestFirst() throws InterruptedException {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        record(store, "第一次");
        Thread.sleep(5);
        record(store, "第二次");

        assertThat(store.list()).extracting(RunRecord.Summary::detail)
                .containsExactly("第二次", "第一次");
    }

    @Test
    @DisplayName("损坏的记录单条跳过，不让整份历史都打不开")
    void skipsBrokenRecord() throws IOException {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        record(store, "正常的");
        Files.writeString(root.resolve(RunStore.DEFAULT_DIR).resolve("broken.json"), "{ 这不是 json");

        assertThat(store.list()).hasSize(1);
    }

    @Test
    @DisplayName("按 id 精读时，文件不存在会明确报错而不是返回空")
    void loadMissingRecordFails() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        assertThatThrownBy(() -> store.load("20260101-000000-000"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("找不到运行记录");
    }

    @Test
    @DisplayName("id 里塞路径穿越时被挡住，不会读到项目外的文件")
    void rejectsPathTraversalInId() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        assertThatThrownBy(() -> store.load("../../etc/passwd"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("找不到运行记录");
    }

    @Test
    @DisplayName("目录还不存在时列表为空，而不是报错")
    void emptyWhenDirectoryMissing() {
        assertThat(new RunStore(root.resolve("nope")).list()).isEmpty();
    }

    /**
     * 老记录里那一条写的是 {@code why} / {@code howToSupply}（三段格式），
     * 而现在的字段是「严重度 / 技术影响 / 业务影响 / 默认值」。
     * 认不出来会怎样：{@code RunStore.read} 把异常吃掉返回空 → <b>那几条历史直接消失</b>，
     * 用户只会觉得「怎么少了几条」，没有任何提示。
     */
    @Test
    @DisplayName("老格式的运行记录仍然读得出来，不会从历史里消失")
    void readsLegacyMissingItems() throws IOException {
        Path dir = root.resolve(RunStore.DEFAULT_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("20260101-000000-000.json"), """
                {
                  "id" : "20260101-000000-000",
                  "startedAt" : "2026-01-01 00:00:00",
                  "status" : "FAILED",
                  "prompt" : "老需求",
                  "targets" : [ "Foo.java" ],
                  "attempts" : 1,
                  "detail" : "结束",
                  "missing" : [ {
                    "what" : "订单表结构",
                    "why" : "要写 SQL",
                    "howToSupply" : "粘贴建表语句"
                  } ]
                }
                """);
        RunStore store = new RunStore(dir);

        assertThat(store.list()).as("老记录不能消失").hasSize(1);
        RunRecord record = store.load("20260101-000000-000");
        assertThat(record.missing()).singleElement().satisfies(item -> {
            assertThat(item.what()).isEqualTo("订单表结构");
            // 老记录里的「为什么需要它」对上新字段的「技术影响」，不能整条丢掉
            assertThat(item.impact()).isEqualTo("要写 SQL");
            assertThat(item.fallback()).isEqualTo("粘贴建表语句");
            assertThat(item.severity()).as("老记录没有严重度，标成未标")
                    .isEqualTo(PlanReview.MissingItem.Severity.UNKNOWN);
        });
    }

    @Test
    @DisplayName("统计「它标的阻断最后真的阻断了吗」：全从已有记录里算")
    void countsBlockingOutcomes() throws InterruptedException {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        // 记录 id 是毫秒时间戳，同一毫秒里建的两条会互相覆盖成一个文件。
        // 生产里撞不上（一次运行要跑好几秒，而且同一时刻只允许一次），测试里会——所以每条之间隔开一点。
        // 一次：报了阻断，最后失败 → 这一条断得对
        blockedRun(store, "第一次", AgentResult.failed(1, List.of(), List.of(), "没做出来"),
                PlanReview.MissingItem.Severity.BLOCKING);
        Thread.sleep(5);
        // 一次：报了阻断，最后成功 → 这一条多半报重了
        blockedRun(store, "第二次", AgentResult.success(1, List.of(), List.of()),
                PlanReview.MissingItem.Severity.BLOCKING);
        Thread.sleep(5);
        // 一次：只报了「影响质量」，不该算进阻断那一栏
        blockedRun(store, "第三次", AgentResult.failed(1, List.of(), List.of(), "没做出来"),
                PlanReview.MissingItem.Severity.QUALITY);

        RunStore.MissingStats stats = store.missingStats();

        assertThat(stats.runs()).isEqualTo(3);
        assertThat(stats.items()).isEqualTo(3);
        assertThat(stats.blockingItems()).isEqualTo(2);
        assertThat(stats.runsWithBlocking()).isEqualTo(2);
        assertThat(stats.runsWithBlockingSucceeded()).as("两次报了阻断，其中一次还是成功了").isEqualTo(1);
    }

    @Test
    @DisplayName("一次运行都没跑过时统计是全零，而不是报错")
    void statsAreZeroWithoutRuns() {
        RunStore.MissingStats stats = new RunStore(root.resolve("nope")).missingStats();

        assertThat(stats.runs()).isZero();
        assertThat(stats.items()).isZero();
        assertThat(stats.runsWithBlocking()).isZero();
    }

    @Test
    @DisplayName("录制器把每个回调原样转发给下游")
    void forwardsEveryCallback() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RecordingDelegate delegate = new RecordingDelegate();
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("a.txt")),
                null, delegate);

        recorder.roundStarted(1);
        recorder.planRejected(1, new PatchConflictException(
                PatchConflictException.Kind.ANCHOR_NOT_FOUND, "没找到"));
        recorder.verificationFinished(1, List.of(VerificationResult.passed("编译校验", "mvn", "")));
        recorder.workspaceRestored(1, "校验未通过");
        recorder.finished(AgentResult.failed(1, List.of(), List.of(), "结束"));

        assertThat(delegate.events).containsExactly("round", "rejected", "verified", "restored", "finished");
    }

    // ---------- 按步留档 ----------

    @Test
    @DisplayName("按步记：每步的目标、状态、用了几轮、改了哪些文件都写进记录里")
    void recordsSteps() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);

        recorder.stepsResolved(List.of(step(1, "先加接口", true), step(2, "接上实现", false)),
                AgentListener.StepsSource.GENERATED, 1);
        recorder.stepStarted(step(1, "先加接口", true));
        recorder.roundStarted(1);
        recorder.filesApplied(1, List.of(change("Foo.java", "+interface")));
        recorder.verificationFinished(1, List.of(VerificationResult.failed("编译校验", "mvn", "编不过")));
        recorder.stepFinished(step(1, "先加接口", true), AgentListener.StepState.INTERMEDIATE);
        recorder.stepStarted(step(2, "接上实现", false));
        recorder.roundStarted(2);
        recorder.filesApplied(2, List.of(change("Bar.java", "+impl")));
        recorder.verificationFinished(2, List.of(VerificationResult.passed("编译校验", "mvn", "")));
        recorder.stepFinished(step(2, "接上实现", false), AgentListener.StepState.SUCCESS);
        recorder.finished(AgentResult.success(2, List.of(), List.of()));

        RunRecord record = store.load(store.list().get(0).id());

        assertThat(record.stepsSource()).isEqualTo("GENERATED");
        assertThat(record.steps()).hasSize(2);
        assertThat(record.steps().get(0)).satisfies(first -> {
            assertThat(first.index()).isEqualTo(1);
            assertThat(first.goal()).isEqualTo("先加接口");
            assertThat(first.intermediate()).isTrue();
            assertThat(first.state()).isEqualTo("INTERMEDIATE");
            assertThat(first.rounds()).isEqualTo(1);
            assertThat(first.changes()).singleElement()
                    .satisfies(change -> assertThat(change.path()).isEqualTo("Foo.java"));
        });
        assertThat(record.steps().get(1)).satisfies(second -> {
            assertThat(second.state()).isEqualTo("SUCCESS");
            assertThat(second.rounds()).isEqualTo(1);
            assertThat(second.changes()).singleElement()
                    .satisfies(change -> assertThat(change.path()).isEqualTo("Bar.java"));
        });
        // 生成施工单多花的那一次调用要留在时间线上：它不算轮次，但一样是钱
        assertThat(record.timeline()).extracting(RunRecord.Line::text)
                .anySatisfy(text -> assertThat(text).contains("多花了 1 次模型调用"));
    }

    /**
     * 留档里要有一份<b>完整的</b>施工单，而且连「涉及哪些文件、怎么算做完」一起。
     *
     * <p>续跑就靠它：挂起时磁盘已经回滚，其它地方都没有这份单子了，
     * 而每步的施工指令要从这两栏拼出来。只记 {@code steps} 是不够的——
     * 那是跑完之后的账（只有跑到了的步子，而且没有 files/check 这两栏）。
     */
    @Test
    @DisplayName("留档里存着完整施工单：跑挂之前没轮到的步子也在，files/check 两栏一个不少")
    void recordsTheWholeSchedule() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);

        recorder.stepsResolved(List.of(step(1, "先加接口", false), step(2, "接上实现", false),
                        step(3, "收尾", false)),
                AgentListener.StepsSource.GENERATED, 1);
        recorder.stepStarted(step(1, "先加接口", false));
        recorder.roundStarted(1);
        recorder.stepFinished(step(1, "先加接口", false), AgentListener.StepState.SUCCESS);
        // 第 2 步就挂起等人补料：第 3 步从来没轮到过
        recorder.finished(AgentResult.needsContext(1, "NEED_CONTEXT: 缺东西"));

        RunRecord record = store.load(store.list().get(0).id());

        assertThat(record.steps()).as("跑完的账里只有跑到过的那一步").hasSize(1);
        assertThat(record.planSteps()).as("施工单要整份留着").hasSize(3);
        assertThat(record.planSteps().get(2).goal()).isEqualTo("收尾");
        assertThat(record.planSteps().get(0).files()).containsExactly("Foo.java");
        assertThat(record.planSteps().get(0).check()).isEqualTo("能编译");
    }

    @Test
    @DisplayName("本步回滚之后，那一步的改动不再算在它名下")
    void forgetsChangesOfARolledBackRound() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);

        recorder.stepStarted(step(1, "第一步", false));
        recorder.roundStarted(1);
        recorder.filesApplied(1, List.of(change("Foo.java", "+废掉的版本")));
        recorder.verificationFinished(1, List.of(VerificationResult.failed("编译校验", "mvn", "错")));
        recorder.stepRestored(step(1, "第一步", false), 1, "校验未通过");
        recorder.roundStarted(2);
        recorder.filesApplied(2, List.of(change("Foo.java", "+最终版本")));
        recorder.verificationFinished(2, List.of(VerificationResult.passed("编译校验", "mvn", "")));
        recorder.stepFinished(step(1, "第一步", false), AgentListener.StepState.SUCCESS);
        recorder.finished(AgentResult.success(2, List.of(), List.of()));

        RunRecord record = store.load(store.list().get(0).id());

        assertThat(record.steps()).singleElement().satisfies(only -> {
            assertThat(only.rounds()).as("两步各一轮").isEqualTo(2);
            assertThat(only.changes()).singleElement()
                    .satisfies(change -> assertThat(change.diff()).contains("最终版本"));
        });
    }

    /**
     * 加 {@code steps} 字段之前写下的记录里没有这一项。和 {@code context} 同样的道理：
     * 读不出来就是<b>静默跳过</b>，用户只会看到历史莫名少了几条。
     */
    @Test
    @DisplayName("没有 steps 字段的老记录仍然读得出来")
    void readsLegacyRecordWithoutSteps() throws IOException {
        Path dir = root.resolve(RunStore.DEFAULT_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("20260103-000000-000.json"), """
                {
                  "id" : "20260103-000000-000",
                  "startedAt" : "2026-01-03 00:00:00",
                  "status" : "SUCCESS",
                  "template" : "implement",
                  "prompt" : "老需求",
                  "targets" : [ "Foo.java" ],
                  "attempts" : 2,
                  "detail" : "结束"
                }
                """);
        RunStore store = new RunStore(dir);

        assertThat(store.list()).as("老记录不能消失").hasSize(1);
        RunRecord record = store.load("20260103-000000-000");
        assertThat(record.attempts()).isEqualTo(2);
        // 缺字段就是一个 null，不是读取失败
        assertThat(record.steps()).isNull();
        assertThat(record.planSteps()).as("老记录里也没有施工单：续跑时只能现生成").isNull();
        assertThat(record.stepsSource()).isNull();
    }

    /**
     * 用例级的账（{@code tests.cases}）是后加的：加它之前跑过的运行里那一项不存在。
     * 和 {@code context}、{@code steps} 同样的道理——读不出来就是静默跳过，
     * 用户只会看到「历史莫名少了几条」，而这次少掉的是带着失败清单的那一条。
     */
    @Test
    @DisplayName("没有 cases 字段的老记录仍然读得出来：失败清单照样在")
    void readsLegacyRecordWithoutCaseResults() throws IOException {
        Path dir = root.resolve(RunStore.DEFAULT_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("20260104-000000-000.json"), """
                {
                  "id" : "20260104-000000-000",
                  "startedAt" : "2026-01-04 00:00:00",
                  "status" : "TESTS_FAILED",
                  "template" : "implement",
                  "prompt" : "老需求",
                  "targets" : [ "Foo.java" ],
                  "attempts" : 1,
                  "detail" : "结束",
                  "tests" : {
                    "directory" : "tools/20260104-000000",
                    "files" : [ "tools/20260104-000000/run.cmd" ],
                    "calls" : 1,
                    "exit" : 1,
                    "verification" : {
                      "verifier" : "测试脚本",
                      "status" : "FAILED",
                      "command" : "run.cmd",
                      "output" : "FAIL | 1 | a | b | c",
                      "kind" : "NONE"
                    },
                    "failures" : [ {
                      "kind" : "ASSERTION",
                      "testCase" : "1",
                      "expected" : "a",
                      "actual" : "b",
                      "opinion" : "c"
                    } ]
                  }
                }
                """);
        RunStore store = new RunStore(dir);

        assertThat(store.list()).as("老记录不能消失").hasSize(1);
        RunRecord record = store.load("20260104-000000-000");
        assertThat(record.tests().failures()).singleElement()
                .satisfies(failure -> assertThat(failure.testCase()).isEqualTo("1"));
        assertThat(record.tests().cases()).as("缺字段就是一个空表，不是读取失败").isEmpty();
        assertThat(record.tests().links()).as("老记录没有溯源连线：空表，不是读取失败").isEmpty();
    }

    /**
     * 单步执行（没有施工单）也要留下<b>一条</b>步级记录。
     *
     * <p>引擎在这种运行里不发步级事件，于是留档里一步都没有：历史详情里看不到
     * 「这一步做了什么、几轮、改了哪些文件」，而分步看得到——同一件事两种说法，
     * 事后翻记录的人只能靠猜。这里补的那一条，步子来自引擎报的施工单，
     * 轮次与改动直接来自运行结果。
     */
    @Test
    @DisplayName("单步执行也留一条步级记录：目标、状态、几轮、改了什么都照实记")
    void writesOneStepForSingleStepRuns() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);

        recorder.stepsResolved(List.of(step(1, "按需求把这件事一次做完", false)),
                AgentListener.StepsSource.SINGLE, 2);
        recorder.roundStarted(1);
        recorder.filesApplied(1, List.of(change("Foo.java", "+实现")));
        recorder.verificationFinished(1, List.of(VerificationResult.passed("编译校验", "mvn", "")));
        recorder.finished(AgentResult.success(1, List.of(change("Foo.java", "+实现")), List.of()));

        RunRecord record = store.load(store.list().get(0).id());

        assertThat(record.stepsSource()).isEqualTo("SINGLE");
        assertThat(record.steps()).as("新记录里这一项总是在的，老记录才没有").singleElement()
                .satisfies(only -> {
                    assertThat(only.index()).isEqualTo(1);
                    assertThat(only.goal()).isEqualTo("按需求把这件事一次做完");
                    assertThat(only.state()).isEqualTo("SUCCESS");
                    assertThat(only.rounds()).as("整次运行的轮次就是这一步的轮次").isEqualTo(1);
                    assertThat(only.changes()).singleElement()
                            .satisfies(change -> assertThat(change.path()).isEqualTo("Foo.java"));
                });
    }

    /**
     * 单步失败时那条记录也得照实：它没做成，所以步态是失败、轮次照记。
     *
     * <p>「失败」这一档同时盖住人工中断、模型说缺料、环境问题——它们对那一步的含义是同一个：
     * 这一步没做成。具体是哪种，同一条记录的时间线里写着。
     *
     * <p>改动照留：磁盘上已经回滚了，差异只有在留档里还看得见，
     * 而「它当时改了什么」正是事后最想知道的那件事（和整次运行那一栏的 changes 一个口径）。
     */
    @Test
    @DisplayName("单步失败（含被中断）时记成失败，改动照实留着而不是假装成功")
    void recordsFailedSingleStepRun() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);

        recorder.stepsResolved(List.of(step(1, "按需求把这件事一次做完", false)),
                AgentListener.StepsSource.SINGLE, 2);
        recorder.roundStarted(1);
        recorder.finished(AgentResult.cancelled(1, List.of(change("Foo.java", "+改了一半")),
                List.of()));

        RunRecord record = store.load(store.list().get(0).id());

        assertThat(record.steps()).singleElement().satisfies(only -> {
            assertThat(only.state()).isEqualTo("FAILED");
            assertThat(only.rounds()).isEqualTo(1);
            assertThat(only.changes()).singleElement()
                    .satisfies(change -> assertThat(change.diff()).contains("改了一半"));
            assertThat(only.changes()).isEqualTo(record.changes());
        });
    }

    /**
     * 一步都没跑的那种运行不补记录。
     *
     * <p>「上一次的改动还没处置」被拒、或者刚开工就被叫停：那一步压根没开始，
     * 记成「失败、0 轮」比空着更容易让人误判成「它做了一步然后失败了」。
     */
    @Test
    @DisplayName("一步都没跑时不补记录：那一步压根没开始")
    void writesNoStepWhenNothingRan() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);

        recorder.stepsResolved(List.of(step(1, "按需求把这件事一次做完", false)),
                AgentListener.StepsSource.SINGLE, 2);
        recorder.finished(AgentResult.cancelled(0, List.of(), List.of()));

        RunRecord record = store.load(store.list().get(0).id());

        assertThat(record.steps()).isEmpty();
    }

    /**
     * 「施工单过期」那次拒绝不留档，而且<b>不能</b>把挂着等人补料的那条挤下去。
     *
     * <p>判「有没有东西挂着」看的是最新那一条记录（{@code RunStore.suspended}），
     * 而拒绝开工时用户刚被告知「把文件加回清单再来一次」——那一次挂起的运行要是被顶掉，
     * 他连「接着跑」都点不了了。所以这一个状态在运行历史里永远见不到；
     * 界面那张历史表仍为它留了一行，是为了表与 {@code AgentResult.Status} 一项不差。
     */
    @Test
    @DisplayName("施工单过期的那次拒绝不留档：挂着等人补料的那条还在原位")
    void doesNotRecordTheRefusedPlanOutdatedRun() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        // 手工造一条「挂着等人补料」的记录：这样它的 id 与下面那个录制器的时间戳必然不同，
        // 「被顶掉」和「压根没写」才分得开
        store.save(record("20260101-000000-001", "NEEDS_CONTEXT",
                "NEED_CONTEXT: 我要 OrderMapper.java"));
        RunRecorder refused = RunRecorder.start(store, TestSpecs.spec(List.of("a.txt")),
                null, AgentListener.NOOP);

        refused.finished(AgentResult.planOutdated("上一次留下的施工单对不上现在的目标文件清单"));

        assertThat(store.list()).as("拒绝那一次不落档：没调模型、没碰磁盘，也没什么可复盘")
                .hasSize(1);
        assertThat(store.suspended()).as("挂着的那次还在，用户还能接着跑").isPresent();
    }

    /**
     * 没定过施工单的运行：留档里<b>没有</b> {@code planSteps} 这一项，与「老记录」同形。
     *
     * <p>写一个空数组的话，「没有施工单」就有了两种说法（缺这一项 / 空数组），
     * 读的那一侧（续跑）得多认一种；而它旁边那一栏 {@code stepsSource} 本来就是 null。
     */
    @Test
    @DisplayName("没定过施工单时留档里没有这一项：与老记录同形，而不是空数组")
    void writesNoPlanStepsWhenTheScheduleNeverArrived() throws IOException {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("a.txt")),
                null, AgentListener.NOOP);
        // 开工就被拦下：一次模型调用都没发生，施工单也就没定过
        recorder.finished(AgentResult.pendingDecision("上一次的改动还没处置"));

        RunRecord record = store.load(store.list().get(0).id());

        assertThat(record.planSteps()).isNull();
        assertThat(record.stepsSource()).as("两栏同形：没有就是没有").isNull();
        assertThat(Files.readString(store.directory().resolve(record.id() + ".json")))
                .as("落盘的那份里压根没有这一栏（写个 [] 就是同一件事的第二种说法）")
                .doesNotContain("planSteps");
    }

    private static PlanStep step(int index, String goal, boolean intermediate) {
        return new PlanStep(index, goal, List.of("Foo.java"), "能编译", intermediate);
    }

    // ---------- 测试环境与「已知失败」（十五.8） ----------

    /**
     * 测试环境这一次的登记要进留档：起了哪些容器、跑了哪几条命令、环境是好是坏。
     *
     * <p>事后翻记录的人要能回答「那次测试是在一个什么环境里跑的」——只留一句
     * 「测试没过」，他连当时连的是哪个库都不知道。清理也靠这一份（按登记逆序清）。
     */
    @Test
    @DisplayName("测试环境的登记进留档：容器、卷、目录、跑过的命令都在")
    void keepsTheEnvironmentRegistration() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("a.txt")),
                null, AgentListener.NOOP);

        recorder.environmentChanged(new EnvRegistration(EnvRegistration.State.READY, "docker",
                "sf-demo", ".specflow/env/20260930-120000/compose.yaml",
                List.of("app", "db"), List.of("sf-demo-app-1"), List.of("sf-demo_default"),
                List.of("sf-demo_data"), List.of(".specflow/env/20260930-120000"),
                List.of("python -m clean-db"), "环境已就绪"));
        recorder.finished(AgentResult.success(1, List.of(), List.of()));

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();

        assertThat(record.environment()).isNotNull();
        assertThat(record.environment().composeProject()).isEqualTo("sf-demo");
        assertThat(record.environment().containers()).containsExactly("sf-demo-app-1");
        assertThat(record.environment().volumes()).containsExactly("sf-demo_data");
        assertThat(record.environment().commands()).containsExactly("python -m clean-db");
        // 算出来的那一行也序列化出去（界面直接用它，不用自己拼）
        assertThat(record.environment().summarize()).contains("已就绪").contains("sf-demo-app-1");
        assertThat(record.timeline()).extracting(RunRecord.Line::text)
                .anySatisfy(text -> assertThat(text).contains("测试环境"));
    }

    /** 没声明环境、只跑单元测试的运行：留档里没有这一栏（而不是写个空壳）。 */
    @Test
    @DisplayName("没跑过环境：留档里没有这一栏")
    void omitsTheEnvironmentWhenThereIsNone() throws IOException {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        record(store, "什么都没跑");

        RunRecord record = store.list().stream().findFirst().map(RunRecord.Summary::id)
                .map(store::load).orElseThrow();

        assertThat(record.environment()).isNull();
        assertThat(Files.readString(store.directory().resolve(record.id() + ".json")))
                .doesNotContain("environment");
    }

    /**
     * 「这几条怎么判的」要落档：它是<b>人做的判断</b>，只留在界面上就是刷新一下就没了——
     * 而事后翻记录的人正是靠它解释「为什么那几条红的最后没被当成问题」。
     *
     * <p>三档（开发错了 / 测试代码错了 / 不重要）用的是同一栏：它们回答的是同一个问题
     * 「谁错了」，分成三栏就会出现「同一条用例在两个地方各有一个判断」。
     */
    @Test
    @DisplayName("人的判断写进留档：换一次读取还在，同一档不刷新原来的时间，改判就换一档")
    void recordsVerdicts() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        record(store, "测试没过");
        String id = store.latestId();

        RunRecord marked = store.judge(id, List.of(2, 1), RunRecord.Verdict.KNOWN);
        assertThat(marked.verdicts()).extracting(RunRecord.Verdict::index)
                .as("按编号排好，界面直接画").containsExactly(1, 2);
        String firstAt = marked.verdicts().get(0).at();
        assertThat(firstAt).isNotBlank();

        // 再标一次（界面会把完整的一份发回来）：已经在里面的保留原时间——
        // 那个时间记的是「哪一刻人的判断变了」，每次重标都刷掉就等于抹掉了最初那一刻
        RunRecord again = store.judge(id, List.of(1, 2, 3), RunRecord.Verdict.KNOWN);
        assertThat(again.verdicts()).extracting(RunRecord.Verdict::index)
                .containsExactly(1, 2, 3);
        assertThat(again.verdicts().get(0).at()).isEqualTo(firstAt);
        assertThat(again.verdicts().get(0).owner()).isEqualTo(RunRecord.Verdict.KNOWN);

        // 读回来还是同一份（落盘、不是只改内存）
        assertThat(store.load(id).verdicts()).hasSize(3);

        // 改判：同一条用例只有一个判断，新的那一档把它换掉（而不是两条并存）
        RunRecord changed = store.judge(id, List.of(2), RunRecord.Verdict.CODE);
        assertThat(changed.verdicts()).extracting(RunRecord.Verdict::owner)
                .contains(RunRecord.Verdict.KNOWN, RunRecord.Verdict.CODE);

        // 取消标记（发一份空的上来）之后这一档就该没有——而不是留一个空数组
        RunRecord cleared = store.judge(id, List.of(), RunRecord.Verdict.KNOWN);
        assertThat(cleared.verdicts()).extracting(RunRecord.Verdict::owner)
                .as("取消「不重要」不该顺手把「开发错了」也抹掉")
                .containsExactly(RunRecord.Verdict.CODE);
        assertThat(store.judge(id, List.of(), RunRecord.Verdict.CODE).verdicts()).isNull();
    }

    /**
     * 收场要落档（十五.8）：接受还是中断，以及当时<b>带着几条失败用例</b>。
     *
     * <p>为什么这一笔最要紧：失败清单只说明「当时红在哪几条上」，说明不了
     * 「人是知道它红着还接受了」。过几天再翻，「带着 2 条失败被接受」和「全绿」
     * 在记录里长得一模一样——而它们是两件完全不同的事。
     */
    @Test
    @DisplayName("收场写进留档：怎么收的场 + 当时带着哪几条失败用例")
    void recordsSettlement() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        record(store, "测试没过，改动留着等人处置");
        String id = store.latestId();

        RunRecord accepted = store.settle(id, RunRecord.Settlement.ACCEPT, List.of(1, 3));

        RunRecord.Settlement settlement = accepted.settlement();
        assertThat(settlement.choice()).isEqualTo(RunRecord.Settlement.ACCEPT);
        assertThat(settlement.at()).isNotBlank();
        assertThat(settlement.failing()).containsExactly(1, 3);
        assertThat(settlement.failures()).as("带着几条失败接受——界面与留档都用它").isEqualTo(2);
        // 这两条钉的是单次运行的口径（false = 不属于任何会话）
        assertThat(settlement.summarize(false)).contains("已接受").contains("2 条失败用例");
        assertThat(store.load(id).settlement().failing()).containsExactly(1, 3);

        // 中断走的是同一栏：两条收场路在记录里必须是同一件事的两种取值
        RunRecord interrupted = store.settle(id, RunRecord.Settlement.INTERRUPT, List.of(2));
        assertThat(interrupted.settlement().summarize(false)).contains("已中断");
        assertThat(interrupted.settlement().failing()).containsExactly(2);
    }

    /**
     * 重新生成的那份产物也要记在留档里。
     *
     * <p>不记的后果很具体：收场时会按留档删产物，漏掉的那一份就永远留在 {@code tools/} 里——
     * 而「上一次接受之后产物没了」是用户唯一能看见的、收场真的做完了的证据。
     */
    @Test
    @DisplayName("重新生成：判断（测试代码错了）与新产物目录一起落进留档")
    void recordsRegeneratedArtifacts() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        recordTestRun(store);
        String id = store.latestId();

        RunRecord first = store.regenerated(id, List.of(1, 2), "tools/20260930-130000");
        assertThat(first.regenerated()).containsExactly("tools/20260930-130000");
        assertThat(first.verdicts()).extracting(RunRecord.Verdict::owner)
                .containsOnly(RunRecord.Verdict.TEST);
        // 重建记录时溯源连线要原样带着：漏一个字段，历史详情里每个 chip 就都变成「未连线」
        assertThat(first.tests().links()).extracting(CaseTraceCheck.Link::line).containsExactly(137);

        // 同一个目录点两次「放行」不会记两笔；换一个目录就是多了一份要收的产物
        RunRecord twice = store.regenerated(id, List.of(1, 2), "tools/20260930-130000");
        assertThat(twice.regenerated()).hasSize(1);
        assertThat(store.regenerated(id, List.of(1, 2), "tools/20260930-131500").regenerated())
                .containsExactly("tools/20260930-130000", "tools/20260930-131500");
    }

    @Test
    @DisplayName("判一个不存在的记录：报错，而不是悄悄新建一条")
    void refusesToMarkAnUnknownRun() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        assertThatThrownBy(() -> store.judge("nope", List.of(1), RunRecord.Verdict.KNOWN))
                .isInstanceOf(SpecflowException.class);
    }

    @Test
    @DisplayName("最新那条记录的 id：一条都没有时是空串（界面据此说「先跑一次」）")
    void reportsTheLatestRecordId() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));

        assertThat(store.latestId()).isEmpty();
        record(store, "跑了一次");
        assertThat(store.latestId()).isEqualTo(store.list().get(0).id());
    }

    /**
     * 老记录里没有这几栏（测试环境登记、人的判断、收场、重新生成过的产物）：
     * 读出来必须是 {@code null}，而不是让整条记录读不出来。
     *
     * <p>读不出来是<b>静默跳过</b>的（见 {@code RunStore.read}），所以这一类问题的表现
     * 只是「历史里少了一条」——没有一条点名的断言，它可以在很久以后才被发现。
     */
    @Test
    @DisplayName("老记录没有环境登记与判决：读出来是 null，不是读失败")
    void readsLegacyRecordWithoutNewFields() throws IOException {
        Path dir = root.resolve(RunStore.DEFAULT_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("20260101-000000-000.json"), """
                {
                  "id": "20260101-000000-000",
                  "startedAt": "2026-01-01T00:00",
                  "status": "SUCCESS",
                  "prompt": "老需求",
                  "acceptance": [],
                  "targets": ["a.txt"],
                  "attempts": 1,
                  "detail": "老记录"
                }
                """);

        RunRecord record = new RunStore(dir).load("20260101-000000-000");

        assertThat(record.status()).isEqualTo("SUCCESS");
        assertThat(record.environment()).as("老记录里没有这一项").isNull();
        assertThat(record.verdicts()).as("老记录里也没有这一项").isNull();
        assertThat(record.settlement()).as("收场是这一批新加的").isNull();
        assertThat(record.regenerated()).isNull();
        // 成本明细（§19.13 新加的那一栏）同理：这份 JSON 里<b>压根没有</b> cost 这个键，
        // 缺的键必须被当成 null 交给记录的构造器。不认这个形状的后果不是「少一个数」，
        // 而是 `RunStore.read` 静默跳过整条记录——历史里少一条，这一栏一个字节都没留下
        assertThat(record.cost()).as("老记录的 JSON 里没有这一栏 → null，而不是读失败").isNull();
        assertThat(record.modelCalls()).as("退回 attempts：那是老留档里唯一记过的钱").isEqualTo(1);
    }

    /**
     * 「标记为已知失败」那一栏<b>改过名字</b>：它以前叫 {@code knownFailures}，
     * 而且每条只有 {@code index} 与 {@code at} 两栏（那时唯一存在的判断就是已知失败）。
     *
     * <p>必须认这个形状：老记录里那些记号是<b>人做过的判断</b>，读不出来是静默跳过，
     * 用户只会看到历史里的记号凭空消失——而这正是前一批踩过的那个坑
     * （缺失项那两个老字段名，不映射就整条记录读不出来）。
     */
    @Test
    @DisplayName("老记录里的 knownFailures：读成「不重要」那一档，而不是丢掉")
    void readsLegacyKnownFailuresAsKnownVerdicts() throws IOException {
        Path dir = root.resolve(RunStore.DEFAULT_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("20260102-000000-000.json"), """
                {
                  "id": "20260102-000000-000",
                  "startedAt": "2026-01-02T00:00",
                  "status": "TESTS_FAILED",
                  "prompt": "老需求",
                  "acceptance": [],
                  "targets": ["a.txt"],
                  "attempts": 1,
                  "detail": "那次带着两条失败被接受了",
                  "knownFailures": [
                    { "index": 2, "at": "2026-01-02T00:05" },
                    { "index": 3, "at": "2026-01-02T00:06" }
                  ]
                }
                """);

        RunRecord record = new RunStore(dir).load("20260102-000000-000");

        assertThat(record.verdicts()).extracting(RunRecord.Verdict::index).containsExactly(2, 3);
        assertThat(record.verdicts()).extracting(RunRecord.Verdict::owner)
                .as("老记录里没有 owner：那时唯一的判断就是「已知失败」")
                .containsOnly(RunRecord.Verdict.KNOWN);
        assertThat(record.verdicts().get(0).at()).as("原来的时间要保留").isEqualTo("2026-01-02T00:05");
        // 写回去之后是新名字，而且读得回来（老记录被就地升级，不会两边各说各的）
        RunStore store = new RunStore(dir);
        store.judge(record.id(), List.of(2, 3), RunRecord.Verdict.KNOWN);
        assertThat(Files.readString(dir.resolve(record.id() + ".json")))
                .contains("\"verdicts\"").doesNotContain("\"knownFailures\"");
        assertThat(store.load(record.id()).verdicts()).hasSize(2);
    }

    private static PatchApplier.FileChange change(String path, String diff) {
        return new PatchApplier.FileChange(Path.of(path), path, false, 10, diff);
    }

    /**
     * 一轮的成本要数<b>全</b>：开发轮次之外，还有现生成施工单、第二段补「怎么测」、生成测试代码
     * 三处调用——它们都不进轮次账，但都是用户掏的钱。
     *
     * <p>为什么值得钉住：「这一轮花了 N 次调用」以前只数开发轮次，实测里第 2 轮界面写着 1、
     * 实际是 3（开发 1 + 第二段 1 + 生成测试 1，§19.13）。数字小一半不会报错，
     * 只会让人以为这工具很省——而且没有任何地方能看出它错在哪，所以这里两项都要钉：
     * 四项明细各自是多少，以及加起来是不是那个总数。
     *
     * <p>{@code attempts}（轮次账）一个字都不许跟着变：界面上「第 N 轮」和它同一个口径。
     */
    @Test
    @DisplayName("这一轮的成本数得全：开发轮次之外，施工单、第二段、生成测试那几次也算")
    void countsEveryCallTheRoundSpent() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("Foo.java")),
                null, AgentListener.NOOP);

        // 开工前现生成施工单花了 2 次（第一次不合规、回喂之后再要了一次）；开发一轮；第二段补
        // 「怎么测」被期望对不上打回一次、共 2 次；测试代码生成 2 版（第一版一条结论都没跑出来）
        recorder.stepsResolved(List.of(step(1, "一次做完", false)),
                AgentListener.StepsSource.GENERATED, 2);
        recorder.roundStarted(1);
        recorder.filesApplied(1, List.of(change("Foo.java", "+实现")));
        recorder.verificationFinished(1, List.of(VerificationResult.passed("编译校验", "mvn", "")));
        recorder.casesRefined(List.of(), "第二段补上了 1 条用例的「怎么测」…为它花了 2 次模型调用。", 2);
        recorder.testsFinished(new TestOutcome("tools/20260930-120000",
                List.of("tools/20260930-120000/run.cmd"), 2, 0,
                VerificationResult.passed("测试脚本", "run", ""), List.of(),
                List.of(new TestOutcome.CaseResult(1, true)), List.of()));
        recorder.finished(AgentResult.success(1, List.of(change("Foo.java", "+实现")), List.of()));

        RunRecord record = store.load(store.list().get(0).id());

        assertThat(record.attempts()).as("轮次账不变：这一轮开发了一个轮次").isEqualTo(1);
        assertThat(record.cost()).as("四处各记各的：开发 / 施工单 / 第二段 / 生成测试")
                .isEqualTo(new RunRecord.Cost(1, 2, 2, 2));
        assertThat(record.modelCalls()).as("会话视图上「这一轮花了 N 次调用」的那个 N")
                .isEqualTo(7);
        assertThat(record.cost().total()).as("四项加起来就是总数，没有第五处悄悄漏在外面")
                .isEqualTo(record.modelCalls());
    }

    /**
     * 老记录里没有 {@code cost} 那一栏：不许按今天的口径替它编一个数，退回它唯一有的那个。
     *
     * <p>「编一个看起来更全的数」比少一个数糟得多：留档是唯一还答得出「当时花了多少」的地方，
     * 它一旦开始猜，之后就再也分不清哪几轮是真账。
     *
     * <p>这一条走的是 {@code save} 一圈，所以写出去的 JSON 里<b>有</b> {@code "cost": null}
     * 这一栏；磁盘上<b>真实的老留档</b>是另一种形状——<b>压根没有这个键</b>
     * （见 {@link #readsLegacyRecordWithoutNewFields}，那边钉着缺键也要读得出来）。
     * 两种形状都要认：前者是今天的代码写出来的，后者是历史文件。
     */
    @Test
    @DisplayName("老记录（cost 那栏是 null）退回 attempts：不替它编一个更全的数")
    void fallsBackToAttemptsForOldRecordsWithoutCost() {
        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        // record(...) 直接造记录，不经录制器：这一条要钉的是「cost 为 null 时怎么读」
        store.save(record("20260101-000000-001", "SUCCESS", "改动已落盘"));

        RunRecord old = store.load("20260101-000000-001");

        assertThat(old.cost()).isNull();
        assertThat(old.modelCalls()).as("退回开发轮次：那是这份留档里唯一记过的数").isEqualTo(1);
    }

    private void record(RunStore store, String detail) {
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("a.txt")),
                null, AgentListener.NOOP);
        recorder.finished(AgentResult.failed(1, List.of(), List.of(), detail));
    }

    /**
     * 造一次「跑过测试、带着两条失败」的运行。
     *
     * <p>收场与「重新生成」那两条都要按 {@code tests.cases} 算出「哪几条没过」，
     * 所以这两条测试用的记录必须真的带一份测试结论——不然验的是「空表也能写进去」，
     * 而真实场景里它总是有内容的。
     */
    private void recordTestRun(RunStore store) {
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("a.txt")),
                null, AgentListener.NOOP);
        recorder.testsFinished(new TestOutcome("tools/20260930-120000",
                List.of("tools/20260930-120000/run.cmd"), 1, 1,
                VerificationResult.failed("测试脚本", "run", "两条没过"),
                List.of(),
                // 三档都要有：过的、没过的、压根没跑到的（没跑到的也算没过）
                List.of(new TestOutcome.CaseResult(1, true),
                        new TestOutcome.CaseResult(2, false),
                        new TestOutcome.CaseResult(3, false)),
                // 溯源连线也过一遍留档的读写：读不回来，界面那一栏就没得画
                List.of(new CaseTraceCheck.Link(1, "tools/20260930-120000/UnitTests.java", 137))));
        recorder.finished(AgentResult.testsFailed(1, List.of(), List.of(), "两条没过"));
    }

    /** 跑一次带缺失项的运行，用它验统计口径。 */
    private void blockedRun(RunStore store, String prompt, AgentResult result,
                            PlanReview.MissingItem.Severity severity) {
        PlanReview review = PlanReview.of("方案", "flowchart TD\n    A-->B",
                List.of(new PlanReview.MissingItem("缺的东西", severity, "影响", "业务影响", "默认值")));
        RunRecorder recorder = RunRecorder.start(store,
                TestSpecs.builder().prompt(prompt).targets(List.of("a.txt")).build(), review,
                AgentListener.NOOP);
        recorder.finished(result);
    }

    private static final class RecordingDelegate implements AgentListener {

        private final List<String> events = new ArrayList<>();

        @Override
        public void roundStarted(int round) {
            events.add("round");
        }

        @Override
        public void planRejected(int round, PatchConflictException failure) {
            events.add("rejected");
        }

        @Override
        public void verificationFinished(int round, List<VerificationResult> results) {
            events.add("verified");
        }

        @Override
        public void workspaceRestored(int round, String reason) {
            events.add("restored");
        }

        @Override
        public void finished(AgentResult result) {
            events.add("finished");
        }
    }
}
