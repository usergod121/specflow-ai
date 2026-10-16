package com.specflow.test;

import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;

import java.nio.file.Path;
import java.util.Map;

/**
 * 给写类工具套一层<b>阶段冻结</b>：验收阶段不许再改被测代码（{@code src/}）。
 *
 * <p>为什么这件事必须在工具层做：测试 Agent 要是一边验一边改代码，
 * "验过了"三个字就只说明「它把代码改成能过的样子了」——那和真的修好了在报告上长得一模一样。
 * 所以冻结由 {@link Freeze} 判、在工具<b>写之前</b>拦，而不是写在提示词里。
 *
 * <p>拦的是参数里那个 {@code path}；被拒时返回一份说清原因、可以继续的失败结果
 * （模型据此换做法，而不是整段崩）。
 */
final class Frozen implements Tool {

    private final Tool inner;
    private final Freeze freeze;

    Frozen(Tool inner, Freeze freeze) {
        this.inner = inner;
        this.freeze = freeze;
    }

    @Override
    public String name() {
        return inner.name();
    }

    @Override
    public String description() {
        return inner.description() + (freeze.frozen()
                ? "（现在处于验收阶段：src/ 冻结）" : "（现在处于准备阶段）");
    }

    @Override
    public Map<String, Param> params() {
        return inner.params();
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String path = args == null ? "" : String.valueOf(args.getOrDefault("path", "")).strip();
        if (!path.isEmpty()) {
            String refusal = freeze.refuse(resolve(path));
            if (refusal != null) {
                return ToolResult.failure(refusal);
            }
        }
        return inner.run(args);
    }

    /** 参数里的相对路径 → 副本内的绝对路径（与工具自己的解析口径一致：都在副本根下）。 */
    private Path resolve(String path) {
        String one = path.replace('\\', '/').strip();
        while (one.startsWith("./")) {
            one = one.substring(2);
        }
        return freeze.root().resolve(one).normalize();
    }
}
