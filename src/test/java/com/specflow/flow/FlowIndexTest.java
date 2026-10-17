package com.specflow.flow;

import com.specflow.core.Atomic;
import com.specflow.core.Layout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨流程看一眼：扫目录 + 读两份小文件，按 id 倒序汇总。
 *
 * <p>为什么不引数据库：这条需求只要"扫目录"，而引一个索引会把"人能打开看"换成
 * "必须有工具才能查"。所以这里钉三件事：数对了、坏的跳过、顺序是新的在前。
 */
class FlowIndexTest {

    /** 造一条流程（落点一律走 Layout，测试也不许自己拼路径）。 */
    private static void flow(Path root, String id, String states, String casesJson) throws IOException {
        Layout layout = new Layout(root);
        Files.createDirectories(layout.flow(id));
        Atomic.write(layout.flowJson(id), "{\"id\":\"" + id + "\",\"states\":" + states + "}\n");
        if (casesJson != null) {
            Atomic.write(layout.cases(id), casesJson + "\n");
        }
    }

    /** 造一份 cases.json（按状态计数造条目；逗号由 join 管，别手拼）。 */
    private static String cases(int pass, int fail, int error, int skipped, int pending) {
        java.util.List<String> rows = new java.util.ArrayList<>();
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        counts.put("PASS", pass);
        counts.put("FAIL", fail);
        counts.put("ERROR", error);
        counts.put("SKIPPED", skipped);
        counts.put("PENDING", pending);
        int at = 1;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                rows.add("{\"id\":\"C" + (at++) + "\",\"what\":\"验一条\",\"tags\":[\"功能\"],"
                        + "\"expect\":\"期望\",\"status\":\"" + entry.getKey() + "\"}");
            }
        }
        return "{\"cases\":[" + String.join(",", rows) + "]}";
    }

    @Test
    @DisplayName("数得对：PASS/FAIL/ERROR/跳过/待定 各算各的")
    void countsVerdicts(@TempDir Path root) throws IOException {
        flow(root, "20261017-100000", "{\"env\":\"DONE\",\"test\":\"READY\"}", cases(3, 1, 1, 2, 4));

        List<FlowIndex> rows = FlowIndex.scan(new Layout(root), 10);

        assertThat(rows).hasSize(1);
        FlowIndex one = rows.get(0);
        assertThat(one.cases()).isEqualTo(11);
        assertThat(one.pass()).isEqualTo(3);
        assertThat(one.fail()).isEqualTo(1);
        assertThat(one.error()).isEqualTo(1);
        assertThat(one.skipped()).isEqualTo(2);
        assertThat(one.pending()).isEqualTo(4);
        assertThat(one.states()).containsEntry("env", "DONE");
    }

    @Test
    @DisplayName("顺序：新的在前（id 是时间戳，字典序就是时间序），limit 生效")
    void newestFirstAndLimited(@TempDir Path root) throws IOException {
        flow(root, "20261017-090000", "{}", cases(1, 0, 0, 0, 0));
        flow(root, "20261017-120000", "{}", cases(2, 0, 0, 0, 0));
        flow(root, "20261017-100000", "{}", cases(3, 0, 0, 0, 0));

        List<FlowIndex> rows = FlowIndex.scan(new Layout(root), 2);

        assertThat(rows).extracting(FlowIndex::id)
                .containsExactly("20261017-120000", "20261017-100000");
    }

    @Test
    @DisplayName("坏文件跳过，不毁掉整页；没有用例也照样出一行")
    void brokenFlowIsSkipped(@TempDir Path root) throws IOException {
        flow(root, "20261017-100000", "{}", cases(1, 0, 0, 0, 0));
        Path bad = new Layout(root).flow("20261017-110000");
        Files.createDirectories(bad);
        Atomic.write(bad.resolve("flow.json"), "{这不是 json");

        List<FlowIndex> rows = FlowIndex.scan(new Layout(root), 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).summarize()).contains("PASS 1");
    }

    @Test
    @DisplayName("一条流程都没有：空表，不抛")
    void emptyProjectIsFine(@TempDir Path root) {
        assertThat(FlowIndex.scan(new Layout(root), 10)).isEmpty();
    }
}
