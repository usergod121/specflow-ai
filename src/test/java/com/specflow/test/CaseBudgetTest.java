package com.specflow.test;

import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>每条用例的轮次预算</b>：卡的是「一条用例」，不是「整轮」。
 *
 * <p>这条测试钉的就是那次事故的形状：整轮预算被"一条还没开始验的用例"吃光。
 * 现在超预算的那一条会被标成 {@code SKIPPED}（带原因），循环被推着去下一条，
 * 而一写回结论（{@code case_update}）预算就清零。
 */
class CaseBudgetTest {

    private static CaseList list(Path dir, String... ids) {
        CaseList cases = new CaseList(dir.resolve("cases.json"));
        List<CaseList.Case> all = new java.util.ArrayList<>();
        for (String id : ids) {
            all.add(new CaseList.Case(id, "验 " + id, List.of("功能"), "期望", null, "", ""));
        }
        cases.save(all);
        return cases;
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Param> params() {
                return Map.of();
            }

            @Override
            public ToolResult run(Map<String, Object> args) {
                return ToolResult.ok(Map.of("ok", true));
            }
        };
    }

    private static String statusOf(CaseList cases, String id) {
        return cases.cases().stream().filter(one -> one.id().equals(id))
                .findFirst().orElseThrow().status().name();
    }

    @Test
    @DisplayName("超预算：第三条调用之后的第四次被拦住，当前用例标成 SKIPPED 并带原因")
    void skipsTheCaseWhenBudgetRunsOut(@TempDir Path dir) {
        CaseList cases = list(dir, "C1", "C2");
        CaseBudget budget = new CaseBudget(cases, 3, null);
        Tool counting = budget.wrap(tool("read_file"));

        assertThat(budget.current()).isEqualTo("C1");
        for (int at = 0; at < 3; at++) {
            assertThat(counting.run(Map.of()).ok()).as("前 3 次照常").isTrue();
        }
        ToolResult stopped = counting.run(Map.of());

        assertThat(stopped.ok()).as("第 4 次被拦住").isFalse();
        assertThat(stopped.reason()).contains("C1").contains("跳过").contains("预算");
        assertThat(statusOf(cases, "C1")).isEqualTo("SKIPPED");
        assertThat(statusOf(cases, "C2")).as("下一条不算这一条的账").isEqualTo("PENDING");
        assertThat(budget.current()).as("注意力换到下一条").isEqualTo("C2");
    }

    @Test
    @DisplayName("写了结论就清零：case_update 之后再给它 3 轮")
    void verdictResetsTheCounter(@TempDir Path dir) {
        CaseList cases = list(dir, "C1", "C2");
        CaseBudget budget = new CaseBudget(cases, 3, null);
        Tool work = budget.wrap(tool("bash"));
        Tool update = budget.wrap(tool("case_update"));

        work.run(Map.of());
        work.run(Map.of());
        work.run(Map.of());
        // 第 3 次之后 if 再来一次就跳过了；先写结论：把 C1 标成 PASS
        cases.settle("C1", CaseList.Status.PASS, "证据原文", "");
        assertThat(update.run(Map.of()).ok()).as("case_update 自己也算一次，但会清零").isTrue();

        assertThat(budget.current()).isEqualTo("C2");
        assertThat(work.run(Map.of()).ok()).as("换了用例，预算重新开始").isTrue();
        assertThat(statusOf(cases, "C1")).isEqualTo("PASS");
    }

    @Test
    @DisplayName("清单全到终态：不再拦任何调用（该去 submit 了）")
    void noBudgetWhenNothingPending(@TempDir Path dir) {
        CaseList cases = list(dir, "C1");
        cases.settle("C1", CaseList.Status.FAIL, "", "不符合预期");
        CaseBudget budget = new CaseBudget(cases, 1, null);
        Tool work = budget.wrap(tool("bash"));

        assertThat(budget.current()).isNull();
        for (int at = 0; at < 5; at++) {
            assertThat(work.run(Map.of()).ok()).isTrue();
        }
    }

    @Test
    @DisplayName("跳过的那一条是**终态**：它让 pending 归零，但不算「真结论」")
    void skippedIsTerminalButNotAVerdict(@TempDir Path dir) {
        CaseList cases = list(dir, "C1");
        cases.skip("C1", "每用例预算用尽");

        assertThat(cases.pending()).isZero();
        List<String> problems = cases.problems();
        assertThat(problems).anySatisfy(one -> assertThat(one).contains("没有一条真跑出结论"));
    }

    @Test
    @DisplayName("重试：reopen 把一条改回 PENDING（跳过/失败的都能重跑）")
    void reopenMakesItRunnable(@TempDir Path dir) {
        CaseList cases = list(dir, "C1");
        cases.skip("C1", "预算用尽");
        assertThat(cases.pending()).isZero();

        cases.reopen("C1");

        assertThat(cases.pending()).isEqualTo(1);
        CaseList.Case one = cases.cases().get(0);
        assertThat(one.status()).isEqualTo(CaseList.Status.PENDING);
        assertThat(one.note()).as("旧的跳过原因要清掉").isEmpty();
    }
}
