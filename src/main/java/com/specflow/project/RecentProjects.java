package com.specflow.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 打开过哪些项目。
 *
 * <p>这是这个工具第一份<b>项目之外</b>的状态：别的都放在项目自己的 {@code .specflow/} 里，
 * 而欢迎页总得记住你打开过哪些项目。所以它放在用户目录下，跟任何项目无关。
 *
 * <p><b>它永远不影响主流程。</b>这只是一份便利清单，不是事实来源——
 * 项目的真实状态永远以磁盘为准。所以读写失败一律不抛给调用方：读不出来当空的，
 * 写不进去记一条日志就算了。为一个「最近打开」把工具锁死，是拿脚投票。
 */
public final class RecentProjects {

    private static final Logger log = LoggerFactory.getLogger(RecentProjects.class);

    /** 全局一份，放在用户目录下。 */
    public static final Path DEFAULT_FILE =
            Path.of(System.getProperty("user.home"), ".specflow", "projects.json");

    /** 留多少个。再长就不是「最近」而是「全部」了。 */
    private static final int MAX_ENTRIES = 15;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    public RecentProjects() {
        this(DEFAULT_FILE);
    }

    public RecentProjects(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    /**
     * 一个打开过的项目。
     *
     * @param path       绝对路径
     * @param name       目录名，界面上显示用
     * @param lastOpened 最后一次打开的时间；只是给人看的相对顺序，不参与任何判断
     */
    public record Entry(String path, String name, String lastOpened) {
    }

    /** 最近打开的，最新的在前。 */
    public synchronized List<Entry> list() {
        return List.copyOf(read());
    }

    /**
     * 记一笔。已经在列表里的会被挪到最前面，而不是多出一条。
     */
    public synchronized void remember(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        String path = normalized.toString();

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(path, nameOf(normalized), LocalDateTime.now().format(STAMP)));
        for (Entry entry : read()) {
            if (!samePath(entry.path(), path)) {
                entries.add(entry);
            }
        }
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        }
        write(entries);
    }

    /** 从列表里去掉一条。项目被删了、或者你不想再看到它。 */
    public synchronized void forget(String path) {
        List<Entry> kept = read().stream().filter(entry -> !samePath(entry.path(), path)).toList();
        write(kept);
    }

    /**
     * 两个路径是不是同一个目录。
     *
     * <p>同一个目录可以有好几种写法：盘符大小写不同、8.3 短名、目录联接。
     * 按字面比就是三条一模一样的记录并排显示，而且删哪条都删不掉另外两条。
     * 交给 {@link Files#isSameFile} 去问操作系统——它才是知道答案的那个。
     *
     * <p>但空白路径除外：{@code Path.of("")} 是合法的，而且指的就是进程当前工作目录。
     * 清单里混进一条空路径时，问操作系统会得到「它和当前项目是同一个目录」，
     * 于是点那一条的「×」会把另一个真实项目一起删掉。空路径只能和空路径相等。
     *
     * <p>目录已经被删掉、或者路径里有非法字符时就问不出来了，只能退回字面比较。
     */
    private static boolean samePath(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        if (left.isBlank() || right.isBlank()) {
            return false;
        }
        try {
            return Files.isSameFile(Path.of(left), Path.of(right));
        } catch (IOException | InvalidPathException e) {
            return false;
        }
    }

    private static String nameOf(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }

    /**
     * 读清单。
     *
     * <p>没有路径的记录直接丢掉：它不是「打开过的项目」，只是一个坏掉的条目——
     * 界面上它既打不开、也没法删（删除要拿路径去匹配），列出来只会让人莫名其妙。
     * 而它更早的害处是：{@code Path.of(null)} 是 NPE，一条坏记录能让整个欢迎页打不开。
     */
    private List<Entry> read() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            Entry[] entries = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Entry[].class);
            if (entries == null) {
                return List.of();
            }
            return Arrays.stream(entries)
                    .filter(entry -> entry != null && entry.path() != null && !entry.path().isBlank())
                    .toList();
        } catch (IOException e) {
            log.warn("最近打开的清单读不出来，当作空的继续：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 写回清单。
     *
     * <p>临时文件 + 原子改名，而不是就地重写：两个服务同时开着（或者你一边在界面上点、
     * 一边在命令行里跑）时，就地重写会让另一个进程读到「只写了一半的 json」——
     * 那份 json 读不出来，于是被当成空清单，下一次 remember 就真的把历史清空了。
     */
    private void write(List<Entry> entries) {
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "projects", ".json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), entries);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 写不进去只影响下一次还能不能看到这条记录，不该让「打开项目」这件事失败
            log.warn("最近打开的清单写不进去：{}", e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("临时清单文件没删掉：{}", e.getMessage());
                }
            }
        }
    }
}
