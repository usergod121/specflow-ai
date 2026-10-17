package com.specflow.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 节点动作的声明：<b>只有这一处，界面按它渲染</b>。
 *
 * <p>这一组测试守的是一次真实事故：① 和 ④ 的页面上<b>根本没有「冻结」按钮</b>，
 * 活干完了却永远走不到 DONE。根因是"有哪些动作"散在六个落点里，漏改一处不报错。
 * 现在动作只有这一份声明，所以这里钉三件事：
 * <ul>
 *   <li>五格都声明了，而且该有的动作一个不少；</li>
 *   <li>同一个动作的 id 在同一格里不重复（重复会让前端按 id 绑定时绑错）；</li>
 *   <li>页头的名额（最多 1 主 + 1 次）由声明自己保证——不靠界面自觉。</li>
 * </ul>
 */
class ActionsTest {

    @Test
    @DisplayName("五格都有动作声明（漏一格 = 那一格没有按钮）")
    void everyNodeIsDeclared() {
        assertThat(Actions.complete()).isTrue();
        for (Node node : Node.values()) {
            assertThat(Actions.of(node)).as("%s 没有动作", node).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("① ② ③ ④ 都有「冻结」：它是 DONE 的唯一入口，少了下游永远解锁不了")
    void everyFreezableNodeHasFreeze() {
        for (Node node : List.of(Node.ENV, Node.PROBE, Node.CASES, Node.DEV)) {
            assertThat(Actions.of(node)).as("%s 少了冻结", node)
                    .extracting(Actions.ActionSpec::id).contains("freeze");
        }
        // ⑤ 的收尾是"提交 → 接受"，不是冻结
        assertThat(Actions.of(Node.TEST)).extracting(Actions.ActionSpec::id)
                .contains("run", "submit", "accept", "optimize");
    }

    @Test
    @DisplayName("同一格里 id 不重复（前端按 id 绑处理函数，重了就会绑错）")
    void idsAreUniquePerNode() {
        for (Node node : Node.values()) {
            List<String> ids = Actions.of(node).stream().map(Actions.ActionSpec::id).toList();
            assertThat(ids).as("%s 的 id 有重复", node).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("页头名额：非「⋯」的动作最多两条，且最多一个主按钮")
    void headSlotsAreRespected() {
        for (Node node : Node.values()) {
            List<Actions.ActionSpec> head = Actions.of(node).stream()
                    .filter(one -> !one.menu()).toList();
            assertThat(head.size()).as("%s 页头放了 %d 个动作（最多 2）", node, head.size())
                    .isLessThanOrEqualTo(2);
            assertThat(head.stream().filter(Actions.ActionSpec::primary).count())
                    .as("%s 页头主按钮不止一个", node).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("「叫停」只在有活时出现；「冻结」进「⋯」，不抢页头")
    void specialActionsHaveTheRightShape() {
        Actions.ActionSpec cancel = Actions.of(Node.ENV).stream()
                .filter(one -> one.id().equals("cancel")).findFirst().orElseThrow();
        assertThat(cancel.onlyWhileRunning()).isTrue();
        assertThat(cancel.danger()).isTrue();

        Actions.ActionSpec freeze = Actions.of(Node.ENV).stream()
                .filter(one -> one.id().equals("freeze")).findFirst().orElseThrow();
        assertThat(freeze.menu()).as("冻结进 ⋯，页头留给主流程").isTrue();
    }

    @Test
    @DisplayName("按钮上的字 ≤6 个汉字（文案预算；长解释一律进 tip）")
    void labelsAreShort() {
        for (Node node : Node.values()) {
            for (Actions.ActionSpec one : Actions.of(node)) {
                long han = one.label().codePoints().filter(cp -> cp >= 0x4e00 && cp <= 0x9fff).count();
                assertThat(han).as("%s 的 %s 标签太长：%s", node, one.id(), one.label())
                        .isLessThanOrEqualTo(6);
                assertThat(one.tip()).as("%s 的 %s 没有悬停说明", node, one.id()).isNotBlank();
            }
        }
    }
}
