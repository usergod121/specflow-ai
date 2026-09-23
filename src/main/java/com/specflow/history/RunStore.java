package com.specflow.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specflow.exception.SpecflowException;
import com.specflow.review.PlanReview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
