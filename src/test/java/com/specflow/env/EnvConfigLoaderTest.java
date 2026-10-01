package com.specflow.env;

import com.specflow.exception.SpecValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code env.yaml} 的解析与校验。
 *
 * <p>重点在<b>报错的质量</b>而不是「能读出来」：这份文件是用户手写的，而写错的代价是
 * 「容器起不来」——那种失败看起来和代码错了没什么两样。所以每一条断言都在问同一个问题：
 * 报出来的那句话，够不够用户自己改对？
 */
@DisplayName("测试环境声明（env.yaml）")
class EnvConfigLoaderTest {

    @TempDir
    Path root;

    private static final String FULL = """
            docker:
              command: "wsl docker"

            image: "eclipse-temurin:17"
            workdir: "/workspace"

            dependencies:
              db:
                image: "mysql:8.0"
                env:
                  MYSQL_ROOT_PASSWORD: "secret"
                healthcheck:
                  test: "mysqladmin ping -h 127.0.0.1"
                  interval: "2s"
                  retries: "30"
                  start-period: "5s"
              cache:
                image: "redis:7"
                healthcheck: "redis-cli ping"

            env:
              DB_HOST: "db"
              DB_PORT: 3306

            init:
              - "python -m pip install --no-index -r requirements.txt"

            reset: "python manage.py flush --noinput"
            """;

    @Test
    @DisplayName("一份完整的声明：六个字段一个不落，依赖、连接信息、init/reset 各就各位")
    void readsAFullDeclaration() {
        EnvConfig config = load(FULL).orElseThrow();

        assertThat(config.dockerCommand()).isEqualTo("wsl docker");
        assertThat(config.image()).isEqualTo("eclipse-temurin:17");
        assertThat(config.workdir()).isEqualTo("/workspace");
        assertThat(config.dependencies().keySet()).containsExactly("db", "cache");
        assertThat(config.dependencies().get("db").image()).isEqualTo("mysql:8.0");
        assertThat(config.dependencies().get("db").env()).containsEntry("MYSQL_ROOT_PASSWORD", "secret");
        assertThat(config.dependencies().get("db").healthcheck())
                .satisfies(check -> {
                    assertThat(check.test()).isEqualTo("mysqladmin ping -h 127.0.0.1");
                    assertThat(check.interval()).isEqualTo("2s");
                    assertThat(check.retries()).isEqualTo("30");
                    assertThat(check.startPeriod()).isEqualTo("5s");
                });
        // healthcheck 只写一条命令的那种简写：健康检查十有八九就是这个形状
        assertThat(config.dependencies().get("cache").healthcheck().test()).isEqualTo("redis-cli ping");
        // 数字也要能当连接信息用：端口写成 3306 而不是 "3306" 是最自然的写法
        assertThat(config.env()).containsEntry("DB_PORT", "3306");
        assertThat(config.init()).singleElement()
                .isEqualTo("python -m pip install --no-index -r requirements.txt");
        assertThat(config.reset()).singleElement().isEqualTo("python manage.py flush --noinput");
    }

    @Test
    @DisplayName("依赖按写下来的顺序留着：YAML → compose 是确定性映射，顺序不能变")
    void keepsDependencyOrder() {
        EnvConfig config = load("""
                image: "x:1"
                dependencies:
                  zeta:
                    image: "z:1"
                  alpha:
                    image: "a:1"
                  beta:
                    image: "b:1"
                """).orElseThrow();

        assertThat(config.dependencies().keySet()).containsExactly("zeta", "alpha", "beta");
    }

    @Test
    @DisplayName("没写 workdir：用默认值（项目目录挂进容器的位置）")
    void defaultsWorkdir() {
        EnvConfig config = load("image: \"x:1\"\n").orElseThrow();

        assertThat(config.workdir()).isEqualTo(EnvConfig.DEFAULT_WORKDIR);
        assertThat(config.workdir()).startsWith("/");
    }

    @Test
    @DisplayName("文件不在 = 没声明，不是错误：没写这份声明的项目照旧只能跑单元测试")
    void missingFileMeansNotDeclared() {
        assertThat(EnvConfigLoader.load(root)).isEmpty();
    }

    @Test
    @DisplayName("空文件、只有注释的文件也算没声明（「建了但还没想好」不该让项目打不开）")
    void blankFileMeansNotDeclared() throws IOException {
        for (String source : List.of("", "\n\n", "# 先占个位置\n# image: 还没想好\n")) {
            write(source);
            assertThat(EnvConfigLoader.load(root)).as("内容：%s", source).isEmpty();
        }
    }

    // ---------- 报错：每一条都要说得清是哪儿 ----------

    @Test
    @DisplayName("字段名写错：报出它在第几行，并列出可以用的是哪几个")
    void reportsUnknownFieldWithLine() throws IOException {
        String source = """
                image: "x:1"
                dependencis:
                  db:
                    image: "mysql:8.0"
                """;
        write(source);

        assertThat(problems()).singleElement().satisfies(problem -> {
            assertThat(problem).contains("第 2 行");
            assertThat(problem).contains("dependencis");
            assertThat(problem).contains("dependencies");
        });
    }

    @Test
    @DisplayName("嵌套字段写错：行号指到那一行，路径也写出来")
    void reportsUnknownNestedFieldWithLine() throws IOException {
        write("""
                image: "x:1"
                dependencies:
                  db:
                    image: "mysql:8.0"
                    health_check: "mysqladmin ping"
                """);

        assertThat(problems()).singleElement().satisfies(problem -> {
            assertThat(problem).contains("第 5 行");
            assertThat(problem).contains("dependencies.db");
            assertThat(problem).contains("health_check");
        });
    }

    @Test
    @DisplayName("没写 image：说清为什么它非写不可，而不是只说「缺少必填项」")
    void requiresImage() throws IOException {
        write("workdir: \"/work\"\n");

        assertThat(problems()).singleElement().asString()
                .contains("image")
                .contains("镜像");
    }

    @Test
    @DisplayName("workdir 是相对路径：报错，并说清相对路径会被当成什么")
    void refusesRelativeWorkdir() throws IOException {
        write("""
                image: "x:1"
                workdir: "work"
                """);

        assertThat(problems()).singleElement().satisfies(problem -> {
            assertThat(problem).contains("第 2 行");
            assertThat(problem).contains("绝对路径");
            // 「为什么不行」也要说：用户第一反应是「相对路径不也能用吗」
            assertThat(problem).contains("命名卷");
        });
    }

    @Test
    @DisplayName("依赖名用了大写：报错（它同时是容器互连的服务名，compose 只认一套字符）")
    void refusesInvalidServiceName() throws IOException {
        write("""
                image: "x:1"
                dependencies:
                  MyDB:
                    image: "mysql:8.0"
                """);

        assertThat(problems()).singleElement().satisfies(problem -> {
            assertThat(problem).contains("第 3 行");
            assertThat(problem).contains("MyDB");
            assertThat(problem).contains("服务名");
        });
    }

    @Test
    @DisplayName("依赖没写 image：报出是哪一个依赖缺的")
    void requiresDependencyImage() throws IOException {
        write("""
                image: "x:1"
                dependencies:
                  db:
                    env:
                      A: "1"
                """);

        assertThat(problems()).singleElement().asString()
                .contains("第 3 行")
                .contains("dependencies.db");
    }

    @Test
    @DisplayName("healthcheck 写了字段组却没有 test：报错，并说清删掉它的后果")
    void requiresHealthcheckTest() throws IOException {
        write("""
                image: "x:1"
                dependencies:
                  db:
                    image: "mysql:8.0"
                    healthcheck:
                      interval: "2s"
                """);

        assertThat(problems()).singleElement().satisfies(problem -> {
            assertThat(problem).contains("第 5 行");
            assertThat(problem).contains("test");
            assertThat(problem).contains("只等容器起来");
        });
    }

    @Test
    @DisplayName("init 里混进非命令：报出是第几条（数组元素也要报得准）")
    void refusesNonCommandInInit() throws IOException {
        write("""
                image: "x:1"
                init:
                  - "python setup.py install"
                  - 42
                """);

        assertThat(problems()).singleElement().asString()
                .contains("第 4 行")
                .contains("init")
                .contains("第 2 条");
    }

    @Test
    @DisplayName("env 的值写成了嵌套结构：报错，并说清环境变量传不了它")
    void refusesNestedEnvValue() throws IOException {
        write("""
                image: "x:1"
                env:
                  DB:
                    host: "db"
                """);

        assertThat(problems()).singleElement().satisfies(problem -> {
            assertThat(problem).contains("第 3 行");
            assertThat(problem).contains("env.DB");
        });
    }

    @Test
    @DisplayName("好几处都错：一次全报出来（用户改一轮就能改完）")
    void reportsEveryProblemAtOnce() throws IOException {
        write("""
                imag: "x:1"
                workdir: "work"
                dependencies:
                  BadName:
                    env:
                      A: "1"
                """);

        List<String> problems = problems();
        // 五条：字段名写错、workdir 是相对路径、依赖名非法、那个依赖缺 image、顶层缺 image。
        // 「遇到第一个就退出」的话，用户要来回跑五趟，而每一趟只被告知一处
        assertThat(problems).hasSize(5);
        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("imag"));
        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("workdir"));
        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains("BadName"));
        assertThat(problems).filteredOn(problem -> problem.contains("没有 image")).hasSize(2);
    }

    @Test
    @DisplayName("YAML 本身写坏了：报解析失败，不冒充「字段不认识」")
    void reportsBrokenYaml() throws IOException {
        write("image: \"x:1\"\n  bad-indent: oops\n");

        assertThat(problems()).singleElement().asString().contains("env.yaml");
    }

    /**
     * 同一个键写了两遍：行号要指向<b>生效的那一行</b>。
     *
     * <p>解析器保留的是最后一份（YAML 的规矩），所以「第几行写错了」也必须指到那一行——
     * 指向第一行的话，报出来的那行上写着一句完全合法的话，用户会对着它发懵。
     */
    @Test
    @DisplayName("同一个键写两遍：行号指到生效的那一行（最后一份）")
    void pointsAtTheLastDuplicateKey() throws IOException {
        write("""
                image: "x:1"
                workdir: "/ok"
                workdir: "work"
                """);

        assertThat(problems()).singleElement().asString().contains("第 3 行");
    }

    @Test
    @DisplayName("最外层不是一组「字段: 值」：报错，而不是静默当成空配置")
    void refusesNonMapping() throws IOException {
        write("- image\n- workdir\n");

        assertThat(problems()).singleElement().asString().contains("最外层");
    }

    // ---------- 辅助 ----------

    private Optional<EnvConfig> load(String source) {
        try {
            write(source);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return EnvConfigLoader.load(root);
    }

    /** 校验失败时的全部问题；没失败就是断言失败（这些测试全都以「报错」为前提）。 */
    private List<String> problems() {
        assertThatThrownBy(() -> EnvConfigLoader.load(root))
                .isInstanceOf(SpecValidationException.class);
        try {
            EnvConfigLoader.load(root);
        } catch (SpecValidationException e) {
            return e.problems();
        }
        throw new AssertionError("这份声明本该校验不过");
    }

    private void write(String source) throws IOException {
        Path file = root.resolve(EnvConfigLoader.relativePath());
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
