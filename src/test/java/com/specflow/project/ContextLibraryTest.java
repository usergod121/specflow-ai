package com.specflow.project;

import com.specflow.exception.SpecflowException;
import com.specflow.spec.ContextItem;
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
 * 上下文导出的测试。
 *
 * <p>重点在「存得进去的东西读得回来」：导出这个功能的价值全在另一端——
 * 文件换台机器打开、被别的项目引用。所以除了内容，还专门验一次
 * <b>文件里多出未知字段</b>的情形：那是这个功能最容易悄悄失效的地方。
 */
@DisplayName("上下文导出")
class ContextLibraryTest {

    @TempDir
    Path root;

    private ContextLibrary library() {
        return new ContextLibrary(root);
    }

    private List<ContextItem> sample() {
        return List.of(
                ContextItem.of("UserController.java", "README.md", null, "照它的风格写"),
                ContextItem.of("订单表结构", null, "CREATE TABLE orders (id BIGINT)", ""),
                ContextItem.of("约定", null, "接口一律返回 JSON", "团队约定"));
    }

    @Test
    @DisplayName("导出之后能在清单里看到，且落在 .specflow/context 下")
    void savesIntoTheLibrary() {
        Path written = library().save("订单上下文", sample());

        assertThat(written).isEqualTo(root.resolve(".specflow/context/订单上下文.yaml"));
        assertThat(Files.isRegularFile(written)).isTrue();
        assertThat(library().directory()).isEqualTo(root.resolve(ContextLibrary.DEFAULT_DIR));
        assertThat(library().names()).containsExactly("订单上下文");
    }

    @Test
    @DisplayName("文件里记着来自哪个项目、什么时候导出的——换台机器打开时要知道它的来历")
    void recordsWhereItCameFrom() {
        Path written = library().save("一套", sample());

        ContextLibrary.Bundle bundle = library().load("一套");
        assertThat(bundle.name()).isEqualTo("一套");
        assertThat(bundle.project()).isEqualTo(root.getFileName().toString());
        assertThat(bundle.exportedAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        assertThat(Files.isRegularFile(written)).isTrue();
    }

    @Test
    @DisplayName("三种形态（引用 / 内联 / 带说明）存回来内容一致")
    void roundTripsEveryShape() {
        library().save("订单上下文", sample());

        List<ContextItem> loaded = library().load("订单上下文").items();

        assertThat(loaded).hasSize(3);
        ContextItem reference = loaded.get(0);
        assertThat(reference.name()).isEqualTo("UserController.java");
        assertThat(reference.ref()).isEqualTo("README.md");
        assertThat(reference.text()).isNull();
        assertThat(reference.note()).isEqualTo("照它的风格写");

        ContextItem inline = loaded.get(1);
        assertThat(inline.ref()).isNull();
        assertThat(inline.text()).isEqualTo("CREATE TABLE orders (id BIGINT)");
        assertThat(inline.note()).isEmpty();

        assertThat(loaded.get(2).note()).isEqualTo("团队约定");
    }

    @Test
    @DisplayName("名字会变成文件名，路径穿越必须被挡住")
    void rejectsUnsafeName() {
        assertThatThrownBy(() -> library().save("../../evil", sample()))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("上下文名非法");
        // 分隔符能在目录里造出另一个文件，和 .. 一样不能放过
        assertThatThrownBy(() -> library().save("a/b", sample()))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("上下文名非法");
        assertThatThrownBy(() -> library().load(".."))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("上下文名非法");

        assertThat(library().names()).isEmpty();
    }

    @Test
    @DisplayName("一条内容都没有的上下文存不进去——空文件导出去只会让人以为丢了东西")
    void rejectsEmptyItems() {
        assertThatThrownBy(() -> library().save("空的", List.of()))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("一条内容都没有");
        assertThatThrownBy(() -> library().save("空的", null))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("一条内容都没有");
    }

    @Test
    @DisplayName("引用的路径越出项目根时存不进去：导出走的是和 spec 同一套路径规则")
    void rejectsEscapingReference() {
        List<ContextItem> escaping = List.of(
                ContextItem.of("外面的文件", "../outside.java", null, ""));

        assertThatThrownBy(() -> library().save("越界", escaping))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("越出项目根目录");

        assertThat(library().names()).isEmpty();
    }

    @Test
    @DisplayName("名字为空的条目存不进去：界面和留档都要靠名字分辨它是哪一条")
    void rejectsUnnamedItem() {
        // 只能绕过 ContextItem.of 直接构造：那条正规路径会给空名字兜一个默认名，
        // 于是「没有名字」这件事只剩从别处拼出来的条目才可能发生
        List<ContextItem> unnamed = List.of(new ContextItem("  ", null, "一段文本", ""));

        assertThatThrownBy(() -> library().save("没名字", unnamed))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("没有名字");
    }

    @Test
    @DisplayName("载入不存在的上下文时明确报错，而不是给一份空的")
    void loadMissingFails() {
        assertThatThrownBy(() -> library().load("没有这套"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("找不到这套上下文");
    }

    @Test
    @DisplayName("目录还不存在时清单为空，而不是报错")
    void emptyWhenDirectoryMissing() {
        assertThat(library().names()).isEmpty();
    }

    @Test
    @DisplayName("按名字排序，界面上的顺序每次都一样")
    void listsSorted() {
        library().save("乙", sample());
        library().save("甲", sample());
        library().save("a", sample());

        assertThat(library().names()).containsExactly("a", "乙", "甲");
    }

    /**
     * 这个文件是可以被手工编辑的，也可能来自更新版本的 specflow。
     * 认不出来的字段会让读取直接失败——而 {@link ContextLibrary#load} 一失败，
     * 界面上就是「我导出的东西打不开了」，恰恰是导出功能最不该出的问题。
     */
    @Test
    @DisplayName("文件里多出未知字段时照常读得出来")
    void toleratesUnknownFields() throws IOException {
        Path dir = root.resolve(ContextLibrary.DEFAULT_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("老的.yaml"), """
                name: 老的
                project: demo
                exportedAt: 2026-01-01 00:00
                version: 2
                exportedBy: 某人
                items:
                  - name: README.md
                    ref: README.md
                    note: 照它写
                """);

        assertThat(library().names()).containsExactly("老的");
        ContextLibrary.Bundle bundle = library().load("老的");
        assertThat(bundle.name()).isEqualTo("老的");
        assertThat(bundle.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("README.md");
            assertThat(item.ref()).isEqualTo("README.md");
        });
    }

    /**
     * 一份手改坏的文件只该让<b>那一份</b>读不出来。
     *
     * <p>以前界面走的是「先 {@code names()} 再逐个 {@code load}」，任意一份抛异常，
     * 整次读取就 400——用户看到的是「我导出的东西一套都打不开了」，
     * 而且连「把那份坏的删掉」的入口都没有。分寸和 {@code TemplateStore} 一致：
     * {@link ContextLibrary#load} 报错，{@link ContextLibrary#loadAll} 跳过并记账。
     */
    @Test
    @DisplayName("一份手改坏的 YAML 只让那一份读不出来，别的一份不少")
    void loadAllSkipsBrokenOnes() throws IOException {
        library().save("好的", sample());
        Path dir = root.resolve(ContextLibrary.DEFAULT_DIR);
        Files.writeString(dir.resolve("坏的.yaml"), "name: 坏的\nitems: [这不是: 合法 yaml\n");

        ContextLibrary.Loaded loaded = library().loadAll();

        assertThat(loaded.bundles()).singleElement()
                .satisfies(bundle -> assertThat(bundle.name()).isEqualTo("好的"));
        assertThat(loaded.broken()).singleElement().satisfies(broken -> {
            // 名字要能让人在资源管理器里找到那份文件
            assertThat(broken.name()).isEqualTo("坏的");
            assertThat(broken.reason()).contains("坏的");
        });
        // 单个读取仍然照旧报错：跳过是给「列清单」用的，不是把错误吃掉
        assertThatThrownBy(() -> library().load("坏的"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("读取上下文失败");
    }

    /**
     * 这句提示会原样摆在界面上（「哪一份读不出来」），所以不能带着 Jackson 的类名、
     * 源码位置和它自己的引用链——那三样对用户都没有用，只会把人吓住。
     * 条目形态那两句是 {@code ContextItem} 自己抛的，那才是原因。
     */
    @Test
    @DisplayName("读不出来的原因说的是人话，不是 Jackson 的类名和源码位置")
    void unreadableReasonIsHuman() throws IOException {
        Files.createDirectories(root.resolve(ContextLibrary.DEFAULT_DIR));
        Files.writeString(root.resolve(ContextLibrary.DEFAULT_DIR).resolve("坏的.yaml"),
                "name: 坏的\nitems:\n  - name: 没形态的一条\n");

        assertThatThrownBy(() -> library().load("坏的"))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("没有 ref")
                .hasMessageNotContaining("Cannot construct instance")
                .hasMessageNotContaining("at [Source")
                .hasMessageNotContaining("com.specflow");
    }

    @Test
    @DisplayName("目录里一份都读得出来时，broken 是空表而不是 null")
    void loadAllWithoutBroken() {
        library().save("好的", sample());

        ContextLibrary.Loaded loaded = library().loadAll();

        assertThat(loaded.broken()).isEmpty();
        assertThat(loaded.bundles()).hasSize(1);
    }
}
