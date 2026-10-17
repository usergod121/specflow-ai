package com.specflow.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>一条编译命令要在两个地方都能跑</b>：宿主机（④ 每次交差）与 Linux 容器（① 的能力校验）。
 *
 * <h2>这一组钉的是真跑出来的那次失败</h2>
 * ① 的 Agent 交出来的是 {@code javac -d out\main src\main\java\com\demo\*.java}——
 * 这是 Windows 的写法。宿主机编译过了（所以 ① 看起来"绿了"），起环境时同一条命令进容器构建，
 * <b>退出码 2</b>，于是「冻结」被闸拦下，而错误信息读起来像"环境坏了"。
 * 真正的原因是：{@code \} 在 sh 里是转义字符，{@code out\main} 变成了 {@code outmain}。
 *
 * <p>两头都要收：提示词那边要求写正斜杠（治本），这里这个类兜住"人自己填了一条 Windows 写法"
 * 的那种情况（治标，但代价只是一次构建）。
 */
class LinuxSafeTest {

    @Test
    @DisplayName("Windows 路径换成正斜杠：out\\main → out/main")
    void rewritesWindowsPaths() {
        assertThat(Capabilities.linuxSafe("javac -d out\\main src\\main\\java\\com\\demo\\*.java"))
                .isEqualTo("javac -d out/main src/main/java/com/demo/*.java");
        assertThat(Capabilities.linuxSafe("gradlew.bat build\\.\\x")).isEqualTo("gradlew.bat build/./x");
    }

    @Test
    @DisplayName("本来就能两处跑的命令：一个字都不动，返回空串")
    void leavesGoodCommandsAlone() {
        assertThat(Capabilities.linuxSafe("mvn -q -DskipTests compile")).isEmpty();
        assertThat(Capabilities.linuxSafe("javac -d out/main src/main/java/com/demo/*.java")).isEmpty();
        assertThat(Capabilities.linuxSafe("")).isEmpty();
        assertThat(Capabilities.linuxSafe(null)).isEmpty();
    }

    @Test
    @DisplayName("带正则的命令不动（那里的反斜杠是模式，不是路径）")
    void doesNotTouchRegexCommands() {
        assertThat(Capabilities.linuxSafe("grep -c \"a\\b\" src/Main.java")).isEmpty();
        assertThat(Capabilities.linuxSafe("sed -n 's/\\.java$//p' list.txt")).isEmpty();
        assertThat(Capabilities.linuxSafe("awk '{print $1\\.}' in.txt")).isEmpty();
    }

    @Test
    @DisplayName("已经在按 Linux 转义写的（含 \\\\）：不动")
    void doesNotTouchDoubleBackslash() {
        assertThat(Capabilities.linuxSafe("echo \"a\\\\b\" > out.txt")).isEmpty();
    }
}
