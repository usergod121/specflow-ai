package com.specflow.tests;

import com.specflow.env.TestEnvironment;
import com.specflow.exception.SpecflowException;
import com.specflow.history.RunRecord;
import com.specflow.history.RunStore;
import com.specflow.project.ProjectConfig;
import com.specflow.session.Session;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.util.SafePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 收场：一次运行（或者一个会话）跑完之后，把三摊东西各自收干净，并把人的选择写进留档（十五.8）。
 *
 * <p><b>为什么单独成类。</b>「接受」和「中断」不只在界面上有：CLI 也有 {@code accept} /
 * {@code rollback}。收场要做的四件事（留或是撤文件、删快照、删测试产物、清环境数据、落档）
 * 如果各写一遍，迟早有一边少做一件——而少的那一件恰好可能是「产物没删」或者「库没清」，
 * 两者都不是当场看得出来的（下一次跑测试时才会以「数据不干净」的面目出现）。
 * 所以两个入口共用这一个实现，差别只有 {@link Choice} 一个参数。
 *
 * <p><b>三摊东西为什么要分开收</b>（十五.8 的口径）：
 * <ul>
 *   <li><b>产品文件</b>——按 run 级快照留或是撤，这是唯一一件「做错了会丢用户的代码」的事，
 *       所以它是<b>前置条件</b>：它没成，就不往下走，也不写收场留档（工作区还在半途，
 *       说「已接受」是假话），用户可以重试；</li>
 *   <li><b>测试产物</b>——{@code tools/<时间戳>/}，留档里记着哪几份（跑的那一份 + 重新生成过的）；</li>
 *   <li><b>环境数据</b>——跑 {@code reset}，把库/缓存恢复到一个已知状态。
 *       它刻意<b>不</b>叫回滚：回滚是「文件回到运行前」，而数据只需要「下次不是一个脏起点」。
 *       容器<b>不动</b>（十五.5：容器常驻复用）——只有环境坏了、关项目、用户手动才 {@code down -v}。</li>
 * </ul>
 *
 * <p><b>后两件为什么是 best-effort。</b>它们发生在「用户刚做了决定」之后，而那个决定已经生效了。
 * 为一条清理命令把整个请求变成 500，用户会以为自己的决定没生效——而它其实生效了。
 * 收不掉的如实报出来（十五.9：清理做不到 100%），不假装收干净了。
 *
 * <p><b>会话的三个动作走同一份实现</b>（§19）。会话（N 轮）和单次运行的差别只有「动几份快照、
 * 落几轮的档」，四件事的顺序、前置条件、best-effort 的口径一模一样：
 * <ul>
 *   <li>{@link Choice#ACCEPT} 接受：留文件、删快照、删产物、清数据、整个会话定稿；</li>
 *   <li>{@link Choice#INTERRUPT} <b>中断并回到会话最初</b>：撤到<b>会话最开始</b>，
 *       然后收摊（会话到此为止）——会话的出口有两个，它是回滚的那一个；</li>
 *   <li>{@link Choice#UNDO_ROUND} 撤回本轮：撤到<b>上一轮结束时</b>，会话还开着
 *       （它是<b>唯一</b>的一步撤销）。</li>
 * </ul>
 *
 * <p><b>「撤回整个会话」为什么没了</b>（用户 2026-10-03 拍板）：它和中断在文件上做的
 * 本来就是同一件事（都回到会话最开始、都清环境数据、都删测试产物、都留档），
 * 差别只有<b>会话要不要接着跑</b>。把这一条差别交给用户在一个几乎同名的按钮上猜，
 * 代价是「想接着跑的人把整个会话扔掉、想收工的人以为自己又开了一轮」——
 * 而这两个后果都不该由一次点击的措辞来决定。现在只剩一个动作，
 * 按钮上就写着它的全部后果：<b>中断并回到会话最初</b>（会话到此为止，想重来就再点「运行」）。
 */
public final class Teardown {

    private static final Logger log = LoggerFactory.getLogger(Teardown.class);

    /** 用户把这次运行（或者这个会话）怎么了结的（十五.8 的两种收场 + §19 的一步撤销）。 */
    public enum Choice {

        /** 接受：磁盘上的改动留着。 */
        ACCEPT(RunRecord.Settlement.ACCEPT),
        /**
         * 中断并回到会话最初：文件按快照回到这次运行（会话）开始前，会话到此为止。
         *
         * <p>会话里它撤的是<b>整个会话</b>（磁盘上挂着的每份快照都撤掉，于是回到会话最开始）；
         * 单次运行时撤的就是那一次运行。同一个动作，两种范围——范围由「磁盘上挂着谁」决定，
         * 不需要用户选。
         */
        INTERRUPT(RunRecord.Settlement.INTERRUPT),
        /** 撤回本轮：只回滚最后一轮，文件回到上一轮结束时的样子，会话还开着。 */
        UNDO_ROUND(RunRecord.Settlement.UNDO_ROUND);

        private final String recorded;

        Choice(String recorded) {
            this.recorded = recorded;
        }

        /** 留档里那个值。它和枚举名同名，但留档里是<b>字符串</b>（见 RunRecord.Settlement）。 */
        public String recorded() {
            return recorded;
        }
    }

    /**
     * 这一次收场的结果。
     *
     * @param settled   有没有东西可收场。{@code false} = 磁盘上本来就没有待处置的改动，
     *                  这次什么都没做（收场是幂等的：点第二次不该动任何东西）
     * @param choice    走的是哪条收场
     * @param files     按快照恢复的文件个数（接受时是 0：那一侧什么都不用动）
     * @param artifacts 删掉的测试产物目录（相对项目根）
     * @param reset     环境数据重置<b>成功</b>了。没声明环境、或者没初始化过时也是 {@code false}——
     *                  那种情况下没有可重置的东西，原始原因（如果有）在 {@code problems} 里
     * @param problems  没做成的那些事（原话）。空表示这一趟都干净
     * @param rounds    这一次处置覆盖了会话的<b>几轮</b>；单次运行（不属于任何会话）时是 0。
     *                  它只用来把那句话说准（「整个会话 3 轮」）——不写的话，
     *                  用户从回音里看不出自己刚接受的是三轮还是一次
     */
    public record Done(boolean settled, Choice choice, int files, List<String> artifacts,
                       boolean reset, List<String> problems, int rounds) {

        public Done {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** 给界面与命令行的一行。收场的历史结论还有一份在留档里（见 {@code RunRecord.Settlement}）。 */
        public String summarize() {
            if (!settled) {
                return "没有待处置的改动：这次收场什么都没做";
            }
            // 会话和单次运行是两件事，说法必须分得开：一遍「已接受」在 N 轮的会话里
            // 到底结掉了几轮，用户只能从这句话看出来
            String scope = rounds > 0 ? "整个会话 " + rounds + " 轮" : "这次运行";
            StringBuilder out = new StringBuilder(switch (choice) {
                case ACCEPT -> "已接受：" + scope + "的改动留在磁盘上";
                // 会话里的中断就是「回到会话最初」：文案与按钮同一句话，
                // 用户点完才知道自己刚才撤到的是会话最开始，而不是最后一轮的起点
                case INTERRUPT -> rounds > 0
                        ? "已中断并回到会话最初：恢复 " + files + " 个文件到会话最开始的样子"
                        : "已中断：恢复 " + files + " 个文件到这次运行开始前的样子";
                case UNDO_ROUND -> "已撤回本轮：恢复 " + files + " 个文件到上一轮结束时的样子";
            });
            out.append("；删掉快照");
            out.append(artifacts.isEmpty() ? "（这次没有测试产物）"
                    : "与 " + artifacts.size() + " 份测试产物");
            if (reset) {
                out.append("；环境数据已重置（容器留着复用）");
            } else if (problems.isEmpty()) {
                // 只有「确实没有可重置的数据」才说这一句。收不掉的那几种情况在下面那段里逐条说，
                // 这里再说一句「没有需要重置的环境数据」就是假话（它明明有，只是没清成）
                out.append("；没有需要重置的环境数据");
            }
            if (choice == Choice.UNDO_ROUND) {
                out.append("。会话还开着：接着跑就点「下一轮」");
            } else if (choice == Choice.INTERRUPT && rounds > 0) {
                // 「结束会话」这件事必须在回音里说出来：不说的话，用户以为它和「撤回本轮」一样
                // 还开着，接着点「下一轮」时才发现自己已经开了一个新会话
                out.append("。会话到此为止：想重新来过就再点「运行」开一个新会话");
            }
            if (!problems.isEmpty()) {
                out.append("。没收掉的：").append(String.join("；", problems));
            }
            return out.toString();
        }
    }

    private Teardown() {
    }

    /**
     * 磁盘上还没处置的快照（按时间从早到晚）。
     *
     * <p>「有待处置的改动」这一个判据只有这一处：界面上的按钮、引擎的门禁、CLI 的
     * {@code accept}/{@code rollback} 都用它。各判一套的后果是「界面说有、CLI 说没有」，
     * 而用户只能靠猜哪一边对。
     */
    public static List<WorkspaceSnapshot> waiting(Path projectRoot, ProjectConfig project) {
        SafePathResolver resolver = new SafePathResolver(projectRoot);
        return WorkspaceSnapshot.undisposed(resolver, resolver.resolve(project.snapshot().dir()));
    }

    /** 这个项目的快照根目录（会话要拿它把「哪一轮拍的那份快照」对上去）。 */
    public static Path snapshotRoot(Path projectRoot, ProjectConfig project) {
        SafePathResolver resolver = new SafePathResolver(projectRoot);
        return resolver.resolve(project.snapshot().dir());
    }

    /**
     * 收场：文件与快照 → 测试产物 → 环境数据 → 收场落档。
     *
     * <p>磁盘上挂着一个<b>开着的会话</b>时，这一次处置是冲着整个会话去的（§19）：
     * N 轮的改动一起定稿或一起撤回，快照一份不剩。不这么做的话，用户点一次「接受」
     * 只结掉最后一轮，前面几轮的快照还挂在那儿继续挡着下一次运行——
     * 「一次处置」就成了「N 次处置」，而用户点第二下时根本不知道还有东西没结。
     *
     * @param environment 这个项目的测试环境；没有环境（没写 {@code env.yaml}）时一次进程都不起
     * @throws SpecflowException 文件这一步没做成（工作区还在半途）。那时<b>不</b>写收场留档：
     *                           改动还挂着没处置，说「已接受 / 已中断」是假话
     */
    public static Done settle(Path projectRoot, ProjectConfig project, RunStore store,
                              TestEnvironment environment, Choice choice) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Session session = store.session(snapshotRoot(root, project)).orElse(null);
        if (session != null && !session.rounds().isEmpty()) {
            return settleSession(root, project, store, environment, session, choice);
        }
        return settleSingle(root, project, store, environment, choice);
    }

    /**
     * 撤回本轮：文件回到<b>上一轮结束时</b>的样子，会话还开着。
     *
     * <p>它是<b>唯一</b>的一步撤销（「撤回整个会话」已经和中断合成一个动作，见类注释）。
     * 留下的这一步与中断的分工：中断是会话的出口（撤完就不再往下跑了），
     * 撤回本轮只是退一步——用户想的是「这一轮不算，我再来一遍」，
     * 而不是「这件事不做了」。把它也做成中断，等于逼人把整个会话扔掉重新开。
     *
     * @throws IllegalStateException 没有开着的会话，或者最新那一轮没有可撤的东西
     *                               （它自己回滚了 / 已经撤过了）
     */
    public static Done undoRound(Path projectRoot, ProjectConfig project, RunStore store,
                                 TestEnvironment environment) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Session session = store.session(snapshotRoot(root, project)).orElse(null);
        if (session == null) {
            throw new IllegalStateException("现在没有开着的会话：没有可撤回的东西");
        }
        if (!session.canUndoRound()) {
            // 拦在开工之前：不拦的话它会去撤<b>上一轮</b>那份快照，而用户点的是「撤回本轮」
            throw new IllegalStateException(session.undoRoundWhy());
        }
        return settleSession(root, project, store, environment, session, Choice.UNDO_ROUND);
    }

    // ---------- 会话：一次处置覆盖 N 轮 ----------

    /**
     * 会话级的收场：动哪些快照、删哪几轮的产物、哪几轮落什么档。
     *
     * <p>快照那一摊取的是<b>磁盘上挂着的那几份</b>，不是「按会话算出来的那几份」：
     * 两者正常时一模一样，而万一多出一份（进程死在半路留下的、没有任何留档的残骸），
     * 按磁盘取能把这一摊一起收干净——只认自己那几份的话，用户点完「接受」还是开不了工，
     * 而界面上那个待处置面板此时已经被会话视图顶掉了，他连在哪儿处置都找不到。
     */
    private static Done settleSession(Path root, ProjectConfig project, RunStore store,
                                      TestEnvironment environment, Session session, Choice choice) {
        List<WorkspaceSnapshot> waiting = waiting(root, project);
        List<WorkspaceSnapshot> target = waiting;
        if (choice == Choice.UNDO_ROUND) {
            String only = session.current().map(Session.Round::snapshot).orElse(null);
            target = waiting.stream().filter(one -> nameOf(one).equals(only)).toList();
            if (target.isEmpty()) {
                throw new IllegalStateException(session.undoRoundWhy().isEmpty()
                        ? "找不到这一轮的快照：它可能已经被撤掉了" : session.undoRoundWhy());
            }
        }
        // 接受什么都不用动文件；三种回滚都要先恢复
        int files = restore(target, choice);
        for (WorkspaceSnapshot snapshot : target) {
            snapshot.discard();
        }

        List<String> problems = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        // 读一次、用两次（删产物、落档）：同一份记录读两遍不止是浪费，
        // 还会在「两次读之间文件变了」时让两件事依据两份不同的留档
        List<RunRecord> scoped = new ArrayList<>();
        for (String recordId : settledRounds(session, choice)) {
            scoped.add(loadQuietly(store, recordId));
        }
        for (RunRecord record : scoped) {
            deleted.addAll(deleteArtifacts(root, record, problems));
        }
        boolean reset = resetData(environment, problems);
        // 不属于这个会话的快照（进程死在半路留下的残骸）：这一次不碰它们，但要报出来——
        // 报成「都收干净了」而它还在，用户下一次运行会被一句「上一次的改动还没处置」挡住，
        // 而那时他不知道为什么（会话视图已经把待处置面板顶掉了）
        List<String> leftover = waiting.stream().map(Teardown::nameOf)
                .filter(name -> !session.owns(name))
                .toList();
        if (!leftover.isEmpty()) {
            problems.add("还有 " + leftover.size() + " 份快照不属于这个会话，这次没动它们（"
                    + String.join("、", leftover) + "）：收完这个会话再单独处置");
        }
        // 落档放在最后：前面三件都做完了，这一栏才是事实。它是 best-effort，理由见类注释
        for (RunRecord record : scoped) {
            settleRecord(store, record == null ? "" : record.id(), choice, record, problems);
        }
        return new Done(true, choice, files, deleted, reset, problems, session.rounds().size());
    }

    /**
     * 这一步要落档的是哪几轮。
     *
     * <p>规则只有两条：<b>撤回本轮只动它自己那一轮</b>，<b>接受与中断覆盖所有还没收场的轮次</b>
     * ——但<b>不覆盖</b>已经写下的撤回：一轮被撤回过就是撤回过，后来接受整个会话并不会
     * 把那一轮的改动变回磁盘上，把它改写成「已接受」就是留档撒谎。
     */
    private static List<String> settledRounds(Session session, Choice choice) {
        List<String> ids = new ArrayList<>();
        if (choice == Choice.UNDO_ROUND) {
            session.current().ifPresent(round -> ids.add(round.recordId()));
            return ids;
        }
        for (Session.Round round : session.rounds()) {
            if (round.settlement() == null
                    || !RunRecord.Settlement.undoes(round.settlement())) {
                ids.add(round.recordId());
            }
        }
        return ids;
    }

    /**
     * 按快照恢复文件（接受时一件都不动）。
     *
     * <p>几份快照叠在一起时<b>从最近的一份往回撤</b>：同一批文件会被写好几遍，
     * 而最早那份最后写，于是结局是「会话最初的样子」——它同时也是最保守的那一版。
     * 只撤最早那一份是不行的：每份快照只记得自己那次的目标文件，
     * 后面几轮改到别的文件时，那些改动就留下来了（而用户点的是「回到会话最初」）。
     *
     * <p>「撤回本轮」走到这里时 {@code waiting} 已经被筛成<b>只有最新那一份</b>
     * （见 {@link #settleSession}），所以从最近一份往回撤 = 只撤这一轮。
     */
    private static int restore(List<WorkspaceSnapshot> waiting, Choice choice) {
        if (choice == Choice.ACCEPT || waiting.isEmpty()) {
            return 0;
        }
        List<WorkspaceSnapshot> newestFirst = new ArrayList<>(waiting);
        java.util.Collections.reverse(newestFirst);
        int restored = 0;
        try {
            for (WorkspaceSnapshot snapshot : newestFirst) {
                restored += snapshot.restore().size();
            }
        } catch (RuntimeException e) {
            // 恢复失败就<b>不删快照</b>：快照是唯一还答得出「原文是什么」的东西，
            // 丢了它，用户就再也回不到这次运行之前了
            throw new SpecflowException("恢复到运行前失败（快照留着，可以重试）：" + e.getMessage(), e);
        }
        return restored;
    }

    // ---------- 单次运行：老口径，一个字都不许变 ----------

    private static Done settleSingle(Path root, ProjectConfig project, RunStore store,
                                     TestEnvironment environment, Choice choice) {
        List<WorkspaceSnapshot> waiting = waiting(root, project);
        if (waiting.isEmpty()) {
            return new Done(false, choice, 0, List.of(), false, List.of(), 0);
        }
        int files = settleFiles(waiting, choice);

        String recordId = store.latestId();
        RunRecord record = loadQuietly(store, recordId);
        List<String> problems = new ArrayList<>();

        List<String> deleted = deleteArtifacts(root, record, problems);
        boolean reset = resetData(environment, problems);
        // 落档放在最后：前面三件都做完了，这一栏才是事实。它是 best-effort，理由见类注释
        settleRecord(store, recordId, choice, record, problems);
        return new Done(true, choice, files, deleted, reset, problems, 0);
    }

    /** 文件那一摊：中断就按快照写回，接受就原样留着；两种都要把快照删掉（十五.8）。 */
    private static int settleFiles(List<WorkspaceSnapshot> waiting, Choice choice) {
        int restored = restore(waiting, choice);
        for (WorkspaceSnapshot snapshot : waiting) {
            snapshot.discard();
        }
        return restored;
    }

    // ---------- 两条路共用的小事 ----------

    private static String nameOf(WorkspaceSnapshot snapshot) {
        return snapshot.directory().getFileName().toString();
    }

    /**
     * 删测试产物：留档里记着的那几份（跑的那一份 + 重新生成过的）。
     *
     * <p>为什么按留档删、而不是把 {@code tools/} 扫一遍：那个目录里可能有用户自己的东西
     * （{@code tools/} 是个常见的目录名），扫一遍就等于删别人的文件。
     */
    private static List<String> deleteArtifacts(Path root, RunRecord record, List<String> problems) {
        List<String> targets = new ArrayList<>();
        if (record != null && record.tests() != null) {
            targets.add(record.tests().directory());
        }
        if (record != null && record.regenerated() != null) {
            targets.addAll(record.regenerated());
        }
        List<String> deleted = new ArrayList<>();
        for (String directory : targets) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            try {
                if (TestArtifacts.delete(root, directory)) {
                    deleted.add(directory);
                } else {
                    // 删不掉的如实报出来：报成「删掉了」而它还在，就是清理漏掉一整轮的方式——
                    // 下一次翻 tools/ 才会发现，而那时已经不知道是谁留下的
                    problems.add("测试产物 " + directory + " 没删掉（不在 " + TestArtifacts.ROOT
                            + "/ 下，或者删不动）");
                }
            } catch (RuntimeException e) {
                log.warn("清测试产物失败 {}：{}", directory, e.getMessage());
                problems.add("测试产物 " + directory + " 没删掉（" + e.getMessage() + "）");
            }
        }
        return deleted;
    }

    /**
     * 清环境数据：跑一次 {@code reset}。
     *
     * <p>最贵的一种错是「这一轮留下的数据把下一轮的断言带偏」——它看起来像被测代码不稳定，
     * 所以每次跑测试之前都要 reset（十五.5），收场时也顺手清一次。
     *
     * <p>只在环境真的活着时做：没声明、docker 没了、没初始化，这里<b>一次进程都不起</b>。
     * 收场失败<b>不</b>收环境：容器是好的，坏的只是那条命令（见 {@code TestEnvironment.reset}）。
     */
    private static boolean resetData(TestEnvironment environment, List<String> problems) {
        if (environment == null) {
            return false;
        }
        try {
            if (!environment.declared() || !environment.docker().ready()
                    || environment.activeComposeFile() == null) {
                return false;
            }
            environment.reset();
            return true;
        } catch (RuntimeException e) {
            log.warn("清环境数据没成功（不影响这次处置）：{}", e.getMessage());
            problems.add("环境数据没重置（" + e.getMessage().replaceAll("\\R", " ") + "）");
            return false;
        }
    }

    /**
     * 写收场留档（十五.8：产物删掉、记录留下）。
     *
     * <p>记的是两件事：<b>怎么收的场</b>，以及<b>收场那一刻还带着哪几条失败用例</b>。
     * 后者是这个工具最基本的诚实：失败清单还在记录里，但「人是知道它红着也接受了」
     * 只有这一栏说得出来。
     *
     * <p>写不进去只记一条警告：收场本身已经生效了（文件、快照、产物都处置完了），
     * 为一次落档失败把请求变成 500，用户会以为自己的决定没生效。
     */
    private static void settleRecord(RunStore store, String recordId, Choice choice,
                                     RunRecord record, List<String> problems) {
        if (recordId == null || recordId.isEmpty() || record == null) {
            problems.add("没有运行记录可落档（这次的处置没有留档）");
            return;
        }
        List<Integer> failing = record.tests() == null
                ? List.of() : record.tests().failingCases();
        try {
            store.settle(recordId, choice.recorded(), failing);
        } catch (RuntimeException e) {
            log.warn("收场留档没写成：{}", e.getMessage());
            problems.add("收场没能写进留档（" + e.getMessage() + "）");
        }
    }

    /** 读一条记录；读不出来只记一句：产物仍在磁盘上，下次收场还能删。 */
    private static RunRecord loadQuietly(RunStore store, String recordId) {
        if (recordId == null || recordId.isEmpty()) {
            return null;
        }
        try {
            return store.load(recordId);
        } catch (RuntimeException e) {
            log.warn("读不到运行记录（{}）：{}", recordId, e.getMessage());
            return null;
        }
    }
}
