package com.specflow.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>镜像里必须有编译用的那个工具</b>——这条是引擎的确定性兜底，不靠模型自觉。
 *
 * <h2>为什么要有它（用户实报的一次）</h2>
 * ① 起完环境，容器里跑 {@code mvn -q -DskipTests compile} 得到 {@code sh: 1: mvn: not found}，
 * 而界面上一句人话写着「容器里没有 mvn …换一个自带该工具的镜像」。用户问：
 * <b>「这是引擎写的还是 AI 写的？环境 Agent 为什么没识别到 maven？」</b>
 * <b>答案是两句</b>：那句话是引擎写死的（{@code env/Capabilities}）；而环境 Agent 没识别到，
 * 是因为<b>它的提示词自己把错的样子写成了示例</b>（{@code image: eclipse-temurin:17} 配 {@code mvn}），
 * 又没有一处要求"镜像要自带构建工具"。
 *
 * <p>所以两头都补：提示词加了"镜像必须自带工具"的硬要求与对照表（治本），
 * 这里再加一道确定性校验（治标，但**不依赖模型**）：命令要 {@code mvn}、镜像却是纯 JDK 时，
 * 直接换成 {@code maven:3.9-eclipse-temurin-17}，并在事件里说明换了什么。
 */
class EnvAgentImageTest {

    private static EnvAgent.Submitted submitted(String image, String compile) {
        return new EnvAgent.Submitted("image: " + image + "\nworkdir: /work\n", compile, "");
    }

    @Test
    @DisplayName("mvn 配纯 JDK 镜像：换成 maven 那个（这是用户撞的那一次）")
    void replacesJdkOnlyImageForMavenCommand() {
        EnvAgent.Submitted fixed = EnvAgent.withBuildImage(
                submitted("eclipse-temurin:17", "mvn -q -DskipTests compile"));

        assertThat(EnvAgent.imageOf(fixed.envYaml())).isEqualTo("maven:3.9-eclipse-temurin-17");
        assertThat(fixed.compileCommand()).isEqualTo("mvn -q -DskipTests compile");
        assertThat(fixed.notes()).contains("eclipse-temurin:17").contains("mvn");
        // 其余行一个字都不许动
        assertThat(fixed.envYaml()).contains("workdir: /work");
    }

    @Test
    @DisplayName("本来就是对的镜像：一个字都不改（不许瞎换）")
    void keepsImageWhenItAlreadyFits() {
        for (String[] pair : new String[][]{
                {"maven:3.9-eclipse-temurin-17", "mvn -q -DskipTests compile"},
                {"node:20", "npm run build"},
                {"eclipse-temurin:17", "javac -d out/main src/Main.java"},
                {"gradle:8-jdk17", "./gradlew build"},
                {"python:3.12", "pytest -q"}}) {
            EnvAgent.Submitted input = submitted(pair[0], pair[1]);
            EnvAgent.Submitted fixed = EnvAgent.withBuildImage(input);
            assertThat(fixed.envYaml()).as("%s + %s 不该被改", pair[0], pair[1]).isEqualTo(input.envYaml());
        }
    }

    @Test
    @DisplayName("认不出的工具：不动它（宁可不修，也不要瞎换）")
    void leavesUnknownToolsAlone() {
        EnvAgent.Submitted input = submitted("mycompany/custom-build:1.0", "./custom-build.sh -q");
        assertThat(EnvAgent.withBuildImage(input).envYaml()).isEqualTo(input.envYaml());
    }

    @Test
    @DisplayName("imageOf：带引号、带缩进、没有 image 行，三种都得认")
    void readsImageLine() {
        assertThat(EnvAgent.imageOf("image: node:20\n")).isEqualTo("node:20");
        assertThat(EnvAgent.imageOf("image: \"maven:3.9\"\n")).isEqualTo("maven:3.9");
        assertThat(EnvAgent.imageOf("  image: golang:1.22\n")).isEqualTo("golang:1.22");
        assertThat(EnvAgent.imageOf("workdir: /work\n")).isEmpty();
    }

    @Test
    @DisplayName("imageHas：纯 JDK 有 javac 没有 mvn（这条判断错了就等于没兜底）")
    void knowsWhatImagesContain() {
        assertThat(EnvAgent.imageHas("eclipse-temurin:17", "javac")).isTrue();
        assertThat(EnvAgent.imageHas("eclipse-temurin:17", "mvn")).isFalse();
        assertThat(EnvAgent.imageHas("maven:3.9-eclipse-temurin-17", "mvn")).isTrue();
        assertThat(EnvAgent.imageHas("node:20", "npm")).isTrue();
        assertThat(EnvAgent.imageHas("python:3.12", "mvn")).isFalse();
    }
}
