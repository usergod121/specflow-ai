package com.specflow.patch;

import com.specflow.spec.PatchStrategyType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 策略注册表。
 *
 * <p>把「spec 里写的策略名」映射到「真正干活的实现」。目前只有
 * {@link SearchReplaceStrategy}，但解析层、校验层、Agent、CLI 都只依赖这个注册表，
 * 因此将来加 {@code ast_merge} 之类的策略时改动面被压在一个地方。
 */
public final class PatchStrategies {

    private final Map<PatchStrategyType, PatchStrategy> registry;

    private PatchStrategies(Map<PatchStrategyType, PatchStrategy> registry) {
        this.registry = registry;
    }

    /**
     * 引擎内置策略集合。
     */
    public static PatchStrategies defaults() {
        Map<PatchStrategyType, PatchStrategy> map = new EnumMap<>(PatchStrategyType.class);
        map.put(PatchStrategyType.SEARCH_REPLACE, new SearchReplaceStrategy());
        return new PatchStrategies(map);
    }

    /**
     * @throws IllegalArgumentException 该策略未注册
     */
    public PatchStrategy get(PatchStrategyType type) {
        PatchStrategy strategy = registry.get(Objects.requireNonNull(type, "type"));
        if (strategy == null) {
            throw new IllegalArgumentException("未注册的补丁策略: " + type);
        }
        return strategy;
    }
}
