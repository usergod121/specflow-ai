package com.specflow.agent;

import com.specflow.context.ContextAssembler;
import com.specflow.context.PatchProtocol;
import com.specflow.exception.PatchConflictException;
import com.specflow.exception.SpecflowException;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.patch.PatchApplier;
import com.specflow.patch.PatchBlock;
import com.specflow.patch.PatchParser;
import com.specflow.patch.PatchPlan;
import com.specflow.patch.PatchStrategies;
import com.specflow.patch.PatchStrategy;
import com.specflow.project.ProjectConfig;
import com.specflow.review.PlanReview;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.spec.Spec;
import com.specflow.template.TemplateRegistry;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.CompileFailure;
import com.specflow.verify.VerificationContext;
import com.specflow.verify.VerificationResult;
import com.specflow.verify.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 开发 Agent——整套引擎的编排者。
 *
 * <p>它自己<b>不做任何具体工作</b>：不解析 YAML、不匹配锚点、不写文件、不跑命令。
 * 它的全部职责是把这些能力按正确的顺序串起来，并管理两条重试预算。
 * 这条边界是刻意的——每一层都能单独测试，Agent 的改动也不会波及下面已经稳定的部分。
 *
 * <p>主循环，每一轮都是一次「模型提议 → 本地校验 → 落盘 → 自检」：
 * <pre>
 *   调用模型
 *     ├─ 模型说 NEED_CONTEXT → 立刻停下，不改任何文件
 *     └─ 解析补丁块
 *          ├─ 解析/校验失败 → 什么都没写，把原因回喂，下一轮（便宜）
 *          └─ 生成改动计划 → 快照 → 落盘 → 校验
 *                                ├─ 通过 → 丢弃快照，收工
 *                                └─ 失败 → 回滚快照，把错误回喂，下一轮（贵）
 * </pre>
 *
 * <p>两条重试预算分开管理，因为代价完全不同：补丁冲突时磁盘没被动过，重试很便宜；
 * 校验失败意味着文件写了一遍又被回滚，重试昂贵，次数由 spec 控制。
 *
 * <p><b>回滚先于重试</b>是这里最关键的一个决定。它保证每一轮的起点都是
 * 「需求 + 原始代码」，因此模型每一轮都可以按原文件内容写 SEARCH 锚点，
 * 不必追踪「上一轮改到哪了」——而这恰恰是模型最不擅长的事。
 */
public final class DevelopmentAgent {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentAgent.class);

    /** 补丁冲突的重试上限：磁盘未改动，可以多给几次机会。 */
    private static final int MAX_CONFLICT_RETRIES = 3;

    /** {@code NEED_CONTEXT} 声明的行数上限——正常补丁里附带的一句话不应被当成中止信号。 */
    private static final int NEED_CONTEXT_MAX_LINES = 3;

    private final Path projectRoot;
    private final SafePathResolver pathResolver;
    private final ProjectConfig project;
    private final TemplateRegistry templates;
    private final LlmClient llm;
    private final List<Verifier> verifiers;
    private final AgentListener listener;

    private final ContextAssembler assembler;
    private final PatchParser parser = new PatchParser();
    private final PatchStrategies strategies = PatchStrategies.defaults();
    private final PatchApplier applier;

    public DevelopmentAgent(Path projectRoot, ProjectConfig project, TemplateRegistry templates,
                            LlmClient llm, List<Verifier> verifiers) {
        this(projectRoot, project, templates, llm, verifiers, AgentListener.NOOP);
    }

    public DevelopmentAgent(Path projectRoot, ProjectConfig project, TemplateRegistry templates,
                            LlmClient llm, List<Verifier> verifiers, AgentListener listener) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.pathResolver = new SafePathResolver(this.projectRoot);
        this.project = project;
        this.templates = templates;
        this.llm = llm;
        this.verifiers = List.copyOf(verifiers);
        this.listener = listener;
        this.assembler = new ContextAssembler(this.pathResolver);
        this.applier = new PatchApplier(this.pathResolver);
    }

    /**
     * 执行一次开发任务。
     *
     * <p>不抛业务异常：所有可预期的失败都被翻译成 {@link AgentResult}，让 CLI 统一渲染。
     * 只有程序性错误（快照目录不可写、构建命令无法启动）才冒泡成异常。
     */
    public AgentResult run(Spec spec) {
        return run(spec, null);
    }

    /**
     * 带「已确认的实现方案」执行一次开发任务。
     *
     * <p>方案来自检查阶段，是人看过、点过确认的那一份。把它回喂给开发阶段有两个作用：
     * 一是让模型别重新想一遍（想出来的可能不是你看过的那份），
     * 二是让它按图施工，代码和方案对不上时更容易被发现。
     *
     * @param approved 已确认的方案；为 {@code null} 表示跳过检查直接开发
     */
    public AgentResult run(Spec spec, PlanReview approved) {
        AgentResult result = execute(spec, approved);
        listener.finished(result);
        return result;
    }

    private AgentResult execute(Spec spec, PlanReview approved) {
        // 上一次的改动还在等人表态：磁盘上那份是好的，但没经过人确认。
        // 在它之上再叠一轮，等于让人在一个自己没看过的状态上继续施工。
        List<WorkspaceSnapshot> undisposed = WorkspaceSnapshot.undisposed(pathResolver, snapshotRoot());
        if (!undisposed.isEmpty()) {
            String waits = String.join("、", undisposed.stream()
                    .map(snapshot -> snapshot.directory().getFileName().toString())
                    .toList());
            return AgentResult.pendingDecision("上一次的改动还没处置（" + waits
                    + "）：请先接受或撤回它，再开始新的运行");
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(assembler.systemMessage(spec, templates)));
        messages.add(ChatMessage.user(userMessage(spec, approved)));

        int attempts = 0;
        int conflictRetries = 0;
        int verificationRetries = 0;
        // 上一轮失败的「指纹」：用来判断再喂回去还有没有意义
        String lastFailureSignature = "";
        List<PatchApplier.FileChange> lastChanges = List.of();
        List<VerificationResult> lastResults = List.of();

        while (true) {
            // 中断只在轮与轮之间生效：正在飞行的模型调用没有干净的取消方式。
            if (listener.cancelled()) {
                log.info("收到中断请求，停在下一轮之前（已完成 {} 轮）", attempts);
                return AgentResult.cancelled(attempts, lastChanges, lastResults);
            }

            attempts++;
            listener.roundStarted(attempts);
            String response = llm.complete(messages);
            messages.add(ChatMessage.assistant(response));

            String need = detectNeedContext(response);
            if (need != null) {
                log.info("模型声明信息不足，本轮不做任何改动：{}", need);
                return AgentResult.needsContext(attempts, need);
            }

            PatchPlan plan;
            try {
                plan = plan(spec, response);
            } catch (PatchConflictException e) {
                listener.planRejected(attempts, e);
                if (conflictRetries >= MAX_CONFLICT_RETRIES) {
                    log.warn("补丁冲突重试次数已用尽：{}", e.getMessage());
                    return AgentResult.failed(attempts, lastChanges, lastResults,
                            "补丁始终无法应用：" + e.getMessage());
                }
                conflictRetries++;
                log.warn("补丁冲突，第 {} 次重试：{}", conflictRetries, e.getMessage());
                messages.add(ChatMessage.user(RepairFeedback.forConflict(e)));
                continue;
            }

            Applied applied = applyAndVerify(spec, plan, attempts);
            lastChanges = applied.changes();
            lastResults = applied.results();

            VerificationResult failure = firstFailure(applied.results());
            if (failure == null) {
                return finish(attempts, applied);
            }
            // 环境/依赖问题：再给它几轮也修不好——它只会把用到那个包的地方删掉，
            // 于是编译过了、需求没实现。这种「假绿灯」比直接失败更糟，所以立刻停。
            if (failure.environmental()) {
                log.warn("校验失败且不是改代码能解决的：{}", failure.output());
                return AgentResult.needsEnvironment(attempts, lastChanges, lastResults,
                        CompileFailure.explain(failure.output()) + System.lineSeparator()
                                + "磁盘已回滚到本次运行前。");
            }
            // 同一个错误连着出现两轮：再喂回去也是白喂，停得干脆一点
            String signature = signatureOf(failure);
            if (signature.equals(lastFailureSignature)) {
                log.warn("连续两轮同一个错误，停止重试：{}", signature);
                return AgentResult.failed(attempts, lastChanges, lastResults,
                        "连续两轮都卡在同一个错误上，再重试也是白试：" + System.lineSeparator()
                                + "  " + signature);
            }
            lastFailureSignature = signature;
            if (verificationRetries >= spec.verify().maxRetry()) {
                log.warn("校验重试次数已用尽：{}", failure.verifier());
                return AgentResult.failed(attempts, lastChanges, lastResults,
                        "校验未通过且重试次数已用尽：" + RepairFeedback.summarize(applied.results()));
            }
            verificationRetries++;
            log.info("校验未通过，第 {} 次重试：{}", verificationRetries, failure.verifier());
            messages.add(ChatMessage.user(RepairFeedback.forVerification(failure)));
        }
    }

    /**
     * 这个失败的「指纹」：取输出里第一行有内容的，用来判断两轮是不是同一个错误。
     *
     * <p>不求精确——它只用来回答一个问题：**再喂回去还有没有意义**。
     */
    private static String signatureOf(VerificationResult failure) {
        String output = failure.output() == null ? "" : failure.output();
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (!text.isEmpty()) {
                return text.length() > 120 ? text.substring(0, 120) : text;
            }
        }
        return failure.verifier();
    }

    // ---------- 落盘与校验 ----------

    /**
     * 快照 → 落盘 → 校验。任何失败路径都必须把文件恢复到进入本方法之前的状态。
     *
     * <p>「落盘本身崩了」也要回滚：多文件写入不是原子的，写到第三个文件时磁盘满了，
     * 前两个已经变了——不恢复就会留下一个半成品工作区。
     */
    private Applied applyAndVerify(Spec spec, PatchPlan plan, int round) {
        WorkspaceSnapshot snapshot = project.snapshot().enabled()
                ? WorkspaceSnapshot.capture(pathResolver, snapshotRoot(), plan.files())
                : null;
        if (snapshot != null) {
            log.debug("已创建{}", snapshot);
        }

        List<PatchApplier.FileChange> changes;
        try {
            changes = applier.apply(plan);
        } catch (RuntimeException e) {
            rollback(snapshot, round, "落盘失败");
            throw e;
        }
        for (PatchApplier.FileChange change : changes) {
            log.info("{}", change.describe());
        }
        listener.filesApplied(round, changes);

        List<VerificationResult> results = verifyEverything(spec);
        listener.verificationFinished(round, results);
        if (firstFailure(results) != null) {
            rollback(snapshot, round, "校验未通过");
        } else if (snapshot != null) {
            // 校验通过不等于用户满意：改动留在磁盘上，快照改名等着人表态。
            // 在这里 discard 就等于替人做了「接受」，而编译通过只证明语法没错。
            snapshot.markPending();
        }
        return new Applied(changes, results);
    }

    /**
     * 跑校验；校验器自己炸了也要落成一条结果，而不是把整个运行掀掉。
     *
     * <p>以前这里让异常直接穿出去，后果是三重的：这一轮**没有结论**、磁盘**不回滚**、
     * 界面还把轮次显示成 0——用户同时看到「已写入 3 个文件」和「运行中断（共 0 轮）」。
     * 而校验器炸掉的原因（读不出日志、命令起不来）本来就不该由模型负责，所以落成
     * {@link VerificationResult.Kind#ENVIRONMENT}，让上层停得干净、说得清楚。
     *
     * <p>注意这里**只负责给出结论，不碰回滚**：回滚在 {@link #applyAndVerify} 里只有一处
     * （按「有没有失败」判断），在这里再回滚一次就会变成回滚两遍、界面收到两条恢复事件。
     */
    private List<VerificationResult> verifyEverything(Spec spec) {
        try {
            return runVerifiers(spec);
        } catch (RuntimeException e) {
            log.warn("校验过程出错，本轮按环境问题处理", e);
            return List.of(VerificationResult.failed("校验", "",
                    "校验过程出错：" + e.getMessage(), VerificationResult.Kind.ENVIRONMENT));
        }
    }

    private List<VerificationResult> runVerifiers(Spec spec) {
        if (verifiers.isEmpty()) {
            return List.of();
        }
        VerificationContext context = new VerificationContext(projectRoot, spec, project);
        List<VerificationResult> results = new ArrayList<>(verifiers.size());
        for (Verifier verifier : verifiers) {
            VerificationResult result = verifier.verify(context);
            log.info("{} => {}", verifier.name(), result.status());
            results.add(result);
            if (result.failed()) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private void rollback(WorkspaceSnapshot snapshot, int round, String reason) {
        listener.workspaceRestored(round, reason);
        if (snapshot == null) {
            log.warn("{}，但快照未开启，无法自动回滚；请手工检查工作区", reason);
            return;
        }
        List<String> restored = snapshot.restore();
        log.warn("{}，已回滚 {} 个文件：{}", reason, restored.size(), String.join(", ", restored));
        discardQuietly(snapshot, reason);
    }

    /**
     * 回滚之后这份快照就没用了，删掉它。
     *
     * <p>必须删：引擎把「磁盘上还有可用快照」当成「上一次还没处置」，
     * 留一份已经作废的快照会把下一次运行挡在门外。
     *
     * <p>删不掉只警告、不改写原有的失败原因——真正的问题（比如磁盘满）比清理更要紧，
     * 而残留的那一份用户在界面上点一下「接受」也能清掉。
     */
    private void discardQuietly(WorkspaceSnapshot snapshot, String reason) {
        try {
            snapshot.discard();
        } catch (SpecflowException e) {
            log.warn("{}之后清理快照失败，请手工删除 {}：{}", reason, snapshot.directory(), e.getMessage());
        }
    }

    private AgentResult finish(int attempts, Applied applied) {
        if (applied.results().isEmpty() || applied.results().stream().allMatch(VerificationResult::skipped)) {
            return AgentResult.unverified(attempts, applied.changes(), applied.results(),
                    "改动已落盘，但没有执行任何校验（未配置编译命令或已被 spec 关闭）");
        }
        return AgentResult.success(attempts, applied.changes(), applied.results());
    }

    private VerificationResult firstFailure(List<VerificationResult> results) {
        return results.stream().filter(VerificationResult::failed).findFirst().orElse(null);
    }

    // ---------- 解析与校验 ----------

    /**
     * 组装发给模型的用户消息。
     *
     * <p>方案拼在最后而不是最前：模型对结尾的内容印象更深，而「按这张图施工」
     * 正是本轮最需要它记住的事。
     */
    private String userMessage(Spec spec, PlanReview approved) {
        String message = assembler.userMessage(spec, templates);
        if (approved == null || approved.render().isEmpty()) {
            return message;
        }
        return message + "\n## 已确认的实现方案（请按它实现）\n" + approved.render() + "\n";
    }

    private PatchPlan plan(Spec spec, String response) {
        List<PatchBlock> blocks = parser.parse(response);
        PatchStrategy strategy = strategies.get(spec.strategy());
        return strategy.plan(blocks, spec, pathResolver);
    }

    /**
     * 识别模型的「信息不足」声明。
     *
     * <p>只在响应很短时才认，避免正常补丁末尾附带的
     * 「如果想更精确，NEED_CONTEXT: ...」被误判成中止信号。
     *
     * @return 声明的内容；不是声明则返回 {@code null}
     */
    private String detectNeedContext(String response) {
        String text = response.strip();
        if (!text.startsWith(PatchProtocol.NEED_CONTEXT_PREFIX)) {
            return null;
        }
        if (text.lines().count() > NEED_CONTEXT_MAX_LINES) {
            return null;
        }
        return text.substring(PatchProtocol.NEED_CONTEXT_PREFIX.length()).strip();
    }

    private Path snapshotRoot() {
        return pathResolver.resolve(project.snapshot().dir());
    }

    /** 落盘与校验的成对结果，避免用可变字段在方法之间传值。 */
    private record Applied(List<PatchApplier.FileChange> changes, List<VerificationResult> results) {
    }
}
