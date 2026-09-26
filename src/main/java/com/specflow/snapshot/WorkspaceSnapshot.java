package com.specflow.snapshot;

import com.specflow.exception.SpecflowException;
import com.specflow.patch.TextDiff;
import com.specflow.util.ProjectFiles;
import com.specflow.util.SafePathResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 落盘前的文件快照，用于失败时回滚。
 *
 * <p>写在磁盘上而不是只放内存，原因有三：
 * <ol>
 *   <li>进程被 Ctrl-C 或 OOM 杀掉时，内存快照一起没了，磁盘快照还在</li>
 *   <li>用户可以直接打开目录看到改动前的原文，不需要理解工具内部结构</li>
 *   <li>回滚逻辑本身可以脱离 Agent 单独测试</li>
 * </ol>
 *
 * <p>清单文件记录每个目标的两种状态——{@code PRESENT}（改前有内容，回滚时写回）
 * 与 {@code ABSENT}（改前不存在，回滚时删除）。少了 {@code ABSENT} 这一态，
 * 新建出来的文件就永远清不掉。
 */
public final class WorkspaceSnapshot {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static final String PRESENT = "PRESENT";
    private static final String ABSENT = "ABSENT";
    private static final String MANIFEST = "manifest.tsv";
    private static final String PAYLOAD_DIR = "files";

    /**
     * 「等用户处置」的目录后缀。
     *
     * <p>校验通过之后把目录改成 {@code <时间戳>.pending}，用一个 bit 表达
     * 「这份快照是校验过的、可以接受」，而不是再写一个标记文件——
     * 状态藏在名字里，就没有「标记文件与目录对不上」这种可能。
     */
    public static final String PENDING_SUFFIX = ".pending";

    private final SafePathResolver pathResolver;
    private final Path directory;
    private final String id;

    private WorkspaceSnapshot(SafePathResolver pathResolver, Path directory, String id) {
        this.pathResolver = pathResolver;
        this.directory = directory;
        this.id = id;
    }

    /**
     * 对待修改的文件做一次快照。
     *
     * @param pathResolver 项目路径守卫
     * @param snapshotRoot 快照根目录，通常是 {@code <project>/.specflow/snapshots}
     * @param files        即将被写入的文件（可以包含尚不存在的文件）
     */
    public static WorkspaceSnapshot capture(SafePathResolver pathResolver, Path snapshotRoot,
                                            Collection<Path> files) {
        Set<Path> unique = new LinkedHashSet<>(files);
        String id = STAMP.format(LocalDateTime.now());
        Path directory = snapshotRoot.resolve(id);

        List<String> manifest = new ArrayList<>();
        for (Path file : unique) {
            String relative = pathResolver.relativize(file);
            if (Files.isRegularFile(file)) {
                String content = ProjectFiles.read(file, relative);
                ProjectFiles.writeAtomic(payloadPath(directory, relative), content, relative);
                manifest.add(PRESENT + "\t" + relative);
            } else {
                manifest.add(ABSENT + "\t" + relative);
            }
        }
        ProjectFiles.writeAtomic(directory.resolve(MANIFEST), String.join("\n", manifest), MANIFEST);

        return new WorkspaceSnapshot(pathResolver, directory, id);
    }

    /**
     * 打开一个已经存在的快照目录（处置上一次留下的快照时用）。
     */
    public static WorkspaceSnapshot open(SafePathResolver pathResolver, Path directory) {
        return new WorkspaceSnapshot(pathResolver, directory, directory.getFileName().toString());
    }

    /**
     * 找出还没被处置的快照。
     *
     * <p>判据只有一条：**目录里有清单**。清单是快照里最后写的东西，所以
     * 「有清单」就等于「这份快照可用」；而一份可用的快照只要还在磁盘上，
     * 就意味着上一次运行的结果还没被人接受或撤回——此时不该再动工作区。
     *
     * <p>返回按目录名排序（时间戳在前，所以最早的在前面）。
     */
    public static List<WorkspaceSnapshot> undisposed(SafePathResolver pathResolver, Path snapshotRoot) {
        return listDirectories(snapshotRoot).stream()
                .filter(directory -> Files.isRegularFile(directory.resolve(MANIFEST)))
                .map(directory -> open(pathResolver, directory))
                .toList();
    }

    /**
     * 打开项目时收一次残局：删掉没写完的快照，以及写文件时留下的临时文件。
     *
     * <p>两类残骸都只由「进程死在半路」产生：
     * <ul>
     *   <li>快照目录里<b>没有清单</b> = 快照还没写完。清单是整个准备阶段最后写的东西，
     *       所以这种目录意味着工作区此刻还没被碰过，直接删掉最省事</li>
     *   <li>{@code *.specflow-tmp-*} = 改名之前就断电了。正式文件是完整的，
     *       这些临时文件没有任何用处，只会在你翻目录时制造困惑</li>
     * </ul>
     *
     * <p>临时文件只在两处找：快照目录内部、以及清单里那些目标文件所在的目录。
     * 这两处正是本工具会写文件的地方——为了几个临时文件去扫整个项目不值得。
     *
     * @return 清理结果的描述，供调用方记日志（没有可清理的返回空列表）
     */
    public static List<String> cleanUp(SafePathResolver pathResolver, Path snapshotRoot) {
        List<String> cleaned = new ArrayList<>();
        for (Path directory : listDirectories(snapshotRoot)) {
            if (!Files.isRegularFile(directory.resolve(MANIFEST))) {
                open(pathResolver, directory).discard();
                cleaned.add(directory.getFileName() + "（快照没写完，已删除）");
                continue;
            }
            int tmp = deleteTempFilesUnder(directory);
            for (String[] entry : open(pathResolver, directory).entries()) {
                tmp += deleteTempFilesIn(pathResolver.resolve(entry[1]).getParent());
            }
            if (tmp > 0) {
                cleaned.add(directory.getFileName() + "（清掉 " + tmp + " 个临时文件）");
            }
        }
        return cleaned;
    }

    /**
     * 标记为「等用户处置」：给目录名加后缀。
     *
     * @return 指向改名后目录的新实例（旧实例仍然指向旧名字，不要再用了）
     */
    public WorkspaceSnapshot markPending() {
        if (isPending()) {
            return this;
        }
        Path renamed = directory.resolveSibling(directory.getFileName() + PENDING_SUFFIX);
        try {
            Files.move(directory, renamed, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new SpecflowException("标记快照待处置失败 " + directory + "：" + e.getMessage(), e);
        }
        return new WorkspaceSnapshot(pathResolver, renamed, id);
    }

    /** 是不是「校验通过、等用户处置」的那一种。 */
    public boolean isPending() {
        return directory.getFileName().toString().endsWith(PENDING_SUFFIX);
    }

    /**
     * 快照之后磁盘上真正变了什么。
     *
     * <p>逐个比对「副本」与「当前文件」，现算，而不是另外存一份改动清单：
     * 清单可以被推导出来，存下来只会多一处可能和磁盘对不上的状态。
     */
    public List<Change> changes() {
        List<Change> changes = new ArrayList<>();
        for (String[] entry : entries()) {
            String relative = entry[1];
            Path target = pathResolver.resolve(relative);
            if (!Files.isRegularFile(target)) {
                continue;   // 改前不存在、现在也不存在 —— 什么都没发生
            }
            String current = ProjectFiles.read(target, relative);
            if (ABSENT.equals(entry[0])) {
                changes.add(new Change(relative, true, TextDiff.unified("", current)));
                continue;
            }
            String backup = ProjectFiles.read(payloadPath(directory, relative), relative);
            if (!backup.equals(current)) {
                changes.add(new Change(relative, false, TextDiff.unified(backup, current)));
            }
        }
        return List.copyOf(changes);
    }

    /**
     * 磁盘上相对快照时刻的一处改动。
     *
     * @param path    相对项目根的 POSIX 路径
     * @param created 快照时还不存在，现在有了
     * @param diff    行级差异，格式与 {@code PatchApplier.FileChange.diff} 一致
     */
    public record Change(String path, boolean created, String diff) {
    }

    /**
     * 把全部目标恢复到快照时刻的状态。
     *
     * @return 实际恢复的展示路径
     * @throws SpecflowException 清单缺失或损坏——此时无法安全回滚，必须让上层知道
     */
    public List<String> restore() {
        List<String> restored = new ArrayList<>();
        for (String[] entry : entries()) {
            String state = entry[0];
            String relative = entry[1];
            Path target = pathResolver.resolve(relative);

            if (PRESENT.equals(state)) {
                ProjectFiles.writeAtomic(target,
                        ProjectFiles.read(payloadPath(directory, relative), relative), relative);
            } else {
                ProjectFiles.deleteIfExists(target, relative);
            }
            restored.add(relative);
        }
        return restored;
    }

    /**
     * 读出清单里的「状态 + 相对路径」。
     *
     * @throws SpecflowException 清单缺失或格式不认——宁可报错，也不要按半份清单去恢复工作区
     */
    private List<String[]> entries() {
        Path manifestFile = directory.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestFile)) {
            throw new SpecflowException("快照清单丢失，无法回滚：" + manifestFile);
        }
        List<String[]> entries = new ArrayList<>();
        for (String line : ProjectFiles.read(manifestFile, MANIFEST).split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            if (parts.length != 2) {
                throw new SpecflowException("快照清单格式错误: " + line);
            }
            if (!PRESENT.equals(parts[0]) && !ABSENT.equals(parts[0])) {
                throw new SpecflowException("快照清单中的未知状态 '" + parts[0] + "'：" + parts[1]);
            }
            entries.add(parts);
        }
        return entries;
    }

    /**
     * 删除本次快照，用于成功路径上的清理。
     */
    public void discard() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new SpecflowException("清理快照失败 " + directory + "：" + e.getMessage(), e);
        }
    }

    public String id() {
        return id;
    }

    public Path directory() {
        return directory;
    }

    private static Path payloadPath(Path snapshotDirectory, String relative) {
        return snapshotDirectory.resolve(PAYLOAD_DIR).resolve(relative.replace('\\', '/'));
    }

    private static List<Path> listDirectories(Path snapshotRoot) {
        if (!Files.isDirectory(snapshotRoot)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(snapshotRoot)) {
            return children.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new SpecflowException("扫描快照目录失败 " + snapshotRoot + "：" + e.getMessage(), e);
        }
    }

    /** 删掉目录树里的临时文件残骸（快照目录是我们自己的，随便走）。 */
    private static int deleteTempFilesUnder(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return deleteTempFiles(walk.toList());
        } catch (IOException e) {
            throw new SpecflowException("清理临时文件失败 " + directory + "：" + e.getMessage(), e);
        }
    }

    /** 删掉这一层目录里的临时文件残骸（目标文件旁边，只列一层，不进子目录）。 */
    private static int deleteTempFilesIn(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> children = Files.list(directory)) {
            return deleteTempFiles(children.toList());
        } catch (IOException e) {
            throw new SpecflowException("清理临时文件失败 " + directory + "：" + e.getMessage(), e);
        }
    }

    private static int deleteTempFiles(List<Path> paths) {
        int deleted = 0;
        for (Path path : paths) {
            if (Files.isRegularFile(path)
                    && path.getFileName().toString().contains(ProjectFiles.TMP_SUFFIX)) {
                ProjectFiles.deleteIfExists(path, path.toString());
                deleted++;
            }
        }
        return deleted;
    }

    /** 供日志展示，避免调用方直接拼字符串。 */
    @Override
    public String toString() {
        return "快照 " + id + " @ " + directory;
    }
}
