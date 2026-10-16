package com.specflow.agent.tools;

import com.specflow.agent.ToolRegistry;
import com.specflow.agent.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code read_file}：带行号、超长截断并说清截了多少、不存在的文件是失败而不是空串。
 */
class ReadFileTest {

    @TempDir
    Path tmp;

    private ToolRegistry tools() {
        return ToolRegistry.of(new ReadFileTool(new Roots(tmp)));
    }

    private void write(String name, String content) throws IOException {
        Path file = tmp.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void returnsContentWithLineNumbers() throws IOException {
        write("A.java", "class A {\n  int x = 1;\n}\n");

        ToolResult result = tools().call("read_file", "{\"path\":\"A.java\"}");

        assertThat(result.ok()).isTrue();
        assertThat(result.json()).contains("1→class A {").contains("2→  int x = 1;").contains("3→}");
        assertThat(result.json()).contains("\"totalLines\":3").contains("\"truncated\":false");
    }

    @Test
    void readsARange() throws IOException {
        write("A.java", "1\n2\n3\n4\n5\n");

        ToolResult result = tools().call("read_file", "{\"path\":\"A.java\",\"from\":2,\"to\":3}");

        assertThat(result.ok()).isTrue();
        assertThat(result.json()).contains("2→2").contains("3→3").doesNotContain("1→1").doesNotContain("4→4");
    }

    @Test
    void truncatesLongFileAndSaysHowMuch() throws IOException {
        write("big.txt", String.join("\n", IntStream.rangeClosed(1, 1000)
                .mapToObj(n -> "第 " + n + " 行").toList()) + "\n");

        ToolResult result = tools().call("read_file", "{\"path\":\"big.txt\"}");

        assertThat(result.ok()).isTrue();
        assertThat(result.json()).contains("\"truncated\":true")
                .contains("\"totalLines\":1000")
                .contains("\"to\":400")
                .contains("还有 600 行没给")
                .contains("from=401");
    }

    @Test
    void missingFileIsAFailureNotAnEmptyString() {
        ToolResult result = tools().call("read_file", "{\"path\":\"nope.txt\"}");

        assertThat(result.ok()).isFalse();
        assertThat(result.data()).isNull();
        assertThat(result.reason()).contains("不存在").contains("nope.txt");
    }

    @Test
    void directoryIsNotAFile() throws IOException {
        Files.createDirectories(tmp.resolve("src"));

        assertThat(tools().call("read_file", "{\"path\":\"src\"}").reason()).contains("目录");
    }

    @Test
    void secretFileIsRefused() throws IOException {
        write(".specflow/local.env", "SPECFLOW_API_KEY=sk-123\n");

        ToolResult result = tools().call("read_file", "{\"path\":\".specflow/local.env\"}");

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("密钥");
        assertThat(result.json()).doesNotContain("sk-123");
    }

    @Test
    void rangeBeyondTheEndFails() throws IOException {
        write("A.java", "1\n2\n");

        assertThat(tools().call("read_file", "{\"path\":\"A.java\",\"from\":9}").ok()).isFalse();
        assertThat(tools().call("read_file", "{\"path\":\"A.java\",\"from\":2,\"to\":1}").ok()).isFalse();
    }
}
