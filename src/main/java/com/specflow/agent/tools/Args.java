package com.specflow.agent.tools;

import java.util.Map;

/**
 * 从模型给的参数表里取值。
 *
 * <p>{@link com.specflow.agent.ToolRegistry} 已经把「不认识的键、缺必填的键」拦过了，
 * 这里只管<b>类型</b>：说清是哪个参数、收到的是什么，让模型下一轮能改对。
 *
 * <p>补丁的 {@code search} / 文件的 {@code content} 一律走 {@link #raw}：
 * 缩进与行尾空白是内容的一部分，剥一下锚点就对不上了。
 */
public final class Args {

    private Args() {
    }

    /** 必填文本（去掉首尾空白）；缺了、空了都拒。 */
    public static String text(Map<String, Object> args, String name) {
        String value = raw(args, name, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("参数 " + name + " 不能为空");
        }
        return value.strip();
    }

    /** 可选整数（数字、数字字符串都认——模型两种都写过）。 */
    public static Integer intOrNull(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("参数 " + name + " 应该是整数，收到：" + text);
            }
        }
        throw new IllegalArgumentException("参数 " + name + " 应该是整数，收到 " + value.getClass().getSimpleName());
    }

    /** 原样取文本（不 strip）。{@code fallback} 是键不存在或值为 null 时的取值。 */
    public static String raw(Map<String, Object> args, String name, String fallback) {
        Object value = args.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("参数 " + name + " 应该是字符串，收到 " + value.getClass().getSimpleName());
    }
}
