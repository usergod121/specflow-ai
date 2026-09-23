package com.specflow.web;

import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.agent.DevelopmentAgent;
import com.specflow.agent.ProgressMessages;
import com.specflow.context.ContextAssembler;
import com.specflow.exception.PatchConflictException;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.llm.LlmClient;
import com.specflow.llm.OpenAiCompatibleClient;
import com.specflow.patch.PatchApplier;
import com.specflow.project.ProjectConfig;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanReviewer;
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
        Spec spec = toValidSpec(request);
        LlmClient llm = OpenAiCompatibleClient.from(project.llm(), projectRoot);

        String runId = hub.startRun(UUID.randomUUID().toString());
        runner.submit(() -> execute(spec, llm, request.approvedPlan()));
        return runId;
    }

    /**
     * 执行一次检查：只有一次模型调用，同步返回。
     *
     * <p>和开发阶段走异步轮询不同，这里没有多轮重试要推送，界面显示一个
     * 「检查中」就够了。为它再建一套进度通道得不偿失。
     */
    public PlanReview review(RunRequest request) {
        Spec spec = toValidSpec(request);
        LlmClient llm = OpenAiCompatibleClient.from(project.llm(), projectRoot);
        return new PlanReviewer(new ContextAssembler(new SafePathResolver(projectRoot)),
                templates(), llm).review(spec);
    }

    /** 界面与 CLI 走同一套校验：这里过不了的 spec，命令行那边同样过不了。 */
    private Spec toValidSpec(RunRequest request) {
        Spec spec = request.toSpec();
        new SpecValidator().validate(spec, new SafePathResolver(projectRoot));
        return spec;
    }

    private void execute(Spec spec, LlmClient llm, PlanReview approved) {
        try {
            // 装饰器：先记进运行留档，再转发给界面推送。两件事互不知道对方存在，
            // CLI 那边套的是同一个录制器，只是转发目标换成了空实现。
            AgentListener listener = RunRecorder.start(store, spec, approved, this);
            DevelopmentAgent agent = new DevelopmentAgent(projectRoot, project, templates(),
                    llm, List.of(new CompileVerifier()), listener);
            agent.run(spec, approved);
        } catch (RuntimeException e) {
            log.warn("运行中断", e);
            hub.publish("error", 0, "运行中断：" + e.getMessage());
            hub.publishResult(payload("ERROR", 0, String.valueOf(e.getMessage()), List.of()));
        } finally {
            hub.finish();
        }
    }

    // ---------- Agent 回调 → 界面事件 ----------

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
