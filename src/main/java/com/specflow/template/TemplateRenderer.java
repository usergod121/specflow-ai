package com.specflow.template;

import com.specflow.exception.SpecflowException;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{变量}}} 占位符渲染。
 *
 * <p>设计取向是<b>严格</b>而不是宽容：占位符没赋值就报错，绝不替换成空串。
 * 理由很实际——把「修改登录接口」渲染成「修改接口」，模型仍然会输出一份
 * 看起来合理的代码，错误要到很久以后才被发现。宁可在这里停下来。
 */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    private TemplateRenderer() {
    }

    /**
     * @param template  含占位符的文本
     * @param variables 变量表；值不得为 null
     * @throws SpecflowException 存在未赋值的占位符
     */
    public static String render(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, String> values = variables == null ? Map.of() : variables;

        Set<String> missing = new LinkedHashSet<>();
        for (String name : placeholders(template)) {
            if (!values.containsKey(name) || values.get(name) == null) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new SpecflowException("模板占位符未赋值: " + String.join(", ", missing)
                    + "；请在 spec 的 variables 段补上");
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(values.get(matcher.group(1))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 提取文本中出现的全部占位符名（去重、保持出现顺序）。
     */
    public static Set<String> placeholders(String... texts) {
        Set<String> names = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }
}
