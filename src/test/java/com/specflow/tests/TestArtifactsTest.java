package com.specflow.tests;

import com.specflow.exception.SpecflowException;
import com.specflow.patch.PatchBlock;
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
 * 测试产物的落盘边界。
 *
 * <p>这一层守的是两件事，都不能靠「模型会守规矩」：
 * <ul>
 *   <li><b>只能写进 {@code tools/<时间戳>/}</b>——产品代码由开发阶段那一套改。
 *       测试代码能改产品代码，等于自己给自己判卷；</li>
 *   <li><b>高危命令不落盘、不执行</b>——脚本是模型写的，而它是在这台机器上真跑的。</li>
 * </ul>
 */
@DisplayName("测试产物的落盘")
class TestArtifactsTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("产物落在 tools/<时间戳>/ 下，入口脚本按平台取名")
    void createsTimestampedDirectory() {
        TestArtifacts artifacts = TestArtifacts.create(root);

        assertThat(artifacts.relative()).startsWith("tools/");
        assertThat(root.resolve(artifacts.relative())).isDirectory();
        assertThat(artifacts.entry()).startsWith(artifacts.relative() + "/");
        assertThat(artifacts.entry()).endsWith(System.getProperty("os.name")
                .toLowerCase().contains("win") ? "run.cmd" : "run.sh");
    }

    /**
     * 两次运行撞在同一秒里时不能共用一个目录：上一次那条失败清单指向的测试代码
     * 正好被这一次覆盖掉，事后谁也说不清「当时跑的到底是哪一份」。
     */
    @Test
    @DisplayName("同一秒里的第二次运行换一个目录，不覆盖上一次的产物")
    void doesNotReuseAnExistingDirectory() throws IOException {
        TestArtifacts first = TestArtifacts.create(root);
        Files.writeString(root.resolve(first.relative()).resolve("a.txt"), "第一次的产物");

        TestArtifacts second = TestArtifacts.create(root);

        assertThat(second.relative()).isNotEqualTo(first.relative());
        assertThat(root.resolve(first.relative()).resolve("a.txt")).exists();
    }

    @Test
    @DisplayName("写进去的文件按补丁块的路径落盘，返回值是相对项目根的路径")
    void writesFiles() throws IOException {
        TestArtifacts artifacts = TestArtifacts.create(root);

        List<String> written = artifacts.write(List.of(block(0,
                artifacts.relative() + "/Check.java", "class Check {}\n")));

        assertThat(written).containsExactly(artifacts.relative() + "/Check.java");
        assertThat(Files.readString(root.resolve(written.get(0)))).isEqualTo("class Check {}\n");
    }

    /**
     * 白名单是这一层的核心：路径只要不在 {@code tools/<时间戳>/} 里就拒绝。
     *
     * <p>越界的那个文件<b>一个字节都不写</b>。前面已经写下去的那几个由调用方整批删
     * （见 {@code TestAgent}）——测试产物要么是完整的一份，要么一个都不留，
     * 半份留在磁盘上的样子最像「跑过了」。
     */
    @Test
    @DisplayName("写到产物目录之外的文件一律拒绝，那个文件一个字节都不写")
    void refusesPathsOutsideTheTestDirectory() {
        TestArtifacts artifacts = TestArtifacts.create(root);

        assertThatThrownBy(() -> artifacts.write(List.of(
                block(0, artifacts.relative() + "/Ok.java", "class Ok {}\n"),
                block(1, "src/main/java/com/demo/Foo.java", "class Foo {}\n"))))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("只能写在")
                .hasMessageContaining("src/main/java/com/demo/Foo.java");

        assertThat(root.resolve("src/main/java/com/demo/Foo.java")).doesNotExist();
    }

    @Test
    @DisplayName("绝对路径与 .. 穿越同样拒绝（白名单只管相对路径）")
    void refusesEscapingPaths() {
        TestArtifacts artifacts = TestArtifacts.create(root);

        assertThatThrownBy(() -> artifacts.write(List.of(
                block(0, artifacts.relative() + "/../../evil.txt", "x"))))
                .isInstanceOf(SpecflowException.class);
        assertThat(root.resolve("evil.txt")).doesNotExist();
    }

    @Test
    @DisplayName("给了 SEARCH 锚点的块拒绝：产物目录是空的，没有旧内容可锚定")
    void refusesAnchoredBlocks() {
        TestArtifacts artifacts = TestArtifacts.create(root);

        assertThatThrownBy(() -> artifacts.write(List.of(
                new PatchBlock(0, artifacts.relative() + "/Check.java", "class Old {}", "class New {}"))))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("SEARCH");
    }

    @Test
    @DisplayName("空内容的块拒绝：那是模型少写了一段，不是「建个空文件」")
    void refusesEmptyBlocks() {
        TestArtifacts artifacts = TestArtifacts.create(root);

        assertThatThrownBy(() -> artifacts.write(List.of(
                block(0, artifacts.relative() + "/Check.java", "   \n"))))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("空的");
    }

    @Test
    @DisplayName("高危命令不落盘：整批拒绝，并把命中的原话报出来")
    void refusesDangerousCommands() {
        TestArtifacts artifacts = TestArtifacts.create(root);

        for (String line : List.of(
                "sudo apt-get install -y mysql-client",
                "rm -rf /tmp/specflow /",
                "rm -fr ./build",
                "dd if=/dev/zero of=/dev/sda",
                "mkfs.ext4 /dev/sdb1",
                "docker run --privileged my-image",
                "docker run -v /:/host my-image",
                "cp -r $HOME/.ssh ./keys",
                "docker run -v /var/run/docker.sock:/var/run/docker.sock img")) {
            assertThatThrownBy(() -> artifacts.write(List.of(
                    block(0, artifacts.relative() + "/run.cmd", line + "\n"))))
                    .as("这一行必须被拦下来：%s", line)
                    .isInstanceOf(SpecflowException.class)
                    .hasMessageContaining("高危命令");
        }
        assertThat(root.resolve(artifacts.relative()).resolve("run.cmd")).doesNotExist();
    }

    /**
     * 大小写不是躲得过去的东西：Windows 的命令行根本不认大小写，
     * {@code SUDO} 和 {@code sudo} 是同一个命令。只拦小写等于留了一条
     * 「把字母改大写」的路——而模型未必是故意的，它照抄一段别人写的脚本就会带上大写。
     */
    @Test
    @DisplayName("高危命令的大小写变体一样拦：SUDO、Rm -Rf、--PRIVILEGED")
    void refusesDangerousCommandsInAnyCase() {
        for (String line : List.of(
                "SUDO apt-get install -y mysql-client",
                "Sudo rm -rf /",
                "Rm -Rf /tmp/x",
                "RM -RF /tmp/x",
                "MKFS.EXT4 /dev/sdb1",
                "docker run --PRIVILEGED img",
                "DOCKER.SOCK",
                "echo %userprofile%",
                "rm -f -r /tmp/x",
                "rm -R --force /tmp/x",
                "rm --recursive --force /")) {
            assertThat(TestArtifacts.forbidden(line))
                    .as("换个大小写／换个旗标顺序就不该躲过去：%s", line)
                    .isNotNull();
        }
    }

    /**
     * 这一条是<b>实测跑出来过</b>的那个洞：假模型给出
     * {@code del /s /q "src\main\java\com\demo\*.java"} + {@code exit /b 0}，
     * 旧判据只认 {@code rm}，于是整批产物落地并被宿主执行——产品源码被删掉，
     * 而这次运行报的是「通过」。所以 Windows 那一套删除命令必须一并收。
     */
    @Test
    @DisplayName("Windows 的删除命令也拦：del /s /q 产品源码、rmdir /s /q、Remove-Item -Recurse")
    void refusesWindowsDeletionCommands() {
        for (String line : List.of(
                "del /s /q \"src\\main\\java\\com\\demo\\*.java\"",
                "DEL /S /Q \"src\\main\\java\\com\\demo\\*.java\"",
                "erase /s /q target\\classes\\*.*",
                "rmdir /s /q C:\\",
                "rd /s /q C:\\Users\\Lenovo",
                "powershell -Command Remove-Item -Recurse -Force C:\\tmp",
                "Remove-Item -Recurse -Force src/main/java/com/demo")) {
            assertThat(TestArtifacts.forbidden(line))
                    .as("删除类命令 + 沾上产品目录／盘根，一律拒：%s", line)
                    .isNotNull();
        }
    }

    /**
     * 命令被换行切开时，单看哪一行都不像：{@code rm -r} 一行、{@code -f /} 一行。
     * shell 会在执行前把续行符和换行一起吃掉，所以那是<b>一条</b>命令——
     * 判据必须先把它们接回去（cmd 用 {@code ^}，sh 用 {@code \}）。
     */
    @Test
    @DisplayName("跨行拆开的命令接回去之后照样拦（cmd 的 ^ 与 sh 的 \\ 续行）")
    void refusesCommandsSplitAcrossLines() {
        assertThat(TestArtifacts.forbidden("rm -r ^\r\n-f /\r\n")).isNotNull();
        assertThat(TestArtifacts.forbidden("rm -r \\\n-f /\n")).isNotNull();
        // 这两个才是非接回去不可的：前半截单看一点问题都没有（`del` / `rm` 后面什么都没有），
        // 旗标和目标全在下一行。不接回去的话每一行都是无害的
        assertThat(TestArtifacts.forbidden("del ^\r\n/s /q \"src\\main\\java\\com\\demo\\*.java\"\r\n"))
                .isNotNull();
        assertThat(TestArtifacts.forbidden("rm ^\n-rf /\n")).isNotNull();
    }

    /**
     * 判据要挂在真正落盘的那条路上才算数：只测 {@code forbidden} 的话，
     * 一个「忘了在 write 里调用它」的改动不会被任何断言抓到。
     *
     * <p>前面那个块已经落盘了——{@code write} 是<b>逐个</b>校验、逐个写的，
     * 整批收干净是调用方的事（见 {@code TestAgentTest.refusesDangerousScript}：
     * 它断言 {@code tools/} 下什么都不剩）。这里只管一件事：<b>带毒的那一份不许落地</b>。
     */
    @Test
    @DisplayName("高危写法在 write 这一层就被拦住：那个文件一个字节都不落盘")
    void refusesDangerousContentBeforeWritingAnything() {
        TestArtifacts artifacts = TestArtifacts.create(root);

        assertThatThrownBy(() -> artifacts.write(List.of(
                block(0, artifacts.relative() + "/Check.java", "class Check {}\n"),
                block(1, artifacts.relative() + "/run.cmd",
                        "@echo off\r\ndel /s /q \"src\\main\\java\\com\\demo\\*.java\"\r\nexit /b 0\r\n"))))
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("高危命令");

        assertThat(root.resolve(artifacts.relative()).resolve("run.cmd"))
                .as("删产品源码的那一行不许落地，更不许被执行").doesNotExist();
        assertThat(Files.exists(root.resolve("src/main/java/com/demo/Foo.java"))).isFalse();
    }

    /**
     * 拦错了的代价同样是实的：一份正常的测试脚本被拒，用户会以为工具坏了。
     * 所以下面这些看着像、其实无害的写法一条都不能拦。
     */
    @Test
    @DisplayName("看着像的普通写法不能误拦：add、rm 一个文件、删自己的临时文件、-v 挂项目内的目录")
    void doesNotBlockOrdinaryLines() {
        assertThat(TestArtifacts.forbidden("// add a simple check for the parser")).isNull();
        assertThat(TestArtifacts.forbidden("rm build/out.txt")).isNull();
        assertThat(TestArtifacts.forbidden("docker run -v /tmp/x:/y img")).isNull();
        assertThat(TestArtifacts.forbidden("cp -r target/classes ./tmp")).isNull();
        assertThat(TestArtifacts.forbidden("echo \"checked: address, hidden\"")).isNull();
        // 测试代码里到处都有的两个词：Python 的 del 语句、Java 的 Files.delete
        assertThat(TestArtifacts.forbidden("del cases[0]")).isNull();
        assertThat(TestArtifacts.forbidden("os.rmdir(path)")).isNull();
        assertThat(TestArtifacts.forbidden("Files.deleteIfExists(out)")).isNull();
    }

    /**
     * Windows 的文件系统不认大小写：模型把 {@code tools} 写成 {@code TOOLS}，
     * 文件落在同一个目录里，而 {@code entry()} 用的是真实拼写。
     * 旧代码在 {@code write} 的返回值里原样带回模型写的那份拼写，
     * 于是上游那句「产物里有没有入口脚本」对不上——整批被拒，
     * 报出来的原因还是「没给入口脚本」这种和真实情况无关的话。
     */
    @Test
    @DisplayName("路径大小写变体落的是同一个目录：返回的路径按产物目录的真实拼写")
    void acceptsCaseVariantPaths() throws IOException {
        // 只在 Windows 上成立：别的系统上 TOOLS/ 真的是另一个目录，那时该拒还是要拒
        org.junit.jupiter.api.Assumptions.assumeTrue(EntryScripts.WINDOWS,
                "路径大小写不敏感只发生在 Windows 上");

        TestArtifacts artifacts = TestArtifacts.create(root);
        String upper = artifacts.relative().toUpperCase(java.util.Locale.ROOT) + "/run.cmd";

        List<String> written = artifacts.write(List.of(block(0, upper, "@echo off\r\nexit /b 0\r\n")));

        assertThat(written).containsExactly(artifacts.entry());
        assertThat(root.resolve(artifacts.entry())).exists();
    }

    @Test
    @DisplayName("delete 把整个产物目录收干净（回滚时要连着它一起清）")
    void deleteRemovesEverything() throws IOException {
        TestArtifacts artifacts = TestArtifacts.create(root);
        Path dir = root.resolve(artifacts.relative());
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/a.txt"), "x");

        artifacts.delete();

        assertThat(dir).doesNotExist();
    }

    private static PatchBlock block(int index, String path, String content) {
        return new PatchBlock(index, path, "", content);
    }
}
