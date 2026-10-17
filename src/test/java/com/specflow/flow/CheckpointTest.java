package com.specflow.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行级断点：<b>跑到哪、下一步干什么</b>。
 *
 * <p>它刻意<b>不</b>存对话历史（那是两万字符一条的东西，存下来就是第二份真相）。
 * 抄的是 LangGraph 最省的那一档语义：只在"一步做完"时落一次，存「状态 + 下一步」。
 * 所以这里钉三件事：写进去读得出、坏文件不炸、读不出来当没有。
 */
class CheckpointTest {

    private static Checkpoint sample() {
        return new Checkpoint("test", "current", "2026-10-17 14:20:00",
                "点「跑测试」从断点接着验（已到终态的不会重验）", List.of("C1", "C2", "C3"), 42, "ROUND_LIMIT");
    }

    @Test
    @DisplayName("写进去能读出来，字段一个不丢")
    void roundTrip(@TempDir Path dir) {
        Path file = dir.resolve("checkpoint.json");

        Checkpoint.write(file, sample());
        Checkpoint read = Checkpoint.read(file);

        assertThat(read).isNotNull();
        assertThat(read.node()).isEqualTo("test");
        assertThat(read.doneCases()).containsExactly("C1", "C2", "C3");
        assertThat(read.roundsUsed()).isEqualTo(42);
        assertThat(read.status()).isEqualTo("ROUND_LIMIT");
        assertThat(read.next()).contains("接着验");
    }

    @Test
    @DisplayName("没有断点 / 文件坏了：都当「没有」，不许因此打不开项目")
    void missingOrBrokenIsNull(@TempDir Path dir) {
        Path file = dir.resolve("checkpoint.json");

        assertThat(Checkpoint.read(file)).isNull();

        com.specflow.core.Atomic.write(file, "{这不是 json");
        assertThat(Checkpoint.read(file)).isNull();
    }

    @Test
    @DisplayName("摘要那句话把三件事都说清：状态、哪几条到终态、下一步")
    void summarizeSaysWhatMatters() {
        String text = sample().summarize();

        assertThat(text).contains("ROUND_LIMIT").contains("C1").contains("C2").contains("下一步");
    }

    @Test
    @DisplayName("清掉之后就读不到了（验完之后不留一个假断点）")
    void clearRemovesIt(@TempDir Path dir) {
        Path file = dir.resolve("checkpoint.json");
        Checkpoint.write(file, sample());

        Checkpoint.clear(file);

        assertThat(Checkpoint.read(file)).isNull();
    }
}
