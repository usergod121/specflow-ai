package com.specflow.flow;

import com.specflow.core.Event;
import com.specflow.core.EventLog;
import com.specflow.core.Layout;
import com.specflow.core.Project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>五节点的动作都在这里</b>：路由那一层只做参数翻译（把 JSON 变成 {@link Node} 与方法参数），
 * 判据一个字都不许写在路由里——写在那里就会出现「界面说能点、服务端说不能」。
 *
 * <h2>这个类做的三件事</h2>
 * <ol>
 *   <li><b>取事实</b>（{@link #facts()}）：事实只有这一处取，闸只认它；</li>
 *   <li><b>推状态</b>（{@code refresh}）：{@code DONE} 是记下来的，{@code READY}/{@code LOCKED}
 *       每次现推——不推的话 {@code GET /api/flow} 报的是「上次落盘时」那一个，
 *       而界面据此把节点灰掉，用户点了没反应却看不出为什么；</li>
 *   <li><b>判闸与作废</b>：{@link #freeze} 过不去就抛 409 人话，{@link #gotoNode} 把下游一起作废。</li>
 * </ol>
 *
 * <h2>两种「闸」的用法，别混</h2>
 * <ul>
 *   <li>{@link #requireOpen}：<b>动作</b>（跑 Agent、起环境、编译…）进来之前先问它——
 *       前置没做完一律 409；</li>
 *   <li>{@link #note}：<b>记产物</b>不问闸。因为「这一格上的活正是为了满足它自己的门槛」
 *       （跑探针就是为了产出探针的产物），按完整闸判会自锁。</li>
 * </ul>
 */
public final class FlowService {

    private final Project project;
    private final Store store;
    private Runs runs;

    public FlowService(Project project) {
        this.project = Objects.requireNonNull(project, "流程服务要有一个项目");
        this.store = new Store(project);
    }

    public Project project() {
        return project;
    }

    public Layout layout() {
        return project.layout();
    }

    public Store store() {
        return store;
    }

    /** 现在这条流程的 id。 */
    public String flowId() {
        return store.currentId();
    }

    /** 归档日志（这一条流程那一份）：过程、工具、收场、闸都从它出去（不变量 4）。 */
    public EventLog events() {
        return new EventLog(layout().events(flowId()));
    }

    /** 当前状态（每次现推一遍再给，见类注释）。 */
    public synchronized FlowState state() {
        return synced();
    }

    /** 新开一条流程：指针指过去，旧的留在盘上（回退作废的副本也在那边）。 */
    public synchronized FlowState newFlow() {
        FlowState fresh = store.newFlow();
        new EventLog(layout().events(fresh.id()))
                .say("", "info", "新开一条流程：" + fresh.id());
        return fresh;
    }

    /** 五格现在各是什么样（`GET /api/flow` 的 nodes 那一栏）。 */
    public List<NodeView> view() {
        FlowState state = state();
        Facts facts = facts(state);
        List<NodeView> out = new ArrayList<>();
        for (Node node : Node.values()) {
            String blocked = Gates.blocked(node, facts, state.states());
            out.add(new NodeView(node, state.stateOf(node), blocked, visible(state, node)));
        }
        return List.copyOf(out);
    }

    /**
     * 判闸要用的那几条事实，<b>现读</b>。
     *
     * <p>为什么不缓存：编译命令是用户能手改的、{@code env.yaml} 可能是刚才写下去的、
     * 用例条数是测试阶段刚追加的——缓存住任何一样，闸就会按一个过期的事实放行。
     *
     * <p>这里刻意<b>真读</b> {@code envReady} 那个键：上一版这一栏被写死成 {@code false}
     * （而写事实的地方用的又是另一个名字），于是「起环境成功过」这件事闸从来没看见过。
     */
    public Facts facts() {
        return facts(state());
    }

    /** 记几栏产物（各节点跑完之后回写；**不改状态**，也不问闸）。 */
    public synchronized void note(Node node, Map<String, String> values) {
        if (node == null || values == null || values.isEmpty()) {
            return;
        }
        FlowState state = synced();
        state.note(node, values);
        store.save(state);
    }

    /** 记一栏产物。 */
    public void note(Node node, String key, String value) {
        note(node, Map.of(key, value == null ? "" : value));
    }

    /**
     * 完整闸的一句话：空串 = 能做，否则就是要给用户看的那句人话。
     *
     * <p>路由与别的包想知道「现在能不能动这一格」都调它（别自己看 facts 推）。
     */
    public String blocked(Node node) {
        FlowState state = state();
        return Gates.blocked(node, facts(state), state.states());
    }

    /**
     * 动作进来之前的门：闸不过就抛 409（并往事件流写一条 {@code type=gate} 的留档，
     * 事后翻得到「当时为什么不让点」）。
     *
     * @param action 用户刚才想做的那件事（写进 409 正文，让它读起来是「你点的那件事」）
     */
    public synchronized void requireOpen(Node node, String action) {
        String blocked = blocked(node);
        if (blocked.isEmpty()) {
            return;
        }
        events().append(Event.gate(0, node.key(), blocked));
        String what = action == null || action.isBlank() ? "碰「" + node.title() + "」" : action;
        throw FlowRejected.conflict("不能" + what + "。" + blocked);
    }

    /**
     * <b>只判前置</b>：给「这一格上的活正是为了满足它自己的门槛」那几个节点用。
     *
     * <h2>为什么非要它（端到端一跑就撞上的自锁）</h2>
     * ① 的门槛是「编译命令非空 ＋（环境起过 或 声明不需要环境）」，而「探测环境」正是产出编译命令
     * 的那一步——按完整闸判，用户第一次进来连点都点不了：
     * <pre>
     *   POST /api/node/env/agent → 409「环境还没起来：点『起环境』跑一次」
     * </pre>
     * 可「起环境」又要先有 env.yaml，而 env.yaml 得靠「探测环境」写——**死锁**。② 同理。
     *
     * <p>硬闸<b>一条都没放宽</b>：前置节点不是 DONE 照样拒。放宽的只是"本节点自己的门槛"，
     * 而那个门槛本来就该由这一格的动作去满足。
     */
    public synchronized void requireReachable(Node node, String action) {
        Node previous = node.previous();
        if (previous == null || state().stateOf(previous) == State.DONE) {
            return;
        }
        String blocked = "前置「" + previous.title() + "」还没做完（现在 "
                + state().stateOf(previous).name() + "）：到那一格点「冻结」，下游才会解锁"
                + "（READY = 活干完了但还没冻住）";
        events().append(Event.gate(0, node.key(), blocked));
        String what = action == null || action.isBlank() ? "碰「" + node.title() + "」" : action;
        throw FlowRejected.conflict("不能" + what + "。" + blocked);
    }

    /**
     * 冻结：<b>「这一步做完了，冻住」</b>——闸不过就 409，正文就是闸那句话。
     *
     * <p>冻结与做完刻意是同一件事：分成两栏就会出现「冻结了但没做完」这种让闸没法判的状态
     * （下游该不该解锁？）。
     */
    public synchronized void freeze(Node node) {
        FlowState state = synced();
        String blocked = Gates.blocked(node, facts(state), state.states());
        if (!blocked.isEmpty()) {
            events().append(Event.gate(0, node.key(), blocked));
            throw FlowRejected.conflict(blocked);
        }
        state.setState(node, State.DONE);
        state.note(node, Map.of(Facts.FROZEN, "true"));
        refresh(state);
        store.save(state);
        events().append(Event.log(0, node.key(), "info", "「" + node.title() + "」做完了，冻住。"));
        // 冻结这一刻是「下一格从哪开始」的锚点：回退要截事件流，截到哪里只有这里知道
        state.note(node, Map.of(Facts.CURSOR, String.valueOf(events().lines())));
        store.save(state);
    }

    /**
     * 回退：<b>回到某节点，它自己以及下游的一切作废</b>（状态、产物、事件），
     * 并如实列出作废了什么。
     *
     * <p>三件事的顺序：先把状态与产物清掉（盘上的真相先变），再截事件流
     * （{@link EventLog#truncateTo} 会<b>先留副本</b>——回退作废的是「状态」，不是「记录」，
     * 用户点回退往往正是因为刚才那一步失败了，把失败原文一起删掉他就再也说不出报的什么），
     * 最后写一条「回到了哪一步」的留档。
     *
     * @return 给人看的一列：作废了什么、删了几条事件、副本在哪
     */
    public synchronized List<String> gotoNode(Node node) {
        FlowState state = synced();
        long keep = node.previous() == null
                ? 0
                : Math.max(0, state.number(node.previous(), Facts.CURSOR));

        List<String> invalidated = new ArrayList<>();
        for (Node target : node.from()) {
            List<String> gone = new ArrayList<>();
            state.facts().getOrDefault(target, Map.of()).forEach((key, value) -> {
                if (!Facts.CURSOR.equals(key)) {
                    gone.add(key);
                }
            });
            if (!gone.isEmpty()) {
                invalidated.add("「" + target.title() + "」的 " + gone.size() + " 项产物作废（"
                        + String.join("、", gone) + "）");
            }
            if (state.stateOf(target) == State.DONE) {
                invalidated.add("「" + target.title() + "」的「已完成」作废，要重做");
            }
            state.clearFacts(target);
            state.setState(target, State.LOCKED);
        }
        refresh(state);
        store.save(state);

        long before = events().lines();
        Path archive = events().truncateTo(Math.min(keep, before));
        long cut = before - events().lines();
        if (cut > 0) {
            invalidated.add("截掉 " + cut + " 条事件（这一段的留档）");
        }
        if (archive != null) {
            invalidated.add("作废副本：" + layout().show(archive));
        }
        events().append(Event.log(0, node.key(), "warn",
                "回到「" + node.title() + "」：它自己和下游的一切都作废了。"));
        if (invalidated.isEmpty()) {
            return List.of("回到「" + node.title() + "」：它下游还没有任何东西，所以没有作废什么。");
        }
        return List.copyOf(invalidated);
    }

    /**
     * 后台运行的登记处（懒建：没人跑活时不占线程池）。
     *
     * <p>同一时刻只许一段在跑这件事由 {@link Runs} 管——它是唯一知道「现在谁在跑」的地方。
     */
    public synchronized Runs runs() {
        if (runs == null) {
            runs = new Runs(this);
        }
        return runs;
    }

    /** 退出时收线程池（服务停之前调；活着的活由 {@code Runs.cancel} 停，不在这里杀）。 */
    public synchronized void close() {
        if (runs != null) {
            runs.close();
            runs = null;
        }
    }

    // ---------- 里面 ----------

    /** 现读 + 现推 + 变了才落盘。 */
    private FlowState synced() {
        FlowState state = store.current();
        if (refresh(state)) {
            store.save(state);
        }
        return state;
    }

    /**
     * 按「前置做完了没有」重算每一格（{@code DONE} 不动）。
     *
     * <p>从前往后算，所以上一步的结果会喂给下一步：① 刚标成 {@code DONE}，② 这一次就该跟着
     * {@code READY}。按自己手里那份原始状态判的话，② 看到的永远是「① 还没做完」。
     *
     * @return 有没有改动（没有就不写盘：读一次盘写一次文件是白花的）
     */
    private static boolean refresh(FlowState state) {
        boolean changed = false;
        for (Node node : Node.values()) {
            if (state.stateOf(node) == State.DONE) {
                continue;
            }
            Node previous = node.previous();
            State want = previous == null || state.stateOf(previous) == State.DONE
                    ? State.READY : State.LOCKED;
            if (state.stateOf(node) != want) {
                state.setState(node, want);
                changed = true;
            }
        }
        return changed;
    }

    private Facts facts(FlowState state) {
        Map<String, String> envBag = state.facts().getOrDefault(Node.ENV, Map.of());
        return new Facts(
                project.compile(),
                state.flag(Node.ENV, Facts.ENV_READY),
                state.flag(Node.ENV, Facts.ENV_NOT_NEEDED),
                // 两条来源取或：盘上真有 env.yaml，或者有人（环境 AI / 人）已经记过这一栏
                state.flag(Node.ENV, Facts.ENV_DECLARED)
                        || Files.isRegularFile(layout().envYaml()),
                state.flag(Node.PROBE, Facts.PROBE_FILES),
                state.flag(Node.PROBE, Facts.PROBE_PROMPT),
                (int) Math.min(Integer.MAX_VALUE, state.number(Node.CASES, Facts.CASE_COUNT)),
                (int) Math.min(Integer.MAX_VALUE, state.number(Node.CASES, Facts.CASE_PENDING)),
                state.flag(Node.TEST, Facts.TEST_RUN),
                state.flag(Node.TEST, Facts.SUBMITTED),
                state.flag(Node.DEV, Facts.COMPILE_PASSED),
                state.flag(Node.ENV, Facts.CONTAINER_BUILD),
                envBag.getOrDefault(Facts.CONTAINER_DETAIL, ""));
    }

    /**
     * 给界面看的那一份产物：**内部键不进界面**。
     *
     * <p>界面把 facts 里每个键画成一枚徽章（`lib/facts.js`），而 {@link Facts#CURSOR}
     * 这种锚点对人没有意义——它会变成一枚没人看得懂的「_cursor」徽章。
     */
    private static Map<String, String> visible(FlowState state, Node node) {
        Map<String, String> bag = state.facts().get(node);
        if (bag == null || bag.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        bag.forEach((key, value) -> {
            if (!key.startsWith("_")) {
                out.put(key, value);
            }
        });
        return out;
    }
}
