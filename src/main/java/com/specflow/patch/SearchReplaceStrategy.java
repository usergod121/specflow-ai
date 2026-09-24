package com.specflow.patch;

import com.specflow.exception.PatchConflictException;
import com.specflow.exception.PatchConflictException.Kind;
import com.specflow.spec.PatchStrategyType;
import com.specflow.spec.Spec;
import com.specflow.util.ProjectFiles;
import com.specflow.util.SafePathResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * {@code search_replace} 策略：多块精准替换。
 *
 * <p>核心不变量（任何一条不成立都拒绝落盘）：
 * <ol>
 *   <li>块必须声明路径，且路径落在 {@code spec.targets} 白名单内</li>
 *   <li>目标文件不存在 且 SEARCH 为空 —— 新建</li>
 *   <li>目标文件已存在 且 SEARCH 非空 —— 就地替换，锚点必须<b>存在</b>且<b>唯一</b></li>
 *   <li>同一文件内多条改动<b>区间不重叠</b>——否则替换会互相破坏</li>
 * </ol>
 *
 * <p>第 2、3 条里没有「模式」这个概念：<b>新建还是修改，完全由文件在不在决定</b>。
 * 这是刻意的——一个全局的模式开关会让「本次同时新增一个文件、修改另一个文件」变得无法表达，
 * 而且用户面对「这个不存在的文件我该选新建还是修改」这种问题时根本无从回答。
 *
 * <p>第 3 条的唯一性检查是这套设计「宁可报错也不猜」的体现。模型偶尔会给出
 * 只含一个 {@code }} 的锚点，若不做唯一性检查，引擎会挑第一个匹配位置替换，
 * 结果静默改错代码——这是最危险的一类失败。
 */
public final class SearchReplaceStrategy implements PatchStrategy {

    /** 锚点未命中时，在错误信息里回显锚点首行的长度上限。 */
    private static final int SNIPPET_LIMIT = 80;

    @Override
    public PatchStrategyType type() {
        return PatchStrategyType.SEARCH_REPLACE;
    }

    @Override
    public PatchPlan plan(List<PatchBlock> blocks, Spec spec, SafePathResolver pathResolver) {
        Set<Path> allowed = new HashSet<>();
        for (String target : spec.targets()) {
            allowed.add(pathResolver.resolve(target));
        }

        List<PlannedEdit> edits = new ArrayList<>(blocks.size());
        for (PatchBlock block : blocks) {
            edits.add(planBlock(block, allowed, pathResolver));
        }

        assertNoOverlap(edits, pathResolver);
        return PatchPlan.of(edits);
    }

    private PlannedEdit planBlock(PatchBlock block, Set<Path> allowed, SafePathResolver pathResolver) {
        int ordinal = block.index() + 1;
        Path file = resolveTarget(block, ordinal, allowed, pathResolver);
        boolean exists = Files.isRegularFile(file);

        return block.isFullWrite()
                ? planNewFile(block, file, exists, pathResolver, ordinal)
                : planInPlaceEdit(block, file, exists, pathResolver, ordinal);
    }

    // ---------- 两种落点 ----------

    /** 新建：SEARCH 为空，REPLACE 是完整文件内容。 */
    private PlannedEdit planNewFile(PatchBlock block, Path file, boolean exists,
                                    SafePathResolver pathResolver, int ordinal) {
        if (exists) {
            String shown = pathResolver.relativize(file);
            throw new PatchConflictException(Kind.TARGET_EXISTS,
                    "补丁块 " + ordinal + " 想整文件写入 " + shown + "，但它已经存在",
                    List.of("引擎不覆盖已存在的文件——那是唯一可能静默丢掉你已有代码的操作",
                            "要改它：在 SEARCH 段落给出与文件现有内容逐字匹配的锚点",
                            "要新建：换一个尚不存在的路径"));
        }
        return new PlannedEdit(file, block.index(), 0, 0, block.replace());
    }

    /** 就地修改：锚点必须存在且唯一。 */
    private PlannedEdit planInPlaceEdit(PatchBlock block, Path file, boolean exists,
                                        SafePathResolver pathResolver, int ordinal) {
        String shown = pathResolver.relativize(file);
        if (!exists) {
            throw new PatchConflictException(Kind.TARGET_MISSING,
                    "补丁块 " + ordinal + " 给了 SEARCH 锚点，但 " + shown + " 不存在",
                    List.of("锚点的作用是在原文件里定位，文件不存在就无处可寻",
                            "新建文件时请把 SEARCH 段落留空，REPLACE 段落放完整文件内容"));
        }

        String content = TextNormalizer.normalize(ProjectFiles.read(file, shown));
        String anchor = TextNormalizer.normalize(block.search());

        int first = content.indexOf(anchor);
        if (first < 0) {
            throw new PatchConflictException(Kind.ANCHOR_NOT_FOUND,
                    "第 " + ordinal + " 个补丁块的锚点在 " + shown + " 中不存在",
                    anchorMissDetails(anchor, content));
        }

        int last = content.lastIndexOf(anchor);
        if (first != last) {
            throw new PatchConflictException(Kind.ANCHOR_AMBIGUOUS,
                    "第 " + ordinal + " 个补丁块的锚点在 " + shown + " 中匹配到 "
                            + countOccurrences(content, anchor) + " 处，无法确定改哪一处",
                    List.of("锚点首行：\"" + snippet(anchor) + "\"",
                            "请让 SEARCH 段落包含更多上下文（例如带上方法签名或前后各一行），使其唯一"));
        }

        return new PlannedEdit(file, block.index(), first, first + anchor.length(), block.replace());
    }

    // ---------- 定位与校验 ----------

    /**
     * 解析目标路径，并确认它落在本次允许的范围内。
     *
     * <p>越界路径与白名单外路径合成一处处理：对调用方来说，
     * 「这个路径我不能碰」这两种情况的应对方式是一样的。
     */
    private Path resolveTarget(PatchBlock block, int ordinal, Set<Path> allowed,
                               SafePathResolver pathResolver) {
        if (block.path().isBlank()) {
            throw new PatchConflictException(Kind.MISSING_TARGET_PATH,
                    "第 " + ordinal + " 个补丁块未声明目标路径",
                    List.of("正确写法：<<<<<<< SEARCH src/main/java/com/example/Foo.java"));
        }

        Path file;
        try {
            file = pathResolver.resolve(block.path());
        } catch (IllegalArgumentException e) {
            throw new PatchConflictException(Kind.TARGET_NOT_ALLOWED,
                    "第 " + ordinal + " 个补丁块路径非法：" + e.getMessage());
        }

        if (!allowed.contains(file)) {
            String nearest = nearest(block.path(), allowed, pathResolver);
            throw new PatchConflictException(Kind.TARGET_NOT_ALLOWED,
                    "第 " + ordinal + " 个补丁块指向了 targets 之外的文件：" + block.path(),
                    List.of(nearest == null
                                    ? "本次允许改动的文件是：" + describeTargets(allowed, pathResolver)
                                    : "最接近的是：" + nearest + "（是名字写得不完全一样吗？）",
                            "本次允许改动的文件是：" + describeTargets(allowed, pathResolver)));
        }
        return file;
    }

    /**
     * 白名单里和它最像的那个。
     *
     * <p>为什么值得算一下：绝大多数越界不是「想改别人的文件」，而是<b>同一个文件名字写差了一点</b>
     * （少个后缀、多了段目录）。只回一句「允许改的是这些」，模型和人都得自己盯着两个长路径找差异；
     * 把最像的那个点出来，一眼就看见了。
     *
     * <p>只在「同目录 + 同名（忽略后缀差异）」或「一个是另一个的后缀」时才算最像，
     * 不做模糊匹配——点错了比不点更糟。
     */
    private String nearest(String written, Set<Path> allowed, SafePathResolver pathResolver) {
        String target = normalize(written);
        String targetStem = stem(target);
        for (Path candidate : allowed) {
            String shown = normalize(pathResolver.relativize(candidate));
            String stem = stem(shown);
            if (stem.equals(targetStem)
                    || shown.endsWith(target) || target.endsWith(shown)
                    || stem.endsWith(targetStem) || targetStem.endsWith(stem)) {
                return shown;
            }
        }
        return null;
    }

    private static String normalize(String path) {
        String value = path.replace('\\', '/').strip();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value.startsWith("/") ? value.substring(1) : value;
    }

    /** 去掉最后一段的后缀：`a/b/C.java` → `a/b/C`。 */
    private static String stem(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(0, dot) : path;
    }

    private void assertNoOverlap(List<PlannedEdit> edits, SafePathResolver pathResolver) {
        var byFile = new LinkedHashMap<Path, List<PlannedEdit>>();
        for (PlannedEdit edit : edits) {
            byFile.computeIfAbsent(edit.file(), key -> new ArrayList<>()).add(edit);
        }
        for (var entry : byFile.entrySet()) {
            List<PlannedEdit> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Comparator.comparingInt(PlannedEdit::start));
            for (int i = 0; i + 1 < sorted.size(); i++) {
                PlannedEdit current = sorted.get(i);
                PlannedEdit next = sorted.get(i + 1);
                if (current.overlaps(next)) {
                    String shown = pathResolver.relativize(entry.getKey());
                    throw new PatchConflictException(Kind.EDIT_OVERLAP,
                            "第 " + (current.blockIndex() + 1) + " 与第 " + (next.blockIndex() + 1)
                                    + " 个补丁块在 " + shown + " 中区间重叠",
                            List.of("区间 [" + current.start() + ", " + current.end() + ")",
                                    "区间 [" + next.start() + ", " + next.end() + ")",
                                    "请让两个锚点各自覆盖互不相交的代码段"));
                }
            }
        }
    }

    /**
     * 锚点未命中时给出可操作的排查线索：先看首行是否整行存在，
     * 以区分「内容不对」和「只是缩进不对」这两类最常见的失败。
     */
    private List<String> anchorMissDetails(String anchor, String content) {
        String firstLine = anchor.lines().findFirst().orElse("").strip();
        List<String> details = new ArrayList<>();
        details.add("锚点首行：\"" + snippet(anchor) + "\"");
        if (firstLine.isEmpty()) {
            details.add("锚点首行为空——SEARCH 段落可能整体缺失或只有空行");
        } else if (content.contains(firstLine)) {
            details.add("首行内容存在但整段不匹配，通常是缩进或空行不一致");
        } else {
            details.add("首行内容也不存在——模型给出的是它「想象」的代码，而非文件真实内容");
        }
        return details;
    }

    private int countOccurrences(String content, String anchor) {
        int count = 0;
        int from = 0;
        while (true) {
            int hit = content.indexOf(anchor, from);
            if (hit < 0) {
                return count;
            }
            count++;
            from = hit + Math.max(1, anchor.length());
        }
    }

    private String snippet(String anchor) {
        String firstLine = anchor.lines().findFirst().orElse("").strip();
        if (firstLine.length() <= SNIPPET_LIMIT) {
            return firstLine;
        }
        return firstLine.substring(0, SNIPPET_LIMIT) + "…";
    }

    private String describeTargets(Set<Path> allowed, SafePathResolver pathResolver) {
        return allowed.stream()
                .map(pathResolver::relativize)
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("(空)");
    }
}
