package com.specflow.tests;

import com.specflow.TestSpecs;
import com.specflow.context.ContextAssembler;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.patch.PatchApplier;
import com.specflow.review.PlanReview;
import com.specflow.spec.Spec;
import com.specflow.template.TemplateRegistry;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>两段式生成的第二段</b>：代码写完、diff 在手之后，只给每条用例补「怎么测」。
 *
 * <p>这一段唯一不许动的东西是<b>期望</b>——看完代码顺手把期望改成「代码现在的行为」，
 * 等于让被测方给自己定答案，测试永远是绿的（见 {@code CaseHowStage} 的类注释）。
 * 所以这里每一条用例都在钉同一件事的两面：<b>怎么测补上了</b>，而
 * <b>期望、分级、要测什么、验收标准一个字节都没变</b>。
 *
 * <p>白盒：假模型按脚本回话（和 {@code TestAgentTest} 里那个桩同一套写法），
 * 这样「它到底收到了什么」也能断言——「差异有没有真的摆给它看」只从回话上看是看不出来的。
 */
@DisplayName("第二段：给用例补「怎么测」")
class CaseHowStageTest {

    @TempDir
    Path root;

    /**
     * 期望逐字照抄的正常路径。
     *
     * <p>为什么连「其余五栏逐字相等」也要一条一条写出来：这一段的产物<b>只有一栏</b>，
     * 而它的实现是「重建一条 TestCase」。重建时漏抄一栏（比如分级），
     * 界面上看不出异常，而「必须过」被悄悄降成「未标」之后，人再看红灯就分不清
     * 是功能没做出来还是它自己觉得这条重要。
     */
    @Test
    @DisplayName("期望逐字照抄：只补上「怎么测」，其余五栏一个字节都没变")
    void copiesTheExpectationVerbatimAndOnlyFillsHow() {
        List<PlanReview.TestCase> frozen = List.of(testCase(1, "金额能查出来",
                PlanReview.TestCase.Level.MUST, "100.00", "A1：金额能查出来"));
        FakeLlm llm = new FakeLlm("1 | 拿 OrderService.query(1) 调一次，看返回 DTO 的 amount | 100.00");

        CaseHowStage.Result result = stage(llm).fill(spec(), frozen, changes());

        assertThat(result.calls()).as("守规矩时只花一次调用").isEqualTo(1);
        assertThat(result.cases()).singleElement().satisfies(after -> {
            PlanReview.TestCase before = frozen.get(0);
            assertThat(after.how()).as("这一段唯一的产物就是它")
                    .isEqualTo("拿 OrderService.query(1) 调一次，看返回 DTO 的 amount");
            assertThat(after.index()).isEqualTo(before.index());
            assertThat(after.what()).as("要测什么取自第一段").isEqualTo(before.what());
            assertThat(after.level()).as("分级取自第一段").isEqualTo(before.level());
            assertThat(after.expected()).as("期望是第一段那一条，逐字").isEqualTo("100.00");
            assertThat(after.acceptance()).as("验收标准取自第一段").isEqualTo(before.acceptance());
        });
        assertThat(result.note()).as("note 要说得清补了几条、花了几个调用")
                .contains("第二段补上了 1 条用例的「怎么测」")
                .contains("为它花了 1 次模型调用");
        // 第二段唯一的依据是这次改动的 diff：不给它，它只能照整份文件猜「这次改了什么，所以从哪儿验」
        assertThat(llm.users().get(0)).contains("## 这次改动的 diff").contains("+int a = 2;");
        // 给它看的那份清单是五栏（没有「怎么测」）：第一段那一栏本来就是空的
        assertThat(llm.users().get(0))
                .as("五栏那份表头：有「怎么测」的话它会顺手把那一栏也当成要填的东西")
                .contains("编号 | 要测什么 | 分级 | 期望什么 | 对应哪条验收标准");
    }

    /**
     * 第一次就想改期望：第一次<b>当场打回</b>，并把差异原样摆给它看，第二次守规矩才采用。
     *
     * <p>「差异有没有真的摆出来」必须从这里断言：只断言「第二次的 how 用上了」的话，
     * 一个「从不打回、直接采用第二版」的实现照样是绿的——而它和省掉这道闸没有区别。
     */
    @Test
    @DisplayName("期望被改：第一次就被打回，第二次守规矩才采用，且差异摆给了它看")
    void sendsTheFirstVersionBackWhenTheExpectationWasRewritten() {
        List<PlanReview.TestCase> frozen = List.of(testCase(1, "金额能查出来",
                PlanReview.TestCase.Level.MUST, "100.00", "A1：金额能查出来"));
        FakeLlm llm = new FakeLlm(
                // 第一版：看完代码之后把期望改成了「代码现在的行为」
                "1 | 拿 OrderService.query(1) 调一次 | 100.0",
                // 第二版：照抄第一段
                "1 | 拿 OrderService.query(1) 调一次，看返回 DTO 的 amount | 100.00");

        CaseHowStage.Result result = stage(llm).fill(spec(), frozen, changes());

        assertThat(result.calls()).as("打回一次再要一次：一共两次").isEqualTo(2);
        assertThat(result.cases()).singleElement().satisfies(after -> {
            assertThat(after.how()).as("用的是第二版那一句").contains("看返回 DTO 的 amount");
            assertThat(after.expected()).as("期望仍然是一个字都没动过的 100.00").isEqualTo("100.00");
        });
        assertThat(llm.users()).hasSize(2);
        assertThat(llm.users().get(0)).as("首版不该带那段差异：它是被打了回票才加上的")
                .doesNotContain("上一版哪里不按规矩");
        assertThat(llm.users().get(1))
                .as("第二版带着第一段原文与它自己写的那句：只报一个编号，人看不出它把 100.00 改成了 100.0")
                .contains("## 上一版哪里不按规矩")
                .contains("第一段写的是「100.00」，它写的是「100.0」");
    }

    /**
     * 两版都想改期望：<b>「怎么测」宁可留空，也不把改过的期望放进去</b>。
     *
     * <p>这是这一段存在的理由本身。留一栏空着人看得见，而放进一条「按代码现在的行为写的期望」，
     * 在界面上和一条真用例长得一模一样——比没有测试更糟。
     */
    @Test
    @DisplayName("两版都改期望：怎么测留空、期望一个字没改，note 里两条原文都在")
    void refusesBothVersionsAndLeavesHowEmpty() {
        List<PlanReview.TestCase> frozen = List.of(testCase(1, "金额能查出来",
                PlanReview.TestCase.Level.MUST, "100.00", "A1：金额能查出来"));
        FakeLlm llm = new FakeLlm(
                "1 | 拿 OrderService.query(1) 调一次 | 100.0",
                "1 | 拿 OrderService.query(1) 调一次，看 amount | 100");

        CaseHowStage.Result result = stage(llm).fill(spec(), frozen, changes());

        assertThat(result.calls()).isEqualTo(2);
        assertThat(result.cases()).singleElement().satisfies(after -> {
            assertThat(after.how()).as("没补上就留空——不能拿「改过期望的那一行」凑一条怎么测").isEmpty();
            assertThat(after.expected()).as("一个字都没被改").isEqualTo("100.00");
        });
        assertThat(result.note())
                .as("哪几条没补上、它想改什么，都要写出来（人得看得见现场）")
                .contains("期望被改了")
                .contains("100.00")
                .contains("100")
                .contains("期望一个字都没改")
                .contains("2 次模型调用");
    }

    /** 只差一条时，对得上的那几条照样补上：不能因为一条不规矩就把整批丢回去。 */
    @Test
    @DisplayName("只差一条：对得上的 1、2 补上，第 3 条怎么测留空")
    void fillsTheOnesThatMatchAndLeavesTheRestEmpty() {
        List<PlanReview.TestCase> frozen = List.of(
                testCase(1, "a 能变成 2", PlanReview.TestCase.Level.MUST, "a == 2", "A1"),
                testCase(2, "b 能变成 3", PlanReview.TestCase.Level.SHOULD, "b == 3", "A2"),
                testCase(3, "c 能变成 4", PlanReview.TestCase.Level.MUST, "c == 4", "A3"));
        FakeLlm llm = new FakeLlm(
                "1 | 读 Foo.java 里的 a | a == 2\n"
                        + "2 | 读 Foo.java 里的 b | b == 3\n"
                        + "3 | 读 Foo.java 里的 c | c == 5",
                "1 | 读 Foo.java 里的 a | a == 2\n"
                        + "2 | 读 Foo.java 里的 b | b == 3\n"
                        + "3 | 读 Foo.java 里的 c | 4");

        CaseHowStage.Result result = stage(llm).fill(spec(), frozen, changes());

        assertThat(result.calls()).isEqualTo(2);
        assertThat(result.cases()).extracting(PlanReview.TestCase::how)
                .as("对得上的两条补上，改期望那条留空")
                .containsExactly("读 Foo.java 里的 a", "读 Foo.java 里的 b", "");
        assertThat(result.cases()).extracting(PlanReview.TestCase::expected)
                .as("三条的期望都还是第一段那份").containsExactly("a == 2", "b == 3", "c == 4");
        assertThat(result.note()).contains("对得上的 2/3 条已经补上「怎么测」").contains("c == 4");
    }

    /** 清单上某条它没写：少一条也不放行（少写一条就是把一条用例悄悄从分母里拿掉）。 */
    @Test
    @DisplayName("缺一条：报「它没写」，另一条照样补上")
    void reportsAMissingCase() {
        List<PlanReview.TestCase> frozen = List.of(
                testCase(1, "a 能变成 2", PlanReview.TestCase.Level.MUST, "a == 2", "A1"),
                testCase(2, "b 能变成 3", PlanReview.TestCase.Level.MUST, "b == 3", "A2"));
        FakeLlm llm = new FakeLlm("1 | 读 Foo.java 里的 a | a == 2",
                "1 | 读 Foo.java 里的 a | a == 2");

        CaseHowStage.Result result = stage(llm).fill(spec(), frozen, changes());

        assertThat(result.calls()).isEqualTo(2);
        assertThat(result.cases()).extracting(PlanReview.TestCase::how)
                .containsExactly("读 Foo.java 里的 a", "");
        assertThat(result.note()).contains("用例 2 它没写（一条都不许少）");
    }

    /** 多写一条清单外的编号：那一条接不回任何用例，不能当没看见。 */
    @Test
    @DisplayName("多写一条：报「编号不在这份清单里」，清单上那条照样补上")
    void reportsAnUnknownCase() {
        List<PlanReview.TestCase> frozen = List.of(
                testCase(1, "a 能变成 2", PlanReview.TestCase.Level.MUST, "a == 2", "A1"));
        String answer = "1 | 读 Foo.java 里的 a | a == 2\n9 | 顺手把别的也验了 | 无所谓";
        FakeLlm llm = new FakeLlm(answer, answer);

        CaseHowStage.Result result = stage(llm).fill(spec(), frozen, changes());

        assertThat(result.calls()).isEqualTo(2);
        assertThat(result.cases()).singleElement()
                .satisfies(one -> assertThat(one.how()).isEqualTo("读 Foo.java 里的 a"));
        assertThat(result.note()).contains("编号 9 不在这份清单里，它却写了");
    }

    /** 「怎么测」是空的那一栏：这一段要的就是它，空的等于没补。 */
    @Test
    @DisplayName("怎么测为空：不算补上，note 里点出来")
    void reportsAnEmptyHow() {
        List<PlanReview.TestCase> frozen = List.of(
                testCase(1, "a 能变成 2", PlanReview.TestCase.Level.MUST, "a == 2", "A1"));
        FakeLlm llm = new FakeLlm("1 |  | a == 2", "1 |  | a == 2");

        CaseHowStage.Result result = stage(llm).fill(spec(), frozen, changes());

        assertThat(result.calls()).isEqualTo(2);
        assertThat(result.cases()).singleElement()
                .satisfies(one -> assertThat(one.how()).isEqualTo(""));
        assertThat(result.note()).contains("用例 1 的「怎么测」是空的");
    }

    /**
     * 第一段那一栏<b>本来就是空的</b>时不比较。
     *
     * <p>没有「照抄的对象」，判它改了期望是替清单背锅（与 {@code CaseTraceCheck} 同一条口径）。
     * 这一条要能抓到「无条件逐字比较」那种写法：那样写会把一条本来合法的回话判成改期望。
     */
    @Test
    @DisplayName("第一段期望本来就空：第 3 栏写别的东西也照样通过")
    void doesNotCompareWhenTheFirstStageExpectationWasBlank() {
        List<PlanReview.TestCase> frozen = List.of(testCase(1, "金额能查出来",
                PlanReview.TestCase.Level.MUST, "", "无"));
        FakeLlm llm = new FakeLlm("1 | 拿 OrderService.query(1) 调一次 | 我随手写的什么都行");

        CaseHowStage.Result result = stage(llm).fill(spec(), frozen, changes());

        assertThat(result.calls()).as("没有可比的对象：一次就过").isEqualTo(1);
        assertThat(result.cases()).singleElement().satisfies(after -> {
            assertThat(after.how()).isEqualTo("拿 OrderService.query(1) 调一次");
            assertThat(after.expected()).as("第一段空着，第二段也不替它填").isEmpty();
        });
    }

    /** 清单为空：没有可补的东西，一次模型调用都不该花。 */
    @Test
    @DisplayName("清单为空：一次调用都不花，原样返回")
    void spendsNothingOnAnEmptyList() {
        FakeLlm llm = new FakeLlm();

        CaseHowStage.Result result = stage(llm).fill(spec(), List.of(), changes());

        assertThat(result.calls()).isZero();
        assertThat(result.cases()).isEmpty();
        assertThat(result.note()).isEmpty();
        assertThat(llm.calls()).as("空清单也要跑一次模型的话，那是白花的钱").isZero();
    }

    // ---------- merge：把「怎么测」并回完整那份清单 ----------

    /**
     * 交给第二段的是<b>活着的</b>那几条（停用的不进测试阶段），而留档要留<b>完整</b>那份。
     *
     * <p>不并这一步，被停用的用例就会从留档里消失：用户点了「删掉它」之后既看不见它、
     * 也恢复不了（{@code RunStore.disable} 会回一句「这条不在那次冻结的清单里」），
     * 停用于是成了不可逆的删除——那正是它当初没做成删除的理由。
     *
     * <p>顺带钉住「其余栏一律以冻结那份为准」：第二段的产物本来就只有「怎么测」一栏，
     * 它要是连期望、分级一起写歪了，一个字节都不该进结果。
     */
    @Test
    @DisplayName("merge：refined 里缺席的那条照样留着，且只换「怎么测」一栏")
    void mergeKeepsTheCaseThatWasNotRefined() {
        PlanReview.TestCase stopped = testCase(2, "b 能变成 3",
                PlanReview.TestCase.Level.SHOULD, "b == 3", "A2");
        List<PlanReview.TestCase> frozen = List.of(
                testCase(1, "a 能变成 2", PlanReview.TestCase.Level.MUST, "a == 2", "A1"),
                stopped,
                testCase(3, "c 能变成 4", PlanReview.TestCase.Level.MUST, "c == 4", "A3"));
        // 停用的那条压根不在第二段手里；而它带回来的另外几栏特意写歪（见 refined 辅助）
        List<PlanReview.TestCase> refined = List.of(refined(1, "读 Foo.java 里的 a"),
                refined(3, "读 Foo.java 里的 c"));

        List<PlanReview.TestCase> merged = CaseHowStage.merge(frozen, refined);

        assertThat(merged).extracting(PlanReview.TestCase::index)
                .as("停用的用例要看得见——它从留档里消失，「恢复」就再也按不回来")
                .containsExactly(1, 2, 3);
        assertThat(merged.get(1)).as("冻结清单里那条原样带着").isSameAs(stopped);
        assertThat(merged.get(1).how()).as("它没进第二段，怎么测就该空着").isEmpty();
        assertThat(merged).extracting(PlanReview.TestCase::how)
                .containsExactly("读 Foo.java 里的 a", "", "读 Foo.java 里的 c");

        for (int position : new int[] {0, 2}) {
            PlanReview.TestCase before = frozen.get(position);
            PlanReview.TestCase after = merged.get(position);
            assertThat(after.what()).as("要测什么以冻结那份为准").isEqualTo(before.what());
            assertThat(after.level()).as("分级以冻结那份为准").isEqualTo(before.level());
            assertThat(after.expected()).as("期望以冻结那份为准").isEqualTo(before.expected());
            assertThat(after.acceptance()).as("验收标准以冻结那份为准").isEqualTo(before.acceptance());
        }
        // 编号不在冻结清单里的行接不回任何用例，不该凭空多出一条
        assertThat(CaseHowStage.merge(frozen, List.of(refined(9, "清单外那一行"))))
                .isEqualTo(frozen);
    }

    /** 冻结清单为空（这次没有清单）：原样返回第二段那份，不替它编一份空的。 */
    @Test
    @DisplayName("merge：冻结清单为空时原样返回第二段那份")
    void mergeReturnsRefinedWhenFrozenIsEmpty() {
        List<PlanReview.TestCase> onlyRefined = List.of(refined(1, "读 Foo.java 里的 a"));

        assertThat(CaseHowStage.merge(List.of(), onlyRefined)).isSameAs(onlyRefined);
        assertThat(CaseHowStage.merge(null, onlyRefined)).isSameAs(onlyRefined);
        assertThat(CaseHowStage.merge(List.of(), List.of())).isEmpty();
    }

    /**
     * 没有一条带回「怎么测」（第二段整段没补上）：原样返回冻结那份。
     *
     * <p>{@code isSameAs} 在这里不是实现细节：调用方拿它判断「这份清单有没有被第二段动过」。
     */
    @Test
    @DisplayName("merge：一条「怎么测」都没有时原样返回冻结那份")
    void mergeReturnsTheFrozenListWhenNothingWasFilled() {
        List<PlanReview.TestCase> frozen = List.of(
                testCase(1, "a 能变成 2", PlanReview.TestCase.Level.MUST, "a == 2", "A1"),
                testCase(2, "b 能变成 3", PlanReview.TestCase.Level.SHOULD, "b == 3", "A2"));

        assertThat(CaseHowStage.merge(frozen, List.of())).isSameAs(frozen);
        assertThat(CaseHowStage.merge(frozen, null)).isSameAs(frozen);
        assertThat(CaseHowStage.merge(frozen, List.of(
                testCase(1, "a 能变成 2", PlanReview.TestCase.Level.MUST, "a == 2", "A1"))))
                .as("带回来的是空「怎么测」：那等于没补").isSameAs(frozen);
    }

    // ---------- 辅助 ----------

    /** 第二段的产物：只有「怎么测」是真的，其余几栏故意写歪——它们一个字节都不该进结果。 */
    private static PlanReview.TestCase refined(int index, String how) {
        return new PlanReview.TestCase(index, "被改过的要测什么", how,
                PlanReview.TestCase.Level.OPTIONAL, "被改过的期望", "被改过的验收标准");
    }

    private CaseHowStage stage(LlmClient llm) {
        return new CaseHowStage(new ContextAssembler(new SafePathResolver(root)),
                TemplateRegistry.empty(), llm);
    }

    private static Spec spec() {
        return TestSpecs.builder().prompt("把金额查出来").targets(List.of("Foo.java")).build();
    }

    /** 这次改动的 diff——第二段唯一的依据。 */
    private List<PatchApplier.FileChange> changes() {
        return List.of(new PatchApplier.FileChange(root.resolve("Foo.java"), "Foo.java", false,
                24, "-int a = 1;\n+int a = 2;"));
    }

    private static PlanReview.TestCase testCase(int index, String what,
                                                PlanReview.TestCase.Level level,
                                                String expected, String acceptance) {
        // 第一段产出的「怎么测」本来就是空的：这一栏是第二段才补的东西
        return new PlanReview.TestCase(index, what, "", level, expected, acceptance);
    }

    /**
     * 按脚本回话的假模型。
     *
     * <p>答案<b>用尽就报错</b>，不悄悄重复最后一条：本类里有好几条用例都在断言
     * 「一共调了几次」，悄悄重复会让多出来的那一次看起来是正常的。
     * 「两版都不规矩」那种用例自己把同一份答案给两遍，用不着这个桩替它猜。
     */
    private static final class FakeLlm implements LlmClient {

        private final Deque<String> answers;
        private final List<String> users = new ArrayList<>();
        private int calls;

        FakeLlm(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public String complete(List<ChatMessage> messages) {
            calls++;
            for (ChatMessage message : messages) {
                if (!ChatMessage.SYSTEM.equals(message.role())) {
                    users.add(message.content());
                }
            }
            if (answers.isEmpty()) {
                throw new IllegalStateException("假模型没有更多答案了（第 " + calls + " 次调用）");
            }
            return answers.poll();
        }

        int calls() {
            return calls;
        }

        List<String> users() {
            return users;
        }
    }
}
