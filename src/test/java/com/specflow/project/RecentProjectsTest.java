package com.specflow.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("最近打开的项目")
class RecentProjectsTest {

    @TempDir
    Path temp;

    private RecentProjects registry() {
        return new RecentProjects(temp.resolve("projects.json"));
    }

    private Path project(String name) throws IOException {
        Path dir = temp.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    @DisplayName("没记过任何东西时是空的，不是报错")
    void emptyAtFirst() {
        assertThat(registry().list()).isEmpty();
    }

    @Test
    @DisplayName("记一笔之后按最近的在前")
    void newestFirst() throws IOException {
        RecentProjects registry = registry();
        registry.remember(project("alpha"));
        registry.remember(project("beta"));

        assertThat(registry.list()).extracting(RecentProjects.Entry::name)
                .containsExactly("beta", "alpha");
    }

    @Test
    @DisplayName("再打开一次已有的项目是把它挪到前面，不是多出一条")
    void rememberingAgainMovesItUp() throws IOException {
        RecentProjects registry = registry();
        Path alpha = project("alpha");
        registry.remember(alpha);
        registry.remember(project("beta"));
        registry.remember(alpha);

        List<RecentProjects.Entry> entries = registry.list();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).name()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("只留最近 15 个——再长就不是「最近」而是「全部」了")
    void capsTheList() throws IOException {
        RecentProjects registry = registry();
        for (int i = 0; i < 20; i++) {
            registry.remember(project("p" + i));
        }

        List<RecentProjects.Entry> entries = registry.list();
        assertThat(entries).hasSize(15);
        assertThat(entries.get(0).name()).isEqualTo("p19");
        assertThat(entries).extracting(RecentProjects.Entry::name).doesNotContain("p0");
    }

    @Test
    @DisplayName("忘掉一条")
    void forgets() throws IOException {
        RecentProjects registry = registry();
        Path alpha = project("alpha");
        registry.remember(alpha);
        registry.remember(project("beta"));

        registry.forget(alpha.toString());

        assertThat(registry.list()).extracting(RecentProjects.Entry::name).containsExactly("beta");
    }

    @Test
    @DisplayName("清单文件被改坏了也只当空的——为一个便利清单把工具锁死不值得")
    void survivesCorruptFile() throws IOException {
        Path file = temp.resolve("projects.json");
        Files.writeString(file, "{ 这不是 json");

        assertThat(new RecentProjects(file).list()).isEmpty();
        // 而且还能继续用：下一次 remember 会把它写回正常内容
        new RecentProjects(file).remember(project("alpha"));
        assertThat(new RecentProjects(file).list()).hasSize(1);
    }

    @Test
    @DisplayName("同一个目录的另一种写法只算一条——按字面比会并排出现三条一样的，还删不掉")
    void differentSpellingsOfTheSameDirectoryAreOneEntry() throws IOException {
        RecentProjects registry = registry();
        Path alpha = project("alpha");
        Path upper = Path.of(alpha.toString().toUpperCase(Locale.ROOT));
        // 大小写不敏感的文件系统上它们才是同一个目录；区分大小写的系统本来就是两条
        assumeTrue(Files.isSameFile(alpha, upper), "这个文件系统区分大小写，跳过");

        registry.remember(alpha);
        registry.remember(upper);

        assertThat(registry.list()).hasSize(1);
        // 删的时候也一样：给哪种写法都得把它删掉
        registry.forget(alpha.toString());
        assertThat(registry.list()).isEmpty();
    }

    @Test
    @DisplayName("清单里有一条坏路径也只影响那一条，不会让 list 炸掉")
    void aBadEntryDoesNotBreakTheList() throws IOException {
        Path file = temp.resolve("projects.json");
        Files.writeString(file, """
                [{"path":"C:\\\\bad\\u0001x","name":"x","lastOpened":"t"},
                 {"path":"%s","name":"alpha","lastOpened":"t"}]
                """.formatted(project("alpha").toString().replace("\\", "\\\\")));

        RecentProjects registry = new RecentProjects(file);

        assertThat(registry.list()).hasSize(2);
        // 比较和删除都得扛得住那条坏路径
        registry.forget("C:\\bad\u0001x");
        assertThat(registry.list()).hasSize(1);
    }

    @Test
    @DisplayName("存的是绝对路径，重新读出来还是同一个")
    void storesAbsolutePaths() throws IOException {
        RecentProjects registry = registry();
        Path alpha = project("alpha");
        registry.remember(alpha.resolve("."));

        assertThat(registry.list()).singleElement()
                .satisfies(e -> assertThat(e.path()).isEqualTo(alpha.toAbsolutePath().normalize().toString()));
    }
}
