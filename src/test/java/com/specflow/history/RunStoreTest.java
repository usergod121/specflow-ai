package com.specflow.history;

import com.specflow.TestSpecs;
import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.exception.PatchConflictException;
import com.specflow.exception.SpecflowException;
import com.specflow.patch.PatchApplier;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanStep;
import com.specflow.spec.ContextItem;
import com.specflow.spec.Spec;
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
                List.of(), null, List.of("Foo.java"), 1, detail, List.of(), List.of(), List.of(),
                null, List.of());
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
        assertThat(record.stepsSource()).isNull();
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

    private static PlanStep step(int index, String goal, boolean intermediate) {
        return new PlanStep(index, goal, List.of("Foo.java"), "能编译", intermediate);
    }

    private static PatchApplier.FileChange change(String path, String diff) {
        return new PatchApplier.FileChange(Path.of(path), path, false, 10, diff);
    }

    private void record(RunStore store, String detail) {
        RunRecorder recorder = RunRecorder.start(store, TestSpecs.spec(List.of("a.txt")),
                null, AgentListener.NOOP);
        recorder.finished(AgentResult.failed(1, List.of(), List.of(), detail));
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
