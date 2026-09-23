package com.specflow.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("目录浏览")
class ProjectBrowserTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("没给路径时列盘符，而不是一片空白")
    void listsDrivesWithoutAPath() {
        ProjectBrowser.Listing listing = new ProjectBrowser().browse("");

        assertThat(listing.dirs()).isNotEmpty();
        assertThat(listing.path()).isNull();
    }

    @Test
    @DisplayName("列子目录时按名字排序，目录本身不出现")
    void listsChildrenSorted() throws IOException {
        for (String name : new String[]{"zebra", "Alpha", "middle"}) {
            Files.createDirectories(root.resolve(name));
        }
        Files.writeString(root.resolve("a-file.txt"), "x");

        ProjectBrowser.Listing listing = new ProjectBrowser().browse(root.toString());

        assertThat(listing.dirs()).extracting(ProjectBrowser.Directory::name)
                .containsExactly("Alpha", "middle", "zebra");
        assertThat(listing.parent()).isEqualTo(root.getParent().toString());
    }

    @Test
    @DisplayName("刚好 300 个子目录时不算被截断——一个都没少，别吓唬人")
    void exactlyTheLimitIsNotTruncated() throws IOException {
        createDirectories(300);

        ProjectBrowser.Listing listing = new ProjectBrowser().browse(root.toString());

        assertThat(listing.dirs()).hasSize(300);
        assertThat(listing.truncated()).isFalse();
    }

    @Test
    @DisplayName("超过 300 个时截断，并如实说明")
    void overTheLimitIsTruncated() throws IOException {
        createDirectories(301);

        ProjectBrowser.Listing listing = new ProjectBrowser().browse(root.toString());

        assertThat(listing.dirs()).hasSize(300);
        assertThat(listing.truncated()).isTrue();
    }

    private void createDirectories(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            Files.createDirectory(root.resolve("d" + i));
        }
    }
}
