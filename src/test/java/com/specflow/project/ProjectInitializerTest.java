package com.specflow.project;

import com.specflow.exception.SpecflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 铺项目骨架。
 *
 * <p>盯两件相反的事：<b>已经写好的东西一个字都不能动</b>，
 * 而<b>空壳必须能修好</b>——空壳留着不管，用户就会卡在「初始化每次都成功、
 * 状态一次都没变」的那个圈里。
 */
@DisplayName("初始化项目")
class ProjectInitializerTest {

    @TempDir
    Path root;

    private ProjectInitializer.Result initialize() {
        return new ProjectInitializer().initialize(root, "mvn -q -DskipTests compile");
    }

    private Path configFile() {
        return root.resolve(ProjectConfigLoader.CONFIG_DIR).resolve(ProjectConfigLoader.CONFIG_FILE);
    }

    @Test
    @DisplayName("新目录一次铺齐：配置、内置模板、示例 spec、以及挡住密钥的 .gitignore")
    void writesTheWholeSkeleton() throws IOException {
        ProjectInitializer.Result result = initialize();

        assertThat(result.written()).containsExactlyInAnyOrder(
                ".specflow/project.yaml", ".specflow/templates/spring-backend.yaml",
                ".specflow/templates/fix-bug.yaml", "spec.yaml", ".gitignore");
        assertThat(configFile()).exists();
        // 骨架里的说明得说清「密钥放哪儿」——这条是用户唯一会主动读的地方
        assertThat(Files.readString(configFile())).contains(".specflow/local.env");
    }

    /**
     * 密钥就写在项目目录里，而 {@code .gitignore} 是每个项目自己的文件：
     * 刚 init 出来的新项目通常还没有它，一次 {@code git add .} 就把密钥提交上去了，
     * 而这个错误没有第二次机会。所以这两条规矩由 init 补上。
     */
    @Test
    @DisplayName("新项目会补一份 .gitignore，挡住 .specflow/local.env 和项目根下的 *.env")
    void writesGitignoreForSecrets() throws IOException {
        initialize();

        String ignore = Files.readString(root.resolve(".gitignore"));
        assertThat(ignore).contains(".specflow/local.env").contains("*.env");
        assertThat(ignore).contains("不进版本库");
    }

    @Test
    @DisplayName("再铺一次不会把 .gitignore 重复追加一遍（幂等）")
    void gitignoreIsAppendedOnlyOnce() throws IOException {
        initialize();
        String once = Files.readString(root.resolve(".gitignore"));

        assertThat(initialize().written()).isEmpty();
        assertThat(Files.readString(root.resolve(".gitignore"))).isEqualTo(once);
    }

    @Test
    @DisplayName("用户自己写的 .gitignore 一个字都不动，只在后面补一条")
    void keepsTheUserGitignore() throws IOException {
        Files.writeString(root.resolve(".gitignore"), "# 我自己写的\ntarget/\n");

        initialize();

        String ignore = Files.readString(root.resolve(".gitignore"));
        assertThat(ignore).startsWith("# 我自己写的\ntarget/\n");
        assertThat(ignore).contains("*.env");
    }

    @Test
    @DisplayName("用户那份 .gitignore 里已经挡了密钥，就一个字都不写")
    void leavesACompleteGitignoreAlone() throws IOException {
        String mine = "*.env\n.specflow/local.env\n";
        Files.writeString(root.resolve(".gitignore"), mine);

        ProjectInitializer.Result result = initialize();

        assertThat(result.written()).doesNotContain(".gitignore");
        assertThat(Files.readString(root.resolve(".gitignore"))).isEqualTo(mine);
    }

    @Test
    @DisplayName("再点一次初始化什么都不写，也不会覆盖")
    void secondRunWritesNothing() {
        initialize();

        assertThat(initialize().written()).isEmpty();
    }

    @Test
    @DisplayName("空壳的 project.yaml 会被修好——不然项目永远停在「还没配过」那一屏")
    void rebuildsEmptyConfig() throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "");

        assertThat(initialize().written()).contains(".specflow/project.yaml");
        assertThat(Files.readString(configFile())).contains("compile:");
    }

    @Test
    @DisplayName("只有注释的 project.yaml 同样被修好——加载时它也不算内容")
    void rebuildsCommentOnlyConfig() throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "# 占位\n");

        assertThat(initialize().written()).contains(".specflow/project.yaml");
        assertThat(new ProjectConfigLoader().load(root).build().compile()).isNotBlank();
    }

    @Test
    @DisplayName("只剩一个 BOM 的 project.yaml 同样被修好——它在编辑器里看着是全空的")
    void rebuildsByteOrderMarkOnlyConfig() throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "\uFEFF");

        assertThat(initialize().written()).contains(".specflow/project.yaml");
        assertThat(new ProjectConfigLoader().load(root).build().compile()).isNotBlank();
    }

    @Test
    @DisplayName("已经写了内容的配置一个字都不动")
    void neverTouchesARealConfig() throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), "build:\n  compile: \"ant\"\n");

        assertThat(initialize().written()).doesNotContain(".specflow/project.yaml");
        assertThat(Files.readString(configFile())).isEqualTo("build:\n  compile: \"ant\"\n");
    }

    @Test
    @DisplayName("project.yaml 是个目录时明说，而不是每次都假装成功、让用户一直点下去")
    void reportsDirectoryInPlaceOfConfig() throws IOException {
        Files.createDirectories(configFile());

        assertThatThrownBy(this::initialize).isInstanceOf(SpecflowException.class)
                .hasMessageContaining(".specflow/project.yaml")
                .hasMessageContaining("目录");
    }

    @Test
    @DisplayName("spec.yaml 是个目录时同样明说")
    void reportsDirectoryInPlaceOfSpec() throws IOException {
        Files.createDirectories(root.resolve("spec.yaml"));

        assertThatThrownBy(this::initialize).isInstanceOf(SpecflowException.class)
                .hasMessageContaining("spec.yaml");
    }
}
