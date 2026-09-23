package com.specflow.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.specflow.exception.SpecflowException;
import com.specflow.spec.Spec;
import com.specflow.spec.SpecLoader;
import com.specflow.spec.SpecValidator;
import com.specflow.util.SafePathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 任务草稿的读写：把「一次需求的完整组装」存下来，下次直接载入。
 *
 * <p>一份草稿<b>就是一个 spec</b>——模板名、需求字段、目标文件、上下文依赖，
 * 这些本来就已经是 {@link Spec} 的字段了。另造一个平行的数据结构只会带来
 * 「两边的校验规则不一样」这种问题，所以这里直接复用 {@link SpecLoader} 与
 * {@link SpecValidator}：命令行读 {@code spec.yaml} 走什么规则，草稿就走什么规则。
 *
 * <p>保存前一定校验通过，否则会出现「存得进去、跑不起来」的草稿。
 */
public final class TaskStore {

    public static final String DEFAULT_DIR = ".specflow/tasks";

    /** 草稿名会变成文件名，必须挡住分隔符和 {@code ..}。中文名是允许的。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\w\\u4e00-\\u9fa5-]{1,64}");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String EXTENSION = ".yaml";

    private final Path directory;
    private final Path projectRoot;
    private final SpecLoader loader = new SpecLoader();

    /**
     * @param projectRoot 项目根目录，用于校验目标文件与上下文引用的路径是否越界
     */
    public TaskStore(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.directory = this.projectRoot.resolve(DEFAULT_DIR);
    }

    public Path directory() {
        return directory;
    }

    /** 按名字排序的草稿名，不带扩展名。目录不存在时返回空列表。 */
    public List<String> names() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .map(path -> stripExtension(path.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new SpecflowException("读取任务草稿目录失败：" + e.getMessage(), e);
        }
    }

    /**
     * @throws SpecflowException 名字非法、文件不存在、或内容不合法
     */
    public Spec load(String name) {
        Path file = fileOf(name);
        if (!Files.isRegularFile(file)) {
            throw new SpecflowException("找不到任务草稿 '" + name + "'");
        }
        Spec spec = loader.load(file);
        new SpecValidator().validate(spec, new SafePathResolver(projectRoot));
        return spec;
    }

    /**
     * 保存草稿。写之前先跑一遍与运行时完全相同的校验。
     *
     * @throws SpecflowException 名字非法，或 spec 本身通不过校验
     */
    public void save(String name, Spec spec) {
        Path file = fileOf(name);
        new SpecValidator().validate(spec, new SafePathResolver(projectRoot));
        try {
            Files.createDirectories(directory);
            YAML.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), spec);
        } catch (IOException e) {
            throw new SpecflowException("保存任务草稿失败 " + name + "：" + e.getMessage(), e);
        }
    }

    /**
     * @throws SpecflowException 草稿不存在
     */
    public void delete(String name) {
        try {
            if (!Files.deleteIfExists(fileOf(name))) {
                throw new SpecflowException("找不到要删除的任务草稿 '" + name + "'");
            }
        } catch (IOException e) {
            throw new SpecflowException("删除任务草稿失败 " + name + "：" + e.getMessage(), e);
        }
    }

    // ---------- 内部 ----------

    private Path fileOf(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new SpecflowException("任务名非法: '" + name
                    + "'（只能用中英文、数字、下划线、连字符，且不能超过 64 个字符）");
        }
        return directory.resolve(name + EXTENSION).normalize();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
