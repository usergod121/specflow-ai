package com.specflow.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「每条验收标准到底有没有用例覆盖」——机器核对的<b>另一半</b>。
 *
 * <p>为什么要有它：{@code PlanAudit} 查的是「方案要动的文件改不改得动」，{@code StepAudit}
 * 查的是「施工单跑不跑得起来」，而「验没验」这一半一直是空的。真模型实测里它当场漏了一条：
 * 10 条验收标准里 A4 一条用例都没覆盖，A9 只是被并进另一条用例而追溯字段没填——
 * 引擎没报、界面没显示，全靠人自己去对两张清单。
 *
 * <p>它只做两件事，两件都是<b>算出来的</b>（不调模型、不看代码）：
 * <ol>
 *   <li><b>零覆盖的验收标准</b>：一条用例都没有提到它；</li>
 *   <li><b>无对应验收的必须过用例</b>：分级是「必须过」、却没写上对应哪条验收标准的用例。</li>
 * </ol>
 * 两个数都该是 0。它们<b>不拦人</b>——用例是给人看的证据（十五.3），
 * 而「这一条我打算靠人工验」是正当的选择；但人得看得见，否则「验收标准写完了就没人管」
 * 会一直静悄悄地发生。
 *
 * <p><b>怎么算「提到」。</b>用例上那一栏（{@link PlanReview.TestCase#acceptance()}）按协议是
 * 「照抄那一条」，所以判据是<b>归一化空白之后的包含关系</b>（双向都认）：
 * 模型照抄全文、或者只写了编号（{@code A1}、{@code R-1}）都算接上了。
 * 认「编号」这一种是必须的——验收标准常常写成「A1：……」，而模型只会在那一栏写 {@code A1}。
 * 反过来，空着、写「无」、写「/」都<b>不算</b>接上：没有指向就是没有指向，不替它猜。
 *
 * <p><b>为什么宁可宽松。</b>这一栏是给人看的提示，不是闸门：误报（把接上的说成没接上）
 * 会让人去改一份本来就对的东西，而漏报只是少了一条提示。所以「包含」就算接上，
 * 只有明确没写才算没接。
 */
public final class AcceptanceCoverage {

    /** 那一栏写这些就等于没写。判据和界面上的 {@code caseAcceptance} 同一套。 */
    private static final Set<String> NONE = Set.of("无", "没有", "n/a", "na", "none", "-", "/", "—");

    /** 太短的「指向」不算指向：一个字母、一个标点对上什么了都说明不了。 */
    private static final int MIN_MATCH_CHARS = 2;

    private AcceptanceCoverage() {
    }

    /**
     * 一份核对结果。
     *
     * @param criteria     这次的验收标准（原样，按界面上的顺序）
     * @param uncovered    一条用例都没提的验收标准（原样文本）
     * @param unmappedMust 没写「对应哪条验收标准」的必须过用例编号，升序
     */
    public record Report(List<String> criteria, List<String> uncovered, List<Integer> unmappedMust) {

        public Report {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
            uncovered = uncovered == null ? List.of() : List.copyOf(uncovered);
            unmappedMust = unmappedMust == null ? List.of() : List.copyOf(unmappedMust);
        }

        /** 零覆盖的验收标准数。界面上那枚计数就是它，应该是 0。 */
        public int uncoveredCount() {
            return uncovered.size();
        }

        /** 无对应验收标准的必须过用例数。也应该是 0。 */
        public int unmappedMustCount() {
            return unmappedMust.size();
        }

        /** 两个数都是 0。 */
        public boolean ok() {
            return uncovered.isEmpty() && unmappedMust.isEmpty();
        }

        /** 一行日志／留档用的结论。没有问题时也说得出现状（「0 条零覆盖」本身是结论）。 */
        public String summarize() {
            if (ok()) {
                return "覆盖核对通过：" + criteria.size() + " 条验收标准都有用例提到，必须过的用例都写了对应验收";
            }
            StringBuilder out = new StringBuilder("覆盖核对：");
            if (!uncovered.isEmpty()) {
                out.append(uncovered.size()).append(" 条验收标准一条用例都没覆盖")
                        .append("（").append(firstFew(uncovered)).append("）");
            }
            if (!unmappedMust.isEmpty()) {
                if (!uncovered.isEmpty()) {
                    out.append("；");
                }
                out.append(unmappedMust.size()).append(" 条必须过的用例没写对应哪条验收标准（用例 ")
                        .append(join(unmappedMust)).append("）");
            }
            return out.toString();
        }

        /** 只列前几条原文：验收标准本身可能很长，全铺上去界面会被撑爆。 */
        private static String firstFew(List<String> items) {
            List<String> shown = items.size() > 3 ? items.subList(0, 3) : items;
            return String.join("、", shown) + (items.size() > 3 ? "…" : "");
        }
    }

    /**
     * 核一遍。
     *
     * @param acceptance 需求里的验收标准；空表示没写验收标准（那就不核了，没什么可覆盖的）
     * @param cases      检查阶段定下来的用例清单
     */
    public static Report check(List<String> acceptance, List<PlanReview.TestCase> cases) {
        List<String> criteria = acceptance == null ? List.of() : acceptance.stream()
                .filter(item -> item != null && !item.isBlank())
                .toList();
        List<PlanReview.TestCase> list = cases == null ? List.of() : cases;
        if (criteria.isEmpty()) {
            return new Report(List.of(), List.of(), List.of());
        }

        List<String> uncovered = new ArrayList<>();
        for (String criterion : criteria) {
            boolean covered = list.stream().anyMatch(testCase -> covers(testCase.acceptance(), criterion));
            if (!covered) {
                uncovered.add(criterion);
            }
        }

        List<Integer> unmapped = new ArrayList<>();
        for (PlanReview.TestCase testCase : list) {
            // 只点「必须过」那一档：建议过／可选本来就可以是补充性的（十五.3 定的分级口径），
            // 拿它们也去要求「必须挂上一条验收标准」，等于把优先级当成义务
            if (testCase.level() != PlanReview.TestCase.Level.MUST) {
                continue;
            }
            boolean mapped = criteria.stream()
                    .anyMatch(criterion -> covers(testCase.acceptance(), criterion));
            if (!mapped) {
                unmapped.add(testCase.index());
            }
        }
        unmapped.sort(Integer::compareTo);
        return new Report(criteria, uncovered, unmapped);
    }

    /**
     * 这条用例那一栏算不算指向了这条验收标准。
     *
     * <p>双向包含：模型可能照抄原文（那一栏更长，或者一样长），也可能只写编号
     * （那一栏更短）。两个方向都要认，否则「写了 A1」会被判成没覆盖——
     * 而那是验收标准编号写法带来的假红。
     */
    private static boolean covers(String declared, String criterion) {
        String field = normalize(declared);
        String target = normalize(criterion);
        if (field.isEmpty() || target.isEmpty() || NONE.contains(field.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (field.equals(target)) {
            return true;
        }
        String shorter = field.length() <= target.length() ? field : target;
        String longer = shorter.equals(field) ? target : field;
        return shorter.length() >= MIN_MATCH_CHARS && longer.contains(shorter);
    }

    /** 归一化空白：换行、制表、连续空格、全角空格都算一个——排版差异不该冒充「没覆盖」。 */
    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\u00a0\\u3000]+", " ").strip();
    }

    private static String join(List<Integer> indexes) {
        StringBuilder out = new StringBuilder();
        for (Integer index : indexes) {
            out.append(out.length() == 0 ? "" : "、").append(index);
        }
        return out.toString();
    }
}
