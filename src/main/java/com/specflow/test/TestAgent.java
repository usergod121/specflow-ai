package com.specflow.test;
import com.specflow.agent.tools.Roots;

import com.specflow.agent.Outcome;
import com.specflow.agent.Loop;
import com.specflow.agent.LoopLimits;
import com.specflow.agent.Shell;
import com.specflow.agent.Tool;
import com.specflow.agent.ToolRegistry;
import com.specflow.agent.ToolResult;
import com.specflow.agent.Watcher;
import com.specflow.agent.tools.ApplyPatchTool;
import com.specflow.agent.tools.ReadFileTool;
import com.specflow.agent.tools.ReadListTool;
import com.specflow.agent.tools.SubmitTool;
import com.specflow.agent.tools.WriteFileTool;
import com.specflow.core.Llm;
import com.specflow.core.Progress;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>⑤ 测试</b>：在容器副本里按冻结的清单逐条验，结论与证据写回清单。
 *
 * <h2>为什么是两阶段</h2>
 * 「熟悉项目」与「验收」是两件事，混在一起会出问题：验收阶段如果还能随便改代码，
 * 那"验过了"这三个字就没有意义（改完再验、还是改完就算过，谁也说不清）。
 * 所以：
 * <ol>
 *   <li><b>准备</b>（{@code phase prepare} 之前）：可以装依赖、可以写脚本、
 *       可以在副本里改任何东西——那是"把环境弄成能验的样子"；</li>
 *   <li><b>验收</b>（{@code phase acceptance}）：{@code src/} <b>冻结</b>，
 *       谁再改它都会被记下来并拒掉。此时只许读、跑命令、写结论。</li>
 * </ol>
 *
 * <h2>它跑在哪</h2>
 * 跑在<b>副本</b>里（{@code sandboxRoot}），命令经 {@link Shell} 进容器。
 * 真项目一个字节都不碰——这是这一格的边界，工具层的根目录就在副本上。
 */
public final class TestAgent {

    /** 一次测试运行的结果。 */
    public record Result(String status, String statusLabel, String reason, int rounds,
                         int verdicts, Outcome last) {

        public boolean ok() {
            return "DONE".equals(status);
        }
    }

    /** 交差了但清单还没到终态时，最多再让它跑几轮。 */
    private static final int MAX_REFILLS = 3;

    /**
     * 容器里那个副本挂在哪儿（{@code env.yaml} 的 workdir）。
     *
     * <p>为什么要它：工具其实有<b>两个世界</b>——{@code read_list}/{@code read_file}/
     * {@code write_file} 走的是宿主上的副本路径，而 {@code bash} 的工作目录是<b>容器里</b>的路径。
     * 提示词原先只给了宿主路径（{@code E:/…/sandbox/xxx}），于是模型在 bash 里拿它去 {@code cd}，
     * 在 read_file 里又拿着 {@code /work/…} 去读——实测来回猜了 5 轮。
     * 现在两个路径都在提示词里点明，模型不需要猜。
     */
    public TestAgent withContainerRoot(String workdir) {
        this.containerRoot = workdir == null || workdir.isBlank() ? "/work" : workdir;
        return this;
    }

    /**
     * 只验这一条（{@code POST /api/node/test/case/retry}）。
     *
     * <p>用户单独点「重试这一条」时，不该把 12 条全重跑一遍：其余用例的结论是他昨天好不容易
     * 验出来的，被一次重试冲掉是最糟的体验。所以这一轮在提示词里点名——只验它，别动别的。
     */
    public TestAgent withFocus(String caseId) {
        this.focus = caseId == null ? "" : caseId.strip();
        return this;
    }

    private final Llm llm;
    private final Progress progress;
    private final Watcher watcher;
    private final Shell shell;
    private final CaseList cases;
    private final Approval approval;
    private final Path sandboxRoot;
    private final String requirement;
    private final int rounds;
    private final int roundsPerCase;
    private String containerRoot = "/work";
    /** 这一轮只验哪一条（用户点了「重试这一条」时非空）。 */
    private String focus = "";
    /** 单条命令最多等多久（「这一步我愿意等多久」那个唯一旋钮，见 {@code env.Budgets}）。 */
    private long waitSeconds = com.specflow.env.Budgets.defaultSeconds();
    /** 每写回一条结论之后干什么（调用方用它落运行级断点）。 */
    private Runnable onCaseDone;

    /** 单条命令的等多久由调用方给（同一个旋钮）；不给就用默认。 */
    public TestAgent withWaitSeconds(long seconds) {
        this.waitSeconds = seconds <= 0 ? com.specflow.env.Budgets.defaultSeconds() : seconds;
        return this;
    }

    /**
     * 每写回一条结论之后做什么——调用方用它落<b>运行级断点</b>
     * （{@code flow/<id>/checkpoint.json}：哪几条已经到终态、下一步干什么）。
     *
     * <p>断点是"提示"，不是真相：真相是 {@code cases.json}。所以这个回调坏了也不影响验证本身。
     */
    public TestAgent withOnCaseDone(Runnable callback) {
        this.onCaseDone = callback;
        return this;
    }

    public TestAgent(Llm llm, Progress progress, Watcher watcher, Shell shell, CaseList cases,
                     Approval approval, Path sandboxRoot, String requirement, int rounds) {
        this(llm, progress, watcher, shell, cases, approval, sandboxRoot, requirement, rounds,
                CaseBudget.DEFAULT_PER_CASE);
    }

    /**
     * @param rounds        整轮上限（兜底：正常情况下先被「每条用例」的预算挡住）
     * @param roundsPerCase <b>每条用例</b>最多几轮工具调用——见 {@link CaseBudget}
     */
    public TestAgent(Llm llm, Progress progress, Watcher watcher, Shell shell, CaseList cases,
                     Approval approval, Path sandboxRoot, String requirement, int rounds,
                     int roundsPerCase) {
        this.llm = llm;
        this.progress = progress == null ? Progress.quiet() : progress;
        this.watcher = watcher == null ? Watcher.none() : watcher;
        this.shell = shell;
        this.cases = cases;
        this.approval = approval == null ? Approval.allowAll() : approval;
        this.sandboxRoot = sandboxRoot;
        this.requirement = requirement == null ? "" : requirement;
        this.rounds = rounds <= 0 ? 60 : rounds;
        this.roundsPerCase = roundsPerCase <= 0 ? CaseBudget.DEFAULT_PER_CASE : roundsPerCase;
    }

    public Result run() {
        if (cases.count() == 0) {
            throw new IllegalArgumentException("用例清单是空的：测试就是照它逐条验的，没有清单就没东西可验");
        }
        Freeze freeze = new Freeze(sandboxRoot);
        // 每条用例的预算：**所有**工具调用都记在"当前这一条"的账上（读文件也算），
        // 超了就把那一条标成跳过并让模型换下一条——这是"一条用例吃掉整轮预算"的解药。
        CaseBudget budget = new CaseBudget(cases, roundsPerCase, progress);
        ToolRegistry tools = ToolRegistry.of(
                budget.wrap(com.specflow.agent.StuckCheck.wrap(new ReadListTool(new Roots(sandboxRoot)),
                        com.specflow.agent.StuckCheck.CASE)),
                budget.wrap(com.specflow.agent.StuckCheck.wrap(new ReadFileTool(new Roots(sandboxRoot)),
                        com.specflow.agent.StuckCheck.CASE)),
                budget.wrap(com.specflow.agent.StuckCheck.wrap(
                        new com.specflow.test.Frozen(new WriteFileTool(new Roots(sandboxRoot)), freeze),
                        com.specflow.agent.StuckCheck.CASE)),
                budget.wrap(com.specflow.agent.StuckCheck.wrap(
                        new com.specflow.test.Frozen(new ApplyPatchTool(new Roots(sandboxRoot)), freeze),
                        com.specflow.agent.StuckCheck.CASE)),
                budget.wrap(com.specflow.agent.StuckCheck.wrap(
                        new BashTool(shell, sandboxRoot, approval, progress, waitSeconds),
                        com.specflow.agent.StuckCheck.CASE)),
                budget.wrap(new CaseUpdateTool(cases, onCaseDone)),
                budget.wrap(new PhaseTool(freeze)),
                budget.wrap(new SubmitTool()));

        Loop loop = new Loop("测试", llm, new LoopLimits(rounds, 400, waitSeconds, 1), tools, watcher, progress);
        // 每条工具结果后面钉一行"你在哪"：解释一次不如每条都报一次
        //（SWE-agent 给每条 observation 都钉 (Current directory: …)，见其 07.yaml:68-72）
        loop.grounding(() -> containerRoot + "（项目根；文件工具用相对路径，bash 用这个路径）");
        // 锚点表里「动过的路径 / 最近的失败」由 Loop 自己攒（五格一致，见 Loop.anchors 的注释）；
        // 这一层只补它独有的那一句：清单还剩几条、现在在验哪条。
        loop.anchors(() -> {
            StringBuilder out = new StringBuilder();
            out.append("- 进度：清单 ").append(cases.count()).append(" 条，还有 ")
                    .append(cases.pending()).append(" 条没到终态");
            String now = budget.current();
            if (now != null) {
                out.append("（当前在验 ").append(now).append("，已用 ")
                        .append(budget.spentOn(now)).append('/').append(budget.perCase()).append(" 轮）");
            }
            return out.toString();
        });
        String system = systemPrompt();
        String message = userMessage(freeze);
        Outcome last = null;
        int totalRounds = 0;
        for (int attempt = 1; attempt <= MAX_REFILLS + 1; attempt++) {
            try {
                last = loop.run(system, message);
            } catch (RuntimeException e) {
                return new Result("MODEL_FAILED", "模型没回来",
                        "连不上模型或者它连续报错：" + e.getMessage(), totalRounds, 0, last);
            }
            totalRounds += last.rounds();
            if (!last.ok()) {
                // 撞了整轮上限也**如实报告已经写下的结论数**（原来恒报 0，是假账）
                return new Result(last.status(), last.statusLabel(),
                        last.reason() == null || last.reason().isBlank() ? "它没交出差" : last.reason(),
                        totalRounds, decided(), last);
            }
            int pending = cases.pending();
            if (pending == 0) {
                return new Result("DONE", "已完成", "", totalRounds, decided(), last);
            }
            if (attempt > MAX_REFILLS) {
                break;
            }
            // 回喂：还有哪几条没有结论（一条都不许留 PENDING）
            message = """
                    你交了差，但清单里还有 %d 条没有结论：

                    %s

                    每一条都要落到一个终态：PASS（要有证据原文）或 FAIL/ERROR（要说清为什么）。
                    回去把这几条验完再 submit。每条最多 %d 轮——拿不到结论就写 ERROR 说清卡在哪。
                    """.formatted(pending, pendingLines(), roundsPerCase);
            progress.warn("还有 " + pending + " 条没有结论：回喂给它继续验");
        }
        return new Result("PENDING_LEFT", "还有没结论的",
                "它验了几轮，清单里仍然有 " + cases.pending() + " 条没有结论", totalRounds, decided(), last);
    }

    /** 已经写下的<b>真结论</b>条数（PASS/FAIL；跳过与 ERROR 不算）。 */
    private int decided() {
        int n = 0;
        for (CaseList.Case one : cases.cases()) {
            if (one.decided()) {
                n++;
            }
        }
        return n;
    }

    private String pendingLines() {
        List<String> found = new ArrayList<>();
        for (CaseList.Case one : cases.cases()) {
            if (one.status() == CaseList.Status.PENDING) {
                found.add("- " + one.id() + " " + one.what()
                        + (one.tags().isEmpty() ? "" : "（" + String.join("/", one.tags()) + "）"));
            }
        }
        return String.join("\n", found);
    }

    // ---------- 提示词 ----------

    private String systemPrompt() {
        String focusLine = focus.isBlank() ? ""
                : "\n**这一轮只验 " + focus + " 这一条**（用户单独点了「重试这一条」）。\n"
                + "其余用例的结论是之前验出来的，**一个字都不许动**：不要对它们调 case_update。\n";
        return """
                你是这个项目里的**测试**。你在一个**副本**里干活（真项目一个字节都不碰），
                命令都进容器里跑。
                %s
                你要按下面这份清单**逐条**验，并把每一条的结论写回清单（`case_update`）：

                %s

                顺序（别跳）：

                1. **先熟悉项目**：读代码，搞清楚这个东西是干什么的、入口在哪、怎么跑起来。
                2. **再理解需求**：需求原话在下面。你要回答的问题是"需求说的行为，现在到底对不对"。
                3. **最后逐条验收**：一条一条来。跑命令、看输出，拿到**证据原文**再下结论。

                规矩：

                1. **每条都要有终态**：`PASS` 必须带 `evidence`（你实际跑出来的那几行原文，
                   不许写"看起来对"）；`FAIL`/`ERROR` 必须带 `note` 说清哪里不对。
                   **一条都不许留在 PENDING。**
                2. **每条用例最多 %d 轮工具调用**：超了引擎会把那一条标成「跳过」并把注意力推到下一条。
                   所以别为一条死磕——拿不到结论也要在预算内写 `ERROR` 说清卡在哪一步。
                3. **期望是模糊的**（一句话意图），具体怎么验由你定——但**不许**为了让某条过而改代码。
                   验收阶段 `src/` 是冻结的，你改它会被记下来并拒掉。
                4. 命令只在副本里跑。**不要自己装工具链**（mvn / jdk / node 那类）：
                   装了也会被拒，而且容器是一次性的，下一轮什么都不剩。缺工具就写 `ERROR`
                   说清"容器里没有 X"——那是 ① 环境声明该修的事。
                5. 遇到危险命令（动设备、把网上的脚本交给 shell）引擎会停下来问用户，
                   被拒了就换一种做法，别硬来。提权（`sudo`）在容器里不可能成功，别试。
                6. 全部验完 `submit`。

                怎么调工具、怎么交差：见下面那段协议。
                """.formatted(focusLine, cases.cases().isEmpty() ? "（清单是空的——这不该发生）"
                        : cases.cases().stream()
                        .map(one -> "- " + one.id() + " " + one.what() + "（期望：" + one.expect() + "）")
                        .reduce("", (a, b) -> a + b + "\n"),
                        roundsPerCase)
                + "\n" + com.specflow.agent.Protocol.instructions();
    }

    private String userMessage(Freeze freeze) {
        return """
                ## 需求（用户原话）

                %s

                ## 你现在在哪（两个世界，别搞混）

                - **文件工具**（`read_list` / `read_file` / `write_file` / `apply_patch`）的路径
                  是**相对项目根**的，只有一种写法：`src/main/java/...`、`pom.xml` 这样。
                  不要写 `E:/...` 也不要写 `/work/...`——这两个都会被拒。
                - **`bash`** 在容器里跑，工作目录就是项目根（容器里叫 `%s`）。
                  所以 bash 里可以直接 `ls`、`cat pom.xml`；要指定目录也用**容器**路径（`%s/...`）。
                - 上面说的"项目根"，宿主上是 `%s`，容器里是 `%s`——**同一份文件，两个视角**。

                ## 阶段

                现在处于**准备**阶段：可以装自己的一次性脚本、准备测试数据，把环境弄成能验的样子
                （但**不要装构建工具链**，那是 `env.yaml` 的事，引擎会拒）。
                准备好之后 `phase acceptance` 切到验收阶段（那时 `src/` 会冻结），然后再逐条验。
                """.formatted(requirement.isBlank() ? "（没给需求，只按清单验）" : requirement.strip(),
                containerRoot, containerRoot,
                sandboxRoot.toString().replace('\\', '/'), containerRoot);
    }

    /** 副本根（工具层的边界）。 */
    public Path sandboxRoot() {
        return sandboxRoot;
    }

    /** 给界面那一栏用的：它现在在哪个阶段。 */
    public Map<String, Object> nothing() {
        return Map.of();
    }

    /** 这一包内部用不到，但留着给排错（谁在改哪个副本）。 */
    static String describe(Tool tool) {
        return tool.name();
    }
}
