package com.specflow.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code env.yaml → compose} 的那张死图（十五.5）。
 *
 * <p>这一层是<b>纯字符串变换</b>，所以测试也按纯函数写：给一份声明、给一个项目目录，
 * 断言渲染出来的每一行。换行、引号、挂载点的写法全都在这里被钉住——它们一旦变了，
 * 变的是「容器里挂着谁的代码」这件事，而那件事出错时没人看得出来。
 */
@DisplayName("环境声明 → compose")
class ComposeFileTest {

    @TempDir
    Path root;

    /** 项目目录按真实路径参与渲染（挂载点就是它），所以断言里要用同一个写法。 */
    private String host() {
        return root.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static EnvConfig config(Map<String, EnvConfig.Dependency> dependencies,
                                    Map<String, String> env, String workdir) {
        return new EnvConfig(null, "eclipse-temurin:17", workdir, dependencies, env,
                List.of("python -m pip install -r requirements.txt"), List.of("python manage.py flush"));
    }

    @Test
    @DisplayName("app 服务：挂项目目录、工作目录是 workdir、sleep infinity 常驻、env 原样注入")
    void mapsTheAppService() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DB_HOST", "db");
        env.put("DB_PORT", "3306");

        String yaml = ComposeFile.render(config(Map.of(), env, "/work"), root);

        assertThat(yaml).contains("  app:\n");
        assertThat(yaml).contains("    image: \"eclipse-temurin:17\"\n");
        assertThat(yaml).contains("    working_dir: \"/work\"\n");
        // 常驻：它不是「跑一次就退」的容器，而是测试代码的落脚点
        assertThat(yaml).contains("    command: [\"sleep\", \"infinity\"]\n");
        // 挂载用长语法 + 正斜杠：短语法里 Windows 的盘符 `E:` 会被当成字段分隔符切错
        assertThat(yaml).contains("    volumes:\n");
        assertThat(yaml).contains("      - type: bind\n");
        assertThat(yaml).contains("        source: \"" + host() + "\"\n");
        assertThat(yaml).contains("        target: \"/work\"\n");
        // 连接信息原样进去：测试代码在容器里读的就是它们
        assertThat(yaml).contains("      \"DB_HOST\": \"db\"\n");
        assertThat(yaml).contains("      \"DB_PORT\": \"3306\"\n");
    }

    @Test
    @DisplayName("依赖原样映射：镜像、环境变量、健康检查五项一个不落")
    void mapsDependencies() {
        EnvConfig.Dependency db = new EnvConfig.Dependency("mysql:8.0",
                Map.of("MYSQL_ROOT_PASSWORD", "secret"),
                new EnvConfig.Healthcheck("mysqladmin ping -h 127.0.0.1", "2s", "5s", "30", "10s"));

        String yaml = ComposeFile.render(config(Map.of("db", db), Map.of(), "/work"), root);

        assertThat(yaml).contains("  db:\n");
        assertThat(yaml).contains("    image: \"mysql:8.0\"\n");
        assertThat(yaml).contains("      \"MYSQL_ROOT_PASSWORD\": \"secret\"\n");
        assertThat(yaml).contains("    healthcheck:\n");
        // CMD-SHELL 而不是 CMD：用户写的是「一条命令」，用 CMD 会把它当成一个文件名去找
        assertThat(yaml).contains("      test: [\"CMD-SHELL\", \"mysqladmin ping -h 127.0.0.1\"]\n");
        assertThat(yaml).contains("      interval: \"2s\"\n");
        assertThat(yaml).contains("      timeout: \"5s\"\n");
        assertThat(yaml).contains("      retries: \"30\"\n");
        // 用户写 start-period（本项目的命名习惯），compose 认的是 start_period
        assertThat(yaml).contains("      start_period: \"10s\"\n");
    }

    @Test
    @DisplayName("写了健康检查的依赖：app 等它健康；没写的只等它起来")
    void waitsOnlyForHealthyDependencies() {
        EnvConfig.Dependency healthy = new EnvConfig.Dependency("mysql:8.0", Map.of(),
                new EnvConfig.Healthcheck("mysqladmin ping", null, null, null, null));
        EnvConfig.Dependency plain = new EnvConfig.Dependency("redis:7", Map.of(), null);
        Map<String, EnvConfig.Dependency> dependencies = new LinkedHashMap<>();
        dependencies.put("db", healthy);
        dependencies.put("cache", plain);

        String yaml = ComposeFile.render(config(dependencies, Map.of(), "/work"), root);

        assertThat(yaml).contains("    depends_on:\n")
                .contains("      db:\n        condition: service_healthy\n")
                .contains("      cache:\n        condition: service_started\n");
    }

    @Test
    @DisplayName("宿主端口一个都不暴露：容器之间走服务名（十五.5）")
    void neverPublishesPorts() {
        Map<String, EnvConfig.Dependency> dependencies = Map.of("db",
                new EnvConfig.Dependency("mysql:8.0", Map.of("MYSQL_ROOT_PASSWORD", "secret"),
                        new EnvConfig.Healthcheck("mysqladmin ping", null, null, null, null)));

        String yaml = ComposeFile.render(config(dependencies, Map.of("DB_HOST", "db"), "/work"), root);

        // 一个都不许有：映射到宿主机就是给这台机器开一个谁都能连的口子，
        // 而测试根本用不着它（容器之间用服务名互连）
        assertThat(yaml).doesNotContain("ports");
        assertThat(yaml).doesNotContain("expose");
    }

    @Test
    @DisplayName("init/reset 不写进 compose：它们是「起来之后要做的事」，不是容器长什么样")
    void keepsInitAndResetOutOfCompose() {
        String yaml = ComposeFile.render(config(Map.of(), Map.of(), "/work"), root);

        // 写进去就会变成容器启动命令的一部分——每次重启都重跑一遍，
        // 而「每次跑前重置」这件事就再也不成立了
        assertThat(yaml).doesNotContain("pip install");
        assertThat(yaml).doesNotContain("manage.py flush");
    }

    @Test
    @DisplayName("同一份声明渲染两次：一个字符都不差（它是确定性映射，不需要模型参与）")
    void rendersDeterministically() {
        Map<String, EnvConfig.Dependency> dependencies = new LinkedHashMap<>();
        dependencies.put("zeta", new EnvConfig.Dependency("z:1", Map.of("B", "2"), null));
        dependencies.put("alpha", new EnvConfig.Dependency("a:1", Map.of("A", "1"), null));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("Z", "1");
        env.put("A", "2");

        EnvConfig declared = config(dependencies, env, "/work");
        String first = ComposeFile.render(declared, root);

        assertThat(ComposeFile.render(declared, root)).isEqualTo(first);
        // 顺序也不能变：它跟着声明里的先后走，不是哈希顺序
        assertThat(first.indexOf("  zeta:")).isLessThan(first.indexOf("  alpha:"));
        assertThat(first.indexOf("\"Z\":")).isLessThan(first.indexOf("\"A\":"));
    }

    @Test
    @DisplayName("值一律加引号：8.0、on、no 这些不加会被 YAML 读成数字和布尔")
    void quotesEveryValue() {
        String yaml = ComposeFile.render(new EnvConfig(null, "mysql:8.0", "/work", Map.of(),
                Map.of("FLAG", "on", "RATIO", "1.5"), List.of(), List.of()), root);

        assertThat(yaml).contains("    image: \"mysql:8.0\"\n");
        assertThat(yaml).contains("\"FLAG\": \"on\"\n");
        assertThat(yaml).contains("\"RATIO\": \"1.5\"\n");
    }

    @Test
    @DisplayName("落位：.specflow/env/<时间戳>/compose.yaml，绝不碰项目自己的 compose 文件")
    void writesUnderItsOwnDirectory() throws IOException {
        Path project = root.resolve("proj");
        Files.createDirectories(project);
        // 用户自己的 compose 文件：内容必须一个字都不变
        Path own = project.resolve("docker-compose.yml");
        Files.writeString(own, "services:\n  web:\n    image: nginx\n", StandardCharsets.UTF_8);

        Path written = ComposeFile.write(project, "20260930-120000",
                config(Map.of(), Map.of(), "/work"));

        assertThat(written).isEqualTo(project.resolve(".specflow/env/20260930-120000/compose.yaml"));
        assertThat(written).exists();
        assertThat(Files.readString(written)).contains("\nservices:\n  app:\n");
        assertThat(Files.readString(own)).isEqualTo("services:\n  web:\n    image: nginx\n");
    }

    @Test
    @DisplayName("文件头写着「别改我」和「不碰你自己的 compose」")
    void explainsItself() throws IOException {
        Path project = root.resolve("proj2");
        Files.createDirectories(project);

        Path written = ComposeFile.write(project, "20260930-120000",
                config(Map.of(), Map.of(), "/work"));
        String head = Files.readString(written, StandardCharsets.UTF_8).lines()
                .takeWhile(line -> !line.startsWith("services:")).reduce("", (a, b) -> a + b + "\n");

        assertThat(head).contains("env.yaml");
        assertThat(head).contains("不碰你项目自己的 compose 文件");
        assertThat(head).contains("一个宿主端口都不映射");
    }

    // ---------- 项目名 ----------

    @Test
    @DisplayName("compose 项目名：sf-<项目目录名>-<短哈希>，稳定（每次初始化都落到同一个名字上）")
    void derivesStableProjectName() {
        Path project = root.resolve("specflow-ai");

        assertThat(ComposeFile.projectName(project)).startsWith("sf-specflow-ai-");
        assertThat(ComposeFile.projectName(project))
                .as("同一个目录永远算出同一个名字——容器才复用得上")
                .isEqualTo(ComposeFile.projectName(project));
        // Windows 上同一个目录的路径大小写可能不同：那也是同一个项目
        assertThat(ComposeFile.projectName(root.resolve("SPECFLOW-AI")))
                .isEqualTo(ComposeFile.projectName(project));
    }

    /**
     * 不同目录必须算出不同名字——<b>这是唯一能删错东西的路径</b>。
     *
     * <p>光靠目录名会撞：两个都叫 {@code backend} 的项目、或者几个中文目录名（归一化之后
     * 全变成 {@code project}）会算出同一个 compose 项目名。撞名之后容器还能靠
     * {@code ...project.working_dir} 标签分辨，但<b>卷和网络没有那个标签</b>——
     * 清理残留时就可能删掉另一个项目的数据。
     */
    @Test
    @DisplayName("同名目录 / 中文目录名：项目名照样各不相同（撞名会删到别人的卷）")
    void keepsProjectNamesUnique() {
        String first = ComposeFile.projectName(root.resolve("a/backend"));
        String second = ComposeFile.projectName(root.resolve("b/backend"));

        assertThat(first).as("两个都叫 backend 的项目").isNotEqualTo(second);

        String chinese = ComposeFile.projectName(root.resolve("订单服务"));
        String otherChinese = ComposeFile.projectName(root.resolve("库存服务"));
        assertThat(chinese).startsWith("sf-project-");
        assertThat(chinese).as("中文目录名归一化之后不能撞在一起").isNotEqualTo(otherChinese);
    }

    @Test
    @DisplayName("项目名归一化：大写、空格、中文都要变成 compose 认的字符")
    void normalizesProjectName() {
        assertThat(ComposeFile.projectName(root.resolve("My Project!"))).startsWith("sf-my-project-");
        // 中文会被整个丢掉，剩下的部分必须还合法（compose 要求以字母数字开头）
        String chinese = ComposeFile.projectName(root.resolve("订单服务"));
        assertThat(chinese).startsWith("sf-project-");
        assertThat(chinese).matches("sf-[a-z0-9][a-z0-9_-]*");
    }

    @Test
    @DisplayName("目录名过长：截断到 compose 认的长度，而且要截在合法位置上")
    void shortensLongProjectName() {
        String longName = "a".repeat(80);
        String project = ComposeFile.projectName(root.resolve(longName));

        // 前缀 + 截断后的目录名 + 短横线 + 6 位哈希
        assertThat(project.length())
                .isLessThanOrEqualTo(ComposeFile.PROJECT_PREFIX.length() + 40 + 1 + 6);
        assertThat(project).matches("sf-[a-z0-9][a-z0-9_-]*");
    }

    @Test
    @DisplayName("目录名里有连续的非字母数字：压成一个短横线，末尾不留")
    void collapsesSeparators() {
        assertThat(ComposeFile.projectName(root.resolve("a   b__c--"))).startsWith("sf-a-b__c-");
    }
}
