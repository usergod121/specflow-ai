package com.specflow.agent;

import com.specflow.core.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ④ 到底吃到了什么：<b>② 的四样产物是不是都进了提示词</b>。
 *
 * <h2>为什么这一组必须存在（一条断了很久的链路）</h2>
 * ② 的探针产出 {@code files}（要读的）/ {@code changes}（要改的）/ {@code context}（为什么相关）/ {@code prompt}。
 * 而 ④ 原来只把 {@code changes}（当白名单）与 {@code prompt} 给了开发 —— <b>另外两样只存不用</b>。
 * 于是用户在 ② 里改「上下文」对开发毫无影响，想让它生效只能自己去改那段 prompt。
 * 这条断链<b>没有任何测试会发现</b>：跑起来也不报错，只是"你改的东西没反应"。
 *
 * <p>所以这里直接钉提示词原文：三样东西（prompt / files / context）都得在它里面出现，
 * 而且顺序是「要做什么 → 先读哪儿 → 为什么相关 → 反馈」。
 */
class DevMessageTest {

    private static Dev dev(Path root) {
        return new Dev(Project.open(root), null, null, null, 3);
    }

    @Test
    @DisplayName("三样产物都进了提示词：prompt / 先读哪儿 / 为什么相关")
    void allThreeArtifactsReachTheModel(@TempDir Path root) {
        String message = dev(root).userMessage(
                "把 InventoryStore.reserve 的库存校验修好",
                List.of("src/main/java/com/demo/InventoryStore.java", "README.md"),
                List.of("InventoryStore.java（库存扣减都在这一个方法里）",
                        "InventoryApp.java（入口，跑一遍能看到结果）"),
                "");

        assertThat(message).contains("把 InventoryStore.reserve 的库存校验修好");
        assertThat(message).contains("先读这几处").contains("InventoryStore.java").contains("README.md");
        assertThat(message).contains("上下文（为什么这些地方相关")
                .contains("库存扣减都在这一个方法里")
                .contains("入口，跑一遍能看到结果");
    }

    @Test
    @DisplayName("顺序：要做什么 → 先读什么 → 为什么相关 → 反馈（动手之前先看清）")
    void sectionsAreOrdered(@TempDir Path root) {
        String message = dev(root).userMessage("改 A", List.of("a.txt"), List.of("a.txt 是入口"), "第 2 条没过");

        int prompt = message.indexOf("这一轮要做的事");
        int reads = message.indexOf("先读这几处");
        int context = message.indexOf("上下文（为什么这些地方相关");
        int feedback = message.indexOf("上一轮测试留下的反馈");

        assertThat(prompt).isGreaterThanOrEqualTo(0);
        assertThat(reads).isGreaterThan(prompt);
        assertThat(context).isGreaterThan(reads);
        assertThat(feedback).isGreaterThan(context);
    }

    @Test
    @DisplayName("两样都可以空（人只写了 prompt 也照样能跑）——但不能因此炸")
    void emptyExtrasAreFine(@TempDir Path root) {
        String message = dev(root).userMessage("只改这一处", List.of(), List.of(), "");

        assertThat(message).contains("只改这一处");
        assertThat(message).doesNotContain("先读这几处");
        assertThat(message).doesNotContain("上下文（为什么这些地方相关");
    }

    @Test
    @DisplayName("prompt 或白名单是空的：当场拒（空 prompt 等于让它自己发明需求）")
    void rejectsEmptyPromptOrWhitelist(@TempDir Path root) {
        Dev dev = dev(root);

        assertThatThrownBy(() -> dev.run(" ", List.of("a.txt"), List.of(), List.of(), ""))
                .as("空 prompt 要被拒")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt");
        assertThatThrownBy(() -> dev.run("做点什么", List.of(), List.of(), List.of(), ""))
                .as("空白名单要被拒")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
    }
}
