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
        Integer maxRetry
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
            @JsonProperty("maxRetry") Integer maxRetry
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
                maxRetry);
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
                        maxRetry == null ? DEFAULT_MAX_RETRY : maxRetry),
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
                spec.verify().compile(), spec.verify().maxRetry());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
