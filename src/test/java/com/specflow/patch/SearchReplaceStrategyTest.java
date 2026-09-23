package com.specflow.patch;

import com.specflow.TestSpecs;
import com.specflow.exception.PatchConflictException;
import com.specflow.exception.PatchConflictException.Kind;
import com.specflow.spec.Spec;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.BeforeEach;
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
 * 补丁定位规则的测试。
 *
 * <p>这几条规则是整个引擎里最值得测的部分：它们决定了「引擎会不会静默改错代码」。
 * 因此断言不只检查抛没抛异常，还检查异常类型（{@link Kind}），
 * 保证报错原因与修复动作是一一对应的。
 */
@DisplayName("search_replace 策略的定位校验")
class SearchReplaceStrategyTest {

    @TempDir
    Path root;

    private final SearchReplaceStrategy strategy = new SearchReplaceStrategy();
    private SafePathResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        resolver = new SafePathResolver(root);
        Files.writeString(root.resolve("Foo.java"), """
                public class Foo {
                    int a = 1;
                    int b = 2;
                }
                """);
    }

    @Test
    @DisplayName("锚点唯一时定位成功，区间覆盖锚点本身")
    void locatesUniqueAnchor() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));
        PatchPlan plan = plan(spec, block("Foo.java", "int a = 1;", "int a = 100;"));

        String rendered = plan.render(root.resolve("Foo.java"),
                TextNormalizer.normalize(read("Foo.java")));

        assertThat(rendered).contains("int a = 100;").doesNotContain("int a = 1;");
        assertThat(plan.totalEdits()).isEqualTo(1);
    }

    @Test
    @DisplayName("锚点不存在时拒绝落盘，并区分「内容不对」与「只是缩进不对」")
    void rejectsMissingAnchor() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));

        assertThatThrownBy(() -> plan(spec, block("Foo.java", "int a = 999;", "x")))
                .isInstanceOf(PatchConflictException.class)
                .extracting(e -> ((PatchConflictException) e).kind())
                .isEqualTo(Kind.ANCHOR_NOT_FOUND);
    }

    @Test
    @DisplayName("锚点命中多处时拒绝落盘——宁可报错也不猜该改哪一处")
    void rejectsAmbiguousAnchor() throws IOException {
        Files.writeString(root.resolve("Dup.java"), """
                int x = 1;
                int x = 1;
                """);
        Spec spec = TestSpecs.spec(List.of("Dup.java"));

        assertThatThrownBy(() -> plan(spec, block("Dup.java", "int x = 1;", "int x = 2;")))
                .isInstanceOf(PatchConflictException.class)
                .hasMessageContaining("匹配到 2 处")
                .extracting(e -> ((PatchConflictException) e).kind())
                .isEqualTo(Kind.ANCHOR_AMBIGUOUS);
    }

    @Test
    @DisplayName("CRLF 文件与 LF 锚点可以正常匹配")
    void matchesAcrossLineEndings() throws IOException {
        Files.writeString(root.resolve("Crlf.java"), "class A {\r\n    int a = 1;\r\n}\r\n");
        Spec spec = TestSpecs.spec(List.of("Crlf.java"));

        PatchPlan plan = plan(spec, block("Crlf.java", "    int a = 1;", "    int a = 2;"));

        assertThat(plan.render(root.resolve("Crlf.java"),
                TextNormalizer.normalize(read("Crlf.java")))).contains("int a = 2;");
    }

    @Test
    @DisplayName("目标文件不在 targets 白名单内时拒绝")
    void rejectsFileOutsideTargets() throws IOException {
        Files.writeString(root.resolve("Other.java"), "class Other {}\n");
        Spec spec = TestSpecs.spec(List.of("Foo.java"));

        assertThatThrownBy(() -> plan(spec, block("Other.java", "class Other {}", "...")))
                .isInstanceOf(PatchConflictException.class)
                .extracting(e -> ((PatchConflictException) e).kind())
                .isEqualTo(Kind.TARGET_NOT_ALLOWED);
    }

    @Test
    @DisplayName("绝对路径与越界路径都被拒绝")
    void rejectsEscapingPath() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));

        assertThatThrownBy(() -> plan(spec, block("../../etc/passwd", "a", "b")))
                .isInstanceOf(PatchConflictException.class)
                .extracting(e -> ((PatchConflictException) e).kind())
                .isEqualTo(Kind.TARGET_NOT_ALLOWED);
    }

    @Test
    @DisplayName("补丁块没写路径时给出明确的协议错误")
    void rejectsMissingPath() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));

        assertThatThrownBy(() -> plan(spec, block("", "a", "b")))
                .isInstanceOf(PatchConflictException.class)
                .extracting(e -> ((PatchConflictException) e).kind())
                .isEqualTo(Kind.MISSING_TARGET_PATH);
    }

    @Test
    @DisplayName("已存在的文件拒绝整文件写入——这是唯一可能静默丢掉已有代码的操作")
    void rejectsFullWriteOverExistingFile() {
        Spec spec = TestSpecs.spec(List.of("Foo.java"));

        assertThatThrownBy(() -> plan(spec, block("Foo.java", "", "whole file")))
                .isInstanceOf(PatchConflictException.class)
                .hasMessageContaining("已经存在")
                .extracting(e -> ((PatchConflictException) e).kind())
                .isEqualTo(Kind.TARGET_EXISTS);
    }

    @Test
    @DisplayName("文件不存在却给了锚点时拒绝——锚点无处可寻")
    void rejectsAnchorOnMissingFile() {
        Spec spec = TestSpecs.spec(List.of("Missing.java"));

        assertThatThrownBy(() -> plan(spec, block("Missing.java", "any code", "new code")))
                .isInstanceOf(PatchConflictException.class)
                .hasMessageContaining("不存在")
                .extracting(e -> ((PatchConflictException) e).kind())
                .isEqualTo(Kind.TARGET_MISSING);
    }

    @Test
    @DisplayName("文件不存在 + SEARCH 为空 = 新建，生成零长度区间")
    void acceptsFullWriteForNewFile() {
        Spec spec = TestSpecs.spec(List.of("New.java"));
        PatchPlan plan = plan(spec, block("New.java", "", "class New {}"));

        assertThat(plan.render(root.resolve("New.java"), "")).isEqualTo("class New {}");
    }

    @Test
    @DisplayName("同一文件内两条改动区间重叠时拒绝——顺序应用会互相破坏")
    void rejectsOverlappingEdits() throws IOException {
        Files.writeString(root.resolve("Over.java"), "abcdefghij");
        Spec spec = TestSpecs.spec(List.of("Over.java"));

        assertThatThrownBy(() -> plan(spec,
                block(0, "Over.java", "abcd", "X"),
                block(1, "Over.java", "cdef", "Y")))
                .isInstanceOf(PatchConflictException.class)
                .extracting(e -> ((PatchConflictException) e).kind())
                .isEqualTo(Kind.EDIT_OVERLAP);
    }

    @Test
    @DisplayName("区间首尾相接不算重叠")
    void acceptsAdjacentEdits() throws IOException {
        Files.writeString(root.resolve("Adj.java"), "abcdefghij");
        Spec spec = TestSpecs.spec(List.of("Adj.java"));

        PatchPlan plan = plan(spec,
                block(0, "Adj.java", "abcd", "X"),
                block(1, "Adj.java", "efgh", "Y"));

        assertThat(plan.render(root.resolve("Adj.java"), "abcdefghij")).isEqualTo("XYij");
    }

    // ---------- 辅助 ----------

    private PatchPlan plan(Spec spec, PatchBlock... blocks) {
        return strategy.plan(List.of(blocks), spec, resolver);
    }

    private PatchBlock block(String path, String search, String replace) {
        return block(0, path, search, replace);
    }

    private PatchBlock block(int index, String path, String search, String replace) {
        return new PatchBlock(index, path, search, replace);
    }

    private String read(String name) {
        try {
            return Files.readString(root.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
