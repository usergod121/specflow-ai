package com.specflow.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读子进程写下来的输出。
 *
 * <p><b>为什么不能直接 {@code Files.readString(UTF_8)}。</b>把这个输出重定向到文件时，
 * 子进程用的是它自己的编码，而且**几种都可能出现**：
 * <ul>
 *   <li>中文 Windows 上 Maven / javac 默认写 <b>GBK</b>——最要命的是 javac 的报错是
 *       <b>本地化中文</b>，所以<b>编译失败时</b>输出里几乎一定有非 UTF-8 字节；</li>
 *   <li>Windows PowerShell 设成 Unicode 时写 <b>UTF-16LE</b>，而且不一定带 BOM；</li>
 *   <li>显式设成 UTF-8 的（比如我们自己起的进程）才是 UTF-8。</li>
 * </ul>
 * 按 UTF-8 硬读会抛 {@code MalformedInputException: Input length = 1}——而这一抛，
 * 本该看到「编译错在哪一行」的人，看到的却是一句编码异常。
 *
 * <p>所以这里按内容自己认：先看 BOM，再按 UTF-8 解，解出替换字符就退回本机编码。
 */
public final class ProcessOutput {

    private ProcessOutput() {
    }

    /** 读文件里的子进程输出。 */
    public static String read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    /**
     * 认字节。
     *
     * <p>顺序有讲究：BOM 最可靠（子进程自己声明的）；没有 BOM 时先试 UTF-8——
     * 它是严格的，能解出来基本就是它；解出替换字符说明不是 UTF-8，再按本机编码试一次。
     */
    public static String decode(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        if (decoded.indexOf('\0') >= 0) {
            // 一堆 NUL：那其实是没带 BOM 的 UTF-16
            return new String(bytes, StandardCharsets.UTF_16LE);
        }
        if (decoded.indexOf('\uFFFD') >= 0) {
            // 出现替换字符：UTF-8 解不动，按这台机器的本地编码再试一次
            return new String(bytes, nativeCharset());
        }
        return decoded;
    }

    /** 这台机器的本地编码（JDK 18 起有 {@code native.encoding}）；问不到就用 JVM 默认的。 */
    private static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null && Charset.isSupported(name)) {
            return Charset.forName(name);
        }
        return Charset.defaultCharset();
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
