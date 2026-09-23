package com.specflow.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 检查阶段的产物：一份给人看的实现方案。
 *
 * <p>它的用途有两个，缺一不可：
 * <ol>
 *   <li><b>给人确认</b>——在模型动手写代码之前，先让它说清楚「打算怎么改」。
 *       理解偏了在这里就能看出来，比写完再回滚便宜太多</li>
 *   <li><b>给模型当施工图</b>——确认过的方案会被回喂给开发阶段。
 *       否则它开会重新想一遍，想出来的可能不是你看过的那份，那你确认了个寂寞</li>
 * </ol>
 *
 * @param summary   一两句话的实现摘要
 * @param flowchart Mermaid 流程图原文，由界面渲染成图
 * @param missing   缺失依赖清单，按严重度排好序；为空表示模型认为信息已经足够
 */
public record PlanReview(
        String summary,
        String flowchart,
        List<MissingItem> missing
) {

    /**
     * 一条缺失的依赖。
     *
     * <p>五个字段是跟模型约定死的顺序：{@code 缺什么 | 严重度 | 影响（技术） | 影响（业务） | 建议默认值}。
     * 为什么要分「技术影响」和「业务影响」两栏：同一条缺失，
     * 老手看技术影响就够了，新手需要「不做这一条，最终用户会看到什么」才判断得出该不该补。
     *
     * @param what     缺什么，例如「orders 表的字段列表」
     * @param severity 缺了它会发生什么（见 {@link Severity}）
     * @param impact   技术视角的影响，例如「写不出 SQL，编译过不了」
     * @param business 业务视角的影响，给不熟悉这块的人看，例如「订单列表可能少一列金额」
     * @param fallback 建议的默认值，例如「按 id/status/created_at 三列先写」
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MissingItem(
            String what,
            Severity severity,
            String impact,
            String business,
            String fallback
    ) {

        /**
         * 严重度。只有 {@link #BLOCKING} 会拦住人，其余一律可以按默认值往下走。
         *
         * <p>判据是「缺了它会发生什么」，不是「重不重要」——形容词没法执行，后果可以。
         */
        public enum Severity {

            /** 没有它代码写不出来或一定写错（缺表结构、缺字段清单、缺接口约定）。 */
            BLOCKING("阻断", 0),
            /** 能写出能编译的代码，但很可能不是你要的（命名习惯、边界值、错误码）。 */
            QUALITY("影响质量", 1),
            /** 锦上添花，没有也不影响这次能不能做出来。 */
            OPTIONAL("可选", 2),
            /** 模型没写、或者写了个认不出来的词。按「影响质量」对待，但如实标成「未标」。 */
            UNKNOWN("未标", 3);

            private final String label;
            private final int rank;

            Severity(String label, int rank) {
                this.label = label;
                this.rank = rank;
            }

            public String label() {
                return label;
            }

            /** 排序用：越靠前越严重。 */
            public int rank() {
                return rank;
            }

            /**
             * 认模型写的严重度。认不出来一律 {@link #UNKNOWN}——
             * <b>不能默认成阻断</b>，否则模型漏写一个词就会把人拦住。
             */
            public static Severity parse(String text) {
                if (text == null || text.isBlank()) {
                    return UNKNOWN;
                }
                String value = text.strip().toLowerCase(Locale.ROOT);
                if (value.contains("阻断") || value.contains("block") || value.equals("高")) {
                    return BLOCKING;
                }
                if (value.contains("影响质量") || value.contains("quality") || value.equals("中")) {
                    return QUALITY;
                }
                if (value.contains("可选") || value.contains("option") || value.equals("低")) {
                    return OPTIONAL;
                }
                return UNKNOWN;
            }
        }

        /**
         * 从 JSON 里读回一条。
         *
         * <p>多出来的两个参数是<b>老记录的形状</b>：这一栏以前叫 {@code why} / {@code howToSupply}。
         * 不认这两个名字，历史面板里那几次运行会整个读不出来——而读不出来是<b>静默跳过</b>的
         * （{@code RunStore.read} 捕掉异常返回空），用户只会看到历史莫名少了几条。
         */
        @JsonCreator
        public static MissingItem of(
                @JsonProperty("what") String what,
                @JsonProperty("severity") String severity,
                @JsonProperty("impact") String impact,
                @JsonProperty("business") String business,
                @JsonProperty("fallback") String fallback,
                @JsonProperty("why") String why,
                @JsonProperty("howToSupply") String howToSupply) {
            return new MissingItem(text(what), Severity.parse(severity),
                    first(impact, why), text(business), first(fallback, howToSupply));
        }

        /** 界面上要显示成「未标」还是「可选」都靠它；不写这一层，各处会各判一套。 */
        public boolean blocking() {
            return severity == Severity.BLOCKING;
        }

        /** 界面上那行「建议默认值」：没写就说清楚没写，别留个空行让人以为是漏显示了。 */
        public String fallbackOrDefault() {
            return fallback.isEmpty() ? "按常规做法处理（它没给具体默认值）" : fallback;
        }

        private static String first(String preferred, String legacy) {
            return preferred != null && !preferred.isBlank() ? preferred.strip() : text(legacy);
        }

        private static String text(String value) {
            return value == null ? "" : value.strip();
        }
    }

    public static PlanReview of(String summary, String flowchart, List<MissingItem> missing) {
        List<MissingItem> items = missing == null ? List.of() : List.copyOf(missing);
        return new PlanReview(summary == null ? "" : summary.strip(),
                flowchart == null ? "" : flowchart.strip(), items);
    }

    /** 按严重度排一遍（越严重越靠前），同档保持模型给的顺序。 */
    public static List<MissingItem> bySeverity(List<MissingItem> items) {
        return items.stream()
                .sorted(Comparator.comparingInt(item -> item.severity().rank()))
                .toList();
    }

    /** 模型认为信息已经足够，可以直接开发。 */
    public boolean complete() {
        return missing.isEmpty();
    }

    /** 有没有「不补上就只能靠它猜」的项。界面据此决定要不要拦人。 */
    public boolean blocking() {
        return missing.stream().anyMatch(MissingItem::blocking);
    }

    /**
     * 渲染成回喂给开发阶段的一段文字。
     *
     * <p>缺失清单<b>要带上</b>，但换了个说法：既然已经确认往下走，这些缺的东西就不是
     * 「你要不要补」的问题，而是「按什么默认值先写」的问题。带上它，模型才不会在开发阶段
     * 把同一件事重新想一遍；不带上，用户确认的其实是它没打算遵守的那份方案。
     */
    public String render() {
        StringBuilder out = new StringBuilder();
        if (!summary.isEmpty()) {
            out.append(summary).append("\n\n");
        }
        if (!flowchart.isEmpty()) {
            out.append("流程图：\n```mermaid\n").append(flowchart).append("\n```\n");
        }
        if (!missing.isEmpty()) {
            out.append("\n检查阶段报出的缺失信息，按下面这些默认值直接写，不要停下来问：\n");
            for (MissingItem item : missing) {
                out.append("- ").append(item.what()).append("：").append(item.fallbackOrDefault());
                if (item.blocking()) {
                    out.append("（这一项被标成「阻断」，用户选择先继续）");
                }
                out.append("\n");
            }
            out.append("这些默认值是你自己定的，不是用户逐条确认过的：按它们写，"
                    + "并在代码注释里写明你假设了什么。\n");
        }
        return out.toString().strip();
    }
}
