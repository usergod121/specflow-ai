package com.specflow.web;

import com.specflow.exception.SpecflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("项目文件索引")
class ProjectIndexTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("列出相对路径并排序")
    void listsRelativePathsSorted() throws IOException {
        write("src/b.txt");
        write("README.md");
        write("src/a.txt");

        assertThat(new ProjectIndex(root).files())
                .containsExactly("README.md", "src/a.txt", "src/b.txt");
    }

    @Test
    @DisplayName("跳过构建产物与版本控制目录——遍历它们会让界面看起来像卡死了")
    void skipsNoisyDirectories() throws IOException {
        write("src/Main.java");
        write("target/classes/Main.class");
        write("node_modules/pkg/index.js");
        write(".git/config");
        write(".idea/workspace.xml");

        assertThat(new ProjectIndex(root).files()).containsExactly("src/Main.java");
    }

    @Test
    @DisplayName("跳过快照目录——那里面全是文件的旧副本")
    void skipsSnapshots() throws IOException {
        write("src/Main.java");
        write(".specflow/snapshots/20260101-000000-000/files/src/Main.java");
        write(".specflow/project.yaml");

        assertThat(new ProjectIndex(root).files())
                .containsExactly(".specflow/project.yaml", "src/Main.java");
    }

    @Test
    @DisplayName("空目录返回空列表")
    void emptyProject() {
        assertThat(new ProjectIndex(root).files()).isEmpty();
    }

    @Test
    @DisplayName("路径统一使用正斜杠，界面上拿到的是可以直接提交的写法")
    void usesForwardSlashes() throws IOException {
        write("src/main/java/com/demo/Deep.java");

        List<String> files = new ProjectIndex(root).files();

        assertThat(files).containsExactly("src/main/java/com/demo/Deep.java");
        assertThat(files.get(0)).doesNotContain("\\");
    }

    @Test
    @DisplayName("目录联接指回项目根时不会套娃——文件列表不该变成几千条 sub/loop/sub/loop/…")
    void doesNotFollowDirectoryLoops() throws IOException, InterruptedException {
        assumeTrue(isWindows(), "目录联接是 Windows 的东西");

        write("sub/x.txt");
        Path link = root.resolve("sub").resolve("loop");
        if (!makeJunction(link, root)) {
            return;   // 造不出来（没有 cmd、权限不允许）就不假装测过了
        }
        try {
            List<String> files = new ProjectIndex(root).files();

            // 靠真实路径去重，联接不会再被走进去；能列出来的只有真实存在的那一份。
            // 这里必须断言「只有一条」：只要多出一条 sub/loop/sub/x.txt，
            // 就说明去重漏了一格，而多出来的那条谁也说不清是哪来的。
            assertThat(files).containsExactly("sub/x.txt");
        } finally {
            removeJunction(link);
        }
    }

    @Test
    @DisplayName("指向自己子目录的联接：同一份文件只列一次，不会两个路径都列出来")
    void anAliasDoesNotListTheSameFileTwice() throws IOException, InterruptedException {
        assumeTrue(isWindows(), "目录联接是 Windows 的东西");

        write("sub/x.txt");
        write("top.txt");
        // 名字排在 sub 之前，所以遍历会先撞上这个别名
        Path alias = root.resolve("a-alias");
        if (!makeJunction(alias, root.resolve("sub"))) {
            return;
        }
        try {
            List<String> files = new ProjectIndex(root).files();

            assertThat(files).hasSize(2);
            assertThat(files).contains("top.txt");
            // 谁先走到就用谁：别名在前，列的就是别名那条；真实的那条不再重复列一遍
            assertThat(files).anySatisfy(path -> assertThat(path).endsWith("x.txt"));
        } finally {
            removeJunction(alias);
        }
    }

    @Test
    @DisplayName("空目录也要列出来——你在 IDE 里新建一个包，界面上就该看得见它")
    void listsEmptyDirectories() throws IOException {
        write("src/Main.java");
        Files.createDirectories(root.resolve("src/main/java/com/demo"));   // 空包，一个文件都没有

        ProjectIndex.Entries entries = new ProjectIndex(root).entries();

        assertThat(entries.directories()).contains("src/main/java/com/demo");
        // 中间那几层也要在，不然树接不起来
        assertThat(entries.directories())
                .contains("src", "src/main", "src/main/java", "src/main/java/com");
        assertThat(entries.files()).containsExactly("src/Main.java");
    }

    @Test
    @DisplayName("跳过的目录即使空着也不出现——target/.git/快照目录不该混进树里")
    void skippedDirectoriesAreNotListed() throws IOException {
        Files.createDirectories(root.resolve("target/classes"));
        Files.createDirectories(root.resolve(".git/objects"));
        Files.createDirectories(root.resolve(".specflow/snapshots/20260101"));
        Files.createDirectories(root.resolve(".specflow/templates"));
        Files.createDirectories(root.resolve("src"));

        ProjectIndex.Entries entries = new ProjectIndex(root).entries();

        // .specflow 自己是留着的（里面有 project.yaml 和模板，用户可能想直接打开它），
        // 只有 snapshots 那棵子树被跳过——那是文件的旧副本
        assertThat(entries.directories())
                .containsExactly(".specflow", ".specflow/templates", "src");
    }

    @Test
    @DisplayName("目录清单里不含项目根自己")
    void rootItselfIsNotListed() throws IOException {
        Files.createDirectories(root.resolve("src"));

        assertThat(new ProjectIndex(root).entries().directories()).doesNotContain("", ".");
    }

    @Test
    @DisplayName("两次扫描之间在磁盘上加了文件/空包，第二次就看得见——这里不能有缓存")
    void seesChangesMadeOnDisk() throws IOException {
        ProjectIndex index = new ProjectIndex(root);
        assertThat(index.entries().files()).isEmpty();
        assertThat(index.entries().directories()).isEmpty();

        write("src/main/java/com/demo/New.java");
        Files.createDirectories(root.resolve("brand-new-pkg"));

        assertThat(index.entries().files()).containsExactly("src/main/java/com/demo/New.java");
        assertThat(index.entries().directories()).contains("brand-new-pkg");
    }

    @Test
    @DisplayName("项目目录被删掉/挪走时明说，而不是给一个空列表")
    void missingRootIsReported() throws IOException {
        Path gone = root.resolve("gone");
        Files.createDirectories(gone);
        ProjectIndex index = new ProjectIndex(gone);
        Files.delete(gone);

        assertThatThrownBy(index::entries).isInstanceOf(SpecflowException.class)
                .hasMessageContaining("已经不在了");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean makeJunction(Path link, Path target) throws IOException, InterruptedException {
        return run("mklink", "/J", link.toString(), target.toString());
    }

    /** 拆联接只能走 rmdir，直接删目录会连目标一起动。 */
    private static void removeJunction(Path link) throws IOException, InterruptedException {
        run("rmdir", link.toString());
    }

    private static boolean run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("cmd", "/c"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }

    private void write(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }
}
