package com.specflow.agent;

/**
 * 「交给上层处理」的信号：工具不自己收场，它只把信号放进成功的结果里。
 *
 * <p>为什么不让 submit 工具直接结束循环：工具不知道那一轮该不该收（编译还没跑、用例还没写完）。
 * 循环认出信号、上层决定怎么收场，分工只有这一处。
 *
 * @param kind 信号类型（{@link #SUBMIT}）
 * @param note 给上层的一句话
 */
public record Signal(String kind, String note) {

    public static final String SUBMIT = "submit";

    public Signal(String kind) {
        this(kind, "");
    }

    public Signal {
        kind = kind == null || kind.isBlank() ? SUBMIT : kind;
        note = note == null ? "" : note;
    }
}
