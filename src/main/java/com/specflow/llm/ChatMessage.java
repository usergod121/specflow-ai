package com.specflow.llm;

import java.util.Objects;

/**
 * 一条对话消息。
 *
 * @param role    {@code system} / {@code user} / {@code assistant}
 * @param content 消息正文
 */
public record ChatMessage(String role, String content) {

    public static final String SYSTEM = "system";
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ASSISTANT, content);
    }
}
