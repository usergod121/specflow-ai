package com.specflow.env;

import com.specflow.exception.SpecflowException;
import com.specflow.util.ProjectFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * {@code env.yaml} → {@code compose.yaml} 的那张<b>死图</b>（十五.5）。
 *
 * <p><b>为什么这一段不需要模型。</b>「用户声明了三个容器、引擎照着写出四个容器」
 * 是一次纯粹的字符串变换：输入定了，输出就该定下来。让模型来写这份文件，
 * 只会得到一个每次都不太一样、偶尔多挂一个宿主机目录的东西——而它是<b>容器配置</b>，
 * 是这套测试环境唯一的结构性边界（十五.9：高危字符串匹配挡不住变体，真正的兜底是容器隔离）。
 * 所以这张图写死在代码里，一字一句都能被测试钉住。
 *
 * <p>三条映射规则，都是定死的：
 * <ol>
 *   <li>{@code image/workdir} → 一个叫 {@code app} 的服务：把<b>项目目录</b>挂进
 *       {@code workdir}，{@code sleep infinity} 常驻，{@code env:} 原样注入。
 *       它是所有测试命令的落脚点——测试要跑在代码旁边，而代码就在这个挂载里；</li>
 *   <li>{@code dependencies.*} 原样映射成服务：镜像、环境变量、健康检查一一对应，
 *       名字就是服务名（所以容器之间用服务名互连，不需要任何端口）；</li>
 *   <li>{@code init/reset} <b>不写进来</b>。它们是「环境起来之后要做的事」，
 *       不是「容器长什么样」——写进 compose 会让它们变成容器启动命令的一部分，
 *       于是每次重启容器都会重跑一遍，而「每次跑前重置」这件事就再也不成立了。</li>
 * </ol>
 *
 * <p><b>宿主端口一律不暴露。</b>这份文件里不会出现 {@code ports}：容器之间走服务名，
 * 而把库的端口映射到宿主机上，等于给这台机器开了一个谁都能连的口子——
 * 测试用不着它，出了事却要用户自己发现。
 *
 * <p>落位在 {@code .specflow/env/<时间戳>/compose.yaml}：<b>绝不碰项目自己的 compose 文件</b>。
 * 用户的 {@code docker-compose.yml} 是他的资产（可能还跑着别的东西），
 * 而这一份是引擎的临时产物，随时可以整个删掉重来。
 */
public final class ComposeFile {

    /** 引擎生成的 compose 文件落在项目里的哪一层。 */
    public static final String ROOT = ".specflow/env";

    /** 文件名固定：它是测试脚本和清理逻辑按名字去找的那一个。 */
    public static final String NAME = "compose.yaml";

    /** 常驻容器的服务名。测试脚本要按这个名字 {@code docker compose exec} 进去。 */
    public static final String APP_SERVICE = "app";

    /** compose 项目名的前缀：{@code sf-<项目>}，容器/网络/卷都挂在这个名字下面。 */
    public static final String PROJECT_PREFIX = "sf-";

    /** 项目名最长多少字符：compose 自己也有上限，太长会被拒。 */
    private static final int MAX_PROJECT_LENGTH = 40;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ComposeFile() {
    }

    /**
     * 这份环境在 compose 里的项目名：{@code sf-<项目目录名>-<短哈希>}。
     *
     * <p>为什么按目录名而不是按 {@code env.yaml} 里的某一行：它得<b>稳定</b>——
     * 每次初始化都要落到同一个名字上，容器才会被复用（十五.5：容器常驻复用）。
     * 用一个随机名字，每跑一次就攒一批没人认领的容器。
     *
     * <p><b>为什么后面还要挂一个短哈希。</b>光靠目录名会撞：两个都叫 {@code backend} 的项目、
     * 或者几个中文目录名（归一化之后全变成 {@code project}）会算出<b>同一个</b> compose
     * 项目名。撞名之后，容器还能靠 compose 自己打的 {@code ...project.working_dir} 标签分辨，
     * 但<b>卷和网络没有那个标签</b>——清理残留时就可能 {@code docker volume rm} 掉另一个项目的
     * 数据。而「删错东西」是这条链上唯一不可接受的失败方式，所以名字本身就得是唯一的：
     * 哈希取的是项目根的绝对路径，同一个目录永远算出同一个名字，不同目录永远不会撞。
     *
     * <p>字符集是归一化出来的：目录名里的中文、空格、大写全会被替换掉（compose 只认
     * {@code [a-z0-9_-]}），所以这里不需要报错——名字只是这台机器上的一个标签。
     */
    public static String projectName(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path folder = root.getFileName();
        String raw = folder == null ? "" : folder.toString().toLowerCase(Locale.ROOT);
        StringBuilder slug = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            boolean plain = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';
            // 连续的非字母数字压成一个短横线：`my project!` → `my-project`
            if (plain) {
                slug.append(ch);
            } else if (slug.length() > 0 && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        while (slug.length() > 0 && slug.charAt(slug.length() - 1) == '-') {
            slug.setLength(slug.length() - 1);
        }
        if (slug.length() > MAX_PROJECT_LENGTH) {
            slug.setLength(MAX_PROJECT_LENGTH);
        }
        // compose 的项目名必须以字母数字开头，所以退化的目录名（`中文` 会全被丢掉）兜一个
        String body = slug.length() == 0 ? "project" : slug.toString();
        return PROJECT_PREFIX + body + "-" + fingerprint(root);
    }

    /**
     * 项目根的短指纹：绝对路径的 SHA-256 取前六位十六进制。
     *
     * <p>大小写先放平（Windows 的路径不认大小写，同一个目录可能写成两种大小写），
     * 否则同一个项目会算出两个项目名——容器复用不上，还会多攒一套。
     */
    private static String fingerprint(Path root) {
        String key = root.toString().replace('\\', '/');
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            key = key.toLowerCase(Locale.ROOT);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的：拿不到就是运行环境坏了，不是可以糊过去的事
            throw new SpecflowException("算不出项目名的指纹：" + e.getMessage(), e);
        }
    }

    /** 一次初始化独占的目录名。时间戳而不是随机串：人工去 {@code .specflow/env/} 里翻的时候看得懂。 */
    public static String newRunId() {
        return LocalDateTime.now().format(STAMP);
    }

    /** {@code .specflow/env} 在我们自己项目里的绝对路径。 */
    public static Path rootOf(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().resolve(ROOT);
    }

    /** 某一个 run 的 compose 文件路径。 */
    public static Path fileOf(Path projectRoot, String runId) {
        return rootOf(projectRoot).resolve(runId).resolve(NAME);
    }

    /**
     * 把这份声明写成一个 compose 文件。
     *
     * <p>写用原子写（同目录临时文件 + rename）：这个文件是<b>起容器的依据</b>，
     * 半截文件会让下一次 {@code up} 报出一句和真实原因无关的 YAML 语法错误。
     *
     * @return 写出来的文件路径
     */
    public static Path write(Path projectRoot, String runId, EnvConfig config) {
        Path file = fileOf(projectRoot, runId);
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new SpecflowException("建不了环境目录 " + file.getParent() + "：" + e.getMessage(), e);
        }
        ProjectFiles.writeAtomic(file, render(config, projectRoot),
                ROOT + "/" + runId + "/" + NAME);
        return file;
    }

    /**
     * 渲染正文。纯函数：同样的声明 + 同样的项目目录 → 一字不差的同一份文件，
     * 所以它能被测试逐行钉住。
     */
    public static String render(EnvConfig config, Path projectRoot) {
        StringBuilder out = new StringBuilder();
        out.append("# 这份 compose 文件是 specflow 按 .specflow/env.yaml 生成的，别手工改它：\n")
                .append("# 下一次初始化会整个覆盖。要改环境，改 env.yaml 再重新初始化。\n")
                .append("# 它不碰你项目自己的 compose 文件——那份文件跟这里没有关系。\n")
                .append("# 容器之间用服务名互连，所以这里一个宿主端口都不映射。\n")
                .append("services:\n");
        app(out, config, projectRoot);
        for (Map.Entry<String, EnvConfig.Dependency> entry : config.dependencies().entrySet()) {
            dependency(out, entry.getKey(), entry.getValue());
        }
        return out.toString();
    }

    private static void app(StringBuilder out, EnvConfig config, Path projectRoot) {
        out.append("  ").append(APP_SERVICE).append(":\n")
                .append("    image: ").append(quote(config.image())).append('\n')
                .append("    working_dir: ").append(quote(config.workdir())).append('\n')
                // 常驻：这个容器不是「跑一次就退」的，而是测试代码的落脚点。
                // 用数组形式而不是 shell 字符串——少一层 shell 解析，就没有转义可错
                .append("    command: [\"sleep\", \"infinity\"]\n")
                .append("    volumes:\n")
                // 只挂项目目录，而且是长语法：短语法用冒号分隔，Windows 的盘符
                // （E:\...）正好也带冒号，会被切错。长语法里 source 是一个独立字段，
                // 盘符再也不会被当成分隔符
                .append("      - type: bind\n")
                .append("        source: ").append(quote(host(projectRoot))).append('\n')
                .append("        target: ").append(quote(config.workdir())).append('\n');
        environment(out, "    ", config.env());
        if (!config.dependencies().isEmpty()) {
            out.append("    depends_on:\n");
            for (Map.Entry<String, EnvConfig.Dependency> entry : config.dependencies().entrySet()) {
                out.append("      ").append(entry.getKey()).append(":\n")
                        // 写了健康检查就等它真的健康：不等的话，第一次连库必然是「连不上」，
                        // 而那看起来正是被测代码的错
                        .append("        condition: ")
                        .append(entry.getValue().healthcheck() == null
                                ? "service_started" : "service_healthy")
                        .append('\n');
            }
        }
    }

    private static void dependency(StringBuilder out, String name, EnvConfig.Dependency dependency) {
        out.append("  ").append(name).append(":\n")
                .append("    image: ").append(quote(dependency.image())).append('\n');
        environment(out, "    ", dependency.env());
        EnvConfig.Healthcheck check = dependency.healthcheck();
        if (check != null) {
            out.append("    healthcheck:\n")
                    // CMD-SHELL 而不是 CMD：用户写的是「一条命令」，不是「一个可执行文件加参数」。
                    // 用 CMD 的话，`mysqladmin ping -h 127.0.0.1` 会被当成一个文件名去找
                    .append("      test: [\"CMD-SHELL\", ").append(quote(check.test())).append("]\n");
            optional(out, "      ", "interval", check.interval());
            optional(out, "      ", "timeout", check.timeout());
            optional(out, "      ", "retries", check.retries());
            optional(out, "      ", "start_period", check.startPeriod());
        }
    }

    private static void environment(StringBuilder out, String indent, Map<String, String> env) {
        if (env.isEmpty()) {
            // 一个空的环境变量块在 compose 里是合法的，但没必要写：不写更接近「什么都没声明」
            return;
        }
        out.append(indent).append("environment:\n");
        for (Map.Entry<String, String> entry : env.entrySet()) {
            out.append(indent).append("  ").append(quote(entry.getKey()))
                    .append(": ").append(quote(entry.getValue())).append('\n');
        }
    }

    private static void optional(StringBuilder out, String indent, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.append(indent).append(key).append(": ").append(quote(value)).append('\n');
        }
    }

    /**
     * 项目目录在宿主机上的写法。
     *
     * <p>反斜杠换成正斜杠：compose 的值里反斜杠是转义字符，{@code E:\a\b} 会被读成
     * {@code E:ab}——挂载点上少两层的错，用户从报错里看不出来。
     */
    private static String host(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /**
     * YAML 里的一个字符串。
     *
     * <p>一律加引号，不做「看起来不用引号」的判断：{@code 8.0}、{@code on}、{@code no}
     * 这些值不加引号会被 YAML 读成数字和布尔，而它们在这里全都是字符串。
     * 判断哪些要引哪些不要，是一个永远会漏的规则。
     */
    private static String quote(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
