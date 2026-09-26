package com.specflow.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.specflow.review.PlanReview;
import com.specflow.spec.ContextItem;

import java.util.List;

/**
 * 一次运行的完整留档。
 *
 * <p>存在的理由：模型说「我还缺某样东西」而停下来的时候，<b>代码已经回滚了</b>——
 * 磁盘上看不出它到底做了什么。那份信息只存在于这次运行的过程中，
 * 不留下来就等于没有。所以这里存的不是代码，是<b>过程</b>：
 * 它打算做什么、实际改了哪些文件、为什么停。
 *
 * <p>它同时是出错时的第一手材料：把这份记录贴给任何人（或任何模型），
 * 对方都能完整还原当时发生了什么，不需要你在一旁回忆。
 *
 * @param id        时间戳形式的标识，同时也是文件名
 * @param startedAt 开始时间，ISO 格式
 * @param status    终态，与 {@code AgentResult.Status} 同名
 * @param template  使用的模板名；不用模板时为空
 * @param prompt    当时的<b>需求</b>——留档里最该有的一项
 * @param acceptance 当时的验收标准
 * @param context   当时给模型的上下文依赖。它同样是「当时依据什么」的一部分：
 *                  引用形态只记路径，文件早改了；内联文本则被截断过（见 {@link RunRecorder}）
 * @param targets   本次允许改动的文件
 * @param attempts  实际调用了模型几次
 * @param detail    面向人的结论
 * @param missing   检查阶段报出的缺失依赖；没跑过检查时为空
 * @param changes   落盘过的改动（失败时这些改动已回滚，但差异本身留在这里）
 * @param steps     按施工单的步记下的过程。分步执行时每一步一条；
 *                  单步执行（没有施工单）时也是<b>一条</b>——那一步就是整次运行，
 *                  引擎为它不发步级事件，由录制器按运行结果补上（见 {@code RunRecorder}），
 *                  否则同一次运行在历史详情里会有两种说法。
 *                  老记录里没有这一项，读出来是 {@code null}
 * @param stepsSource 施工单是从哪来的（{@code APPROVED} / {@code GENERATED} / {@code SINGLE}）；
 *                    没有施工单这个概念的记录里是 {@code null}
 * @param timeline  逐条的过程记录
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunRecord(
        String id,
        String startedAt,
        String status,
        String template,
        String prompt,
        List<String> acceptance,
        List<ContextItem> context,
        String requirementId,
        List<String> targets,
        int attempts,
        String detail,
        List<PlanReview.MissingItem> missing,
        List<Change> changes,
        List<Step> steps,
        String stepsSource,
        List<Line> timeline
) {

    /**
     * 一条落盘的改动。
     *
     * @param diff 行级差异，在回滚<b>之前</b>就算好了——回滚之后文件内容还原，差异再也算不出来
     */
    public record Change(String path, boolean created, int bytes, String diff) {
    }

    /**
     * 施工单上的一步，跑完之后的样子。
     *
     * <p>为什么要按步存而不是只存一份总的 changes：一次分步运行的改动是<b>按步累积</b>的，
     * 事后想回答「第 3 步当时动了什么、为什么重试了两次」，只有总账是答不出来的。
     *
     * @param index        第几步，从 1 开始
     * @param goal         这一步做什么
     * @param intermediate 这一步做完是否允许整项目编不过
     * @param state        终态：{@code SUCCESS} / {@code INTERMEDIATE} / {@code FAILED}，
     *                     与 {@code AgentListener.StepState} 同名
     * @param rounds       这一步实际调了几次模型（含失败的那些）
     * @param changes      这一步落盘的改动。步内重试前被回滚掉的那一版不算在它名下
     *                     （磁盘上已经不在了）；但整次运行回滚时已经落盘的改动会留着——
     *                     磁盘上同样看不出来了，留档是唯一还答得出「它当时改了什么」的地方
     */
    public record Step(int index, String goal, boolean intermediate, String state,
                       int rounds, List<Change> changes) {
    }

    /**
     * 时间线上的一行。
     *
     * @param round 第几轮；未开始/未分轮时为 0
     * @param level {@code info} / {@code warn} / {@code error}
     */
    public record Line(int round, String level, String text) {
    }

    /** 列表页用的投影：不带差异与时间线，避免拉一次历史把几 MB 全读进来。 */
    public record Summary(String id, String startedAt, String status, String template,
                          List<String> targets, String detail) {
    }

    public Summary summary() {
        return new Summary(id, startedAt, status, template, targets, detail);
    }
}
