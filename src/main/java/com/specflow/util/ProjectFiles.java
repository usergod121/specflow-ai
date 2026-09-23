package com.specflow.util;

import com.specflow.exception.SpecflowException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件读写的最小封装。
 *
 * <p>存在理由很实际：读文件和写文件这两件事在 {@code PatchApplier}、
 * {@code SearchReplaceStrategy}、{@code ContextAssembler} 里都要做，
 * 而每处各写一遍 {@code try { ... } catch (IOException e)} 是本项目里
 * 最容易长出的重复代码。统一在这里把 {@link IOException} 翻译成带路径的
 * {@link SpecflowException}，上层就再也不需要处理受检异常了。
 *
 * <p>本类不做路径安全判断——那是 {@link SafePathResolver} 的职责。
 */
public final class ProjectFiles {

    private ProjectFiles() {
    }

    /**
     * 按 UTF-8 读取文本。
     *
     * @param shown 用于错误信息的展示路径（通常是相对路径）
     */
    public static String read(Path file, String shown) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SpecflowException("读取文件失败 " + shown + "：" + e.getMessage(), e);
        }
    }

    /**
     * 按 UTF-8 写入文本，自动创建父目录。
     */
    public static void write(Path file, String content, String shown) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new SpecflowException("写入文件失败 " + shown + "：" + e.getMessage(), e);
        }
    }

    /**
     * 删除文件，文件本就不存在时静默成功。
     */
    public static void deleteIfExists(Path file, String shown) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new SpecflowException("删除文件失败 " + shown + "：" + e.getMessage(), e);
        }
    }
}
