package com.specflow.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>模型的唯一出口</b>。
 *
 * <p>为什么只有它：一次模型调用是这条链上最贵、最慢、最容易出错的一步
 * （网络、限流、超时、密钥）。这些事都交给 LangChain4j 那个 OpenAI 兼容客户端，
 * 我们只负责两件它管不了的：密钥从哪来、调用前后在事件流里留一笔。
 *
 * <p>密钥只从两处来：环境变量、或 {@code .specflow/local.env}。**永不回显、永不落日志**。
 */
public final class Llm {

    /** 一次调用的输入（这个 record 是用来把我们自己的代码与框架类型隔开的）。 */
    public record Msg(String role, String content) {

        public static Msg system(String content) {
            return new Msg("system", content);
        }

        public static Msg user(String content) {
            return new Msg("user", content);
        }

        public static Msg assistant(String content) {
            return new Msg("assistant", content);
        }
    }

    private final ChatModel model;
    private final String modelName;

    private Llm(ChatModel model, String modelName) {
        this.model = model;
        this.modelName = modelName;
    }

    /**
     * 从项目配置造一个。
     *
     * @throws IllegalStateException 密钥没配——**当场说清去哪配**，别等第一次调用才 401
     */
    public static Llm from(Project project) {
        Project.LlmConfig config = project.llm();
        String key = project.apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("没找到模型密钥：请在 " + project.layout().show(project.layout().localEnv())
                    + " 里写 " + config.apiKeyEnv() + "=<你的 key>，或者把它设成环境变量");
        }
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(key)
                .modelName(config.model())
                .temperature(config.temperature())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
        return new Llm(model, config.model());
    }

    public String modelName() {
        return modelName;
    }

    /** 问一次，拿原文。失败一律抛（上层翻成"模型没回来"，不许伪装成业务结论）。 */
    public String complete(List<Msg> messages) {
        List<dev.langchain4j.data.message.ChatMessage> converted = new ArrayList<>(messages.size());
        for (Msg one : messages) {
            converted.add(switch (one.role()) {
                case "system" -> SystemMessage.from(one.content());
                case "assistant" -> AiMessage.from(one.content());
                default -> UserMessage.from(one.content());
            });
        }
        String text = model.chat(converted).aiMessage().text();
        return text == null ? "" : text;
    }

    public String complete(Msg... messages) {
        return complete(List.of(messages));
    }
}
