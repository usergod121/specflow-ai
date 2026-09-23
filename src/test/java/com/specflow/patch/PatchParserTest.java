package com.specflow.patch;

import com.specflow.exception.PatchConflictException;
import com.specflow.exception.PatchConflictException.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("补丁块解析")
class PatchParserTest {

    private final PatchParser parser = new PatchParser();

    @Test
    @DisplayName("解析单个补丁块，路径取自 SEARCH 标记行")
    void parsesSingleBlock() {
        List<PatchBlock> blocks = parser.parse("""
                <<<<<<< SEARCH src/main/java/com/demo/Foo.java
                int a = 1;
                =======
                int a = 2;
                >>>>>>> REPLACE
                """);

        assertThat(blocks).hasSize(1);
        PatchBlock block = blocks.get(0);
        assertThat(block.index()).isZero();
        assertThat(block.path()).isEqualTo("src/main/java/com/demo/Foo.java");
        assertThat(block.search()).isEqualTo("int a = 1;");
        assertThat(block.replace()).isEqualTo("int a = 2;");
        assertThat(block.isFullWrite()).isFalse();
    }

    @Test
    @DisplayName("一次响应中的多个块按顺序解析")
    void parsesMultipleBlocks() {
        List<PatchBlock> blocks = parser.parse("""
                <<<<<<< SEARCH a.txt
                one
                =======
                ONE
                >>>>>>> REPLACE
                <<<<<<< SEARCH b.txt
                two
                =======
                TWO
                >>>>>>> REPLACE
                """);

        assertThat(blocks).extracting(PatchBlock::index).containsExactly(0, 1);
        assertThat(blocks).extracting(PatchBlock::path).containsExactly("a.txt", "b.txt");
    }

    @Test
    @DisplayName("整段响应被 markdown 围栏包裹时自动剥掉")
    void toleratesOuterFence() {
        List<PatchBlock> blocks = parser.parse("""
                ```java
                <<<<<<< SEARCH a.txt
                old
                =======
                new
                >>>>>>> REPLACE
                ```
                """);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).replace()).isEqualTo("new");
    }

    @Test
    @DisplayName("块前后的解释性文字不会影响解析")
    void ignoresSurroundingProse() {
        List<PatchBlock> blocks = parser.parse("""
                好的，下面是修改：
                <<<<<<< SEARCH a.txt
                old
                =======
                new
                >>>>>>> REPLACE
                以上是全部改动。
                """);

        assertThat(blocks).hasSize(1);
    }

    @Test
    @DisplayName("SEARCH 段落两侧的空行被剥掉——模型经常多带一行")
    void stripsBlankEdgesOfSearch() {
        List<PatchBlock> blocks = parser.parse("""
                <<<<<<< SEARCH a.txt

                old

                =======
                new
                >>>>>>> REPLACE
                """);

        assertThat(blocks.get(0).search()).isEqualTo("old");
    }

    @Test
    @DisplayName("SEARCH 为空表示整文件写入")
    void emptySearchMeansFullWrite() {
        List<PatchBlock> blocks = parser.parse("""
                <<<<<<< SEARCH new.txt
                =======
                hello
                >>>>>>> REPLACE
                """);

        assertThat(blocks.get(0).isFullWrite()).isTrue();
        assertThat(blocks.get(0).replace()).isEqualTo("hello");
    }

    @Test
    @DisplayName("缺少分隔行时拒绝解析，而不是猜测边界")
    void failsWhenSeparatorMissing() {
        assertThatThrownBy(() -> parser.parse("""
                <<<<<<< SEARCH a.txt
                old
                >>>>>>> REPLACE
                """))
                .isInstanceOf(PatchConflictException.class)
                .hasMessageContaining("缺少 '======='");
    }

    @Test
    @DisplayName("响应为空时报 NO_BLOCK_PARSED")
    void failsOnEmptyResponse() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(PatchConflictException.class)
                .extracting(e -> ((PatchConflictException) e).kind())
                .isEqualTo(Kind.NO_BLOCK_PARSED);
    }

    @Test
    @DisplayName("响应里全是文字、没有标记时，报错并提示模型没遵守协议")
    void failsWhenNoMarker() {
        assertThatThrownBy(() -> parser.parse("我修改了文件。"))
                .isInstanceOf(PatchConflictException.class)
                .hasMessageContaining("未找到任何");
    }
}
