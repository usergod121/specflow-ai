package com.specflow.flow;

import com.specflow.core.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>写前快照与回滚</b>：④ 改坏的代码能不能<b>逐字节</b>退回原文。
 *
 * <h2>为什么这一组是补的（一条零测试的活路）</h2>
 * 快照回滚是全系统唯一"会反向写用户真项目"的动作，而它此前<b>一条单元测试都没有</b>——
 * 端到端也没按过「回滚」那个按钮。它写坏的后果是二次伤害：本来只是代码被改坏，
 * 回滚再把它弄成半份文件、或者把新建的文件留在那儿，用户就再也不敢点第二次。
 *
 * <p>这一组钉五件事：<b>原文逐字节回来</b>（不是"文本差不多"）、<b>新建的文件被删掉</b>
 * （少了 ABSENT 这一态就永远清不掉）、<b>清单不在就不按半份清单回滚</b>、
 * <b>同一份快照不许处置两次</b>（否则第二次会把 accept 过的改动又退回去）、
 * <b>项目外的文件拍不进去，但要说出来</b>（不当成拍到了）。
 */
class SnapshotTest {

    private static Project project(Path root) throws IOException {
        Files.createDirectories(root.resolve(".specflow"));
        Files.writeString(root.resolve(".specflow/project.yaml"), "build:\n  compile: \"\"\n");
        Project project = Project.open(root);
        new Store(project).newFlow();          // 快照落在"当前流程"的目录下，先得有一条流程
        return project;
    }

    private static Path write(Path root, String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    @DisplayName("改坏三个文件 → 回滚 → 原文逐字节回来，新建的那个被删掉")
    void rollbackRestoresBytesAndDeletesNewFiles(@TempDir Path root) throws IOException {
        Project project = project(root);
        Path store = write(root, "src/InventoryStore.java", "class InventoryStore { int onHand = 3; }\n");
        Path readme = write(root, "README.md", "# 库存\n\nreserve(5) 该返回 false\n");
        Path created = root.resolve("src/Helper.java");        // 拍的时候还不存在

        Snapshot snapshot = Snapshot.capture(project, List.of(store, readme, created));
        snapshot.hold();
        assertThat(snapshot.files()).isEqualTo(3);

        // 开发 Agent 干的事：改两个、建一个
        Files.writeString(store, "class InventoryStore { int onHand = 0; }  // 被改坏了\n");
        Files.writeString(readme, "改坏了\n");
        Files.writeString(created, "class Helper {}\n");

        Snapshot.Report report = snapshot.rollback();

        assertThat(report.ok()).isTrue();
        assertThat(report.files()).isEqualTo(3);
        assertThat(Files.readString(store)).isEqualTo("class InventoryStore { int onHand = 3; }\n");
        assertThat(Files.readString(readme)).contains("reserve(5) 该返回 false");
        assertThat(Files.exists(created)).as("改前不存在 → 回滚时删掉").isFalse();
    }

    @Test
    @DisplayName("回滚之后快照改名成 .done，再回滚一次不会再动文件")
    void rollbackTwiceIsRefused(@TempDir Path root) throws IOException {
        Project project = project(root);
        Path file = write(root, "a.txt", "原文\n");
        Snapshot snapshot = Snapshot.capture(project, List.of(file));
        Files.writeString(file, "改过\n");

        assertThat(snapshot.rollback().ok()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("原文\n");

        // 第二次：必须拒（不然用户"再点一次回滚"会把中间新做的改动静默抹掉）
        Files.writeString(file, "用户后来又改的\n");
        Snapshot.Report again = snapshot.rollback();
        assertThat(again.ok()).isFalse();
        assertThat(again.summarize()).contains("处置过");
        assertThat(Files.readString(file)).as("第二次不许动文件").isEqualTo("用户后来又改的\n");
    }

    @Test
    @DisplayName("accept：改动留着、快照删掉；之后再回滚也是拒")
    void acceptKeepsChangesAndDropsSnapshot(@TempDir Path root) throws IOException {
        Project project = project(root);
        Path file = write(root, "a.txt", "原文\n");
        Snapshot snapshot = Snapshot.capture(project, List.of(file));
        Path dir = snapshot.directory();
        Files.writeString(file, "改好的\n");

        Snapshot.Report report = snapshot.accept();

        assertThat(report.ok()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("改好的\n");
        assertThat(Files.exists(dir)).as("快照已删").isFalse();
        assertThat(snapshot.rollback().ok()).isFalse();
    }

    @Test
    @DisplayName("清单被删掉：不按半份清单回滚，而是如实报「没法回滚」，文件不动")
    void missingManifestIsReportedNotGuessed(@TempDir Path root) throws IOException {
        Project project = project(root);
        Path file = write(root, "a.txt", "原文\n");
        Snapshot snapshot = Snapshot.capture(project, List.of(file));
        Files.delete(snapshot.directory().resolve("manifest.tsv"));
        Files.writeString(file, "改过\n");

        Snapshot.Report report = snapshot.rollback();

        assertThat(report.ok()).isFalse();
        assertThat(report.problems()).anySatisfy(one -> assertThat(one).contains("清单"));
        assertThat(Files.readString(file)).as("不知道该退回哪一份，就别动它").isEqualTo("改过\n");
    }

    @Test
    @DisplayName("项目外的文件拍不进去，但要如实说出来（不当成拍到了）")
    void filesOutsideProjectAreSkippedAndReported(@TempDir Path root, @TempDir Path outside) throws IOException {
        Project project = project(root);
        Path inside = write(root, "a.txt", "原文\n");
        Path away = Files.writeString(outside.resolve("elsewhere.txt"), "别人的文件\n");

        Snapshot snapshot = Snapshot.capture(project, List.of(inside, away));
        assertThat(snapshot.files()).as("只拍了项目里那一个").isEqualTo(1);
        assertThat(snapshot.summarize()).contains("项目外");

        Snapshot.Report report = snapshot.rollback();
        assertThat(report.problems()).anySatisfy(one -> assertThat(one).contains("项目外"));
        assertThat(Files.readString(away)).as("项目外的文件一个字节都没动").isEqualTo("别人的文件\n");
    }

    @Test
    @DisplayName("latest()：拿到最近一份没处置的快照；同一次运行拍两次不会串（id 带序号）")
    void latestFindsTheNewestPending(@TempDir Path root) throws IOException {
        Project project = project(root);
        Path a = write(root, "a.txt", "a\n");
        Path b = write(root, "b.txt", "b\n");

        Snapshot first = Snapshot.capture(project, List.of(a));
        Snapshot second = Snapshot.capture(project, List.of(b));
        assertThat(second.id()).isNotEqualTo(first.id());

        Snapshot found = Snapshot.latest(project);
        assertThat(found).isNotNull();
        assertThat(found.directory().getFileName().toString()).endsWith(".pending");

        // 处置掉最近这份之后，latest 应该回到上一份（而不是 null）
        found.accept();
        Snapshot older = Snapshot.latest(project);
        assertThat(older).isNotNull();
        assertThat(older.directory().getFileName().toString())
                .isNotEqualTo(found.directory().getFileName().toString());
    }
}
