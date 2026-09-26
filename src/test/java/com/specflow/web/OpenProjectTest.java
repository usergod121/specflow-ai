package com.specflow.web;

import com.specflow.project.SnapshotConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.util.SafePathResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("打开项目时的收尾")
class OpenProjectTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("收掉没写完的快照，但不动那份等着人处置的")
    void openingCleansLeftoversButKeepsUsableSnapshot() throws IOException {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "original");
        WorkspaceSnapshot usable = WorkspaceSnapshot.capture(new SafePathResolver(root),
                root.resolve(SnapshotConfig.DEFAULT_DIR), List.of(file)).markPending();
        Path halfWritten = root.resolve(SnapshotConfig.DEFAULT_DIR).resolve("20260101-000000-001");
        Files.createDirectories(halfWritten);

        OpenProject.open(root).close();

        assertThat(halfWritten).doesNotExist();
        assertThat(usable.directory().resolve("manifest.tsv")).exists();
        assertThat(usable.directory().resolve("files/Foo.java")).hasContent("original");
    }
}
