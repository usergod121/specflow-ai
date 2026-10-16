package com.specflow.env;

import com.specflow.core.Atomic;
import com.specflow.core.Layout;
import com.specflow.core.SpecException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvConfigLoaderTest {

    /** 三样都用上的一份声明：依赖 + 连接信息 + 两种 init 写法 + 一行 reset。 */
    private static final String FULL = """
            image: "eclipse-temurin:17-jdk"
            workdir: "/work"

            dependencies:
              db:
                image: "mysql:8.0"
                env:
                  MYSQL_ROOT_PASSWORD: "root"
                  MYSQL_DATABASE: "app"
                healthcheck: "mysqladmin ping -h 127.0.0.1 -uroot -proot"

            env:
              DB_HOST: "db"
              DB_PORT: 3306
              DEBUG: true

            init:
              - "mvn -q -DskipTests compile"
              - service: "db"
                command: "mysql -h127.0.0.1 -uroot -proot app"
                stdin: "sql/schema.sql"

            reset: "mvn -q flyway:clean flyway:migrate"
            """;

    @Test
    void 一份完整声明读得对() {
        EnvConfig config = EnvConfigLoader.parse(FULL);

        assertThat(config.image()).isEqualTo("eclipse-temurin:17-jdk");
        assertThat(config.workdir()).isEqualTo("/work");
        assertThat(config.dependencies()).containsOnlyKeys("db");
        assertThat(config.dependencies().get("db").image()).isEqualTo("mysql:8.0");
        assertThat(config.dependencies().get("db").env())
                .containsEntry("MYSQL_ROOT_PASSWORD", "root");
        assertThat(config.dependencies().get("db").healthcheck()).isEqualTo("mysqladmin ping -h 127.0.0.1 -uroot -proot");
        assertThat(config.env()).containsEntry("DB_HOST", "db");
        assertThat(config.init()).hasSize(2);
        assertThat(config.reset()).hasSize(1);
    }

    @Test
    void init两种写法都对() {
        // 一行命令 = 跑在 app 容器里
        EnvConfig one = EnvConfigLoader.parse("""
                image: "x"
                init: "make setup"
                """);
        assertThat(one.init()).hasSize(1);
        assertThat(one.init().get(0).inApp()).isTrue();
        assertThat(one.init().get(0).command()).isEqualTo("make setup");

        // 三栏 = 去依赖容器里跑、还要喂一个项目里的文件
        EnvConfig three = EnvConfigLoader.parse(FULL);
        EnvConfig.Step step = three.init().get(1);
        assertThat(step.inApp()).isFalse();
        assertThat(step.service()).isEqualTo("db");
        assertThat(step.command()).startsWith("mysql ");
        assertThat(step.stdin()).isEqualTo("sql/schema.sql");

        // workdir 没写就是默认的那个；reset 写一行也照收
        assertThat(one.workdir()).isEqualTo(EnvConfig.DEFAULT_WORKDIR);
        assertThat(three.reset().get(0).inApp()).isTrue();
    }

    @Test
    void service写了没声明的依赖要说清能写哪几个() {
        assertThatThrownBy(() -> EnvConfigLoader.parse("""
                image: "x"
                dependencies:
                  db:
                    image: "mysql:8.0"
                init:
                  - service: "cache"
                    command: "redis-cli ping"
                """))
                .isInstanceOf(SpecException.class)
                .hasMessageContaining("cache")
                .hasMessageContaining("能写的是：db");
    }

    @Test
    void image缺失要抛() {
        assertThatThrownBy(() -> EnvConfigLoader.parse("""
                dependencies:
                  db:
                    image: "mysql:8.0"
                """))
                .isInstanceOf(SpecException.class)
                .hasMessageContaining("image");
    }

    @Test
    void docker段两种写法都认() {
        EnvConfig one = EnvConfigLoader.parse("""
                docker: "wsl docker"
                image: "x"
                """);
        assertThat(one.image()).isEqualTo("x");

        EnvConfig two = EnvConfigLoader.parse("""
                docker:
                  command: "C:/Program Files/Docker/Docker/resources/bin/docker.exe"
                image: "x"
                """);
        assertThat(two.image()).isEqualTo("x");
    }

    @Test
    void 报错带行号而且一次报全() {
        assertThatThrownBy(() -> EnvConfigLoader.parse("""
                image: "x"
                workdir: "work"
                nope: 1
                """))
                .isInstanceOf(SpecException.class)
                .hasMessageContaining("workdir")
                .hasMessageContaining("nope")
                .hasMessageContaining("第 3 行");
    }

    @Test
    void 文件不在算没声明_写了但非法才算错(@TempDir Path root) {
        Layout layout = new Layout(root);
        assertThat(EnvConfigLoader.load(layout)).isEmpty();

        // 只有注释 = 「建了文件还没想好」，不算声明，也不算错
        Atomic.write(layout.envYaml(), "# image: \"x\"\n\n");
        assertThat(EnvConfigLoader.load(layout)).isEmpty();

        Atomic.write(layout.envYaml(), "image: \"mysql:8.0\"\nnope: 1\n");
        assertThatThrownBy(() -> EnvConfigLoader.load(layout)).isInstanceOf(SpecException.class);
    }

    @Test
    void 界面新建用的模板是能读的示例(@TempDir Path root) throws Exception {
        assertThat(EnvConfigLoader.template()).contains("image:").contains("dependencies:");
        // 模板整份都是注释，所以写下去等于「还没声明」，而不是一份坏配置
        Path file = new Layout(root).envYaml();
        Files.createDirectories(file.getParent());
        Files.writeString(file, EnvConfigLoader.template());
        assertThat(EnvConfigLoader.load(new Layout(root))).isEqualTo(Optional.empty());
    }
}
