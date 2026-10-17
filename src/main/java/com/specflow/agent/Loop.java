package com.specflow.agent;

import com.specflow.core.Json;
import com.specflow.core.Llm;
import com.specflow.core.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 工具结果默认按 {@code result.json()} 注入一条 user 消息；
     * {@link #onToolResult} 的钩子往参数表里写这个键（字符串值）就能换掉注入的那一句。
     */
    public static final String INJECT = "inject";

    private static final Logger log = LoggerFactory.getLogger(Loop.class);

    private final String runName;
    private final Model model;
    private final LoopLimits limits;
    private final ToolRegistry tools;
    private final Watcher watcher;
    private final Progress progress;

    private BiConsumer<Map<String, Object>, ToolResult> onToolResult;
    private java.util.function.Supplier<String> anchors = () -> "";
    private Compact.Result lastCompact = new Compact.Result(false, 0, 0, 0, 0);

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
     * 工具结果注入的方式（默认：一条 user 消息，内容是 {@code result.json()}）。
     *
     * <p>钩子拿到的是<b>模型给的那份参数表</b>与结果；想换掉注入的那一句，
     * 就往参数表里写 {@link #INJECT} 键（字符串）。钩子抛异常只会被记一行日志。
     */
    public Loop onToolResult(BiConsumer<Map<String, Object>, ToolResult> hook) {
        this.onToolResult = hook;
        return this;
    }

    /**
     * 压缩时那张<b>锚点表</b>由谁提供（默认空：只留 system + 任务书 + 尾巴）。
     *
     * <p>为什么不在这里自己攒：这三件事（动过哪些文件 / 最近为什么失败 / 做到哪一条）
     * 只有<b>调用方</b>能准地说清——它知道 case 清单长什么样、哪些工具算"动过文件"。
     * 循环只管"什么时候压、压成什么形状"。
     */
    public Loop anchors(java.util.function.Supplier<String> supplier) {
        this.anchors = supplier == null ? () -> "" : supplier;
        return this;
    }

    /** 最后一次压缩的结果（没压过就是 {@code applied=false}）。 */
    public Compact.Result lastCompact() {
        return lastCompact;
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
            lastCompact = Compact.maybe(messages, anchors.get(), limits.compactChars(),
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
        String reply = model.complete(List.copyOf(messages));
        return reply == null ? "" : reply;
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
        messages.add(Llm.Msg.user(
                head(injection(argsJson, result), LoopLimits.MAX_OUTPUT_CHARS)));
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

    /** 钩子有机会换掉注入的那一句（默认就是 {@code result.json()}）。 */    private String injection(String argsJson, ToolResult result) {
        String text = result.json();
        if (onToolResult == null) {
            return text;
        }
        Map<String, Object> table = new LinkedHashMap<>();
        try {
            table.putAll(Json.map(argsJson));
        } catch (RuntimeException e) {
            // 参数本身是坏的：注册表已经给出同一份原因，这里只保证钩子还能拿到一个空表
        }
        try {
            onToolResult.accept(table, result);
        } catch (RuntimeException e) {
            log.warn("工具结果钩子抛了异常（不影响这一次运行）：{}", e.toString());
        }
        Object custom = table.get(INJECT);
        return custom instanceof String one && !one.isBlank() ? one : text;
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
