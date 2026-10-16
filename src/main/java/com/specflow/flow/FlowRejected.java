package com.specflow.flow;

/**
 * 闸没过 / 现在不能这么做 → 路由翻成 <b>409</b>（`docs/API.md` §0）。
 *
 * <p>为什么用异常而不是返回 {@code Optional}：闸的调用点有几十处（每个动作、每条路由），
 * 返回值要求每一处都记得判——漏一处就是一条没有闸的路。异常是<b>默认拒绝</b>的：
 * 忘了接住它，请求是 500，而不是悄悄放行。
 *
 * <p>{@code getMessage()} 恒为那句给用户看的人话（路由原样放进 {@code {"error": …}}）。
 * 上一版这里叫 {@code NodeRejected}，语义一样，名字跟着这一版叫 {@code FlowRejected}。
 */
public final class FlowRejected extends RuntimeException {

    private final int status;

    public FlowRejected(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP 状态码（现在只有 409 一种：现在不能这么做）。 */
    public int status() {
        return status;
    }

    /** 409：闸没过、或者还有东西在跑。 */
    public static FlowRejected conflict(String message) {
        return new FlowRejected(409, message);
    }
}
