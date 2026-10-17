package com.specflow.agent;

import com.specflow.core.Llm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文压缩：太长就压成「锚点 + 任务书 + 尾巴」。
 *
 * <p>刻意<b>不</b>用模型写摘要——那会多一次延迟、多一次花钱、多一个故障点。
 * 要保住的三件事（动过哪些文件 / 最近为什么失败 / 做到哪一条）引擎自己就知道，
 * 所以这里全是确定性拼装：同一段对话永远压出同一份锚点，能被这几条测试钉死。
 */
class CompactTest {

    private static List<Llm.Msg> dialogue(int turns, int charsPerMessage) {
        List<Llm.Msg> messages = new ArrayList<>();
        messages.add(Llm.Msg.system("你是测试。规矩：一条都不许留 PENDING。"));
        messages.add(Llm.Msg.user("需求：把 reserve 的库存校验修好。"));
        for (int at = 0; at < turns; at++) {
            messages.add(Llm.Msg.assistant("第 " + at + " 轮：" + "x".repeat(charsPerMessage)));
            messages.add(Llm.Msg.user("工具结果：" + "y".repeat(charsPerMessage)));
        }
        return messages;
    }

    @Test
    @DisplayName("短对话不压（不能因为'有点长'就动它）")
    void shortDialogueIsUntouched() {
        List<Llm.Msg> messages = dialogue(2, 100);

        Compact.Result result = Compact.maybe(messages, "锚点", Compact.DEFAULT_CHARS, Compact.DEFAULT_KEEP_TAIL);

        assertThat(result.applied()).isFalse();
        assertThat(messages).hasSize(6);
    }

    @Test
    @DisplayName("长对话：system 与任务书保住、锚点插进去、尾巴原样留")
    void compressionKeepsSystemTaskAndTail() {
        List<Llm.Msg> messages = dialogue(40, 2_000);        // ≈ 16 万字符，必压
        String firstTask = messages.get(1).content();

        Compact.Result result = Compact.maybe(messages,
                "- 改过 src/main/java/com/demo/InventoryStore.java\n- 最近失败：bash 退出码 1（表不存在）",
                20_000, 6);

        assertThat(result.applied()).isTrue();
        assertThat(result.charsAfter()).isLessThan(result.charsBefore());
        // ① system 还在最前面
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(0).content()).contains("一条都不许留 PENDING");
        // ② 锚点紧跟其后
        assertThat(messages.get(1).content()).contains("压缩前的锚点").contains("InventoryStore.java");
        // ③ 原始任务书没丢
        assertThat(messages.stream().map(Llm.Msg::content).toList()).contains(firstTask);
        // ④ 尾巴原样保留（最后一条就是压之前的那条）
        assertThat(messages.get(messages.size() - 1).content()).isEqualTo("工具结果：" + "y".repeat(2_000));
        // ⑤ 中间那几万字符的过程没了
        assertThat(result.after()).isLessThan(result.before());
        assertThat(Compact.totalChars(messages)).isLessThan(30_000);
    }

    @Test
    @DisplayName("压完继续长的话会再压一次（幂等：不会越压越多）")
    void compressionIsRepeatable() {
        List<Llm.Msg> messages = dialogue(40, 2_000);
        Compact.maybe(messages, "锚点", 20_000, 6);
        int afterFirst = messages.size();

        Compact.maybe(messages, "锚点", 20_000, 6);

        assertThat(messages.size()).as("没有新的过程可压时不会再涨").isLessThanOrEqualTo(afterFirst + 1);
        assertThat(messages.get(0).role()).isEqualTo("system");
    }

    @Test
    @DisplayName("没有锚点也能压（只留 system + 任务书 + 尾巴）")
    void worksWithoutAnchors() {
        List<Llm.Msg> messages = dialogue(40, 2_000);

        Compact.Result result = Compact.maybe(messages, "", 20_000, 6);

        assertThat(result.applied()).isTrue();
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.stream().map(Llm.Msg::content).toList())
                .noneSatisfy(one -> assertThat(one).contains("压缩前的锚点"));
    }
}
