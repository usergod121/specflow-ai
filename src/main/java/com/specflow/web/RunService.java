package com.specflow.web;

import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.agent.DevelopmentAgent;
import com.specflow.agent.ProgressMessages;
import com.specflow.context.ContextAssembler;
import com.specflow.env.EnvConfigLoader;
import com.specflow.env.EnvRegistration;
import com.specflow.env.TestEnvironment;
import com.specflow.exception.PatchConflictException;
import com.specflow.exception.SpecValidationException;
import com.specflow.history.RunRecord;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.llm.LlmClient;
import com.specflow.llm.OpenAiCompatibleClient;
import com.specflow.patch.PatchApplier;
import com.specflow.project.ProjectConfig;
import com.specflow.review.PlanAudit;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanReviewer;
import com.specflow.review.PlanStep;
import com.specflow.review.ReviewOutcome;
import com.specflow.review.StepAudit;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.spec.Spec;
import com.specflow.spec.SpecValidator;
import com.specflow.template.TemplateRegistry;
import com.specflow.tests.ExecutionLocation;
import com.specflow.tests.TestAgent;
import com.specflow.tests.TestOutcome;
import com.specflow.tests.TestSettings;
import com.specflow.tests.Teardown;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.CompileVerifier;
import com.specflow.verify.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把一次界面请求变成一次 Agent 运行，并把过程翻译成界面能显示的事件。
 *
 * <p>它同时是 {@link AgentListener}：Agent 每走一步就回调一个方法，
 * 这里把回调翻译成人话（「第 2 轮：调用模型…」）推进 {@link RunHub}。
 * 之所以不直接转发日志，是因为日志里混着第三方的、与本任务无关的行，
 * 而界面需要的是干净的一条时间线。
 *
 * <p>运行放在独立线程上：模型调用要几秒到几十秒，HTTP 请求不能挂着等。
 * 代价是需要一个「一次只能跑一个」的约束——两个 Agent 同时改同一批文件，
 * 快照与回滚会互相踩，所以这里直接拒绝并发。
 */
public final class RunService implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final Path projectRoot;
    private final ProjectConfig project;
    private final Path templatesDir;
    private final RunStore store;
    private final RunHub hub = new RunHub();

    /**
     * 这个项目的测试环境（十五.5）。
     *
     * <p>它由 {@link OpenProject} 装配时建一次，整个「打开项目」期间共用：
     * 探测结果要缓存（每次问都起进程，界面一进来会问好几遍），而环境本身是<b>项目级</b>
     * 而不是运行级的东西——容器常驻复用，跨运行都活着。
     */
    private final TestEnvironment environment;

    /**
     * 有没有人按了「停止」。
     *
     * <p>由运行线程读、HTTP 线程写，所以是 {@code volatile}。
     *
     * <p>它拦不住正在飞的那次模型调用——引擎只在<b>轮与轮之间</b>问一次
     * （见 {@link DevelopmentAgent}），收到请求后的表现是「这一轮跑完就停」。
     * 让一个已经发出去的请求半路作废，只会留下一个说不清状态的连接，
     * 而真正的代价并不大：停下之前的所有改动都会被回滚。
     */
    private volatile boolean cancelRequested;

    /**
     * 当前在第几步（0 = 不属于某一步）。
     *
     * <p>由运行线程写、运行线程读：所有回调都发生在同一条运行线程上。
     * 它存在的意义是让界面能把一段乱序的日志按步分组——「这一步的落盘、编译、重试」
     * 本来散在好几条轮级事件里，界面自己猜不准该归到哪一步。
     */
    private int currentStep;

    /**
     * 这次运行的测试结论（没有测试阶段时是 {@code null}）。
     *
     * <p>为什么要在这里记一笔：终态事件（{@code result}）在收场那一刻才发，
     * 而测试结论是它<b>之前</b>就拿到手的。不留这一笔，界面就只能再跑一趟
     * {@code /api/run-detail} 才画得出失败清单——多一个来回，而且那一刻用户正盯着屏幕等结果。
     */
    private TestOutcome lastTests;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "specflow-run");
        thread.setDaemon(true);
        return thread;
    });

    public RunService(Path projectRoot, ProjectConfig project, Path templatesDir) {
        this(projectRoot, project, templatesDir, TestEnvironment.of(projectRoot));
    }

    /**
     * 带测试环境的那一版：测试要能换一个假的 docker 跑（这台机器上没有 Docker）。
     */
    public RunService(Path projectRoot, ProjectConfig project, Path templatesDir,
                      TestEnvironment environment) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.project = project;
        this.templatesDir = templatesDir;
        this.environment = environment;
        this.store = new RunStore(this.projectRoot.resolve(RunStore.DEFAULT_DIR));
    }

    /**
     * 关掉运行线程池。
     *
     * <p>换项目时旧的那个必须收掉：池子是单线程的，不收就一直挂着一个线程；
     * 而且它往里面写进度的那个 {@link RunHub} 已经不有人看了。
     */
    public void shutdown() {
        runner.shutdownNow();
    }

    /**
     * 每次用到时现读模板。
     *
     * <p>不在构造时读一次存起来：那样界面上改完模板，跑起来的还是旧的那份，
     * 用户只能靠重启服务来解决——而重读几个 yaml 的代价可以忽略。
     */
    private TemplateRegistry templates() {
        return TemplateRegistry.load(templatesDir);
    }

    public RunHub hub() {
        return hub;
    }

    /** 运行记录的读取入口，供界面翻历史。 */
    public RunStore history() {
        return store;
    }

    /**
     * 提交一次运行，立即返回，不等待执行完成。
     *
     * <p>所有能同步发现的问题都在这里抛出去（spec 不合法、没配密钥、已有任务在跑），
     * 这样界面能在点下按钮的瞬间就给出准确反馈，而不是等到轮询事件时才知道失败了。
     *
     * @return 本次运行的标识
     * @throws com.specflow.exception.SpecValidationException spec 不合法
     * @throws com.specflow.llm.LlmException                 模型密钥缺失
     * @throws IllegalStateException                         已有任务在运行
     */
    public synchronized String start(RunRequest request) {
        if (hub.running()) {
            throw new IllegalStateException("已有任务正在运行，请等它结束");
        }
        // 引擎自己也会拦（见 DevelopmentAgent）。这里先拦一遍是为了让界面在点下的
        // 瞬间就拿到 409，而不是等一个注定被拒的任务跑起来才知道
        if (waitingSnapshot() != null) {
            throw new IllegalStateException("上一次的改动还没处置：请先「保留改动」或「撤回改动」");
        }
        // 清掉上一次留下的停止请求。放在并发判断<b>之后</b>：
        // 这次提交被拒的时候，上一次运行可能正跑到一半，它的停止请求不该被顺手抹掉
        cancelRequested = false;
        // 上一次的测试结论同理：留着它，一次没跑测试的运行会顶着上一轮的失败清单收场
        lastTests = null;
        Spec spec = toValidSpec(request);
        // 环境这道闸排在模型配置之前：它是「这件事现在做不了」里最靠前的一条，
        // 而且不花钱。排在后面的话，一个没配密钥的项目会收到「密钥没配」，
        // 而它真正的问题是没有环境
        requireEnvironment(request);
        LlmClient llm = OpenAiCompatibleClient.from(project.llm(), projectRoot);

        String runId = hub.startRun(UUID.randomUUID().toString());
        runner.submit(() -> execute(spec, llm, request.approvedPlan(), settingsOf(request)));
        return runId;
    }

    /**
     * 勾了集成测试但没有可用的环境：<b>立刻拒，而不是等测试阶段才失败</b>。
     *
     * <p>十五.5 定的是「初始化好之后集成测试才能勾选」。界面上那道闸（勾选框能不能点）
     * 是给人看的，而这条是机器判的：界面可以旧、可以被改坏、也可以被别的调用方绕开
     * （CLI 就是一个）。等到测试阶段才发现，用户已经烧掉一整轮开发调用了，
     * 而失败看起来还像「代码写错了」。
     */
    private void requireEnvironment(RunRequest request) {
        if (!request.runsIntegration()) {
            return;
        }
        TestEnvironment.Status status = environment.status();
        if (!status.declared()) {
            throw new IllegalStateException("勾了集成测试，但这个项目没有 "
                    + EnvConfigLoader.relativePath() + "：先写一份环境声明再初始化");
        }
        if (!status.usable()) {
            throw new IllegalStateException("勾了集成测试，但测试环境还没就绪（"
                    + status.state().label() + "）："
                    + (status.todo().isEmpty() ? "" : status.todo()));
        }
    }

    /** 这次运行按哪个测试设置走。 */
    private static TestSettings settingsOf(RunRequest request) {
        return request.runsIntegration()
                ? new TestSettings(true)
                : TestSettings.UNIT_ONLY;
    }

    /**
     * 现在有没有一次运行挂着等人补料。
     */
    public SuspendedRun suspended() {
        return store.suspended()
                .map(record -> SuspendedRun.of(record, store.repeatedNeedsContext()))
                .orElseGet(SuspendedRun::none);
    }

    /**
     * 接着上一次跑：丢掉的那一轮不重发上下文，只补「它当时说了什么」和「接下来怎么办」。
     *
     * <p>为什么不干脆重开一轮：重开会把目标文件全文与上下文依赖再发一遍，
     * 而这份上下文用户已经付过一次钱了。施工单同理——挂着的那次已经定过单子，
     * 就从那条留档里拿过来接着用，不再问一遍（见 {@link DevelopmentAgent.Resume}）。
     *
     * @param force {@code true} = 用户没补料、直接放行；{@code false} = 用户补过上下文了
     */
    public synchronized String resume(RunRequest request, boolean force) {
        if (hub.running()) {
            throw new IllegalStateException("已有任务正在运行，请等它结束");
        }
        if (waitingSnapshot() != null) {
            throw new IllegalStateException("上一次的改动还没处置：请先「保留改动」或「撤回改动」");
        }
        RunRecord suspended = store.suspended()
                .orElseThrow(() -> new IllegalStateException("现在没有挂起的运行，直接点运行就行"));
        cancelRequested = false;
        lastTests = null;
        Spec spec = toValidSpec(request);
        requireEnvironment(request);
        LlmClient llm = OpenAiCompatibleClient.from(project.llm(), projectRoot);
        DevelopmentAgent.Resume origin =
                new DevelopmentAgent.Resume(suspended.detail(), force, suspended.planSteps());
        String runId = hub.startRun(UUID.randomUUID().toString());
        runner.submit(() -> execute(spec, llm, request.approvedPlan(), origin,
                settingsOf(request)));
        return runId;
    }

    /**
     * 请求停止当前这次运行。
     *
     * <p>它只置一个标志，不做任何等待，也不抛异常——界面点完「停止」要立刻有反馈，
     * 而真正的停止时刻由引擎决定：它在<b>下一轮开始之前</b>问一次
     * （{@link AgentListener#cancelled()}），为真就停下并把工作区回滚。
     *
     * <p>所以「点了停止」到「真的停下」之间隔着当前这一轮的剩余时间，
     * 可能还有一次模型调用在飞。不能真正打断的理由见
     * {@link AgentListener#cancelled()} 的说明。
     */
    public void cancel() {
        cancelRequested = true;
    }

    /**
     * 执行一次检查：只有一次模型调用，同步返回。
     *
     * <p>和开发阶段走异步轮询不同，这里没有多轮重试要推送，界面显示一个
     * 「检查中」就够了。为它再建一套进度通道得不偿失。
     *
     * <p>回来之后机器再过一遍：方案里提到的文件凡是不在目标清单里的，就是执行不了的地方。
     * 这件事不靠模型自评——它连"清单是白名单"都看不见（那是开发阶段的协议），
     * 所以它给出的方案必须由引擎自己核一遍。
     */
    public ReviewOutcome review(RunRequest request) {
        Spec spec = toValidSpec(request);
        LlmClient llm = OpenAiCompatibleClient.from(project.llm(), projectRoot);
        PlanReview plan = new PlanReviewer(new ContextAssembler(new SafePathResolver(projectRoot)),
                templates(), llm).review(spec);

        // 顺手扫一遍项目：判「方案里的文件在不在清单里」得知道项目里都有什么。
        // 一次检查只有一次模型调用（好几秒），这点扫树的功夫可以忽略；
        // 为此把 ProjectIndex 塞进构造函数反而让这个类多背一个依赖。
        ProjectIndex.Entries entries = new ProjectIndex(projectRoot).entries();
        return new ReviewOutcome(plan,
                PlanAudit.check(plan, spec.targets(), entries.files(), entries.directories()),
                // 施工单那几条不用扫项目：要动哪些文件是白纸黑字写在单子上的
                StepAudit.check(plan.steps(), spec.targets()));
    }

    /** 界面与 CLI 走同一套校验：这里过不了的 spec，命令行那边同样过不了。 */
    private Spec toValidSpec(RunRequest request) {
        Spec spec = request.toSpec();
        new SpecValidator().validate(spec, new SafePathResolver(projectRoot));
        return spec;
    }

    /**
     * 磁盘上那份还没被处置的改动；没有就返回 {@code null}。
     */
    public PendingChanges pending() {
        WorkspaceSnapshot snapshot = waitingSnapshot();
        return snapshot == null ? PendingChanges.none() : PendingChanges.of(snapshot);
    }

    /**
     * 接受：把快照删掉，磁盘上的改动保持不动（十五.8）。
     *
     * <p>「接受」不需要动文件——改动早就写进去了，快照留着只是为了让人还来得及撤回。
     * 剩下的三件事（删测试产物、清环境数据、把「带着几条失败接受的」写进留档）
     * 都在 {@link Teardown} 里，和 CLI 的 {@code accept} 共用同一份实现。
     */
    public Teardown.Done accept() {
        return Teardown.settle(projectRoot, project, store, environment, Teardown.Choice.ACCEPT);
    }

    /**
     * 中断：按快照把文件恢复原样，然后删快照、删测试产物、清环境数据（十五.8）。
     *
     * <p>它和 {@link #accept()} 走的是同一条路，只差 {@link Teardown.Choice} 一个参数：
     * 两条路的收尾动作一模一样，分头写就一定会有一边少做一件。
     */
    public Teardown.Done rollback() {
        return Teardown.settle(projectRoot, project, store, environment, Teardown.Choice.INTERRUPT);
    }

    /**
     * 最早的那一份未处置快照。
     *
     * <p>正常情况下最多只有一份：引擎在它被处置之前会拒绝开新的运行。
     * 多份只可能来自「清理快照失败」这类残留，那就从最早的一份开始算。
     */
    private WorkspaceSnapshot waitingSnapshot() {
        return Teardown.waiting(projectRoot, project).stream().findFirst().orElse(null);
    }

    private void execute(Spec spec, LlmClient llm, PlanReview approved, TestSettings settings) {
        execute(spec, llm, approved, null, settings);
    }

    /**
     * @param resume 非空表示这是「接着上次跑」，见 {@link #resume(RunRequest, boolean)}
     */
    private void execute(Spec spec, LlmClient llm, PlanReview approved, DevelopmentAgent.Resume resume,
                         TestSettings settings) {
        try {
            // 装饰器：先记进运行留档，再转发给界面推送。两件事互不知道对方存在，
            // CLI 那边套的是同一个录制器，只是转发目标换成了空实现。
            AgentListener listener = RunRecorder.start(store, spec, approved, this);
            DevelopmentAgent agent = new DevelopmentAgent(projectRoot, project, templates(),
                    llm, List.of(new CompileVerifier()), listener, settings, environment);
            if (resume == null) {
                agent.run(spec, approved);
            } else {
                agent.resume(spec, approved, resume.modelSaid(), resume.force(), resume.steps());
            }
        } catch (RuntimeException e) {
            log.warn("运行中断", e);
            hub.publish("error", 0, 0, null, "运行中断：" + e.getMessage());
            hub.publishResult(payload("ERROR", 0, String.valueOf(e.getMessage()), List.of()));
        } finally {
            hub.finish();
        }
    }

    // ---------- Agent 回调 → 界面事件 ----------

    /** 引擎每轮开头问的那一句，见 {@link #cancel()}。 */
    @Override
    public boolean cancelled() {
        return cancelRequested;
    }

    /**
     * 施工单定下来了：整份推一次。
     *
     * <p>界面要能一眼看出「一共几步、现在在第几步、还差什么」，所以不能一步步喂——
     * 那样它在最后一步之前都不知道总共有几步。{@code source} 还顺带回答了
     * 「这次和走检查的那次差在哪」这个唯一的问题。
     */
    @Override
    public void stepsResolved(List<PlanStep> steps, AgentListener.StepsSource source,
                              int probeCalls) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source.name());
        payload.put("probeCalls", probeCalls);
        payload.put("steps", steps);
        hub.publishPlan(ProgressMessages.stepsResolved(steps, source, probeCalls), payload);
    }

    @Override
    public void stepStarted(PlanStep step) {
        currentStep = step.index();
        hub.publish("info", 0, currentStep, RunEvent.STEP_RUNNING,
                ProgressMessages.stepStarted(step));
    }

    /**
     * 一步的终态：步态用 {@code StepState} 的枚举名，<b>不给界面留「认中文」这条路</b>。
     *
     * <p>以前只推一句「第 2 步：加接口：中间态（编译未通过）」，界面靠比对这几句措辞
     * 才知道该把哪一步画成什么状态。那种约定的坏处是：措辞一改，界面静默错位，
     * 而两边各自的测试都还是绿的。
     */
    @Override
    public void stepFinished(PlanStep step, AgentListener.StepState state) {
        hub.publish(stepLevel(state), 0, currentStep, state.name(),
                ProgressMessages.stepFinished(step, state));
    }

    @Override
    public void stepRestored(PlanStep step, int round, String reason) {
        // 回滚到该步开始前：这一步又回到「进行中」，而不是停在失败上
        hub.publish("warn", round, currentStep, RunEvent.STEP_RUNNING,
                ProgressMessages.stepRestored(step, reason));
    }

    @Override
    public void roundStarted(int round) {
        hub.publish("info", round, currentStep, null, ProgressMessages.roundStarted(round));
    }

    @Override
    public void planRejected(int round, PatchConflictException failure) {
        hub.publish("warn", round, currentStep, null, ProgressMessages.planRejected(failure));
    }

    @Override
    public void filesApplied(int round, List<PatchApplier.FileChange> changes) {
        hub.publish("info", round, currentStep, null, ProgressMessages.filesApplied(changes));
    }

    @Override
    public void verificationFinished(int round, List<VerificationResult> results) {
        for (VerificationResult result : results) {
            hub.publish(ProgressMessages.levelOf(result), round, currentStep, null,
                    ProgressMessages.verified(result));
        }
    }

    /**
     * 测试阶段开始了：时间线上先给一行「正在进行」。
     *
     * <p>这一行必须由引擎在这一刻发，不能让界面按下「运行」就自己写死：
     * 只有引擎知道<b>测试真的开始了</b>（也可能根本走不到这一步——编译没过、被中断）。
     * 界面自己写死的话，一次编译失败的运行也会显示「测试进行中」。
     */
    @Override
    public void testsStarted(int cases) {
        hub.publish("info", 0, 0, null, ProgressMessages.testsStarted(cases));
    }

    /**
     * 测试阶段收场了：时间线上给一行。
     *
     * <p>整份失败清单<b>不在这里推</b>——它比一行字重得多，跟着终态事件与运行留档走
     * （见 {@link #testPayload}）。这一行只回答「测试跑到哪了、成了没有」。
     *
     * <p>步号给 0（不属于任何一步）：测试阶段跑在整份施工单<b>之后</b>，
     * 挂在最后一步上会让人以为它是那一步的一部分。
     */
    @Override
    public void testsFinished(TestOutcome outcome) {
        // 留一笔给终态事件用：用户在结果面板上等的就是这个，不该再多一个来回
        lastTests = outcome;
        hub.publish(ProgressMessages.levelOf(outcome), 0, 0, null,
                ProgressMessages.testsFinished(outcome));
    }

    /**
     * 测试环境这一摊的变化：起好了、重置过、坏掉了、收掉了。
     *
     * <p>推一行给界面。它<b>不</b>推整份登记：那里面有几十个名字，实时流里塞不下，
     * 而界面要的是「现在到哪一步了」。整份登记跟着终态事件与运行留档走
     * （见 {@link #testPayload} 与 {@code RunRecord.environment}）。
     */
    @Override
    public void environmentChanged(EnvRegistration registration) {
        hub.publish(ProgressMessages.levelOf(registration), 0, 0, null,
                ProgressMessages.environmentChanged(registration));
    }

    @Override
    public void workspaceRestored(int round, String reason) {
        hub.publish("warn", round, currentStep, null, ProgressMessages.restored(reason));
    }

    /** 中间态和失败都要看得见：它们意味着此刻磁盘上的代码是编不过的。 */
    private static String stepLevel(AgentListener.StepState state) {
        return state == AgentListener.StepState.SUCCESS ? "info" : "warn";
    }

    @Override
    public void finished(AgentResult result) {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (PatchApplier.FileChange change : result.changes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", change.relative());
            item.put("created", change.created());
            item.put("bytes", change.bytes());
            item.put("diff", change.diff());
            changes.add(item);
        }
        Map<String, Object> body = payload(result.status().name(), result.attempts(),
                result.detail(), changes);
        // 这一次跑过测试就把结果一并带上（没跑就没有这个键：给一个空对象会被读成「跑了、全过」）
        if (lastTests != null) {
            body.put("tests", testPayload(lastTests));
        }
        hub.publishResult(body);
    }

    // ---------- 测试阶段的界面接口 ----------

    /**
     * 重新生成测试产物：<b>只生成，不跑</b>（十五.6 里「测试代码错了」那条路）。
     *
     * <p>为什么它不在 {@code /api/run} 里顺手做掉：这两件事的语义正好相反。
     * 运行是「按已确认的方案改产品代码，然后验收」；用户点「测试代码错了」的时候，
     * 他要的恰恰是<b>别再动产品代码、也别再跑一遍</b>，先看一眼新生成的测试代码写成什么样。
     * 塞进运行里，用户点一下就又烧掉一轮开发调用，而他要 review 的那份代码可能还是错的。
     *
     * <p>所以它不是一次运行：不排队、不占运行槽、不进轮次账、不写运行留档
     * （要留档的是产品改动，这里一个字节产品代码都没动）。它只花一次模型调用，
     * 把产物写到新的 {@code tools/<时间戳>/} 里，然后原样把代码交回界面。
     *
     * @param request 界面那份运行请求：用例清单在 {@code approvedPlan.cases} 里
     *                （用的是用户确认并冻结过的那一份，不是重新问模型要的）
     * @throws IllegalStateException     已有任务在跑、或者没有用例清单
     * @throws com.specflow.exception.SpecflowException 生成被拒（协议、越界、高危命令）
     */
    public Map<String, Object> regenerateTests(RunRequest request) {
        if (hub.running()) {
            throw new IllegalStateException("已有任务正在运行，请等它结束");
        }
        List<PlanReview.TestCase> cases = request.approvedPlan() == null
                ? List.of()
                : request.approvedPlan().cases();
        if (cases.isEmpty()) {
            throw new IllegalStateException("没有用例清单，重新生成无从下手：先「先检查」拿到用例再说");
        }
        Spec spec = toValidSpec(request);
        LlmClient llm = OpenAiCompatibleClient.from(project.llm(), projectRoot);
        // 重新生成也要跟着这次勾没勾集成走：只重新生成单元那一半，
        // 「放行」之后集成那一步会因为找不到入口而失败（见 TestAgent.generate）
        Map<String, String> variables = request.runsIntegration() && environment.status().usable()
                ? environment.variables() : null;
        // 入口脚本的名字由执行位置决定（容器里是 run.sh、宿主上是 run.cmd），
        // 所以这一处必须和真正跑起来那一次问的是同一个方法（见 ExecutionLocation）
        TestAgent.Generated generated = new TestAgent(projectRoot, project, templates(), llm)
                .generate(spec, cases, settingsOf(request), variables,
                        ExecutionLocation.of(environment));
        // 生成成功才把人这笔判断与产物账记下来（十五.6 第二条路）：生成失败什么都没换，
        // 记成「测试代码错了」会让人以为已经重生成过了
        markRegenerated(generated.directory());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("directory", generated.directory());
        payload.put("files", generated.files());
        // 正文一并回去：界面要把它摊开给人 review——这就是这条路存在的全部意义
        payload.put("sources", generated.sources());
        // 这批新代码接上线了没有（四条判据的结果）：没接上就先别点「放行」，
        // 因为拿去跑也是被拒绝运行——等再跑一次才发现，就白花一轮
        payload.put("trace", generated.trace());
        return payload;
    }

    /**
     * 测试结果里界面要用的那一份。
     *
     * <p>它跟着<b>终态事件</b>一起下发，而不是让界面再跑一趟 {@code /api/run-detail}：
     * 运行刚结束的那几秒正是用户盯着屏幕看结果的时候，多一个来回就是几百毫秒的空白；
     * 而且「这一次的失败清单」本来就在手边（{@link #testsFinished} 刚给过）。
     * 留档那一份仍然是权威（翻历史、刷新页面都读它），这一份只是先到一步。
     *
     * <p><b>只带事实，不带结论。</b>谁错了（代码还是用例）机器判不了，界面也不许替它判：
     * 传出去的 {@code failures[].opinion} 是脚本自己的说法，叫法就写着「它认为」。
     */
    private Map<String, Object> testPayload(TestOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("directory", outcome.directory());
        payload.put("files", outcome.files());
        payload.put("exit", outcome.exit());
        payload.put("passed", outcome.passed());
        payload.put("cases", outcome.cases());
        payload.put("failures", outcome.failures());
        payload.put("output", outcome.output());
        // 溯源连线（哪条用例的测试代码在哪个文件第几行）：界面上每个 chip 那一行
        // 「✅ 已连线 / ❌ 未连线」靠它，缺的几条就是未连线——那是要标红的东西
        payload.put("links", outcome.links());
        // 测试代码正文：界面上「这条用例由哪段代码验」靠它，路径只是一个索引
        payload.put("sources", TestAgent.sources(projectRoot, outcome.directory(), outcome.files()));
        return payload;
    }

    // ---------- 测试环境的界面接口 ----------

    /**
     * 现在这套测试环境是什么状态。
     *
     * @param refresh 用户刚把 Docker 启动起来时会用到它（见 {@code TestEnvironment.docker}）
     */
    public Map<String, Object> environmentStatus(boolean refresh) {
        // 先读声明：它自己写错了的时候连 docker 都不该探——一探就抛，
        // 而用户要的是「第几行写错了」，不是一句 400
        List<String> problems = configError();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("declaredFile", EnvConfigLoader.relativePath());
        payload.put("configError", problems);
        if (!problems.isEmpty()) {
            payload.put("declared", EnvConfigLoader.exists(projectRoot));
            payload.put("state", EnvRegistration.State.NOT_READY.name());
            payload.put("usable", false);
            payload.put("docker", "");
            payload.put("dockerLabel", "");
            payload.put("todo", "先改掉上面那几处（每一条都带着行号），改完再来初始化");
            payload.put("leftovers", 0);
            payload.put("leftoverWarn", false);
            return payload;
        }
        if (!environment.declared()) {
            // 没写这份声明的项目（绝大多数）**一次进程都不起**：连 docker 都不探。
            // 界面打开一次就要问一次环境状态，而「你到底装没装 Docker」这个问题，
            // 对一个只能跑单元测试的项目来说没有任何意义
            TestEnvironment.Status status = environment.status();
            payload.put("declared", false);
            payload.put("state", status.state().name());
            payload.put("usable", false);
            payload.put("docker", "");
            payload.put("dockerLabel", "");
            payload.put("todo", status.todo());
            payload.put("leftovers", 0);
            payload.put("leftoverWarn", false);
            payload.put("registration", status.registration());
            return payload;
        }

        environment.docker(refresh);
        TestEnvironment.Status status = environment.status();
        payload.put("declared", status.declared());
        payload.put("state", status.state().name());
        payload.put("usable", status.usable());
        payload.put("docker", status.dockerState());
        payload.put("dockerLabel", status.dockerLabel());
        payload.put("todo", status.todo());
        payload.put("leftovers", status.leftovers());
        payload.put("leftoverWarn", TestEnvironment.shouldWarnAboutLeftovers(status.leftovers()));
        payload.put("registration", status.registration());
        return payload;
    }

    /**
     * {@code env.yaml} 自身的问题（结构、字段、缺项），一个字段一项；没问题时是空表。
     *
     * <p>为什么要单独问一遍：它是<b>用户写的文件</b>，而写错的代价是「容器起不来」。
     * 在点初始化之前就把「第几行写错了」摆出来，用户改一轮就能改完；
     * 等到 docker 报一句语法错误，他只会以为是 docker 的问题。
     */
    private List<String> configError() {
        try {
            environment.config();
            return List.of();
        } catch (SpecValidationException e) {
            return e.problems();
        }
    }

    /**
     * 初始化测试环境（十五.5：导入项目时问一次，问的就是这件事）。
     *
     * <p>它是<b>同步</b>的：起容器要拉镜像，可能几分钟。做成异步就得再搭一条进度通道，
     * 而这一步的语义是「点一下，等它好」——界面上给一个忙碌状态就够了。
     */
    public EnvRegistration initializeEnvironment() {
        requireIdle("初始化测试环境");
        return environment.up();
    }

    /**
     * 收环境：用户手动点的「清空测试环境」（十五.8 那一个手动入口）。
     *
     * <p>连卷一起删（{@code down -v}）：用户点它的场景就是「这套环境不对劲，
     * 我要一个干净的」，留着卷只是把旧数据带进下一轮。
     */
    public EnvRegistration clearEnvironment() {
        requireIdle("清空测试环境");
        return environment.down(true);
    }

    /** 有任务在跑的时候不许动环境：容器正被这次运行用着，删掉就等于把测试腰斩。 */
    private void requireIdle(String what) {
        if (hub.running()) {
            throw new IllegalStateException("有任务正在运行，等它结束再" + what);
        }
    }

    // ---------- 人对失败用例的判断落档 ----------

    /**
     * 把「这几条怎么判的」写进运行留档（十五.6 里落在用例上的那三条路）。
     *
     * <p>它为什么要有接口：这是<b>人做的判断</b>，而留档是它唯一的去处。
     * 只留在界面上，刷新一次就没了——事后翻记录的人只会看到一片红，
     * 然后以为那次运行是失败的。
     *
     * @param id     哪一次运行；空串表示「界面上正看着的那一次」，按最新那条落（见下面注释）
     * @param cases  被这样判定的用例编号（<b>完整的一份集合</b>，不是增量）
     * @param owner  谁错了，见 {@link RunRecord.Verdict}
     */
    public RunRecord judge(String id, List<Integer> cases, String owner) {
        String target = id == null ? "" : id.strip();
        if (target.isEmpty()) {
            // 刷新过页面之后，界面手里只有屏幕上那份失败清单，拿不到记录 id。
            // 屏幕上那份清单本来就是「最新一次跑出来的」，所以按最新那条落比拒绝一次
            // 人的判断要好——而拒绝的代价是这条判断又丢了
            target = store.latestId();
        }
        if (target.isEmpty()) {
            throw new IllegalStateException("一条运行记录都没有：先跑一次测试，才有可判的失败清单");
        }
        return store.judge(target, cases, owner);
    }

    /**
     * 记一笔「测试代码错了」：人点了「重新生成」，这条判断和那批新产物都要落在
     * 被他判的那次运行上。
     *
     * <p>为什么由服务端自己记，而不是让界面再发一个请求：这一次动作本身就是那个判断
     * （他点的就是「测试代码错了」），不需要再多一次往返——而多出来的那一次往返，
     * 正是最容易被漏掉的那一次（用户在生成完之后刷新页面，判断就没了）。
     *
     * <p>判的范围是<b>最新那条记录上没过的那几条用例</b>：他看的就是这份清单。
     * 写不进去只记一条警告，绝不让它把一次成功的生成变成失败——产物已经生成好了。
     */
    private void markRegenerated(String directory) {
        String latest = store.latestId();
        if (latest.isEmpty()) {
            return;
        }
        try {
            RunRecord record = store.load(latest);
            List<Integer> failing = record.tests() == null
                    ? List.of() : record.tests().failingCases();
            store.regenerated(latest, failing, directory);
        } catch (RuntimeException e) {
            log.warn("这次重新生成的测试代码没能记进留档：{}", e.getMessage());
        }
    }

    // ---------- 内部 ----------

    private static Map<String, Object> payload(String status, int attempts, String detail,
                                               List<Map<String, Object>> changes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("attempts", attempts);
        payload.put("detail", detail);
        payload.put("changes", changes);
        return payload;
    }
}
