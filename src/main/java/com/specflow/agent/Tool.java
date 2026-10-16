package com.specflow.agent;

import java.util.Map;

/**
 * 模型能调用的一个工具 = 名字 + 用途 + 参数声明 + 执行体。
 *
 * <p>{@link #run} 允许抛 {@link RuntimeException}（越界、锚点不唯一、磁盘满）——
 * 统一由 {@link ToolRegistry#call} 翻成 {@code ok=false} 的结果。
 * 调用方请一律走注册表，别直接 new 一个工具来 run。
 */
public interface Tool {

    String name();

    /** 写给模型看：什么时候该用它（不是复述名字）。 */
    String description();

    /** 本工具接受的参数；没参数就给 {@code Map.of()}。 */
    Map<String, Param> params();

    ToolResult run(Map<String, Object> args);

    /**
     * 一个参数的声明。
     *
     * @param type        {@code string} / {@code int} / {@code bool} / {@code object} / {@code array}
     * @param description 写给模型看的说明
     * @param required    必填（缺这个键就拒，不看值是不是空串）
     */
    record Param(String type, String description, boolean required) {

        public static Param required(String type, String description) {
            return new Param(type, description, true);
        }

        public static Param optional(String type, String description) {
            return new Param(type, description, false);
        }
    }
}
