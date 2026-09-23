package com.specflow.patch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一份**已通过全部校验、但尚未写入任何字节**的改动计划。
 *
 * <p>这是两阶段提交的「第一阶段产物」。它的存在让落盘动作变得可回滚：
 * 只要 {@code PatchPlan} 构造成功，后续写入就不会因为校验问题失败。
 *
 * <p>不可变对象：构造后既不能加也不能删改动，避免「边写边算」的隐性状态。
 * {@link #render} 是纯函数，不碰磁盘，因此可以被单元测试直接覆盖。
 */
public final class PatchPlan {

    private final Map<Path, List<PlannedEdit>> editsByFile;
    private final int totalEdits;

    private PatchPlan(Map<Path, List<PlannedEdit>> editsByFile, int totalEdits) {
        this.editsByFile = editsByFile;
        this.totalEdits = totalEdits;
    }

    public static PatchPlan of(List<PlannedEdit> edits) {
        Map<Path, List<PlannedEdit>> grouped = new LinkedHashMap<>();
        for (PlannedEdit edit : edits) {
            grouped.computeIfAbsent(edit.file(), key -> new ArrayList<>()).add(edit);
        }
        return new PatchPlan(grouped, edits.size());
    }

    public static PatchPlan empty() {
        return new PatchPlan(Map.of(), 0);
    }

    public Set<Path> files() {
        return editsByFile.keySet();
    }

    /**
     * @return 该文件上的改动，不可变副本——调用方拿到后无法破坏计划的完整性
     */
    public List<PlannedEdit> editsFor(Path file) {
        return List.copyOf(editsByFile.getOrDefault(file, List.of()));
    }

    public int totalEdits() {
        return totalEdits;
    }

    public boolean isEmpty() {
        return totalEdits == 0;
    }

    /**
     * 把本文件上的改动应用到内容上。
     *
     * <p>按起点**降序**应用，这样前面的改动不会让后面的区间偏移，
     * 也就不需要维护位置修正表。
     *
     * @param normalizedContent 已完成换行符规范化的文件内容
     * @return 应用后的内容（同样是 {@code \n} 风格）
     */
    public String render(Path file, String normalizedContent) {
        List<PlannedEdit> edits = new ArrayList<>(editsFor(file));
        if (edits.isEmpty()) {
            return normalizedContent;
        }
        edits.sort(Comparator.comparingInt(PlannedEdit::start).reversed());

        StringBuilder result = new StringBuilder(normalizedContent);
        for (PlannedEdit edit : edits) {
            result.replace(edit.start(), edit.end(), edit.replacement());
        }
        return result.toString();
    }
}
