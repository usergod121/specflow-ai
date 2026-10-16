package com.specflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSON / YAML 的唯一入口。
 *
 * <p>为什么值得单独钉：「<b>还没写</b>」与「<b>写错了</b>」必须分得开。
 * 刚铺开的 {@code project.yaml} 整份都是注释，那是正常的起点；
 * 而 Jackson 遇到「没有内容」会抛 {@code No content to map due to end-of-input}
 * ——那句话会原样冒到界面上，用户看不懂，也改不动（他什么都没写错）。
 */
class JsonTest {

    @Test
    @DisplayName("只有注释 / 只有空行 / 文件不在：都算「还没写」，返回 null，不抛")
    void emptyMeansNotWrittenYet(@TempDir Path root) throws Exception {
        Path comments = root.resolve("only-comments.yaml");
        Files.writeString(comments, "# 全是注释\n#   image: \"x\"\n\n");
        assertThat(Json.readYaml(comments, Map.class)).isNull();

        Path blank = root.resolve("blank.yaml");
        Files.writeString(blank, "\n\n   \n");
        assertThat(Json.readYaml(blank, Map.class)).isNull();

        assertThat(Json.readYaml(root.resolve("missing.yaml"), Map.class)).isNull();
    }

    @Test
    @DisplayName("有内容就读得出来（顺便：不认识的字段不炸）")
    void readsRealYaml(@TempDir Path root) throws Exception {
        Path file = root.resolve("project.yaml");
        Files.writeString(file, """
                build:
                  compile: "mvn -q compile"
                llm:
                  model: "deepseek-chat"
                what-is-this: "老版本写的字段"
                """);

        @SuppressWarnings("unchecked")
        Map<String, Object> read = Json.readYaml(file, Map.class);
        assertThat(read).containsKeys("build", "llm");
    }

    @Test
    @DisplayName("YAML 真写坏了：抛，而且话里带上文件名（不然不知道是哪个文件）")
    void brokenYamlSaysWhichFile(@TempDir Path root) throws Exception {
        Path file = root.resolve("env.yaml");
        Files.writeString(file, "image: [没关的方括号\n");

        assertThatThrownBy(() -> Json.readYaml(file, Map.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("env.yaml");
    }

    @Test
    @DisplayName("hasNoContent：这套判据是「还没写」与「写错了」的分界线，两条路共用")
    void hasNoContentRules() {
        assertThat(Json.hasNoContent(null)).isTrue();
        assertThat(Json.hasNoContent("")).isTrue();
        assertThat(Json.hasNoContent("# a\n  \n# b")).isTrue();
        assertThat(Json.hasNoContent("image: \"x\"")).isFalse();
        assertThat(Json.hasNoContent("# 注释在后面\nimage: \"x\"")).isFalse();
    }

    @Test
    @DisplayName("JSON 那一侧：读不出来的话里要说清是哪一段坏了")
    void jsonErrorsAreHuman() {
        assertThatThrownBy(() -> Json.read("{不是 json", Map.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }
}
