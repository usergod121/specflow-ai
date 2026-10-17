package com.specflow.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 文件写入的<b>唯一入口</b>：先写临时文件，再原子 rename 覆盖。
 *
 * <p>为什么非要它：这一版写的是用户的真项目（改代码、写配置），
 * 而「写到一半断电 / 被 kill / 磁盘满」留下的半份文件比没写更糟——
 * 用户打开它看到的是一份语法都不完整的文件，而没有任何地方告诉他这是崩溃留下的。
 * 临时文件 + rename 之后，目标路径要么是旧的完整内容，要么是新的完整内容。
 *
 * <p>临时文件放在<b>同一个目录</b>下（不是系统临时目录）：rename 跨盘会退化成复制 + 删，
 * 那样又回到"写一半"的状态。
 */
public final class Atomic {

    private Atomic() {
    }

    public static void write(Path file, String content) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写不进去：" + file + "（" + e.getMessage() + "）", e);
        }
    }

    /** 追加一行（事件日志用；它天生只追加，不需要原子替换）。 */
    public static void appendLine(Path file, String line) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(file, (line == null ? "" : line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("追加不进去：" + file + "（" + e.getMessage() + "）", e);
        }
    }

    /** 读一个文件；不在或读不出来时给默认值（界面初值那一类用，不该因此报错）。 */
    public static String read(Path file, String fallback) {
        try {
            if (!Files.isRegularFile(file)) {
                return fallback;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // 记事本 / PowerShell 的 `Set-Content -Encoding utf8` 会写一个 BOM（U+FEFF）。
            // 它是文件里第 0 个字符，会让 `Path.of(第一行)`、YAML 解析、路径比较全部莫名其妙地失败——
            // 用户手改一下配置就炸掉欢迎页，报错还只说「Illegal char」。读的时候就剥掉，只在这一处。
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException e) {
            return fallback;
        }
    }

    /** 复制一份留档（回退作废时"截之前先留副本"用它）。 */
    public static void copy(Path from, Path to) {
        try {
            Path dir = to.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("复制失败：" + from + " → " + to, e);
        }
    }
}
