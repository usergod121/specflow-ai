package com.specflow.test;

import com.specflow.agent.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>结论必须有据</b>：一次工具调用都没发生就宣布 PASS，直接拒。
 *
 * <h2>为什么（用户实报）</h2>
 * 11 条用例几乎全 PASS，但证据是模型自己写的一段话——引擎核不了。
 * 用户的原话：「证据基本都是"资源不存在"，那怎么能说明 PASS？」。
 * 真相：清单里的 status/evidence 都是模型的口供。
 *
 * <p>引擎唯一数得准的东西是「这条用例期间到底调了几次工具」。所以：
 * <b>PASS 必须伴随至少一次新的工具调用</b>；没有就当场拒，并告诉它先去真跑一遍。
 * 这是这一版能给的、代价最小的"反嘴炮"护栏（真正的证据仍是事件流里那些调用的原文）。
 */
class CaseEvidenceTest {

    private static CaseList listWith(Path root, String id) throws IOException {
        Path file = root.resolve("cases.json");
        Files.writeString(file, """
                [{"id":"%s","what":"验一件事","tags":["功能"],"expect":"返回成功"}]
                """.formatted(id));
        return new CaseList(file);
    }

    private static Map<String, Object> update(String id, String status, String evidence) {
        return Map.of("id", id, "status", status, "evidence", evidence, "note", "");
    }

    @Test
    @DisplayName("一次工具调用都没有就判 PASS：拒（并说明先去真跑一遍）")
    void rejectsPassWithoutAnyToolCall(@TempDir Path root) throws IOException {
        CaseList cases = listWith(root, "C1");
        AtomicInteger calls = new AtomicInteger(0);
        CaseUpdateTool tool = new CaseUpdateTool(cases, null, calls::get);

        ToolResult result = tool.run(update("C1", "PASS", "看着是好的"));

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("一次工具调用都没发生").contains("先真跑一遍");
    }

    @Test
    @DisplayName("期间真跑过工具：PASS 放行，并把调用数记下来（下一条要重新数）")
    void acceptsPassAfterRealCalls(@TempDir Path root) throws IOException {
        CaseList cases = listWith(root, "C1");
        AtomicInteger calls = new AtomicInteger(0);
        CaseUpdateTool tool = new CaseUpdateTool(cases, null, calls::get);

        calls.set(3);
        ToolResult first = tool.run(update("C1", "PASS", "curl 返回 ok"));
        assertThat(first.ok()).as("第一次 PASS 被拒的原因：" + first.reason()).isTrue();

        // C2 期间一次都没跑：拒
        calls.set(3);
        ToolResult second = tool.run(update("C1", "PASS", "还是那句话"));
        assertThat(second.ok()).isFalse();
    }

    @Test
    @DisplayName("FAIL / ERROR 不需要工具调用（它是在如实说「没验成」）")
    void failNeedsNoCalls(@TempDir Path root) throws IOException {
        CaseList cases = listWith(root, "C9");
        CaseUpdateTool tool = new CaseUpdateTool(cases, null, () -> 0);

        ToolResult result = tool.run(Map.of("id", "C9", "status", "ERROR",
                "note", "服务起不来，卡在端口占用", "evidence", ""));

        assertThat(result.ok()).as("ERROR 被拒的原因：" + result.reason()).isTrue();
        List<CaseList.Case> after = cases.cases();
        assertThat(after.get(0).status()).isEqualTo(CaseList.Status.ERROR);
        assertThat(after.get(0).note()).contains("端口占用");
    }
}
