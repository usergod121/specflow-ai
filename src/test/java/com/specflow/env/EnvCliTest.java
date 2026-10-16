package com.specflow.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code specflow env …}：参数这一层，以及「收场到底收掉了什么」。
 *
 * <p>为什么要单独钉：它现在扛着 {@code start.bat} / {@code stop.bat} 两条脚本路，
 * 脚本不该去猜 docker 参数——参数错一位，用户看到的是「机器没反应」。
 *
 * <p>这里<b>不碰 docker</b>：{@code prepare} 只写文件；{@code down} 在「环境从没起过」
 * 时必须<b>什么都不做</b>就收场（这条早先是错的：它拼一条指向不存在文件的命令去跑 docker，
 * 然后把 docker 的「找不到文件」当成「收环境失败」报出来）。
 */
class EnvCliTest {

    private static List<String> args(String... all) {
        return List.of(all);
    }

    @Test
    @DisplayName("四个动作都认得，默认项目是当前目录、不 purge")
    void parsesEveryAction() {
        for (String action : List.of("prepare", "init", "reset", "status", "down")) {
            EnvCli.Plan plan = EnvCli.plan(args(action));
            assertThat(plan.action()).isEqualTo(action);
            assertThat(plan.project()).isEqualTo(Path.of("").toAbsolutePath());
            assertThat(plan.timeoutMinutes()).isZero();
            assertThat(plan.purge()).isFalse();
            assertThat(plan.verbose()).isFalse();
        }
    }

    @Test
    @DisplayName("参数都认得：-p / --timeout / --purge / --verbose")
    void parsesEveryFlag() {
        EnvCli.Plan plan = EnvCli.plan(args("down", "-p", "D:\\proj", "--purge", "--verbose"));

        assertThat(plan.project().toString()).isEqualTo("D:\\proj");
        assertThat(plan.purge()).isTrue();
        assertThat(plan.verbose()).isTrue();

        assertThat(EnvCli.plan(args("init", "--project", "D:\\proj", "--timeout", "45")).timeoutMinutes())
                .isEqualTo(45);
    }

    @Test
    @DisplayName("动作写错：话里要把五个动作列出来")
    void unknownActionListsThem() {
        assertThatThrownBy(() -> EnvCli.plan(args("dwon")))
                .isInstanceOf(com.specflow.core.SpecException.class)
                .hasMessageContaining("dwon")
                .hasMessageContaining("prepare")
                .hasMessageContaining("down");
    }

    @Test
    @DisplayName("其它写法错：没给动作、参数不认识、--timeout 不是正数、-p 后面没值")
    void rejectsBadArguments() {
        assertThatThrownBy(() -> EnvCli.plan(args()))
                .hasMessageContaining("用法");
        assertThatThrownBy(() -> EnvCli.plan(args("status", "--nope")))
                .hasMessageContaining("--nope");
        assertThatThrownBy(() -> EnvCli.plan(args("init", "--timeout", "abc")))
                .hasMessageContaining("--timeout");
        assertThatThrownBy(() -> EnvCli.plan(args("init", "--timeout", "0")))
                .hasMessageContaining("正整数");
        assertThatThrownBy(() -> EnvCli.plan(args("init", "-p")))
                .hasMessageContaining("-p");
    }

    @Test
    @DisplayName("--purge 只跟 down 一起用（它是删数据的开关，挂在别处就是误删）")
    void purgeOnlyWithDown() {
        assertThatThrownBy(() -> EnvCli.plan(args("init", "--purge")))
                .hasMessageContaining("--purge")
                .hasMessageContaining("down");
    }

    @Test
    @DisplayName("prepare：铺出 project.yaml 与 local.env，而且第二次不覆盖")
    void prepareWritesConfigOnce(@TempDir Path root) throws IOException {
        assertThat(EnvCli.run(args("prepare", "-p", root.toString()))).isZero();

        Path yaml = root.resolve(".specflow/project.yaml");
        Path env = root.resolve(".specflow/local.env");
        assertThat(yaml).exists();
        assertThat(env).exists();
        assertThat(String.join("\n", Files.readAllLines(yaml))).contains("compile");

        Files.writeString(yaml, "# 我改过的\n");
        assertThat(EnvCli.run(args("prepare", "-p", root.toString()))).isZero();
        assertThat(Files.readString(yaml)).as("已经在了就不该覆盖").isEqualTo("# 我改过的\n");
    }

    @Test
    @DisplayName("down：环境从没起过也要收场成功，而且不编一句「收环境失败」")
    void downOnFreshProjectIsClean(@TempDir Path root) {
        assertThat(EnvCli.run(args("prepare", "-p", root.toString()))).isZero();

        assertThat(EnvCli.run(args("down", "-p", root.toString()))).isZero();
        // 「没起过」不该顺手写出一份没人要的日志目录
        assertThat(root.resolve(".specflow/flow/standalone/env")).doesNotExist();
    }

    @Test
    @DisplayName("down --purge：流程数据删掉，项目配置留着")
    void purgeRemovesRunDataOnly(@TempDir Path root) throws IOException {
        assertThat(EnvCli.run(args("prepare", "-p", root.toString()))).isZero();
        Path flow = root.resolve(".specflow/flow/f1");
        Files.createDirectories(flow);
        Files.writeString(flow.resolve("flow.json"), "{}");
        Files.writeString(flow.resolve("events.jsonl"), "");
        Path sandbox = flow.resolve("sandbox/r1");
        Files.createDirectories(sandbox);
        Files.writeString(sandbox.resolve("compose.yaml"), "name: x\n");

        assertThat(EnvCli.run(args("down", "-p", root.toString(), "--purge"))).isZero();

        assertThat(root.resolve(".specflow/flow")).as("流程数据该没了").doesNotExist();
        assertThat(root.resolve(".specflow/project.yaml")).as("项目配置要留着").exists();
        assertThat(root.resolve(".specflow/local.env")).exists();
    }

    @Test
    @DisplayName("项目目录不存在：exit 2（脚本据此区分「参数错」和「跑失败」）")
    void missingProjectIsUsageError() {
        assertThat(EnvCli.run(args("status", "-p", "Z:\\definitely\\not\\here")))
                .isEqualTo(EnvCli.EXIT_USAGE);
    }
}
