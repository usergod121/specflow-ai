package com.specflow.agent;
import com.specflow.agent.tools.ApplyPatchTool;
import com.specflow.agent.tools.ReadFileTool;
import com.specflow.agent.tools.ReadListTool;
import com.specflow.agent.tools.SubmitTool;

import com.specflow.core.Llm;
import com.specflow.core.Project;
import com.specflow.core.Progress;
import com.specflow.agent.tools.Roots;
import com.specflow.env.Compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>④ 开发</b>：在白名单内改<b>真项目</b>，每次交差引擎编译一次，编译错误自动回喂。
 *
 * <h2>为什么"回喂"是这一格的关键</h2>
 * 开发 Agent 交差（{@code submit}）时它**自己不看编译**——引擎跑编译，把原始错误原文
 * 原样塞回对话让它再改一轮。这条闭环意味着：用户在 ④ 上点一次「跑开发」，
 * 中间那些"编译没过 → 它再改一次"的过程不需要人管，也不会把一份编不过的代码投给他。
 *
 * <h2>两条边界</h2>
 * <ol>
 *   <li><b>写白名单</b>：只有 ② 挑出来的那几个文件能改。工具层强制（{@code apply_patch} 会拒），
 *       提示词里再说一遍——两层都要有，因为提示词不是闸；</li>
 *   <li><b>上一轮的反馈</b>：⑤ 提交的那段总结会作为这一轮的目标带进来（"现在还有什么缺陷"）。
 *       它**不是**需求，不许覆盖需求。</li>
 * </ol>
 */
public final class Dev {

    /** 一轮开发的结果。 */
    public record Result(String status, String statusLabel, String reason, int rounds, int submits,
                         boolean compilePassed, String compileOutput, Outcome last) {

        public boolean ok() {
            return compilePassed;
        }
    }

    /** 编译没过时最多再让它改几轮（再多就是在烧钱）。 */
    private static final int MAX_SUBMITS = 4;

    private final Project project;
    private final Llm llm;
    private final Progress progress;
    private final Watcher watcher;
    private final int rounds;

    public Dev(Project project, Llm llm, Progress progress, Watcher watcher, int rounds) {
        this.project = project;
        this.llm = llm;
        this.progress = progress == null ? Progress.quiet() : progress;
        this.watcher = watcher == null ? Watcher.none() : watcher;
        this.rounds = rounds <= 0 ? 40 : rounds;
    }

    /**
     * 跑一轮开发。
     *
     * @param prompt    ② 组装好的那段 prompt（要改什么、改成什么样、不许动什么）
     * @param whitelist 允许改的文件（② 的 {@code changes}）
     * @param feedback  上一轮 ⑤ 留下的总结（可以空）
     */
    public Result run(String prompt, List<String> whitelist, String feedback) {
        return run(prompt, whitelist, List.of(), List.of(), feedback);
    }

    /**
     * 跑一轮开发（带 ② 的另外两样产物）。
     *
     * <h2>为什么要有这个重载（一条断掉的链路）</h2>
     * ② 的探针产出四样东西：{@code files}（要读的）、{@code changes}（要改的）、
     * {@code context}（<b>几段要喂给开发的文本，一条一句"为什么相关"</b>）、{@code prompt}。
     * 而原来的 {@code run} 只把 {@code changes}（当白名单）与 {@code prompt} 给了开发，
     * <b>另外两样只存不用</b>——于是用户在 ② 里改「上下文」对 ④ 一点影响都没有，
     * 想让它生效只能自己去改那段 prompt。这就是"文本替换"那条链路断在最后一步。
     *
     * @param readable 要读的文件（② 的 {@code files}）：告诉它"先读这几处"
     * @param context  上下文（② 的 {@code context}）：一条一句"为什么相关"
     */
    public Result run(String prompt, List<String> whitelist, List<String> readable,
                      List<String> context, String feedback) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("给开发的 prompt 是空的：它照这段干活，空 prompt 等于让它自己发明需求");
        }
        if (whitelist == null || whitelist.isEmpty()) {
            throw new IllegalArgumentException("写白名单是空的：那等于让它对着整个项目猜该动哪儿");
        }
        ToolRegistry tools = ToolRegistry.of(
                StuckCheck.wrap(new ReadListTool(new Roots(project.root()))),
                StuckCheck.wrap(new ReadFileTool(new Roots(project.root()))),
                StuckCheck.wrap(new Whitelist(new ApplyPatchTool(new Roots(project.root())), whitelist)),
                new SubmitTool());

        Loop loop = new Loop("开发", llm, new LoopLimits(rounds, 200, 300, 1), tools, watcher, progress);
        String system = systemPrompt(whitelist);
        String message = userMessage(prompt, readable, context, feedback);

        int submits = 0;
        int totalRounds = 0;
        Compiler.Result compiled = null;
        Outcome last = null;
        for (int attempt = 1; attempt <= MAX_SUBMITS; attempt++) {
            Outcome outcome;
            try {
                outcome = loop.run(system, message);
            } catch (RuntimeException e) {
                return new Result("MODEL_FAILED", "模型没回来",
                        "连不上模型或者它连续报错：" + e.getMessage(), totalRounds, submits,
                        false, compiled == null ? "" : compiled.output(), last);
            }
            last = outcome;
            totalRounds += outcome.rounds();
            if (!outcome.ok()) {
                return new Result(outcome.status(), outcome.statusLabel(),
                        outcome.reason() == null || outcome.reason().isBlank()
                                ? "它没交出改动" : outcome.reason(),
                        totalRounds, submits, false,
                        compiled == null ? "" : compiled.output(), last);
            }
            submits++;
            compiled = Compiler.compile(project, progress);
            if (compiled.passed()) {
                return new Result("DONE", "已完成", "", totalRounds, submits, true,
                        compiled.output(), last);
            }
            if (compiled.isNone()) {
                // 没配编译命令不是它的错：如实收场，并说清要去哪配（① 的闸本该先拦住这一步）
                return new Result("NO_COMPILE_COMMAND", "没配编译命令", compiled.output(),
                        totalRounds, submits, false, compiled.output(), last);
            }
            if (attempt == MAX_SUBMITS) {
                break;
            }
            // 回喂：原始错误原文一字不改地交给它（总结过的错误会让它照着总结去猜）
            message = """
                    你上一轮交了差，但引擎编译没过。**原始错误全文**如下（一个字都没改）：

                    ```
                    %s
                    ```

                    照着它改。改完再 submit 一次。
                    """.formatted(compiled.output());
            progress.warn("编译没过（第 " + submits + " 次交差）：把原始错误回喂给它再改一轮");
        }
        return new Result("COMPILE_FAILED", "编译没过",
                "它改了 " + submits + " 轮，编译还是没过。最后一次的原始错误见下",
                totalRounds, submits, false, compiled == null ? "" : compiled.output(), last);
    }

    // ---------- 提示词 ----------

    private String systemPrompt(List<String> whitelist) {
        return """
                你是这个项目里的**开发**。你在**真项目**上改代码，改完交差，引擎编译一次。

                规矩：

                1. **只许改下面「写白名单」里列出的文件。** 改别的文件会被工具直接拒掉——
                   那不是建议，是边界。
                2. 改之前先把要改的文件**读一遍**（`read_file`）。凭印象改会改坏。
                3. 改动用 `apply_patch`：给出**要替换的原文**（必须在文件里**恰好出现一次**）与**替换成什么**。
                   一次改动一小步，改完自己读一遍确认。
                4. 目标是「需求说的行为对了」，**不是**「让某一条断言通过」：
                   不许为了让测试过而写死返回值、加特判、把断言改掉。
                5. 需求没要求的地方不要顺手重构（改得越少越安全）。
                6. 改完 `submit` 交差。编译没过的话，原始错误会回给你，照着它改，再交一次。

                ## 写白名单（只有这些能改）

                %s
                """.formatted(whitelist.stream().map(one -> "- " + one).reduce("", (a, b) -> a + b + "\n"))
                + "\n" + Protocol.instructions();
    }

    private String userMessage(String prompt, String feedback) {
        return userMessage(prompt, List.of(), List.of(), feedback);
    }

    /**
     * 给开发的那段话：<b>② 的四样产物按"先读什么、为什么、要改哪儿"排好</b>。
     *
     * <p>顺序是有意的：先给"这一轮要做什么"（prompt），再给"先读这几处"（readable），
     * 再给"为什么相关"（context），最后才是"要改的"（system 里的白名单）与反馈。
     * 让它在动手之前先看清、而不是直接去改。
     *
     * <p><b>可测性</b>：这个方法所以是包内可见的静态风格，就是为了单元测试能直接断言
     * "context 真的进了提示词"——否则这条链路断没断只能靠真跑一次模型才知道（那正是它断掉时没人发现的原因）。
     */
    String userMessage(String prompt, List<String> readable, List<String> context, String feedback) {
        StringBuilder out = new StringBuilder();
        out.append("## 这一轮要做的事（② 组装好的 prompt）\n\n").append(prompt.strip()).append('\n');

        List<String> reads = readable == null ? List.of() : readable;
        if (!reads.isEmpty()) {
            out.append("\n## 先读这几处（② 挑出来的入口）\n\n");
            for (String one : reads) {
                out.append("- ").append(one).append('\n');
            }
            out.append("\n它们只是入口：改之前先把里面与你这次改动相关的部分读完。\n");
        }

        List<String> notes = context == null ? List.of() : context;
        if (!notes.isEmpty()) {
            out.append("\n## 上下文（为什么这些地方相关——② 的交代）\n\n");
            for (String one : notes) {
                out.append("- ").append(one).append('\n');
            }
            out.append("\n这是别人替你摸过的底：拿它省时间，但**别把它当结论**——"
                    + "它说的地方要自己看一眼再动。\n");
        }

        if (feedback != null && !feedback.isBlank()) {
            out.append("""

                    ## 上一轮测试留下的反馈（**这一轮的目标**）

                    """).append(feedback.strip()).append("""

                    上面这些是"现在还有什么不对"，不是新需求：原需求不变，照着它把没做对的地方改对。
                    """);
        }
        out.append("\n现在开始：先 read_file 读要改的文件，再 apply_patch，最后 submit。\n");
        return out.toString();
    }

    /** 只读这些（工具层用；本包不提供别的落点）。 */
    static List<String> readable(Project project) {
        return new ArrayList<>(List.of(project.root().toString()));
    }

    static Map<String, Object> nothing() {
        return Map.of();
    }
}
