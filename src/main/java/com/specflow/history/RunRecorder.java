package com.specflow.history;

import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.agent.ProgressMessages;
import com.specflow.env.EnvRegistration;
import com.specflow.exception.PatchConflictException;
import com.specflow.patch.PatchApplier;
import com.specflow.review.AcceptanceCoverage;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanStep;
import com.specflow.spec.ContextItem;
import com.specflow.spec.Spec;
import com.specflow.tests.CaseHowStage;
import com.specflow.tests.Refeed;
import com.specflow.tests.TestOutcome;
import com.specflow.verify.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
     * 这一轮回喂给开发的那几条失败用例（十五.6 第一条路）。
     *
     * <p>开工时就定下来了（它在提示词里），所以在这里存着、写记录时一并落档：
     * 「这一轮到底按什么在改」只有它答得出来，而实测里那一轮它一个字节都没改——
     * 不留这一段，事后谁也说不清那次为什么白跑。
     */
    private final Refeed refeed;

    /**
     * 这次运行定下来的完整施工单。
     *
     * <p>它和 {@link #steps} 不是一回事：那个是跑完之后按步攒的账（只记跑到了的步子），
     * 这个是开工时引擎报上来的那份图。留档里必须有它，续跑才不必为同一份单子再花一次调用。
     *
     * <p>没定过施工单时它是 {@code null}——那一次运行里引擎压根没报过单子（开工就被拒，
     * 或者连单步那一次都没走到），留档里于是<b>没有这一项</b>，与「老记录」同形；
     * 而不是写一个空数组，那会变成同一件事的两种说法。读的那一侧按「有没有」判
     * （见 {@code DevelopmentAgent.Resume}），两条路都吃得下。
     */
    private List<PlanStep> planSteps;

    /**
     * 施工单上那一步——单步执行时的全部内容。
     *
     * <p>只记「恰好一步」这一种：多于一步时每一步都有步级事件，账在 {@link #steps} 里攒着，
     * 这里不需要兜底；一步都没有（连施工单都没报过）时它也是 {@code null}。
     */
    private PlanStep singleStep;

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

    /**
     * 测试阶段的结论：这一次测试留档里最值钱的那一块。
     *
     * <p>它比时间线上那一行重得多：里面是产物路径、退出码和<b>整份失败清单</b>
     * （哪条用例、期望、实际、哪一类失败）。用户事后翻记录时只有它答得出
     * 「那几条到底怎么错的」——代码可能已经被改回去了，失败清单不会。
     *
     * <p>没跑过测试阶段（没有用例清单、或者运行没走到那一步）时它是 {@code null}，
     * 留档里于是没有这一项——而不是写一个空壳，那会变成「跑过但没失败」的另一种说法。
     */
    private TestOutcome tests;

    /**
     * 测试环境这一次的登记（十五.8 的第一件）。
     *
     * <p>它记的是<b>当时的事实</b>：起了哪些容器、有哪些卷、跑了哪几条 init/reset、
     * 环境是好是坏。清理靠它（按登记逆序清），事后翻记录的人也靠它——
     * 「那次测试是在一个什么环境里跑的」这个问题，只有它答得出来。
     *
     * <p>没声明环境、或者这次只跑了单元测试时它是 {@code null}，留档里于是没有这一项。
     */
    private EnvRegistration environment;

    /**
     * 这次运行<b>带着哪几条停用的用例</b>开工（谁、什么时候）。
     *
     * <p>它在开工那一刻就定下来了（界面把手上那份集合随请求发过来），所以照 {@link #refeed}
     * 的样子在构造时收着、写记录时一并落档。留档里没有这一栏，事后的人就只能看到
     * 「通过率的分母比清单短」——而那是这一批要回答的第一个问题：分母里少了哪几条、谁停的。
     */
    private final List<RunRecord.CaseSwitch> caseSwitches;

    /**
     * <b>第二段</b>补完之后的用例清单（见 {@code CaseHowStage}）；没走到第二段时是 {@code null}。
     *
     * <p>它和 {@link #approved} 的差别只有一栏：「怎么测」是看了这次改动的 diff 之后补的，
     * 而期望、分级、验收标准一个字都不许改（机器逐字核过，见 {@code CaseHowStage}）。
     * 留档里留下的必须是补完之后那一份：界面上的 chip 要说得出
     * 「这条的期望来自第一段、怎么测来自第二段」。
     */
    private List<PlanReview.TestCase> refinedCases;

    private RunRecorder(RunStore store, AgentListener delegate, Spec spec, PlanReview approved,
                        Refeed refeed, List<RunRecord.CaseSwitch> caseSwitches) {
        this.store = store;
        this.delegate = delegate;
        this.spec = spec;
        this.approved = approved;
        this.refeed = refeed == null ? Refeed.none() : refeed;
        this.caseSwitches = caseSwitches == null ? List.of() : List.copyOf(caseSwitches);
        LocalDateTime now = LocalDateTime.now();
        this.id = STAMP.format(now);
        this.startedAt = now.toString();
    }

    /**
     * @param delegate 转发目标；CLI 传 {@link AgentListener#NOOP}
     */
    public static RunRecorder start(RunStore store, Spec spec, PlanReview approved,
                                    AgentListener delegate) {
        return start(store, spec, approved, delegate, Refeed.none());
    }

    /**
     * 带「这一轮回喂了什么」的那一版：界面上点「下一轮」时走它（十五.6 第一条路）。
     *
     * @param refeed 这次回喂给开发的失败用例与那段原文；{@link Refeed#none()} 表示不是回喂
     */
    public static RunRecorder start(RunStore store, Spec spec, PlanReview approved,
                                    AgentListener delegate, Refeed refeed) {
        return new RunRecorder(store, delegate, spec, approved, refeed, List.of());
    }

    /**
     * 带「这次带着哪几条停用的用例」的那一版：界面提交的运行都走它。
     *
     * <p>单独一个重载而不是改掉上面那个签名：CLI 那条路（{@code RunCommand} / {@code ContinueCommand}）
     * 没有停用这个概念，让它多传一个空表只是给它添一处没意义的参数。
     *
     * @param caseSwitches 开工时那份停用流水（谁、什么时候、哪几条）
     */
    public static RunRecorder start(RunStore store, Spec spec, PlanReview approved,
                                    AgentListener delegate, Refeed refeed,
                                    List<RunRecord.CaseSwitch> caseSwitches) {
        return new RunRecorder(store, delegate, spec, approved, refeed, caseSwitches);
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
        this.planSteps = List.copyOf(resolved);
        // 单步执行（现生成的施工单核不过、或者压根没跑过检查）时引擎**不发步级事件**，
        // 于是留档里一步都没有。留着这一份，写记录时才能替它补上一条（见 stepsOf）
        this.singleStep = resolved.size() == 1 ? resolved.get(0) : null;
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

    /**
     * 测试阶段开始了。
     *
     * <p>只转发、不落档：这一行说的是「正在进行」，而留档记的是已经发生的事——
     * 把它记进去，事后翻记录的人会看到一条没有下文的「测试进行中」。
     * 但它必须转发：这个类挡在 Agent 与界面之间，漏掉一个回调，
     * 界面就永远收不到那一句——而「实测五分钟没动静」正是它要解释的那件事。
     */
    @Override
    public void testsStarted(int cases) {
        delegate.testsStarted(cases);
    }

    /**
     * 测试阶段跑完了。
     *
     * <p>记两笔：时间线上那<b>一行</b>（什么时候、成了没有、失败几条），
     * 以及留档里那<b>一整块</b>（失败清单）。前者给人扫，后者给人查——
     * 一行摘要里塞不下四条失败用例的期望与实际。
     */
    @Override
    public void testsFinished(TestOutcome outcome) {
        this.tests = outcome;
        record(round, ProgressMessages.levelOf(outcome), ProgressMessages.testsFinished(outcome));
        delegate.testsFinished(outcome);
    }

    /**
     * 测试环境变了（起好了 / 重置过 / 坏掉了 / 收掉了）。
     *
     * <p>每次都覆盖那一份：登记说的是<b>当时的全貌</b>，而留档要回答的是「跑完那一刻环境是什么样」。
     * 攒一串历史只会让「留档里那个 environment 是哪一次」变成要猜的事。
     *
     * <p>时间线上那一行照发：环境这一摊慢得看不出来在干什么，而它是这次运行的一部分。
     */
    @Override
    public void environmentChanged(EnvRegistration registration) {
        this.environment = registration;
        record(round, ProgressMessages.levelOf(registration),
                ProgressMessages.environmentChanged(registration));
        delegate.environmentChanged(registration);
    }

    @Override
    public void workspaceRestored(int round, String reason) {
        record(round, "warn", ProgressMessages.restored(reason));
        delegate.workspaceRestored(round, reason);
    }

    /**
     * <b>第二段</b>补好了每一条的「怎么测」。
     *
     * <p>记两笔：清单换成本次运行真正用的那一份（期望与分级一个字都没改，机器核过），
     * 以及那句话进时间线——「补了几条、哪几条没补上、有没有出现过想改期望被拦下」
     * 都要留在留档里。不记的话，事后翻记录只看得到「怎么测」这一栏空着，
     * 分不清是没验、还是第二段没补成功。
     *
     * <p><b>交给它的用例是「活着的」那几条</b>（停用的不进测试阶段，也就没有「怎么测」要补），
     * 而录制器手里那份 {@code approved} 是<b>完整</b>的。所以这里按编号把这一栏并回去
     * （见 {@code CaseHowStage.merge}），并把并完的那一份转发给下游：留档与界面于是都拿完整清单——
     * 少并这一步，被停用的用例会从留档里消失，「恢复」再也按不回来（那是不可逆的删除）。
     */
    @Override
    public void casesRefined(List<PlanReview.TestCase> cases, String note) {
        if (cases != null) {
            this.refinedCases = CaseHowStage.merge(
                    approved == null ? List.of() : approved.cases(), cases);
        }
        if (note != null && !note.isBlank()) {
            record(round, "info", note);
        }
        delegate.casesRefined(this.refinedCases != null ? this.refinedCases : cases, note);
    }

    @Override
    public void finished(AgentResult result) {
        // 拒绝开工的那一次（施工单已经对不上现在的清单）不留档。
        // 判据是 RunStore.suspended() 只看**最新那一条**记录：留档就会把「挂着等人补料」
        // 的那条挤下去——用户刚被告知「把文件加回清单再来一次」，那时界面上已经找不到
        // 可接着跑的那次了。而这次拒绝本来也没有可复盘的东西：没调模型、没碰磁盘、
        // 连施工单都没见过（见 DevelopmentAgent.staleSchedule）。
        // 代价是这一个状态在**运行历史里永远见不到**——它只出现在这次运行的结果面板上。
        // 要改这个取舍，得先想清楚 suspended() 怎么办
        if (result.status() != AgentResult.Status.PLAN_OUTDATED) {
            persist(result);
        }
        delegate.finished(result);
    }

    // ---------- 内部 ----------

    private void record(int round, String level, String text) {
        timeline.add(new RunRecord.Line(round, level, text));
    }

    private void persist(AgentResult result) {
        // 清单用第二段补完之后那一份（有的话）：期望与验收标准与冻结的那份逐字相同，
        // 差别只有「怎么测」——而那一栏正是第二段唯一的产物，界面要说得出来它是哪来的。
        // 它已经是**完整**那份（见 casesRefined：第二段的产物按编号并回了冻结清单）：
        // 拿只有活着的用例那份当留档，被停用的用例就会从留档里消失，停用也就恢复不了了
        List<PlanReview.TestCase> cases = refinedCases != null ? refinedCases
                : (approved == null ? List.of() : approved.cases());
        RunRecord record = new RunRecord(id, startedAt, result.status().name(), spec.template(),
                spec.prompt(), spec.acceptance(), contextOf(spec), spec.trace().requirementId(),
                spec.targets(), result.attempts(), result.detail(),
                approved == null ? List.of() : approved.missing(),
                changesOf(result.changes()), stepsOf(result), planSteps, stepsSource,
                cases, tests, environment,
                // 判决、收场、重新生成过哪几份产物，都是**跑完之后**人写的（见 RunStore.judge /
                // settle / regenerated）；这里给 null，留档里于是没有这几项——
                // 「没人判过」和「判了一个空表」是两件事
                null, null, null,
                List.copyOf(timeline),
                // 覆盖核对：拿需求里的验收标准与这次那份清单数一遍。它和判决一样是**算出来的**，
                // 跟着记录走，界面在通过率旁边固定显示的那几个计数就是它。
                // 停用的用例要一起传进去：它们不参与分母，而「管这条验收标准的用例被停用了」
                // 与「一条用例都没覆盖」是两句不同的话（见 AcceptanceCoverage.Report）
                AcceptanceCoverage.check(spec.acceptance(), cases, disabledIn()),
                refeed.present() ? refeed : null,
                // 「它没有改动」：只有这一轮真回喂了才给这个结论（见 Refeed.unchanged）
                refeed.present() ? Refeed.unchanged(refeed, result.changes()) : null,
                // 谁在什么时候停用了哪几条（开工那一刻的那份集合，见 caseSwitches）
                caseSwitches.isEmpty() ? null : caseSwitches);
        try {
            store.save(record);
            log.debug("运行记录已写入 {}", store.directory().resolve(id));
        } catch (RuntimeException e) {
            log.warn("运行记录写入失败，不影响本次结果：{}", e.getMessage());
        }
    }

    /** 这次开工时停用着的那几条用例编号。 */
    private Set<Integer> disabledIn() {
        return RunRecord.CaseSwitch.disabledIn(caseSwitches);
    }

    private static List<RunRecord.Change> changesOf(List<PatchApplier.FileChange> changes) {
        return changes.stream()
                .map(change -> new RunRecord.Change(change.relative(), change.created(),
                        change.bytes(), change.diff()))
                .toList();
    }

    /**
     * 这次运行留下哪些步级记录。
     *
     * <p>分步执行时每一步都有 {@code stepStarted} / {@code stepFinished}，账在 {@link #steps} 里攒好了。
     * <b>单步执行不发步级事件</b>（那正是它和分步的差别），于是留档里一步都没有：
     * 历史详情里看不到「这一步做了什么、几轮、改了哪些文件」，而分步看得到——
     * 同一件事两种说法，事后翻记录的人只能靠猜。所以这里替它补一条：
     * 步子用引擎报的那一步（连 goal 一起），轮次与改动直接来自运行结果。
     *
     * <p>一步都没跑（开工就被叫停）时不补：那一步压根没开始，记成「失败」比空着更容易让人误判。
     * 被判「上一次的改动还没处置」而拒开的运行属于这一类——它连施工单都没见过。
     */
    private List<RunRecord.Step> stepsOf(AgentResult result) {
        if (!steps.isEmpty()) {
            return List.copyOf(steps);
        }
        if (singleStep == null || result.attempts() == 0) {
            return List.of();
        }
        return List.of(new RunRecord.Step(singleStep.index(), singleStep.goal(),
                singleStep.intermediate(), singleStepState(result), result.attempts(),
                changesOf(result.changes())));
    }

    /**
     * 单步执行的步态：从这次运行的终态倒推。
     *
     * <p>只可能是两档。中间态不在其中——它是<b>施工单上的约定</b>（「这一步做完项目允许编不过」），
     * 单步没有单子可约定；而「人工中断 / 模型说缺料 / 环境问题」都意味着这一步<b>没做成</b>、
     * 磁盘也已经回滚，记成失败是实话，具体原因在同一条记录的时间线上。
     */
    private static String singleStepState(AgentResult result) {
        return switch (result.status()) {
            case SUCCESS, SUCCESS_UNVERIFIED -> AgentListener.StepState.SUCCESS.name();
            default -> AgentListener.StepState.FAILED.name();
        };
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
