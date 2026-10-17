package com.specflow.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeTest {

    private EnvConfig config() {
        Map<String, String> dbEnv = new LinkedHashMap<>();
        dbEnv.put("MYSQL_ROOT_PASSWORD", "root");
        Map<String, EnvConfig.Dependency> dependencies = new LinkedHashMap<>();
        // 有健康检查的依赖：up --wait 要等它真的可用；没有的那一个只等容器起来
        dependencies.put("db", new EnvConfig.Dependency("mysql:8.0", dbEnv, "mysqladmin ping -h 127.0.0.1"));
        dependencies.put("cache", new EnvConfig.Dependency("redis:7", Map.of(), null));
        return new EnvConfig("eclipse-temurin:17-jdk", "", dependencies, Map.of("DB_HOST", "db"),
                List.of(EnvConfig.Step.inApp("mvn -q compile")), List.of());
    }

    private String render(Path root) {
        return Compose.render(config(), root, Compose.projectName(root));
    }

    @Test
    void compose里没有初始化和重置(@TempDir Path root) {
        String yaml = render(root);

        // 它们不是「容器长什么样」，写进来会被当成容器启动命令、每次重启都重跑一遍。
        // 判据按 **YAML 键**（`init:` / `reset:`）而不是子串：`sleep infinity` 里就有 "init"
        // ——按子串判会把一份完全正确的 compose 判红（这条测试自己踩过）
        assertThat(yaml).doesNotContain("\n  init:");
        assertThat(yaml).doesNotContain("\n  reset:");
        assertThat(yaml).doesNotContain("\ninit:");
        assertThat(yaml).doesNotContain("\nreset:");
        assertThat(yaml).doesNotContain("初始化").doesNotContain("重置");
        assertThat(yaml).contains("services:").contains("  app:").contains("  db:").contains("  cache:");
    }

    @Test
    void 只有app挂项目目录(@TempDir Path root) {
        String yaml = render(root);

        assertThat(yaml).containsOnlyOnce("type: bind");
        assertThat(yaml).contains("source: \"" + Compose.hostPath(root) + "\"");
        assertThat(yaml).contains("target: \"/work\"");
        // app 的挂载块是**一处 bind（项目树）+ 一处 volume（依赖缓存）**；
        // db / cache 那两个服务里没有 volumes，但顶层会为缓存卷写一段声明——
        // 所以这里数的是 `type:` 而不是 "volumes:"（后者正确出现两次）
        assertThat(yaml).containsOnlyOnce("type: bind");
        assertThat(yaml).containsOnlyOnce("type: volume");
    }

    @Test
    void 依赖缓存卷跨轮保留_而数据每轮重置(@TempDir Path root) {
        String yaml = render(root);

        // 这些写在卷上的东西（Maven 的 ~/.m2 之类）不该跟着 down -v 一起没：
        // 不这么写的话每轮都得从零下依赖——实测就是 7 轮 sleep、17 分钟白等
        assertThat(yaml).contains("external: true");
        assertThat(yaml).contains("-home");
        // HOME 由引擎定：缓存位置必须我们说了算，不能跟着"镜像里 uid 1000 恰好叫 ubuntu"走
        assertThat(yaml).contains("target: \"" + Compose.HOME_IN_CONTAINER + "\"");
        assertThat(yaml).contains("\"HOME\": \"" + Compose.HOME_IN_CONTAINER + "\"");
    }

    @Test
    void app是常驻的非root沙箱(@TempDir Path root) {
        String yaml = render(root);

        assertThat(yaml).contains("user: \"1000:1000\"");
        assertThat(yaml).contains("cap_drop: [\"ALL\"]");
        assertThat(yaml).contains("no-new-privileges:true");
        assertThat(yaml).contains("command: [\"sleep\", \"infinity\"]");
        assertThat(yaml).contains("working_dir: \"/work\"");
        // 资源上限：进程数 / 内存 / CPU 三样都在
        assertThat(yaml).contains("pids_limit:").contains("mem_limit:").contains("cpus:");
    }

    @Test
    void 依赖都在_有健康检查就等它健康(@TempDir Path root) {
        String yaml = render(root);

        assertThat(yaml).contains("image: \"mysql:8.0\"").contains("image: \"redis:7\"");
        assertThat(yaml).contains("MYSQL_ROOT_PASSWORD\": \"root\"");
        assertThat(yaml).contains("test: [\"CMD-SHELL\", \"mysqladmin ping -h 127.0.0.1\"]");
        assertThat(yaml).contains("depends_on:").contains("      db:").contains("      cache:");
        assertThat(yaml).contains("condition: service_healthy");
        assertThat(yaml).contains("condition: service_started");
        // 宿主端口一个都不映射
        assertThat(yaml).doesNotContain("ports:");
    }

    @Test
    void 项目名只由小写字母数字短横线组成而且随路径稳定(@TempDir Path one, @TempDir Path two) {
        String name = Compose.projectName(one);

        assertThat(name).matches("[a-z0-9][a-z0-9-]*");
        assertThat(Compose.projectName(one)).isEqualTo(name);
        assertThat(Compose.projectName(two)).isNotEqualTo(name);
        // 全中文的目录名会被清空，兜一个能当项目名的
        assertThat(Compose.projectName(one.resolve("中文目录"))).matches("[a-z0-9][a-z0-9-]*");
    }
}
