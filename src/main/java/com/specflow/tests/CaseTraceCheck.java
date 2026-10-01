package com.specflow.tests;

import com.specflow.review.PlanReview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用例清单 ⇄ 测试代码 的<b>溯源连线</b>核对。
 *
 * <p>它只做一件事：把两边的编号对起来，看它们是不是同一份东西。判据全是<b>纯文本扫描</b>——
 * 不编译、不执行、不看语言，所以 Java / Python / shell 一视同仁（引擎零知识，十五.1）。
 *
 * <p>锚点约定由 {@link TestProtocol} 写进提示词，两行注释，内容格式统一、注释符随语言：
 * <pre>
 *   // CASE 3
 *   // expect: 返回 0.00
 * </pre>
 * 第二行那个值必须是<b>冻结清单里那条用例的期望，逐字照抄</b>。四条机器判据：
 * <ol>
 *   <li>{@link Kind#MISSING 漏实现}：清单上有、代码里没扫到（锚点只写了一半也算：只有 CASE 没有 expect）；</li>
 *   <li>{@link Kind#EXTRA 清单外乱写}：代码里有、清单上没有；</li>
 *   <li>{@link Kind#DUPLICATE 重复实现}：同一个编号出现了多次；</li>
 *   <li>{@link Kind#CHANGED 偷偷改期望}：代码里的 expect 与清单的期望，归一化空白后逐字不等。</li>
 * </ol>
 * 任何一条不通过，这批测试就<b>不跑</b>：调用方拿 {@link Report#ok()} 拒绝执行，把差异摆给人看，
 * 走「测试代码错了 → 重新生成」那条路（十五.6）。
 *
 * <p><b>为什么要这么严。</b>测试代码是被验的那一方写的，它要是能随手改期望、悄悄少写几条、
 * 或者多写几条给自己凑通过率，那么「通过」这两个字就一文不值——而这一层本来就是摆给人看的
 * 证据（十五.1）。所以这里宁可错杀一次让人来看一眼，也不放过一条对不上线的。
 *
 * <p><b>为什么把 expect 绑给「最近一条 CASE」，不做距离判定。</b>锚点写在第几行是排版问题，
 * 加一条「expect 必须在 CASE 后面 N 行内」的规则，只会在模型多空一行时报一句假红。
 * 同一条用例写了两遍 expect 时两条都拿去比——不一致的那条照样会被抓出来。
 *
 * <p><b>为什么孤儿 expect（前面没有 CASE）不算一条问题。</b>它接不回任何编号，而它对应的那条
 * 用例本来就缺锚点，会被第 ① 条报成「漏实现」——一条用例只报一次，不重复刷屏。
 */
public final class CaseTraceCheck {

    /** {@code CASE <编号>}：整行只有这一句。后面跟了别的话就不是锚点，免得范围描述被当成实现。 */
    private static final Pattern CASE_LINE = Pattern.compile("(?i)^case\\s*#?\\s*(\\d+)$");

    /** {@code expect: <期望>}：冒号后面那一整段就是期望原文（中文冒号也认）。 */
    private static final Pattern EXPECT_LINE = Pattern.compile("(?i)^expect\\s*[:：]\\s*(.*)$");

    /**
     * 行首的注释引导符。<b>按长的排前面</b>：{@code <!--} 先于 {@code --}，
     * {@code /*} 先于 {@code *}，否则会被短的啃掉一截、剩下半个符号让锚点认不出来。
     */
    private static final List<String> COMMENT_LEADS =
            List.of("<!--", "/*", "//", "::", "--", "#", ";", "*");

    /** 行尾的注释收尾符（HTML 注释与块注释）。 */
    private static final List<String> COMMENT_CLOSERS = List.of("-->", "*/");

    private CaseTraceCheck() {
    }

    /** 一条对不上线的地方。 */
    public enum Kind {

        MISSING("漏实现"),
        EXTRA("清单外乱写"),
        DUPLICATE("重复实现"),
        CHANGED("偷偷改期望");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一处差异。
     *
     * @param kind   哪一条判据
     * @param index  用例编号；{@link Kind#EXTRA} 时是代码里那个清单外的编号
     * @param where  在哪（{@code 文件:行}，对不上就是空串）
     * @param detail 给人看的一句话，说清差异是什么
     */
    public record Finding(Kind kind, int index, String where, String detail) {

        public Finding {
            where = where == null ? "" : where;
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * 一条<b>接上了</b>的连线：用例编号落在哪个文件的第几行。
     *
     * <p>它是界面上「✅ 已连线 + 实现文件:行」那一栏的全部数据来源。为什么不给整段代码的地址范围：
     * 段落在哪儿由模型排版决定，而锚点那一行是<b>引擎唯一能确定</b>的位置——给一个算出来的范围
     * 等于凭空猜一个行号，点过去可能落在别处。
     */
    public record Link(int index, String file, int line) {
    }

    /**
     * 核对结论。
     *
     * @param links    接上的那些用例（按清单顺序）
     * @param findings 对不上的那些地方（空 = 通过）
     */
    public record Report(List<Link> links, List<Finding> findings) {

        public Report {
            links = links == null ? List.of() : List.copyOf(links);
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        /** 可以跑了吗——四条判据一条都不能有。 */
        public boolean ok() {
            return findings.isEmpty();
        }

        /** 一行日志／留档用的结论：缺几条、多几条、哪条期望被改了。 */
        public String summarize() {
            if (findings.isEmpty()) {
                return "溯源核对通过：" + links.size() + " 条用例都连上了测试代码";
            }
            StringBuilder out = new StringBuilder("溯源核对不通过：");
            for (Kind kind : Kind.values()) {
                List<Integer> indexes = findings.stream()
                        .filter(finding -> finding.kind() == kind)
                        .map(Finding::index)
                        .distinct()
                        .sorted()
                        .toList();
                if (indexes.isEmpty()) {
                    continue;
                }
                StringBuilder numbers = new StringBuilder();
                for (Integer index : indexes) {
                    numbers.append(numbers.length() == 0 ? "" : "、").append(index);
                }
                out.append(kind.label()).append(' ').append(indexes.size())
                        .append(" 条（用例 ").append(numbers).append("）；");
            }
            out.setLength(out.length() - 1);
            return out.toString();
        }
    }

    /** 代码里出现过的一处锚点位置。 */
    private record Spot(String file, int line) {

        String show() {
            return file + ":" + line;
        }
    }

    /** 代码里认出来的一个编号：它在哪些地方出现、旁边写了什么期望。 */
    private static final class Anchors {

        private final List<Spot> spots = new ArrayList<>();
        private final Set<String> expects = new LinkedHashSet<>();
    }

    /**
     * 核对一遍。
     *
     * @param declared 冻结的那份用例清单（分母；空清单时调用方不该调到这里来）
     * @param contents 这次生成的全部测试产物：<b>路径 → 完整正文</b>。
     *                 正文必须是完整的——掐过头的正文里可能正好少了后半截的锚点，
     *                 那会把一条已经实现的用例判成「漏实现」
     */
    public static Report check(List<PlanReview.TestCase> declared, Map<String, String> contents) {
        Map<Integer, Anchors> found = scan(contents);
        List<Link> links = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Set<Integer> declaredIndexes = new LinkedHashSet<>();

        for (PlanReview.TestCase testCase : declared) {
            int index = testCase.index();
            declaredIndexes.add(index);
            Anchors anchors = found.get(index);
            List<Spot> spots = anchors == null ? List.of() : anchors.spots;
            Spot first = spots.isEmpty() ? null : spots.get(0);
            if (spots.isEmpty()) {
                findings.add(new Finding(Kind.MISSING, index, "",
                        "测试代码里没有扫到 CASE " + index + " 这一段"));
                continue;
            }
            if (anchors.expects.isEmpty()) {
                findings.add(new Finding(Kind.MISSING, index, first.show(),
                        "只写了 CASE " + index + "，没有跟着一行 expect:（协议要求两行都写）"));
                continue;
            }
            links.add(new Link(index, first.file(), first.line()));
            if (spots.size() > 1) {
                findings.add(new Finding(Kind.DUPLICATE, index, first.show(),
                        "用例 " + index + " 出现了 " + spots.size() + " 次：" + locations(spots)));
            }
            for (String written : anchors.expects) {
                // 清单里没写期望时不做比较：没有「照抄」的对象，判它改了期望是替清单背锅
                if (testCase.expected().isEmpty() || written.equals(normalize(testCase.expected()))) {
                    continue;
                }
                findings.add(new Finding(Kind.CHANGED, index, first.show(),
                        "清单里写的是「" + testCase.expected() + "」，代码里写的是「" + written + "」"));
            }
        }

        List<Integer> extra = new ArrayList<>(found.keySet());
        extra.removeAll(declaredIndexes);
        extra.sort(Integer::compareTo);
        for (Integer index : extra) {
            findings.add(new Finding(Kind.EXTRA, index, found.get(index).spots.get(0).show(),
                    "清单里没有编号 " + index + " 这条用例，测试代码却写了它"));
        }
        return new Report(links, findings);
    }

    /** 逐个文件扫一遍锚点。文件顺序就是产物写入的顺序，所以结论也是稳定的。 */
    private static Map<Integer, Anchors> scan(Map<String, String> contents) {
        Map<Integer, Anchors> found = new LinkedHashMap<>();
        if (contents == null) {
            return found;
        }
        for (Map.Entry<String, String> file : contents.entrySet()) {
            String text = file.getValue();
            if (text == null || text.isEmpty()) {
                continue;
            }
            scanFile(found, file.getKey(), text);
        }
        return found;
    }

    /** 一个文件里的锚点：{@code CASE n} 开一段，后面遇到 {@code expect:} 就归给它。 */
    private static void scanFile(Map<Integer, Anchors> found, String file, String text) {
        int current = -1;
        int lineNumber = 0;
        for (String raw : text.split("\\R", -1)) {
            lineNumber++;
            String line = anchorText(raw);
            Matcher caseMatcher = CASE_LINE.matcher(line);
            if (caseMatcher.matches()) {
                current = Integer.parseInt(caseMatcher.group(1));
                found.computeIfAbsent(current, key -> new Anchors()).spots.add(new Spot(file, lineNumber));
                continue;
            }
            Matcher expectMatcher = EXPECT_LINE.matcher(line);
            if (current > 0 && expectMatcher.matches()) {
                String value = normalize(expectMatcher.group(1));
                if (!value.isEmpty()) {
                    found.get(current).expects.add(value);
                }
            }
        }
    }

    /**
     * 这一行的<b>锚点正文</b>：剥掉行首的注释符、行尾的注释收尾符。
     *
     * <p>为什么连注释符一起剥：各语言的注释写法不一样（Java 是 {@code //}、Python 是 {@code #}、
     * SQL 是 {@code --}、cmd 是 {@code REM}），而引擎不许知道语言（十五.1）。剥了它，
     * 「两行锚点的内容格式统一」这句话才成立，各语言只需要换个引导符。
     *
     * <p>不带注释符的裸写法也认（模型把它写成独立一行时）：多认一种写法只会少一次误判，
     * 而少认一种就会把「其实写了」判成漏实现——后者要人重新生成一次才看得出来。
     */
    private static String anchorText(String raw) {
        String text = raw.strip();
        if (text.regionMatches(true, 0, "REM", 0, 3)
                && (text.length() == 3 || Character.isWhitespace(text.charAt(3)))) {
            text = text.substring(3).strip();
        } else {
            for (String lead : COMMENT_LEADS) {
                if (text.startsWith(lead)) {
                    text = text.substring(lead.length()).strip();
                    break;
                }
            }
        }
        for (String closer : COMMENT_CLOSERS) {
            while (text.endsWith(closer)) {
                text = text.substring(0, text.length() - closer.length()).strip();
            }
        }
        return text;
    }

    /**
     * 归一化空白：换行、制表、连续空格、不间断空格、全角空格都算一个空格。
     *
     * <p>只归一化空白，<b>不碰其它任何字符</b>——「逐字照抄」是这条判据的全部意义，
     * 改一个标点、换一个数字都是改了期望。归一化空白只是不想让排版差异冒充错误。
     */
    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\u00a0\\u3000]+", " ").strip();
    }

    private static String locations(List<Spot> spots) {
        StringBuilder out = new StringBuilder();
        for (Spot spot : spots) {
            out.append(out.length() == 0 ? "" : "、").append(spot.show());
        }
        return out.toString();
    }
}
