package com.specflow.history;

import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.agent.ProgressMessages;
import com.specflow.exception.PatchConflictException;
import com.specflow.patch.PatchApplier;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanStep;
import com.specflow.spec.ContextItem;
import com.specflow.spec.Spec;
import com.specflow.verify.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 一边把进度转给下游，一边把它记成一份运行留档。
 *
 * <p>它是装饰器：拿到一个下游监听器（界面推送、或者什么都不做的空实现），
 * 每个回调先记进时间线，再原样转发。这样「落盘」和「实时推送」两件事
 * 互不知道对方存在，CLI 和 Web 也都能用同一套录制。
 *
 * <p>记录在 {@link #finished} 时一次写出，而不是边跑边追加：
 * 一次运行的记录是自包含的，写一份完整文件比维护一个可追加的格式简单得多，
 * 也不会出现「进程被杀留下一份半截记录」的中间态。
 *
 * <p>写入失败只记一条警告，<b>不影响已经完成的运行</b>——
 * 磁盘满了不该让一次成功的改动变成失败。
 */
public final class RunRecorder implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(RunRecorder.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** 内联上下文在留档里最多保留多少个字；够认出是哪一段，又不至于把记录撑成几 MB。 */
    private static final int MAX_TEXT_SOURCE = 200;

    private final RunStore store;
    private final AgentListener delegate;
    private final Spec spec;
    private final PlanReview approved;
    private final String id;
    private final String startedAt;
    private final List<RunRecord.Line> timeline = new ArrayList<>();
    private final List<RunRecord.Step> steps = new ArrayList<>();
    private String stepsSource;

    /**
     * 正在跑的那一步，以及它自己的账。
     *
     * <p>步级的账（几轮、改了哪些文件）必须单独攒：一次运行的改动是<b>按步累积</b>的，
     * 事后想回答「第 3 步当时动了什么」，只有总账是答不出来的。
     */
    private PlanStep openStep;
    private int openRounds;
    private final List<PatchApplier.FileChange> openChanges = new ArrayList<>();

    /**
     * 最近一次看到的轮次。
     *
     * <p>步级事件（开始/结束/回滚）本身不带轮次——「这一步在第几轮开始」不是它们要回答的问题。
     * 但时间线是按轮分组显示的，所以每条步级记录都得挂在某一轮上，
     * 挂最近的那一轮最接近事实。
     */
    private int round;

    private RunRecorder(RunStore store, AgentListener delegate, Spec spec, PlanReview approved) {
        this.store = store;
        this.delegate = delegate;
        this.spec = spec;
        this.approved = approved;
        LocalDateTime now = LocalDateTime.now();
        this.id = STAMP.format(now);
        this.startedAt = now.toString();
    }

    /**
     * @param delegate 转发目标；CLI 传 {@link AgentListener#NOOP}
     */
    public static RunRecorder start(RunStore store, Spec spec, PlanReview approved,
                                    AgentListener delegate) {
        return new RunRecorder(store, delegate, spec, approved);
    }

    // ---------- 记录并转发 ----------

    @Override
    public boolean cancelled() {
        return delegate.cancelled();
    }

    @Override
    public void stepsResolved(List<PlanStep> resolved, AgentListener.StepsSource source,
                              int probeCalls) {
        this.stepsSource = source.name();
        record(round, "info", ProgressMessages.stepsResolved(resolved, source, probeCalls));
        delegate.stepsResolved(resolved, source, probeCalls);
    }

    @Override
    public void stepStarted(PlanStep step) {
        openStep = step;
        openRounds = 0;
        openChanges.clear();
        record(round, "info", ProgressMessages.stepStarted(step));
        delegate.stepStarted(step);
    }

    @Override
    public void stepFinished(PlanStep step, AgentListener.StepState state) {
        if (openStep != null) {
            steps.add(new RunRecord.Step(step.index(), step.goal(), step.intermediate(),
                    state.name(), openRounds, changesOf(openChanges)));
        }
        openStep = null;
        openChanges.clear();
        record(round, state == AgentListener.StepState.SUCCESS ? "info" : "warn",
                ProgressMessages.stepFinished(step, state));
        delegate.stepFinished(step, state);
    }

    @Override
    public void stepRestored(PlanStep step, int round, String reason) {
        // 本步已回滚到进入点：这一轮写进去的东西已经不在盘上了，
        // 记账不能留着它，否则留档里会多出一份「改过但又没了」的差异
        openChanges.clear();
        record(round, "warn", ProgressMessages.stepRestored(step, reason));
        delegate.stepRestored(step, round, reason);
    }

    @Override
    public void roundStarted(int round) {
        this.round = round;
        if (openStep != null) {
            openRounds++;
        }
        record(round, "info", ProgressMessages.roundStarted(round));
        delegate.roundStarted(round);
    }

    @Override
    public void planRejected(int round, PatchConflictException failure) {
        record(round, "warn", ProgressMessages.planRejected(failure));
        delegate.planRejected(round, failure);
    }

    @Override
    public void filesApplied(int round, List<PatchApplier.FileChange> changes) {
        openChanges.addAll(changes);
        record(round, "info", ProgressMessages.filesApplied(changes));
        delegate.filesApplied(round, changes);
    }

    @Override
    public void verificationFinished(int round, List<VerificationResult> results) {
        for (VerificationResult result : results) {
            record(round, ProgressMessages.levelOf(result), ProgressMessages.verified(result));
        }
        delegate.verificationFinished(round, results);
    }

    @Override
    public void workspaceRestored(int round, String reason) {
        record(round, "warn", ProgressMessages.restored(reason));
        delegate.workspaceRestored(round, reason);
    }

    @Override
    public void finished(AgentResult result) {
        persist(result);
        delegate.finished(result);
    }

    // ---------- 内部 ----------

    private void record(int round, String level, String text) {
        timeline.add(new RunRecord.Line(round, level, text));
    }

    private void persist(AgentResult result) {
        RunRecord record = new RunRecord(id, startedAt, result.status().name(), spec.template(),
                spec.prompt(), spec.acceptance(), contextOf(spec), spec.trace().requirementId(),
                spec.targets(), result.attempts(), result.detail(),
                approved == null ? List.of() : approved.missing(),
                changesOf(result.changes()), List.copyOf(steps), stepsSource,
                List.copyOf(timeline));
        try {
            store.save(record);
            log.debug("运行记录已写入 {}", store.directory().resolve(id));
        } catch (RuntimeException e) {
            log.warn("运行记录写入失败，不影响本次结果：{}", e.getMessage());
        }
    }

    private static List<RunRecord.Change> changesOf(List<PatchApplier.FileChange> changes) {
        return changes.stream()
                .map(change -> new RunRecord.Change(change.relative(), change.created(),
                        change.bytes(), change.diff()))
                .toList();
    }

    /**
     * 记下这次运行带了哪些上下文。
     *
     * <p>长文本要截断：内联上下文常常是一整段建表语句、几千字，
     * 原样存进去会让每一次运行都留下一份几 MB 的记录——而历史列表要在界面上列出来，
     * 「翻一次历史」就变成了读几十兆磁盘。
     *
     * <p>截断而不是整条丢掉：留档要回答的是「这次依据的是什么」，
     * 前 200 字加上总字数够还原出它是哪一段；整条丢掉则会让
     * 「它当时到底看没看到那份表结构」重新变成无从查证。
     */
    private static List<ContextItem> contextOf(Spec spec) {
        return spec.context().stream()
                .map(item -> item.text() == null
                        ? item
                        : ContextItem.of(item.name(), item.ref(), shorten(item.text()), item.note()))
                .toList();
    }

    /** 超长时留下前 {@value #MAX_TEXT_SOURCE} 字，并注明原文共多少字。 */
    private static String shorten(String text) {
        if (text.length() <= MAX_TEXT_SOURCE) {
            return text;
        }
        return text.substring(0, MAX_TEXT_SOURCE) + "…（共 " + text.length() + " 字）";
    }
}
