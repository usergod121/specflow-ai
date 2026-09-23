package com.specflow.project;

import com.specflow.exception.SpecflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 铺项目骨架。
 *
 * <p>盯两件相反的事：<b>已经写好的东西一个字都不能动</b>，
 * 而<b>空壳必须能修好</b>——空壳留着不管，用户就会卡在「初始化每次都成功、
 * 状态一次都没变」的那个圈里。
 */
@DisplayName("初始化项目")
class ProjectInitializerTest {

    @TempDir
    Path root;

    private ProjectInitializer.Result initialize() {
        return new ProjectInitializer().initialize(root, "mvn -q -DskipTests compile");
    }

    private Path configFile() {
        return root.resolve(ProjectConfigLoader.CONFIG_DIR).resolve(ProjectConfigLoader.CONFIG_FILE);
    }

    @Test
    @DisplayName("新目录一次铺齐：配置、内置模板、示例 spec")
    void writesTheWholeSkeleton() {
        ProjectInitializer.Result result = initialize();

        assertThat(result.written()).containsExactlyInAnyOrder(
                ".specflow/project.yaml", ".specflow/templates/spring-backend.yaml",
                ".specflow/templates/fix-bug.yaml", "spec.yaml");
        assertThat(configFile()).exists();
    }

    @Test
    @DisplayName("再点一次初始化什么都不写，也不会覆盖")
    void secondRunWritesNothing() {
        initialize();

        assertThat(initialize().written()).isEmpty();
    }

    @Test
    @DisplayName("空壳的 project.yaml 会被修好——不然项目永远停在「还没配过」那一屏")
    void rebuildsEmptyConfig() throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "");

        assertThat(initialize().written()).contains(".specflow/project.yaml");
        assertThat(Files.readString(configFile())).contains("compile:");
    }

    @Test
    @DisplayName("只有注释的 project.yaml 同样被修好——加载时它也不算内容")
    void rebuildsCommentOnlyConfig() throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "# 占位\n");

        assertThat(initialize().written()).contains(".specflow/project.yaml");
        assertThat(new ProjectConfigLoader().load(root).build().compile()).isNotBlank();
    }

    @Test
    @DisplayName("只剩一个 BOM 的 project.yaml 同样被修好——它在编辑器里看着是全空的")
    void rebuildsByteOrderMarkOnlyConfig() throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "\uFEFF");

        assertThat(initialize().written()).contains(".specflow/project.yaml");
        assertThat(new ProjectConfigLoader().load(root).build().compile()).isNotBlank();
    }

    @Test
    @DisplayName("已经写了内容的配置一个字都不动")
    void neverTouchesARealConfig() throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "build:\n  compile: \"ant\"\n");

        assertThat(initialize().written()).doesNotContain(".specflow/project.yaml");
        assertThat(Files.readString(configFile())).isEqualTo("build:\n  compile: \"ant\"\n");
    }

    @Test
    @DisplayName("project.yaml 是个目录时明说，而不是每次都假装成功、让用户一直点下去")
    void reportsDirectoryInPlaceOfConfig() throws IOException {
        Files.createDirectories(configFile());

        assertThatThrownBy(this::initialize).isInstanceOf(SpecflowException.class)
                .hasMessageContaining(".specflow/project.yaml")
                .hasMessageContaining("目录");
    }

    @Test
    @DisplayName("spec.yaml 是个目录时同样明说")
    void reportsDirectoryInPlaceOfSpec() throws IOException {
        Files.createDirectories(root.resolve("spec.yaml"));

        assertThatThrownBy(this::initialize).isInstanceOf(SpecflowException.class)
                .hasMessageContaining("spec.yaml");
    }
}
