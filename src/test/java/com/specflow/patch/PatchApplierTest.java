package com.specflow.patch;

import com.specflow.TestSpecs;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("落盘执行器")
class PatchApplierTest {

    @TempDir
    Path root;

    private SafePathResolver resolver;
    private PatchApplier applier;

    @BeforeEach
    void setUp() {
        resolver = new SafePathResolver(root);
        applier = new PatchApplier(resolver);
    }

    @Test
    @DisplayName("写入后保留目标文件原有的 CRLF 行尾，不把整个文件改成 LF")
    void preservesCrlfLineEndings() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "line1\r\nline2\r\nline3\r\n");

        PatchPlan plan = plan(TestSpecs.spec(List.of("Foo.java")),
                new PatchBlock(0, "Foo.java", "line2", "LINE2"));
        applier.apply(plan);

        assertThat(Files.readString(file)).isEqualTo("line1\r\nLINE2\r\nline3\r\n");
    }

    @Test
    @DisplayName("LF 文件写回后仍是 LF")
    void keepsLfLineEndings() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "line1\nline2\n");

        applier.apply(plan(TestSpecs.spec(List.of("Foo.java")),
                new PatchBlock(0, "Foo.java", "line2", "LINE2")));

        assertThat(Files.readString(file)).isEqualTo("line1\nLINE2\n");
    }

    @Test
    @DisplayName("mode=create 时新建文件并自动创建父目录")
    void createsFileAndParentDirectories() throws IOException {
        Path file = root.resolve("src/main/java/demo/Demo.java");

        List<PatchApplier.FileChange> changes = applier.apply(
                plan(TestSpecs.spec(List.of("src/main/java/demo/Demo.java")),
                        new PatchBlock(0, "src/main/java/demo/Demo.java", "", "class Demo {}")));

        assertThat(Files.readString(file)).isEqualTo("class Demo {}");
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.created()).isTrue();
            assertThat(change.relative()).isEqualTo("src/main/java/demo/Demo.java");
            assertThat(change.bytes()).isEqualTo("class Demo {}".getBytes(StandardCharsets.UTF_8).length);
        });
    }

    @Test
    @DisplayName("修改已有文件时标记为修改而非新建，并带上行级差异")
    void reportsUpdateForExistingFile() throws IOException {
        Files.writeString(root.resolve("Foo.java"), "hello\nworld\n");

        List<PatchApplier.FileChange> changes = applier.apply(plan(TestSpecs.spec(List.of("Foo.java")),
                new PatchBlock(0, "Foo.java", "hello", "HELLO")));

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.created()).isFalse();
            assertThat(change.describe()).startsWith("修改 Foo.java");
            assertThat(change.diff().lines()).containsExactly("-hello", "+HELLO", " world");
        });
    }

    @Test
    @DisplayName("同一文件上的多处改动按位置倒序应用，互不干扰")
    void appliesMultipleEditsOnSameFile() throws IOException {
        Files.writeString(root.resolve("Foo.java"), "a\nb\nc\n");

        applier.apply(plan(TestSpecs.spec(List.of("Foo.java")),
                new PatchBlock(0, "Foo.java", "a", "A"),
                new PatchBlock(1, "Foo.java", "c", "C")));

        assertThat(Files.readString(root.resolve("Foo.java"))).isEqualTo("A\nb\nC\n");
    }

    private PatchPlan plan(com.specflow.spec.Spec spec, PatchBlock... blocks) {
        return new SearchReplaceStrategy().plan(List.of(blocks), spec, resolver);
    }
}
