package com.specflow.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("认出项目是什么")
class ProjectScannerTest {

    @TempDir
    Path root;

    private final ProjectScanner scanner = new ProjectScanner();

    private void touch(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, "x");
    }

    @Test
    @DisplayName("认不出的目录：什么都不猜，也不假装配过")
    void unknownDirectory() {
        ProjectScanner.Scan scan = scanner.scan(root);

        assertThat(scan.manifests()).isEmpty();
        assertThat(scan.compileCommand()).isNull();
        assertThat(scan.configured()).isFalse();
    }

    @Test
    @DisplayName("Maven 项目：认出语言，并给出编译命令")
    void mavenProject() throws IOException {
        touch("pom.xml");

        ProjectScanner.Scan scan = scanner.scan(root);

        assertThat(scan.manifests()).singleElement()
                .satisfies(m -> assertThat(m.language()).contains("Maven"));
        assertThat(scan.compileCommand()).isEqualTo("mvn -q -DskipTests compile");
    }

    @Test
    @DisplayName("有 wrapper 就用 wrapper——那是唯一能顺手解决「版本对不对」的地方")
    void prefersWrapper() throws IOException {
        touch("pom.xml");
        touch("mvnw");

        ProjectScanner.Scan scan = scanner.scan(root);

        // 具体是 mvnw 还是 mvnw.cmd 看平台，这里只断言「用了 wrapper」
        assertThat(scan.compileCommand()).startsWith("mvnw").contains("-DskipTests compile");
        // wrapper 不是「项目是什么」的说明，所以不计入清单文件
        assertThat(scan.manifests()).singleElement()
                .satisfies(m -> assertThat(m.file()).isEqualTo("pom.xml"));
    }

    @Test
    @DisplayName("只有 mvnw.cmd（Windows 生成的）也认")
    void recognizesCmdWrapper() throws IOException {
        touch("pom.xml");
        touch("mvnw.cmd");

        assertThat(scanner.scan(root).compileCommand()).contains("mvnw.cmd");
    }

    @Test
    @DisplayName("Gradle 项目")
    void gradleProject() throws IOException {
        touch("build.gradle");

        ProjectScanner.Scan scan = scanner.scan(root);

        assertThat(scan.compileCommand()).contains("compileJava");
        assertThat(scan.manifests()).singleElement()
                .satisfies(m -> assertThat(m.language()).contains("Gradle"));
    }

    @Test
    @DisplayName("多语言的仓库：清单文件都列出来，命令取第一条能给的")
    void mixedRepository() throws IOException {
        touch("pom.xml");
        touch("package.json");

        ProjectScanner.Scan scan = scanner.scan(root);

        assertThat(scan.manifests()).extracting(ProjectScanner.ManifestFile::file)
                .containsExactly("pom.xml", "package.json");
        assertThat(scan.compileCommand()).isEqualTo("mvn -q -DskipTests compile");
    }

    @Test
    @DisplayName("认不出的构建文件不硬凑——列出来反而让人以为工具认出了什么")
    void ignoresUnrecognizedFiles() throws IOException {
        touch("settings.gradle");
        touch("build.xml");
        touch("Dockerfile");

        assertThat(scanner.scan(root).manifests()).isEmpty();
    }

    @Test
    @DisplayName("真的配过 specflow 的目录要认出来——界面得知道该不该提示初始化")
    void detectsExistingConfig() throws IOException {
        Files.createDirectories(root.resolve(ProjectConfigLoader.CONFIG_DIR));
        Files.writeString(root.resolve(ProjectConfigLoader.CONFIG_DIR)
                .resolve(ProjectConfigLoader.CONFIG_FILE), "build:\n  compile: \"mvn compile\"\n");

        assertThat(scanner.scan(root).configured()).isTrue();
    }

    @Test
    @DisplayName("空壳的 project.yaml 不算「配过」——否则界面上不会出现「初始化」，用户就没有出口了")
    void emptyConfigIsNotConfigured() throws IOException {
        Path config = root.resolve(ProjectConfigLoader.CONFIG_DIR)
                .resolve(ProjectConfigLoader.CONFIG_FILE);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "");

        assertThat(scanner.scan(root).configured()).isFalse();
    }

    @Test
    @DisplayName("扫的时候不往目录里写任何东西——打开一个陌生项目等于只读浏览")
    void scanIsReadOnly() throws IOException {
        touch("pom.xml");
        int before = Files.list(root).toList().size();

        scanner.scan(root);

        assertThat(Files.list(root).toList()).hasSize(before);
    }
}
