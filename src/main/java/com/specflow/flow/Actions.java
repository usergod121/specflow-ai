package com.specflow.flow;

import java.util.List;
import java.util.Map;

/**
 * <b>每一格有哪些动作——只有这一处声明</b>。
 *
 * <h2>为什么要有它（一处事故 + 一条外部证据）</h2>
 * 事故：① 和 ④ 的页面上<b>根本没有「冻结」按钮</b>——活干完了却永远走不到 DONE，下游永远解锁不了。
 * 根因不是谁写错了，而是"有哪些动作"这件事<b>散在 6 个地方</b>：
 * <ul>
 *   <li>{@link Node} 枚举、{@link Gates} 的闸表、{@code web.Api} 的路由注解、</li>
 *   <li>前端的 {@code lib/flow.js} / {@code lib/pages.js} / {@code router.js}、
 *       以及<b>每个视图文件里各写一遍的动作数组</b>。</li>
 * </ul>
 * 加一个动作要改 6 处，漏改一处不会报错，只会"这个按钮不在"或"点了没反应"。
 *
 * <p>外部证据（研究里最有用的一条）：<b>toonflow</b> 的节点动作<b>只有一份定义，后端没有副本</b>——
 * 后端只解析节点包里的元数据，能力由组件在运行期自己注册，于是"加一个节点 = 加一个包，后端零改动"。
 * 我们没有插件体系，但同一个原则能用：<b>声明放后端，前端只提供"按 id 绑定处理函数"</b>。
 *
 * <h2>它不是第二份"流程真相"</h2>
 * 流程的顺序仍然由 {@link Node#ordinal()} 定，能不能动仍由 {@link Gate} 判。
 * 这里只声明"这一格有哪些按钮、叫什么、进页头还是进「⋯」"——<b>纯展示元数据</b>。
 * 界面拿它渲染，处理函数按 id 绑定；<b>声明里有、界面里没接</b>的动作会渲染成灰的并说明原因，
 * 而不是静默消失（那正是上面那次事故的样子）。
 */
public final class Actions {

    /**
     * 一个动作。
     *
     * @param id               稳定标识（前端按它绑处理函数；与 {@code web.Api} 的路由同名同义）
     * @param label            按钮上那几个字（正文 ≤6 汉字，长解释一律进 {@code tip}）
     * @param tip              悬停说明（写清"这是什么、会做什么"）
     * @param primary          页头最多一个主按钮
     * @param danger           危险动作（红）
     * @param menu             放进「⋯」而不是页头（页头最多 1 主 + 1 次）
     * @param onlyWhileRunning 只在有活在跑时出现（叫停）
     */
    public record ActionSpec(String id, String label, String tip, boolean primary, boolean danger,
                             boolean menu, boolean onlyWhileRunning) {

        static ActionSpec head(String id, String label, String tip) {
            return new ActionSpec(id, label, tip, false, false, false, false);
        }

        static ActionSpec primary(String id, String label, String tip) {
            return new ActionSpec(id, label, tip, true, false, false, false);
        }

        static ActionSpec menu(String id, String label, String tip) {
            return new ActionSpec(id, label, tip, false, false, true, false);
        }

        static ActionSpec danger(String id, String label, String tip) {
            return new ActionSpec(id, label, tip, false, true, true, false);
        }

        /**
         * 叫停：平时在「⋯」里，**跑着的时候被提到页头**（`lib/actions.js` 的 `head()` 会把它捞出来，
         * `MoreMenu` 再把它排到最前面）。
         *
         * <p>用户实报过：出错提示写着「先等它跑完，或者点『叫停』」，可他在页面上找不到那一枚——
         * 因为它一直待在「⋯」里，而那不是"退路"该待的地方。<b>唯一能解开死锁的那一枚必须在明面上。</b>
         * 声明里仍按 `menu=true`（这样"页头最多 2 枚"的额度不被它占掉），由前端在
         * `onlyWhileRunning` 为真时提升到页头。
         */
        static ActionSpec cancel() {
            return new ActionSpec("cancel", "叫停", "让它停下来（立标记 + 杀整棵进程树）",
                    false, true, true, true);
        }

        /** 冻结：每一格都有，位置由各格自己定。 */
        static ActionSpec freeze(String tip) {
            return new ActionSpec("freeze", "冻结", tip, false, false, true, false);
        }
    }

    /** 五格的动作表。<b>加/删动作先改这里</b>。 */
    private static final Map<Node, List<ActionSpec>> TABLE = Map.of(
            Node.ENV, List.of(
                    ActionSpec.primary("agent", "探测环境", "只读地读一遍项目，交回 env.yaml 草稿与编译命令"),
                    ActionSpec.head("up", "起环境", "拉镜像 + init + 在容器里真构建一次（长任务）"),
                    ActionSpec.freeze("做完了、冻住：之后 ② 才可动（它要求容器里真能构建）"),
                    ActionSpec.menu("compile", "编译", "在宿主机上跑一次编译命令（④ 每次交差也用它）"),
                    ActionSpec.menu("clear", "清空输出", "只清屏幕上的输出，不动任何文件"),
                    ActionSpec.menu("timeout", "改超时", "「这一步我愿意等多久」（分钟）"),
                    ActionSpec.danger("skip", "跳过这步", "声明这个项目不需要容器环境（闸的另一条出路）"),
                    ActionSpec.cancel()),

            Node.PROBE, List.of(
                    ActionSpec.primary("agent", "跑探针", "只读地读一遍项目，交出四样产物"),
                    ActionSpec.head("freeze", "冻结", "冻住之后 ③ 才可动"),
                    ActionSpec.menu("brief", "存需求", "把需求与设计写回项目文件"),
                    ActionSpec.menu("save", "保存产物", "人改过的四样产物存回项目文件"),
                    ActionSpec.cancel()),

            Node.CASES, List.of(
                    ActionSpec.primary("save", "保存", "整份清单存回项目文件（不合规就整份不落盘）"),
                    ActionSpec.menu("agent", "生成用例", "AI 出一版草稿交给你改（不落盘）"),
                    ActionSpec.freeze("冻住之后 ④ 才可动")),

            Node.DEV, List.of(
                    ActionSpec.primary("run", "跑开发", "只在白名单里改真项目，每次交差编译一次"),
                    ActionSpec.head("freeze", "冻结", "改完了、冻住：之后 ⑤ 才可动"),
                    ActionSpec.danger("rollback", "回滚改动", "按快照把真项目退回原文")),

            Node.TEST, List.of(
                    ActionSpec.primary("run", "跑测试", "在容器副本里逐条验，结论写回清单"),
                    ActionSpec.head("submit", "提交", "落 summary.md；清单还有未定的会被拦"),
                    ActionSpec.menu("accept", "接受并完成", "闭环：这一轮到此为止"),
                    ActionSpec.menu("optimize", "再优化一轮", "把总结回喂给 ④ 再来一次")));

    private Actions() {
    }

    /** 这一格的动作（顺序就是界面上的顺序）。 */
    public static List<ActionSpec> of(Node node) {
        return TABLE.getOrDefault(node, List.of());
    }

    /** 五格都有声明——漏一格是一条"这一格没有按钮"的事故，所以这里有兜底判据。 */
    public static boolean complete() {
        for (Node node : Node.values()) {
            if (TABLE.getOrDefault(node, List.of()).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
