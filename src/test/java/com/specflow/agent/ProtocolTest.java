package com.specflow.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型答复的解析：四种一轮都要认对——工具块 / json 块 / 两者混在一轮 / 什么都没有。
 *
 * <p>钉的是「解析层不许抛」：认不出来只返回 {@link Protocol.Kind#UNPARSABLE} 加一句原因。
 */
class ProtocolTest {

    @Test
    void parsesToolBlock() {
        Protocol.Parsed parsed = Protocol.parse("""
                我先看一眼目录。

                ```specflow-tool
                {"name":"read_list","arguments":{"path":"src/main/java"}}
                ```
                """);

        assertThat(parsed.kind()).isEqualTo(Protocol.Kind.TOOL);
        assertThat(parsed.tool()).isEqualTo("read_list");
        assertThat(parsed.args()).isEqualTo("{\"path\":\"src/main/java\"}");
        assertThat(parsed.problem()).isEmpty();
    }

    @Test
    void toolBlockWithoutArgumentsMeansEmptyTable() {
        Protocol.Parsed parsed = Protocol.parse("""
                ```specflow-tool
                {"name":"submit"}
                ```
                """);

        assertThat(parsed.kind()).isEqualTo(Protocol.Kind.TOOL);
        assertThat(parsed.tool()).isEqualTo("submit");
        assertThat(parsed.args()).isEqualTo("{}");
    }

    @Test
    void parsesAnswerBlock() {
        Protocol.Parsed parsed = Protocol.parse("""
                做完了。

                ```json
                {"cases":[{"id":"c1"}]}
                ```
                """);

        assertThat(parsed.kind()).isEqualTo(Protocol.Kind.ANSWER);
        assertThat(parsed.answer()).isEqualTo("{\"cases\":[{\"id\":\"c1\"}]}");
    }

    @Test
    void rejectsToolAndAnswerInOneRound() {
        Protocol.Parsed parsed = Protocol.parse("""
                ```specflow-tool
                {"name":"read_list","arguments":{}}
                ```

                ```json
                {"done":true}
                ```
                """);

        assertThat(parsed.kind()).isEqualTo(Protocol.Kind.UNPARSABLE);
        assertThat(parsed.problem()).contains("一轮").contains("2");
    }

    @Test
    void rejectsEmptyAndPlainText() {
        assertThat(Protocol.parse("").kind()).isEqualTo(Protocol.Kind.UNPARSABLE);
        assertThat(Protocol.parse(null).kind()).isEqualTo(Protocol.Kind.UNPARSABLE);
        assertThat(Protocol.parse("我想想再说吧。").problem()).isNotBlank();
    }

    @Test
    void rejectsForeignFence() {
        Protocol.Parsed parsed = Protocol.parse("""
                ```java
                int x = 1;
                ```
                """);

        assertThat(parsed.kind()).isEqualTo(Protocol.Kind.UNPARSABLE);
        assertThat(parsed.problem()).contains("java");
    }

    @Test
    void rejectsBrokenToolBlockWithoutThrowing() {
        assertThat(Protocol.parse("```specflow-tool\n{不是 JSON\n```").problem()).contains("JSON");
        assertThat(Protocol.parse("```specflow-tool\n{\"arguments\":{}}\n```").problem()).contains("name");
        assertThat(Protocol.parse("```specflow-tool\n{\"name\":\"x\",\"arguments\":\"pom.xml\"}\n```")
                .problem()).contains("arguments");
    }

    @Test
    void instructionsAndRetryNoticeMentionBothFences() {
        assertThat(Protocol.instructions()).contains(Protocol.TOOL_FENCE).contains(Protocol.ANSWER_FENCE);
        assertThat(Protocol.retryNotice("没有代码块"))
                .contains("没有代码块").contains(Protocol.TOOL_FENCE);
    }
}
