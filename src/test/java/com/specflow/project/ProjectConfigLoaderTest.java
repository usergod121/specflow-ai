package com.specflow.project;

import com.specflow.exception.SpecValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目配置的加载。
 *
 * <p>这里最要紧的一条是：<b>坏掉的配置不能把项目锁在门外</b>。
 * 配置是打开项目时读的，读失败就意味着这个目录永远打不开——而修它偏偏要先打开它。
 */
@DisplayName("project.yaml 的加载")
class ProjectConfigLoaderTest {

    @TempDir
    Path root;

    private ProjectConfig load() {
        return new ProjectConfigLoader().load(root);
    }

    private void writeConfig(String content) throws IOException {
        Path file = root.resolve(ProjectConfigLoader.CONFIG_DIR)
                .resolve(ProjectConfigLoader.CONFIG_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("没有配置文件就用默认值——零配置也能跑起来")
    void missingFileMeansDefaults() {
        assertThat(load().build().compile()).isNull();
    }

    @Test
    @DisplayName("空文件等同「没配过」，而不是让项目永远打不开")
    void emptyFileIsTreatedAsUnconfigured() throws IOException {
        writeConfig("");

        assertThat(load().build().compile()).isNull();
    }

    @Test
    @DisplayName("只有注释的文件同样算没配过——注释在 YAML 里不是内容")
    void commentOnlyFileIsTreatedAsUnconfigured() throws IOException {
        writeConfig("# 我还没来得及写\n# 先占个位\n\n");

        assertThat(load().build().compile()).isNull();
    }

    @Test
    @DisplayName("正常配置读得出来")
    void readsConfiguredValues() throws IOException {
        writeConfig("""
                build:
                  compile: "mvn -q -DskipTests compile"
                llm:
                  model: "deepseek-chat"
                """);

        assertThat(load().build().compile()).isEqualTo("mvn -q -DskipTests compile");
        assertThat(load().llm().model()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("带 BOM 的配置读得出来——记事本存 UTF-8 就是会带上它")
    void readsConfigWithByteOrderMark() throws IOException {
        Files.createDirectories(root.resolve(ProjectConfigLoader.CONFIG_DIR));
        Files.writeString(root.resolve(ProjectConfigLoader.CONFIG_DIR)
                .resolve(ProjectConfigLoader.CONFIG_FILE), "\uFEFFbuild:\n  compile: \"ant\"\n");

        assertThat(load().build().compile()).isEqualTo("ant");
    }

    @Test
    @DisplayName("只剩一个 BOM 的配置也算没配过——它在编辑器里看着就是全空的")
    void byteOrderMarkOnlyIsTreatedAsUnconfigured() throws IOException {
        Files.createDirectories(root.resolve(ProjectConfigLoader.CONFIG_DIR));
        Files.writeString(root.resolve(ProjectConfigLoader.CONFIG_DIR)
                .resolve(ProjectConfigLoader.CONFIG_FILE), "\uFEFF");

        assertThat(load().build().compile()).isNull();
    }

    @Test
    @DisplayName("只有文档标记/空标量的文件同样算「没配过」——它们在 YAML 里解析出来什么都不是")
    void documentMarkersAreNotContent() throws IOException {
        for (String source : List.of("---\n", "---\n# 占位\n", "%YAML 1.2\n---\n",
                                     "null\n", "~\n", "\"\"\n", "...\n")) {
            writeConfig(source);

            assertThat(load().build().compile()).as(source).isNull();
        }
    }

    @Test
    @DisplayName("看不见的空白也算空白：全角空格、NBSP、零宽空格都留不住一个「坏」文件")
    void invisibleWhitespaceIsNotContent() throws IOException {
        // 中文输入法下「清空文件」很容易留下一个全角空格；这几种字符 trim() 都去不掉
        for (String source : List.of("\u3000", "\u00A0", "\u200B", "\u3000\n\u00A0")) {
            writeConfig(source);

            assertThat(load().build().compile()).as(source).isNull();
        }
    }

    @Test
    @DisplayName("真写坏了照样报错，而且说的是人话——不能一声不响地当默认值用")
    void reallyBrokenConfigIsReported() throws IOException {
        writeConfig("build:\n  compile: [这不是字符串\n");

        assertThatThrownBy(this::load).isInstanceOf(SpecValidationException.class)
                .hasMessageContaining("project.yaml");
    }

    @Test
    @DisplayName("未知字段被点出来——写错字段名时最忌讳沉默")
    void unknownFieldIsNamed() throws IOException {
        writeConfig("build:\n  compileCommand: \"mvn compile\"\n");

        assertThatThrownBy(this::load).isInstanceOf(SpecValidationException.class)
                .hasMessageContaining("compileCommand");
    }
}
