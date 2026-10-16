package com.specflow.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册表是「模型写错了」与「引擎炸了」的分界：名字不认、参数不是 JSON、缺必填，
 * 三种都是一份<b>可读的</b> {@code ok=false}，绝不抛出去。
 */
class ToolRegistryTest {

    private static final class Echo implements Tool {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显一句话";
        }

        @Override
        public Map<String, Param> params() {
            return Map.of("text", Param.required("string", "要回显的话"));
        }

        @Override
        public ToolResult run(Map<String, Object> args) {
            return ToolResult.ok(Map.of("echo", String.valueOf(args.get("text"))));
        }
    }

    private static final class Boom implements Tool {

        @Override
        public String name() {
            return "boom";
        }

        @Override
        public String description() {
            return "一定会炸";
        }

        @Override
        public Map<String, Param> params() {
            return Map.of();
        }

        @Override
        public ToolResult run(Map<String, Object> args) {
            throw new IllegalStateException("内部炸了");
        }
    }

    private final ToolRegistry registry = ToolRegistry.of(new Echo(), new Boom());

    @Test
    void unknownNameListsWhatIsAvailable() {
        ToolResult result = registry.call("read", "{}");

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("read").contains("echo").contains("boom");
        assertThat(result.json()).contains("\"ok\":false");
    }

    @Test
    void argumentsThatAreNotJson() {
        ToolResult result = registry.call("echo", "{oops");

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("JSON").contains("echo");
    }

    @Test
    void missingRequiredParameter() {
        ToolResult result = registry.call("echo", "{}");

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("text");
    }

    @Test
    void unknownParameter() {
        ToolResult result = registry.call("echo", "{\"file\":\"pom.xml\"}");

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("file").contains("text");
    }

    @Test
    void crashingToolBecomesAFailure() {
        ToolResult result = registry.call("boom", "{}");

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("内部炸了");
    }

    @Test
    void goodCallReturnsData() {
        ToolResult result = registry.call("echo", "{\"text\":\"你好\"}");

        assertThat(result.ok()).isTrue();
        assertThat(result.json()).contains("你好");
    }

    @Test
    void duplicateNameIsAWiringBug() {
        assertThatThrownBy(() -> ToolRegistry.of(new Echo(), new Echo()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void describeListsEveryToolWithItsParameters() {
        String text = registry.describe();

        assertThat(text).contains("### echo").contains("### boom")
                .contains("text").contains("必填").contains("参数：无");
    }

    @Test
    void allKeepsTheAssemblyOrder() {
        assertThat(registry.all()).extracting(Tool::name).containsExactly("echo", "boom");
    }
}
