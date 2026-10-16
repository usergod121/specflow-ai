package com.specflow.agent.tools;

import com.specflow.agent.Signal;
import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;

import java.util.Map;

/**
 * {@code submit}：交差。
 *
 * <p>它不自己收场，也不改任何状态：只把一条 {@link Signal} 放进成功结果里，
 * 由循环认出信号、由上层决定接下来干什么（开发 = 跑编译，测试 = 记下这一条验完了）。
 * 工具不知道那一轮该不该收，让它自己收场就会绕过编译与验收。
 */
public final class SubmitTool implements Tool {

    @Override
    public String name() {
        return "submit";
    }

    @Override
    public String description() {
        return "声明这一轮干完了，交给引擎处理（开发=编译，测试=验收）。"
                + "调用之后不要再改文件；引擎会把结果投回来让你接着下一轮。";
    }

    @Override
    public Map<String, Param> params() {
        return Map.of("note", Param.optional("string",
                "这次做了什么、还有哪些没做（写清楚，它会进留档与总结）"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String note = Args.raw(args, "note", "");
        return ToolResult.ok(new Signal(Signal.SUBMIT, note.strip()));
    }
}
