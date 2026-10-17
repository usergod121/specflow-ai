package com.specflow.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 容器侧的构建**在容器内的副本里跑**，绝不写项目目录。
 *
 * <h2>这一条是一次真失败换来的</h2>
 * ① 的容器把项目挂进 {@code /work}，而 {@code target/} 是**宿主机**（Windows）编出来的。
 * 容器里的 uid 1000 去覆盖 {@code /work/target/classes/application.yml}，得到：
 * <pre>
 * [ERROR] maven-resources-plugin:3.3.1:resources … failed with
 *         FileSystemException: /work/target/classes/application.yml: Operation not permitted
 * </pre>
 * mvn 退出码 1。用户看到的是"容器里有 mvn 却编不过"，而真相是<b>两边共用一个输出目录</b>。
 *
 * <p>现在容器侧先复制一份到 {@code /tmp/specflow-build}（并扔掉宿主机编出来的 {@code target/}）
 * 再那里编：与工具无关、宿主机产物不动、依赖仍走外部缓存卷。
 */
class ContainerBuildCommandTest {

    @Test
    @DisplayName("构建命令在副本里跑：先复制、扔掉宿主机的 target/、再 cd 进去编")
    void buildsInACopy() {
        String command = Capabilities.inCopy("mvn -q -DskipTests compile");

        assertThat(command).startsWith("rm -rf /tmp/specflow-build");
        assertThat(command).contains("cp -a /work/. /tmp/specflow-build/");
        assertThat(command).contains("rm -rf /tmp/specflow-build/target");
        assertThat(command).contains("cd /tmp/specflow-build");
        assertThat(command).as("原命令要原样带上，别改用户的命令").contains("( mvn -q -DskipTests compile )");
        assertThat(command).as("绝不能在 /work 里编").doesNotContain("cd /work");
    }

    @Test
    @DisplayName("与工具无关：javac / npm / gradle 也都套同一层")
    void worksForAnyTool() {
        for (String compile : new String[]{
                "javac -d out/main src/Main.java",
                "npm run build",
                "./gradlew build --no-daemon"}) {
            assertThat(Capabilities.inCopy(compile))
                    .as(compile)
                    .contains("cp -a /work/. /tmp/specflow-build/")
                    .contains("( " + compile + " )");
        }
    }
}
