package com.specflow.exception;

/**
 * 安全闸拦下了一条命令：<b>产物一个字节都不落盘、也不会被执行</b>。
 *
 * <p>为什么要专门有这么一个类型，而不是继续用一句 {@link SpecflowException} 带文字：
 * 「这批测试产物不能用」有两种完全不同的原因，而它们的收场方式不一样：
 * <ul>
 *   <li><b>安全拦截</b>（本类）——它写的东西里有 {@code rm -rf}、提权、挂宿主目录这类命令。
 *       人要看的是「引擎拦了它、为什么拦」，这属于一次<b>正常的测试失败</b>（见
 *       {@code TestOutcome.Failure.Kind#BLOCKED_COMMAND}），该和断言失败并排出现在失败清单里，
 *       并且值得<b>把拒绝原因喂回去再生成一版</b>——实测过它连着三版都写同一句
 *       {@code rm -rf "$OUT_DIR"}，一版就停等于连着白跑三轮；</li>
 *   <li><b>协议不符 / 路径越界</b>（仍是普通的 {@code SpecflowException}）——它没按补丁协议写、
 *       或者想把文件写到产物目录外面去。那种错重掷骰子还是同样的错，所以不重试。</li>
 * </ul>
 *
 * <p>靠字符串去认「刚才那句是不是安全拦截的」是条迟早会断的路（改一个字就认不出来），
 * 所以这里用一个类型把判据说清楚：<b>谁抛的一看就知道，谁接的也一看就知道</b>。
 */
public class BlockedCommandException extends SpecflowException {

    public BlockedCommandException(String message) {
        super(message);
    }
}
