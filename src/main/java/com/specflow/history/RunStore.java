package com.specflow.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specflow.exception.SpecflowException;
import com.specflow.review.PlanReview;
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
        List<RunRecord> records = readAll();
        return !records.isEmpty() && NEEDS_CONTEXT.equals(records.get(0).status())
                ? Optional.of(records.get(0))
                : Optional.empty();
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
        List<RunRecord> records = readAll();
        return records.isEmpty() ? "" : records.get(0).id();
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
