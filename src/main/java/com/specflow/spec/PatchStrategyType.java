package com.specflow.spec;

import java.util.Arrays;

/**
 * 补丁合并策略。决定 AI 的输出如何落到文件上。
 *
 * <p>每种策略对应一个 {@code com.specflow.patch.PatchStrategy} 实现，
 * 新增策略只需实现接口并在此登记，不必改动引擎。
 */
public enum PatchStrategyType {

    /**
     * 多块精准替换。当前唯一实现的策略，也是默认值。
     */
    SEARCH_REPLACE;

    public static PatchStrategyType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return SEARCH_REPLACE;
        }
        for (PatchStrategyType type : values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "未知 strategy: " + raw + "（可选 " + Arrays.toString(values()) + "）");
    }
}
