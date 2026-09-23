package com.specflow.spec;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.specflow.exception.SpecValidationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取并把 {@code spec.yaml} 反序列化为强类型 {@link Spec}。
 *
 * <p>只负责「能不能解析成对象」，不负责语义是否合理——后者是 {@link SpecValidator} 的职责。
 * 两层分开的好处：解析层的错误信息是「格式/字段名」级别，校验层的是「业务规则」级别，
 * 用户看到的问题不会混在一起。
 */
public final class SpecLoader {

    private static final YAMLMapper YAML = new YAMLMapper();

    /**
     * @throws SpecValidationException 文件不存在、YAML 语法错误、或含未知字段
     */
    public Spec load(Path specFile) {
        if (!Files.isRegularFile(specFile)) {
            throw new SpecValidationException(List.of("spec 文件不存在: " + specFile));
        }
        String content;
        try {
            content = Files.readString(specFile);
        } catch (IOException e) {
            throw new SpecValidationException(List.of("无法读取 spec 文件: " + specFile + "（" + e.getMessage() + "）"));
        }
        return parse(content, specFile.toString());
    }

    /**
     * 从字符串解析，便于测试与内嵌调用。
     */
    public Spec parse(String yamlContent, String sourceName) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new SpecValidationException(List.of("spec 内容为空: " + sourceName));
        }
        try {
            Spec spec = YAML.readValue(yamlContent, Spec.class);
            if (spec == null) {
                throw new SpecValidationException(List.of("spec 解析结果为 null: " + sourceName));
            }
            return spec;
        } catch (UnrecognizedPropertyException e) {
            throw new SpecValidationException(List.of(unknownFieldMessage(e, sourceName)));
        } catch (IOException e) {
            throw new SpecValidationException(List.of(
                    "YAML 解析失败: " + sourceName + System.lineSeparator() + "  " + firstLine(e.getMessage())));
        } catch (IllegalArgumentException e) {
            // PatchStrategyType.from 抛出的枚举拼写错误
            throw new SpecValidationException(List.of(e.getMessage()));
        }
    }

    private String unknownFieldMessage(UnrecognizedPropertyException e, String sourceName) {
        List<String> known = new ArrayList<>(e.getKnownPropertyIds().stream()
                .map(String::valueOf)
                .sorted()
                .toList());
        return "spec 中存在未知字段 '" + e.getPropertyName() + "'（" + sourceName + "）；可用字段: " + String.join(", ", known);
    }

    private String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int cut = message.indexOf('\n');
        return cut < 0 ? message : message.substring(0, cut);
    }
}
