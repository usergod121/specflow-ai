package com.specflow.flow;

import java.util.Map;

/**
 * <b>闸</b>：唯一判据。返回空串 = 能做；返回一句话 = 不能做，而那句话<b>直接给用户看</b>
 * （409 的正文、界面上的「为什么还不能点」都是它，一字不改）。
 *
 * <p>纯函数：不读盘、不写盘、不看时间。同一份 facts 永远给同一个答案——闸因此能被
 * 单元测试钉死，而不需要起服务、不需要真项目。
 *
 * <h2>两层判断，顺序不能反</h2>
 * <ol>
 *   <li><b>硬规则（顺序）</b>：节点 N 不是 {@code DONE} → N+1 的动作一律拒。
 *       这一层只看「上一个做完了没有」，不看任何别的条件，而且是
 *       {@link #blocked(Node, Facts, Map)} 的<b>第一句</b>——说出来的话里必须点名
 *       差哪一步（用户要知道该回去点哪一个）；</li>
 *   <li><b>门槛（本节点自己的）</b>：前置完成后，这一格要往下走还得满足它自己的条件
 *       （编译命令、环境、用例…），见 {@link #blocked(Node, Facts)}。</li>
 * </ol>
 *
 * <p><b>为什么有两个重载</b>：顺序那一层要节点的状态，而 {@code Facts} 里没有状态
 * （状态在 {@link FlowState} 里，那是另外一件事的真实来源）。所以：
 * {@link #blocked(Node, Facts, Map)} 是<b>完整的</b>闸（{@code FlowService} 用它），
 * {@link #blocked(Node, Facts)} 是同一个判据去掉顺序那一层（只问「这一格自己的门槛」——
 * 给「这一格上的活正是为了满足它自己的门槛」的场合用，比如跑探针去产出探针的产物）。
 *
 * <p>文案的规矩：<b>先说我看见了什么，再说你去点什么</b>。上一版只说「未满足冻结条件」，
 * 用户唯一的办法是把五个节点各点一遍。
 */
public final class Gates {

    private Gates() {
    }

    /**
     * 完整闸：先判前置（顺序），再判本节点自己的门槛。
     *
     * @param node   要动的节点
     * @param facts  判闸的事实（{@code FlowService.facts()} 那一份）
     * @param states 五节点的状态（{@code FlowState.states()}）；给 {@code null} = 不判顺序
     */
    public static String blocked(Node node, Facts facts, Map<Node, State> states) {
        if (node == null) {
            throw new IllegalArgumentException("判闸要有节点");
        }
        Node previous = node.previous();
        if (previous != null && states != null) {
            State was = states.getOrDefault(previous, State.LOCKED);
            if (was != State.DONE) {
                return "上一步「" + previous.title() + "」" + word(was)
                        + "：先把「" + previous.title() + "」做完并冻结，再动「" + node.title() + "」。";
            }
        }
        return blocked(node, facts);
    }

    /**
     * 一格一个实现，一张表查过去（{@link Gate} 的类注释里写了为什么不再用 switch）。
     *
     * <p>表在这里、<b>只有这里</b>：想知道"① 的门槛是什么"，看这张表就知道去哪个类；
     * 想加一格，往表里加一行（漏了的话 {@link Node} 那边会缺一格，测不出来才怪）。
     */
    private static final java.util.Map<Node, Gate> TABLE = java.util.Map.of(
            Node.ENV, Gates::env,
            Node.PROBE, Gates::probe,
            Node.CASES, Gates::cases,
            Node.DEV, Gates::dev,
            Node.TEST, Gates::test);

    /**
     * 这一格自己的门槛（{@code docs/ARCHITECTURE.md} §5 那一列「完成的判据 = 冻结条件」）。
     *
     * @return 空串 = 能做；否则是给用户看的那句话
     */
    public static String blocked(Node node, Facts facts) {
        if (node == null) {
            throw new IllegalArgumentException("判闸要有节点");
        }
        Gate gate = TABLE.get(node);
        if (gate == null) {
            // 加了枚举却没进表：宁可当场炸，也不要悄悄放行（那会让"没闸"看起来像"能做"）
            throw new IllegalStateException("节点 " + node + " 没有登记门槛：Gates.TABLE 里缺一行");
        }
        return gate.blocked(facts == null ? Facts.empty() : facts);
    }

    /**
     * ① 环境搭建：<b>编译命令非空 ＋（环境起过 或 声明不需要环境）＋ 容器里真能构建</b>。
     *
     * <p>三条的顺序不能反：先要一条命令（没有它，开发每次交差都只会拿到「编译没有执行」，
     * 一直空转到轮次上限）；再要环境（要跑测试就得有个能跑的地方）；最后要<b>它真能用</b>
     * ——这一条是实测撞出来的：宿主机有 Maven、容器里没有，① 亮绿灯，⑤ 进去发现 `mvn` 不存在，
     * 只好自己下载安装，烧掉 20 轮 / 30 分钟，一条用例都没验成。
     *
     * <p>「不需要环境」必须由人<b>显式声明</b>——「没写 env.yaml」与「你说过不要」是两件事；
     * 声明了不需要的，就不要求容器能力（那时压根没有容器）。
     */
    private static String env(Facts facts) {
        if (!facts.compileConfigured()) {
            return "编译命令还是空的：让「AI 探测环境」读一遍项目，或者自己填一条。"
                    + "没有它，开发提交之后只会拿到一句「编译没有执行」。";
        }
        if (!facts.envReady() && !facts.envNotNeeded()) {
            return "环境还没起来：点「起环境」跑一次（拉镜像 + 初始化 + 在容器里构建一次），"
                    + "或者显式声明「这个项目不需要环境」。"
                    + (facts.envDeclared() ? "（磁盘上已经有 env.yaml 了，但还没起成功过）" : "");
        }
        if (facts.envReady() && !facts.envNotNeeded() && !facts.containerBuild()) {
            return "环境起来了，但**容器里构建不起来**——下游（⑤ 测试）会卡死在同一处。"
                    + (facts.containerDetail().isBlank()
                    ? "还没在容器里试过构建：点「起环境」时它会自动验一次。"
                    : facts.containerDetail());
        }
        return "";
    }

    /** ② 上下文探针：有可读文件 ＋ prompt 非空。 */
    private static String probe(Facts facts) {
        if (!facts.probeFiles()) {
            return "还没有挑出「开发能读的文件」：跑一次探针，它会读一遍项目并给出清单；你也可以自己加。";
        }
        if (!facts.probePrompt()) {
            return "给开发的那段 prompt 还是空的：跑一次探针让它组装，或者自己写一段。";
        }
        return "";
    }

    /** ③ 测试用例：至少一条。 */
    private static String cases(Facts facts) {
        if (facts.caseCount() <= 0) {
            return "一条用例都没有：让 AI 依需求与设计生成一版，或者自己加一条。"
                    + "测试 Agent 就是照这份清单逐条验收的。";
        }
        return "";
    }

    /** ④ 开发：编译通过。 */
    private static String dev(Facts facts) {
        if (!facts.compilePassed()) {
            return "还没跑过开发，或者上一次编译没过：编译通过是进测试的唯一入口——"
                    + "改一半的项目拿去测，测出来的是编译错误。";
        }
        return "";
    }

    /** ⑤ 测试：清单无 PENDING ＋ 已提交。 */
    private static String test(Facts facts) {
        if (facts.casePending() > 0) {
            return "清单里还有 " + facts.casePending() + " 条没到终态（还是 PENDING）："
                    + "提交之前每条都要有结论——测过的写 PASS/FAIL，没测成的写 ERROR 并说明原因。";
        }
        if (!facts.submitted()) {
            return "测试还没提交：提交时要附一段总结——那段话是要回喂给开发的，"
                    + "说的是「现在还有什么缺陷」。" + (facts.testRun() ? "" : "（看起来还没跑过测试，先点「跑测试」）");
        }
        return "";
    }

    /** 状态在话里的说法——三档固定，不许自由发挥。 */
    private static String word(State state) {
        return switch (state) {
            case DONE -> "已经做完了";
            case READY -> "还没做完（可以做，但还没冻结）";
            case LOCKED -> "还没开始";
        };
    }
}
