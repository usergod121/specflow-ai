package com.specflow.template;

import com.specflow.exception.SpecflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("提示词模板加载")
class TemplateRegistryTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("目录不存在时返回空集合，而不是报错——内联 prompt 是合法用法")
    void missingDirectoryIsNotAnError() {
        assertThat(TemplateRegistry.load(dir.resolve("nope")).size()).isZero();
    }

    @Test
    @DisplayName("按文件名索引模板，并读取出字段声明")
    void loadsTemplatesByFileName() throws IOException {
        Files.writeString(dir.resolve("add-endpoint.yaml"), """
                name: 与文件名不一致
                tags: [class, java]
                description: 新增一个 REST 接口
                system: 你是后端工程师
                """);

        TemplateRegistry registry = TemplateRegistry.load(dir);

        assertThat(registry.names()).containsExactly("add-endpoint");
        PromptTemplate template = registry.get("add-endpoint");
        assertThat(template.description()).isEqualTo("新增一个 REST 接口");
        assertThat(template.tags()).containsExactly("class", "java");
        assertThat(template.system()).contains("你是后端工程师");
    }

    @Test
    @DisplayName("非 yaml 文件被忽略")
    void ignoresNonYamlFiles() throws IOException {
        Files.writeString(dir.resolve("README.md"), "# 说明");
        Files.writeString(dir.resolve("ok.yml"), """
                name: ok
                system: s
                """);

        assertThat(TemplateRegistry.load(dir).names()).containsExactly("ok");
    }

    @Test
    @DisplayName("引用不存在的模板时，错误信息里列出所有可用模板")
    void unknownTemplateListsAvailableNames() throws IOException {
        Files.writeString(dir.resolve("a.yaml"), """
                name: a
                system: s
                """);
        TemplateRegistry registry = TemplateRegistry.load(dir);

        assertThatThrownBy(() -> registry.get("b"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("a");
    }

    @Test
    @DisplayName("模板缺 system 字段时进 broken 名单，并指出是哪一份文件")
    void keepsInvalidTemplateAsideWithoutFailingTheLoad() throws IOException {
        Files.writeString(dir.resolve("good.yaml"), """
                name: good
                system: s
                """);
        Files.writeString(dir.resolve("broken.yaml"), """
                name: broken
                """);

        TemplateRegistry registry = TemplateRegistry.load(dir);

        assertThat(registry.names()).containsExactly("good");
        assertThat(registry.broken()).singleElement()
                .satisfies(item -> {
                    assertThat(item.name()).isEqualTo("broken");
                    assertThat(item.reason()).contains("broken.yaml");
                });
    }

    @Test
    @DisplayName("拿到一份读不出来的模板时，报的是「读不出来」而不是「找不到」")
    void reportsBrokenTemplateAsSuch() throws IOException {
        Files.writeString(dir.resolve("broken.yaml"), """
                name: broken
                """);
        TemplateRegistry registry = TemplateRegistry.load(dir);

        // 混成「找不到模板 broken；可用模板: …」会让人对着那句里赫然列着的 broken 发懵
        assertThatThrownBy(() -> registry.get("broken"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("读不出来");
    }
}
