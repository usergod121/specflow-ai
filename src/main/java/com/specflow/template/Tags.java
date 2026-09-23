package com.specflow.template;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模板标签。
 *
 * <p>标签是<b>给模型看的语义提示</b>：写了 {@code mybatis}，它自己就知道有 {@code @Select}。
 * 我们不该去维护一份「标签 → 注解」的对照表——那是把训练数据抄一遍，而且永远抄不全。
 * 所以这里只做两件事：<b>规范化</b>和<b>给界面一份常见清单</b>。
 *
 * <p>标签里既有<b>产物形态</b>（{@link #PRODUCT_TYPES}），也有<b>技术栈</b>（{@link #STACKS}）。
 * 引擎不区分这两层，一律当成「项目这一侧的约定」原样拼给模型——
 * 一旦引擎要去认哪个标签是「形态」，就又得维护那份永远抄不全的对照表。
 *
 * <p>{@link #COMMON} 是输入提示，<b>不是白名单</b>：用户打任何词都算数。
 */
public final class Tags {

    /** 单个标签的长度上限。再长它就不再是标签，而是第二个提示词了。 */
    private static final int MAX_LENGTH = 24;

    /** 标签个数上限。同理——标签的价值在于「一眼扫过」。 */
    private static final int MAX_COUNT = 12;

    /**
     * 产物类型：这一层回答「写出来的是个什么东西」，和用什么语言无关。
     *
     * <p>它是模板最顶层的抽象——一个「接口」模板和一个「脚本」模板，
     * 差别不在技术栈，而在于产出物的形状。
     */
    public static final List<String> PRODUCT_TYPES = List.of(
            "class", "interface", "function", "script", "component", "module", "test");

    /**
     * 技术栈：这一层回答「用什么写」。
     *
     * <p>刻意只有名字、不带解释，也不带版本号（{@code java17} 这种）——
     * 版本属于「项目固定」，写进模板的 system 更合适，标签保持粗。
     */
    public static final List<String> STACKS = List.of(
            "java", "python", "go", "typescript", "javascript",
            "spring-boot", "spring-cloud", "mybatis", "mybatis-plus", "jpa",
            "fastapi", "django", "flask", "express", "nestjs",
            "mysql", "postgresql", "redis", "mongodb", "elasticsearch",
            "rabbitmq", "kafka",
            "react", "vue", "vite", "tailwind",
            "docker", "kubernetes", "grpc", "lombok");

    /**
     * 界面上做输入提示用的完整清单：产物类型在前，技术栈在后。
     *
     * <p>顺序不是随意的——先问「写什么」，再问「用什么写」。
     */
    public static final List<String> COMMON = concat(PRODUCT_TYPES, STACKS);

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new java.util.ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private Tags() {
    }

    /**
     * 去重、去空白、限长限量。
     *
     * <p>限量不是洁癖：标签一旦能塞下一整段话，它就会变成第二个提示词，
     * 而且是<b>优先级说不清</b>的那个。宁可在这里拦住。
     *
     * @throws IllegalArgumentException 某个标签过长，或总数超限
     */
    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String tag : raw) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String normalized = tag.strip();
            if (normalized.length() > MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "标签过长（'" + normalized + "'）：单个标签不超过 " + MAX_LENGTH + " 个字");
            }
            unique.add(normalized);
        }
        if (unique.size() > MAX_COUNT) {
            throw new IllegalArgumentException("标签最多 " + MAX_COUNT + " 个，当前 " + unique.size() + " 个");
        }
        return List.copyOf(unique);
    }
}
