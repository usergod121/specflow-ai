package com.specflow.snapshot;

import com.specflow.exception.SpecflowException;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("快照与回滚")
class WorkspaceSnapshotTest {

    @TempDir
    Path root;

    private SafePathResolver resolver;
    private Path snapshotRoot;

    @BeforeEach
    void setUp() {
        resolver = new SafePathResolver(root);
        snapshotRoot = root.resolve(".specflow/snapshots");
    }

    @Test
    @DisplayName("回滚已有文件：内容恢复到快照时刻")
    void restoresModifiedFile() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");

        WorkspaceSnapshot snapshot = capture(file);
        Files.writeString(file, "modified");
        snapshot.restore();

        assertThat(Files.readString(file)).isEqualTo("original");
    }

    @Test
    @DisplayName("回滚新建文件：文件被删除")
    void deletesFileCreatedAfterSnapshot() throws IOException {
        Path file = root.resolve("New.java");

        WorkspaceSnapshot snapshot = capture(file);
        Files.writeString(file, "created later");
        snapshot.restore();

        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("一次快照覆盖多个文件：已存在的恢复内容，新建的被删除")
    void restoresMultipleFiles() throws IOException {
        Path kept = root.resolve("Kept.java");
        Files.writeString(kept, "keep me");
        Path created = root.resolve("src/main/java/demo/New.java");

        WorkspaceSnapshot snapshot = capture(kept, created);
        Files.createDirectories(created.getParent());
        Files.writeString(created, "created");
        snapshot.restore();

        assertThat(Files.readString(kept)).isEqualTo("keep me");
        assertThat(created).doesNotExist();
    }

    @Test
    @DisplayName("快照目录里保留可读的原文副本与清单，人可以自己打开看")
    void keepsReadableBackupOnDisk() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");

        WorkspaceSnapshot snapshot = capture(file);

        assertThat(snapshot.directory()).isDirectory();
        assertThat(Files.readString(snapshot.directory().resolve("manifest.tsv")))
                .isEqualTo("PRESENT\tFoo.java");
        assertThat(Files.readString(snapshot.directory().resolve("files/Foo.java")))
                .isEqualTo("original");
    }

    @Test
    @DisplayName("discard 之后快照目录被清空")
    void discardRemovesSnapshotDirectory() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");

        WorkspaceSnapshot snapshot = capture(file);
        snapshot.discard();

        assertThat(snapshot.directory()).doesNotExist();
    }

    @Test
    @DisplayName("清单丢失时拒绝回滚，而不是假装成功")
    void failsWhenManifestMissing() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");

        WorkspaceSnapshot snapshot = capture(file);
        Files.delete(snapshot.directory().resolve("manifest.tsv"));

        assertThatThrownBy(snapshot::restore)
                .isInstanceOf(SpecflowException.class)
                .hasMessageContaining("快照清单丢失");
    }

    @Test
    @DisplayName("清单说 PRESENT 的每一条，副本都必须真的在（清单最后写，副本先落盘）")
    void keepsPayloadForEveryPresentEntry() throws IOException {
        Path existing = root.resolve("src/main/java/demo/Foo.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "original");
        Path missing = root.resolve("src/main/java/demo/New.java");

        WorkspaceSnapshot snapshot = capture(existing, missing);

        assertThat(Files.readString(snapshot.directory().resolve("manifest.tsv")))
                .isEqualTo("PRESENT\tsrc/main/java/demo/Foo.java\n"
                        + "ABSENT\tsrc/main/java/demo/New.java");
        assertThat(snapshot.directory().resolve("files/src/main/java/demo/Foo.java"))
                .hasContent("original");
    }

    @Test
    @DisplayName("快照落盘后不留临时文件残骸")
    void leavesNoTempResidueAfterCapture() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");

        WorkspaceSnapshot snapshot = capture(file);

        assertThat(tempResidue(snapshot.directory())).isEmpty();
    }

    @Test
    @DisplayName("标记待处置：目录改名加后缀，改名后照样能回滚")
    void markPendingRenamesDirectory() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");
        WorkspaceSnapshot captured = capture(file);
        Path before = captured.directory();

        WorkspaceSnapshot pending = captured.markPending();

        assertThat(pending.isPending()).isTrue();
        assertThat(pending.directory().getFileName().toString())
                .endsWith(WorkspaceSnapshot.PENDING_SUFFIX);
        assertThat(before).doesNotExist();
        Files.writeString(file, "changed later");
        assertThat(pending.restore()).containsExactly("Foo.java");
        assertThat(Files.readString(file)).isEqualTo("original");
    }

    @Test
    @DisplayName("未处置的快照：有清单才算一份能用的快照")
    void undisposedRequiresManifest() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");
        WorkspaceSnapshot snapshot = capture(file).markPending();

        List<WorkspaceSnapshot> waiting = WorkspaceSnapshot.undisposed(resolver, snapshotRoot);

        assertThat(waiting).hasSize(1);
        assertThat(waiting.get(0).isPending()).isTrue();

        Files.delete(snapshot.directory().resolve("manifest.tsv"));

        assertThat(WorkspaceSnapshot.undisposed(resolver, snapshotRoot)).isEmpty();
    }

    @Test
    @DisplayName("现算改动：改过的和新建的都在列表里，没动过的文件不出现")
    void changesReportsWhatMoved() throws IOException {
        Path modified = root.resolve("Foo.java");
        Files.writeString(modified, "a\n");
        Path untouched = root.resolve("Bar.java");
        Files.writeString(untouched, "b\n");
        Path created = root.resolve("New.java");

        WorkspaceSnapshot snapshot = capture(modified, untouched, created);
        Files.writeString(modified, "b\n");
        Files.writeString(created, "class New {}\n");

        List<WorkspaceSnapshot.Change> changes = snapshot.changes();

        assertThat(changes).extracting(WorkspaceSnapshot.Change::path)
                .containsExactly("Foo.java", "New.java");
        assertThat(changes.get(0).created()).isFalse();
        assertThat(changes.get(0).diff()).contains("-a").contains("+b");
        assertThat(changes.get(1).created()).isTrue();
        assertThat(changes.get(1).diff()).contains("+class New {}");
    }

    @Test
    @DisplayName("现算改动：什么都没动时是空的")
    void changesIsEmptyWhenNothingMoved() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");

        assertThat(capture(file).changes()).isEmpty();
    }

    @Test
    @DisplayName("收残局：没写完的快照被删、写一半的临时文件被清掉，能用的快照一根汗毛都不动")
    void cleanUpRemovesLeftoversOnly() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");
        WorkspaceSnapshot snapshot = capture(file);
        Files.writeString(file, "changed");

        Path halfWritten = snapshotRoot.resolve("20260101-000000-001");
        Files.createDirectories(halfWritten.resolve("files"));
        Path tmpInSnapshot = snapshot.directory().resolve("manifest.tsv.specflow-tmp-abcd1234");
        Files.writeString(tmpInSnapshot, "x");
        Path tmpNextToTarget = root.resolve("Foo.java.specflow-tmp-abcd1234");
        Files.writeString(tmpNextToTarget, "x");

        List<String> cleaned = WorkspaceSnapshot.cleanUp(resolver, snapshotRoot);

        assertThat(halfWritten).doesNotExist();
        assertThat(tmpInSnapshot).doesNotExist();
        assertThat(tmpNextToTarget).doesNotExist();
        assertThat(snapshot.directory().resolve("manifest.tsv")).exists();
        assertThat(snapshot.directory().resolve("files/Foo.java")).hasContent("original");
        assertThat(cleaned).hasSize(2);
    }

    @Test
    @DisplayName("收残局：干净的项目上什么都不做")
    void cleanUpIsSilentWhenNothingToDo() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");
        WorkspaceSnapshot snapshot = capture(file);

        assertThat(WorkspaceSnapshot.cleanUp(resolver, snapshotRoot)).isEmpty();
        assertThat(snapshot.directory()).isDirectory();
        assertThat(WorkspaceSnapshot.cleanUp(resolver, root.resolve("没有这个目录"))).isEmpty();
    }

    private static List<String> tempResidue(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            return stream.filter(path -> path.getFileName().toString().contains(".specflow-tmp-"))
                    .map(Path::toString)
                    .toList();
        }
    }

    private WorkspaceSnapshot capture(Path... files) {
        return WorkspaceSnapshot.capture(resolver, snapshotRoot, List.of(files));
    }
}
