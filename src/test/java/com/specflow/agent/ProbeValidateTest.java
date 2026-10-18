package com.specflow.agent;

import com.specflow.core.Project;
import com.specflow.core.Progress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探针产物的校验：<b>允许"还没建的文件"进写白名单</b>。
 *
 * <h2>这一组钉的是用户连报的两个现象（同一个根因）</h2>
 * 「API 文档要求新增 DTO/VO，探针只给 controller→mapper」+「重新跑探针，旧的产物没被覆盖」。
 * 根因是校验把"不存在的路径"直接判死：
 * <pre>
 * for (file : files) if (!isRegularFile(root.resolve(file))) → REJECTED「探针不许写没读到过的路径」
 * </pre>
 * 模型一旦按提示词把新文件列出来就被驳回，重试三次后整份 REJECTED —— <b>盘上那份就永远不被覆盖</b>；
 * 而模型从驳回话术里学会了"只列已存在的"，于是又回到只改不建。
 *
 * <p>现在的判据分成两栏，各自清楚：
 * <ul>
 *   <li>{@code files}（要读的）：<b>必须真存在</b>；</li>
 *   <li>{@code changes}（写白名单）：已存在的必须先出现在 files 里；<b>还不存在的算"新建"，允许</b>。</li>
 * </ul>
 */
class ProbeValidateTest {

    private static String validate(Path root, Probe.Artifacts artifacts) throws Exception {
        Project project = Project.open(root);
        Probe probe = new Probe(project, null, Progress.quiet(), null, 3);
        Class<?> answerType = Class.forName("com.specflow.agent.Probe$Answer");
        var ctor = answerType.getDeclaredConstructor(List.class, List.class, List.class, String.class);
        ctor.setAccessible(true);
        Object answer = ctor.newInstance(artifacts.files(), artifacts.changes(),
                artifacts.context(), artifacts.prompt());
        Method method = Probe.class.getDeclaredMethod("validate", answerType);
        method.setAccessible(true);
        return (String) method.invoke(probe, answer);
    }

    @Test
    @DisplayName("新增文件（还不存在）可以进 changes —— 这是它能「建 DTO/VO」的前提")
    void allowsPathsThatDoNotExistYet(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/java/com/demo/mapper"));
        Files.writeString(root.resolve("src/main/java/com/demo/mapper/BookMapper.java"), "class BookMapper {}\n");

        Probe.Artifacts artifacts = new Probe.Artifacts(
                List.of("src/main/java/com/demo/mapper/BookMapper.java"),
                List.of("src/main/java/com/demo/mapper/BookMapper.java",
                        "src/main/java/com/demo/dto/BookCreateDTO.java"),   // ← 还不存在
                List.of("BookCreateDTO 是新增的，照着 BookDTO 的样子写"),
                "把新增图书接口做出来");

        assertThat(validate(root, artifacts)).isNull();
    }

    @Test
    @DisplayName("files 里的路径必须真存在（要读的东西不能是猜的）")
    void rejectsMissingReadableFiles(@TempDir Path root) throws Exception {
        Probe.Artifacts artifacts = new Probe.Artifacts(
                List.of("src/main/java/com/demo/NotThere.java"),
                List.of("src/main/java/com/demo/NotThere.java"),
                List.of(), "做点什么");

        String problem = validate(root, artifacts);
        assertThat(problem).contains("不存在").contains("changes");
    }

    @Test
    @DisplayName("已存在的文件要进 changes，就必须同时进 files（先能读才谈得上改）")
    void rejectsExistingChangeNotInFiles(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/java/com/demo"));
        Files.writeString(root.resolve("src/main/java/com/demo/Book.java"), "class Book {}\n");
        Files.writeString(root.resolve("src/main/java/com/demo/Reader.java"), "class Reader {}\n");

        Probe.Artifacts artifacts = new Probe.Artifacts(
                List.of("src/main/java/com/demo/Book.java"),
                List.of("src/main/java/com/demo/Book.java", "src/main/java/com/demo/Reader.java"),
                List.of(), "改一处");

        assertThat(validate(root, artifacts)).contains("Reader.java").contains("files");
    }

    @Test
    @DisplayName("空 files / 空 prompt 仍旧当场拒（没东西可读、没目标可干）")
    void keepsTheOldGuards(@TempDir Path root) throws Exception {
        assertThat(validate(root, new Probe.Artifacts(List.of(), List.of(), List.of(), "做点什么")))
                .contains("files 是空的");
        assertThat(validate(root, new Probe.Artifacts(List.of("a.java"), List.of(), List.of(), " ")))
                .contains("prompt 是空的");
    }
}
