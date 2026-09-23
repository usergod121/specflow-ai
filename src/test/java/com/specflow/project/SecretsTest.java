package com.specflow.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("密钥的取用")
class SecretsTest {

    private static final String KEY = "SPECFLOW_API_KEY";

    @TempDir
    Path root;

    private void writeLocalEnv(String content) throws IOException {
        Path file = root.resolve(Secrets.LOCAL_ENV);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 假的环境，只有指定的这几个变量有值。 */
    private static java.util.function.Function<String, String> env(String name, String value) {
        return actual -> actual.equals(name) ? value : null;
    }

    @Test
    @DisplayName("写进 .specflow/local.env 就读得到——这样才不用每次启动都手输")
    void readsFromFile() throws IOException {
        writeLocalEnv(KEY + "=sk-from-file\n");

        assertThat(Secrets.lookup(root, KEY, env(null, null))).contains("sk-from-file");
    }

    @Test
    @DisplayName("环境变量优先——临时换一个 key 不该还要去改文件")
    void environmentWinsOverFile() throws IOException {
        writeLocalEnv(KEY + "=sk-from-file\n");

        assertThat(Secrets.lookup(root, KEY, env(KEY, "sk-from-env"))).contains("sk-from-env");
    }

    @Test
    @DisplayName("环境变量是个空白值时，退回文件")
    void blankEnvironmentFallsBackToFile() throws IOException {
        writeLocalEnv(KEY + "=sk-from-file\n");

        assertThat(Secrets.lookup(root, KEY, env(KEY, "   "))).contains("sk-from-file");
    }

    @Test
    @DisplayName("注释行和别的键都不算数")
    void skipsCommentsAndOtherKeys() throws IOException {
        writeLocalEnv("# " + KEY + "=sk-commented\nOTHER=value\n" + KEY + "=sk-real\n");

        assertThat(Secrets.lookup(root, KEY, env(null, null))).contains("sk-real");
    }

    @Test
    @DisplayName("值外面包引号也认——照 .env 的习惯写引号，不该把引号带进密钥")
    void stripsQuotes() throws IOException {
        writeLocalEnv(KEY + "=\"sk-double\"\n");
        assertThat(Secrets.lookup(root, KEY, env(null, null))).contains("sk-double");

        writeLocalEnv(KEY + "='sk-single'\n");
        assertThat(Secrets.lookup(root, KEY, env(null, null))).contains("sk-single");
    }

    @Test
    @DisplayName("值后面的行尾空格不算密钥的一部分")
    void stripsTrailingSpaces() throws IOException {
        writeLocalEnv(KEY + "=sk-padded   \n");

        assertThat(Secrets.lookup(root, KEY, env(null, null))).contains("sk-padded");
    }

    @Test
    @DisplayName("没文件、空值、没有这个键，都安静地返回空")
    void missingIsEmpty() throws IOException {
        assertThat(Secrets.lookup(root, KEY, env(null, null))).isEmpty();

        writeLocalEnv(KEY + "=\n");
        assertThat(Secrets.lookup(root, KEY, env(null, null))).isEmpty();

        writeLocalEnv("OTHER=value\n");
        assertThat(Secrets.lookup(root, KEY, env(null, null))).isEmpty();

        writeLocalEnv("=value\n");
        assertThat(Secrets.lookup(root, KEY, env(null, null))).isEmpty();
    }
}
