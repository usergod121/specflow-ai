package com.specflow.flow;

import com.specflow.core.Atomic;
import com.specflow.core.Layout;
import com.specflow.core.Project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * <b>写前快照</b>（写时复制）：只存「将要动的那些文件」改动<b>之前</b>的原文，
 * 动了之后要么接受、要么退回原文（不变量 6）。
 *
 * <h2>为什么要落在盘上，而不是只留内存里</h2>
 * <ol>
 *   <li>进程被 Ctrl-C 或 OOM 杀掉时，内存那份一起没了，而磁盘上那份还在——
 *       用户第二天打开项目，还能把开发 Agent 改坏的代码退回去；</li>
 *   <li>用户可以直接打开快照目录看到改动前的原文，不需要理解内部结构；</li>
 *   <li>回滚本身可以脱离 Agent 单独测（{@code SnapshotTest}）。</li>
 * </ol>
 *
 * <h2>两态清单，少一态就回不干净</h2>
 * 每个目标记两种状态：{@code PRESENT}（改前有原文，回滚时写回）与 {@code ABSENT}
 * （改前不存在，回滚时删掉）。少了 {@code ABSENT}，开发 Agent 新建出来的文件就永远清不掉。
 *
 * <h2>目录名里的状态</h2>
 * 拍的时候叫 {@code <时间戳>.pending}，{@link #accept()} / {@link #rollback()} 之后
 * 变成 {@code <时间戳>.done}（接受时）或者被删掉——状态藏在名字里，
 * 就没有「标记文件与目录对不上」这种可能。
 */
public final class Snapshot {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static final String PENDING = ".pending";
    private static final String DONE = ".done";
    /** 没人处置、又被新一次快照顶替的旧快照：收进这个后缀，不再被 {@link #latest} 选中。 */
    private static final String STALE = ".stale";
    private static final String MANIFEST = "manifest.tsv";
    private static final String PAYLOAD = "files";
    private static final String PRESENT = "PRESENT";
    private static final String ABSENT = "ABSENT";

    private final Project project;
    private final String id;
    private final List<String> skipped;
    private final int present;
    private final int absent;

    private Path directory;
    private volatile boolean disposed;

    private Snapshot(Project project, String id, Path directory, List<String> skipped,
                     int present, int absent) {
        this.project = project;
        this.id = id;
        this.directory = directory;
        this.skipped = List.copyOf(skipped);
        this.present = present;
        this.absent = absent;
    }

    /**
     * 拍一份快照：{@code files} 是<b>即将被写</b>的那些文件（可以包含还不存在的）。
     *
     * <p>文件在项目外面的一律不拍（并如实记下来）：快照的落点、清单里的相对路径、
     * 回滚时的目标都按「项目根」算，项目外的文件没有这个基准。
     */
    public static Snapshot capture(Project project, List<Path> files) {
        if (project == null) {
            throw new IllegalArgumentException("拍快照要有一个项目");
        }
        Layout layout = project.layout();
        Path root = layout.snapshot(new Store(project).currentId());
        Set<Path> wanted = new LinkedHashSet<>();
        if (files != null) {
            for (Path one : files) {
                if (one != null) {
                    wanted.add(one.toAbsolutePath().normalize());
                }
            }
        }
        String id = freshId(root);
        Path directory = root.resolve(id + PENDING);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("快照目录建不出来：" + directory, e);
        }
        List<String> manifest = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int present = 0;
        int absent = 0;
        for (Path file : wanted) {
            if (!file.startsWith(layout.root())) {
                skipped.add(layout.show(file));
                continue;
            }
            String relative = layout.root().relativize(file).toString().replace('\\', '/');
            if (Files.isRegularFile(file)) {
                // 原文按字节复制：回滚之后要逐字节一样（读成字符串再写回去会改变编码与换行）
                Atomic.copy(file, payload(directory, relative));
                manifest.add(PRESENT + "\t" + relative);
                present++;
            } else {
                manifest.add(ABSENT + "\t" + relative);
                absent++;
            }
        }
        Atomic.write(directory.resolve(MANIFEST), manifest.isEmpty() ? "" : String.join("\n", manifest) + "\n");
        return new Snapshot(project, id, directory, skipped, present, absent);
    }

    /**
     * 把「没人处置」的旧快照收进 {@code .stale}，并返回收起来的那些号。
     *
     * <h2>为什么要有这一步（用户实报：目录里像把整个项目拷了一份）</h2>
     * 一次 ④ 跑开发拍一份 {@code .pending}，它只在两种情况下被处置：人点「冻结」（删掉）
     * 或点「回滚」（改成 {@code .done}）。实际情况是：跑了三次开发、叫停一次、没冻也没滚——
     * 三份 {@code .pending} 一直挂着，加起来二十几个文件，看起来像"整个项目被拷了下来"。
     *
     * <p>而 {@link #latest} 只认<b>最新</b>一份，旧的已经永远回滚不到了；留着它们除了占地方、
     * 还会让人误判。所以拍新快照之前先把旧的收起来：改名而不是删——那毕竟是用户文件的原文，
     * 真出了问题人还能自己从目录里捞回来，但产品不会再选它。
     */
    public static List<String> supersede(Project project) {
        if (project == null) {
            return List.of();
        }
        Path root = project.layout().snapshot(new Store(project).currentId());
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> folded = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            for (Path one : children.filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().endsWith(PENDING))
                    .sorted()
                    .toList()) {
                String name = one.getFileName().toString();
                String id = name.substring(0, name.length() - PENDING.length());
                Files.move(one, one.resolveSibling(id + STALE));
                folded.add(id);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("旧快照收不起来：" + root + "（" + e.getMessage() + "）", e);
        }
        return List.copyOf(folded);
    }

    /** 打开最近一份还没处置的快照（`POST /api/node/dev/rollback` 与拍快照不在同一次请求里）。没有 → {@code null}。 */
    public static Snapshot latest(Project project) {
        Path root = project.layout().snapshot(new Store(project).currentId());
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> children = Files.list(root)) {
            List<Path> pending = children
                    .filter(Files::isDirectory)
                    .filter(one -> one.getFileName().toString().endsWith(PENDING))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            if (pending.isEmpty()) {
                return null;
            }
            Path directory = pending.get(0);
            String name = directory.getFileName().toString();
            String id = name.substring(0, name.length() - PENDING.length());
            Snapshot snapshot = new Snapshot(project, id, directory, List.of(), 0, 0);
            return snapshot.withCounts();
        } catch (IOException e) {
            throw new UncheckedIOException("快照目录读不出来：" + root, e);
        }
    }

    /** 快照目录（拍的时候是 {@code <时间戳>.pending}）。 */
    public Path directory() {
        return directory;
    }

    /** 这一份快照的号（时间戳，同一毫秒内再加 {@code -2}、{@code -3}）。 */
    public String id() {
        return id;
    }

    /** 拍了几个文件。 */
    public int files() {
        return present + absent;
    }

    public String summarize() {
        StringBuilder text = new StringBuilder();
        text.append("快照 ").append(id).append("：").append(files()).append(" 个文件（")
                .append(present).append(" 个有原文、").append(absent).append(" 个拍的时候还不存在），落在 ")
                .append(show(directory));
        if (!skipped.isEmpty()) {
            text.append("；另有 ").append(skipped.size()).append(" 个文件在项目外，没拍");
        }
        if (disposed) {
            text.append("（已处置）");
        }
        return text.toString();
    }

    /**
     * 开工时拍好、先别删。
     *
     * <p>{@link #capture} 已经把快照留在盘上了；这一下是「开工前确认它真的在」——
     * 拍丢了要当场知道，而不是等到回滚时才发现回不去。
     */
    public void hold() {
        if (!Files.isRegularFile(directory.resolve(MANIFEST))) {
            throw new IllegalStateException("快照已经不在了，不能拿它回滚：" + show(directory));
        }
    }

    /** 按快照把文件写回原文（并删掉改前不存在的那些）。 */
    public synchronized Report rollback() {
        if (disposed) {
            return new Report(false, 0, List.of("这份快照已经处置过了：" + show(directory)),
                    "已经处置过的快照不重复回滚。");
        }
        List<String> problems = new ArrayList<>();
        List<String[]> entries = entries(problems);
        int restored = 0;
        for (String[] entry : entries) {
            Path target = project.layout().root().resolve(entry[1]);
            try {
                if (PRESENT.equals(entry[0])) {
                    Path from = payload(directory, entry[1]);
                    if (!Files.isRegularFile(from)) {
                        problems.add("快照里没有这份原文：" + entry[1]);
                        continue;
                    }
                    restore(from, target);
                } else {
                    Files.deleteIfExists(target);
                }
                restored++;
            } catch (IOException e) {
                problems.add("回不去 " + entry[1] + "：" + e.getMessage());
            }
        }
        if (!skipped.isEmpty()) {
            problems.add("有 " + skipped.size() + " 个文件在项目外，快照没拍它们，回不了："
                    + String.join("、", skipped));
        }
        disposed = true;
        Path kept = rename(DONE, problems);
        if (kept != null) {
            directory = kept;
        }
        String summary = problems.isEmpty()
                ? "已按快照把 " + restored + " 个文件退回原文；这份快照留在 " + show(directory)
                : "回滚有 " + problems.size() + " 处没做成：" + String.join("；", problems);
        return new Report(problems.isEmpty(), restored, List.copyOf(problems), summary);
    }

    /** 改动留着，快照删掉（接受了就没用了）。 */
    public synchronized Report accept() {
        if (disposed) {
            return new Report(false, 0, List.of("这份快照已经处置过了：" + show(directory)),
                    "已经处置过的快照不重复处置。");
        }
        List<String> problems = new ArrayList<>();
        try {
            delete(directory);
        } catch (IOException e) {
            problems.add("快照目录没删掉：" + e.getMessage());
        }
        disposed = true;
        String summary = problems.isEmpty()
                ? "改动留着：" + files() + " 个文件的改动都留着，快照已删。"
                : "改动留着，但快照没删干净：" + String.join("；", problems);
        return new Report(problems.isEmpty(), files(), List.copyOf(problems), summary);
    }

    /** 处置的结果（给响应体与留档用）。 */
    public record Report(boolean ok, int files, List<String> problems, String summarize) {

        public Report {
            problems = problems == null ? List.of() : List.copyOf(problems);
            summarize = summarize == null ? "" : summarize;
        }
    }

    // ---------- 里面 ----------

    /** {@link #latest} 打开时现数一遍两个计数（目录名里没有它们）。 */
    private Snapshot withCounts() {
        List<String> ignored = new ArrayList<>();
        int yes = 0;
        int no = 0;
        for (String[] entry : entries(ignored)) {
            if (PRESENT.equals(entry[0])) {
                yes++;
            } else {
                no++;
            }
        }
        return new Snapshot(project, id, directory, skipped, yes, no);
    }

    /** 清单里的「状态 + 相对路径」；读不出来时把话记进 {@code problems}，并**不按半份清单回滚**。 */
    private List<String[]> entries(List<String> problems) {
        Path manifest = directory.resolve(MANIFEST);
        String text = Atomic.read(manifest, null);
        if (text == null) {
            problems.add("这份快照的清单不在了（" + show(manifest) + "），没法按它回滚");
            return List.of();
        }
        List<String[]> out = new ArrayList<>();
        for (String row : text.split("\r?\n")) {
            String line = row.strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            if (parts.length != 2 || (!PRESENT.equals(parts[0]) && !ABSENT.equals(parts[0]))) {
                problems.add("清单这一行认不出来：" + line);
                continue;
            }
            out.add(parts);
        }
        return out;
    }

    /** 退回原文：临时文件 + 原子改名（写一半断电留下的半份文件比没写更糟）。 */
    private static void restore(Path from, Path target) throws IOException {
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".restore.tmp");
        try {
            Files.copy(from, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private Path rename(String suffix, List<String> problems) {
        Path target = directory.resolveSibling(id + suffix);
        try {
            Files.move(directory, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            problems.add("快照目录没改名成 " + suffix + "：" + e.getMessage());
            return null;
        }
    }

    private static void delete(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path one : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(one);
            }
        }
    }

    private static Path payload(Path directory, String relative) {
        return directory.resolve(PAYLOAD).resolve(relative);
    }

    private static String freshId(Path root) {
        String base = STAMP.format(LocalDateTime.now());
        String id = base;
        int attempt = 1;
        while (Files.exists(root.resolve(id + PENDING)) || Files.exists(root.resolve(id + DONE))) {
            attempt++;
            id = base + "-" + attempt;
        }
        return id;
    }

    private String show(Path path) {
        return project.layout().show(path);
    }
}
