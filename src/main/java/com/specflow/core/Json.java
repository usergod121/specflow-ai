package com.specflow.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * JSON / YAML 的唯一入口。
 *
 * <p>为什么要有这么一个类：这一版所有状态都是人能打开看的文件（JSON 与 YAML），
 * 而「用哪个 mapper、空值写不写、遇到不认识的字段炸不炸」这类决定如果散在各处，
 * 迟早出现「同一份文件两个地方读出来的形状不一样」。所以只有这里 new mapper。
 *
 * <p>三条口径：
 * <ul>
 *   <li>空值<b>不写</b>（{@code NON_NULL}）：事件里那几个只在工具调用时才有的字段，
 *       不该在每一条日志上都吐个 {@code null} 出来；</li>
 *   <li>读到不认识的字段<b>不炸</b>：老版本写的文件新版本要读得进来（反过来才该谨慎）；</li>
 *   <li>序列化失败<b>抛</b>：写不出一份坏文件比抛异常糟得多。</li>
 * </ul>
 */
public final class Json {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .build()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("序列化失败：" + e.getMessage(), e);
        }
    }

    /** 一行紧凑 JSON（事件日志一行一条用它）。 */
    public static String line(Object value) {
        return write(value);
    }

    public static <T> T read(String text, Class<T> type) {
        try {
            return JSON.readValue(text, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("读不出这一段 JSON：" + e.getMessage(), e);
        }
    }

    /** 读成一张扁平的字符串表（facts 那种形状）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(String text) {
        return read(text, Map.class);
    }

    public static <T> T readFile(Path file, Class<T> type) {
        return read(Atomic.read(file, "null"), type);
    }

    /**
     * 读一份 YAML（env.yaml / project.yaml / 各种声明）。
     *
     * <p>「<b>没有内容</b>」与「文件不在」是同一件事，都返回 {@code null}，由调用方去决定
     * 默认值：只有注释的 {@code project.yaml}（用户刚铺开、还没填）在 Jackson 那边会抛
     * {@code No content to map due to end-of-input}——那不是「配置写错了」，
     * 而用户看到的就是界面上冒一句没人看得懂的英文。
     */
    public static <T> T readYaml(Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("读不出 " + file.getFileName() + "：" + e.getMessage(), e);
        }
        if (hasNoContent(text)) {
            return null;
        }
        try {
            return YAML.readValue(text, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("读不出 " + file.getFileName() + "：" + e.getMessage(), e);
        }
    }

    /**
     * 除了空行与整行注释之外还有没有东西。
     *
     * <p>这是<b>「还没写」与「写错了」的分界线</b>，所以只有这一处定义：
     * {@code env.yaml}（{@link com.specflow.env.EnvConfigLoader}）与 {@code project.yaml}
     * （{@link Project}）都要按同一句话判——刚铺开的文件里全是注释，那是正常的起点，
     * 不该报错；而写成别的花样才是错。
     */
    public static boolean hasNoContent(String yaml) {
        if (yaml == null) {
            return true;
        }
        for (String raw : yaml.split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                return false;
            }
        }
        return true;
    }
}
