package com.specflow.project;

import com.specflow.exception.SpecflowException;
import com.specflow.util.ProjectFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 铺一个项目的基础配置。
 *
 * <p>命令行 {@code specflow init} 和界面上「打开一个还没配过的项目」走的是<b>这同一段</b>。
 * 两边各写一份的话，迟早会出现「命令行生成的配置和界面生成的配置不一样」，
 * 而那时候用户已经分不清哪个才是对的。
 *
 * <p>已存在的文件一律不覆盖。把用户写了一半的 spec 冲掉，是这类工具最不可原谅的 bug。
 */
public final class ProjectInitializer {

    /** 内置模板。放在 jar 里而不是代码里拼字符串，是为了让模板本身能被 diff、被 review。 */
    private static final List<String> BUNDLED_TEMPLATES = List.of("spring-backend.yaml", "fix-bug.yaml");

    /**
     * 配置骨架。
     *
     * <p>编译命令是<b>参数</b>而不是写死的：这套工具不该假定所有人都用 Maven。
     * 它是扫出来的（见 {@link ProjectScanner}），扫不出来就留空并写明原因。
     */
    private static final String CONFIG_TEMPLATE = """
            # Specflow 项目配置。一次配好，长期复用。
            #
            # 与 spec.yaml 的分工：spec 描述「这一次改什么」，本文件描述
            # 「这个项目怎么构建、怎么连模型」。

            build:
              # 编译校验命令。改动落盘后会执行它；返回非 0 即视为校验失败并回滚。
              # 留空则跳过编译校验（运行结果会明确标成「未校验」）。
            %s

            llm:
              # 兼容 OpenAI 协议的任意服务
              base-url: "https://api.deepseek.com"
              model: "deepseek-chat"
              # 密钥取这个名字：先找环境变量，再找 .specflow/local.env（不进版本库）
              api-key-env: "SPECFLOW_API_KEY"
              # 别在项目根另放一份 key.env / .env：那种文件这里读不到，
              # 而且很容易跟着一次 git add . 一起提交上去。
              # init 会往 .gitignore 里补 *.env，但那是兜底，不是许可。
              temperature: 0.2

            snapshot:
              # 落盘前对目标文件做快照，失败时自动回滚
              enabled: true
              dir: ".specflow/snapshots"
            """;

    private static final String SPEC_TEMPLATE = """
            version: 1

            strategy: search_replace

            prompt: |
              新建一个 Java 类 Demo，包含 main 方法，输出一行问候语。

            # 允许被改动的文件白名单。补丁块指向不在其中的文件会被直接拒绝。
            # 文件不存在就是新建，已存在就是就地修改——不用你声明。
            targets:
              - src/main/java/demo/Demo.java

            constraints:
              - 使用 Java 17 语法
              - 不要引入新的第三方依赖

            verify:
              compile: false   # 示例目标文件不在任何构建路径下，先关掉编译校验
            """;

    /**
     * 密钥文件要挡在版本库外面，见 {@link #ensureGitignore}。
     *
     * <p>只列这两个：一个是这个工具自己写密钥的地方，
     * 另一个是 {@code .gitignore} 里那条同名约定（{@code key.env} 这类也跟着它走）。
     */
    private static final List<String> SECRET_IGNORES = List.of(".specflow/local.env", "*.env");

    /** @param written 这次真正动过的文件：新建的，或者补过密钥规矩的 {@code .gitignore}，相对项目根 */
    public record Result(List<String> written) {

        public boolean wroteNothing() {
            return written.isEmpty();
        }
    }

    /**
     * 生成骨架。
     *
     * @param compileCommand 扫出来的编译校验命令；传 {@code null} 表示没认出来，
     *                       配置里会留空并写明「不填就不做校验」
     */
    public Result initialize(Path root, String compileCommand) {
        Path projectRoot = root.toAbsolutePath().normalize();
        Path configDir = projectRoot.resolve(".specflow");
        Path templatesDir = configDir.resolve("templates");

        List<String> written = new ArrayList<>();
        installReplacingBlank(configDir.resolve("project.yaml"), configSource(compileCommand),
                projectRoot, written);
        for (String name : BUNDLED_TEMPLATES) {
            install(templatesDir.resolve(name), resource(name), projectRoot, written);
        }
        install(projectRoot.resolve("spec.yaml"), SPEC_TEMPLATE, projectRoot, written);
        ensureGitignore(projectRoot, written);
        return new Result(List.copyOf(written));
    }

    /**
     * 保证这个项目的 {@code .gitignore} 里挡着密钥。
     *
     * <p>为什么不靠文档里写一句：密钥就写在项目目录里（{@code .specflow/local.env}），
     * 而 {@code .gitignore} 是<b>每个项目自己的</b>文件——刚用 init 铺骨架的项目通常
     * 还没有它，一次 {@code git add .} 就把密钥提交上去了，而这个错误没有第二次机会。
     * 项目根下那些顺手放的 {@code key.env} 也是一样，所以连 {@code *.env} 一起补。
     *
     * <p>只补缺的那几条，别的一个字都不动：那份文件可能是用户手写的，
     * 也可能是别的工具生成的，覆盖它比不补更糟。所以这里读出来再追加，
     * 而不是「写一份正确的进去」。
     *
     * <p>换行一律用 {@code \n}：{@code .gitignore} 是跟着版本库走、要跨平台的文本文件，
     * BOM 和 CRLF 都会让某条规则在别人机器上失效。
     */
    private static void ensureGitignore(Path projectRoot, List<String> written) {
        Path file = projectRoot.resolve(".gitignore");
        if (Files.isDirectory(file)) {
            throw new SpecflowException(".gitignore 是个目录，先删掉它再初始化");
        }
        String existing = Files.isRegularFile(file) ? ProjectFiles.read(file, ".gitignore") : "";
        List<String> lines = existing.lines().map(String::strip).toList();
        List<String> missing = SECRET_IGNORES.stream().filter(rule -> !lines.contains(rule)).toList();
        if (missing.isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder(existing);
        if (!existing.isEmpty()) {
            if (!existing.endsWith("\n")) {
                content.append('\n');
            }
            // 空一行：上面是用户自己写的那几段
            content.append('\n');
        }
        content.append("# 密钥不进版本库：specflow 的密钥写在 .specflow/local.env，\n")
                .append("# 项目根下的 *.env（key.env 之类）也一律不入库。\n")
                .append(String.join("\n", missing))
                .append('\n');
        ProjectFiles.writeAtomic(file, content.toString(), ".gitignore");
        written.add(".gitignore");
    }

    private static String configSource(String compileCommand) {
        String line = compileCommand == null
                ? "  # 没认出来这个项目用什么构建，先留空。\n"
                        + "  # 填上之后，改动落盘会自动跑它；不填就不做校验。\n"
                        + "  compile: \"\""
                : "  compile: " + quote(compileCommand);
        return CONFIG_TEMPLATE.formatted(line);
    }

    /** 命令可能带引号或反斜杠，用双引号包起来并转义，别让 YAML 把它读坏。 */
    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static void install(Path file, String content, Path root, List<String> written) {
        install(file, content, root, written, false);
    }

    /**
     * 同 {@link #install}，但允许重建一份<b>空壳</b>的 project.yaml。
     *
     * <p>空文件和只有注释的文件在加载时等同于「没配过」（见 {@link ProjectConfigLoader}），
     * 这里要是照样跳过，项目就会永远停在界面上那句「还没配过 specflow」。空壳里没有
     * 任何东西可丢，重建它不会伤害谁；而 spec.yaml 不适用——用户把想到的写成了注释，
     * 那也是他的东西。
     */
    private static void installReplacingBlank(Path file, String content, Path root,
                                              List<String> written) {
        install(file, content, root, written, true);
    }

    /**
     * @param replaceBlank 已存在但没有任何内容时，是否覆盖重建
     */
    private static void install(Path file, String content, Path root, List<String> written,
                                boolean replaceBlank) {
        if (Files.isDirectory(file)) {
            // 同名目录占着这个位置时，跳过它就等于每次都白跑一趟：界面上「初始化」
            // 永远成功，项目却永远配不上，用户只会一直点下去。这种坑要说出来。
            throw new SpecflowException(shown(root, file) + " 是个目录，先删掉它再初始化");
        }
        if (Files.exists(file) && !(replaceBlank && isEmptyShell(file, root))) {
            return;
        }
        ProjectFiles.writeAtomic(file, content, shown(root, file));
        written.add(shown(root, file));
    }

    private static boolean isEmptyShell(Path file, Path root) {
        return Files.isRegularFile(file)
                && ProjectConfigLoader.hasNoContent(ProjectFiles.read(file, shown(root, file)));
    }

    private static String shown(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * 读取打包在 jar 内的模板。
     *
     * <p>放到 jar 里而不是在代码里拼字符串，是为了让模板文件本身可以被 diff、被 review。
     */
    private static String resource(String name) {
        String path = "/specflow/templates/" + name;
        try (InputStream in = ProjectInitializer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new SpecflowException("内置模板缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SpecflowException("读取内置模板失败 " + path + "：" + e.getMessage(), e);
        }
    }
}
