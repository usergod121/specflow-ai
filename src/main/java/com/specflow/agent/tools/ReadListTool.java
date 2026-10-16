package com.specflow.agent.tools;

import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code read_list}：列一个目录里有什么。
 *
 * <p>模型不列目录就只能靠猜路径，而猜出来的路径十次有八次不存在。
 *
 * <p>两个取舍：构建产物与依赖目录（{@code target/ node_modules/ .git/}）不列，
 * 但<b>滤掉多少条如实报出来</b>——静默隐藏会让模型以为「项目里没有这个目录」；
 * 条目数有上限，到顶置 {@code truncated}，让模型知道该往下一层看。
 */
public final class ReadListTool implements Tool {

    private static final Set<String> NOISE_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", ".gradle", ".mvn", ".settings",
            "target", "build", "out", "dist", "node_modules", "__pycache__");

    private static final int MAX_ENTRIES = 200;

    private final Roots roots;

    public ReadListTool(Roots roots) {
        this.roots = roots;
    }

    @Override
    public String name() {
        return "read_list";
    }

    @Override
    public String description() {
        return "列出一个目录里有哪些文件与子目录（不含构建产物、依赖目录这类噪音）。"
                + "不确定路径该怎么写时先用它看一眼；path 留空就是根目录。";
    }

    @Override
    public Map<String, Param> params() {
        return Map.of("path", Param.optional("string",
                "要列的目录，相对根，例如 src/main/java；留空 = 根目录"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        Path directory = roots.read(Args.raw(args, "path", ""));
        String shown = roots.show(directory);
        if (!Files.exists(directory)) {
            return ToolResult.failure("路径不存在：" + shown);
        }
        if (!Files.isDirectory(directory)) {
            return ToolResult.failure("这是一个文件而不是目录：" + shown + "（读它的内容用 read_file）");
        }

        List<Path> children;
        try (Stream<Path> stream = Files.list(directory)) {
            children = stream.sorted().toList();
        } catch (IOException e) {
            return ToolResult.failure("读不出目录 " + shown + "：" + e.getMessage());
        }

        List<Entry> entries = new ArrayList<>();
        int listed = 0;
        int skipped = 0;
        for (Path child : children) {
            if (Files.isDirectory(child)
                    && NOISE_DIRECTORIES.contains(child.getFileName().toString())) {
                skipped++;
                continue;
            }
            listed++;
            if (entries.size() < MAX_ENTRIES) {
                entries.add(entryOf(child));
            }
        }
        entries.sort(Comparator.comparingInt((Entry one) -> "dir".equals(one.type()) ? 0 : 1));
        return ToolResult.ok(new Result(shown, entries, listed, skipped, listed > entries.size()));
    }

    /** 单个子项读不到属性（坏链、权限）不该让整次列表失败。 */
    private Entry entryOf(Path child) {
        String name = child.getFileName().toString();
        try {
            return Files.isDirectory(child)
                    ? new Entry(name, "dir", null)
                    : new Entry(name, "file", Files.size(child));
        } catch (IOException e) {
            return new Entry(name, "unknown", null);
        }
    }

    /**
     * 一次列表的结果。
     *
     * @param total     滤掉噪音之后、截断之前的条目数
     * @param skipped   因为噪音规则被滤掉的条目数（如实报出）
     * @param truncated 有条目因为上限没列出来
     */
    public record Result(String path, List<Entry> entries, int total, int skipped, boolean truncated) {
    }

    /**
     * @param type {@code dir} / {@code file} / {@code unknown}
     * @param size 字节数；目录与 unknown 为 null
     */
    public record Entry(String name, String type, Long size) {
    }
}
