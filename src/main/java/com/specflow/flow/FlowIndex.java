package com.specflow.flow;

import com.specflow.core.Atomic;
import com.specflow.core.Json;
import com.specflow.core.Layout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>跨流程看一眼</b>：把项目下每一条流程的状态与结论数汇总成一行行。
 *
 * <h2>为什么它不做成"数据库那种查询"</h2>
 * 我们的状态是文件（{@code flow/<id>/flow.json} + {@code cases.json}），而"跨流程统计"这件事
 * 只需要<b>扫目录 + 读两份小文件</b>。为此引一个索引或数据库，会把"人能打开看"的唯一优点换成
 * "必须有工具才能查"——那是这一版明确不换的东西（见 {@code docs/v3/DECISIONS.md}）。
 *
 * <p>所以它是一次<b>只读扫描</b>：按 id 倒序（id 是时间戳，字典序就是时间序）取前 N 条。
 * 单条读不出来就跳过它并记一笔，而不是让整页报错——一条坏文件不该毁掉历史列表。
 *
 * @param id      流程 id（时间是它就有的）
 * @param states  五格状态（{@code env=ENV} 这种）
 * @param cases   用例总数
 * @param pass    结论为 PASS 的条数
 * @param fail    FAIL
 * @param error   ERROR
 * @param skipped 被跳过（预算用尽）的条数
 * @param pending 还没到终态的条数
 */
public record FlowIndex(String id, Map<String, String> states, int cases, int pass, int fail,
                        int error, int skipped, int pending) {

    /** 一句话（列表里那一行）。 */
    public String summarize() {
        if (cases == 0) {
            return "还没有用例";
        }
        return "用例 " + cases + "：PASS " + pass + (fail > 0 ? "、FAIL " + fail : "")
                + (error > 0 ? "、ERROR " + error : "")
                + (skipped > 0 ? "、跳过 " + skipped : "")
                + (pending > 0 ? "、待定 " + pending : "");
    }

    /**
     * 扫一遍项目下的流程。
     *
     * @param limit 最多几条（按 id 倒序；{@code <=0} 当 20）
     */
    public static List<FlowIndex> scan(Layout layout, int limit) {
        Path dir = layout.flows();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (var children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .forEach(one -> ids.add(one.getFileName().toString()));
        } catch (java.io.IOException e) {
            return List.of();
        }
        ids.sort(Comparator.reverseOrder());
        int keep = limit <= 0 ? 20 : limit;
        List<FlowIndex> found = new ArrayList<>();
        for (String id : ids) {
            if (found.size() >= keep) {
                break;
            }
            FlowIndex one = read(layout, id);
            if (one != null) {
                found.add(one);
            }
        }
        return List.copyOf(found);
    }

    /** 读一条；读不出来（坏文件、权限）返回 {@code null}，由调用方跳过。 */
    private static FlowIndex read(Layout layout, String id) {
        try {
            Map<String, String> states = new LinkedHashMap<>();
            String raw = Atomic.read(layout.flowJson(id), "");
            if (!raw.isBlank()) {
                Map<String, Object> root = Json.map(raw);
                if (root.get("states") instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> states.put(String.valueOf(key), String.valueOf(value)));
                }
            }
            int cases = 0;
            int pass = 0;
            int fail = 0;
            int error = 0;
            int skipped = 0;
            int pending = 0;
            String casesRaw = Atomic.read(layout.cases(id), "");
            if (!casesRaw.isBlank()) {
                Object list = casesRaw.strip().startsWith("[")
                        ? Json.read(casesRaw, List.class)
                        : Json.map(casesRaw).get("cases");
                if (list instanceof List<?> rows) {
                    cases = rows.size();
                    for (Object row : rows) {
                        if (!(row instanceof Map<?, ?> one)) {
                            continue;
                        }
                        String status = String.valueOf(one.get("status")).toUpperCase(java.util.Locale.ROOT);
                        switch (status) {
                            case "PASS" -> pass++;
                            case "FAIL" -> fail++;
                            case "ERROR" -> error++;
                            case "SKIPPED" -> skipped++;
                            default -> pending++;
                        }
                    }
                }
            }
            return new FlowIndex(id, Map.copyOf(states), cases, pass, fail, error, skipped, pending);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
