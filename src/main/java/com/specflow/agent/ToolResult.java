package com.specflow.agent;

import com.specflow.core.Json;

/**
 * 一次工具调用的结果：只有「成功带数据」与「失败带原因」两种形态，<b>不抛异常</b>。
 *
 * <p>名字不认、参数不合法、工具内部炸了，都在 {@link ToolRegistry#call} 里翻成 {@code ok=false}——
 * 循环于是只有两条路可走：接着干，或者把原因喂回模型让它自己改。
 * 加第三种形态（部分成功、警告）就得为它再写一条没人知道怎么收场的分支。
 */
public record ToolResult(boolean ok, Object data, String reason) {

    public ToolResult {
        if (!ok && (reason == null || reason.isBlank())) {
            reason = "工具失败了，但没说原因";
        }
    }

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult failure(String reason) {
        return new ToolResult(false, null, reason);
    }

    /** 注入下一轮的那一句（紧凑 JSON）。序列化失败也不许把循环掀掉，退回手写转义的一行。 */
    public String json() {
        try {
            return Json.line(this);
        } catch (RuntimeException e) {
            return "{\"ok\":false,\"reason\":\"" + escape("工具结果序列化不出来：" + e.getMessage()) + "\"}";
        }
    }

    private static String escape(String text) {
        return String.valueOf(text)
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
