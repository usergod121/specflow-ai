package com.specflow.verify;

/**
 * 校验器扩展点。
 *
 * <p>当前只有编译校验一个实现。之所以先立接口，是因为「测试 Agent」
 * 已经在规划里：它要做的事情——按需求派生用例、执行、判定——与编译校验
 * 在流程上的位置完全一致，都是「写入之后、收尾之前的一段可失败检查」。
 * 把这层抽象先摆好，将来加测试校验不必改 Agent 的编排。
 *
 * <p>实现约定：<b>不抛异常表示校验逻辑本身没崩</b>，而校验是否通过由
 * {@link VerificationResult#status()} 表达。构建命令失败是正常结果，不是异常。
 */
public interface Verifier {

    /** 校验器名称，用于日志与错误信息。 */
    String name();

    /**
     * 执行校验。
     *
     * <p>实现必须是幂等的、可重复调用的——Agent 在重试循环里会调用多次。
     */
    VerificationResult verify(VerificationContext context);
}
