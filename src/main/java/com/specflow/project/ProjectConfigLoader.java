package com.specflow.project;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.specflow.exception.SpecValidationException;
import com.specflow.util.SafePathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 读取 {@code .specflow/project.yaml}。
 *
 * <p>与 spec 不同，项目配置缺失是正常的——此时回落到全默认值，
 * 让工具在「零配置」下也能跑起来。
 */
public final class ProjectConfigLoader {

    public static final String CONFIG_DIR = ".specflow";
    public static final String CONFIG_FILE = "project.yaml";

    private static final YAMLMapper YAML = new YAMLMapper();

    /**
     * 加载项目配置；文件不存在时返回 {@link ProjectConfig#DEFAULT}。
     */
    public ProjectConfig load(Path projectRoot) {
        Path configFile = projectRoot.resolve(CONFIG_DIR).resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configFile)) {
            return ProjectConfig.DEFAULT;
        }
        try {
            String source = Files.readString(configFile);
            // 空文件（手建的、编辑器存空的、写入被打断的）和「没配过」是同一件事。
            // 报错的话项目就永远打不开，而修它偏偏要先打开这个项目——用户被锁在门外。
            if (hasNoContent(source)) {
                return ProjectConfig.DEFAULT;
            }
            ProjectConfig config = YAML.readValue(source, ProjectConfig.class);
            ProjectConfig resolved = config == null ? ProjectConfig.DEFAULT : config;
            validatePaths(resolved, projectRoot);
            return resolved;
        } catch (UnrecognizedPropertyException e) {
            throw new SpecValidationException(List.of(
                    "project.yaml 中存在未知字段 '" + e.getPropertyName() + "'（" + configFile + "）"));
        } catch (IOException e) {
            throw new SpecValidationException(List.of(
                    "project.yaml 解析失败: " + configFile + System.lineSeparator() + "  " + e.getMessage()));
        }
    }

    /**
     * 这份配置算不算「没内容」。
     *
     * <p>判据是「这一堆字里有没有一个字符是配置内容」，而不是「文件大不大」：
     * 空行、注释、{@code ---} / {@code ...} 这类文档标记、{@code %YAML} 指令、
     * 以及 {@code null} / {@code ~} / {@code ""} 这种空标量，全都不算内容。
     * 这几样在 YAML 里的效果是一样的——<b>解析出来什么都没有</b>，所以它们必须同进同出。
     *
     * <p>看不见的空白也算空白：中文输入法下「清空文件」很容易留下一个全角空格，
     * 而 {@code String.trim()} 只认 ≤U+0020 的字符，全角空格、NBSP、零宽空格都会漏过去——
     * 那样一份「看着是空的」文件会被当成坏配置，项目再也打不开。
     *
     * <p>{@link ProjectInitializer} 与 {@link ProjectScanner} 都用这个判断：
     * 三边口径必须一致，否则会出现「加载说没配过、初始化说有文件」的死角，
     * 用户点多少次初始化都出不来。
     */
    static boolean hasNoContent(String source) {
        String body = source.startsWith("\uFEFF") ? source.substring(1) : source;
        return body.lines().allMatch(ProjectConfigLoader::isNotContent);
    }

    /** 这一行算不算配置内容；见 {@link #hasNoContent} 里列的那几类。 */
    private static boolean isNotContent(String line) {
        String text = line.replace("\u00A0", "")
                .replace("\u200B", "")
                .replace("\u3000", "")
                .strip();
        if (text.isEmpty() || text.startsWith("#")) {
            return true;
        }
        return text.equals("---") || text.equals("...") || text.startsWith("%YAML")
                || text.equals("null") || text.equals("~")
                || text.equals("\"\"") || text.equals("''");
    }

    /**
     * 配置里的相对路径同样会变成磁盘写入位置，因此必须在加载时就挡掉越界的写法——
     * 否则错误要到真正跑任务、快照已经建了一半时才暴露。
     */
    private void validatePaths(ProjectConfig config, Path projectRoot) {
        try {
            new SafePathResolver(projectRoot).resolve(config.snapshot().dir());
        } catch (IllegalArgumentException e) {
            throw new SpecValidationException(List.of("project.yaml 中 snapshot.dir 非法: " + e.getMessage()));
        }
    }
}
