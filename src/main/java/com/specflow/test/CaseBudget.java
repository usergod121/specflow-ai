package com.specflow.test;

import com.specflow.agent.Protocol;
import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;
import com.specflow.core.Progress;

import java.util.List;
import java.util.Map;

/**
 * <b>每条用例的 ReAct 轮次预算</b>——卡的是「一条用例」，不是「整轮」。
 *
 * <h2>为什么要改（一次实测事故）</h2>
 * ⑤ 原来只有一个整轮上限（60 轮）。用户那个项目一条用例要验 HTTP 行为，光"让容器能构建"
 * 就吃掉 28 轮，于是 60 轮用尽时**12 条用例一条都没验**——预算被一条都还没开始的工作吃光了，
 * 而报告上只能写"还有 12 条没结论"。
 *
 * <p>按用例卡之后，失败的样子变了：最坏是"N 条跳过、其余照验"，
 * 而不是"整轮白跑"。跳过的那一条还会<b>留一条终态记录</b>（{@code SKIPPED} + 原因），
 * 用户能在界面上对它点「重试这一条」。
 *
 * <h2>它怎么记数（刻意做得笨）</h2>
 * 不猜"这一轮是在为哪条用例干活"——<b>当前用例 = 清单里第一条还没到终态的用例</b>
 * （提示词里就要求按顺序验），每发生一次工具调用就等于"当前这一条又用掉一轮"，
 * 一写回结论（{@code case_update}）就清零换下一条。笨一点的好处是：
 * 它的账和人一眼能看懂的账是同一本。
 *
 * <p>包装在 {@link Tool} 这一层，所以<b>所有工具</b>都算数（读文件也算）——
 * 这一层没有"免费调用"。
 */
public final class CaseBudget {

    /** 一条用例默认给多少轮。12 是实测出来的折中：够"读代码 + 起服务 + 发一次请求"，又不至于一条吃掉整轮。 */
    public static final int DEFAULT_PER_CASE = 12;

    /**
     * <b>准备阶段的宽限</b>：整轮最前面这么多次工具调用不算在任何一条用例头上。
     *
     * <p>为什么需要（端到端实测两次才看清）：Agent 在真正开始"验"之前，要先读一圈代码
     * （<code>read_list</code> 走七八次、<code>read_file</code> 读几个关键文件）。这些调用如果
     * 记在**第一条**用例的账上，那条用例会在还没开始验的时候就"超预算被跳过"——
     * 实测就是这样：C1 一秒没验，直接被标成 SKIPPED。
     *
     * <p>宽限给 8 次：够它摸清目录与几个关键文件，又不至于宽到"一条用例吃掉整轮"。
     */
    public static final int PREP_GRACE = 8;

    private final CaseList cases;
    private final int perCase;
    private final Progress progress;
    /** 每条用例各花了多少轮（**按用例记账**，不是"当前这一条"一个计数）。 */
    private final Map<String, Integer> spent = new java.util.HashMap<>();
    /** 整轮一共调了多少次（用来判准备阶段的宽限）。 */
    private int calls;
    private String current;
    /** 换到新的一条用例时回调（⑤ 用它跑 env.yaml 的 {@code reset}：让每条用例从已知状态开始）。 */
    private java.util.function.Consumer<String> onCaseStart;

    public CaseBudget(CaseList cases, int perCase, Progress progress) {
        this.cases = cases;
        this.perCase = perCase <= 0 ? DEFAULT_PER_CASE : perCase;
        this.progress = progress == null ? Progress.quiet() : progress;
    }

    /**
     * 换用例时的回调：**每条用例从同一个已知状态开始**。
     *
     * <p>为什么需要：用例之间会互相污染——C1 删掉的书、C5 改过的借阅状态，会让 C2/C6 的结论失真。
     * {@code env.yaml} 里本来就有一栏 {@code reset}（"每次跑测试之前把数据恢复到已知状态"），
     * 但它此前<b>只有命令行 {@code specflow env reset} 调过</b>，测试跑的时候一次都没用上。
     */
    public CaseBudget withCaseStart(java.util.function.Consumer<String> callback) {
        this.onCaseStart = callback;
        return this;
    }

    /** 一条用例给几轮。 */
    public int perCase() {
        return perCase;
    }

    /** 现在在验哪一条（没有待验的就是 {@code null}）。 */
    public String current() {
        return currentCase();
    }

    /** 某一条已经用掉几轮。 */
    public int spentOn(String id) {
        return spent.getOrDefault(id == null ? "" : id, 0);
    }

    /** 包一层：这一次调用记在当前用例的账上。 */
    public Tool wrap(Tool tool) {
        return new Counted(tool);
    }

    // ---------- 里面 ----------

    /** 清单里第一条还没到终态的用例。 */
    private String currentCase() {
        for (CaseList.Case one : cases.cases()) {
            if (one.status() == CaseList.Status.PENDING) {
                return one.id();
            }
        }
        return null;
    }

    /**
     * 这一条还让不让继续干。
     *
     * @return 超预算时返回一句给模型看的话（并已把这条标成跳过）；否则 {@code null}
     */
    private String exhausted() {
        String which = currentCase();
        if (which != null && !which.equals(current)) {
            // **换用例了**：先把它拉回已知状态，再让模型动它（每条用例从同一个起点开始）
            current = which;
            if (onCaseStart != null) {
                try {
                    onCaseStart.accept(which);
                } catch (RuntimeException e) {
                    progress.warn("跑 reset 失败（" + which + "）：" + e.getMessage() + "（接着验，但结论可能受上一条影响）");
                }
            }
        }
        current = which;
        if (which == null) {
            return null;
        }
        // 准备阶段的宽限：整轮最前面几次调用（摸目录、读几个关键文件）不算在任何一条头上，
        // 不然第一条用例会在"还没开始验"的时候就被标成跳过（端到端实测撞到过）
        if (calls <= PREP_GRACE) {
            return null;
        }
        if (spentOn(which) < perCase) {
            return null;
        }
        String why = "超出每用例预算（" + perCase + " 轮工具调用）还没验出结论："
                + "引擎把它标成「跳过」，好把剩下的轮次留给别的用例。"
                + "用户可以在 ⑤ 页面对它点「重试这一条」。";
        try {
            cases.skip(which, why);
        } catch (RuntimeException e) {
            // 清单写不进去也要让循环继续（否则一份坏文件会把整轮卡死）
            progress.warn("标跳过失败（" + which + "）：" + e.getMessage());
        }
        progress.warn(which + " " + why);
        return "「" + which + "」" + why + "\n别在这一条上继续了，去验下一条"
                + "（清单里还剩 " + cases.pending() + " 条没验）。";
    }

    /** 一次工具调用的包装：数一次，超预算就拦住并让它换下一条。 */
    private final class Counted implements Tool {

        private final Tool inner;

        Counted(Tool inner) {
            this.inner = inner;
        }

        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public String description() {
            return inner.description();
        }

        @Override
        public Map<String, Param> params() {
            return inner.params();
        }

        @Override
        public ToolResult run(Map<String, Object> args) {
            calls++;
            String stopped = exhausted();
            if (stopped != null) {
                // 拦住这一次：返回一份模型能读懂、能继续的失败结果（不是异常）
                return ToolResult.failure(stopped);
            }
            String which = currentCase();
            if (which != null && calls > PREP_GRACE) {
                spent.merge(which, 1, Integer::sum);
            }
            ToolResult result = inner.run(args);
            // 写了结论：这一条已经"有账"了，它不再是当前那一条（下一次调用自然记到下一条头上）
            if ("case_update".equals(inner.name()) && result != null && result.ok()) {
                current = currentCase();
            }
            return result;
        }
    }

    /** 给提示词用的那一段话（预算这件事必须让模型知道，否则它一定死磕第一条）。 */
    public static String prompt(List<CaseList.Case> all, int perCase) {
        return "预算：清单里共 " + all.size() + " 条，**每条最多 " + perCase + " 轮工具调用**。"
                + "超了引擎会把那一条标成「跳过」，并把你的注意力推到下一条——"
                + "所以别为一条用例死磕：拿不到结论也要在预算内写清「卡在哪」（ERROR）。\n"
                + "顺序：按清单顺序一条一条来；写完一条结论再动下一条。\n"
                + Protocol.instructions();
    }
}
