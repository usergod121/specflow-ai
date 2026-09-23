package com.specflow.project;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型接入配置。
 *
 * <p>刻意不提供 apiKey 字段——密钥进了配置文件就会跟着进版本库。
 * {@code apiKeyEnv} 只声明密钥<b>叫什么名字</b>，值从哪儿取见 {@link Secrets}。
 *
 * <p>取值规范放在<b>规范构造器</b>里，而不是只放在 {@link #of} 里：
 * 这样即便是直接 {@code new} 出来的实例，也不会带着 {@code maxRetries = -1}
 * 或空 baseUrl 进入运行期。
 *
 * @param baseUrl        服务地址（不含 {@code /chat/completions} 后缀）
 * @param model          模型名
 * @param apiKeyEnv      密钥的名字：先按它找环境变量，再找 {@code .specflow/local.env}
 * @param timeoutSeconds 单次请求超时
 * @param temperature    采样温度；代码生成建议取低值
 * @param maxRetries     网络层重试次数（与编译重试无关）
 */
public record LlmConfig(
        String baseUrl,
        String model,
        String apiKeyEnv,
        int timeoutSeconds,
        double temperature,
        int maxRetries
) {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-chat";
    public static final String DEFAULT_API_KEY_ENV = "SPECFLOW_API_KEY";
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    public static final double DEFAULT_TEMPERATURE = 0.2;
    public static final int DEFAULT_MAX_RETRIES = 2;

    public static final LlmConfig DEFAULT = new LlmConfig(DEFAULT_BASE_URL, DEFAULT_MODEL,
            DEFAULT_API_KEY_ENV, DEFAULT_TIMEOUT_SECONDS, DEFAULT_TEMPERATURE, DEFAULT_MAX_RETRIES);

    public LlmConfig {
        baseUrl = orDefault(baseUrl, DEFAULT_BASE_URL);
        model = orDefault(model, DEFAULT_MODEL);
        apiKeyEnv = orDefault(apiKeyEnv, DEFAULT_API_KEY_ENV);
        timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        maxRetries = Math.max(0, maxRetries);
    }

    @JsonCreator
    public static LlmConfig of(
            @JsonProperty("base-url") @JsonAlias({"base_url", "baseUrl"}) String baseUrl,
            @JsonProperty("model") String model,
            @JsonProperty("api-key-env") @JsonAlias({"api_key_env", "apiKeyEnv"}) String apiKeyEnv,
            @JsonProperty("timeout-seconds") @JsonAlias({"timeout_seconds", "timeoutSeconds"}) Integer timeoutSeconds,
            @JsonProperty("temperature") Double temperature,
            @JsonProperty("max-retries") @JsonAlias({"max_retries", "maxRetries"}) Integer maxRetries
    ) {
        return new LlmConfig(
                baseUrl,
                model,
                apiKeyEnv,
                timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds,
                temperature == null ? DEFAULT_TEMPERATURE : temperature,
                maxRetries == null ? DEFAULT_MAX_RETRIES : maxRetries
        );
    }

    /**
     * 缺少配置时回落到全局默认值。
     */
    public static LlmConfig merge(LlmConfig override) {
        return override == null ? DEFAULT : override;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
