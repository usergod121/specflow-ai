package com.specflow.web;

import com.specflow.core.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把异常翻成 <b>{@code {"error": "一句人话"}}</b>——这一版对界面的承诺。
 *
 * <p>三条口径：
 * <ol>
 *   <li><b>原话照给</b>：{@link ApiException} 的正文原样出去（它本来就是写给人看的）；</li>
 *   <li><b>别的异常也给原话</b>：{@code getMessage()} 有内容就用它，没内容才回一句兜底的；
 *       绝不回堆栈，也绝不回一句"操作失败"；</li>
 *   <li><b>引擎的锅记日志</b>：500 那一档同时打一条 error 日志（带堆栈），
 *       因为界面上只能显示一句话，而排查需要堆栈。</li>
 * </ol>
 */
@RestControllerAdvice
public class Errors {

    private static final Logger log = LoggerFactory.getLogger(Errors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Json.write(Map.of("error", text(e.getMessage()))));
    }

    /**
     * 闸拒掉一个动作：用<b>它自己带的那个状态码</b>（通常是 409）。
     *
     * <p>为什么单独一条：{@code FlowRejected} 是闸专用的异常，"现在不能这么做"与"引擎炸了"
     * 是两件事——不单独接住的话它会被下面那个兜底分支翻成 500，
     * 而界面上就会把"还没到那一步"显示成"服务出错了"（端到端实测踩到）。
     */
    @ExceptionHandler(com.specflow.flow.FlowRejected.class)
    public ResponseEntity<String> flow(com.specflow.flow.FlowRejected e) {
        return ResponseEntity.status(e.status()).body(Json.write(Map.of("error", text(e.getMessage()))));
    }

    /** 参数不合法（Spring 的绑定错误也归这一档）：400 + 原话。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<String> bad(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Json.write(Map.of("error", text(e.getMessage()))));
    }

    /**
     * 静态资源找不到（浏览器要 favicon.ico 那种）：<b>404，而且不记 ERROR</b>。
     *
     * <p>为什么单独一条：它由静态资源处理器抛出，落到下面那个兜底分支时会被翻成 500 +
     * 一条 ERROR 日志——于是一个没人关心的 favicon 把日志刷成一片红，真正要查的那几行反而不见了
     * （实测：七页每页一条 "No static resource favicon.ico"）。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<String> missing(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Json.write(Map.of("error", "没有这个资源")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> any(Exception e) {
        log.error("接口炸了：{}", e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Json.write(Map.of("error", text(e.getMessage()))));
    }

    private static String text(String message) {
        return message == null || message.isBlank() ? "引擎自己出了点问题（日志里有堆栈）" : message;
    }
}
