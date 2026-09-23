package com.specflow.web;

import com.specflow.exception.SpecflowException;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 项目文件索引，供界面上的文件选择器使用。
 *
 * <p>存在的理由很朴素：让用户手打 {@code src/main/java/com/specflow/patch/SearchReplaceStrategy.java}
 * 是这套工具最反人类的地方。列出来点选，打字量降为零，路径也打不错。
 *
 * <p>剪枝策略是这里唯一需要小心的地方——必须用 {@link Files#walkFileTree} 而不是
 * {@code Files.walk}：后者会先走进 {@code target/} 和 {@code node_modules/} 再在过滤时丢掉，
 * 而光是把那些目录遍历一遍就够让人以为程序卡死了。
 */
public final class ProjectIndex {

    /** 目录名命中即整棵子树跳过。 */
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", ".gradle", ".settings", ".mvn",
            "target", "build", "out", "dist", "node_modules", "__pycache__");

    /** 相对路径前缀命中即整棵子树跳过——快照目录里全是文件的旧副本。 */
    private static final List<String> SKIP_PREFIXES = List.of(".specflow/snapshots");

    /** 文件数上限：再大的仓库也不是靠滚列表找文件，该用搜索。 */
    private static final int MAX_FILES = 5_000;

    private final Path root;

    public ProjectIndex(Path projectRoot) {
        this.root = projectRoot.toAbsolutePath().normalize();
    }

    /**
     * 一次扫描的结果。
     *
     * @param files       相对项目根的文件路径，按字典序
     * @param directories 相对项目根的目录路径，按字典序；<b>空目录也算</b>——
     *                    你在 IDE 里新建了一个包、里面还没放东西，界面上就该看得见它
     */
    public record Entries(List<String> files, List<String> directories) {
    }

    /**
     * @return 相对项目根的 POSIX 风格路径，按字典序排列
     */
    public List<String> files() {
        return entries().files();
    }

    /** 文件与目录一起扫：走两遍树纯属浪费，而且界面上这两份数据本来就是同一时刻的。 */
    public Entries entries() {
        if (!Files.isDirectory(root)) {
            // 目录被删了/被挪了的话，walkFileTree 会一声不响地给出一个空列表，
            // 界面上就成了「这个项目里一个文件都没有」——那是假话，要说清楚。
            throw new SpecflowException("项目目录已经不在了：" + root);
        }
        List<String> files = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        // 已经进过的目录。规则是「谁先走到就用谁，同一个真实目录只列一次」：
        //   - 普通目录按它自己的路径记账——这一步不要钱；
        //   - 可能是链接的目录按真实路径记账——这一步要 0.5~3 毫秒，所以只对它做。
        // 项目根的真实路径也先记上：指回根的联接是最常见的那种环，第一次撞上就该退出来。
        Set<Path> visited = new HashSet<>();
        visited.add(root);
        visited.add(realPathOf(root));
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (isSkipped(relative(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!visited.add(identityOf(dir, attrs))) {
                        // Windows 的目录联接（junction）在 Java 里不算符号链接，
                        // 「不跟随链接」这条防线对它无效：一个指回项目根的联接就足以
                        // 让遍历一层层套下去，文件列表变成几千条 sub/loop/sub/loop/…。
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    directories.add(relative(dir));
                    return files.size() >= MAX_FILES
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !isSkipped(relative(file))) {
                        files.add(relative(file));
                    }
                    return files.size() >= MAX_FILES
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    // 单个文件读不到（权限、符号链接失效）不该让整个列表失败
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new SpecflowException("扫描项目文件失败：" + e.getMessage(), e);
        }
        files.sort(String::compareTo);
        directories.sort(String::compareTo);
        return new Entries(List.copyOf(files), List.copyOf(directories));
    }

    private String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * 这个目录用哪个路径去认「是不是已经进过」。
     *
     * <p>普通目录用自己就行——比它自己的路径更省事的是没有别的算法，而且不用碰磁盘。
     * 可能是链接的目录才去求真实路径：{@code toRealPath()} 一次要 0.5~3 毫秒，
     * 四百个目录全问一遍就是 1.3 秒，而正常目录一个都不会重复，问它纯属白花。
     * {@code attrs} 是遍历时免费带上的，问它不花钱。
     */
    private static Path identityOf(Path dir, BasicFileAttributes attrs) {
        return attrs.isSymbolicLink() || attrs.isOther() ? realPathOf(dir) : dir;
    }

    /**
     * 目录的真实路径；解不出来时退回它自己。
     *
     * <p>退回去意味着「当作没进过」，也就是照旧往下走——所以这里不能返回 {@code null}，
     * 否则一处读不了目录就会让去重集合里塞满同一个键，把整棵树剪掉。
     */
    private static Path realPathOf(Path dir) {
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            return dir;
        }
    }

    private boolean isSkipped(String relativePath) {
        for (String prefix : SKIP_PREFIXES) {
            if (relativePath.equals(prefix) || relativePath.startsWith(prefix + "/")) {
                return true;
            }
        }
        for (String segment : relativePath.split("/")) {
            if (SKIP_DIRECTORIES.contains(segment)) {
                return true;
            }
        }
        return false;
    }
}
