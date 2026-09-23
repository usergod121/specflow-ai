package com.specflow.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级差异，用于把「文件被改了」变成「改了哪几行」。
 *
 * <p>算法分两步，这个组合是本类唯一值得解释的地方：
 * <ol>
 *   <li>先剥掉公共前缀与公共后缀。补丁的本质就是"在一段没变的代码中间换掉几行"，
 *       剥完之后真正需要比对的往往只有几行</li>
 *   <li>只对剩下的中间段做 LCS。全文 LCS 是 O(n×m) 内存，一个 5000 行的文件
 *       就要 2500 万个格子——而中间段通常不到 10 行</li>
 * </ol>
 *
 * <p>中间段本身也可能很大（整个文件被重写）。这时放弃精确比对，
 * 直接按「全删 + 全插」输出：结果一样是可读的差异，代价只是不做行对齐。
 *
 * <p>输出是带前缀的纯文本：{@code " "} 上下文、{@code "-"} 删除、{@code "+"} 新增。
 * 不生成 {@code @@} 头——调用方是 UI 和日志，它们不需要行号定位，
 * 而那些头只会占掉两行屏幕。
 */
public final class TextDiff {

    /** 上下文行数：改动前后各保留几行原样内容。 */
    public static final int DEFAULT_CONTEXT = 3;

    /** LCS 的格子数上限，超过就退化为「全删全插」。 */
    private static final long MAX_CELLS = 1_000_000L;

    private TextDiff() {
    }

    /**
     * @return 差异文本；两段内容相同则返回空串
     */
    public static String unified(String before, String after) {
        return unified(before, after, DEFAULT_CONTEXT);
    }

    public static String unified(String before, String after, int contextLines) {
        List<String> left = lines(before);
        List<String> right = lines(after);

        int prefix = commonPrefix(left, right);
        int suffix = commonSuffix(left, right, prefix);

        List<String> leftMiddle = left.subList(prefix, left.size() - suffix);
        List<String> rightMiddle = right.subList(prefix, right.size() - suffix);
        if (leftMiddle.isEmpty() && rightMiddle.isEmpty()) {
            return "";
        }

        List<String> out = new ArrayList<>();
        appendContext(out, left, prefix - contextLines, prefix);
        diffMiddle(leftMiddle, rightMiddle, out);
        int suffixStart = left.size() - suffix;
        appendContext(out, left, suffixStart, suffixStart + contextLines);
        return String.join("\n", out);
    }

    // ---------- 中间段的比对 ----------

    private static void diffMiddle(List<String> left, List<String> right, List<String> out) {
        if ((long) left.size() * right.size() > MAX_CELLS) {
            left.forEach(line -> out.add("-" + line));
            right.forEach(line -> out.add("+" + line));
            return;
        }
        int[][] lengths = lcsLengths(left, right);
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            if (left.get(i).equals(right.get(j))) {
                out.add(" " + left.get(i));
                i++;
                j++;
            } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
                out.add("-" + left.get(i++));
            } else {
                out.add("+" + right.get(j++));
            }
        }
        while (i < left.size()) {
            out.add("-" + left.get(i++));
        }
        while (j < right.size()) {
            out.add("+" + right.get(j++));
        }
    }

    /** {@code lengths[i][j]} = 左段从 i 起、右段从 j 起的最长公共子序列长度。 */
    private static int[][] lcsLengths(List<String> left, List<String> right) {
        int[][] lengths = new int[left.size() + 1][right.size() + 1];
        for (int i = left.size() - 1; i >= 0; i--) {
            for (int j = right.size() - 1; j >= 0; j--) {
                lengths[i][j] = left.get(i).equals(right.get(j))
                        ? lengths[i + 1][j + 1] + 1
                        : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
            }
        }
        return lengths;
    }

    // ---------- 辅助 ----------

    /**
     * 按行切分。末尾的换行符不算作一行——否则每个文件都会多出一行空上下文。
     */
    private static List<String> lines(String text) {
        String normalized = TextNormalizer.normalize(text == null ? "" : text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static int commonPrefix(List<String> left, List<String> right) {
        int limit = Math.min(left.size(), right.size());
        int index = 0;
        while (index < limit && left.get(index).equals(right.get(index))) {
            index++;
        }
        return index;
    }

    private static int commonSuffix(List<String> left, List<String> right, int prefix) {
        int limit = Math.min(left.size(), right.size()) - prefix;
        int count = 0;
        while (count < limit
                && left.get(left.size() - 1 - count).equals(right.get(right.size() - 1 - count))) {
            count++;
        }
        return count;
    }

    /** 追加 {@code [from, to)} 区间内的原文，下标越界部分自动裁掉。 */
    private static void appendContext(List<String> out, List<String> lines, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(lines.size(), to);
        for (int i = start; i < end; i++) {
            out.add(" " + lines.get(i));
        }
    }
}
