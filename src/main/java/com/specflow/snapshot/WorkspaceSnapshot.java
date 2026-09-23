package com.specflow.snapshot;

import com.specflow.exception.SpecflowException;
import com.specflow.util.ProjectFiles;
import com.specflow.util.SafePathResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                ProjectFiles.write(payloadPath(directory, relative), content, relative);
                manifest.add(PRESENT + "\t" + relative);
            } else {
                manifest.add(ABSENT + "\t" + relative);
            }
        }
        ProjectFiles.write(directory.resolve(MANIFEST), String.join("\n", manifest), MANIFEST);

        return new WorkspaceSnapshot(pathResolver, directory, id);
    }

    /**
     * 把全部目标恢复到快照时刻的状态。
     *
     * @return 实际恢复的展示路径
     * @throws SpecflowException 清单缺失或损坏——此时无法安全回滚，必须让上层知道
     */
    public List<String> restore() {
        Path manifestFile = directory.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestFile)) {
            throw new SpecflowException("快照清单丢失，无法回滚：" + manifestFile);
        }

        List<String> restored = new ArrayList<>();
        for (String line : ProjectFiles.read(manifestFile, MANIFEST).split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            if (parts.length != 2) {
                throw new SpecflowException("快照清单格式错误: " + line);
            }
            String state = parts[0];
            String relative = parts[1];
            Path target = pathResolver.resolve(relative);

            if (PRESENT.equals(state)) {
                ProjectFiles.write(target, ProjectFiles.read(payloadPath(directory, relative), relative), relative);
            } else if (ABSENT.equals(state)) {
                ProjectFiles.deleteIfExists(target, relative);
            } else {
                throw new SpecflowException("快照清单中的未知状态 '" + state + "'：" + relative);
            }
            restored.add(relative);
        }
        return restored;
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

    /** 供日志展示，避免调用方直接拼字符串。 */
    @Override
    public String toString() {
        return "快照 " + id + " @ " + directory;
    }
}
