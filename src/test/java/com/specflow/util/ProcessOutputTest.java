package com.specflow.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 读子进程输出的测试。
 *
 * <p>这一组全是「按字节认编码」的边界：真实事故是中文 Windows 上 javac 用 GBK 写报错，
 * 我们按 UTF-8 硬读，抛 {@code MalformedInputException: Input length = 1}——
 * 于是用户看到的是编码异常，而不是「哪一行编译不过」。
 */
@DisplayName("子进程输出的编码识别")
class ProcessOutputTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("纯 UTF-8：原样读出中文")
    void readsUtf8() {
        String text = "错误：找不到符号";

        assertThat(ProcessOutput.decode(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);
    }

    @Test
    @DisplayName("带 BOM 的 UTF-8：BOM 不出现在结果里")
    void stripsUtf8Bom() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "BUILD FAILURE".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);

        assertThat(ProcessOutput.decode(bytes)).isEqualTo("BUILD FAILURE");
    }

    @Test
    @DisplayName("带 BOM 的 UTF-16LE：按 UTF-16 解，而不是当 UTF-8")
    void readsUtf16LeWithBom() {
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] body = "编译成功".getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);

        assertThat(ProcessOutput.decode(bytes)).isEqualTo("编译成功");
    }

    @Test
    @DisplayName("没带 BOM 的 UTF-16LE：靠一堆 NUL 认出来")
    void readsUtf16WithoutBom() {
        byte[] bytes = "error".getBytes(StandardCharsets.UTF_16LE);

        assertThat(ProcessOutput.decode(bytes)).isEqualTo("error");
    }

    @Test
    @DisplayName("本机编码写出来的中文能读回来（中文 Windows 上就是 GBK）")
    void readsTheNativeEncodingOfThisMachine() throws IOException {
        // 中文 Windows 的 console 编码是 GBK，而 JVM 默认编码在 JDK 18+ 已经是 UTF-8：
        // 目录里有中文（E:\秒杀）时，两种编码就分家了，所以这条靠的是 native.encoding
        Charset nativeCharset = Charset.forName(System.getProperty("native.encoding",
                Charset.defaultCharset().name()));
        Path log = root.resolve("native.log");
        Files.write(log, "错误：E:\\秒杀 不存在".getBytes(nativeCharset));

        // 老实现按 UTF-8 硬读，这一句就会抛 MalformedInputException: Input length = 1
        assertThatCode(() -> ProcessOutput.read(log)).doesNotThrowAnyException();
        assertThat(ProcessOutput.read(log))
                .as("用 %s 写出来的内容", nativeCharset)
                .isEqualTo("错误：E:\\秒杀 不存在");
    }

    @Test
    @DisplayName("空文件读成空串，而不是异常")
    void readsEmptyFile() throws IOException {
        Path log = root.resolve("empty.log");
        Files.write(log, new byte[0]);

        assertThat(ProcessOutput.read(log)).isEmpty();
    }
}
