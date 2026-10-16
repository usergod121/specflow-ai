package com.specflow.agent.tools;

import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code read_file}：读一个文本文件，<b>带行号</b>，可以只要一段行区间。
 *
 * <p>三条刻意的取舍：
 * <ul>
 *   <li><b>带行号</b>（{@code 12→public class Foo {}）：模型引用「第几行」时才说得清，
 *       报错里也有行号可以对。代价是它可能把行号一起抄进 {@code apply_patch} 的 search 里——
 *       那一头由 {@link ApplyPatchTool} 负责把行号前缀剥掉；</li>
 *   <li><b>一次最多 {@value #MAX_LINES} 行</b>：一个几十万行的文件整读进来，这一轮就没有
 *       别的地方放上下文了。截断了要<b>说清截了多少、怎么接着读</b>；</li>
 *   <li><b>不存在就是失败</b>，不是空串：空串会让模型以为「文件是空的」，然后去新建它。</li>
 * </ul>
 */
public final class ReadFileTool implements Tool {

    private static final int MAX_LINES = 400;

    private final Roots roots;

    public ReadFileTool(Roots roots) {
        this.roots = roots;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读一个文本文件的正文，返回的每一行前面带行号（行号不是文件内容的一部分）。"
                + "大文件用 from/to 分段读；返回里带总行数。";
    }

    @Override
    public Map<String, Param> params() {
        return Map.of(
                "path", Param.required("string", "要读的文件，相对根"),
                "from", Param.optional("int", "起始行号（含），从 1 起；默认 1"),
                "to", Param.optional("int", "结束行号（含）；默认到文件结尾"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String requested = Args.text(args, "path");
        if (Roots.secret(requested)) {
            return ToolResult.failure("这是密钥文件，引擎一律不许读：" + requested
                    + "（密钥只从环境变量与 .specflow/local.env 由引擎自己取，不进模型上下文）");
        }
        Path file = roots.read(requested);
        String shown = roots.show(file);
        if (!Files.exists(file)) {
            return ToolResult.failure("文件不存在：" + shown);
        }
        if (Files.isDirectory(file)) {
            return ToolResult.failure("这是一个目录而不是文件：" + shown + "（列目录用 read_list）");
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure("读不出来 " + shown + "：" + e.getMessage());
        }
        int total = lines.size();
        if (total == 0) {
            return ToolResult.ok(new Result(shown, 0, 0, 0, "", false, ""));
        }

        Integer fromArg = Args.intOrNull(args, "from");
        Integer toArg = Args.intOrNull(args, "to");
        int from = fromArg == null ? 1 : fromArg;
        if (from < 1) {
            return ToolResult.failure("from 从 1 起数，收到 " + from);
        }
        if (from > total) {
            return ToolResult.failure("文件只有 " + total + " 行，读不到第 " + from + " 行（" + shown + "）");
        }
        int to = toArg == null ? total : toArg;
        if (to < from) {
            return ToolResult.failure("to 不能小于 from（from=" + from + "，to=" + to + "）");
        }
        to = Math.min(to, total);

        boolean truncated = to - from + 1 > MAX_LINES;
        if (truncated) {
            to = from + MAX_LINES - 1;
        }
        StringBuilder body = new StringBuilder();
        for (int at = from; at <= to; at++) {
            body.append(at).append('→').append(lines.get(at - 1)).append('\n');
        }
        String note = truncated
                ? "已截断：只给了第 " + from + "–" + to + " 行，共 " + total + " 行，还有 "
                + (total - to) + " 行没给（接着读请传 from=" + (to + 1) + "）"
                : "";
        return ToolResult.ok(new Result(shown, total, from, to, body.toString(), truncated, note));
    }

    /**
     * 一次读取的结果。
     *
     * @param content   正文，每行前面是「行号→」；空文件是空串
     * @param truncated 是否被行数上限截断（要接着读就把 from 设成 {@code to + 1}）
     * @param note      截断时说清截了多少、怎么接着读；没截断是空串
     */
    public record Result(String path, int totalLines, int from, int to, String content,
                         boolean truncated, String note) {
    }
}
