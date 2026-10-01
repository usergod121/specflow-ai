package com.specflow.tests;

import com.specflow.env.TestEnvironment;
import com.specflow.exception.SpecflowException;
import com.specflow.history.RunRecord;
import com.specflow.history.RunStore;
import com.specflow.project.ProjectConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.util.SafePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 收场：一次运行跑完之后，把三摊东西各自收干净，并把人的选择写进留档（十五.8）。
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
 *       容器<b>不</b>动（十五.5：容器常驻复用）——只有环境坏了、关项目、用户手动才 {@code down -v}。</li>
 * </ul>
 *
 * <p><b>后两件为什么是 best-effort。</b>它们发生在「用户刚做了决定」之后，而那个决定已经生效了。
 * 为一条清理命令把整个请求变成 500，用户会以为自己的决定没生效——而它其实生效了。
 * 收不掉的如实报出来（十五.9：清理做不到 100%），不假装收干净了。
 */
public final class Teardown {

    private static final Logger log = LoggerFactory.getLogger(Teardown.class);

    /** 用户把这次运行怎么了结的（十五.8 的两种收场）。 */
    public enum Choice {

        /** 接受：磁盘上的改动留着。 */
        ACCEPT(RunRecord.Settlement.ACCEPT),
        /** 中断（恢复到初始）：文件按快照回到这次运行开始前。 */
        INTERRUPT(RunRecord.Settlement.INTERRUPT);

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
     */
    public record Done(boolean settled, Choice choice, int files, List<String> artifacts,
                       boolean reset, List<String> problems) {

        public Done {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** 给界面与命令行的一行。收场的历史结论还有一份在留档里（见 {@code RunRecord.Settlement}）。 */
        public String summarize() {
            if (!settled) {
                return "没有待处置的改动：这次收场什么都没做";
            }
            StringBuilder out = new StringBuilder(choice == Choice.ACCEPT
                    ? "已接受：改动留在磁盘上"
                    : "已中断：恢复 " + files + " 个文件到这次运行开始前");
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

    /**
     * 收场：文件与快照 → 测试产物 → 环境数据 → 收场落档。
     *
     * @param environment 这个项目的测试环境；没有环境（没写 {@code env.yaml}）时一次进程都不起
     * @throws SpecflowException 文件这一步没做成（工作区还在半途）。那时<b>不</b>写收场留档：
     *                           改动还挂着没处置，说「已接受 / 已中断」是假话
     */
    public static Done settle(Path projectRoot, ProjectConfig project, RunStore store,
                              TestEnvironment environment, Choice choice) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<WorkspaceSnapshot> waiting = waiting(root, project);
        if (waiting.isEmpty()) {
            return new Done(false, choice, 0, List.of(), false, List.of());
        }
        int files = settleFiles(waiting, choice);

        String recordId = store.latestId();
        RunRecord record = loadQuietly(store, recordId);
        List<String> problems = new ArrayList<>();

        List<String> deleted = deleteArtifacts(root, record, problems);
        boolean reset = resetData(environment, problems);
        // 落档放在最后：前面三件都做完了，这一栏才是事实。它是 best-effort，理由见类注释
        settleRecord(store, recordId, choice, record, problems);
        return new Done(true, choice, files, deleted, reset, problems);
    }

    /** 文件那一摊：中断就按快照写回，接受就原样留着；两种都要把快照删掉（十五.8）。 */
    private static int settleFiles(List<WorkspaceSnapshot> waiting, Choice choice) {
        int restored = 0;
        for (WorkspaceSnapshot snapshot : waiting) {
            // 从最早的一份开始：多份叠在一起时，回到最初始的状态最保守
            if (choice == Choice.INTERRUPT) {
                try {
                    restored += snapshot.restore().size();
                } catch (RuntimeException e) {
                    // 恢复失败就<b>不删快照</b>：快照是唯一还答得出「原文是什么」的东西，
                    // 丢了它，用户就再也回不到这次运行之前了
                    throw new SpecflowException("恢复到运行前失败（快照留着，可以重试）："
                            + e.getMessage(), e);
                }
            }
            snapshot.discard();
        }
        return restored;
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

    /** 读最新那条记录；读不出来只记一句：产物仍在磁盘上，下次收场还能删。 */
    private static RunRecord loadQuietly(RunStore store, String recordId) {
        if (recordId == null || recordId.isEmpty()) {
            return null;
        }
        try {
            return store.load(recordId);
        } catch (RuntimeException e) {
            log.warn("读不到最新那条运行记录（{}）：{}", recordId, e.getMessage());
            return null;
        }
    }
}
