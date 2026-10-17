package com.specflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code project.yaml} 是盘上的真相，<b>不是打开项目那一刻的快照</b>。
 *
 * <h2>这一组钉的是一个"改了没反应"的 bug</h2>
 * 原来配置只在 {@code Project.open} 时读一遍。于是界面上「保存编译命令」写进
 * {@code build.compile} 之后，① 的闸读到的还是旧值（空串），点「冻结」被拦：
 * 「编译命令还是空的」。文件明明是对的——<b>只是没人去读第二遍</b>。
 * 用户唯一的出路是重开项目，而界面上没有那个按钮。
 *
 * <p>这类 bug 在盘上看不出来、在日志里也看不出来，只有"改完立刻问它一遍"才发现得了，
 * 所以这里就这么问。
 */
class ProjectTest {

    private static Path prepared(Path root, String yaml) throws IOException {
        Path specflow = root.resolve(".specflow");
        Files.createDirectories(specflow);
        Files.writeString(specflow.resolve("project.yaml"), yaml);
        return root;
    }

    @Test
    @DisplayName("写完再读：compile / model 立刻是新值，不用重开项目")
    void readsAgainAfterFileChanges(@TempDir Path root) throws IOException {
        Project project = Project.open(prepared(root, "build:\n  compile: \"\"\n"));

        assertThat(project.compile()).isEmpty();
        assertThat(project.llm().model()).isEqualTo(Project.LlmConfig.DEFAULT_MODEL);

        // 模拟「保存编译命令」那一步：只改盘，不碰这个 Project 对象
        Files.writeString(root.resolve(".specflow/project.yaml"), """
                build:
                  compile: "javac -d out src/Main.java"
                llm:
                  model: "deepseek-reasoner"
                """);

        assertThat(project.compile()).isEqualTo("javac -d out src/Main.java");
        assertThat(project.llm().model()).isEqualTo("deepseek-reasoner");
    }

    @Test
    @DisplayName("文件被删掉：留着上一次读到的那份，不会把好好的配置变成空配置")
    void keepsLastKnownWhenFileVanishes(@TempDir Path root) throws IOException {
        Project project = Project.open(prepared(root, "build:\n  compile: \"mvn -q compile\"\n"));
        assertThat(project.compile()).isEqualTo("mvn -q compile");

        Files.delete(root.resolve(".specflow/project.yaml"));

        assertThat(project.compile()).as("读不出来时不该突然变空").isEqualTo("mvn -q compile");
    }

    @Test
    @DisplayName("一次都没读到过（文件本来就不在）：给一份默认值，不抛")
    void defaultsWhenNeverRead(@TempDir Path root) throws IOException {
        Project project = Project.open(prepared(root, "build:\n  compile: \"\"\n"));
        Files.delete(root.resolve(".specflow/project.yaml"));

        Project fresh = Project.open(root);

        assertThat(fresh.compile()).isEmpty();
        assertThat(fresh.llm().model()).isEqualTo(Project.LlmConfig.DEFAULT_MODEL);
        assertThat(fresh.prepared()).as(".specflow 还在").isTrue();
    }
}
