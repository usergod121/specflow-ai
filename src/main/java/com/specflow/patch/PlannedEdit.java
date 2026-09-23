package com.specflow.patch;

import java.nio.file.Path;

/**
 * 一条已经定位成功、等待落盘的改动。
 *
 * <p>{@code start}/{@code end} 是**规范化后**（换行符统一为 {@code \n}）内容中的字符区间，
 * 左闭右开。这样多条改动应用到同一文件时，用区间运算就能检出重叠，不需要额外结构。
 *
 * @param file        目标文件绝对路径
 * @param blockIndex  来源补丁块的序号，便于把错误信息映射回模型输出
 * @param start       替换起点（含）
 * @param end         替换终点（不含）
 * @param replacement 替换文本
 */
public record PlannedEdit(
        Path file,
        int blockIndex,
        int start,
        int end,
        String replacement
) {

    public PlannedEdit {
        if (file == null) {
            throw new IllegalArgumentException("file 不能为 null");
        }
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("区间非法: [" + start + ", " + end + ")");
        }
        if (replacement == null) {
            throw new IllegalArgumentException("replacement 不能为 null");
        }
    }

    public int length() {
        return end - start;
    }

    /**
     * 两条改动是否在同一文件内重叠。端点相接（前者 end == 后者 start）不算重叠。
     */
    public boolean overlaps(PlannedEdit other) {
        return start < other.end && other.start < end;
    }
}
