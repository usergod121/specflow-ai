package com.specflow.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 施工单的机器校验。
 *
 * <p>三条硬拦全是「照着做会卡住」而不是「可能不够好」：步数越界、最后一步标了中间态、
 * 某一步要动清单外的文件。中间态超标只提示——它是「按功能切」时真实存在的东西，
 * 做成硬拦只会逼着模型为了过关把「中间态」改写成「自洽」。
 */
@DisplayName("施工单的可行性检查")
class StepAuditTest {

    private static final List<String> TARGETS = List.of(
            "src/main/java/com/library/service/HealthService.java",
            "src/main/java/com/library/mapper/HealthMapper.java",
            "src/main/java/com/library/dto/SummaryDTO.java");

    private static PlanStep step(int index, boolean intermediate, String... files) {
        return new PlanStep(index, "第 " + index + " 步做的事", List.of(files), "能编译", intermediate);
    }

    private static PlanStep fine(int index) {
        return step(index, false, "src/main/java/com/library/service/HealthService.java");
    }

    // ---------- 步数 ----------

    @Test
    @DisplayName("步数太少：一次改动而已，分步是白搭，要求合并")
    void rejectsTooFewSteps() {
        StepAudit.Result result = StepAudit.check(List.of(fine(1), fine(2)), TARGETS);

        assertThat(result.blocking()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.step()).as("整份施工单的问题，不是某一步的").isZero();
            assertThat(finding.reason()).contains("2 步").contains("少于 3 步");
        });
    }

    @Test
    @DisplayName("步数太多：每一步都要重装上下文、重跑编译，要求合并")
    void rejectsTooManySteps() {
        List<PlanStep> eight = List.of(fine(1), fine(2), fine(3), fine(4),
                fine(5), fine(6), fine(7), fine(8));

        StepAudit.Result result = StepAudit.check(eight, TARGETS);

        assertThat(result.blocking()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.reason()).contains("8 步").contains("上限是 7 步"));
    }

    @Test
    @DisplayName("3 到 7 步都合格——边界值不该误报")
    void acceptsBoundaryCounts() {
        assertThat(StepAudit.check(List.of(fine(1), fine(2), fine(3)), TARGETS).blocking()).isFalse();
        assertThat(StepAudit.check(List.of(fine(1), fine(2), fine(3), fine(4), fine(5), fine(6),
                fine(7)), TARGETS).blocking()).isFalse();
    }

    // ---------- 最后一步 ----------

    @Test
    @DisplayName("最后一步标了中间态：施工单跑完必须能编译，硬拦")
    void rejectsIntermediateLastStep() {
        StepAudit.Result result = StepAudit.check(
                List.of(fine(1), fine(2), step(3, true, "src/main/java/com/library/dto/SummaryDTO.java")),
                TARGETS);

        assertThat(result.blocking()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.step()).as("要点出是哪一步").isEqualTo(3);
            assertThat(finding.reason()).contains("最后一步").contains("中间态");
        });
    }

    @Test
    @DisplayName("中间的步骤标中间态不拦——那是「按功能切」时躲不掉的东西")
    void allowsIntermediateStepsInTheMiddle() {
        StepAudit.Result result = StepAudit.check(
                List.of(step(1, true, "src/main/java/com/library/service/HealthService.java"),
                        fine(2), fine(3), fine(4)),
                TARGETS);

        assertThat(result.blocking()).isFalse();
        assertThat(result.hints()).as("4 步里有 1 个中间态，没超三分之一").isEmpty();
    }

    // ---------- 文件 ----------

    @Test
    @DisplayName("某一步要动清单外的文件：那一步物理上做不了，硬拦")
    void rejectsFileOutsideTargets() {
        StepAudit.Result result = StepAudit.check(
                List.of(step(1, false, "src/main/java/com/library/controller/HealthController.java"),
                        fine(2), fine(3)),
                TARGETS);

        assertThat(result.blocking()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.step()).isEqualTo(1);
            assertThat(finding.reason()).contains("HealthController.java").contains("不在目标文件清单里");
        });
    }

    @Test
    @DisplayName("同一个越界文件提两次只报一条；每一条越界都报出来，不截断")
    void reportsEachOffendingFileOnce() {
        StepAudit.Result twice = StepAudit.check(List.of(
                step(1, false, "a/Bad.java", "a/Bad.java",
                        "src/main/java/com/library/service/HealthService.java"), fine(2), fine(3)),
                TARGETS);
        assertThat(twice.findings()).hasSize(1);

        StepAudit.Result many = StepAudit.check(List.of(
                step(1, false, "a/Bad.java"), step(2, false, "a/Worse.java"), fine(3)), TARGETS);
        assertThat(many.findings()).hasSize(2);
    }

    @Test
    @DisplayName("只写类名、清单里是完整路径，算同一个文件——不能因为写法不同就报错")
    void acceptsShortFormOfTarget() {
        StepAudit.Result result = StepAudit.check(
                List.of(step(1, false, "HealthService.java"), step(2, false, "HealthMapper"),
                        step(3, false, "src/main/java/com/library/dto/SummaryDTO")),
                TARGETS);

        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("这一步一栏文件没写：不算越界（宁可漏报，也不误报）")
    void ignoresStepsWithoutFiles() {
        StepAudit.Result result = StepAudit.check(
                List.of(new PlanStep(1, "先看看", List.of(), "", false), fine(2), fine(3)), TARGETS);

        assertThat(result.findings()).isEmpty();
    }

    // ---------- 只提示 ----------

    @Test
    @DisplayName("中间态超过三分之一：提一句，但不拦人")
    void hintsWhenTooManyIntermediateSteps() {
        StepAudit.Result result = StepAudit.check(List.of(
                step(1, true, "src/main/java/com/library/service/HealthService.java"),
                step(2, true, "src/main/java/com/library/mapper/HealthMapper.java"),
                step(3, true, "src/main/java/com/library/dto/SummaryDTO.java"),
                step(4, false, "src/main/java/com/library/service/HealthService.java")), TARGETS);

        assertThat(result.findings()).isEmpty();
        assertThat(result.blocking()).isFalse();
        assertThat(result.hints()).singleElement().satisfies(
                hint -> assertThat(hint).contains("3 步").contains("中间态").contains("三分之一"));
    }

    @Test
    @DisplayName("正好三分之一不算超：3 步里 1 个中间态不提")
    void doesNotHintAtExactlyOneThird() {
        StepAudit.Result result = StepAudit.check(List.of(
                step(1, true, "src/main/java/com/library/service/HealthService.java"),
                fine(2), fine(3)), TARGETS);

        assertThat(result.hints()).isEmpty();
    }

    // ---------- 没有施工单 ----------

    @Test
    @DisplayName("没有施工单：不报错，但要说话——静默降级成单步是最坏的做法")
    void speaksUpWhenThereIsNoPlan() {
        StepAudit.Result result = StepAudit.check(List.of(), TARGETS);

        assertThat(result.blocking()).isFalse();
        assertThat(result.hints()).singleElement()
                .satisfies(hint -> assertThat(hint).contains("没有施工单").contains("单步"));
    }

    @Test
    @DisplayName("一份干净的施工单一条都不报——这条检查不能变成噪音源")
    void quietWhenThePlanIsFine() {
        StepAudit.Result result = StepAudit.check(List.of(fine(1), fine(2), fine(3)), TARGETS);

        assertThat(result.findings()).isEmpty();
        assertThat(result.hints()).isEmpty();
    }
}
