package com.specflow.patch;

import com.specflow.spec.PatchStrategyType;
import com.specflow.spec.Spec;
import com.specflow.util.SafePathResolver;

import java.util.List;

/**
 * 补丁合并策略。
 *
 * <p>这是引擎最重要的扩展点：新增一种落盘方式（全量重写、AST 合并、结构化插入……）
 * 只需实现本接口并在 {@link PatchStrategies} 中登记，无需改动 Agent 编排、CLI 或校验层。
 *
 * <p>实现必须遵守的契约：
 * <ol>
 *   <li><b>纯校验</b>——本方法不得写入任何文件。落盘由
 *       {@link PatchApplier} 在计划构造成功之后统一执行。</li>
 *   <li><b>失败即异常</b>——遇到无法安全处理的情况抛
 *       {@link com.specflow.exception.PatchConflictException}，并给出可操作的细节。</li>
 *   <li><b>无副作用</b>——同样的输入必须产出同样的计划。</li>
 * </ol>
 */
public interface PatchStrategy {

    PatchStrategyType type();

    /**
     * 校验补丁块并生成改动计划。
     *
     * @param blocks       已解析的补丁块
     * @param spec         契约（提供 mode 与 targets 白名单）
     * @param pathResolver 项目路径守卫，负责拒绝越界路径
     * @return 已通过校验的改动计划
     */
    PatchPlan plan(List<PatchBlock> blocks, Spec spec, SafePathResolver pathResolver);
}
