package com.specflow.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specflow.review.PlanReview;
import com.specflow.spec.ContextItem;
import com.specflow.spec.Spec;
import com.specflow.spec.TraceSpec;
import com.specflow.spec.VerifySpec;

import java.util.List;
import java.util.Map;

/**
 * 界面提交的一次运行请求。
 *
 * <p>它是 {@link Spec} 的「表单版」：字段一一对应，但都是界面能直接产出的形状——
 * 没有 YAML、没有版本号、没有策略名（目前只有一种，问了也是白问）。
 * 转换由 {@link #toSpec()} 完成，产出的仍然是引擎原本认识的那个 {@code Spec}，
 * 因此这条路和读 spec.yaml 那条路走的是同一套校验与落盘逻辑。
 *
 * <p>同样没有「新建还是修改」——那由目标文件在不在决定，界面上表现为
 * 「勾选已有文件」还是「手输一个新路径」。
 *
 * <p>未知字段直接报错：界面的字段名和后端对不上时必须立刻可见，
 * 否则会表现成「我明明填了需求，它却说没填」。
 *
 * @param approvedPlan 检查阶段确认过的实现方案。它只影响发给模型的提示词，
 *                     <b>不参与任何校验</b>——引擎该做的判断不会因为「方案已确认」而放宽
 * @param integration  这次要不要跑集成测试。它默认是 {@code false}：**没勾就完全不碰环境**，
 *                     连 docker 都不探一下——「这批之前的行为一个字节都没变」就落在这里。
 *                     勾上之后才要求环境已初始化，也才会多生成一个集成入口脚本（十五.5）
 * @param maxRounds    整次运行允许调用模型几次；不填（或 0）= 按施工单步数自动算，
 *                     见 {@link VerifySpec#roundBudget}
 * @param refeed       <b>回喂给开发的那几条失败用例编号</b>（十五.6 第一条路）。
 *                     空 = 这一次不是「下一轮」。引擎按它从<b>上一轮那条运行留档</b>里
 *                     取出失败清单，照十五.7 的固定模板拼成一段放进提示词，
 *                     并把这一段写进新的留档——「它到底按什么改的」必须答得出来
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RunRequest(
        String template,
        String prompt,
        List<String> acceptance,
        String requirementId,
        Map<String, String> variables,
        List<String> targets,
        List<String> constraints,
        List<ContextItem> context,
        PlanReview approvedPlan,
        Boolean verifyCompile,
        Integer maxRetry,
        Integer maxRounds,
        Boolean integration,
        List<Integer> refeed
) {

    private static final int DEFAULT_MAX_RETRY = VerifySpec.DEFAULT_MAX_RETRY;

    @JsonCreator
    public static RunRequest of(
            @JsonProperty("template") String template,
            @JsonProperty("prompt") String prompt,
            @JsonProperty("acceptance") List<String> acceptance,
            @JsonProperty("requirementId") String requirementId,
            @JsonProperty("variables") Map<String, String> variables,
            @JsonProperty("targets") List<String> targets,
            @JsonProperty("constraints") List<String> constraints,
            @JsonProperty("context") List<ContextItem> context,
            @JsonProperty("approvedPlan") PlanReview approvedPlan,
            @JsonProperty("verifyCompile") Boolean verifyCompile,
            @JsonProperty("maxRetry") Integer maxRetry,
            @JsonProperty("maxRounds") Integer maxRounds,
            @JsonProperty("integration") Boolean integration,
            @JsonProperty("refeed") List<Integer> refeed
    ) {
        return new RunRequest(
                blankToNull(template),
                prompt == null ? "" : prompt,
                acceptance == null ? List.of() : acceptance,
                blankToNull(requirementId),
                variables == null ? Map.of() : variables,
                targets == null ? List.of() : targets,
                constraints == null ? List.of() : constraints,
                context == null ? List.of() : context,
                approvedPlan,
                verifyCompile,
                maxRetry,
                maxRounds,
                integration,
                refeed == null ? List.of() : refeed);
    }

    /**
     * 这次要回喂哪几条失败用例；空表示不是「下一轮」。
     *
     * <p>去重、排序在引擎那一侧做（见 {@code Refeed.of}）：界面只管把勾中的编号发过来，
     * 顺序与重复是界面的自由，而拼出来的那一段必须是稳定的。
     */
    public List<Integer> pickedRefeed() {
        return refeed == null ? List.of() : refeed.stream()
                .filter(index -> index != null && index > 0)
                .distinct()
                .sorted()
                .toList();
    }

    /** 勾没勾集成测试；不填就是没勾。 */
    public boolean runsIntegration() {
        return Boolean.TRUE.equals(integration);
    }

    /**
     * 转成引擎认识的 {@link Spec}。语义校验不在这里做——那是
     * {@link com.specflow.spec.SpecValidator} 的事，两边共用同一套规则。
     */
    public Spec toSpec() {
        return Spec.of(
                null,
                null,
                template,
                variables,
                prompt,
                acceptance,
                targets,
                constraints,
                context,
                new VerifySpec(verifyCompile == null || verifyCompile, null,
                        maxRetry == null ? DEFAULT_MAX_RETRY : maxRetry,
                        maxRounds == null ? VerifySpec.AUTO_ROUNDS : maxRounds),
                TraceSpec.of(requirementId));
    }

    /**
     * 从一份 spec 反向还原成表单形状。
     *
     * <p>任务草稿的存与载都用这个形状：存进去的是 {@code toSpec()}，
     * 载出来的是 {@code from(spec)}——两个方向同一套字段，
     * 界面不用为「保存」和「载入」各写一份映射。
     */
    public static RunRequest from(Spec spec) {
        return new RunRequest(spec.template(), spec.prompt(), spec.acceptance(), spec.trace().requirementId(), spec.variables(),
                spec.targets(), spec.constraints(), spec.context(), null,
                spec.verify().compile(), spec.verify().maxRetry(), spec.verify().maxRounds(), null,
                // 草稿里没有回喂：它是「看了失败清单之后的一次决定」，不是需求的一部分
                List.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
