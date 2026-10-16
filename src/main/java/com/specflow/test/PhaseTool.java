package com.specflow.test;

import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code phase}：切换「准备 / 验收」两个阶段。
 *
 * <p>为什么要有它，而不是让引擎按轮次自动切：<b>什么时候"准备好了"只有模型知道</b>
 * （依赖装完了、脚本写好了、项目跑起来了）。引擎猜一个轮次切过去，会在它还没准备好的时候
 * 冻结 {@code src/}，于是它连"把项目弄成能跑的样子"都做不了。
 *
 * <p>反向（验收 → 准备）<b>不许</b>：冻了又解，等于那条冻结从一开始就不存在。
 */
public final class PhaseTool implements Tool {

    private final Freeze freeze;

    public PhaseTool(Freeze freeze) {
        this.freeze = freeze;
    }

    @Override
    public String name() {
        return "phase";
    }

    @Override
    public String description() {
        return "切换阶段：`acceptance` = 开始逐条验收（此后 src/ 冻结，不许再改被测代码）；"
                + "`prepare` = 回到准备（只在没切过的时候有意义）。";
    }

    @Override
    public Map<String, Param> params() {
        Map<String, Param> params = new LinkedHashMap<>();
        params.put("name", Param.required("string", "prepare 或 acceptance"));
        return params;
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String wanted = String.valueOf(args.getOrDefault("name", "")).strip().toLowerCase(java.util.Locale.ROOT);
        Freeze.Phase next = switch (wanted) {
            case "acceptance", "accept", "验收" -> Freeze.Phase.ACCEPTANCE;
            case "prepare", "准备" -> Freeze.Phase.PREPARE;
            default -> null;
        };
        if (next == null) {
            return ToolResult.failure("name 只能是 prepare 或 acceptance，收到：" + wanted);
        }
        if (next == Freeze.Phase.PREPARE && freeze.frozen()) {
            return ToolResult.failure("已经进验收阶段了，不能回退到准备："
                    + "冻了又解等于那条冻结从一开始就不存在。要改代码就回退到 ④，改完重新跑测试。");
        }
        freeze.to(next);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", next.name());
        return ToolResult.ok(data);
    }
}
