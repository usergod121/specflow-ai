package com.specflow.core;

/**
 * <b>用户写得不对</b>（配置、参数、路径）：正文就是给人看的那一句话，路由把它翻成 400。
 *
 * <p>它和「引擎自己炸了」是两回事：前者用户照着正文改一改就能过去，后者只能看堆栈。
 * 所以正文里要写清「哪一份文件、哪一行、能用的是什么」，而不是一句「参数错误」。
 */
public class SpecException extends RuntimeException {

    public SpecException(String message) {
        super(message);
    }

    public SpecException(String message, Throwable cause) {
        super(message, cause);
    }
}
