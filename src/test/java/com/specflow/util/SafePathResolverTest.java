package com.specflow.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("项目路径守卫")
class SafePathResolverTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("相对路径解析为根目录下的绝对路径")
    void resolvesRelativePath() {
        SafePathResolver resolver = new SafePathResolver(root);

        assertThat(resolver.resolve("src/main/java/Foo.java"))
                .isEqualTo(root.resolve("src/main/java/Foo.java").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("拒绝绝对路径")
    void rejectsAbsolutePath() {
        SafePathResolver resolver = new SafePathResolver(root);
        String absolute = root.resolve("inside.txt").toAbsolutePath().toString();

        assertThatThrownBy(() -> resolver.resolve(absolute))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只接受相对路径");
    }

    @Test
    @DisplayName("拒绝用 .. 逃出项目根目录")
    void rejectsEscape() {
        SafePathResolver resolver = new SafePathResolver(root);

        assertThatThrownBy(() -> resolver.resolve("../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越出项目根目录");
    }

    @Test
    @DisplayName("中间夹着 .. 但最终仍在根目录内时允许")
    void allowsDotDotThatStaysInside() {
        SafePathResolver resolver = new SafePathResolver(root);

        assertThat(resolver.resolve("a/b/../../c.txt"))
                .isEqualTo(root.resolve("c.txt").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("拒绝空路径与指向根目录本身的路径")
    void rejectsEmptyAndRoot() {
        SafePathResolver resolver = new SafePathResolver(root);

        assertThatThrownBy(() -> resolver.resolve("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve("."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目根目录本身");
    }

    @Test
    @DisplayName("绝对路径还原为 POSIX 风格的相对路径，便于日志与清单")
    void relativizeUsesForwardSlashes() {
        SafePathResolver resolver = new SafePathResolver(root);

        assertThat(resolver.relativize(root.resolve("src/main/java/Foo.java")))
                .isEqualTo("src/main/java/Foo.java");
    }
}
