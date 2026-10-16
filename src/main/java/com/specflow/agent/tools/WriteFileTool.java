package com.specflow.agent.tools;

import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;
import com.specflow.core.Atomic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code write_file}：整份写入一个文件（新建或覆盖）。
 *
 * <p>落在允许根之内、走 {@link Atomic#write}（临时文件 + rename）——
 * 「写到一半崩了留下半份文件」比没写更糟。
 * 改已有文件里的<b>一小段</b>请用 {@code apply_patch}：整份重写会把模型没读到的部分也换掉。
 */
public final class WriteFileTool implements Tool {

    private final Roots roots;

    public WriteFileTool(Roots roots) {
        this.roots = roots;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "把一个文件的完整内容写进去（文件不存在就新建，存在就整份覆盖）。"
                + "只改文件里的一小段请用 apply_patch。";
    }

    @Override
    public Map<String, Param> params() {
        return Map.of(
                "path", Param.required("string", "要写的文件，相对根"),
                "content", Param.required("string", "文件的完整内容（要写空文件就给空串）"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String requested = Args.text(args, "path");
        if (Roots.secret(requested)) {
            return ToolResult.failure("这是密钥文件，引擎一律不许写：" + requested
                    + "（密钥只由用户自己放进 .specflow/local.env）");
        }
        String content = Args.raw(args, "content", "");
        Path file = roots.write(requested);
        boolean created = !Files.exists(file);
        Atomic.write(file, content);
        int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
        return ToolResult.ok(new Result(roots.show(file), created, lines, content.length()));
    }

    /**
     * @param created 是不是新建的
     * @param lines   写完之后的字符长度与行数（给模型一个「我确实写了」的回执）
     */
    public record Result(String path, boolean created, int lines, int chars) {
    }
}
