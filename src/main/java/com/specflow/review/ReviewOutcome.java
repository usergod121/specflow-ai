package com.specflow.review;

import java.util.List;

/**
 * 一次检查的产出。
 *
 * <p>刻意分成四块，谁也不依赖谁：
 * <ul>
 *   <li>{@code plan} 是<b>模型说的</b>——摘要、流程图、它认为缺什么、它打算分成几步、打算怎么验；</li>
 *   <li>{@code audit} 是<b>机器查出来的</b>「方案执行不了的地方」（见 {@link PlanAudit}）；</li>
 *   <li>{@code stepAudit} 是<b>机器查出来的</b>「施工单执行不了的地方」（见 {@link StepAudit}）；</li>
 *   <li>{@code coverage} 是<b>机器数出来的</b>「哪条验收标准一条用例都没覆盖」（见
 *       {@link AcceptanceCoverage}）。</li>
 * </ul>
 *
 * <p>分开的理由不只是好看：界面上"要不要拦人"这件事只该由机器那两块决定，
 * 而模型那一块只用来提示。混在一个对象里，早晚会有人拿模型的自评去挡用户。
 * 第四块（覆盖）连拦都不拦——它是给人看的计数（十五.3：用例是证据，不是门槛）。
 *
 * @param plan      模型给的实现方案（含施工单）
 * @param audit     机器查出来的「方案执行不了」清单，可能为空
 * @param stepAudit 机器查出来的「施工单执行不了」清单；没有施工单时两块都是空的
 * @param coverage  机器数出来的验收覆盖情况；没写验收标准时是空的（没什么可覆盖的）
 */
public record ReviewOutcome(PlanReview plan, List<PlanAudit.Finding> audit,
                            StepAudit.Result stepAudit, AcceptanceCoverage.Report coverage) {

    public ReviewOutcome {
        audit = audit == null ? List.of() : List.copyOf(audit);
        stepAudit = stepAudit == null ? StepAudit.none() : stepAudit;
        coverage = coverage == null ? new AcceptanceCoverage.Report(List.of(), List.of(), List.of())
                : coverage;
    }
}
