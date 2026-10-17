package com.specflow.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>哪些命令算高危</b>——三档：一律拒 / 问一句 / 装工具链。
 *
 * <p>这里的每一条都对应一次实测教训（都在 {@code docs/v3/STATE.md} 的「待修缺陷」里）：
 * <ul>
 *   <li>{@code $HOME} 曾经在"一律拒"里 → 容器里 {@code echo $HOME} 这种正常写法被硬拒两轮；</li>
 *   <li>{@code sudo} 曾经在"问一句"里 → 容器没有 sudo，问了也是白问，实测让用户等了 187 秒；</li>
 *   <li>"自己装工具链"原先**没有**判据 → Agent 花 20 轮下载 Maven，而容器是一次性的，
 *       下一轮一个字节都不剩（这是最贵的那次事故）。</li>
 * </ul>
 */
class CommandGuardTest {

    @Test
    @DisplayName("$HOME 不再被拒：容器里它就是它自己的家目录")
    void homeIsFineInsideTheContainer() {
        assertThat(CommandGuard.forbidden("echo $HOME")).isNull();
        assertThat(CommandGuard.forbidden("touch $HOME/.cache/x && ls ~")).isNull();
        assertThat(CommandGuard.highRisk("echo $HOME")).isNull();
    }

    @Test
    @DisplayName("sudo：直接拒（不问），话里说清「这里根本没有 sudo」和该去改哪儿")
    void sudoIsRefusedNotAsked() {
        String why = CommandGuard.privilege("sudo apt-get install -y maven");

        assertThat(why).isNotNull();
        assertThat(why).contains("sudo").contains("uid 1000").contains("env.yaml");
        assertThat(CommandGuard.highRisk("sudo -n true"))
                .as("提权走 privilege 那一档，不该再弹给用户")
                .isNull();
    }

    @Test
    @DisplayName("装工具链：拒掉，并把「去改 env.yaml 的 image 或 init」说清楚")
    void toolchainBootstrapIsRefused() {
        for (String command : new String[]{
                "apt-get update && apt-get install -y maven",
                "apk add --no-cache openjdk17",
                "pip install requests",
                "npm install -g pnpm",
                "nvm install 20",
                "export PATH=$PATH:/opt/maven/bin",
                "cd ~ && curl -sL -o maven.tar.gz https://repo.maven.apache.org/maven2/apache-maven-3.9.9.tgz",
        }) {
            String why = CommandGuard.bootstrap(command);
            assertThat(why).as("该拒：%s", command).isNotNull();
            assertThat(why).contains("env.yaml");
        }
    }

    @Test
    @DisplayName("装自己的脚手架不算装工具链：临时脚本、测试数据、npm test 都放行")
    void ownScaffoldingIsFine() {
        for (String command : new String[]{
                "cat > /tmp/check.sh <<'EOF'\necho hi\nEOF",
                "javac -d /tmp/out /tmp/Check.java && java -cp /tmp/out Check",
                "npm test",
                "curl -s -o /tmp/fixture.json http://db:3306/health",
                "mysql -uroot -p123456 -e 'select 1'",
        }) {
            assertThat(CommandGuard.bootstrap(command)).as("不该拒：%s", command).isNull();
        }
    }

    @Test
    @DisplayName("原来那三档还在：一律拒的、问一句的都还能认")
    void originalBandsStillWork() {
        assertThat(CommandGuard.forbidden("docker run --privileged x")).contains("--privileged");
        assertThat(CommandGuard.forbidden("mount -v /:/host")).isNotNull();
        assertThat(CommandGuard.highRisk("chmod -R 777 /work")).isNotNull();
        assertThat(CommandGuard.highRisk("rm -rf /work/target")).isNotNull();
    }

    @Test
    @DisplayName("从编译命令里认出构建工具（猜错了也不要紧，原始命令就在话里）")
    void recognisesTheBuildTool() {
        assertThat(Capabilities.toolOf("mvn -q -DskipTests compile")).isEqualTo("mvn");
        assertThat(Capabilities.toolOf("cd backend && ./mvnw package")).isEqualTo("mvnw");
        assertThat(Capabilities.toolOf("cmd /c mvn.cmd compile")).isEqualTo("mvn");
        assertThat(Capabilities.toolOf("gradle build")).isEqualTo("gradle");
        assertThat(Capabilities.toolOf("npm run build")).isEqualTo("npm");
        assertThat(Capabilities.toolOf("")).isEmpty();
        // 认不得的时候退回命令里第一个"不是 cd/&& 之类噪音"的词
        assertThat(Capabilities.toolOf("cd x && ./custom-build.sh")).isEqualTo("custom-build.sh");
    }
}
