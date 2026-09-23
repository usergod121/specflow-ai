package com.specflow.patch;

import com.specflow.exception.PatchConflictException;
import com.specflow.exception.PatchConflictException.Kind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 解析模型返回的补丁块。
 *
 * <p>协议（行级，非正则全文匹配——这样块内出现 {@code =} 或 {@code <} 也不会误判）：
 * <pre>
 * &lt;&lt;&lt;&lt;&lt;&lt;&lt; SEARCH src/main/java/com/example/Foo.java
 * 原代码片段
 * =======
 * 新代码片段
 * &gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE
 * </pre>
 *
 * <p>容错范围（模型不总是听话）：
 * <ul>
 *   <li>整段响应被 markdown 代码围栏包裹 —— 自动剥掉</li>
 *   <li>块前后有解释性文字 —— 逐行扫描时自然跳过</li>
 *   <li>多个块各自套了自己的围栏 —— 围栏行不是标记行，天然被跳过</li>
 * </ul>
 *
 * <p>不做容错的事：标记拼写错误。宁可报错也不要「猜」模型想干什么，
 * 因为猜错会静默改错文件。
 */
public final class PatchParser {

    private static final String SEARCH_PREFIX = "<<<<<<< SEARCH";
    private static final String SEPARATOR = "=======";
    private static final String END_MARKER = ">>>>>>> REPLACE";
    private static final String FENCE = "```";

    /**
     * @throws PatchConflictException 响应为空、或存在结构不完整的块
     */
    public List<PatchBlock> parse(String response) {
        if (response == null || response.isBlank()) {
            throw new PatchConflictException(Kind.NO_BLOCK_PARSED, "模型响应为空，无法解析出补丁块");
        }

        List<String> lines = stripOuterFence(splitLines(response));
        List<PatchBlock> blocks = new ArrayList<>();

        int cursor = 0;
        while (cursor < lines.size()) {
            if (!isSearchMarker(lines.get(cursor))) {
                cursor++;
                continue;
            }
            int ordinal = blocks.size() + 1;
            String path = extractPath(lines.get(cursor));

            int separator = indexOfMarker(lines, cursor + 1, PatchParser::isSeparator);
            if (separator < 0) {
                throw new PatchConflictException(Kind.NO_BLOCK_PARSED,
                        "第 " + ordinal + " 个补丁块缺少 '" + SEPARATOR + "' 分隔行");
            }

            int end = indexOfMarker(lines, separator + 1, PatchParser::isEndMarker);
            if (end < 0) {
                throw new PatchConflictException(Kind.NO_BLOCK_PARSED,
                        "第 " + ordinal + " 个补丁块缺少 '" + END_MARKER + "' 结束行");
            }

            List<String> searchLines = stripBlankEdges(lines.subList(cursor + 1, separator));
            List<String> replaceLines = lines.subList(separator + 1, end);

            blocks.add(new PatchBlock(
                    blocks.size(),
                    path,
                    String.join(TextNormalizer.LF, searchLines),
                    String.join(TextNormalizer.LF, replaceLines)));

            cursor = end + 1;
        }

        if (blocks.isEmpty()) {
            throw new PatchConflictException(Kind.NO_BLOCK_PARSED,
                    "响应中未找到任何 '" + SEARCH_PREFIX + "' 标记；"
                            + "模型可能没有遵守补丁协议，请检查提示词或重试");
        }
        return List.copyOf(blocks);
    }

    // ---------- 内部 ----------

    private static List<String> splitLines(String text) {
        return new ArrayList<>(Arrays.asList(TextNormalizer.normalize(text).split(TextNormalizer.LF, -1)));
    }

    private static boolean isSearchMarker(String line) {
        return line != null && line.strip().startsWith(SEARCH_PREFIX);
    }

    private static boolean isSeparator(String line) {
        return line != null && line.strip().equals(SEPARATOR);
    }

    private static boolean isEndMarker(String line) {
        return line != null && line.strip().equals(END_MARKER);
    }

    private static boolean isFence(String line) {
        return line != null && line.strip().startsWith(FENCE);
    }

    /**
     * 从 {@code <<<<<<< SEARCH path/to/File.java} 中取出路径；未声明时返回空串，
     * 由校验阶段报 MISSING_TARGET_PATH——解析阶段不越权做业务判断。
     */
    private static String extractPath(String markerLine) {
        String rest = markerLine.strip().substring(SEARCH_PREFIX.length()).strip();
        return rest;
    }

    private static int indexOfMarker(List<String> lines, int from, java.util.function.Predicate<String> matcher) {
        for (int i = from; i < lines.size(); i++) {
            if (matcher.test(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 剥掉首尾空行。锚点关心的是「哪段代码」，两侧的空行属于排版噪声，
     * 模型经常多带一两行，留着会直接导致匹配失败。
     */
    private static List<String> stripBlankEdges(List<String> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isBlank()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isBlank()) {
            end--;
        }
        return lines.subList(start, end);
    }

    /**
     * 整段响应被 ``` 包裹时剥掉最外层围栏。
     */
    private static List<String> stripOuterFence(List<String> lines) {
        if (lines.size() < 2 || !isFence(lines.get(0))) {
            return lines;
        }
        int last = lines.size() - 1;
        while (last > 0 && lines.get(last).isBlank()) {
            last--;
        }
        if (last > 0 && isFence(lines.get(last))) {
            return lines.subList(1, last);
        }
        return lines;
    }
}
