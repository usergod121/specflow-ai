package com.specflow.agent;

import com.specflow.agent.AgentListener.StepState;
import com.specflow.agent.AgentListener.StepsSource;
import com.specflow.context.ContextAssembler;
import com.specflow.context.PatchProtocol;
import com.specflow.exception.PatchConflictException;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.patch.PatchApplier;
import com.specflow.patch.PatchBlock;
import com.specflow.patch.PatchParser;
import com.specflow.patch.PatchPlan;
import com.specflow.patch.PatchStrategies;
import com.specflow.patch.PatchStrategy;
import com.specflow.project.ProjectConfig;
import com.specflow.review.PlanParser;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanStep;
import com.specflow.review.StepAudit;
import com.specflow.review.StepsProtocol;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.spec.Spec;
import com.specflow.template.TemplateRegistry;
import com.specflow.util.ProjectFiles;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.CompileFailure;
import com.specflow.verify.VerificationContext;
import com.specflow.verify.VerificationResult;
import com.specflow.verify.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 开发 Agent——整套引擎的编排者。
 *
 * <p>它自己<b>不做任何具体工作</b>：不解析 YAML、不匹配锚点、不写文件、不跑命令。
 * 它的全部职责是把这些能力按正确的顺序串起来，并管理三份预算
 * （补丁冲突、每步的编译重试、整次运行的总轮次）。
 * 这条边界是刻意的——每一层都能单独测试，Agent 的改动也不会波及下面已经稳定的部分。
 *
 * <p><b>只有一条执行路径</b>：沿施工单一步一步走。
 * <pre>
 *   （没有施工单就先花一次调用生成；拿不到就退化成只有一步）
 *   整次运行拍一个快照（覆盖目标清单全部文件）
 *   对施工单上的每一步：
 *       记下该步的进入点（内存）→ 追加本步施工指令 → 循环：
 *           调用模型
 *             ├─ 模型说 NEED_CONTEXT → 停下并回滚（分步时前面几步一并撤掉）
 *             └─ 解析补丁块
 *                  ├─ 解析/校验失败 → 什么都没写，把原因回喂，下一轮（便宜）
 *                  └─ 落盘 → 编译
 *                        ├─ 通过 → 下一步
 *                        ├─ 失败且这一步是「中间态」→ 记一笔，继续下一步
 *                        └─ 失败 → 回滚到**本步进入点**，把错误回喂重试（贵）
 *       本步重试耗尽 / 总轮次用尽 → **整个运行回滚到起点**，失败结束
 *   全部走完 → 快照改名 .pending，等人接受或回滚
 * </pre>
 *
 * <p><b>没有施工单时（{@code steps} 只有一步）走的就是上面这条路的特例</b>——
 * 一个步骤、没有步级事件、没有步级指令。老的单步行为不需要另写一段代码来保持。
 *
 * <p>唯一另一处输入差异是提示词的措辞：<b>不会再拿到新材料</b>时用
 * {@link PatchProtocol#INSTRUCTIONS_WITHOUT_NEED_CONTEXT}（不递「缺料就认输」这个梯子），
 * 其余用带出口的 {@link PatchProtocol#INSTRUCTIONS}。「不会再拿到新材料」有两种：
 * 检查过、方案已由人确认，以及用户按了「直接放行」（{@link Resume#force()}）。
 * 两条路共用同一套循环与回滚。
 *
 * <p><b>回滚先于重试</b>是这里最关键的一个决定。它保证每一轮的起点都是
 * 「需求 + 该步开始前的代码」，因此模型每一轮都可以按那份原文写 SEARCH 锚点，
 * 不必追踪「上一轮改到哪了」——而这恰恰是模型最不擅长的事。
 */
public final class DevelopmentAgent {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentAgent.class);

    /** 补丁冲突的重试上限：磁盘未改动，可以多给几次机会。 */
    private static final int MAX_CONFLICT_RETRIES = 3;

    /** {@code NEED_CONTEXT} 声明的行数上限——正常补丁里附带的一句话不应被当成中止信号。 */
    private static final int NEED_CONTEXT_MAX_LINES = 3;

    /**
     * 现生成施工单最多试几次。
     *
     * <p>2 而不是 3：第一次不合法时会把机器的意见回喂再要一次，那一次的命中率明显更高；
     * 两次都拿不到就说明这条需求本来也不适合分步，再试只是多烧调用。
     */
    private static final int STEP_GENERATION_TRIES = 2;

    /**
     * 拿不到施工单时用的那一步。
     *
     * <p>它的存在让「没有施工单」不必另写一条分支：整条循环照跑，只是只有一步、
     * 也就没有步级事件和步级指令——正是今天的行为。
     */
    private static final PlanStep SINGLE_STEP =
            new PlanStep(1, "按需求把这件事一次做完", List.of(), "", false);

    private final Path projectRoot;
    private final SafePathResolver pathResolver;
    private final ProjectConfig project;
    private final TemplateRegistry templates;
    private final LlmClient llm;
    private final List<Verifier> verifiers;
    private final AgentListener listener;

    private final ContextAssembler assembler;
    private final PatchParser parser = new PatchParser();
    private final PlanParser planParser = new PlanParser();
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
        AgentResult result = execute(spec, approved, null);
        listener.finished(result);
        return result;
    }

    /**
     * 从「它上一次说缺什么」接着跑，而不是重开一轮。
     *
     * <p>重开一轮等于把整份上下文（目标文件全文 + 上下文依赖）再发一遍，用户已经付过一次钱了。
     * 这里只补两条消息：它当时说了什么、接下来该怎么办。
     *
     * @param modelSaid     上一次它输出的那段 {@code NEED_CONTEXT} 原文
     * @param force         {@code true} = 用户没补东西、直接让它干；{@code false} = 用户补过上下文了
     * @param recordedSteps 上一次那条留档里已经定下来的施工单；留档里没有（老记录）就给空列表，
     *                      那时候才现生成一份，见 {@link #stepsFor}
     */
    public AgentResult resume(Spec spec, PlanReview approved, String modelSaid, boolean force,
                              List<PlanStep> recordedSteps) {
        AgentResult result = execute(spec, approved, new Resume(modelSaid, force, recordedSteps));
        listener.finished(result);
        return result;
    }

    /**
     * 上一次留下的「缺料声明」，以及这一次是补了料还是直接放行。
     *
     * @param modelSaid 上一次它输出的那段 {@code NEED_CONTEXT} 原文
     * @param force     {@code true} = 用户没补东西、直接让它干
     * @param steps     上一次那条留档里已经定下来的施工单。老记录里没有这一项，
     *                  读出来是 {@code null}——那一次续跑只能照旧现生成一份
     */
    public record Resume(String modelSaid, boolean force, List<PlanStep> steps) {

        public Resume {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    private AgentResult execute(Spec spec, PlanReview approved, Resume resume) {
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
        // 施工单从哪来，是「这次运行和别人不一样」的唯一一处输入差异；定下来就先说出去，
        // 界面才能一次画出「一共几步」。它同时决定提示词用哪一档协议、要不要叮嘱它别再来要东西，
        // 所以这一步必须排在拼消息之前
        Steps steps = stepsFor(spec, approved, resume);
        boolean confirmedPlan = steps.source() == StepsSource.APPROVED;
        // 这一次不会再有新材料：方案已由人确认，或者用户按了「直接放行」（force）。
        // 两种情况下都不该留「缺料就认输」那个出口——留着它，续跑就还是原地停下再要一次料，
        // 而用户刚刚明确说过没有更多东西了。不只管提示词：下面两条回给模型的话也要同一档口径
        boolean noNeedContext = confirmedPlan || (resume != null && resume.force());
        messages.add(ChatMessage.system(assembler.systemMessage(spec, templates,
                noNeedContext
                        ? PatchProtocol.INSTRUCTIONS_WITHOUT_NEED_CONTEXT
                        : PatchProtocol.INSTRUCTIONS)));
        messages.add(ChatMessage.user(userMessage(spec, approved, noNeedContext)));
        if (resume != null) {
            messages.add(ChatMessage.assistant(resume.modelSaid()));
            messages.add(ChatMessage.user(resumePrompt(resume.force(), confirmedPlan)));
        }

        listener.stepsResolved(steps.list(), steps.source(), steps.probeCalls());

        List<Path> targets = targets(spec);
        // 整次运行只拍一个快照，而且覆盖**目标清单全部文件**，不是某一步声明的 files：
        // 白名单是引擎已知的，「哪一步改哪个文件」只是模型的说法；
        // 按说法去拍，它改到说法之外的文件时就回不去了
        WorkspaceSnapshot snapshot = project.snapshot().enabled()
                ? WorkspaceSnapshot.capture(pathResolver, snapshotRoot(), targets)
                : null;
        if (snapshot != null) {
            log.debug("已创建{}", snapshot);
        }

        int budget = spec.verify().roundBudget(steps.list().size());
        boolean split = steps.split();
        int rounds = 0;
        List<PatchApplier.FileChange> lastChanges = List.of();
        List<VerificationResult> lastResults = List.of();
        StepState lastState = StepState.SUCCESS;
        // 单步执行时进入点就是运行起点，拍一次就够；分步时每一步各拍一次
        StepCheckpoint entry = split ? null : StepCheckpoint.of(pathResolver, targets);

        nextStep:
        for (PlanStep step : steps.list()) {
            // 步与步之间也要能停：一份施工单是几十次模型调用，
            // 只在轮与轮之间问的话，用户按下停止还要等一整步跑完
            if (split && listener.cancelled()) {
                log.info("收到中断请求，停在下一步之前（已完成 {} 轮）", rounds);
                closeRun(snapshot, true, rounds, "人工中断");
                return AgentResult.cancelled(rounds, lastChanges, lastResults);
            }
            if (split) {
                // 进入点只存内存：它只服务于「本步重试前回滚」这一件事，用完即弃。
                // 落盘的话，崩溃之后它会变成一个和 WorkspaceSnapshot 抢恢复判据的目录
                entry = StepCheckpoint.of(pathResolver, targets);
                listener.stepStarted(step);
                messages.add(ChatMessage.user(stepInstruction(step, steps.list().size(), noNeedContext)));
            }

            int verificationRetries = 0;
            int conflictRetries = 0;
            // 本步内上一轮失败的「指纹」：用来判断再喂回去还有没有意义
            String lastFailureSignature = "";

            while (true) {
                // 中断只在轮与轮之间生效：正在飞行的模型调用没有干净的取消方式
                if (listener.cancelled()) {
                    log.info("收到中断请求，停在下一轮之前（已完成 {} 轮）", rounds);
                    closeRun(snapshot, split, rounds, "人工中断");
                    return AgentResult.cancelled(rounds, lastChanges, lastResults);
                }
                // 总轮次是最后一道闸（0 = 不设闸，见 VerifySpec.roundBudget）
                if (budget > 0 && rounds >= budget) {
                    // 这一轮还没落盘：单步时磁盘仍等于运行起点，分步时前面几步还在
                    return fail(snapshot, split, rounds, lastChanges, lastResults,
                            "总轮次已用尽（上限 " + budget + " 轮）。"
                                    + (split ? "施工单共 " + steps.list().size() + " 步，" : "")
                                    + "要么调大 verify.max-rounds，要么把这一步拆小一点");
                }

                rounds++;
                listener.roundStarted(rounds);
                String response = llm.complete(messages);
                messages.add(ChatMessage.assistant(response));

                String need = detectNeedContext(response);
                if (need != null) {
                    log.info("模型声明信息不足，本轮不做任何改动：{}", need);
                    // 挂起等人的前提是「磁盘上没我们的东西」：分步时前面几步已经落盘，
                    // 半成品加一个挂起，续跑的那次谁也说不清该从哪一步接
                    closeRun(snapshot, split, rounds, "模型声明信息不足");
                    return AgentResult.needsContext(rounds, split
                            ? need + System.lineSeparator()
                                    + "（第 " + step.index() + " 步说要补料，已把前面几步的改动一并撤回："
                                    + "挂起期间磁盘必须是干净的）"
                            : need);
                }

                PatchPlan plan;
                try {
                    plan = plan(spec, response);
                } catch (PatchConflictException e) {
                    listener.planRejected(rounds, e);
                    if (conflictRetries >= MAX_CONFLICT_RETRIES) {
                        log.warn("补丁冲突重试次数已用尽：{}", e.getMessage());
                        // 这一轮一个字节都没写：单步时磁盘仍等于运行起点，分步时前面几步还在
                        return fail(snapshot, split, rounds, lastChanges, lastResults,
                                "补丁始终无法应用：" + e.getMessage());
                    }
                    conflictRetries++;
                    log.warn("补丁冲突，第 {} 次重试：{}", conflictRetries, e.getMessage());
                    messages.add(ChatMessage.user(RepairFeedback.forConflict(e)));
                    continue;
                }

                Applied applied;
                try {
                    applied = applyAndVerify(spec, plan, rounds);
                } catch (RuntimeException e) {
                    // 落盘写到一半失败：多文件写入不是原子的，前几个文件可能已经变了。
                    // 这种时候工作区是个半成品，只能整个撤回，没有「修一下继续」这回事
                    closeRun(snapshot, true, rounds, "落盘失败");
                    throw e;
                }
                lastChanges = applied.changes();
                lastResults = applied.results();

                VerificationResult failure = firstFailure(applied.results());
                if (failure == null) {
                    // 「能过就当比预期好记一笔」在 ProgressMessages.stepFinished 里说
                    lastState = StepState.SUCCESS;
                    if (split) {
                        listener.stepFinished(step, lastState);
                    }
                    continue nextStep;
                }

                // 环境/依赖问题：再给它几轮也修不好——它只会把用到那个包的地方删掉，
                // 于是编译过了、需求没实现。这种「假绿灯」比直接失败更糟，所以立刻停。
                if (failure.environmental()) {
                    log.warn("校验失败且不是改代码能解决的：{}", failure.output());
                    // 这一轮的改动还在盘上（还没走到回滚那一步），所以必须撤
                    closeRun(snapshot, true, rounds, "校验失败且不是改代码能解决的");
                    return AgentResult.needsEnvironment(rounds, lastChanges, lastResults,
                            CompileFailure.explain(failure.output()) + System.lineSeparator()
                                    + "磁盘已回滚到本次运行前。");
                }

                // 中间态：这一步做完整个项目本来就编不过，这是施工单上写明了的约定。
                // 不重试、不阻塞——重试也没用，它缺的是下一步的代码，不是这一次的修补
                if (step.intermediate()) {
                    log.info("第 {} 步声明为中间态，编译未通过，按约定继续下一步", step.index());
                    lastState = StepState.INTERMEDIATE;
                    if (split) {
                        listener.stepFinished(step, lastState);
                    }
                    continue nextStep;
                }

                // 同一个错误连着出现两轮：再喂回去也是白喂，停得干脆一点
                String signature = signatureOf(failure);
                if (signature.equals(lastFailureSignature)) {
                    log.warn("连续两轮同一个错误，本步停止重试：{}", signature);
                    // 同样：本轮的改动还在盘上
                    return fail(snapshot, true, rounds, lastChanges, lastResults,
                            "第 " + step.index() + " 步连续两轮都卡在同一个错误上，再重试也是白试："
                                    + System.lineSeparator() + "  " + signature);
                }
                lastFailureSignature = signature;
                if (verificationRetries >= spec.verify().maxRetry()) {
                    log.warn("第 {} 步的校验重试次数已用尽：{}", step.index(), failure.verifier());
                    return fail(snapshot, true, rounds, lastChanges, lastResults,
                            "第 " + step.index() + " 步的校验未通过且重试次数已用尽："
                                    + RepairFeedback.summarize(applied.results()));
                }
                verificationRetries++;
                log.info("本步校验未通过，第 {} 次重试：{}", verificationRetries, failure.verifier());
                // 回滚到本步的进入点再重试：改动留在盘上的话，
                // 下一轮的锚点必然对不上（它看到的是 run 开始时那份上下文）
                restoreStep(entry, step, rounds, split, "校验未通过");
                messages.add(ChatMessage.user(RepairFeedback.forVerification(failure)));
            }
        }

        // 最后一步是中间态：施工单跑完本该是一个能编译的项目，这里没有做到。
        // 只有人硬放的、最后一步被标成中间态的单子会走到这儿
        if (lastState == StepState.INTERMEDIATE) {
            log.warn("施工单最后一步是中间态且编译未通过，这次运行不能算成功");
            return fail(snapshot, true, rounds, lastChanges, lastResults,
                    "施工单最后一步被标成「中间态」且编译未通过：磁盘上的代码可能编不过。"
                            + "施工单本该保证最后一步之后项目能编译，请修正施工单后重跑");
        }
        if (snapshot != null) {
            // 编译通过不等于用户满意：改动留在磁盘上，快照改名等着人表态。
            // 在这里 discard 就等于替人做了「接受」，而编译通过只证明语法没错。
            snapshot.markPending();
        }
        return finish(rounds, lastChanges, lastResults);
    }

    // ---------- 施工单 ----------

    /**
     * 这次运行按哪些步走。
     *
     * <p>检查阶段给过的施工单<b>直接就用</b>，运行期不再自己核一遍：
     * 它已经被人看过、可能还改过，而「改单」是检查阶段的权利。
     * 机器那几条硬规则在检查时就报过了，人点「仍然继续」就是承担了这个选择。
     *
     * <p>续跑时同理<b>先用上一条留档里的那一份</b>：那次挂起的运行已经定过单子、
     * 也已经为它付过调用（见 {@link #generateSteps}），重开一轮再问一遍是在为同一件事付第二次钱。
     * 留档里没有（老记录没这一项）才现生成。
     *
     * <p>两条路的单子都是「已经定下来的」：留档里那一份当初也是过了同一套机器校验才被采用的。
     */
    private Steps stepsFor(Spec spec, PlanReview approved, Resume resume) {
        if (approved != null && !approved.steps().isEmpty()) {
            return new Steps(approved.steps(), StepsSource.APPROVED, 0);
        }
        if (resume != null && !resume.steps().isEmpty()) {
            return new Steps(resume.steps(), StepsSource.RESUMED, 0);
        }
        return generateSteps(spec, approved);
    }

    /**
     * 没有施工单时，开工前花一次调用现生成一份。
     *
     * <p>为什么要允许「现造单」：直接开工（没跑过检查）是正常用法，那条路上只能现生成。
     * 而<b>拿不到就退化成单步</b>——永远不卡人：这次调用没换到更好的分步，
     * 最坏也只是退回今天的行为。
     *
     * <p>第一次不合法时把机器的意见回喂再要一次，而不是重掷骰子：
     * 「步数越界 / 最后一步中间态 / 引用了清单外的文件」都是能一句话说清、也一定能改的。
     *
     * <p>这几次调用<b>不计入轮次</b>（见 {@link #stepsFor} 的调用方与
     * {@code AgentListener#stepsResolved}）：轮次是「装配一次上下文、写一次代码」，
     * 混进来会让界面上的「共 N 轮」和「第 N 轮」对不上。它们由 {@code probeCalls} 单独报。
     */
    private Steps generateSteps(Spec spec, PlanReview approved) {
        List<ChatMessage> probe = new ArrayList<>();
        probe.add(ChatMessage.system(assembler.systemMessage(spec, templates, StepsProtocol.INSTRUCTIONS)));
        // 「现生成施工单」和「检查过、已确认」是互斥的两条路：走到这里就说明没有已确认的单子，
        // 所以这一份用户消息照旧留着「信息确实不足」那个出口
        probe.add(ChatMessage.user(userMessage(spec, approved, false)));

        for (int attempt = 1; attempt <= STEP_GENERATION_TRIES; attempt++) {
            String response = llm.complete(probe);
            List<PlanStep> steps = planParser.parseSteps(response);
            StepAudit.Result audit = StepAudit.check(steps, spec.targets());
            if (!steps.isEmpty() && !audit.blocking()) {
                log.info("开工前现生成的施工单共 {} 步", steps.size());
                return new Steps(steps, StepsSource.GENERATED, attempt);
            }
            if (attempt == STEP_GENERATION_TRIES) {
                log.warn("现生成的施工单仍然不可用，这次退回单步执行：{}", refusalOf(steps, audit));
                break;
            }
            probe.add(ChatMessage.assistant(response));
            probe.add(ChatMessage.user(retryPrompt(steps, audit)));
        }
        return new Steps(List.of(SINGLE_STEP), StepsSource.SINGLE, STEP_GENERATION_TRIES);
    }

    /** 为什么这份施工单用不了——写进日志，也写进回喂给模型的那句话。 */
    private static String refusalOf(List<PlanStep> steps, StepAudit.Result audit) {
        StringBuilder out = new StringBuilder();
        if (steps.isEmpty()) {
            out.append("没有认出来任何一步（STEPS 块缺失，或者每行不是五个字段）");
        }
        for (StepAudit.Finding finding : audit.findings()) {
            if (out.length() > 0) {
                out.append("；");
            }
            out.append(finding.reason());
        }
        return out.toString();
    }

    private static String retryPrompt(List<PlanStep> steps, StepAudit.Result audit) {
        StringBuilder out = new StringBuilder("这份施工单机器核不过：\n");
        if (steps.isEmpty()) {
            out.append("- 一行都没认出来：必须用 ").append(com.specflow.review.ReviewProtocol.STEPS_MARKER)
                    .append(" 块，每行一步，五个字段用竖线 | 分隔\n");
        }
        audit.findings().forEach(finding -> out.append("- ").append(finding.reason()).append('\n'));
        out.append("请按同样的格式重新给一份**完整的**施工单（不要只给要改的那几行）。");
        return out.toString();
    }

    /** 施工单，以及它是从哪来的。 */
    private record Steps(List<PlanStep> list, StepsSource source, int probeCalls) {

        /** 只有一步 = 单步执行，也就是没有施工单时的老行为。 */
        boolean split() {
            return list.size() > 1;
        }
    }

    /**
     * 本步的施工指令——每一步开工前追加的那一条 user 消息。
     *
     * <p>为什么非要多发一条：模型看到的是<b>整份需求</b>，而「现在只做第 3 步」这件事
     * 没有任何地方写着。不写清楚，它会把后面几步一起做掉——那些步骤的上下文和重试预算
     * 都还没轮到，最后表现为「它一口气写完、编译失败，却不知道是哪一步的错」。
     *
     * @param noNeedContext 这一次不会再有新材料（已确认的方案，或者用户按了「直接放行」）。
     *                      这时要明说「不要再要求补充信息」：提示词里已经没有那个出口了，
     *                      但还是得用一句人话说清楚——模型对「这里可以认输」的印象
     *                      往往来自它见过的别的提示词
     */
    private static String stepInstruction(PlanStep step, int total, boolean noNeedContext) {
        StringBuilder out = new StringBuilder();
        out.append("## 本步施工指令（第 ").append(step.index()).append(" 步，共 ")
                .append(total).append(" 步）\n");
        out.append("这一步做什么：")
                .append(step.goal().isEmpty() ? "（施工单上没写）" : step.goal()).append('\n');
        out.append("这一步涉及的文件：")
                .append(step.files().isEmpty()
                        ? "施工单上没写；只改这一次必须动的文件"
                        : String.join("、", step.files()))
                .append("\n**只改上面这些文件**：其余文件属于后面几步，这次不要顺手改。\n");
        if (!step.check().isEmpty()) {
            out.append("这一步怎么算做完：").append(step.check()).append('\n');
        }
        if (step.intermediate()) {
            out.append("\n这一步在施工单上被标成「中间态」：做完它整个项目**可能编译不过**，"
                    + "这是事先约定好的，不必为了让别处编过而改动这一步之外的地方。\n");
        }
        out.append("\n按这一步的范围给出补丁块；不要输出与这一步无关的改动。");
        if (noNeedContext) {
            out.append("\n按施工单做，不要再要求补充信息：需要的上下文已经给全了，"
                    + "做不到的部分用补丁或校验结果说话。");
        }
        return out.toString();
    }

    // ---------- 落盘与校验 ----------

    /**
     * 落盘 → 校验。<b>这里不回滚</b>——回滚由调用方按「这次失败该怎么收场」决定：
     * 单步时回到这一轮的进入点、分步时回到本步的进入点、走到头时回到整个运行起点。
     * 三种落点在这里分不清，硬塞进来就会变成三处各写一遍回滚。
     */
    private Applied applyAndVerify(Spec spec, PatchPlan plan, int round) {
        List<PatchApplier.FileChange> changes = applier.apply(plan);
        for (PatchApplier.FileChange change : changes) {
            log.info("{}", change.describe());
        }
        listener.filesApplied(round, changes);

        List<VerificationResult> results = verifyEverything(spec);
        listener.verificationFinished(round, results);
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
     * <p>注意这里**只负责给出结论，不碰回滚**：回滚在调用方只有一处
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

    // ---------- 回滚 ----------

    /**
     * 回到本步的进入点：只丢这一步的改动，已经做成的步骤留着。
     *
     * <p>这是「回滚先于重试」的落点。单步执行时进入点就是运行起点，
     * 所以老行为（每轮回滚到运行前）在这里自动成立，不需要另一条分支。
     */
    private void restoreStep(StepCheckpoint entry, PlanStep step, int round, boolean split, String reason) {
        if (split) {
            listener.stepRestored(step, round, reason);
        } else {
            listener.workspaceRestored(round, reason);
        }
        List<String> restored = entry.restore();
        log.warn("第 {} 步{}，已回滚 {} 个文件：{}", step.index(), reason, restored.size(),
                String.join(", ", restored));
    }

    /**
     * 这次运行走到头了，把磁盘和快照收干净。
     *
     * @param rollback 磁盘上有没有<b>这次运行留下的改动</b>。
     *                 问法只有一个：这一轮落过盘没有？
     *                 <ul>
     *                   <li>落过（校验失败、落盘炸了、最后一步是中间态）→ 一定有，撤</li>
     *                   <li>没落（模型还没给出补丁就被拦下：中断、喊缺料、补丁冲突用尽、轮次用尽）→
     *                       单步执行时磁盘仍等于运行起点，删个快照就行；
     *                       分步执行时前面几步的成果还在盘上，得撤</li>
     *                 </ul>
     *                 撤的时候顺带删掉快照：留着它，下一次运行会被「上一次还没处置」挡在门外。
     *                 不撤的时候也必须删——这一份什么都没兜住，留着只是拦人
     */
    private void closeRun(WorkspaceSnapshot snapshot, boolean rollback, int round, String reason) {
        if (snapshot == null) {
            if (rollback) {
                log.warn("{}，但快照未开启，无法自动回滚；请手工检查工作区", reason);
            }
            return;
        }
        if (rollback) {
            listener.workspaceRestored(round, reason);
            List<String> restored = snapshot.restore();
            log.warn("{}，已回滚 {} 个文件：{}", reason, restored.size(), String.join(", ", restored));
        }
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
        } catch (RuntimeException e) {
            log.warn("{}之后清理快照失败，请手工删除 {}：{}", reason, snapshot.directory(), e.getMessage());
        }
    }

    /**
     * 整次运行失败：先收干净磁盘，再给出结论。
     *
     * <p>「整个 run 回滚到起点」是施工单语义里刻意的取舍：已经做完的步骤一并丢弃。
     * 留下半份成果看起来更省，但下一次运行会在一个没人确认过的状态上开工，
     * 而那一份的锚点、编译结论、留档全都不在了。
     */
    private AgentResult fail(WorkspaceSnapshot snapshot, boolean rollback, int rounds,
                             List<PatchApplier.FileChange> changes,
                             List<VerificationResult> results, String detail) {
        closeRun(snapshot, rollback, rounds, "本次运行失败");
        return AgentResult.failed(rounds, changes, results, detail);
    }

    private AgentResult finish(int rounds, List<PatchApplier.FileChange> changes,
                               List<VerificationResult> results) {
        if (results.isEmpty() || results.stream().allMatch(VerificationResult::skipped)) {
            return AgentResult.unverified(rounds, changes, results,
                    "改动已落盘，但没有执行任何校验（未配置编译命令或已被 spec 关闭）");
        }
        return AgentResult.success(rounds, changes, results);
    }

    private VerificationResult firstFailure(List<VerificationResult> results) {
        return results.stream().filter(VerificationResult::failed).findFirst().orElse(null);
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

    // ---------- 解析与校验 ----------

    /**
     * 组装发给模型的用户消息。
     *
     * <p>方案拼在最后而不是最前：模型对结尾的内容印象更深，而「按这张图施工」
     * 正是本轮最需要它记住的事。
     *
     * @param noNeedContext 这一次不会再有新材料（已确认的方案，或者用户按了「直接放行」）。
     *                      老的那句话里「或者信息确实不足」是个隐藏的出口——它和提示词里那段
     *                      「缺料就直说」是一对，去了后一半就得连它一起去掉
     */
    private String userMessage(Spec spec, PlanReview approved, boolean noNeedContext) {
        String message = assembler.userMessage(spec, templates);
        if (approved == null || approved.render().isEmpty()) {
            return message;
        }
        String how = noNeedContext
                ? "除非遇到硬性障碍（要动目标清单之外的文件），否则按这张方案做，不要另起一套；"
                        + "按施工单做，不要再要求补充信息——该给的上下文已经给全了。"
                : "除非遇到硬性障碍（要动目标清单之外的文件、或者信息确实不足），"
                        + "否则按这张方案做，不要另起一套。";
        return message + "\n## 已确认的实现方案（已由人确认，请按它实现）\n" + how + "\n\n"
                + approved.render() + "\n";
    }

    /**
     * 续跑时回给模型的那一句。
     *
     * <p>两种语气不一样：「补了料」是告诉它新材料在哪、顺着往下做；「直接放行」要更强硬——
     * 用户已经表态不再补了，这时再喊缺就是白烧一轮。仍然允许它说「做不到哪一部分」，
     * 但不允许它再要东西。
     *
     * <p>有已确认方案时一律走强硬那一档：那份方案就是它这次能拿到的全部材料，
     * 系统提示词里也没了那个出口——再写一句「仍然缺就说出来」，等于自己把梯子搭回去。
     */
    private static String resumePrompt(boolean force, boolean confirmedPlan) {
        if (force || confirmedPlan) {
            return "你上一次要求补充信息，但没有更多材料了：目标文件清单之外的文件你改不了，"
                    + "也不会再有新的上下文。请用上面这些信息直接给出补丁；"
                    + "确实做不到的部分，说清是哪一处、为什么做不到，不要再要求补充信息。";
        }
        return "上面的「上下文依赖」与「目标文件」已经按你上一次的要求更新过（没有变化就是原来那些）。"
                + "请据此继续输出补丁。如果仍然缺，只输出 NEED_CONTEXT 开头的那几行（最多 3 行），"
                + "并且具体到你缺哪个文件。";
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

    /**
     * 目标文件清单变成路径。
     *
     * <p>快照和进入点都按它来——两处必须是同一份，不然「回滚」会漏掉文件。
     */
    private List<Path> targets(Spec spec) {
        return spec.targets().stream().map(pathResolver::resolve).toList();
    }

    /** 落盘与校验的成对结果，避免用可变字段在方法之间传值。 */
    private record Applied(List<PatchApplier.FileChange> changes, List<VerificationResult> results) {
    }

    /**
     * 一步的「进入点」：这一步开始前，目标文件的原文。
     *
     * <p><b>只在内存里</b>，不落盘。理由：它只服务于「本步重试前回滚」这一件事，用完即弃；
     * 写成磁盘目录的话，崩溃之后它就成了一个新版的「没处置的快照」，
     * 会和 {@link WorkspaceSnapshot} 的恢复判据打架（那边认的是「有清单就是可用快照」）。
     *
     * <p>覆盖的是**目标清单全部文件**，而不是这一步声明的 files：补丁的白名单是
     * {@code spec.targets()}，模型完全可能改到声明之外的文件（那属于偏离施工单），
     * 而引擎不该因为「它没按单子写」就失去回滚能力——回滚不全，
     * 后面每一轮的锚点都会对不上，那才是真正的连锁失败。
     */
    private static final class StepCheckpoint {

        private final SafePathResolver pathResolver;

        /** 进入点时的原文；值为 {@code null} 表示那时这个文件还不存在（回滚时删掉）。 */
        private final Map<Path, String> originals = new LinkedHashMap<>();

        private StepCheckpoint(SafePathResolver pathResolver) {
            this.pathResolver = pathResolver;
        }

        static StepCheckpoint of(SafePathResolver pathResolver, List<Path> files) {
            StepCheckpoint checkpoint = new StepCheckpoint(pathResolver);
            for (Path file : files) {
                checkpoint.originals.put(file, Files.isRegularFile(file)
                        ? ProjectFiles.read(file, pathResolver.relativize(file))
                        : null);
            }
            return checkpoint;
        }

        /** 把变过的文件写回进入点；没变过的连碰都不碰（免得白白改一遍修改时间）。 */
        List<String> restore() {
            List<String> restored = new ArrayList<>();
            for (Map.Entry<Path, String> entry : originals.entrySet()) {
                Path file = entry.getKey();
                String shown = pathResolver.relativize(file);
                String original = entry.getValue();
                String current = Files.isRegularFile(file) ? ProjectFiles.read(file, shown) : null;
                if (Objects.equals(original, current)) {
                    continue;
                }
                if (original == null) {
                    ProjectFiles.deleteIfExists(file, shown);
                } else {
                    ProjectFiles.writeAtomic(file, original, shown);
                }
                restored.add(shown);
            }
            return restored;
        }
    }
}
