package com.specflow.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specflow.exception.SpecflowException;
import com.specflow.review.PlanReview;
import com.specflow.session.Session;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.tests.Refeed;
import com.specflow.util.ProjectFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 运行记录的读写。
 *
 * <p>每个文件一次运行，文件名就是那份记录的时间戳标识。
 * 用文件而不是一个不断长大的索引文件：单次运行的记录是自包含的，
 * 删一条就是删一个文件，也不存在「写到一半进程挂了、整个历史全废」的问题。
 */
public final class RunStore {

    public static final String DEFAULT_DIR = ".specflow/runs";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXTENSION = ".json";

    /** 列表最多返回多少条。历史是给人翻的，不是给人搜的。 */
    private static final int MAX_LISTED = 100;

    /** 算「阻断报得准不准」时要认的成功状态。 */
    private static final String SUCCESS = "SUCCESS";
    private static final String UNVERIFIED = "SUCCESS_UNVERIFIED";

    /** 模型宣布「信息不足」时的终态名，与 {@code AgentResult.Status} 同名。 */
    private static final String NEEDS_CONTEXT = "NEEDS_CONTEXT";

    private final Path directory;

    public RunStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    /**
     * 写入一份记录。
     *
     * <p>用原子写（同目录临时文件 → rename）：留档是这套工具唯一的「事后依据」，
     * 而它是在运行<b>收尾那一刻</b>写的——那时进程完全可能被关掉、被 kill。
     * 直写会留下一个半截 JSON，而半截 JSON 的读法是<b>静默跳过</b>（见 {@link #read}），
     * 于是用户看到的是「历史里少了一条」，不是「有一条坏了」。
     *
     * @throws SpecflowException 磁盘写不进去——记录失败不该影响已经完成的运行，
     *                           所以调用方会捕获它并降级为一条警告
     */
    public void save(RunRecord record) {
        Path file = directory.resolve(record.id() + EXTENSION);
        try {
            Files.createDirectories(directory);
            ProjectFiles.writeAtomic(file, JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(record), DEFAULT_DIR + "/" + record.id() + EXTENSION);
        } catch (IOException e) {
            throw new SpecflowException("写入运行记录失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按时间倒序列出全部记录摘要。目录不存在时返回空列表。
     */
    public List<RunRecord.Summary> list() {
        return readAll().stream()
                .map(RunRecord::summary)
                .sorted(Comparator.comparing(RunRecord.Summary::id).reversed())
                .limit(MAX_LISTED)
                .toList();
    }

    /**
     * 「它标的阻断，最后真的阻断了吗」。
     *
     * <p>这是检查阶段唯一能被证伪的地方：标了「阻断」，用户要么补上、要么硬跑；
     * 硬跑还成功了，说明这一条多半是报重了。数据全部来自已有的运行记录——
     * 每条记录里都存着当时报出的缺失项和严重度，不需要另外记一份统计。
     */
    public MissingStats missingStats() {
        List<RunRecord> records = readAll();
        int items = 0;
        int blocking = 0;
        int runsWithBlocking = 0;
        int runsWithBlockingSucceeded = 0;
        for (RunRecord record : records) {
            List<PlanReview.MissingItem> missing = record.missing() == null ? List.of() : record.missing();
            long blockingHere = missing.stream().filter(PlanReview.MissingItem::blocking).count();
            items += missing.size();
            blocking += (int) blockingHere;
            if (blockingHere > 0) {
                runsWithBlocking++;
                if (SUCCESS.equals(record.status()) || UNVERIFIED.equals(record.status())) {
                    runsWithBlockingSucceeded++;
                }
            }
        }
        return new MissingStats(records.size(), items, blocking, runsWithBlocking, runsWithBlockingSucceeded);
    }

    /**
     * 「检查阶段的自评准不准」的一行汇总。
     *
     * @param runs                        有记录的运行次数
     * @param items                       检查阶段一共报过多少条缺失项
     * @param blockingItems               其中被标成「阻断」的有多少条
     * @param runsWithBlocking            报出过阻断项的运行次数
     * @param runsWithBlockingSucceeded   这些运行里，最后仍然成功的次数（多半是报重了）
     */
    public record MissingStats(int runs, int items, int blockingItems,
                               int runsWithBlocking, int runsWithBlockingSucceeded) {
    }

    /**
     * 现在挂着的那一次运行（模型说缺料、停下了），没有就返回空。
     *
     * <p>判据只看**最新那一条记录**：接着跑出来的新纪录会把它挤下去，所以
     * 「最新那条是不是 NEEDS_CONTEXT」正好等于「现在有没有东西挂着等人」——
     * 不需要另存一个「挂起中」的标记，也就不会出现标记和记录对不上的情况。
     */
    public Optional<RunRecord> suspended() {
        return latest().filter(record -> NEEDS_CONTEXT.equals(record.status()));
    }

    /**
     * 现在<b>开着的那个会话</b>（§19）；没有就返回空。
     *
     * <p>判据全部来自留档，不另存一份「会话状态」：最新那条带会话的运行属于哪个会话，
     * 那个会话就数它；它的哪几轮还没收场，就折成 {@link Session}。
     * 存一份状态文件的代价是它迟早和留档对不上，而对不上的表现是
     * 「界面上说第 3 轮、引擎里其实第 2 轮」。
     *
     * <p><b>已经收场的会话不算开着</b>（{@link Session#closed}）：接受或中断过之后，
     * 下一次运行会开一个<b>新会话</b>——会话是「一次做到满意」的那件事，
     * 接受完了还往里面塞新一轮，事后谁也说不清那几轮是哪一次的事。
     *
     * <p>找的是<b>最新那条带会话的留档</b>，不是「最新那条留档」：命令行跑出来的记录
     * 不属于任何会话（{@code session} 是 null），它照样会是最新那条。
     *
     * @param snapshotRoot 快照根目录（{@code <项目>/.specflow/snapshots}）。会话要把
     *                     「哪一轮拍的那份快照」对上去，靠的就是这个目录下的名字
     */
    public Optional<Session> session(Path snapshotRoot) {
        List<RunRecord> all = readAll();
        for (RunRecord record : all) {
            if (record.session() == null) {
                continue;
            }
            String id = record.session().id();
            List<RunRecord> mine = sessionRecords(id, all);
            if (Session.closed(mine)) {
                return Optional.empty();
            }
            return Optional.of(Session.of(id, mine, WorkspaceSnapshot.names(snapshotRoot)));
        }
        return Optional.empty();
    }

    /** 某个会话的全部留档（按轮次序号升序）；没有这个会话时是空表。 */
    public List<RunRecord> sessionRecords(String sessionId, List<RunRecord> all) {
        String target = sessionId == null ? "" : sessionId.strip();
        if (target.isEmpty()) {
            return List.of();
        }
        return all.stream()
                .filter(record -> record.session() != null && target.equals(record.session().id()))
                .sorted(Comparator.comparingInt(record -> record.session().round()))
                .toList();
    }

    /**
     * 最新那条记录的 id；一条都没有时是空串。
     *
     * <p>给「不知道是哪一次」的调用方兜底：界面把「标志成已知失败」这个动作发给谁，
     * 靠的是它当时正在看的那次运行——而刷新过页面之后它手里只有屏幕上那份失败清单，
     * 拿不到记录 id。那时候按「最新的那条」落，比拒绝一次人的判断要好：
     * 屏幕上那份清单本来就是最新一次跑出来的。
     */
    public String latestId() {
        return latest().map(RunRecord::id).orElse("");
    }

    /**
     * 最新那条记录本身；一条都没有时是空的。
     *
     * <p>「最新那条」只有这一处算（{@link #latestId()} 与 {@link #suspended()} 都走它）：
     * 读盘 + 取第一条写三遍的话，日后「哪条算最新」的口径一改，就会有一处漏改——
     * 而漏的那处不会报错，只会安安静静地看错一条记录。
     */
    public Optional<RunRecord> latest() {
        // readAll() 已按文件名（= 时间戳）倒序，所以第一条就是最新那条
        return readAll().stream().findFirst();
    }

    /**
     * 连着几次说缺料（从最新那条往回数，遇到别的状态就停）。
     *
     * <p>数出来而不是记下来：多存一个计数器就多一处可能和历史对不上的状态。
     * 用户看到的是「它已经第 N 次说缺」，据此判断该补料还是该改需求。
     */
    public int repeatedNeedsContext() {
        int count = 0;
        for (RunRecord record : readAll()) {
            if (!NEEDS_CONTEXT.equals(record.status())) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * 把「人勾中的那几条失败」变成这一轮真正要喂给开发的那一段（十五.7 的固定模板）。
     *
     * <p>原料全在<b>上一轮那条留档</b>里（用例清单、失败清单、目标文件），所以拼法挂在
     * 「读留档」这一层：界面只发编号（{@code refeed: [7,8]}）、命令行只写编号（{@code --refeed 7,8}），
     * 内容一律由引擎从事实里拼。<b>这是两条路唯一的一份实现</b>——谁在自己那边拼一段文本，
     * 谁就在把「回喂了什么」交给一个可以旧、可以被改坏的调用方（实测过界面上「已回喂」是假的：
     * 引擎里根本没有这条路）。
     *
     * <p><b>勾中的编号要逐项核过才放行。</b>回喂是按编号说话的，而编号只在「跑出这份失败清单的那一轮」
     * 所冻结的用例清单里有意义——那一轮的清单、那一轮的失败清单，两个都在这条留档里。
     * 对不上（编号不在清单里 / 那一轮它没失败 / 期望与实际取不到）就<b>当场拒掉这一次「下一轮」</b>，
     * 并把哪一条、差什么原样报出来。实测撞上过一次错位：编号取自上一轮留档、语义取自新一轮清单，
     * 喂出去的那一条既没失败、也没期望/实际，整段话自相矛盾，而钱已经花了。
     * 拒掉一次运行的代价，比喂一段自相矛盾的话小得多——后者看上去和正常运行一模一样。
     *
     * @param picked 人勾中的用例编号；空表示这一次不是「下一轮」（{@link Refeed#none()}）
     * @throws IllegalStateException 勾了编号、但上一轮压根没有可回喂的失败清单，
     *                               或者勾中的编号与那一轮冻结的清单对不上
     */
    public Refeed refeed(List<Integer> picked) {
        if (picked == null || picked.isEmpty()) {
            return Refeed.none();
        }
        RunRecord latest = tested();
        // 停用的用例不参与回喂：它已经退出所有分母了，再把它喂给开发就是拿一件不算数的事
        // 去换一轮真实的模型调用。拦在这一处而不是默默滤掉——用户勾的是它，
        // 悄悄少喂一条的下一轮看起来和正常的一模一样（与下面那道逐项校验同一个道理）
        List<Integer> stopped = stopped(latest, picked);
        if (!stopped.isEmpty()) {
            throw new IllegalStateException("回喂被拦下：用例 " + join(stopped)
                    + " 被你停用了，停用的用例不参与回喂——"
                    + "要么先恢复它，要么把勾选里这几条去掉，再点「下一轮」。");
        }
        List<String> problems = Refeed.problems(latest.testCases(), latest.tests(), picked);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("回喂被拦下：这几条对不上上一轮冻结的那份用例清单——"
                    + String.join("；", problems)
                    + "。清单可能被重新生成过（再点一次「先检查」会换一份）："
                    + "先按屏幕上那份清单（界面上写的就是那一轮冻结的那一份）重新勾一次，再点「下一轮」。");
        }
        return Refeed.of(latest.testCases(), latest.tests(), latest.targets(), picked);
    }

    /**
     * 上一轮失败清单里的<b>全部</b>失败用例编号（升序）——命令行上的 {@code --refeed all} 就是它。
     *
     * <p>判据和界面上的「全选」同一个：失败清单里报出来的那些编号。界面上那枚按钮在界面里算，
     * 命令行在引擎里算，两边都不许另想一套「哪些算失败」。
     *
     * <p><b>被停用的那几条不算</b>：它们已经退出分母，回喂给开发只会让模型去修一件用户
     * 明确说过「别再算了」的事——而 {@code all} 的本意是「上次红着的那些全都要修」。
     *
     * @throws IllegalStateException 上一轮压根没有可回喂的失败清单
     */
    public List<Integer> failingCases() {
        RunRecord latest = tested();
        Set<Integer> off = latest.disabledIndexes();
        return latest.tests().failingCases().stream()
                .filter(index -> !off.contains(index))
                .toList();
    }

    /**
     * 停用 / 恢复几条用例，把这件事<b>写进留档</b>。
     *
     * <p>用户的原话是「删掉它」，而这里实现成<b>可恢复的停用</b>：
     * 停用同样让它退出所有分母（通过率、溯源、回喂、覆盖核对），但留档里留下了
     * 「谁、什么时候、对哪几条做了什么」，人随时能把它拿回来。删除是不可逆的——
     * 清单是冻结的，删掉之后既解释不了「当时为什么少了一条」，
     * 也没法在下一次检查里把这件事讲清楚。
     *
     * <p>为什么要写进<b>这一次运行</b>的留档而不是一个全局状态文件：停用是冲着
     * 「那一轮冻结的那份清单」说的（编号只在那份清单里有意义，与回喂同一套口径）。
     * 记在别处，翻旧记录时就会拿今天的停用状态去解释当时的通过率。
     *
     * <p>流水式记录（见 {@code RunRecord.CaseSwitch}）：停用记一条、恢复也记一条，
     * 当下状态从流水里折出来——只留一个状态字段的话，「这几条曾经被谁停过又恢复过」
     * 就查无实据了。
     *
     * @param id       哪一次运行；空串表示「界面上正看着的那一次」，按最新那条落
     * @param indices  这次动的用例编号
     * @param disabled {@code true} = 停用，{@code false} = 恢复
     * @return 写回去之后的那条记录（界面按它的流水重画那一栏）
     * @throws SpecflowException 记录不存在、或者编号不在那一轮冻结的清单里
     */
    public RunRecord disable(String id, List<Integer> indices, boolean disabled) {
        String target = id == null || id.isBlank() ? latestId() : id.strip();
        if (target.isEmpty()) {
            throw new SpecflowException("一条运行记录都没有：先跑一次，才有可停用的用例清单");
        }
        RunRecord record = load(target);
        List<Integer> wanted = indices == null ? List.of() : indices.stream()
                .filter(index -> index != null && index > 0)
                .distinct()
                .sorted()
                .toList();
        if (wanted.isEmpty()) {
            throw new SpecflowException("没说是哪几条用例：请求里 cases 是空的");
        }
        // 编号必须在那一轮冻结的清单里：记一条清单上没有的编号，等于在留档里
        // 留一个指向空处的记号，而它以后会被当成「这条被停用过」
        List<Integer> known = record.testCases() == null ? List.of()
                : record.testCases().stream().map(PlanReview.TestCase::index).toList();
        List<Integer> unknown = wanted.stream().filter(index -> !known.contains(index)).toList();
        if (!unknown.isEmpty()) {
            throw new SpecflowException("用例 " + join(unknown) + " 不在这次运行冻结的用例清单里"
                    + (known.isEmpty() ? "（那一次压根没有用例清单）"
                            : "（清单上是第 " + join(known) + " 条）")
                    + "：先看清屏幕上那份清单再停用");
        }
        RunRecord updated = record.withCaseSwitches(RunRecord.CaseSwitch.append(record.caseSwitches(),
                wanted, disabled, LocalDateTime.now().toString()));
        save(updated);
        return updated;
    }

    /** 这批编号里，有哪几条此刻是停用着的（升序）。 */
    private static List<Integer> stopped(RunRecord record, List<Integer> picked) {
        Set<Integer> off = record.disabledIndexes();
        return picked.stream()
                .filter(index -> index != null && off.contains(index))
                .distinct()
                .sorted()
                .toList();
    }

    /** 编号写成「1、2、3」（错话里要用，界面那一侧另有一份同样的写法）。 */
    private static String join(List<Integer> indexes) {
        StringBuilder out = new StringBuilder();
        for (Integer index : indexes) {
            out.append(out.length() == 0 ? "" : "、").append(index);
        }
        return out.toString();
    }

    /**
     * 上一轮那条留档，前提是它<b>跑过测试</b>——没有测试结论就没有可回喂的东西。
     *
     * <p>拒绝而不是「喂一段空的」：一次什么都没喂进去的运行看起来和正常的运行一模一样，
     * 而用户点「下一轮」要的正是那几条失败，白跑一轮的代价比一句拒绝大得多。
     */
    private RunRecord tested() {
        RunRecord latest = latest().orElse(null);
        if (latest == null || latest.tests() == null) {
            throw new IllegalStateException("没有可回喂的失败清单：上一轮没有跑过测试。");
        }
        return latest;
    }

    /**
     * 把「这几条怎么判的」写进留档（十五.6 里落在用例上的那三条路）。
     *
     * <p>为什么要落盘而不是留在界面上：这些是<b>人做的判断</b>。它们解释的是
     * 「为什么那几条红的最后没被当成问题、为什么这次要回喂开发」——刷新一次就丢的话，
     * 事后翻记录的人只会看到一片红，然后以为那次是失败的。
     *
     * <p>传进来的是<b>完整的一份集合</b>，不是增量：界面上的记号本来就是一个集合
     * （勾上、标记、再勾再标），发全量就不存在「两次点击乱序到达」这种要命的状态。
     * 已经不在这一档里的编号会从留档里去掉——那正是用户「取消这个判断」的意思。
     *
     * @param indices 被这样判定的用例编号
     * @param owner   谁错了，见 {@code RunRecord.Verdict}
     * @return 写回去之后的那条记录
     * @throws SpecflowException 记录不存在或写不进去
     */
    public RunRecord judge(String id, List<Integer> indices, String owner) {
        RunRecord record = load(id);
        List<RunRecord.Verdict> merged = mergeVerdicts(record.verdicts(), indices, owner);
        RunRecord updated = record.withVerdicts(merged);
        save(updated);
        return updated;
    }

    /**
     * 收场落档（十五.8）：接受还是中断，以及那一刻还带着哪几条失败用例。
     *
     * <p>为什么这一笔非写不可：留下来的失败清单只说明「当时红在哪几条上」，
     * 说明不了<b>人是知道它红着还接受了</b>。过几天再看，「这次改动带着 2 条失败被接受」
     * 和「这次改动全绿」在记录里长得一模一样——而它们是两件完全不同的事。
     *
     * @param choice  接受 / 中断，见 {@code RunRecord.Settlement}
     * @param failing 收场那一刻还带着的失败用例编号
     */
    public RunRecord settle(String id, String choice, List<Integer> failing) {
        RunRecord record = load(id);
        RunRecord updated = record.withSettlement(new RunRecord.Settlement(choice,
                LocalDateTime.now().toString(), failing));
        save(updated);
        return updated;
    }

    /**
     * 记一笔「测试代码错了，重新生成到 {@code directory}」（十五.6 第二条路）。
     *
     * <p>这一笔里有两件事，一起写：<b>判断</b>（这几条用例被判成「测试代码错了」）和
     * <b>产物账</b>（新开的那份 {@code tools/<时间戳>/} 在哪儿）。
     *
     * <p>产物账为什么非记不可：留档里原本只有这次运行跑过的那一份产物，而重新生成会新开一份——
     * 收场时按留档删产物（十五.8），漏掉的那份就永远留在项目里，{@code tools/} 于是只增不减。
     *
     * <p>两次落档合成一次写：分开写就是同一个文件读两遍写两遍，而中间那一次被进程打断
     * 就会留下「判断记了、产物账没记」的半截状态。
     *
     * @param failing   被判成「测试代码错了」的用例编号（他看的就是这份失败清单）
     * @param directory 新生成的产物目录。空串只记判断，不记产物
     */
    public RunRecord regenerated(String id, List<Integer> failing, String directory) {
        RunRecord record = load(id);
        List<RunRecord.Verdict> verdicts = record.verdicts();
        if (failing != null && !failing.isEmpty()) {
            verdicts = mergeVerdicts(verdicts, failing, RunRecord.Verdict.TEST);
        }
        List<String> before = record.regenerated() == null ? List.of() : record.regenerated();
        List<String> dirs = before;
        if (directory != null && !directory.isBlank() && !before.contains(directory)) {
            List<String> all = new ArrayList<>(before);
            all.add(directory);
            dirs = List.copyOf(all);
        }
        RunRecord updated = record.withVerdicts(verdicts).withRegenerated(dirs);
        save(updated);
        return updated;
    }

    /**
     * 合成新的「人的判断」那一栏。
     *
     * <p>两条规矩：<b>同一条用例只有一个判断</b>（改判就替换，它问的是「谁错了」，
     * 不可能同时是两个答案）；<b>同一档上的老判断保留原来的时间</b>——那个时间记的是
     * 「哪一刻人的判断变了」，每次重标都刷成现在，等于把最初那一刻抹掉。
     */
    private static List<RunRecord.Verdict> mergeVerdicts(List<RunRecord.Verdict> existing,
                                                         List<Integer> indices, String owner) {
        Map<Integer, RunRecord.Verdict> before = new LinkedHashMap<>();
        if (existing != null) {
            existing.forEach(item -> before.put(item.index(), item));
        }
        // 这一次判的是这一档：先把这一档里已经不在了的去掉（用户取消了）
        before.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner)
                && (indices == null || !indices.contains(entry.getKey())));
        if (indices == null || indices.isEmpty()) {
            return before.isEmpty() ? null : List.copyOf(before.values());
        }
        String now = LocalDateTime.now().toString();
        TreeMap<Integer, RunRecord.Verdict> merged = new TreeMap<>(before);
        for (Integer index : new TreeSet<>(indices)) {
            if (index == null || index <= 0) {
                continue;
            }
            RunRecord.Verdict old = merged.get(index);
            merged.put(index, old != null && old.owner().equals(owner)
                    ? old : new RunRecord.Verdict(index, owner, now));
        }
        return merged.isEmpty() ? null : List.copyOf(merged.values());
    }

    /**
     * 读取单份记录。
     *
     * @throws SpecflowException 记录不存在或无法解析
     */
    public RunRecord load(String id) {
        Path file = directory.resolve(id + EXTENSION).normalize();
        // 文件名来自 URL，必须挡住 ../../ 这种写法
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            throw new SpecflowException("找不到运行记录: " + id);
        }
        return read(file).orElseThrow(() -> new SpecflowException("运行记录已损坏: " + id));
    }

    /**
     * 读全部记录，按文件名（= 时间戳）倒序。单条读不出来就跳过，
     * 不让一份坏文件把整段历史挡在门外。
     */
    private List<RunRecord> readAll() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .map(this::read)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(RunRecord::id).reversed())
                    .toList();
        } catch (IOException e) {
            throw new SpecflowException("读取运行记录列表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 单份记录读不出来（被手工改坏、写到一半断电）时返回空，
     * 由调用方决定是跳过还是报错——列表跳过，按 id 精读则报错。
     */
    private Optional<RunRecord> read(Path file) {
        try {
            return Optional.ofNullable(JSON.readValue(file.toFile(), RunRecord.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
