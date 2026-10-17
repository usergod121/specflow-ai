package com.specflow.env;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code env.yaml → compose.yaml} 的那张<b>死图</b>：输入定了，输出就该定下来，所以它能被测试逐字钉住。
 *
 * <p>三条映射规则：
 * <ol>
 *   <li>{@code image / workdir / env} → 一个叫 {@code app} 的服务：把<b>项目目录</b>挂进
 *       {@code workdir}、{@code sleep infinity} 常驻，命令都在它里面跑；</li>
 *   <li>{@code dependencies.*} → 一个依赖一个服务，镜像 / 环境变量 / 健康检查一一对应。
 *       名字就是服务名，所以容器之间靠服务名互连，<b>一个宿主端口都不映射</b>；</li>
 *   <li>{@code init / reset} <b>不写进来</b>。它们是「环境起来之后要做的事」，不是「容器长什么样」
 *       ——写进 compose 会变成容器启动命令的一部分，每次重启重跑一遍，而「每次跑前重置」就不再成立。</li>
 * </ol>
 *
 * <p><b>加固只加在 app 上</b>：app 是引擎与测试命令的落脚点，非 root（{@code 1000:1000}）+
 * {@code cap_drop ALL} + {@code no-new-privileges} + 进程数/内存/CPU 限额。
 * 依赖容器只带 {@code no-new-privileges} 与限额，<b>不强制非 root、也不 cap_drop</b>：
 * 中间件镜像的入口脚本要用 root 去 chown 自己的数据目录（mysql / postgres 都是这样），
 * 把 app 那套照抄上去只会让依赖起不来——而那个失败看起来正是「代码连不上库」。
 */
public final class Compose {

    /** 常驻容器的服务名：{@code docker compose exec -T app sh -s} 用的就是它。 */
    public static final String APP_SERVICE = "app";

    /** 容器里用哪个用户跑：非 root 的惯例 id。写死在渲染里——加固是边界，不是用户偏好。 */
    public static final String SANDBOX_USER = "1000:1000";

    /**
     * app 的家目录（引擎定的，不跟镜像走）：构建工具的依赖缓存都在它下面。
     *
     * <p>为什么不定成镜像里那个 {@code /home/ubuntu}：那是"恰好 eclipse-temurin 的 uid 1000 叫 ubuntu"
     * 的结果，换个镜像（比如 {@code maven:3.9-eclipse-temurin-17}）就未必成立；
     * 缓存路径必须是我们说了算，否则同一个项目会攒出两份缓存。
     */
    public static final String HOME_IN_CONTAINER = "/specflow-home";

    /** 依赖缓存卷的后缀（{@code <compose 项目名>-home}）。 */
    public static final String VOLUME_SUFFIX = "-home";

    private static final int APP_PIDS = 1024;
    private static final String APP_MEMORY = "2g";
    private static final String APP_CPUS = "2.0";
    private static final int DEPENDENCY_PIDS = 512;
    private static final String DEPENDENCY_MEMORY = "1g";
    private static final String DEPENDENCY_CPUS = "1.0";
    private static final String NO_NEW_PRIVILEGES = "no-new-privileges:true";

    /** compose 项目名最长多少：太长会被它自己拒掉。 */
    private static final int MAX_NAME = 40;

    private Compose() {
    }

    /**
     * 渲染 compose 全文。
     *
     * <p>文件头里不出现 init / reset 这两个词（那是「这里没有它们」的最好证明——
     * 用一句话去解释反而会把它们写进来，读的人分不清是注释还是配置）。
     *
     * @param projectRoot 挂进 app 容器的那棵树
     * @param projectName {@link #projectName} 算出来的项目名（容器、卷、网络都挂在它下面）
     */
    public static String render(EnvConfig config, Path projectRoot, String projectName) {
        StringBuilder out = new StringBuilder();
        out.append("# 这份 compose 是 specflow 按 .specflow/env.yaml 渲染出来的，别手工改：\n")
                .append("# 下一次「起环境」会整个覆盖它。要改环境，改 env.yaml 再起一次。\n")
                .append("#\n")
                .append("# 它不是你的 compose 文件：引擎的产物落在 .specflow/flow/<流程>/env/<时间戳>/，\n")
                .append("# 你自己的 docker-compose.yml 一个字节都不会动。\n")
                .append("#\n")
                .append("# 环境准备那几条命令（env.yaml 里那两个列表）**不在这份文件里**：\n")
                .append("# 它们不是「容器长什么样」，而是「起来之后要做什么」——写进来会被当成\n").append("# 容器启动命令，每次重启都重跑一遍。\n")
                .append("#\n")
                .append("# 加固：app 跑非 root（").append(SANDBOX_USER).append("）、cap_drop ALL、\n")
                .append("# no-new-privileges，并带进程数 / 内存 / CPU 限额。依赖容器带 no-new-privileges\n")
                .append("# 与限额，但**不强制非 root**：中间件镜像的入口要用 root 去准备自己的数据目录。\n")
                .append("#\n")
                .append("# 宿主端口一个都不映射：容器之间用服务名互连，映射出去等于白开一个口子。\n")
                .append("name: ").append(quote(projectName)).append('\n')
                .append("services:\n");
        app(out, config, projectRoot, projectName);
        for (Map.Entry<String, EnvConfig.Dependency> entry : config.dependencies().entrySet()) {
            dependency(out, entry.getKey(), entry.getValue());
        }
        // 依赖缓存卷声明成 external：它**不属于这次 up/down 的生命周期**。
        // 不这么写的话 `down -v` 会把缓存一起删掉——那等于每轮都从零下依赖（实测烧掉 17 分钟）。
        // 「数据每轮重置、依赖跨轮复用」就是靠这一条分出来的。
        out.append("volumes:\n")
                .append("  ").append(quote(homeVolume(projectName))).append(":\n")
                .append("    external: true\n")
                .append("    name: ").append(quote(homeVolume(projectName))).append('\n');
        return out.toString();
    }

    /**
     * 项目名：{@code sf-<目录名>-<路径指纹>}。
     *
     * <p>为什么不是「目录名」就完事：它得<b>稳定</b>（每次起环境落到同一个名字上，容器才复用得上），
     * 又得<b>唯一</b>——两个都叫 {@code backend} 的项目会算出同一个名字，而卷和网络没有
     * 「哪个目录在用它」这种标签，清理残留时就可能删掉另一个项目的数据。指纹取项目根的绝对路径，
     * 所以同一个目录永远同一个名字、不同目录永远不撞。
     */
    public static String projectName(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        String folder = root.getFileName() == null ? "" : root.getFileName().toString();
        StringBuilder slug = new StringBuilder();
        for (char ch : folder.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                slug.append(ch);
                // 连续的非字母数字压成一个短横线：`my project!` → `my-project`
            } else if (slug.length() > 0 && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        while (slug.length() > 0 && slug.charAt(slug.length() - 1) == '-') {
            slug.setLength(slug.length() - 1);
        }
        if (slug.length() > MAX_NAME) {
            slug.setLength(MAX_NAME);
        }
        // 全中文的目录名会被清空，而 compose 的项目名必须以字母数字开头
        String body = slug.length() == 0 ? "project" : slug.toString();
        return "sf-" + body + "-" + fingerprint(root);
    }

    /** 项目根的短指纹（绝对路径的 SHA-256 前六位）。大小写先放平：Windows 上同一个目录会写成两种大小写。 */
    private static String fingerprint(Path root) {
        String key = root.toString().replace('\\', '/');
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            key = key.toLowerCase(Locale.ROOT);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x%02x", digest[0], digest[1], digest[2]);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("这个 JDK 没有 SHA-256，算不出项目名：" + e.getMessage(), e);
        }
    }

    private static void app(StringBuilder out, EnvConfig config, Path projectRoot, String projectName) {
        out.append("  ").append(APP_SERVICE).append(":\n")
                .append("    image: ").append(quote(config.image())).append('\n')
                .append("    working_dir: ").append(quote(config.workdir())).append('\n')
                // 用数组形式而不是 shell 字符串：少一层 shell 解析，就没有转义可错
                .append("    command: [\"sleep\", \"infinity\"]\n")
                .append("    user: ").append(quote(SANDBOX_USER)).append('\n')
                .append("    cap_drop: [\"ALL\"]\n")
                .append("    security_opt: [").append(quote(NO_NEW_PRIVILEGES)).append("]\n")
                .append("    pids_limit: ").append(APP_PIDS).append('\n')
                .append("    mem_limit: ").append(quote(APP_MEMORY)).append('\n')
                .append("    cpus: ").append(quote(APP_CPUS)).append('\n')
                .append("    volumes:\n")
                // 长语法而不是 `- E:/x:/work`：短语法用冒号分隔，而 Windows 的盘符正是冒号
                .append("      - type: bind\n")
                .append("        source: ").append(quote(hostPath(projectRoot))).append('\n')
                .append("        target: ").append(quote(config.workdir())).append('\n')
                // 构建工具的依赖缓存（Maven 的 ~/.m2、Gradle 的 ~/.gradle、npm 的 ~/.npm 都在它下面）。
                // 为什么必须有它：项目树每轮都是新副本，镜像里又没有工具链时，
                // agent 要么自己装（实测烧掉 20 轮）、要么每轮重下依赖（实测 7 轮 sleep / 17 分钟）。
                // **缓存在卷里跨轮保留，数据仍然每轮重置**——CI 都是这么分的。
                .append("      - type: volume\n")
                .append("        source: ").append(quote(homeVolume(projectName))).append('\n')
                .append("        target: ").append(quote(HOME_IN_CONTAINER)).append('\n');
        // HOME 放最前，且**用户声明的 HOME 被忽略**：缓存全在它下面，
        // 让它跟着"镜像里那个 uid 1000 恰好叫什么"走，缓存就会散在两个地方。
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", HOME_IN_CONTAINER);
        config.env().forEach(env::putIfAbsent);
        environment(out, "    ", env);
        if (!config.dependencies().isEmpty()) {
            out.append("    depends_on:\n");
            for (Map.Entry<String, EnvConfig.Dependency> entry : config.dependencies().entrySet()) {
                out.append("      ").append(entry.getKey()).append(":\n")
                        // 写了健康检查就等它真的健康：不等的话第一次连库必然「连不上」
                        .append("        condition: ")
                        .append(entry.getValue().healthcheck() == null
                                ? "service_started" : "service_healthy")
                        .append('\n');
            }
        }
    }

    /** app 的依赖缓存卷名：跟 compose 项目名走，清理时按同一个名字找。 */
    public static String homeVolume(String projectName) {
        return projectName + VOLUME_SUFFIX;
    }

    private static void dependency(StringBuilder out, String name, EnvConfig.Dependency dependency) {
        out.append("  ").append(name).append(":\n")
                .append("    image: ").append(quote(dependency.image())).append('\n')
                .append("    security_opt: [").append(quote(NO_NEW_PRIVILEGES)).append("]\n")
                .append("    pids_limit: ").append(DEPENDENCY_PIDS).append('\n')
                .append("    mem_limit: ").append(quote(DEPENDENCY_MEMORY)).append('\n')
                .append("    cpus: ").append(quote(DEPENDENCY_CPUS)).append('\n');
        environment(out, "    ", dependency.env());
        String check = dependency.healthcheck();
        if (check != null) {
            out.append("    healthcheck:\n")
                    // CMD-SHELL 而不是 CMD：用户写的是一条命令，不是「可执行文件 + 参数」——
                    // 用 CMD 的话 `mysqladmin ping -h 127.0.0.1` 会被当成一个文件名去找
                    .append("      test: [\"CMD-SHELL\", ").append(quote(check)).append("]\n");
        }
    }

    private static void environment(StringBuilder out, String indent, Map<String, String> env) {
        if (env.isEmpty()) {
            return;
        }
        out.append(indent).append("environment:\n");
        for (Map.Entry<String, String> entry : env.entrySet()) {
            out.append(indent).append("  ").append(quote(entry.getKey()))
                    .append(": ").append(quote(entry.getValue())).append('\n');
        }
    }

    /** 宿主路径写进 compose：反斜杠在 YAML 里是转义字符，{@code E:\a\b} 会被读成 {@code E:ab}。 */
    public static String hostPath(Path tree) {
        return tree.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /**
     * YAML 里的字符串，一律加引号：{@code 8.0} / {@code on} / {@code no} 不加引号会被读成
     * 数字和布尔，而它们在这里全是字符串——「哪些要引哪些不要」是一个永远会漏的规则。
     */
    private static String quote(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
