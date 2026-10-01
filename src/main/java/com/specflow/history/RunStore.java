package com.specflow.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specflow.exception.SpecflowException;
import com.specflow.review.PlanReview;

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
     * @throws SpecflowException 磁盘写不进去——记录失败不该影响已经完成的运行，
     *                           所以调用方会捕获它并降级为一条警告
     */
    public void save(RunRecord record) {
        try {
            Files.createDirectories(directory);
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve(record.id() + EXTENSION).toFile(), record);
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
     * 把「这几条不重要」写进留档。
     *
     * <p>为什么要落盘而不是留在界面上：这是<b>人做的判断</b>（十五.6：不重要/误报 → 标记，
     * 接受时不阻塞）。它解释的是「为什么那几条红的最后没被当成问题」——
     * 刷新一次就丢的话，事后翻记录的人只会看到一片红，然后以为那次是失败的。
     *
     * <p>传进来的是<b>完整的一份集合</b>，不是增量：界面上的记号本来就是一个集合
     * （勾上、标记、再勾再标），发全量就不存在「两次点击乱序到达」这种要命的状态。
     * 已经不在了的编号会从留档里去掉——那正是用户「取消标记」的意思。
     *
     * @param indices 被标成已知失败的用例编号
     * @return 写回去之后的那条记录
     * @throws SpecflowException 记录不存在或写不进去
     */
    public RunRecord markKnownFailures(String id, List<Integer> indices) {
        RunRecord record = load(id);
        List<RunRecord.KnownFailure> known = mergeKnown(record.knownFailures(), indices);
        RunRecord updated = record.withKnownFailures(known);
        save(updated);
        return updated;
    }

    /**
     * 合成新的一份「已知失败」。
     *
     * <p>已经标过的那几条<b>保留原来的时间</b>：那个时间记的是「哪一刻人的判断变了」，
     * 每次重标都刷成现在，等于把最初那一刻抹掉。
     */
    private static List<RunRecord.KnownFailure> mergeKnown(List<RunRecord.KnownFailure> existing,
                                                           List<Integer> indices) {
        Map<Integer, String> before = new LinkedHashMap<>();
        if (existing != null) {
            existing.forEach(item -> before.put(item.index(), item.at()));
        }
        if (indices == null || indices.isEmpty()) {
            return null;
        }
        String now = LocalDateTime.now().toString();
        List<RunRecord.KnownFailure> merged = new ArrayList<>();
        for (Integer index : new TreeSet<>(indices)) {
            if (index == null || index <= 0) {
                continue;
            }
            merged.add(new RunRecord.KnownFailure(index, before.getOrDefault(index, now)));
        }
        return merged.isEmpty() ? null : List.copyOf(merged);
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
