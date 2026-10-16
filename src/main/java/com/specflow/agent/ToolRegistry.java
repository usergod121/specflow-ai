package com.specflow.agent;

import com.specflow.core.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具名 → 工具的注册表：按名字派发，<b>不抛异常</b>。
 *
 * <p>这里是「模型写错了」与「引擎炸了」的分界：名字不认、参数不是 JSON、缺必填、工具内部抛了，
 * 四条路全部翻成一份可读的 {@code ok=false} 喂回模型。模型最容易犯的错是把名字记岔
 * （{@code read} / {@code read_file}），所以名单一起给它。
 */
public final class ToolRegistry {

    /** 保序：模型看到的清单顺序 = 装配顺序。用 unmodifiableMap 而不是 Map.copyOf（后者不承诺顺序）。 */
    private final Map<String, Tool> byName;

    private ToolRegistry(Map<String, Tool> byName) {
        this.byName = Collections.unmodifiableMap(byName);
    }

    public static ToolRegistry of(Tool... tools) {
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool tool : tools) {
            if (tool == null) {
                throw new IllegalArgumentException("工具不能为 null");
            }
            String name = tool.name();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("工具名不能为空：" + tool.getClass().getName());
            }
            if (map.putIfAbsent(name.strip(), tool) != null) {
                throw new IllegalArgumentException("工具名重复：" + name);
            }
        }
        return new ToolRegistry(map);
    }

    public List<Tool> all() {
        return List.copyOf(byName.values());
    }

    /** 按名字调用。任何一步出错都返回失败结果，<b>本方法不抛</b>。 */
    public ToolResult call(String name, String argsJson) {
        String key = name == null ? "" : name.strip();
        Tool tool = byName.get(key);
        if (tool == null) {
            return ToolResult.failure("没有名为 '" + key + "' 的工具；可用的是："
                    + String.join("、", byName.keySet()));
        }
        Map<String, Object> args = new LinkedHashMap<>();
        String text = argsJson == null ? "" : argsJson.strip();
        if (!text.isEmpty()) {
            try {
                args.putAll(Json.map(text));
            } catch (RuntimeException e) {
                return ToolResult.failure(tool.name() + " 的参数不是合法 JSON：" + reason(e)
                        + "；收到的是：" + cut(text, 200));
            }
        }
        String problem = check(tool, args);
        if (problem != null) {
            return ToolResult.failure(problem);
        }
        try {
            ToolResult result = tool.run(args);
            return result == null ? ToolResult.failure(tool.name() + " 没有给出结果") : result;
        } catch (RuntimeException e) {
            return ToolResult.failure(tool.name() + " 执行失败：" + reason(e));
        }
    }

    /** 参数表本身的检查：不认识的键、缺必填的键。 */
    private static String check(Tool tool, Map<String, Object> args) {
        Map<String, Tool.Param> params = tool.params() == null ? Map.of() : tool.params();
        List<String> unknown = new ArrayList<>();
        for (String key : args.keySet()) {
            if (!params.containsKey(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            return tool.name() + " 不认识参数 " + String.join("、", unknown)
                    + (params.isEmpty() ? "；它不接受任何参数"
                    : "；它接受的参数是：" + String.join("、", params.keySet()));
        }
        for (Map.Entry<String, Tool.Param> one : params.entrySet()) {
            if (one.getValue().required() && !args.containsKey(one.getKey())) {
                return tool.name() + " 缺少必填参数 " + one.getKey()
                        + "（" + one.getValue().description() + "）";
            }
        }
        return null;
    }

    /** 给系统提示词用的那段「你能用的工具」。 */
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (Tool tool : byName.values()) {
            out.append("### ").append(tool.name()).append('\n')
                    .append(tool.description()).append('\n');
            if (tool.params().isEmpty()) {
                out.append("参数：无\n");
            } else {
                out.append("参数：\n");
                for (Map.Entry<String, Tool.Param> one : tool.params().entrySet()) {
                    Tool.Param param = one.getValue();
                    out.append("  - ").append(one.getKey()).append("（").append(param.type())
                            .append(param.required() ? "，必填" : "，可选").append("）：")
                            .append(param.description()).append('\n');
                }
            }
            out.append('\n');
        }
        return out.toString().strip();
    }

    static String reason(Throwable error) {
        if (error == null) {
            return "没有原因";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    static String cut(String text, int limit) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
