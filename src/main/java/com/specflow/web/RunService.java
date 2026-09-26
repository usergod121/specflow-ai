package com.specflow.web;

import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.agent.DevelopmentAgent;
import com.specflow.agent.ProgressMessages;
import com.specflow.context.ContextAssembler;
import com.specflow.exception.PatchConflictException;
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
import com.specflow.review.ReviewOutcome;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.spec.Spec;
import com.specflow.spec.SpecValidator;
import com.specflow.template.TemplateRegistry;
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

    private final ExecutorService runner = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "specflow-run");
        thread.setDaemon(true);
        return thread;
    });

    public RunService(Path projectRoot, ProjectConfig project, Path templatesDir) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.project = project;
        this.templatesDir = templatesDir;
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
        Spec spec = toValidSpec(request);
        LlmClient llm = OpenAiCompatibleClient.from(project.llm(), projectRoot);

        String runId = hub.startRun(UUID.randomUUID().toString());
        runner.submit(() -> execute(spec, llm, request.approvedPlan()));
        return runId;
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
     * 而这份上下文用户已经付过一次钱了。
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
        Spec spec = toValidSpec(request);
        LlmClient llm = OpenAiCompatibleClient.from(project.llm(), projectRoot);
        DevelopmentAgent.Resume origin = new DevelopmentAgent.Resume(suspended.detail(), force);
        String runId = hub.startRun(UUID.randomUUID().toString());
        runner.submit(() -> execute(spec, llm, request.approvedPlan(), origin));
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
                PlanAudit.check(plan, spec.targets(), entries.files(), entries.directories()));
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
     * 接受：把快照删掉，磁盘上的改动保持不动。
     *
     * <p>「接受」不需要动文件——改动早就写进去了，快照留着只是为了让人还来得及撤回。
     */
    public void accept() {
        dispose(false);
    }

    /**
     * 撤回：按快照把文件恢复原样，然后删掉快照。
     */
    public void rollback() {
        dispose(true);
    }

    private void dispose(boolean restore) {
        WorkspaceSnapshot snapshot = waitingSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("没有待处置的改动");
        }
        if (restore) {
            snapshot.restore();
        }
        snapshot.discard();
    }

    /**
     * 最早的那一份未处置快照。
     *
     * <p>正常情况下最多只有一份：引擎在它被处置之前会拒绝开新的运行。
     * 多份只可能来自「清理快照失败」这类残留，那就从最早的一份开始算。
     */
    private WorkspaceSnapshot waitingSnapshot() {
        SafePathResolver resolver = new SafePathResolver(projectRoot);
        return WorkspaceSnapshot.undisposed(resolver, resolver.resolve(project.snapshot().dir()))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void execute(Spec spec, LlmClient llm, PlanReview approved) {
        execute(spec, llm, approved, null);
    }

    /**
     * @param resume 非空表示这是「接着上次跑」，见 {@link #resume(RunRequest, boolean)}
     */
    private void execute(Spec spec, LlmClient llm, PlanReview approved, DevelopmentAgent.Resume resume) {
        try {
            // 装饰器：先记进运行留档，再转发给界面推送。两件事互不知道对方存在，
            // CLI 那边套的是同一个录制器，只是转发目标换成了空实现。
            AgentListener listener = RunRecorder.start(store, spec, approved, this);
            DevelopmentAgent agent = new DevelopmentAgent(projectRoot, project, templates(),
                    llm, List.of(new CompileVerifier()), listener);
            if (resume == null) {
                agent.run(spec, approved);
            } else {
                agent.resume(spec, approved, resume.modelSaid(), resume.force());
            }
        } catch (RuntimeException e) {
            log.warn("运行中断", e);
            hub.publish("error", 0, "运行中断：" + e.getMessage());
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

    @Override
    public void roundStarted(int round) {
        hub.publish("info", round, ProgressMessages.roundStarted(round));
    }

    @Override
    public void planRejected(int round, PatchConflictException failure) {
        hub.publish("warn", round, ProgressMessages.planRejected(failure));
    }

    @Override
    public void filesApplied(int round, List<PatchApplier.FileChange> changes) {
        hub.publish("info", round, ProgressMessages.filesApplied(changes));
    }

    @Override
    public void verificationFinished(int round, List<VerificationResult> results) {
        for (VerificationResult result : results) {
            hub.publish(ProgressMessages.levelOf(result), round, ProgressMessages.verified(result));
        }
    }

    @Override
    public void workspaceRestored(int round, String reason) {
        hub.publish("warn", round, ProgressMessages.restored(reason));
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
        hub.publishResult(payload(result.status().name(), result.attempts(),
                result.detail(), changes));
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
