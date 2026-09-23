package com.specflow.llm;

import com.specflow.exception.SpecflowException;

/**
 * 模型调用失败。
 *
 * <p>独立成一个类型，是为了让上层能区分「模型/网络出问题」与
 * 「模型给了答复但答复不合法」（后者是 {@code PatchConflictException}）。
 * 两者的处置方式不同：前者可以原样重试，后者必须把错误原因喂回给模型。
 */
public class LlmException extends SpecflowException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
