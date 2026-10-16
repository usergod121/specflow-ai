package com.specflow.agent;

import com.specflow.core.Json;
import com.specflow.core.Llm;
import com.specflow.core.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>③ 的用例 Agent</b>：读需求 / 设计 / ② 的上下文，产出**一版带标签的用例**。
 *
 * <h2>它不是个循环，就一次调用</h2>
 * 写用例不需要读项目（② 已经把该读的读完了），也不需要改任何东西——
 * 一次模型调用、一份 JSON 回来就完事。上一版把它塞进链里绕了一圈，收益是零。
 *
 * <h2>期望是模糊的（这是刻意的）</h2>
 * 每条用例给的是**一句话意图**（"库存不足时不能扣"，而不是"assert reserve(5)==false"）：
 * 具体怎么验由 ⑤ 在容器里定。写死断言的后果是开发照着断言硬拟合——
 * 「改到那条断言恰好过」和真的修好了，在报告上长得一模一样。
 */
public final class CasesAgent {

    private static final Logger log = LoggerFactory.getLogger(CasesAgent.class);

    /** 标签是固定集合（界面上的筛选用它，模型不许自己发明）。 */
    public static final List<String> TAGS = List.of("功能", "边界", "异常", "性能", "安全");

    private final Llm llm;
    private final Progress progress;

    public CasesAgent(Llm llm, Progress progress) {
        this.llm = llm;
        this.progress = progress == null ? Progress.quiet() : progress;
    }

    /** 一条用例（与 {@code test.CaseList.Case} 同形状，落盘由调用方做）。 */
    public record One(String id, String what, List<String> tags, String expect) {
    }

    /**
     * 生成一版。
     *
     * @param requirement 需求原话
     * @param design      设计说明（可空）
     * @param files       ② 挑出的可读文件（当上下文用）
     * @param context     ② 给的几段"为什么相关"
     * @throws IllegalStateException 模型没回来、或者回来的东西一条都用不了（**不当成"生成了 0 条"**）
     */
    public List<One> generate(String requirement, String design, List<String> files,
                              List<String> context) {
        String system = """
                你是这个项目里的**用例 Agent**。照需求写一份验收清单：**每条一句话意图 + 标签**。

                规矩：

                1. 每条给：`what`（要验什么，一句话）、`tags`（从 %s 里选，至少一个）、
                   `expect`（**模糊期望**：期望的行为，一句话）。
                2. **不许写具体断言**（不许出现 assert / 具体的返回值比较 / 具体的命令行）——
                   具体怎么验由测试那一格在容器里定。写死断言会让开发照着断言硬拟合，
                   而「改到那条断言恰好过」看起来和真的修好了完全一样。
                3. 覆盖这几类：正常路径、边界（刚好够/差一个/为零）、异常（不存在、非法输入）、
                   以及需求里**明确不许**发生的事。
                4. 条数 6~12 条：太少漏事，太多没人看。
                5. 只写需求里说得到的行为，别发明需求。

                只输出一个 json 代码块：

                ```json
                {"cases": [{"id": "C1", "what": "库存充足时正常预占", "tags": ["功能"],
                            "expect": "返回成功且库存相应减少"}]}
                ```
                """.formatted(String.join(" / ", TAGS));
        StringBuilder user = new StringBuilder();
        user.append("## 需求（用户原话，一个字都不许改写）\n\n").append(requirement.strip()).append('\n');
        if (design != null && !design.isBlank()) {
            user.append("\n## 设计\n\n").append(design.strip()).append('\n');
        }
        if (files != null && !files.isEmpty()) {
            user.append("\n## 这个项目里相关的文件\n\n");
            for (String one : files) {
                user.append("- ").append(one).append('\n');
            }
        }
        if (context != null && !context.isEmpty()) {
            user.append("\n## 上下文（② 给的，一条一句为什么相关）\n\n");
            for (String one : context) {
                user.append("- ").append(one).append('\n');
            }
        }
        user.append("\n现在写清单。只输出那个 json 代码块。\n");

        progress.step("让模型写一版用例", "", 0);
        String reply;
        try {
            reply = llm.complete(Llm.Msg.system(system), Llm.Msg.user(user.toString()));
        } catch (RuntimeException e) {
            progress.fail("模型没回来：这一版用例没写出来", e.getMessage());
            throw new IllegalStateException("模型没回来，用例没生成：" + e.getMessage(), e);
        }
        List<One> found = parse(reply);
        if (found.isEmpty()) {
            progress.fail("模型回了，但里面一条用例都读不出来", shortText(reply));
            throw new IllegalStateException("模型回的那一段里一条用例都读不出来（引擎不会替你编清单）："
                    + shortText(reply));
        }
        progress.stepDone("写出来 " + found.size() + " 条");
        log.info("用例 Agent 生成了 {} 条", found.size());
        return found;
    }

    @SuppressWarnings("unchecked")
    static List<One> parse(String reply) {
        String json = Probe.firstJsonBlock(reply);
        if (json == null) {
            return List.of();
        }
        List<One> found = new ArrayList<>();
        try {
            Map<String, Object> map = Json.map(json);
            Object raw = map.get("cases");
            if (!(raw instanceof List<?> items)) {
                return List.of();
            }
            int at = 0;
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> one)) {
                    continue;
                }
                at++;
                String what = str(one.get("what"));
                if (what.isBlank()) {
                    continue;
                }
                String id = str(one.get("id"));
                found.add(new One(id.isBlank() ? "C" + at : id, what,
                        tags(one.get("tags")), str(one.get("expect"))));
            }
        } catch (RuntimeException e) {
            log.warn("用例那一版读不出来：{}", e.getMessage());
            return List.of();
        }
        return found;
    }

    /** 标签只认固定集合里的（模型写了别的就当它没写，界面按"缺标签"提示人补）。 */
    private static List<String> tags(Object raw) {
        List<String> found = new ArrayList<>();
        if (raw instanceof List<?> items) {
            for (Object one : items) {
                String tag = str(one);
                if (TAGS.contains(tag) && !found.contains(tag)) {
                    found.add(tag);
                }
            }
        } else {
            String tag = str(raw);
            if (TAGS.contains(tag)) {
                found.add(tag);
            }
        }
        return List.copyOf(found);
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).strip();
    }

    private static String shortText(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    static Map<String, Object> nothing() {
        return new LinkedHashMap<>();
    }

    static String upper(String text) {
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
    }
}
