package com.specflow.util;

import com.specflow.exception.SpecflowException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 文件读写的最小封装。
 *
 * <p>存在理由很实际：读文件和写文件这两件事在 {@code PatchApplier}、
 * {@code SearchReplaceStrategy}、{@code ContextAssembler} 里都要做，
 * 而每处各写一遍 {@code try { ... } catch (IOException e)} 是本项目里
 * 最容易长出的重复代码。统一在这里把 {@link IOException} 翻译成带路径的
 * {@link SpecflowException}，上层就再也不需要处理受检异常了。
 *
 * <p>写入一律走 {@link #writeAtomic}：先写同目录下的临时文件、刷盘，再改名替换。
 * 直接 {@code TRUNCATE_EXISTING} 就地写会在断电或崩溃时留下**空文件或半截文件**，
 * 而工作区里的每个被改文件都可能是用户唯一的副本。
 *
 * <p>本类不做路径安全判断——那是 {@link SafePathResolver} 的职责。
 */
public final class ProjectFiles {

    /**
     * 临时文件的后缀。
     *
     * <p>崩溃时改名还没发生，临时文件会留在工作区里；靠这个后缀能把它认出来，
     * 由「打开项目」那一步扫掉。
     */
    public static final String TMP_SUFFIX = ".specflow-tmp-";

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
     * 原子写入：先在同目录写临时文件并刷盘，再改名替换目标。
     *
     * <p>为什么必须同目录：只有同一个卷内的改名才是原子的。先写系统临时目录再搬过来
     * 会退化成「复制 + 删除」，中途断电同样是半截。
     *
     * <p>为什么先 {@code force(true)}：改名只保证目录项原子，不保证内容已经落盘。
     * 不刷盘的话，断电后可能看到一个「文件名是新的、内容是空的」文件。
     *
     * @param shown 用于错误信息的展示路径（通常是相对路径）
     */
    public static void writeAtomic(Path file, String content, String shown) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX + suffix());
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            // CREATE_NEW 而不是 CREATE：临时文件名撞车时宁可报错，也不要覆盖别人的文件
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(tmp);
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

    /**
     * 清理临时文件，失败不改写错误。
     *
     * <p>清理不掉的残骸由下次「打开项目」时的扫描兜底，所以这里不抛——
     * 抛出去只会把真正的失败原因（比如磁盘满）盖掉。
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 见上：残骸有兜底的清理路径
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
