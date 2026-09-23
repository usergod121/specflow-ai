package com.specflow.spec;

import com.specflow.template.PromptTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 序列化往返测试。
 *
 * <p>它挡的是一类很隐蔽的 bug：字段<b>读得进来、写不出去</b>。
 * 起因是多词字段在读取端声明的是 kebab-case（{@code compile-command}），
 * 而写出端默认用访问器名（{@code compileCommand}）——写出来的文件自己读不回去。
 *
 * <p>过去只有「读 spec」这一条路，所以这个坑一直没露头；
 * 一旦要让界面<b>保存</b>模板和任务草稿，它就会变成「存了之后就打不开了」。
 */
@DisplayName("序列化往返")
class SpecRoundTripTest {

    @TempDir
    Path root;

    private final SpecLoader loader = new SpecLoader();

    @Test
    @DisplayName("spec 写出去再读回来，每个字段都还在")
    void specSurvivesRoundTrip() throws IOException {
        Spec original = loader.parse("""
                version: 1
                strategy: search_replace
                template: implement
                variables:
                  requirement: 加一个接口
                prompt: |
                  给 Foo 加一个按编号查询的方法
                acceptance:
                  - 传入不存在的编号时返回空，不抛异常
                  - 不改动已有方法
                targets:
                  - src/main/java/demo/Foo.java
                constraints:
                  - 不引入新依赖
                context:
                  - ref: src/main/java/demo/UserController.java
                    note: 照它的风格写
                  - name: 订单表结构
                    text: CREATE TABLE orders (id BIGINT)
                verify:
                  compile: true
                  compile-command: mvn -q -DskipTests compile
                  max-retry: 3
                trace:
                  requirement-id: REQ-1
                """, "original");

        Spec reloaded = loader.parse(YamlRoundTrip.write(original, root), "round-trip");

        assertThat(reloaded.strategy()).isEqualTo(original.strategy());
        assertThat(reloaded.template()).isEqualTo("implement");
        assertThat(reloaded.prompt()).contains("给 Foo 加一个按编号查询的方法");
        assertThat(reloaded.acceptance()).containsExactly(
                "传入不存在的编号时返回空，不抛异常", "不改动已有方法");
        assertThat(reloaded.variables()).containsEntry("requirement", "加一个接口");
        assertThat(reloaded.targets()).containsExactly("src/main/java/demo/Foo.java");
        assertThat(reloaded.constraints()).containsExactly("不引入新依赖");
        assertThat(reloaded.context()).hasSize(2);
        assertThat(reloaded.context().get(0).ref()).isEqualTo("src/main/java/demo/UserController.java");
        assertThat(reloaded.context().get(1).text()).isEqualTo("CREATE TABLE orders (id BIGINT)");
        assertThat(reloaded.verify().compileCommand()).isEqualTo("mvn -q -DskipTests compile");
        assertThat(reloaded.verify().maxRetry()).isEqualTo(3);
        assertThat(reloaded.trace().requirementId()).isEqualTo("REQ-1");
    }

    @Test
    @DisplayName("模板写出去再读回来，标签、角色与默认上下文都还在")
    void templateSurvivesRoundTrip() {
        PromptTemplate original = PromptTemplate.of("spring-backend", List.of("class", "java"), "后端模具",
                "你是后端工程师，项目名是 {{project}}",
                List.of(ContextItem.of(null, "docs/order.sql", null, "表结构")));

        PromptTemplate reloaded = YamlRoundTrip.readTemplate(YamlRoundTrip.write(original));

        assertThat(reloaded.name()).isEqualTo("spring-backend");
        assertThat(reloaded.tags()).containsExactly("class", "java");
        assertThat(reloaded.description()).isEqualTo("后端模具");
        assertThat(reloaded.system()).contains("{{project}}");
        assertThat(reloaded.context()).singleElement().satisfies(item -> {
            assertThat(item.ref()).isEqualTo("docs/order.sql");
            assertThat(item.name()).isEqualTo("order.sql");
        });
    }

    @Test
    @DisplayName("写出来的 YAML 用的是人写的那种字段名，不是 Java 的驼峰名")
    void writesReadableFieldNames() {
        String yaml = YamlRoundTrip.write(new VerifySpec(true, "mvn compile", 3),
                new TraceSpec("REQ-1"));

        assertThat(yaml).contains("compile-command").contains("max-retry")
                .contains("requirement-id");
        assertThat(yaml).doesNotContain("compileCommand").doesNotContain("maxRetry")
                .doesNotContain("requirementId");
    }

    /** 供 save 用的最小 YAML 写出，不动磁盘时也能跑。 */
    static final class YamlRoundTrip {

        private static final com.fasterxml.jackson.dataformat.yaml.YAMLMapper YAML =
                new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();

        static String write(Object value) {
            try {
                return YAML.writeValueAsString(value);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static String write(Object value, Path root) throws IOException {
            Path file = root.resolve("round-trip.yaml");
            YAML.writeValue(file.toFile(), value);
            return Files.readString(file);
        }

        static PromptTemplate readTemplate(String yaml) {
            try {
                return YAML.readValue(yaml, PromptTemplate.class);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 顺带把「两个对象一起写」的用法也覆盖到：Spec 里本来就嵌着这两层。 */
        static String write(VerifySpec verify, TraceSpec trace) {
            return write(Map.of("verify", verify, "trace", trace));
        }
    }
}
