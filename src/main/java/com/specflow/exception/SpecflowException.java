package com.specflow.exception;

/**
 * Specflow 所有业务异常的基类。
 *
 * <p>继承 {@link RuntimeException}：这类错误无法在当前层恢复，只能向上冒泡到 CLI 边界，
 * 由边界统一渲染为人可读的错误信息。业务代码因此不必到处写 try-catch。
 */
public class SpecflowException extends RuntimeException {

    public SpecflowException(String message) {
        super(message);
    }

    public SpecflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
