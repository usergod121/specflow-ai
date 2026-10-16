package com.specflow.agent.tools;

import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;
import com.specflow.core.Atomic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code apply_patch}：搜索替换式改一个已有文件。
 *
 * <p><b>锚点必须唯一</b>：{@code search} 在文件里恰好出现一次才动它。0 次说明抄错了（缩进、
 * 空白、或者文件压根不是那一版），多次说明这段文字在文件里到处都是——两种情况都<b>失败并说出次数</b>，
 * 绝不「替换第一个」：那样改错的地方没有任何人看得出来。
 *
 * <p>落盘走 {@link Atomic#write}（临时文件 + rename），路径必须落在 {@link Roots} 的根内。
 *
 * <p>为什么要把行号前缀剥掉：{@link ReadFileTool} 返回的正文每行前面带 {@code 12→}，
 * 模型照抄时经常把行号一起抄进 {@code search}，于是锚点必然失配。只有<b>每一行</b>
 * 都带前缀时才剥（那样才确定它是抄来的，而不是文件里真的写着这些字）。
 */
public final class ApplyPatchTool implements Tool {

    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+→");

    private final Roots roots;

    public ApplyPatchTool(Roots roots) {
        this.roots = roots;
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public String description() {
        return "把文件里的一段文字换成另一段：search 给要替换掉的原文（要与文件里逐字一致、"
                + "而且在文件里只出现一次），replace 给换成什么（空串 = 删掉）。"
                + "锚点出现 0 次或多次都会被拒并告诉你次数。新建文件用 write_file。";
    }

    @Override
    public Map<String, Param> params() {
        return Map.of(
                "path", Param.required("string", "要改的文件，相对根"),
                "search", Param.required("string", "要被换掉的那段原文（含缩进，必须唯一）"),
                "replace", Param.required("string", "换成的内容；要删掉这段就给空串"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String requested = Args.text(args, "path");
        if (Roots.secret(requested)) {
            return ToolResult.failure("这是密钥文件，引擎一律不许改：" + requested);
        }
        Path file = roots.write(requested);
        String shown = roots.show(file);
        if (!Files.isRegularFile(file)) {
            return ToolResult.failure("文件不存在：" + shown + "（新建文件用 write_file）");
        }
        String content = Atomic.read(file, null);
        if (content == null) {
            return ToolResult.failure("读不出来：" + shown);
        }

        String search = stripLineNumbers(Args.raw(args, "search", ""));
        if (search.isBlank()) {
            return ToolResult.failure("search 不能为空：锚点式替换要给一段与文件里逐字一致的内容"
                    + "（整份重写用 write_file）");
        }
        String replace = stripLineNumbers(Args.raw(args, "replace", ""));

        int count = countOf(content, search);
        if (count == 0) {
            return ToolResult.failure("锚点在 " + shown + " 里出现 0 次：search 要与文件里那一段逐字一致"
                    + "（含缩进与空行）。先用 read_file 读一遍你要改的地方再抄一次");
        }
        if (count > 1) {
            return ToolResult.failure("锚点在 " + shown + " 里出现 " + count + " 次："
                    + "多补几行前后的上下文，让它只剩一处");
        }

        int at = content.indexOf(search);
        String updated = content.substring(0, at) + replace + content.substring(at + search.length());
        Atomic.write(file, updated);

        int line = 1 + (int) content.substring(0, at).chars().filter(one -> one == '\n').count();
        int removed = linesOf(search);
        int added = replace.isEmpty() ? 0 : linesOf(replace);
        String note = "第 " + line + " 行起：" + removed + " 行 → " + added + " 行，"
                + "现在共 " + linesOf(updated) + " 行";
        return ToolResult.ok(new Result(shown, count, line, linesOf(updated), updated.length(), note));
    }

    /** 出现几次（不重叠）。 */
    private static int countOf(String content, String search) {
        int count = 0;
        int at = content.indexOf(search);
        while (at >= 0) {
            count++;
            at = content.indexOf(search, at + search.length());
        }
        return count;
    }

    /** 每一行都带 {@code read_file} 的行号前缀时才剥掉。 */
    private static String stripLineNumbers(String text) {
        String[] rows = text.split("\n", -1);
        boolean stripped = false;
        for (String row : rows) {
            if (row.isBlank()) {
                continue;
            }
            if (!NUMBERED.matcher(row).find()) {
                return text;
            }
            stripped = true;
        }
        if (!stripped) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < rows.length; at++) {
            out.append(NUMBERED.matcher(rows[at]).replaceFirst(""));
            if (at < rows.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static int linesOf(String text) {
        return text.isEmpty() ? 0 : text.split("\n", -1).length;
    }

    /**
     * 一次替换的结果。
     *
     * @param occurrences 锚点在文件里出现的次数（成功时恒为 1）
     * @param line        锚点从第几行起
     * @param lines       改完之后文件多少行
     * @param note        一行回执（替换了几行 → 几行）
     */
    public record Result(String path, int occurrences, int line, int lines, int bytes, String note) {
    }
}
