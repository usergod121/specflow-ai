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

    private final CaseList cases;
    private final int perCase;
    private final Progress progress;
    /** 每条用例各花了多少轮（**按用例记账**，不是"当前这一条"一个计数）。 */
    private final Map<String, Integer> spent = new java.util.HashMap<>();
    private String current;

    public CaseBudget(CaseList cases, int perCase, Progress progress) {
        this.cases = cases;
        this.perCase = perCase <= 0 ? DEFAULT_PER_CASE : perCase;
        this.progress = progress == null ? Progress.quiet() : progress;
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
        current = which;
        if (which == null) {
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
            String stopped = exhausted();
            if (stopped != null) {
                // 拦住这一次：返回一份模型能读懂、能继续的失败结果（不是异常）
                return ToolResult.failure(stopped);
            }
            String which = currentCase();
            if (which != null) {
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
