package com.specflow.flow;

import java.util.Map;

/**
 * 一个节点给界面看的形状（`docs/API.md` §2）。
 *
 * <p>{@code blockedReason} 是<b>空串 = 能做</b>，不是 null：界面只做「有字就显示」这一个判断，
 * 而 null 会让它多写一条「到底是没闸还是后端没给」的分支——那种分支迟早会长成第二个判据。
 *
 * <p>{@code key()} / {@code title()} 是给路由的方便方法：直接序列化这个记录，
 * 出来的形状就与 API.md §2 对得上（前端读 {@code key}），不必在路由里再拼一遍对象。
 *
 * @param facts 这一格的产物（扁平字符串表，给界面画徽章）；<b>没有就是空表，不是 null</b>
 */
public record NodeView(Node node, State state, String blockedReason, Map<String, String> facts) {

    public NodeView {
        blockedReason = blockedReason == null ? "" : blockedReason;
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    public String key() {
        return node.key();
    }

    public String title() {
        return node.title();
    }

    public String what() {
        return node.what();
    }
}
