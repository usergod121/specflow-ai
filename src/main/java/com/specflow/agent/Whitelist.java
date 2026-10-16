package com.specflow.agent;

import com.specflow.agent.tools.Roots;

import java.util.List;
import java.util.Map;

/**
 * 给写类工具套一层<b>白名单</b>：只有 ② 挑出来的那几个文件能改。
 *
 * <h2>为什么白名单要在工具层拦，而不是只写在提示词里</h2>
 * 提示词不是闸。模型完全可能"顺手"改一个它觉得该改的文件——那个文件也许对，
 * 但用户没有同意过。写了白名单就等于说「这一轮的范围就是这个」，
 * 越界必须<b>失败</b>（并且说清为什么），而不是悄悄改掉。
 *
 * <p>拦的是工具参数里那个 {@code path}：它在被规范化<b>之前</b>就要过一遍，
 * 所以 {@code ../} 这种写法也躲不过去（规范化只会让它更明显，但先拦更省事）。
 */
public final class Whitelist implements Tool {

    private final Tool inner;
    private final List<String> allowed;

    public Whitelist(Tool inner, List<String> allowed) {
        this.inner = inner;
        this.allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    @Override
    public String name() {
        return inner.name();
    }

    @Override
    public String description() {
        return inner.description() + "（只许改：" + String.join("、", allowed) + "）";
    }

    @Override
    public Map<String, Param> params() {
        return inner.params();
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String path = args == null ? "" : String.valueOf(args.getOrDefault("path", "")).strip();
        if (!path.isEmpty() && !allowed.contains(normalize(path))) {
            return ToolResult.failure("`" + path + "` 不在这一轮的写白名单里，不能改。"
                    + "白名单是：" + String.join("、", allowed)
                    + "。要改别的文件先回 ② 把它加进「要改的文件」，再重新跑一次。");
        }
        return inner.run(args);
    }

    /** 比较之前把分隔符与 {@code ./} 统一掉（白名单是人在界面上写的，写法不该有歧义）。 */
    static String normalize(String path) {
        String one = path.replace('\\', '/').strip();
        while (one.startsWith("./")) {
            one = one.substring(2);
        }
        return one;
    }

    /** 只读的那些工具不需要它；这个工厂只是让调用点读起来顺一点。 */
    public static Tool of(Tool inner, List<String> allowed) {
        return new Whitelist(inner, allowed);
    }

    /** 这一轮允许改的（给提示词与测试看）。 */
    public List<String> allowed() {
        return allowed;
    }

    /** 根不变，只是名字读起来清楚（Dev 的写工具都建在项目根上）。 */
    static Roots roots(java.nio.file.Path root) {
        return new Roots(root);
    }
}
