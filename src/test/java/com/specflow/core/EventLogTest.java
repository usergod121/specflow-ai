package com.specflow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一归档日志：<b>id 恒等于行号</b>、重启接着数、盘上是唯一真相。
 *
 * <p>这个类此前<b>一条测试都没有</b>（评审点名的风险：把事件流改坏，149 条测试一条都不会红）。
 * 现在它有三条铁律 + 一份行缓存，所以这四条测试正好一条钉一铁律、一条钉缓存的失效。
 */
class EventLogTest {

    private static Event log(String node, String text) {
        return Event.log(0, node, "info", text);
    }

    @Test
    @DisplayName("id 恒等于行号：追加 5 条，id 就是 1..5")
    void idEqualsLineNumber(@TempDir Path dir) {
        EventLog events = new EventLog(dir.resolve("events.jsonl"));

        for (int at = 1; at <= 5; at++) {
            assertThat(events.append(log("env", "第 " + at + " 条")).id()).isEqualTo(at);
        }
        assertThat(events.lines()).isEqualTo(5);
        assertThat(events.next()).isEqualTo(6);
        assertThat(events.read(0, 0)).extracting(Event::id).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("重启接着数：换一个实例读同一个文件，接着写 id=6")
    void restartKeepsCounting(@TempDir Path dir) {
        Path file = dir.resolve("events.jsonl");
        EventLog first = new EventLog(file);
        first.append(log("env", "一"));
        first.append(log("env", "二"));

        EventLog second = new EventLog(file);       // 模拟进程重启
        assertThat(second.lines()).isEqualTo(2);
        assertThat(second.append(log("probe", "三")).id()).isEqualTo(3);
        assertThat(second.read(2, 0)).extracting(Event::id).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("盘上是唯一真相：文件被外部改过，下一次读必须看见新内容")
    void externalWritesAreSeen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("events.jsonl");
        EventLog events = new EventLog(file);
        events.append(log("env", "原来那条"));
        assertThat(events.lines()).isEqualTo(1);

        // 外部追加（手工编辑、或另一个进程）：缓存必须失效
        Files.writeString(file, Files.readString(file).replace("原来那条", "被改过"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(events.read(1, 0).get(0).text()).contains("被改过");

        // 外部整份替换：大小变了，一样要重读
        Files.writeString(file, "", java.nio.charset.StandardCharsets.UTF_8);
        assertThat(events.lines()).isZero();
        assertThat(events.append(log("env", "重来")).id()).isEqualTo(1);
    }

    @Test
    @DisplayName("一行坏掉不吞：换成一条 warn，后面的照读")
    void brokenLineBecomesWarn(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("events.jsonl");
        EventLog events = new EventLog(file);
        events.append(log("env", "好的"));
        Files.writeString(file, Files.readString(file) + "{这不是 json\n",
                java.nio.charset.StandardCharsets.UTF_8);

        List<Event> all = events.read(0, 0);

        assertThat(all).hasSize(2);
        assertThat(all.get(1).level()).isEqualTo("warn");
        assertThat(all.get(1).text()).contains("读不出来");
        assertThat(all.get(1).id()).as("坏行也占一个行号").isEqualTo(2);
    }

    @Test
    @DisplayName("回退作废：截之前先留副本，截完接着数")
    void truncateArchivesFirst(@TempDir Path dir) {
        EventLog events = new EventLog(dir.resolve("events.jsonl"));
        for (int at = 1; at <= 4; at++) {
            events.append(log("env", "第 " + at));
        }

        Path archive = events.truncateTo(2);

        assertThat(archive).isNotNull();
        assertThat(Files.exists(archive)).isTrue();
        assertThat(events.lines()).isEqualTo(2);
        assertThat(events.append(log("env", "新的")).id()).as("截断之后接着数").isEqualTo(3);
        // 备份里是截之前的全文（4 条），所以"刚才到底报了什么"还查得到
        assertThat(Atomic.read(archive, "").lines().count()).isEqualTo(4);
    }

    @Test
    @DisplayName("文件不在：读是空、不抛；第一条写下去 id 从 1 开始")
    void missingFileIsEmpty(@TempDir Path dir) {
        EventLog events = new EventLog(dir.resolve("nope").resolve("events.jsonl"));

        assertThat(events.read(0, 0)).isEmpty();
        assertThat(events.lines()).isZero();
        assertThat(events.append(log("env", "第一")).id()).isEqualTo(1);
    }
}
