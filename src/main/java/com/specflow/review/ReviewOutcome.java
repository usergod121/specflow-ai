package com.specflow.review;

import java.util.List;

/**
 * 一次检查的产出。
 *
 * <p>刻意分成三块，谁也不依赖谁：
 * <ul>
 *   <li>{@code plan} 是<b>模型说的</b>——摘要、流程图、它认为缺什么、它打算分成几步；</li>
 *   <li>{@code audit} 是<b>机器查出来的</b>「方案执行不了的地方」（见 {@link PlanAudit}）；</li>
 *   <li>{@code stepAudit} 是<b>机器查出来的</b>「施工单执行不了的地方」（见 {@link StepAudit}）。</li>
 * </ul>
 *
 * <p>分开的理由不只是好看：界面上"要不要拦人"这件事只该由机器那两块决定，
 * 而模型那一块只用来提示。混在一个对象里，早晚会有人拿模型的自评去挡用户。
 *
 * @param plan      模型给的实现方案（含施工单）
 * @param audit     机器查出来的「方案执行不了」清单，可能为空
 * @param stepAudit 机器查出来的「施工单执行不了」清单；没有施工单时两块都是空的
 */
public record ReviewOutcome(PlanReview plan, List<PlanAudit.Finding> audit,
                            StepAudit.Result stepAudit) {

    public ReviewOutcome {
        audit = audit == null ? List.of() : List.copyOf(audit);
        stepAudit = stepAudit == null ? StepAudit.none() : stepAudit;
    }
}
