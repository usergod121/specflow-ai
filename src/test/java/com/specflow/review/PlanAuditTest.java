package com.specflow.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这份方案执行不了的地方」的机器检查。
 *
 * <p>第一条用例就是真模型的现场：目标清单只给了 service 和 mapper，
 * 检查阶段却给出「新建 com.library.controller.HealthController」——那种方案根本执行不了，
 * 开发阶段要到补丁被拒才发现，白跑一轮。这件事不用问模型，算一下就知道。
 */
@DisplayName("方案的可行性检查")
class PlanAuditTest {

    /**
     * 一次真实项目的样子。注意这里**故意没有** HealthController.java：
     * 现场那次它还不存在，方案里说"新建"它，而清单里没有这个位置。
     */
    private static final List<String> PROJECT_FILES = List.of(
            "src/main/java/com/library/service/HealthService.java",
            "src/main/java/com/library/mapper/HealthMapper.java",
            "src/main/java/com/library/dto/SummaryDTO.java",
            "src/main/java/com/library/entity/Summary.java",
            "pom.xml");
    private static final List<String> DIRECTORIES = List.of(
            "src", "src/main", "src/main/java", "src/main/java/com",
            "src/main/java/com/library", "src/main/java/com/library/controller",
            "src/main/java/com/library/service", "src/main/java/com/library/mapper",
            "src/main/java/com/library/dto", "src/main/java/com/library/entity");

    private static PlanReview planWith(String flowchart, PlanReview.MissingItem... items) {
        return PlanReview.of("", flowchart, List.of(items));
    }

    @Test
    @DisplayName("现场重现：方案要新建清单外的 Controller → 必须被查出来")
    void catchesNewFileOutsideTargets() {
        PlanReview plan = planWith("flowchart TD\n    A[加接口] --> B[写 Controller]",
                new PlanReview.MissingItem("Controller 类名与包路径",
                        PlanReview.MissingItem.Severity.BLOCKING, "能编译，但类名可能不一致",
                        "无业务影响", "新建 com.library.controller.HealthController，与 HealthService 对应"));

        List<PlanAudit.Finding> findings = PlanAudit.check(plan,
                List.of("src/main/java/com/library/service/HealthService.java",
                        "src/main/java/com/library/mapper/HealthMapper.java"),
                PROJECT_FILES, DIRECTORIES);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).path()).isEqualTo("com/library/controller/HealthController");
        assertThat(findings.get(0).reason()).contains("清单里没有它");
        // 这个文件还不存在，给不出「加进目标文件」用的路径——宁可空着，也不塞一个错的
        assertThat(findings.get(0).suggest()).isNull();
    }

    @Test
    @DisplayName("方案里写的是相对路径时，直接给出可以加进目标文件的那个路径")
    void suggestsPathFormCandidate() {
        PlanReview plan = planWith("flowchart TD\n    A[新建 src/main/java/com/library/entity/Summary.java]");

        List<PlanAudit.Finding> findings = PlanAudit.check(plan,
                List.of("src/main/java/com/library/service/HealthService.java"),
                PROJECT_FILES, DIRECTORIES);

        assertThat(findings).singleElement()
                .satisfies(finding -> assertThat(finding.suggest())
                        .isEqualTo("src/main/java/com/library/entity/Summary.java"));
    }

    @Test
    @DisplayName("同一个文件只是后缀写法不同，不算两回事")
    void acceptsExtensionDifference() {
        PlanReview plan = planWith("flowchart TD\n    A[改] --> B[src/main/java/com/library/dto/SummaryDTO.java]");

        List<PlanAudit.Finding> findings = PlanAudit.check(plan,
                List.of("src/main/java/com/library/dto/SummaryDTO"), PROJECT_FILES, DIRECTORIES);

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("清单里已经有它：不管写成路径还是全限定类名，都不该报")
    void acceptsCoveredTargets() {
        List<String> targets = List.of("src/main/java/com/library/service/HealthService.java");

        assertThat(PlanAudit.check(
                planWith("flowchart TD\n    A[改] --> B[src/main/java/com/library/service/HealthService.java]"),
                targets, PROJECT_FILES, DIRECTORIES)).isEmpty();
        assertThat(PlanAudit.check(
                planWith("flowchart TD\n    A[改] --> B[com.library.service.HealthService]"),
                targets, PROJECT_FILES, DIRECTORIES)).isEmpty();
    }

    @Test
    @DisplayName("项目外面的类名（java.util.List 这类）不当文件")
    void ignoresForeignNamespaces() {
        PlanReview plan = planWith("flowchart TD\n    A[用 java.util.List 接结果] --> B[java.util.stream.Collectors]");

        assertThat(PlanAudit.check(plan, List.of("src/main/java/com/library/dto/SummaryDTO.java"),
                PROJECT_FILES, DIRECTORIES)).isEmpty();
    }

    @Test
    @DisplayName("普通文字里的斜杠不当路径（拿不准就不报）")
    void ignoresProse() {
        PlanReview plan = planWith("flowchart TD\n    A[先不引依赖 / 先导出 CSV] --> B[按 id/order_no 拼]");

        assertThat(PlanAudit.check(plan, List.of("src/main/java/com/library/dto/SummaryDTO.java"),
                PROJECT_FILES, DIRECTORIES)).isEmpty();
    }

    @Test
    @DisplayName("项目里已有、但不在清单里的文件：说法要不一样")
    void distinguishesExistingFileOutsideTargets() {
        PlanReview plan = planWith("flowchart TD\n    A[顺带改一下 com.library.controller.HealthController]");
        List<String> withController = new ArrayList<>(PROJECT_FILES);
        withController.add("src/main/java/com/library/controller/HealthController.java");

        List<PlanAudit.Finding> findings = PlanAudit.check(plan,
                List.of("src/main/java/com/library/service/HealthService.java"),
                withController, DIRECTORIES);

        assertThat(findings).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.reason()).contains("它在项目里");
                    assertThat(finding.suggest())
                            .isEqualTo("src/main/java/com/library/controller/HealthController.java");
                });
    }

    @Test
    @DisplayName("同一个文件提两次只报一条；最多报 5 条")
    void dedupesAndCaps() {
        PlanReview repeated = planWith("flowchart TD\n    A[com.library.controller.A] --> B[com.library.controller.A]");
        assertThat(PlanAudit.check(repeated, List.of("src/main/java/com/library/dto/S.java"),
                PROJECT_FILES, DIRECTORIES)).hasSize(1);

        PlanReview many = planWith("flowchart TD\n    A[com.library.controller.A] --> B[com.library.controller.B]"
                + "\n    B --> C[com.library.controller.C]\n    C --> D[com.library.controller.D]"
                + "\n    D --> E[com.library.controller.E]\n    E --> F[com.library.controller.F]");
        assertThat(PlanAudit.check(many, List.of("src/main/java/com/library/dto/S.java"),
                PROJECT_FILES, DIRECTORIES)).hasSize(5);
    }

    @Test
    @DisplayName("方案干净时一条都不报——这条检查不能变成噪音源")
    void quietWhenPlanIsFine() {
        PlanReview plan = planWith("flowchart TD\n    A[读 HealthService] --> B[改 HealthMapper]\n"
                        + "    B --> C[返回 Result]",
                new PlanReview.MissingItem("统计口径",
                        PlanReview.MissingItem.Severity.QUALITY, "可能算错", "数字对不上", "照现有 SQL 的口径来"));

        assertThat(PlanAudit.check(plan, List.of("src/main/java/com/library/service/HealthService.java"),
                PROJECT_FILES, DIRECTORIES)).isEmpty();
    }
}
