package com.specflow.tests;

import com.specflow.patch.PatchApplier;
import com.specflow.review.PlanReview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「回喂给开发」这一段——十五.6 第一条路真正落地的地方。
 *
 * <p>在它之前，这条路上只有留档里一个标签（「开发 AI 错了（已回喂）」），
 * 而那个「已回喂」是假的：引擎里根本没有把失败交给开发的那条路，
 * 界面只能把它拼成一条内联上下文塞进请求里。真模型实测里，那一轮开发 Agent
 * <b>把文件原样再交了一遍</b>（写入字节数与旧版逐字相同、diff 为空），
 * 而界面上「已写入 1 个文件」长得和真改过一模一样。
 *
 * <p>所以这一批把这件事收回引擎：<b>内容按十五.7 的固定模板拼</b>，放进提示词里、
 * 排在「需求 → 施工单」之后，并且<b>连它一起写进运行留档</b>——
 * 事后翻记录的人要答得出「它当时到底按什么在改」。
 *
 * <p><b>给什么、不给什么，是这一段最要紧的事。</b>给：用例的语义描述、失败类型、
 * 期望 vs 实际、涉及的目标文件。不给：测试代码、断言源码。给了断言，模型最省事的做法
 * 就是照着断言改代码——而那份断言本身可能才是错的（十五.7）。
 *
 * @param cases 这次回喂了哪几条用例（编号，升序）
 * @param text  拼好的那一段（含标题）
 */
public record Refeed(List<Integer> cases, String text) {

    /**
     * 那段话的标题。固定成一句，是因为「回喂了没有」这件事在留档里要一眼看得出来——
     * 换了措辞就得靠人解读。
     */
    public static final String HEADING = "## 上一轮的测试失败";

    /** 认用例编号用：失败清单那一栏是脚本的原话，里面那串数字就是编号。 */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    public Refeed {
        cases = cases == null ? List.of() : List.copyOf(cases);
        text = text == null ? "" : text;
    }

    /** 空的回喂：这次不是「下一轮」，提示词里不加那一段。 */
    public static Refeed none() {
        return new Refeed(List.of(), "");
    }

    public boolean present() {
        return !cases.isEmpty() && !text.isBlank();
    }

    /**
     * 按十五.7 的固定模板拼一段。
     *
     * @param declared 上一轮那份用例清单（人确认并冻结过的那一份）。它给的是<b>语义描述</b>——
     *                 失败清单里那栏「期望」是脚本自己的原话，两者对不上时以清单为准
     * @param tests    上一轮的测试结论（失败清单在里面）
     * @param targets  本次运行的白名单（{@code spec.targets()}）。<b>run 级</b>，
     *                 不是「这条用例对应哪个文件」——引擎没记过那个对应关系，
     *                 硬编一个上去比说清它是什么更糟
     * @param picked   人勾中的用例编号
     */
    public static Refeed of(List<PlanReview.TestCase> declared, TestOutcome tests,
                            List<String> targets, List<Integer> picked) {
        List<Integer> wanted = picked == null ? List.of() : picked.stream()
                .filter(index -> index != null)
                .distinct()
                .sorted()
                .toList();
        if (wanted.isEmpty()) {
            return none();
        }
        List<PlanReview.TestCase> cases = declared == null ? List.of() : declared;
        List<TestOutcome.Failure> failures = tests == null ? List.of() : tests.failures();
        StringBuilder out = new StringBuilder(HEADING).append('\n');
        out.append("人看过上一轮的失败清单，确认下面这几条是产品代码的问题（不是用例写错了）：\n");
        List<Integer> fed = new ArrayList<>();
        for (Integer index : wanted) {
            PlanReview.TestCase one = caseOf(cases, index);
            TestOutcome.Failure failure = failureOf(failures, index);
            fed.add(index);
            out.append("- 用例 ").append(index).append('「')
                    .append(one == null ? "（清单里没有这一条）"
                            : (one.what().isEmpty() ? "（这条没写要测什么）" : one.what()))
                    .append("」");
            out.append("；失败类型：").append(failure == null
                    ? "没在这份失败清单里" : failure.kind().label());
            String expected = failure != null && !failure.expected().isEmpty()
                    ? failure.expected() : (one == null ? "" : one.expected());
            out.append("；期望 ").append(expected.isEmpty() ? "（没写）" : expected);
            out.append("；实际 ").append(failure == null || failure.actual().isEmpty()
                    ? "（没写）" : failure.actual());
            if (one != null && !one.how().isEmpty()) {
                out.append("；它怎么验的：").append(one.how());
            }
            out.append('\n');
        }
        out.append("涉及的目标文件（本次运行的白名单）：")
                .append(targets == null || targets.isEmpty()
                        ? "（没勾任何目标文件）" : String.join("、", targets)).append('\n');
        out.append("按这些语义去改产品代码，改动只许落在上面这些文件里。\n");
        out.append("测试代码与断言源码不给你：照着断言改代码等于对着答案抄，")
                .append("而那份断言本身可能才是错的。\n");
        return new Refeed(fed, out.toString());
    }

    /**
     * 回喂之后<b>它到底改没改</b>。
     *
     * <p>为什么要有这一条：实测里那一轮回喂，留档记的是「已写入 1 个文件」，
     * 而 {@code diff} 是空的——写出来的内容与旧版<b>逐字相同</b>。界面和命令行都不区分
     * 「改了一个字节」和「原样再交一遍」，于是人点完「下一轮」看不出它其实什么都没做。
     *
     * <p>判据就是「有没有一处真正的差异」：只有这次确实是回喂（{@link #present()}）、
     * 而且每一处改动的 {@code diff} 都是空白时，才说得出口「它没有改动」。
     * 不是回喂的那一轮不给这个结论：普通运行没改文件本来就该说「没有改动」，
     * 那是另一件事，不该顶着这句话出现在结果面板上。
     */
    public static boolean unchanged(Refeed refeed, List<PatchApplier.FileChange> changes) {
        if (refeed == null || !refeed.present()) {
            return false;
        }
        List<PatchApplier.FileChange> list = changes == null ? List.of() : changes;
        return list.stream().allMatch(change -> change.diff() == null || change.diff().isBlank());
    }

    /** 那一栏可能是空的（比如用例是在别的记录里定的）：找不到就返回 {@code null}。 */
    private static PlanReview.TestCase caseOf(List<PlanReview.TestCase> cases, int index) {
        return cases.stream().filter(one -> one.index() == index).findFirst().orElse(null);
    }

    /**
     * 这条用例在失败清单里的那一行。
     *
     * <p>编号从脚本打印的那一栏里取（可能是「用例 7」）：和 {@code TestReport} 认编号同一套口径，
     * 两处各判一套的话，回喂的那一段会和界面上的失败清单对不上号。
     */
    private static TestOutcome.Failure failureOf(List<TestOutcome.Failure> failures, int index) {
        for (TestOutcome.Failure failure : failures) {
            if (numbersIn(failure.testCase()).contains(index)) {
                return failure;
            }
        }
        return null;
    }

    /** 一段文字里的数字们——「哪条用例」那一栏是脚本的原话（可能是「用例 7」）。 */
    private static List<Integer> numbersIn(String text) {
        List<Integer> found = new ArrayList<>();
        if (text == null) {
            return found;
        }
        Matcher matcher = DIGITS.matcher(text);
        while (matcher.find()) {
            try {
                found.add(Integer.valueOf(matcher.group()));
            } catch (NumberFormatException e) {
                // 长得像数字但超出 int 的一串：它不是用例编号，忽略
            }
        }
        return found;
    }
}
