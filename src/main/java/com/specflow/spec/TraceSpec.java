package com.specflow.spec;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 追溯信息：本次改动是为了满足哪条需求。
 *
 * <p>这是「需求是资产」唯一能落地的地方——把需求编号写成目标文件里的注释锚点，
 * 需求文档和代码之间就有了链：需求改了能反查影响面，
 * 三个月后看到一段奇怪的代码也能查到它是为哪条需求写的。
 *
 * <p>只有编号、没有验收标准：验收标准已经是一等公民（{@link Spec#acceptance()}），
 * 它是给人看的一句句话，不是编号。
 *
 * @param requirementId 需求编号，如 {@code R-001}；为空表示不建立追溯
 */
public record TraceSpec(
        String requirementId
) {

    public static final TraceSpec EMPTY = new TraceSpec(null);

    @JsonCreator
    public static TraceSpec of(
            @JsonProperty("requirement-id") @JsonAlias("requirement_id") String requirementId
    ) {
        return new TraceSpec(
                requirementId == null || requirementId.isBlank() ? null : requirementId.trim());
    }

    public boolean present() {
        return requirementId != null;
    }

    // ---------- 输出侧的字段名 ----------
    // 与读取端保持一致，否则写出来的 YAML 自己读不回去。

    @JsonProperty("requirement-id")
    public String requirementId() {
        return requirementId;
    }
}
