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
     * @param disabledOnly <b>本来有用例在管、而那条用例被人停用</b>的验收标准（原样文本）。
     *                     它和 {@code uncovered} 必须分开说：前者是「没人管」，
     *                     后者是「有人管，但管事的那条被你停用了」——两句不同的话，
     *                     而它们对应的下一步也不同（一条要去找人写用例，一条只要恢复就行）。
     *                     停用的用例<b>不参与分母</b>，所以它确实不再覆盖这条验收标准；
     *                     但把这件事说成「零覆盖」，用户会以为用例压根没写过。
     *                     老记录里没有这一项，读出来是空表
     */
    public record Report(List<String> criteria, List<String> uncovered, List<Integer> unmappedMust,
                         List<String> disabledOnly) {

        /**
         * 老记录里没有 {@code disabledOnly} 这一栏（那时还没有停用这个动作）：
         * Jackson 按规范构造器去读，缺的那一栏就是 {@code null}，下面这一行把它兜成空表——
         * 所以这里<b>不需要</b>再开一个三栏的兼容构造器（开了也一个调用点都没有，
         * 而它会让人以为老记录是走它读进来的）。
         */
        public Report {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
            uncovered = uncovered == null ? List.of() : List.copyOf(uncovered);
            unmappedMust = unmappedMust == null ? List.of() : List.copyOf(unmappedMust);
            disabledOnly = disabledOnly == null ? List.of() : List.copyOf(disabledOnly);
        }

        /** 零覆盖的验收标准数。界面上那枚计数就是它，应该是 0。 */
        public int uncoveredCount() {
            return uncovered.size();
        }

        /** 无对应验收标准的必须过用例数。也应该是 0。 */
        public int unmappedMustCount() {
            return unmappedMust.size();
        }

        /** 用例被停用之后没人管的验收标准数。也应该是 0（除非你确实不打算验它了）。 */
        public int disabledOnlyCount() {
            return disabledOnly.size();
        }

        /**
         * 三个数都是 0。
         *
         * <p>{@link #disabledOnly} 也算在内：那一条验收标准此刻<b>确实没人验</b>——
         * 停用是人的选择，但「选择的结果」和「没人管」在这个数上是同一件事。
         * 分开的是<b>说法</b>（见 {@link #summarize()}），不是健康度。
         */
        public boolean ok() {
            return uncovered.isEmpty() && unmappedMust.isEmpty() && disabledOnly.isEmpty();
        }

        /** 一行日志／留档用的结论。没有问题时也说得出现状（「0 条零覆盖」本身是结论）。 */
        public String summarize() {
            if (ok()) {
                return "覆盖核对通过：" + criteria.size() + " 条验收标准都有用例提到，必须过的用例都写了对应验收";
            }
            // 三段各说各的，用同一个分隔符拼：三件事的下一步完全不同，合并不了（见 Report 的说明）
            List<String> parts = new ArrayList<>();
            if (!uncovered.isEmpty()) {
                parts.add(uncovered.size() + " 条验收标准一条用例都没覆盖（" + firstFew(uncovered) + "）");
            }
            if (!disabledOnly.isEmpty()) {
                parts.add(disabledOnly.size() + " 条验收标准的用例被你停用了、此刻没人验它（"
                        + firstFew(disabledOnly) + "）");
            }
            if (!unmappedMust.isEmpty()) {
                // 说清「机器把它降成了建议过」：那一栏是在**降级之前**的清单上算的
                // （见 RunService.review），而人看到的清单上那条已经是「建议过」了——
                // 不说清，两句话看起来就是自相矛盾
                parts.add(unmappedMust.size() + " 条用例标了「必须过」却没挂验收标准"
                        + "（机器把它们降成了「建议过」，用例 " + join(unmappedMust) + "）");
            }
            return "覆盖核对：" + String.join("；", parts);
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
        return check(acceptance, cases, Set.of());
    }

    /**
     * 核一遍，并且把<b>被人停用的用例排除在分母之外</b>。
     *
     * <p>停用是用户明确要过的动作（「删掉它」），语义是「这条别再算了」。所以：
     * <ul>
     *   <li>它<b>不再覆盖</b>验收标准——覆盖是算出来的，算它就得算它的用例；</li>
     *   <li>但它<b>不落进「零覆盖」那一栏</b>：那条验收标准本来有人管，是人自己把它停了，
     *       说成「一条用例都没覆盖」会让人以为清单里压根没写过它（见 {@link Report#disabledOnly()}）；</li>
     *   <li>{@code unmappedMust} 也不再看停用的用例：它已经不算数了，再去问它「怎么没挂验收」是白问。</li>
     * </ul>
     *
     * @param disabled 此刻还停用着的用例编号；{@code null} 或空 = 一条都没停用
     */
    public static Report check(List<String> acceptance, List<PlanReview.TestCase> cases,
                               Set<Integer> disabled) {
        List<String> criteria = acceptance == null ? List.of() : acceptance.stream()
                .filter(item -> item != null && !item.isBlank())
                .toList();
        List<PlanReview.TestCase> list = cases == null ? List.of() : cases;
        Set<Integer> off = disabled == null ? Set.of() : disabled;
        if (criteria.isEmpty()) {
            return new Report(List.of(), List.of(), List.of(), List.of());
        }

        List<String> uncovered = new ArrayList<>();
        List<String> disabledOnly = new ArrayList<>();
        for (String criterion : criteria) {
            boolean live = list.stream().anyMatch(testCase -> !off.contains(testCase.index())
                    && covers(testCase.acceptance(), criterion));
            if (live) {
                continue;
            }
            // 没有活着的用例管它：那就看是不是「管它的那条被停用了」
            boolean byDisabled = list.stream().anyMatch(testCase -> off.contains(testCase.index())
                    && covers(testCase.acceptance(), criterion));
            (byDisabled ? disabledOnly : uncovered).add(criterion);
        }

        List<Integer> unmapped = new ArrayList<>();
        for (PlanReview.TestCase testCase : list) {
            // 只点「必须过」那一档：建议过／可选本来就可以是补充性的（十五.3 定的分级口径），
            // 拿它们也去要求「必须挂上一条验收标准」，等于把优先级当成义务
            if (testCase.level() != PlanReview.TestCase.Level.MUST || off.contains(testCase.index())) {
                continue;
            }
            boolean mapped = criteria.stream()
                    .anyMatch(criterion -> covers(testCase.acceptance(), criterion));
            if (!mapped) {
                unmapped.add(testCase.index());
            }
        }
        unmapped.sort(Integer::compareTo);
        return new Report(criteria, uncovered, unmapped, disabledOnly);
    }

    /**
     * <b>硬规则</b>：没有对应验收标准的用例，不许是「必须过」级。
     *
     * <p>为什么这是一条规则而不是一句提示：「必须过」的含义是「不过就等于这次需求没做到」，
     * 而这个含义<b>只有验收标准给得起</b>——它是从需求那一边来的。一条谁也不对应的用例标上
     * 「必须过」，是把模型自己的判断冒充成需求的分量：真跑到红灯时，人分不清
     * 「功能没做出来」和「它自己觉得这条重要」。所以机器在这里把它降成「建议过」，
     * 并且这一次降级会被 {@link #check} 数进 {@code unmappedMust}——降级不等于抹掉，
     * 用户照样看得见「这几条标了必须过却没挂验收」。
     *
     * <p><b>没有写验收标准时不动它</b>：那时候「对应哪条验收标准」这一栏没有可填的对象，
     * 把它一律降级等于惩罚「需求里没写验收标准」这件事——那是另一个问题（老用法就这么用），
     * 不该在这里静悄悄地改变一条用例的分量。这一条取舍写在这里，免得下一个人以为是漏判。
     *
     * @param acceptance 需求里的验收标准
     * @param cases      检查阶段定下来的用例清单（原样，不改）
     * @return 降级之后的清单，以及被降级的编号（给界面说清是哪几条、为什么）
     */
    public static Levelled level(List<String> acceptance, List<PlanReview.TestCase> cases) {
        List<String> criteria = acceptance == null ? List.of() : acceptance.stream()
                .filter(item -> item != null && !item.isBlank())
                .toList();
        List<PlanReview.TestCase> list = cases == null ? List.of() : cases;
        if (criteria.isEmpty()) {
            return new Levelled(list, List.of());
        }
        List<PlanReview.TestCase> fixed = new ArrayList<>();
        List<Integer> downgraded = new ArrayList<>();
        for (PlanReview.TestCase testCase : list) {
            boolean mapped = criteria.stream()
                    .anyMatch(criterion -> covers(testCase.acceptance(), criterion));
            if (testCase.level() == PlanReview.TestCase.Level.MUST && !mapped) {
                downgraded.add(testCase.index());
                fixed.add(new PlanReview.TestCase(testCase.index(), testCase.what(), testCase.how(),
                        PlanReview.TestCase.Level.SHOULD, testCase.expected(), testCase.acceptance()));
                continue;
            }
            fixed.add(testCase);
        }
        downgraded.sort(Integer::compareTo);
        return new Levelled(List.copyOf(fixed), List.copyOf(downgraded));
    }

    /**
     * 硬规则跑完之后的样子。
     *
     * @param cases      可以往下用的清单（被降级的那些已经是「建议过」）
     * @param downgraded 被机器降级的用例编号（标成「必须过」却没挂验收标准的那几条）
     */
    public record Levelled(List<PlanReview.TestCase> cases, List<Integer> downgraded) {

        public Levelled {
            cases = cases == null ? List.of() : List.copyOf(cases);
            downgraded = downgraded == null ? List.of() : List.copyOf(downgraded);
        }
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
