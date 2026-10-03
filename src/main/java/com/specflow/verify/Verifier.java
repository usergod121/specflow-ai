package com.specflow.verify;

/**
 * 校验器扩展点。
 *
 * <p>挂进引擎那份校验器列表的只有编译校验一个（见 {@code RunCommand.verifiers()} /
 * {@code RunService}）。立这个接口时的打算是让「测试 Agent」也实现它
 * （两者在流程上的位置确实一样，都是「写入之后、收尾之前的一段可失败检查」），
 * <b>后来没有这么做</b>：测试 Agent 要的东西这个接口一样都装不下——
 * 冻结的用例清单、这次勾没勾集成、测试环境那组变量、脚本在容器里还是宿主上跑、
 * 本轮改了哪些文件（第二段补「怎么测」只认它），以及它产出之后要进的全轮成本账、
 * 失败清单、回喂、处置——都不是一个 {@link VerificationResult} 说得完的。
 * 所以它落在 {@code DevelopmentAgent.testPhase}，接口留给「和编译同形」的检查。
 *
 * <p>这条记录留着，是因为它正好是「先摆好的抽象后来没被用上」那一类：
 * 上面那段预判一共写在三处——本接口这段注释、{@code docs/ARCHITECTURE.md} 的「扩展点」一节、
 * {@code BuildConfig} 的类注释，而三处都过时了（2026-10-03 一并改准）。
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
