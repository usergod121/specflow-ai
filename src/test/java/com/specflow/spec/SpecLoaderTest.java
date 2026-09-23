package com.specflow.spec;

import com.specflow.exception.SpecValidationException;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("spec 的加载与校验")
class SpecLoaderTest {

    @TempDir
    Path root;

    private final SpecLoader loader = new SpecLoader();
    private final SpecValidator validator = new SpecValidator();

    @Test
    @DisplayName("完整 spec 可以解析出全部字段")
    void parsesFullSpec() {
        Spec spec = loader.parse("""
                version: 1
                strategy: search_replace
                template: implement
                variables:
                  requirement: 加一个接口
                targets:
                  - src/main/java/demo/Foo.java
                constraints:
                  - 不引入新依赖
                context:
                  - ref: src/main/java/demo/UserController.java
                    note: 照这个已有接口的风格写
                  - name: 订单表结构
                    text: CREATE TABLE orders (id BIGINT)
                verify:
                  compile: true
                  max-retry: 2
                trace:
                  requirement-id: REQ-1
                """, "test");

        assertThat(spec.strategy()).isEqualTo(PatchStrategyType.SEARCH_REPLACE);
        assertThat(spec.template()).isEqualTo("implement");
        assertThat(spec.variables()).containsEntry("requirement", "加一个接口");
        assertThat(spec.targets()).containsExactly("src/main/java/demo/Foo.java");
        assertThat(spec.constraints()).containsExactly("不引入新依赖");
        assertThat(spec.context()).hasSize(2);
        assertThat(spec.context().get(0).name()).isEqualTo("UserController.java");
        assertThat(spec.context().get(0).hasRef()).isTrue();
        assertThat(spec.context().get(0).note()).isEqualTo("照这个已有接口的风格写");
        assertThat(spec.context().get(1).name()).isEqualTo("订单表结构");
        assertThat(spec.context().get(1).hasRef()).isFalse();
        assertThat(spec.verify().maxRetry()).isEqualTo(2);
        assertThat(spec.trace().requirementId()).isEqualTo("REQ-1");
    }

    @Test
    @DisplayName("省略 version/strategy 时取默认值")
    void appliesDefaults() {
        Spec spec = loader.parse("""
                prompt: 改点东西
                targets: [a.txt]
                """, "test");

        assertThat(spec.version()).isEqualTo(Spec.CURRENT_VERSION);
        assertThat(spec.strategy()).isEqualTo(PatchStrategyType.SEARCH_REPLACE);
        assertThat(spec.verify()).isEqualTo(VerifySpec.DEFAULT);
    }

    @Test
    @DisplayName("target_paths 是 targets 的别名，写错字段名不会静默失效")
    void acceptsTargetPathsAlias() {
        Spec spec = loader.parse("""
                prompt: 改点东西
                target_paths: [a.txt]
                """, "test");

        assertThat(spec.targets()).containsExactly("a.txt");
    }

    @Test
    @DisplayName("未知字段直接报错并列出可用字段——拼错字段名必须立刻可见")
    void rejectsUnknownField() {
        assertThatThrownBy(() -> loader.parse("""
                prompt: 改点东西
                target: [a.txt]
                """, "test.yaml"))
                .isInstanceOf(SpecValidationException.class)
                .hasMessageContaining("target")
                .hasMessageContaining("targets");
    }

    @Test
    @DisplayName("mode 已废弃：老 spec 里写了它会被当成未知字段拒绝，而不是被静默忽略")
    void rejectsRemovedModeField() {
        assertThatThrownBy(() -> loader.parse("""
                mode: create
                prompt: 改点东西
                targets: [a.txt]
                """, "test.yaml"))
                .isInstanceOf(SpecValidationException.class)
                .hasMessageContaining("mode");
    }

    @Test
    @DisplayName("枚举值拼错时报错")
    void rejectsUnknownEnum() {
        assertThatThrownBy(() -> loader.parse("""
                strategy: ast_merge
                prompt: x
                targets: [a.txt]
                """, "test"))
                .isInstanceOf(SpecValidationException.class)
                .hasMessageContaining("ast_merge");
    }

    @Test
    @DisplayName("上下文条目既没有 ref 也没有 text 时报错——无法判断它是什么")
    void rejectsEmptyContextItem() {
        assertThatThrownBy(() -> loader.parse("""
                prompt: x
                targets: [a.txt]
                context:
                  - name: 只有名字
                """, "test"))
                .isInstanceOf(SpecValidationException.class)
                .hasMessageContaining("既没有 ref")
                .hasMessageContaining("也没有 text");
    }

    @Test
    @DisplayName("上下文条目同时给了 ref 和 text 时报错——不知道该用哪个")
    void rejectsAmbiguousContextItem() {
        assertThatThrownBy(() -> loader.parse("""
                prompt: x
                targets: [a.txt]
                context:
                  - ref: a.txt
                    text: 一段文本
                """, "test"))
                .isInstanceOf(SpecValidationException.class)
                .hasMessageContaining("同时给了 ref 和 text");
    }

    @Test
    @DisplayName("上下文条目没写名字时用文件名兜底，界面上不会出现一行空白")
    void derivesContextNameFromRef() {
        Spec spec = loader.parse("""
                prompt: x
                targets: [a.txt]
                context:
                  - ref: src/main/java/demo/UserController.java
                """, "test");

        assertThat(spec.context().get(0).name()).isEqualTo("UserController.java");
    }

    @Test
    @DisplayName("上下文引用的路径越出项目根目录时，校验阶段就拦下来")
    void rejectsEscapingContextRef() {
        SpecValidationException failure = catchThrowableOfType(SpecValidationException.class,
                () -> validate(loader.parse("""
                        prompt: x
                        targets: [a.txt]
                        context:
                          - ref: ../outside.sql
                        """, "test")));

        assertThat(failure.problems()).anySatisfy(problem ->
                assertThat(problem).contains("越出项目根目录"));
    }

    @Test
    @DisplayName("上下文条目重名时拒绝——界面上要能分辨是哪一条")
    void rejectsDuplicateContextName() {
        SpecValidationException failure = catchThrowableOfType(SpecValidationException.class,
                () -> validate(loader.parse("""
                        prompt: x
                        targets: [a.txt]
                        context:
                          - name: 表结构
                            text: A
                          - name: 表结构
                            text: B
                        """, "test")));

        assertThat(failure.problems()).anySatisfy(problem ->
                assertThat(problem).contains("重名"));
    }

    @Test
    @DisplayName("需求必填：只给 targets 是不够的")
    void requiresPrompt() {
        SpecValidationException failure = catchThrowableOfType(SpecValidationException.class,
                () -> validate(loader.parse("""
                        targets: [a.txt]
                        """, "test")));

        assertThat(failure.problems()).anySatisfy(problem ->
                assertThat(problem).contains("prompt 不能为空"));
    }

    @Test
    @DisplayName("需求与模板可以同时存在——它们管的是两件事")
    void acceptsPromptWithTemplate() {
        validate(loader.parse("""
                template: spring-backend
                prompt: 加一个按订单号查询的方法
                targets: [a.txt]
                """, "test"));
    }

    @Test
    @DisplayName("targets 为空、重复或越界时一次性报出全部问题")
    void reportsAllTargetProblems() {
        SpecValidationException failure = catchThrowableOfType(SpecValidationException.class,
                () -> validate(loader.parse("""
                        prompt: x
                        targets:
                          - a.txt
                          - a.txt
                          - ../escape.txt
                        """, "test")));

        assertThat(failure.problems()).hasSize(2);
        assertThat(failure.problems()).anySatisfy(p -> assertThat(p).contains("重复"));
        assertThat(failure.problems()).anySatisfy(p -> assertThat(p).contains("越出项目根目录"));
    }

    @Test
    @DisplayName("version 高于工具支持的上限时拒绝")
    void rejectsFutureVersion() {
        SpecValidationException failure = catchThrowableOfType(SpecValidationException.class,
                () -> validate(loader.parse("""
                        version: 99
                        prompt: x
                        targets: [a.txt]
                        """, "test")));

        assertThat(failure.problems()).anySatisfy(problem ->
                assertThat(problem).contains("99"));
    }

    @Test
    @DisplayName("variables 键名非法时拒绝")
    void rejectsInvalidVariableName() {
        SpecValidationException failure = catchThrowableOfType(SpecValidationException.class,
                () -> validate(loader.parse("""
                        prompt: x
                        targets: [a.txt]
                        variables:
                          "bad-name": v
                        """, "test")));

        assertThat(failure.problems()).anySatisfy(problem ->
                assertThat(problem).contains("bad-name"));
    }

    @Test
    @DisplayName("合法 spec 通过校验")
    void acceptsValidSpec() {
        validate(loader.parse("""
                prompt: x
                targets: [a.txt, b.txt]
                variables:
                  ok_name_1: v
                """, "test"));
    }

    private void validate(Spec spec) {
        validator.validate(spec, new SafePathResolver(root));
    }
}
