package com.specflow.flow;

/**
 * <b>一格的门槛</b>：{@code Facts} 进，一句话出（空串 = 能做）。
 *
 * <h2>为什么要把它从 {@code Gates} 的 switch 里拆出来</h2>
 * 五格的门槛原来是一个 {@code switch} 的五个 {@code private static} 方法——能跑，但两手都别扭：
 * <ul>
 *   <li><b>加一格</b>要改三处（枚举、switch、路由），而漏改一处不会报错，只会"这一格永远不动"；</li>
 *   <li><b>测一格</b>只能整体测（{@code GatesTest} 里全是 {@code Gates.blocked(ENV, …)}），
 *       想单独钉住某一格的话术就得绕；</li>
 *   <li><b>看一格</b>要在一百多行里找。</li>
 * </ul>
 * 拆成"一格一个实现 + 一张表"之后：{@code Gates} 只剩下"顺序那一条硬规则"，
 * 而每一格的门槛是它自己那个小类——读、测、加都只碰一处。
 *
 * <h2>纯函数的约定没变（重要）</h2>
 * 实现里<b>不许读盘、不许看时间、不许写事件</b>：同一份 {@code Facts} 永远给同一个答案。
 * 这条是闸能被单测钉死的前提；一旦有人在这里读盘，"同一个问题两个答案"就会回来。
 *
 * <h2>它<b>不</b>负责"顺序"</h2>
 * "上一格没冻结就不许动"是<b>跨格</b>的规则，需要五格的状态（{@code FlowState}），
 * 而这里只有 {@code Facts}。所以那一条留在 {@link Gates#blocked(Node, Facts, java.util.Map)}
 * 里——它是那道硬闸，且永远是第一句（用户要知道该回去点哪一个）。
 */
@FunctionalInterface
public interface Gate {

    /**
     * @param facts 判闸的事实（{@code FlowService.facts()} 那一份；永远不是 {@code null}）
     * @return 空串 = 能做；否则是<b>直接给用户看的那句话</b>（409 正文原样，一字不改）
     */
    String blocked(Facts facts);
}
