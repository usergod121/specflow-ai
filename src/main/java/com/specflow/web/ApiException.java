package com.specflow.web;

/**
 * <b>路由层唯一认识的异常</b>：一句话 + 一个 HTTP 状态码。
 *
 * <p>为什么要有它：这个服务的错误必须带着<b>原文</b>到界面上（"哪里没成、下一步怎么办"），
 * 而 Spring 默认那套错误响应（`{"timestamp":…,"path":…,"status":500}`）把原话丢了。
 * 所以业务层只管抛，`Errors` 把它翻成 `{"error": "那句人话"}`。
 *
 * <p>状态码只用三个，含义写死：
 * <ul>
 *   <li><b>409</b> —— 现在不能这么做（闸没过、还有东西在跑、没有可回滚的快照）；</li>
 *   <li><b>400</b> —— 你发上来的东西不对（缺字段、路径不存在）；</li>
 *   <li><b>500</b> —— 引擎自己炸了（这时正文仍然是那句话，不是堆栈）。</li>
 * </ul>
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** 409：现在不能这么做。 */
    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    /** 400：请求本身不合法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }
}
