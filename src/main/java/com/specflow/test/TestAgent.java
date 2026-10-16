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

    private final Llm llm;
    private final Progress progress;
    private final Watcher watcher;
    private final Shell shell;
    private final CaseList cases;
    private final Approval approval;
    private final Path sandboxRoot;
    private final String requirement;
    private final int rounds;

    public TestAgent(Llm llm, Progress progress, Watcher watcher, Shell shell, CaseList cases,
                     Approval approval, Path sandboxRoot, String requirement, int rounds) {
        this.llm = llm;
        this.progress = progress == null ? Progress.quiet() : progress;
        this.watcher = watcher == null ? Watcher.none() : watcher;
        this.shell = shell;
        this.cases = cases;
        this.approval = approval == null ? Approval.allowAll() : approval;
        this.sandboxRoot = sandboxRoot;
        this.requirement = requirement == null ? "" : requirement;
        this.rounds = rounds <= 0 ? 60 : rounds;
    }

    public Result run() {
        if (cases.count() == 0) {
            throw new IllegalArgumentException("用例清单是空的：测试就是照它逐条验的，没有清单就没东西可验");
        }
        Freeze freeze = new Freeze(sandboxRoot);
        ToolRegistry tools = ToolRegistry.of(
                new ReadListTool(new Roots(sandboxRoot)),
                new ReadFileTool(new Roots(sandboxRoot)),
                new com.specflow.test.Frozen(new WriteFileTool(new Roots(sandboxRoot)), freeze),
                new com.specflow.test.Frozen(new ApplyPatchTool(new Roots(sandboxRoot)), freeze),
                new BashTool(shell, sandboxRoot, approval, progress),
                new CaseUpdateTool(cases),
                new PhaseTool(freeze),
                new SubmitTool());

        Loop loop = new Loop("测试", llm, new LoopLimits(rounds, 400, 600, 1), tools, watcher, progress);
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
                return new Result(last.status(), last.statusLabel(),
                        last.reason() == null || last.reason().isBlank() ? "它没交出差" : last.reason(),
                        totalRounds, 0, last);
            }
            int pending = cases.pending();
            if (pending == 0) {
                return new Result("DONE", "已完成", "", totalRounds, cases.count(), last);
            }
            if (attempt > MAX_REFILLS) {
                break;
            }
            // 回喂：还有哪几条没有结论（一条都不许留 PENDING）
            message = """
                    你交了差，但清单里还有 %d 条没有结论：

                    %s

                    每一条都要落到一个终态：PASS（要有证据原文）或 FAIL/ERROR（要说清为什么）。
                    回去把这几条验完再 submit。
                    """.formatted(pending, pendingLines());
            progress.warn("还有 " + pending + " 条没有结论：回喂给它继续验");
        }
        return new Result("PENDING_LEFT", "还有没结论的",
                "它验了几轮，清单里仍然有 " + cases.pending() + " 条没有结论", totalRounds, 0, last);
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
        return """
                你是这个项目里的**测试**。你在一个**副本**里干活（真项目一个字节都不碰），
                命令都进容器里跑。

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
                2. **期望是模糊的**（一句话意图），具体怎么验由你定——但**不许**为了让某条过而改代码。
                   验收阶段 `src/` 是冻结的，你改它会被记下来并拒掉。
                3. 命令只在副本里跑；装依赖有次数上限，省着用。
                4. 遇到危险命令（提权、动设备、把网上的脚本交给 shell）引擎会停下来问用户，
                   被拒了就换一种做法，别硬来。
                5. 全部验完 `submit`。

                怎么调工具、怎么交差：见下面那段协议。
                """.formatted(cases.cases().isEmpty() ? "（清单是空的——这不该发生）"
                        : cases.cases().stream()
                        .map(one -> "- " + one.id() + " " + one.what() + "（期望：" + one.expect() + "）")
                        .reduce("", (a, b) -> a + b + "\n"))
                + "\n" + com.specflow.agent.Protocol.instructions();
    }

    private String userMessage(Freeze freeze) {
        return """
                ## 需求（用户原话）

                %s

                ## 你现在在哪

                副本根：`%s`（容器里挂着它）。先 `read_list` 看一眼，再 `read_file` 读关键文件。

                ## 阶段

                现在处于**准备**阶段：可以装依赖、写脚本、把环境弄成能验的样子。
                准备好之后 `phase acceptance` 切到验收阶段（那时 `src/` 会冻结），然后再逐条验。
                """.formatted(requirement.isBlank() ? "（没给需求，只按清单验）" : requirement.strip(),
                sandboxRoot.toString().replace('\\', '/'));
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
