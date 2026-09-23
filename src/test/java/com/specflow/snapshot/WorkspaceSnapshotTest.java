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

    private WorkspaceSnapshot capture(Path... files) {
        return WorkspaceSnapshot.capture(resolver, snapshotRoot, List.of(files));
    }
}
