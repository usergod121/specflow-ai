package com.specflow.template;

import com.specflow.exception.SpecflowException;
import com.specflow.spec.ContextItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("模板文件读写")
class TemplateStoreTest {

    @TempDir
    Path root;

    private TemplateStore store() {
        return new TemplateStore(root);
    }

    /** 模板名同时是文件名，所以样本的名字由调用方给——存成什么名字就写什么名字。 */
    private PromptTemplate sample(String name) {
        return PromptTemplate.of(name, List.of("class", "java"), "后端模具",
                "你是后端工程师",
                List.of(ContextItem.of(null, "docs/order.sql", null, "表结构")));
    }

    @Test
    @DisplayName("存下来再读回去，标签、角色与默认上下文都在")
    void savesAndLoads() {
        store().save(sample("spring-backend"));

        PromptTemplate loaded = store().load("spring-backend");
        assertThat(loaded.tags()).containsExactly("class", "java");
        assertThat(loaded.description()).isEqualTo("后端模具");
        assertThat(loaded.system()).contains("你是后端工程师");
        assertThat(loaded.context()).singleElement()
                .satisfies(item -> assertThat(item.ref()).isEqualTo("docs/order.sql"));
    }

    @Test
    @DisplayName("模板不装需求：没有 user、也没有输入字段声明")
    void templateCarriesNoRequirement() {
        store().save(sample("spring-backend"));

        String yaml = store().toYaml(store().load("spring-backend"));

        assertThat(yaml).doesNotContain("user:").doesNotContain("fields:");
    }

    @Test
    @DisplayName("老模板里的 type-name 会被认出来，并给出「改用 tags」的迁移提示")
    void guidesMigrationFromRetiredField() throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("old.yaml"), """
                name: old
                type-name: class
                system: 你是工程师
                """);

        assertThat(store().loadAll().broken()).singleElement()
                .satisfies(item -> {
                    assertThat(item.name()).isEqualTo("old");
                    assertThat(item.reason()).contains("type-name").contains("已废弃").contains("tags");
                });
    }

    @Test
    @DisplayName("标签去重、去空白；过长或过多会被拦住——它不该变成第二个提示词")
    void normalizesTags() {
        assertThat(PromptTemplate.of("t", List.of("class", "java"), "", "s", List.of()).tags())
                .containsExactly("class", "java");

        assertThat(PromptTemplate.of("t", Arrays.asList("java", " java ", "", "  ", "go"),
                "", "s", List.of()).tags()).containsExactly("java", "go");

        assertThatThrownBy(() -> PromptTemplate.of("t", List.of("x".repeat(25)), "", "s", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签过长");

        assertThatThrownBy(() -> PromptTemplate.of("t",
                List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m"),
                "", "s", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多 12 个");
    }

    @Test
    @DisplayName("没有标签的模板照常工作")
    void tagsAreOptional() {
        store().save(PromptTemplate.of("plain", List.of(), "", "你是工程师", List.of()));

        assertThat(store().load("plain").tags()).isEmpty();
    }

    @Test
    @DisplayName("模板名会变成文件名，路径穿越必须被挡住")
    void rejectsUnsafeName() {
        assertThatThrownBy(() -> store().save(sample("../evil")))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("模板名非法");
        assertThatThrownBy(() -> store().load("a/b"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("模板名非法");
    }

    @Test
    @DisplayName("中文模板名是允许的")
    void allowsChineseName() {
        store().save(sample("接口1"));

        assertThat(store().exists("接口1")).isTrue();
        assertThat(store().load("接口1").tags()).containsExactly("class", "java");
    }

    @Test
    @DisplayName("以双下划线开头的名字被挡住——那是界面里「自由输入」「新建模板」的保留值")
    void rejectsReservedName() {
        // 叫 __new__ 的模板在下拉框里永远选不中：一选就弹新建。所以在入口处断掉。
        assertThatThrownBy(() -> store().save(sample("__new__")))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("不能以 __ 开头");
        assertThatThrownBy(() -> store().save(sample("__free__")))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("不能以 __ 开头");
    }

    @Test
    @DisplayName("源码视图：能把模板转成 YAML，也能把 YAML 解析回来")
    void parsesAndRendersYaml() {
        String yaml = store().toYaml(sample("spring-backend"));

        assertThat(yaml).contains("tags").contains("spring-backend");
        assertThat(store().parse(yaml, "界面源码").name()).isEqualTo("spring-backend");
    }

    @Test
    @DisplayName("源码视图里缺必填字段时，在点「应用」的那一刻就报错")
    void rejectsBrokenSource() {
        assertThatThrownBy(() -> store().parse("name: x\n", "界面源码"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("角色提示词不能为空");

        assertThatThrownBy(() -> store().parse("system: 你是工程师\n", "界面源码"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("模板名不能为空");
    }

    @Test
    @DisplayName("删除之后再读会明确报错，而不是给出一个空模板")
    void deletes() {
        store().save(sample("spring-backend"));
        store().delete("spring-backend");

        assertThat(store().exists("spring-backend")).isFalse();
        assertThatThrownBy(() -> store().load("spring-backend"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("找不到模板");
        assertThatThrownBy(() -> store().delete("spring-backend"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("找不到要删除的模板");
    }

    @Test
    @DisplayName("目录不存在时加载为空，不是报错")
    void emptyWhenDirectoryMissing() {
        assertThat(store().loadAll().templates()).isEmpty();
    }

    @Test
    @DisplayName("一个坏文件不会连累别人：它进 broken，其余照常可用")
    void oneBrokenFileDoesNotTakeDownTheRest() throws IOException {
        Files.createDirectories(root);
        store().save(sample("good"));
        Files.writeString(root.resolve("broken.yaml"), "name: broken\n");

        TemplateStore.Loaded loaded = store().loadAll();

        assertThat(loaded.templates()).containsOnlyKeys("good");
        assertThat(loaded.broken()).singleElement()
                .satisfies(item -> assertThat(item.name()).isEqualTo("broken"));
    }

    @Test
    @DisplayName("0 字节的文件也只是 broken，不是把整个列表带下去，而且说的是人话")
    void emptyFileIsJustBroken() throws IOException {
        Files.createDirectories(root);
        store().save(sample("good"));
        Files.writeString(root.resolve("zzempty.yaml"), "");

        TemplateStore.Loaded loaded = store().loadAll();

        assertThat(loaded.templates()).containsOnlyKeys("good");
        assertThat(loaded.broken()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("zzempty");
            // 不是 Jackson 那句「No content to map due to end-of-input」
            assertThat(item.reason()).contains("是空的").doesNotContain("end-of-input");
        });
    }

    @Test
    @DisplayName("列表顺序稳定：按文件名排，不随 JVM 每次的哈希盐变")
    void loadAllKeepsOrder() {
        store().save(sample("charlie"));
        store().save(sample("alpha"));
        store().save(sample("bravo"));

        assertThat(store().loadAll().templates().keySet())
                .containsExactly("alpha", "bravo", "charlie");
    }

    @Test
    @DisplayName("手工建的 .yml 也读得到、删得掉——扫描时认它，别的路径就不能不认")
    void readsAndDeletesYmlFiles() throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("handmade.yml"), """
                name: handmade
                system: 你是工程师
                """);

        assertThat(store().loadAll().templates()).containsOnlyKeys("handmade");
        assertThat(store().load("handmade").name()).isEqualTo("handmade");

        store().delete("handmade");
        assertThat(store().loadAll().templates()).isEmpty();
    }

    @Test
    @DisplayName("太大以至于读不回来的模板，保存那一刻就被挡住")
    void rejectsTemplateTooBigToReadBack() {
        // snakeyaml 对单份文档有硬上限；直接落盘的话这份文件从此读不出来，
        // 而列表是全量加载的，它会连整个界面和下次启动一起带下去
        assertThatThrownBy(() -> store().save(
                PromptTemplate.of("huge", List.of(), "", "X".repeat(3_200_000), List.of())))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("超过 YAML 的解析上限");

        assertThat(store().loadAll().templates()).isEmpty();
        assertThat(store().loadAll().broken()).isEmpty();
    }

    @Test
    @DisplayName("保存失败不会留下半个文件——目录里干干净净")
    void failedSaveLeavesNothingBehind() throws IOException {
        assertThatThrownBy(() -> store().save(
                PromptTemplate.of("huge", List.of(), "", "X".repeat(3_200_000), List.of())))
                .isInstanceOf(SpecflowException.class);

        assertThat(Files.list(root).toList()).isEmpty();
    }
}
