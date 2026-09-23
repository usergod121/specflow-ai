package com.specflow.web;

import com.specflow.exception.SpecflowException;
import com.specflow.project.ProjectScanner;
import com.specflow.util.UserPath;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 目录浏览，给「打开项目」那个弹窗用。
 *
 * <p>只做两件事：列出一个目录下的<b>子目录</b>，以及认出这个目录是什么项目。
 * <b>只列名字，不读文件内容</b>——用户只是想挑一个目录，
 * 把内容端到界面上既没必要，也白白扩大这个服务的风险面。
 *
 * <p>子目录数量有上限。挑项目的弹窗里塞 3000 个条目既帮不上忙，还会把界面卡住；
 * 真遇到那种目录，直接输入完整路径更快。
 */
final class ProjectBrowser {

    /** 一次最多列多少个子目录。 */
    private static final int MAX_DIRECTORIES = 300;

    private final ProjectScanner scanner = new ProjectScanner();

    /** 一个子目录。 */
    record Directory(String name, String path) {
    }

    /**
     * 一次浏览的结果。
     *
     * @param path   正在看的目录；{@code null} 表示「还没选，先给盘符」
     * @param parent 上一级；{@code null} 表示已经到顶或还没选
     * @param dirs   子目录；{@code path} 为 {@code null} 时是盘符列表
     * @param scan   这个目录是什么项目；{@code path} 为 {@code null} 时不扫
     * @param truncated 子目录被截断过
     */
    record Listing(String path, String parent, List<Directory> dirs, ProjectScanner.Scan scan,
                   boolean truncated) {
    }

    /** 浏览一个目录；{@code rawPath} 为空时列出盘符。 */
    Listing browse(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return new Listing(null, null, drives(), null, false);
        }
        Path dir = UserPath.parse(driveRoot(rawPath)).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new SpecflowException("不是一个目录：" + dir);
        }
        // 多取一个才能分辨「刚好 300 个」和「被砍到 300 个」——
        // 前者一个都没少，不该告诉用户「只列了前 300 个」
        List<Directory> children = childrenOf(dir, MAX_DIRECTORIES + 1);
        boolean truncated = children.size() > MAX_DIRECTORIES;
        return new Listing(dir.toString(), parentOf(dir),
                truncated ? children.subList(0, MAX_DIRECTORIES) : children,
                scanner.scan(dir), truncated);
    }

    private static List<Directory> drives() {
        List<Directory> roots = new ArrayList<>();
        for (File root : File.listRoots()) {
            roots.add(new Directory(root.getPath(), root.getAbsolutePath()));
        }
        return List.copyOf(roots);
    }

    /**
     * 只敲了一个盘符字母（{@code E} 或 {@code E:}）时按盘根算。
     *
     * <p>不这么办的话，{@code E} 会被当成相对路径解析成「当前目录下的 E」——
     * 实测就是 {@code E:\specflow-ai\E}，用户看着莫名其妙；而 {@code E:} 在 Windows 上
     * 更绕，它的意思是「E 盘上的当前目录」。在目录框里敲一个 E，意思只有一个：打开 E 盘。
     */
    private static String driveRoot(String rawPath) {
        String trimmed = rawPath.trim();
        if (File.separatorChar == '\\' && trimmed.matches("(?i)[a-z]:?")) {
            return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + ":\\";
        }
        return rawPath;
    }

    private static String parentOf(Path dir) {
        Path parent = dir.getParent();
        return parent == null ? null : parent.toString();
    }

    private static List<Directory> childrenOf(Path dir, int limit) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .limit(limit)
                    .map(path -> new Directory(path.getFileName().toString(), path.toString()))
                    .toList();
        } catch (IOException e) {
            // 权限不足、盘符掉线之类：说得清楚一点，别让人对着空列表猜
            throw new SpecflowException("读不了这个目录：" + dir + "（" + e.getMessage() + "）");
        }
    }
}
