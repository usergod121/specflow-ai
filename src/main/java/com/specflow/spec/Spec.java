package com.specflow.spec;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次改动的完整契约。对应项目根目录的 {@code spec.yaml}。
 *
 * <p>它描述的是<b>需求那一侧</b>：要做什么、怎样算做完、能碰哪些文件、依据什么。
 * 项目那一侧的常量（产物形态、技术栈、角色、代码风格）在
 * {@link com.specflow.template.PromptTemplate} 里，两者通过 {@code template} 字段挂上。
 *
 * <p>三类字段的消费者不同，这一点决定了校验强度也不同：
 * <ul>
 *   <li>{@code strategy} / {@code targets} / {@code acceptance} —— 引擎消费，强校验</li>
 *   <li>{@code variables} —— 模板渲染器消费，K-V 结构校验</li>
 *   <li>{@code prompt} / {@code constraints} / {@code context} —— 模型消费，自由文本</li>
 * </ul>
 *
 * <p>这里没有「新建还是修改」的字段：那由目标文件在不在决定。
 * 一个全局的模式开关会让人面对「这个还不存在的文件该选哪个模式」这种无从回答的问题，
 * 也会让「本次同时新增一个文件、修改另一个文件」变得无法表达。
 *
 * <p>本类型是纯数据载体，不含校验逻辑；语义检查集中在 {@link SpecValidator}。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Spec(
        int version,
        PatchStrategyType strategy,
        String template,
        Map<String, String> variables,
        String prompt,
        List<String> acceptance,
        List<String> targets,
        List<String> constraints,
        List<ContextItem> context,
        VerifySpec verify,
        TraceSpec trace
) {

    public static final int CURRENT_VERSION = 1;

    @JsonCreator
    public static Spec of(
            @JsonProperty("version") Integer version,
            @JsonProperty("strategy") String strategy,
            @JsonProperty("template") String template,
            @JsonProperty("variables") Map<String, String> variables,
            @JsonProperty("prompt") String prompt,
            @JsonProperty("acceptance") List<String> acceptance,
            @JsonProperty("targets") @JsonAlias("target_paths") List<String> targets,
            @JsonProperty("constraints") List<String> constraints,
            @JsonProperty("context") List<ContextItem> context,
            @JsonProperty("verify") VerifySpec verify,
            @JsonProperty("trace") TraceSpec trace
    ) {
        return new Spec(
                version == null ? CURRENT_VERSION : version,
                PatchStrategyType.from(strategy),
                blankToNull(template),
                copyTextMap(variables, "variables"),
                prompt == null ? "" : prompt.strip(),
                copyTextList(acceptance, "acceptance"),
                targets == null ? List.of() : List.copyOf(targets),
                constraints == null ? List.of() : List.copyOf(constraints),
                context == null ? List.of() : List.copyOf(context),
                verify == null ? VerifySpec.DEFAULT : verify,
                trace == null ? TraceSpec.EMPTY : trace
        );
    }

    /**
     * 复制字符串列表，并拒绝空元素。
     *
     * <p>和 {@link #copyTextMap} 同一个理由：YAML 里写 {@code acceptance:}
     * 底下跟一个孤零零的 {@code -}，会得到一个 null，
     * 而 {@code List.copyOf} 抛出的 NPE 看不出是哪一行的问题。
     */
    private static List<String> copyTextList(List<String> source, String field) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(source.size());
        for (String item : source) {
            if (item == null) {
                throw new IllegalArgumentException(field + " 中存在空条目；请补上内容或删掉那一行");
            }
            if (!item.isBlank()) {
                copy.add(item.strip());
            }
        }
        return List.copyOf(copy);
    }

    /**
     * 复制键值对，并拒绝空值。
     *
     * <p>不能直接用 {@code Map.copyOf}：YAML 里写 {@code requirement:} 而不给值时，
     * 值是 null，{@code Map.copyOf} 会抛一个看不出所以然的 NPE。
     * 这里换成一句能指出是哪个键的报错——「变量没填值」是写 spec 时最常见的笔误。
     */
    private static Map<String, String> copyTextMap(Map<String, String> source, String field) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException(
                        field + " 中 '" + entry.getKey() + "' 没有取值；请补上值或删掉这一行");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(copy);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
