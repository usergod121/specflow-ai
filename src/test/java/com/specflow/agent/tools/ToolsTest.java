package com.specflow.agent.tools;

import com.specflow.agent.Shell;
import com.specflow.agent.Signal;
import com.specflow.agent.ToolRegistry;
import com.specflow.agent.ToolResult;
import com.specflow.core.Progress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 剩下几个工具的短线验收：{@code read_list} / {@code write_file} / {@code submit} / {@code bash}。 */
class ToolsTest {

    @TempDir
    Path tmp;

    /** 记账用的假进度口（bash 的逐行输出要真的推上去）。 */
    private static final class Lines implements Progress {

        private final List<String> lines = new ArrayList<>();

        @Override
        public void step(String name, String command, long timeoutSeconds) {
        }

        @Override
        public void stepDone(String name) {
        }

        @Override
        public void line(String text) {
            lines.add(text);
        }

        @Override
        public void warn(String text) {
        }

        @Override
        public void fail(String text, String raw) {
        }

        @Override
        public void done(String text, Map<String, Object> extra) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public long elapsedSeconds() {
            return 0;
        }

        @Override
        public List<Map<String, Object>> steps() {
            return List.of();
        }
    }

    private static Shell shellOf(int exit, String output, boolean timedOut) {
        return (command, workdir, timeoutSeconds, onLine) -> {
            for (String line : output.split("\n", -1)) {
                if (!line.isEmpty() && onLine != null) {
                    onLine.accept(line);
                }
            }
            return new Shell.Result(exit, output, timedOut);
        };
    }

    // ---------- read_list ----------

    @Test
    void readListListsSourcesAndSkipsNoise() throws IOException {
        Files.createDirectories(tmp.resolve("src/main"));
        Files.createDirectories(tmp.resolve("target"));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        ToolRegistry tools = ToolRegistry.of(new ReadListTool(new Roots(tmp)));

        ToolResult result = tools.call("read_list", "{}");

        assertThat(result.ok()).isTrue();
        assertThat(result.json()).contains("src").contains("pom.xml")
                .doesNotContain("target").contains("\"skipped\":1");
    }

    @Test
    void readListOnMissingPathFails() {
        ToolRegistry tools = ToolRegistry.of(new ReadListTool(new Roots(tmp)));

        assertThat(tools.call("read_list", "{\"path\":\"nope\"}").reason()).contains("不存在");
    }

    // ---------- write_file ----------

    @Test
    void writeFileCreatesThenOverwrites() throws IOException {
        ToolRegistry tools = ToolRegistry.of(new WriteFileTool(new Roots(tmp)));

        assertThat(tools.call("write_file", "{\"path\":\"a/b.txt\",\"content\":\"一\\n二\\n\"}").ok()).isTrue();
        assertThat(Files.readString(tmp.resolve("a/b.txt"))).isEqualTo("一\n二\n");
        assertThat(tools.call("write_file", "{\"path\":\"a/b.txt\",\"content\":\"三\"}").ok()).isTrue();
        assertThat(Files.readString(tmp.resolve("a/b.txt"))).isEqualTo("三");
    }

    @Test
    void writeFileOutsideTheRootFails() {
        ToolRegistry tools = ToolRegistry.of(new WriteFileTool(new Roots(tmp)));

        assertThat(tools.call("write_file", "{\"path\":\"../x.txt\",\"content\":\"x\"}").ok()).isFalse();
        assertThat(Files.exists(tmp.getParent().resolve("x.txt"))).isFalse();
    }

    @Test
    void writeFileRefusesSecrets() {
        ToolRegistry tools = ToolRegistry.of(new WriteFileTool(new Roots(tmp)));

        assertThat(tools.call("write_file", "{\"path\":\".specflow/local.env\",\"content\":\"k=v\"}")
                .reason()).contains("密钥");
    }

    @Test
    void writeFileWithoutContentIsRejected() {
        ToolRegistry tools = ToolRegistry.of(new WriteFileTool(new Roots(tmp)));

        assertThat(tools.call("write_file", "{\"path\":\"a.txt\"}").reason()).contains("content");
    }

    // ---------- submit ----------

    @Test
    void submitReturnsASignalInsteadOfSettling() {
        ToolResult result = ToolRegistry.of(new SubmitTool()).call("submit", "{\"note\":\"改完了\"}");

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).isInstanceOf(Signal.class);
        assertThat(((Signal) result.data()).kind()).isEqualTo(Signal.SUBMIT);
        assertThat(((Signal) result.data()).note()).isEqualTo("改完了");
    }

    // ---------- bash ----------

    @Test
    void bashStreamsEveryLineToProgress() {
        Lines progress = new Lines();
        ToolRegistry tools = ToolRegistry.of(new BashTool(shellOf(0, "第一行\n第二行\n", false), "/work", 60, progress));

        ToolResult result = tools.call("bash", "{\"command\":\"ls\"}");

        assertThat(result.ok()).isTrue();
        assertThat(progress.lines).containsExactly("第一行", "第二行");
        assertThat(result.json()).contains("第一行").contains("第二行");
    }

    @Test
    void bashNonZeroExitIsAFailureThatCarriesTheOutput() {
        ToolRegistry tools = ToolRegistry.of(new BashTool(shellOf(2, "编译错了\n", false), "/work", 60, null));

        ToolResult result = tools.call("bash", "{\"command\":\"mvn compile\"}");

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("退出码 2").contains("编译错了");
    }

    @Test
    void bashTimeoutIsReportedAsATimeoutNotAsAFailure() {
        ToolRegistry tools = ToolRegistry.of(new BashTool(shellOf(-1, "卡住了\n", true), "/work", 60, null));

        ToolResult result = tools.call("bash", "{\"command\":\"sleep 999\"}");

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("超过 60 秒").contains("终止");
    }

    @Test
    void bashTimeoutCanOnlyBeLowered() {
        List<Long> asked = new ArrayList<>();
        Shell shell = (command, workdir, timeoutSeconds, onLine) -> {
            asked.add(timeoutSeconds);
            return new Shell.Result(0, "", false);
        };
        ToolRegistry tools = ToolRegistry.of(new BashTool(shell, "/work", 60, null));

        tools.call("bash", "{\"command\":\"ls\",\"timeout\":5}");
        tools.call("bash", "{\"command\":\"ls\",\"timeout\":9999}");

        assertThat(asked).containsExactly(5L, 60L);
    }

    @Test
    void bashOutputIsCapped() {
        String long1 = "x".repeat(20_000);
        ToolRegistry tools = ToolRegistry.of(new BashTool(shellOf(0, long1 + "\n尾巴\n", false), "/work", 60, null));

        ToolResult result = tools.call("bash", "{\"command\":\"cat big\"}");

        assertThat(result.ok()).isTrue();
        assertThat(result.json()).contains("还有 1 行没给");
    }
}
