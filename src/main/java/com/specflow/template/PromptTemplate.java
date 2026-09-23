package com.specflow.template;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specflow.spec.ContextItem;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一套可复用的「模具」，对应 {@code .specflow/templates/<name>.yaml}。
 *
 * <p>它只装<b>项目那一侧的常量</b>：产物形态、技术栈、角色、代码风格、这个项目里惯用的参考文件。
 * 这些东西配一次，长期复用。
 *
 * <p>它<b>不装需求</b>——需求每次都不一样，写进模板就等于「一个模板只能匹配一个需求」。
 * 需求、验收标准、目标文件、本次补充的上下文，全部属于运行时。
 *
 * <p>字段刻意只有五个。再多就会长成一套模板语言，
 * 而模板语言最终一定会有人想写条件分支和循环，那是另一个项目该干的事。
 *
 * @param name        模板名，spec 里通过 {@code template: <name>} 引用
 * @param tags        标签，如 {@code [class, java, spring-boot, mybatis]}。引擎不读它，
 *                    只负责拼成一句话递给模型
 * @param description 用途说明，界面的模板下拉框里显示
 * @param system      角色、做事方式、代码风格。<b>配一次的静态文本，不支持占位符</b>——
 *                    模板里塞变量，就会重蹈「一个模板只能匹配一种需求」
 * @param context     默认上下文依赖。界面用它预填上下文列表，
 *                    每一条都可以在本次运行里单独移除——<b>引擎不读它</b>，
 *                    真正生效的始终是 spec 自己写的那份列表
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PromptTemplate(
        String name,
        List<String> tags,
        String description,
        String system,
        List<ContextItem> context
) {

    @JsonCreator
    public static PromptTemplate of(
            @JsonProperty("name") String name,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("description") String description,
            @JsonProperty("system") String system,
            @JsonProperty("context") List<ContextItem> context
    ) {
        return new PromptTemplate(
                requireText(name, "模板名"),
                Tags.normalize(tags),
                description == null ? "" : description.strip(),
                requireText(system, "角色提示词"),
                context == null ? List.of() : List.copyOf(context)
        );
    }


    /**
     * 必填文本不能为空。
     *
     * <p>报错里用的是界面上那个名字（「模板名」），不是 JSON 字段名（{@code name}）——
     * 这句话会一路端到用户眼前，中间没有任何一层负责翻译它。
     */
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value;
    }
}
