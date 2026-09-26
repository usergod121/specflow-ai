package com.specflow.util;

import com.specflow.exception.SpecflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("文件读写封装")
class ProjectFilesTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("原子写：目标不存在时创建它")
    void createsNewFile() {
        Path file = root.resolve("New.java");

        ProjectFiles.writeAtomic(file, "class New {}", "New.java");

        assertThat(ProjectFiles.read(file, "New.java")).isEqualTo("class New {}");
    }

    @Test
    @DisplayName("原子写：目标已存在时也能替换（改名必须覆盖旧文件，不是报错）")
    void replacesExistingFile() {
        Path file = root.resolve("Foo.java");
        ProjectFiles.writeAtomic(file, "旧内容", "Foo.java");

        ProjectFiles.writeAtomic(file, "新内容", "Foo.java");

        assertThat(ProjectFiles.read(file, "Foo.java")).isEqualTo("新内容");
    }

    @Test
    @DisplayName("原子写：上层目录不存在时自动创建")
    void createsParentDirectories() {
        Path file = root.resolve("a/b/c/Foo.java");

        ProjectFiles.writeAtomic(file, "x", "a/b/c/Foo.java");

        assertThat(Files.isRegularFile(file)).isTrue();
    }

    @Test
    @DisplayName("原子写：成功后不留下临时文件")
    void leavesNoTempFileBehind() throws IOException {
        Path file = root.resolve("Foo.java");
        ProjectFiles.writeAtomic(file, "第一次", "Foo.java");
        ProjectFiles.writeAtomic(file, "第二次", "Foo.java");

        assertThat(listNames(root)).containsExactly("Foo.java");
    }

    @Test
    @DisplayName("原子写：按 UTF-8 写入，内容一个字节都不改")
    void writesUtf8Verbatim() throws IOException {
        Path file = root.resolve("Foo.java");
        String content = "// 中文注释\r\nclass Foo {\n\tString s = \"引号\";\n}\n";

        ProjectFiles.writeAtomic(file, content, "Foo.java");

        assertThat(Files.readAllBytes(file)).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("原子写：目标位置被目录占着时失败，并且不留下临时文件")
    void cleansUpTempFileWhenWriteFails() throws IOException {
        Path directory = root.resolve("taken");
        Files.createDirectories(directory);

        assertThatThrownBy(() -> ProjectFiles.writeAtomic(directory, "x", "taken"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("写入文件失败 taken");

        assertThat(listNames(root)).containsExactly("taken");
    }

    @Test
    @DisplayName("删除文件：不存在时静默成功")
    void deleteIsSilentWhenMissing() throws IOException {
        ProjectFiles.deleteIfExists(root.resolve("nope.txt"), "nope.txt");

        assertThat(listNames(root)).isEmpty();
    }

    private static List<String> listNames(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
