package com.specflow.agent;

import com.specflow.core.Json;
import com.specflow.core.Llm;
import com.specflow.core.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * ReAct 循环本体：问模型 → 解析 → 执行工具 → 注入结果 → 再问。
 *
 * <pre>
 *   第 N 轮：
 *     ├─ 问一次模型
 *     ├─ 认它的输出（认不出来就同轮补问一次）
 *     ├─ 工具调用 → starting（执行前）→ 跑 → finished（执行后）→ 结果注入下一轮
 *     ├─ submit 信号 → 收成 DONE
 *     └─ 最终答复 → 收成 DONE
 *   轮次用尽 / 工具次数用尽 / 认不出来 / 模型没回来 / 用户叫停 → 各自的收场档
 * </pre>
 *
 * <p><b>三条不会破的规矩</b>：
 * <ol>
 *   <li><b>循环不因为工具坏了而崩</b>：注册表把一切异常翻成 {@code ok=false} 喂回模型；</li>
 *   <li><b>一次调用恰好一前一后两声</b>（{@link Watcher}），都在<b>本线程</b>、顺序发生
 *       ——工具本体可能被挪到另一个线程上去等超时，那两声不动；</li>
 *   <li><b>上限永远在</b>：轮次、单次工具耗时、单次输出长度、工具调用总次数。</li>
 * </ol>
 *
 * <p>本类不是线程安全的，一个实例只跑一次（记忆与调用清单都是实例状态）。
 */
public final class Loop {

    /** 模型调用口：生产用 {@code llm::complete} 适配，测试喂一个脚本化的假模型。 */
    public interface Model {
        String complete(List<Llm.Msg> messages);
    }

    private static final Logger log = LoggerFactory.getLogger(Loop.class);

    private final String runName;
    private final Model model;
    private final LoopLimits limits;
    private final ToolRegistry tools;
    private final Watcher watcher;
    private final Progress progress;

    private java.util.function.Supplier<String> extraAnchors = () -> "";
    private java.util.function.Supplier<String> grounding = () -> "";
    private Compact.Result lastCompact = new Compact.Result(false, 0, 0, 0, 0);

    /** 锚点表里"动过的路径"最多几条、"最近的失败"最多几条（再多就不是锚点，是一份日志）。 */
    static final int ANCHOR_PATHS = 12;
    static final int ANCHOR_FAILURES = 3;

    /** 这两样由循环自己攒：每一条工具结果都过一遍（谁都不用去接这个钩子）。 */
    private final Set<String> touched = new LinkedHashSet<>();
    private final List<String> failures = new ArrayList<>();

    public Loop(String runName, Llm llm, LoopLimits limits, ToolRegistry tools, Watcher watcher,
                Progress progress) {
        this(runName, llm == null ? null : llm::complete, limits, tools, watcher, progress);
    }

    public Loop(String runName, Model model, LoopLimits limits, ToolRegistry tools, Watcher watcher,
                Progress progress) {
        if (model == null) {
            throw new IllegalArgumentException("模型调用口不能为空");
        }
        if (tools == null) {
            throw new IllegalArgumentException("工具注册表不能为空");
        }
        this.runName = runName == null || runName.isBlank() ? "ReAct" : runName;
        this.model = model;
        this.limits = limits == null ? LoopLimits.defaults() : limits;
        this.tools = tools;
        this.watcher = watcher == null ? Watcher.none() : watcher;
        this.progress = progress == null ? Progress.quiet() : progress;
    }

    /**
     * 压缩时那张<b>锚点表</b>里，<b>额外</b>再加几句（默认没有）。
     *
     * <h2>"动过哪些文件 / 最近为什么失败"不归调用方管（这一条是补的）</h2>
     * 原来这张表全交给调用方拼，结果五格 Agent 里<b>只有 ⑤ 真的拼了</b>，
     * ②③④ 压出来只剩"system + 任务书 + 尾巴"——上下文是省了，可"它到底动过什么、
     * 刚才为什么失败"这两句最该留的话一句都没有。而这两件事<b>循环自己全看得见</b>
     * （每次工具调用的参数与结果）。所以现在：
     * <ul>
     *   <li><b>动过的路径 / 最近的失败</b>：循环自己攒（{@link #remember}），五格都一样；</li>
     *   <li><b>进度那一类</b>（清单还剩几条、现在在验哪条）：只有调用方知道，用这个口子补。</li>
     * </ul>
     */
    public Loop anchors(java.util.function.Supplier<String> supplier) {
        this.extraAnchors = supplier == null ? () -> "" : supplier;
        return this;
    }

    /** 压一次时交给 {@link Compact} 的那张表：循环自己攒的两句 + 调用方补的那几句。 */
    private String anchorTable() {
        StringBuilder out = new StringBuilder();
        if (!touched.isEmpty()) {
            out.append("- 读过/改过的路径：").append(String.join("、", touched)).append('\n');
        }
        if (!failures.isEmpty()) {
            out.append("- 最近的失败：").append(String.join("；", failures)).append('\n');
        }
        try {
            String extra = extraAnchors == null ? "" : extraAnchors.get();
            if (extra != null && !extra.isBlank()) {
                out.append(extra.strip()).append('\n');
            }
        } catch (RuntimeException e) {
            // 补的那几句算不出来不该掀掉整条链：能留几句就留几句
            log.warn("拼锚点表时调用方抛了异常（不影响这一次运行）：{}", e.toString());
        }
        return out.toString();
    }

    /** 攒锚点表要用的两件事：这条工具调用动了哪个路径、它为什么失败。 */
    private void remember(Map<String, Object> args, ToolResult result) {
        if (args != null) {
            Object path = args.get("path");
            if (path instanceof String one && !one.isBlank() && touched.size() < ANCHOR_PATHS) {
                touched.add(one.strip());
            }
        }
        if (result != null && !result.ok() && failures.size() < ANCHOR_FAILURES) {
            String why = String.valueOf(result.reason()).lines().findFirst().orElse("").strip();
            if (!why.isBlank()) {
                failures.add(why.length() > 90 ? why.substring(0, 90) + "…" : why);
            }
        }
    }

    /** 最后一次压缩的结果（没压过就是 {@code applied=false}）。 */
    public Compact.Result lastCompact() {
        return lastCompact;
    }

    /**
     * <b>每次工具结果后面钉一行"你在哪"</b>（默认空 = 不钉）。
     *
     * <p>为什么值得单独一个口子：实测里模型在 {@code bash} 与文件工具之间来回猜路径，
     * 白烧了 5 轮——提示词解释过"两个世界"，但<b>解释一次不如每条结果都报一次</b>。
     * 深读 SWE-agent 时看到它的做法更省：给<u>每一条</u> observation 都钉一行
     * {@code (Current directory: {{working_dir}}) (Open file: {{open_file}})}
     * （{@code config/sweagent_0_7/07.yaml:68-72}）——不解释差异，只报当前位置。
     */
    public Loop grounding(java.util.function.Supplier<String> supplier) {
        this.grounding = supplier == null ? () -> "" : supplier;
        return this;
    }

    public Outcome run(String systemPrompt, String userMessage) {
        List<Llm.Msg> messages = new ArrayList<>();
        messages.add(Llm.Msg.system(systemPrompt == null ? "" : systemPrompt));
        messages.add(Llm.Msg.user(userMessage == null ? "" : userMessage));

        List<Outcome.Call> calls = new ArrayList<>();
        int modelCalls = 0;
        int toolCalls = 0;
        int round = 0;

        while (round < limits.maxRounds()) {
            round++;
            if (progress.cancelled()) {
                return outcome(Outcome.CANCELLED, round, "用户叫停", "", calls, modelCalls);
            }
            // 每轮开头看一眼上下文有多大：太长就压成"锚点 + 尾巴"（确定性拼装，不调模型）
            lastCompact = Compact.maybe(messages, anchorTable(), limits.compactChars(),
                    limits.compactKeepTail());
            if (lastCompact.applied()) {
                progress.warn(lastCompact.describe());
                log.info("{} 第 {} 轮：{}", runName, round, lastCompact.describe());
            }

            Turn turn = turn(messages);
            modelCalls += turn.calls();
            if (turn.status() != null) {
                return outcome(turn.status(), round, turn.problem(), "", calls, modelCalls);
            }

            if (turn.parsed().kind() == Protocol.Kind.ANSWER) {
                return outcome(Outcome.DONE, round, "", turn.parsed().answer(), calls, modelCalls);
            }

            if (++toolCalls > limits.maxInstalls()) {
                return outcome(Outcome.TOOL_LIMIT, round,
                        "工具调用次数上限 " + limits.maxInstalls() + " 到点", "", calls, modelCalls);
            }

            Signal signal = runTool(round, turn.parsed(), messages, calls);
            if (signal != null) {
                return outcome(Outcome.DONE, round, "模型交差（" + signal.kind() + "）",
                        signal.note(), calls, modelCalls);
            }
        }
        return outcome(Outcome.ROUND_LIMIT, round,
                "ReAct 轮次上限 " + limits.maxRounds() + " 用尽，模型还没收工", "", calls, modelCalls);
    }

    // ---------- 一轮的原文 ----------

    /**
     * 问一次模型、认它；认不出来就<b>在同一轮里</b>补问一次（补问同样花一次模型调用）。
     *
     * <p>认不出来的原文<b>不进上下文</b>：引擎都读不懂的东西留在历史里，
     * 只会让模型下一轮照着它继续写。
     */
    private Turn turn(List<Llm.Msg> messages) {
        int calls = 0;
        // 补问**两次**（不是一次）：模型偶尔会把整轮回复写成一段解释、一个代码块都不给
        // （实测：同一份提示词，一次通过、一次「没有给出任何代码块」）。它是个概率问题，
        // 代价只是多花一次调用——比让整段运行白跑一遍便宜得多
        int retries = 0;
        String reply;
        try {
            reply = ask(messages);
            calls++;
        } catch (RuntimeException e) {
            return Turn.failed(Outcome.MODEL_FAILED, "模型调用失败：" + ToolRegistry.reason(e), calls);
        }
        Protocol.Parsed parsed = Protocol.parse(reply);
        while (parsed.kind() == Protocol.Kind.UNPARSABLE && retries < 2) {
            retries++;
            messages.add(Llm.Msg.user(Protocol.retryNotice(parsed.problem())));
            try {
                reply = ask(messages);
                calls++;
            } catch (RuntimeException e) {
                return Turn.failed(Outcome.MODEL_FAILED, "模型调用失败：" + ToolRegistry.reason(e), calls);
            }
            parsed = Protocol.parse(reply);
        }
        if (parsed.kind() == Protocol.Kind.UNPARSABLE) {
            return Turn.failed(Outcome.UNPARSABLE,
                    "模型这一轮的输出认不出来（补问 " + retries + " 次之后仍是）：" + parsed.problem(), calls);
        }
        messages.add(Llm.Msg.assistant(reply));
        return new Turn(parsed, calls, null, "");
    }

    /** 一次真实的模型调用（空答复当空串处理，交给解析层去说它认不出来）。 */
    private String ask(List<Llm.Msg> messages) {
        long started = System.currentTimeMillis();
        boolean ok = false;
        try {
            String reply = model.complete(List.copyOf(messages));
            ok = true;
            return reply == null ? "" : reply;
        } finally {
            // 模型调用是最贵的一步：成功/失败各记一次、花了多少秒也记（actuator 里看得见）
            com.specflow.core.Metrics.installed()
                    .modelCall(runName, ok, System.currentTimeMillis() - started);
        }
    }

    // ---------- 工具 ----------

    /** @return 工具给出的信号（submit 那一类）；没有就返回 {@code null}。 */
    private Signal runTool(int round, Protocol.Parsed parsed, List<Llm.Msg> messages,
                           List<Outcome.Call> calls) {
        String tool = parsed.tool();
        String argsJson = parsed.args();

        // 「开始」必须在动手之前推出去：bash 可能跑十分钟，等结果再推就等于没有流式
        notifyStarting(round, tool, argsJson);
        long started = System.currentTimeMillis();
        ToolResult result = attempt(tool, argsJson);
        long millis = System.currentTimeMillis() - started;

        String summary = ToolRegistry.cut(result.json(), 240);
        String full = head(result.json(), LoopLimits.MAX_OUTPUT_CHARS);
        notifyFinished(round, tool, argsJson, summary, full, result.ok(), millis);
        calls.add(new Outcome.Call(round, tool, argsJson, result.ok(), summary));

        // 注入的正文**保留换行**：read_file 的内容是要给模型照着读的
        String where = grounding == null ? "" : grounding.get();
        String body = head(injection(argsJson, result), LoopLimits.MAX_OUTPUT_CHARS);
        messages.add(Llm.Msg.user(where == null || where.isBlank() ? body
                : body + "\n（当前位置：" + where.strip() + "）"));
        log.info("{} 第 {} 轮 调用 {}：{}", runName, round, tool, result.ok() ? "成功" : summary);
        return result.data() instanceof Signal signal ? signal : null;
    }

    /**
     * 报「这一次调用要开始了」。
     *
     * <p>观察者抛异常就吞掉：推送失败不该掀掉整条链（它<b>只报事实、不改变任何行为</b>）。
     */
    private void notifyStarting(int round, String tool, String argsJson) {
        try {
            watcher.starting(round, tool, argsJson);
        } catch (RuntimeException e) {
            log.warn("工具观察者报错（不影响这一次运行）：{}", e.toString());
        }
    }

    /** 同上面那一条的规矩：观察者坏了，循环照跑。 */
    private void notifyFinished(int round, String tool, String argsJson, String summary,
                                String resultFull, boolean ok, long millis) {
        try {
            watcher.finished(round, tool, argsJson, summary, resultFull, ok, millis);
        } catch (RuntimeException e) {
            log.warn("工具观察者报错（不影响这一次运行）：{}", e.toString());
        }
    }

    /**
     * 工具结果怎么注入下一轮：<b>就是 {@code result.json()} 原文</b>。
     *
     * <p>顺手把锚点表的原料攒下来（{@link #remember}）——那两件事只有这里看得见：
     * 每次调用动了哪个路径、它为什么失败。
     *
     * <p>这里原来还有一个 {@code onToolResult} 钩子（可以换掉注入的那一句），
     * 但<b>生产代码一个调用点都没有，只有测试在用</b>。按"只有测试在用的口子 = 生产没接"
     * 这条判据，它被删了：需要替换注入内容的那天再连调用方一起加。
     */
    private String injection(String argsJson, ToolResult result) {
        try {
            remember(Json.map(argsJson), result);
        } catch (RuntimeException e) {
            // 参数本身是坏的：注册表已经给出同一份原因，锚点里就少这一条路径
            remember(Map.of(), result);
        }
        return result.json();
    }

    /**
     * 跑一次工具调用，带上超时与（可选的）重试。
     *
     * <p>工具本体挪到一个守护线程上跑，超时到点就不再等它——那条线程收不回来，
     * 但它不挡着循环（真正能杀进程的是 {@code Shell} 实现里的超时）。
     */
    private ToolResult attempt(String tool, String argsJson) {
        ToolResult last = null;
        for (int tries = 1; tries <= limits.toolAttempts(); tries++) {
            last = once(tool, argsJson);
            if (last.ok()) {
                return last;
            }
        }
        return last;
    }

    private ToolResult once(String tool, String argsJson) {
        long timeout = limits.toolTimeoutSeconds();
        if (timeout <= 0) {
            return tools.call(tool, argsJson);
        }
        ToolResult[] box = new ToolResult[1];
        Thread worker = new Thread(() -> box[0] = tools.call(tool, argsJson), "specflow-tool-" + tool);
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(timeout * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(tool + " 等待时被中断");
        }
        if (worker.isAlive()) {
            return ToolResult.failure(tool + " 超过 " + timeout + " 秒还没结束，引擎不再等它");
        }
        return box[0] == null ? ToolResult.failure(tool + " 没有给出结果") : box[0];
    }

    // ---------- 收场 ----------

    private Outcome outcome(String status, int rounds, String reason, String answer,
                            List<Outcome.Call> calls, int modelCalls) {
        return new Outcome(status, Outcome.label(status), rounds, modelCalls, reason, answer,
                List.copyOf(calls));
    }

    /** 超长截断：保留换行（工具结果里的正文要原样给模型看）。 */
    private static String head(String text, int limit) {
        String one = text == null ? "" : text;
        return one.length() <= limit ? one
                : one.substring(0, limit) + "\n…（超过 " + limit + " 个字符，已截断）";
    }

    /**
     * 一轮的结果。
     *
     * @param status  非空 = 这一轮没收成（{@code MODEL_FAILED} / {@code UNPARSABLE}）
     * @param problem 没收成的原因
     * @param calls   这一轮花掉的模型调用次数
     */
    private record Turn(Protocol.Parsed parsed, int calls, String status, String problem) {

        static Turn failed(String status, String problem, int calls) {
            return new Turn(null, calls, status, problem);
        }
    }
}
