package com.specflow.patch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("行级差异")
class TextDiffTest {

    @Test
    @DisplayName("内容相同时不产出任何差异")
    void identicalContent() {
        assertThat(TextDiff.unified("a\nb\n", "a\nb\n")).isEmpty();
        assertThat(TextDiff.unified("", "")).isEmpty();
    }

    @Test
    @DisplayName("新增行标加号")
    void detectsAddition() {
        String diff = TextDiff.unified("a\nc\n", "a\nb\nc\n");

        assertThat(diff.lines()).containsExactly(" a", "+b", " c");
    }

    @Test
    @DisplayName("删除行标减号")
    void detectsDeletion() {
        String diff = TextDiff.unified("a\nb\nc\n", "a\nc\n");

        assertThat(diff.lines()).containsExactly(" a", "-b", " c");
    }

    @Test
    @DisplayName("替换一行时减号在前、加号在后")
    void detectsReplacement() {
        String diff = TextDiff.unified("a\nold\nc\n", "a\nnew\nc\n");

        assertThat(diff.lines()).containsExactly(" a", "-old", "+new", " c");
    }

    @Test
    @DisplayName("新建文件时整段都是新增")
    void newFileIsAllAdditions() {
        String diff = TextDiff.unified("", "class A {}\n");

        assertThat(diff.lines()).containsExactly("+class A {}");
    }

    @Test
    @DisplayName("改动前后各保留三行上下文，更远的未改动内容不出现在差异里")
    void keepsLimitedContext() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";
        String after = before.replace("5", "five");

        String diff = TextDiff.unified(before, after);

        assertThat(diff).contains(" 2").contains(" 3").contains(" 4");
        assertThat(diff).contains("-5").contains("+five");
        assertThat(diff).contains(" 6").contains(" 7").contains(" 8");
        assertThat(diff.lines()).doesNotContain(" 1", " 9", " 10");
    }

    @Test
    @DisplayName("多处改动之间未变的行作为上下文保留，不会把整段都标成改动")
    void keepsUnchangedLinesBetweenEdits() {
        String before = "a\nb\nc\nd\ne\nf\ng\n";
        String after = "a\nB\nc\nd\ne\nF\ng\n";

        String diff = TextDiff.unified(before, after);

        assertThat(diff).contains("-b").contains("+B").contains("-f").contains("+F");
        assertThat(diff).contains(" c").contains(" d").contains(" e");
    }

    @Test
    @DisplayName("差异算法与行尾风格无关——CRLF 文件比较的是同一批行")
    void ignoresLineEndingStyle() {
        assertThat(TextDiff.unified("a\r\nb\r\n", "a\nb\n")).isEmpty();
    }

    @Test
    @DisplayName("中间段过大时退化为全删全插，仍然给出可读的差异而不是卡死")
    void fallsBackForHugeMiddleSection() {
        String before = "same\n" + "x\n".repeat(2000);
        String after = "same\n" + "y\n".repeat(2000);

        String diff = TextDiff.unified(before, after);

        assertThat(diff).startsWith(" same");
        assertThat(diff.lines().filter(line -> line.equals("-x")).count()).isEqualTo(2000);
        assertThat(diff.lines().filter(line -> line.equals("+y")).count()).isEqualTo(2000);
    }

    @Test
    @DisplayName("空行的改动也能正确表示")
    void handlesBlankLines() {
        assertThat(TextDiff.unified("a\nb\n", "a\n\nb\n").lines()).containsExactly(" a", "+", " b");
    }
}
