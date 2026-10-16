package com.specflow.agent.tools;

import com.specflow.agent.ToolRegistry;
import com.specflow.agent.ToolResult;
import com.specflow.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code apply_patch} 的锚点唯一性：命中一次成功；0 次与多次都失败，
 * 而且正文里要<b>说出次数</b>（不然模型只能瞎猜自己错在哪）。越界路径一律失败。
 */
class ApplyPatchTest {

    @TempDir
    Path tmp;

    private ToolRegistry tools() {
        return ToolRegistry.of(new ApplyPatchTool(new Roots(tmp)));
    }

    private static String args(String path, String search, String replace) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", path);
        map.put("search", search);
        map.put("replace", replace);
        return Json.line(map);
    }

    private void write(String name, String content) throws IOException {
        Path file = tmp.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void replacesWhenTheAnchorOccursExactlyOnce() throws IOException {
        write("src/A.java", "class A {\n  int x = 1;\n}\n");

        ToolResult result = tools().call("apply_patch",
                args("src/A.java", "  int x = 1;", "  int x = 2;"));

        assertThat(result.ok()).isTrue();
        assertThat(result.json()).contains("occurrences\":1");
        assertThat(Files.readString(tmp.resolve("src/A.java"))).isEqualTo("class A {\n  int x = 2;\n}\n");
    }

    @Test
    void emptyReplaceDeletesTheAnchor() throws IOException {
        write("A.java", "a\nb\nc\n");

        ToolResult result = tools().call("apply_patch", args("A.java", "b\n", ""));

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(tmp.resolve("A.java"))).isEqualTo("a\nc\n");
    }

    @Test
    void zeroOccurrencesFailsAndSaysZero() throws IOException {
        write("A.java", "class A {\n  int x = 1;\n}\n");

        ToolResult result = tools().call("apply_patch", args("A.java", "int y = 9;", "int y = 8;"));

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("出现 0 次");
        assertThat(Files.readString(tmp.resolve("A.java"))).contains("int x = 1;");
    }

    @Test
    void manyOccurrencesFailsAndSaysHowMany() throws IOException {
        write("A.java", "int x = 1;\nint x = 1;\nint x = 1;\n");

        ToolResult result = tools().call("apply_patch", args("A.java", "int x = 1;", "int x = 2;"));

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("出现 3 次");
        assertThat(Files.readString(tmp.resolve("A.java"))).isEqualTo("int x = 1;\nint x = 1;\nint x = 1;\n");
    }

    @Test
    void anchorFromReadFileWithLineNumbersStillMatches() throws IOException {
        write("A.java", "class A {\n  int x = 1;\n}\n");

        ToolResult result = tools().call("apply_patch",
                args("A.java", "1→class A {\n2→  int x = 1;", "class A {\n  int x = 2;"));

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(tmp.resolve("A.java"))).isEqualTo("class A {\n  int x = 2;\n}\n");
    }

    @Test
    void pathOutsideTheRootFails() throws IOException {
        write("A.java", "a\n");

        assertThat(tools().call("apply_patch", args("../outside.txt", "a", "b")).ok()).isFalse();
        assertThat(tools().call("apply_patch", args("A.java", "a", "b")).ok()).isTrue();
        assertThat(tools().call("apply_patch", args("C:/Windows/win.ini", "a", "b")).ok()).isFalse();
        assertThat(tools().call("apply_patch", args("sub/../A.java", "b", "c")).ok()).isFalse();
    }

    @Test
    void missingFileFails() {
        ToolResult result = tools().call("apply_patch", args("nope.txt", "a", "b"));

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("不存在");
    }

    @Test
    void emptySearchIsRejected() throws IOException {
        write("A.java", "a\n");

        ToolResult result = tools().call("apply_patch", args("A.java", "   ", "b"));

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("search");
    }
}
