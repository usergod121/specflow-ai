package com.specflow.template;

import com.specflow.exception.SpecflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("模板占位符渲染")
class TemplateRendererTest {

    @Test
    @DisplayName("按名字替换占位符，允许括号内有空格")
    void rendersPlaceholders() {
        String rendered = TemplateRenderer.render(
                "实现 {{ requirement }}，验收标准：{{acceptance}}",
                Map.of("requirement", "登录接口", "acceptance", "返回 token"));

        assertThat(rendered).isEqualTo("实现 登录接口，验收标准：返回 token");
    }

    @Test
    @DisplayName("占位符未赋值时直接报错，绝不静默替换成空串")
    void failsOnMissingVariable() {
        assertThatThrownBy(() -> TemplateRenderer.render("实现 {{requirement}}", Map.of()))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("requirement");
    }

    @Test
    @DisplayName("一次报出全部缺失变量，避免用户逐个试")
    void reportsAllMissingVariables() {
        assertThatThrownBy(() -> TemplateRenderer.render("{{a}} {{b}} {{c}}", Map.of("b", "x")))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("a")
                .hasMessageContaining("c");
    }

    @Test
    @DisplayName("无占位符时原样返回")
    void passesThroughPlainText() {
        assertThat(TemplateRenderer.render("没有变量", Map.of())).isEqualTo("没有变量");
    }

    @Test
    @DisplayName("提取占位符时去重且保持出现顺序")
    void extractsPlaceholders() {
        assertThat(TemplateRenderer.placeholders("{{b}} {{a}} {{b}}")).containsExactly("b", "a");
    }

    @Test
    @DisplayName("非法占位符名（含连字符）不被识别，因此不会误报缺失")
    void ignoresInvalidPlaceholderNames() {
        assertThat(TemplateRenderer.placeholders("{{not-valid}}")).isEmpty();
    }

    @Test
    @DisplayName("变量值中的特殊字符不会被当成正则替换组")
    void escapesReplacementCharacters() {
        String rendered = TemplateRenderer.render("{{code}}", Map.of("code", "$1 \\ and $"));

        assertThat(rendered).isEqualTo("$1 \\ and $");
    }
}
