package com.specflow.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一条上下文依赖。
 *
 * <p>它回答的是「模型动手之前，还需要看到什么」。两种形态：
 * <ul>
 *   <li><b>文件引用</b>（{@code ref}）——只记路径，<b>每次运行实时读取</b>。
 *       这样文件改了它跟着变，不会像快照那样悄悄过期</li>
 *   <li><b>内联文本</b>（{@code text}）——直接粘贴一段内容，
 *       典型场景是从数据库导出的表结构 DDL，它在项目里本来就没有对应文件</li>
 * </ul>
 *
 * <p>形态不写成字段：给了 {@code ref} 就是引用，给了 {@code text} 就是内联。
 * 少一个要手填的枚举，就少一类「填错了但没人发现」的错误。
 *
 * <p>本类型是纯数据载体，不含路径安全判断——那是 {@link SpecValidator} 的职责。
 *
 * @param name 展示名，界面上显示；引用形态留空时取路径里的文件名
 * @param ref  相对项目根的文件路径，仅引用形态有值
 * @param text 内联内容，仅内联形态有值
 * @param note 给模型看的说明，讲清楚「为什么要看它」，例如「照这个已有接口的风格写」
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ContextItem(
        String name,
        String ref,
        String text,
        String note
) {

    @JsonCreator
    public static ContextItem of(
            @JsonProperty("name") String name,
            @JsonProperty("ref") String ref,
            @JsonProperty("text") String text,
            @JsonProperty("note") String note
    ) {
        String normalizedRef = blankToNull(ref);
        String normalizedText = blankToNull(text);

        if (normalizedRef == null && normalizedText == null) {
            throw new IllegalArgumentException(
                    "上下文条目既没有 ref（文件引用）也没有 text（内联文本），无法判断它是什么");
        }
        if (normalizedRef != null && normalizedText != null) {
            throw new IllegalArgumentException(
                    "上下文条目同时给了 ref 和 text；引用文件就只写 ref，粘贴内容就只写 text");
        }
        return new ContextItem(
                name == null || name.isBlank() ? defaultName(normalizedRef) : name.strip(),
                normalizedRef,
                text == null ? null : text.strip(),
                note == null ? "" : note.strip()
        );
    }

    /** 引用形态没写名字时，用文件名兜底，界面上不至于出现一行空白。 */
    private static String defaultName(String ref) {
        if (ref == null) {
            return "粘贴的文本";
        }
        String normalized = ref.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    /**
     * 是不是「引用文件」形态。
     *
     * <p>刻意不叫 {@code isReference()}：那个名字符合 {@code isXxx()} 的 getter 命名规则，
     * Jackson 会把它当成属性 {@code reference} 一并序列化出去，
     * 而反序列化时它又不在构造参数里——存下来的东西自己读不回去。
     */
    public boolean hasRef() {
        return ref != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
