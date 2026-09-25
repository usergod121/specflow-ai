package com.specflow.history;

import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.agent.ProgressMessages;
import com.specflow.exception.PatchConflictException;
import com.specflow.patch.PatchApplier;
import com.specflow.review.PlanReview;
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
    public void roundStarted(int round) {
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
        List<RunRecord.Change> changes = result.changes().stream()
                .map(change -> new RunRecord.Change(change.relative(), change.created(),
                        change.bytes(), change.diff()))
                .toList();
        RunRecord record = new RunRecord(id, startedAt, result.status().name(), spec.template(),
                spec.prompt(), spec.acceptance(), contextOf(spec), spec.trace().requirementId(),
                spec.targets(), result.attempts(), result.detail(),
                approved == null ? List.of() : approved.missing(),
                changes, List.copyOf(timeline));
        try {
            store.save(record);
            log.debug("运行记录已写入 {}", store.directory().resolve(id));
        } catch (RuntimeException e) {
            log.warn("运行记录写入失败，不影响本次结果：{}", e.getMessage());
        }
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
