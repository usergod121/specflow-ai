package com.specflow.flow;

/**
 * 一个节点的三态。
 *
 * <p>这三档的<b>算法</b>只有一处（{@code FlowService} 的 refresh）：
 * {@code DONE} 是记在 {@code flow.json} 里的「这一步做过并冻结了」；
 * {@code READY} = 前置做完了；其余 {@code LOCKED}。
 *
 * <p>为什么 {@code READY} 不看本节点自己的门槛：看了就会自锁——① 还没配编译命令时
 * 整格灰掉，用户连「去配编译命令」都点不进去，而配它正是这一格的活。
 */
public enum State {
    LOCKED, READY, DONE
}
