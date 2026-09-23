package com.specflow.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specflow.project.LlmConfig;
import com.specflow.project.Secrets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI 兼容协议的 {@code /chat/completions} 客户端。
 *
 * <p>只用 JDK 自带的 {@link HttpClient}，不引第三方 SDK：这个协议足够简单，
 * 引一个 SDK 会带来一整套附加配置和版本约束，而收益只有几十行代码。
 *
 * <p>重试边界（这是本类唯一需要仔细设计的部分）：
 * <ul>
 *   <li><b>重试</b>：连接失败、读取超时、HTTP 429、HTTP 5xx——这些是瞬时故障</li>
 *   <li><b>不重试</b>：HTTP 4xx（除 429）——鉴权错误、参数错误、余额不足，
 *       重试只是浪费时间且掩盖真实原因，直接给出可读的失败信息</li>
 * </ul>
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final long RETRY_BASE_MILLIS = 800L;
    private static final long RETRY_CAP_MILLIS = 8_000L;

    private final HttpClient http;
    private final LlmConfig config;
    private final String apiKey;
    private final URI endpoint;

    private OpenAiCompatibleClient(HttpClient http, LlmConfig config, String apiKey) {
        this.http = http;
        this.config = config;
        this.apiKey = apiKey;
        this.endpoint = URI.create(stripTrailingSlash(config.baseUrl()) + "/chat/completions");
    }

    /**
     * 按项目配置构建客户端。
     *
     * <p>密钥从 {@link LlmConfig#apiKeyEnv()} 指定的名字取：先看环境变量，
     * 再看项目本地的 {@code .specflow/local.env}（见 {@link Secrets}）。
     *
     * @throws LlmException 两处都没有——早失败好过带着空密钥发请求
     */
    public static OpenAiCompatibleClient from(LlmConfig config, Path projectRoot) {
        String name = config.apiKeyEnv();
        String key = Secrets.lookup(projectRoot, name).orElseThrow(() -> new LlmException(
                name + " 没有配置，无法调用模型。二选一：" + System.lineSeparator()
                + "  1. 写进 " + projectRoot.resolve(Secrets.LOCAL_ENV)
                + "，一行：" + name + "=sk-..." + System.lineSeparator()
                + "  2. 或者设置环境变量 " + name));
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new OpenAiCompatibleClient(http, config, key);
    }

    @Override
    public String complete(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new LlmException("调用模型时未提供任何消息");
        }
        String body = buildRequestBody(messages);
        LlmException lastFailure = null;

        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            if (attempt > 0) {
                sleep(backoffMillis(attempt));
            }
            try {
                return invoke(body);
            } catch (RetryableFailure e) {
                lastFailure = e.failure;
            }
        }
        throw lastFailure;
    }

    // ---------- 请求 ----------

    private String buildRequestBody(List<ChatMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.model());
        root.put("temperature", config.temperature());
        root.put("stream", false);

        ArrayNode array = root.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
        }
        return root.toString();
    }

    private String invoke(String body) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw retryable(new LlmException("调用模型失败：" + e.getMessage(), e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("调用模型被中断", e);
        }

        int status = response.statusCode();
        if (status == 200) {
            return extractContent(response.body());
        }
        String detail = summarize(response.body());
        if (status == 429 || status >= 500) {
            throw retryable(new LlmException("模型服务返回 HTTP " + status + "：" + detail));
        }
        throw new LlmException("模型服务返回 HTTP " + status + "：" + detail
                + (status == 401 || status == 403 ? "（请检查 " + config.apiKeyEnv() + " 是否有效）" : ""));
    }

    private String extractContent(String responseBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new LlmException("模型响应不是合法 JSON：" + summarize(responseBody));
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new LlmException("模型响应中不含文本内容：" + summarize(responseBody));
        }
        return content.asText();
    }

    // ---------- 重试与错误渲染 ----------

    /** 用异常包装「值得重试」这一事实，避免在循环里重复判断状态码。 */
    private static final class RetryableFailure extends RuntimeException {
        private final transient LlmException failure;

        RetryableFailure(LlmException failure) {
            super(failure.getMessage());
            this.failure = failure;
        }
    }

    private static RetryableFailure retryable(LlmException failure) {
        return new RetryableFailure(failure);
    }

    private long backoffMillis(int attempt) {
        long delay = RETRY_BASE_MILLIS * attempt;
        return Math.min(delay, RETRY_CAP_MILLIS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("等待重试时被中断", e);
        }
    }

    /** 错误响应可能很长，截断后再拼进异常信息，避免刷屏。 */
    private static String summarize(String text) {
        if (text == null) {
            return "(空响应)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
