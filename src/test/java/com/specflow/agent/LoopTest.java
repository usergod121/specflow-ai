package com.specflow.agent;

import com.specflow.agent.tools.SubmitTool;
import com.specflow.core.Llm;
import com.specflow.core.Progress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReAct 循环。四条最要紧的：
 * <ol>
 *   <li>一轮工具 + 一轮交差 = 收成 {@code DONE}，轮次如实；</li>
 *   <li>工具抛异常循环<b>不死</b>（翻成 {@code ok=false} 喂回模型）；</li>
 *   <li>轮次上限到点收成 {@code ROUND_LIMIT}；</li>
 *   <li><b>Watcher 先收到 starting、后收到 finished</b>，同一个线程、顺序正确——这条最重要。</li>
 * </ol>
 */
class LoopTest {

    /** 脚本化的假模型：按顺序发答复，用完了重复最后一条。 */
    private static final class Scripted implements Loop.Model {

        private final List<String> replies;
        private final List<List<Llm.Msg>> seen = new ArrayList<>();
        private int at;

        Scripted(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public String complete(List<Llm.Msg> messages) {
            seen.add(List.copyOf(messages));
            String reply = replies.get(Math.min(at, replies.size() - 1));
            at++;
            return reply;
        }
    }

    /** 一次工具调用的脚本。 */
    private static String call(String tool, String args) {
        return "```" + Protocol.TOOL_FENCE + "\n{\"name\":\"" + tool + "\",\"arguments\":" + args + "}\n```";
    }

    private static final String ANSWER = """
            ```json
            {"done":true}
            ```""";

    /** 会记账的假工具：跑的时候往 trace 里写一笔，用来验证「starting 在它之前」。 */
    private static final class Echo implements Tool {

        private final List<String> trace;

        Echo(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显一句话";
        }

        @Override
        public Map<String, Param> params() {
            return Map.of("text", Param.required("string", "要回显的话"));
        }

        @Override
        public ToolResult run(Map<String, Object> args) {
            trace.add("run:echo");
            return ToolResult.ok(Map.of("echo", String.valueOf(args.get("text"))));
        }
    }

    /** 内部炸掉的工具：循环不许因此死掉。 */
    private static final class Boom implements Tool {

        @Override
        public String name() {
            return "boom";
        }

        @Override
        public String description() {
            return "一定会炸";
        }

        @Override
        public Map<String, Param> params() {
            return Map.of();
        }

        @Override
        public ToolResult run(Map<String, Object> args) {
            throw new IllegalStateException("内部炸了");
        }
    }

    private static Loop loop(Scripted model, List<String> trace, Watcher watcher, LoopLimits limits) {
        ToolRegistry tools = ToolRegistry.of(new Echo(trace), new Boom(), new SubmitTool());
        return new Loop("测试", model, limits, tools, watcher, Progress.quiet());
    }

    // ---------- 一轮工具 + 一轮交差 ----------

    @Test
    void toolRoundThenSubmitSettlesDone() {
        Scripted model = new Scripted(
                call("echo", "{\"text\":\"hi\"}"),
                call("submit", "{\"note\":\"改完了\"}"));
        List<String> trace = Collections.synchronizedList(new ArrayList<>());

        Outcome outcome = loop(model, trace, Watcher.none(), LoopLimits.defaults())
                .run("系统提示词", "开工");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.status()).isEqualTo(Outcome.DONE);
        assertThat(outcome.statusLabel()).isEqualTo("已完成");
        assertThat(outcome.rounds()).isEqualTo(2);
        assertThat(outcome.modelCalls()).isEqualTo(2);
        assertThat(outcome.answer()).isEqualTo("改完了");
        assertThat(outcome.calls()).hasSize(2);
        assertThat(outcome.calls().get(0).tool()).isEqualTo("echo");
        assertThat(outcome.calls().get(0).round()).isEqualTo(1);
        assertThat(outcome.calls().get(1).tool()).isEqualTo("submit");
        assertThat(outcome.calls().get(1).round()).isEqualTo(2);
    }

    @Test
    void answerRoundAlsoSettlesDone() {
        Scripted model = new Scripted(ANSWER);

        Outcome outcome = loop(model, new ArrayList<>(), Watcher.none(), LoopLimits.defaults())
                .run("系统提示词", "开工");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.rounds()).isEqualTo(1);
        assertThat(outcome.answer()).isEqualTo("{\"done\":true}");
    }

    // ---------- 工具结果注入 ----------

    @Test
    void toolResultIsInjectedIntoNextRound() {
        Scripted model = new Scripted(
                call("echo", "{\"text\":\"hi\"}"),
                ANSWER);

        loop(model, new ArrayList<>(), Watcher.none(), LoopLimits.defaults())
                .run("系统提示词", "开工");

        List<Llm.Msg> second = model.seen.get(1);
        assertThat(second.get(second.size() - 1).role()).isEqualTo("user");
        assertThat(second.get(second.size() - 1).content())
                .contains("\"ok\":true").contains("hi");
    }

    // ---------- 工具坏了循环不死 ----------

    @Test
    void crashingToolBecomesFailureAndLoopSurvives() {
        Scripted model = new Scripted(call("boom", "{}"), call("submit", "{\"note\":\"还行\"}"));

        Outcome outcome = loop(model, new ArrayList<>(), Watcher.none(), LoopLimits.defaults())
                .run("系统提示词", "开工");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.calls()).hasSize(2);
        assertThat(outcome.calls().get(0).ok()).isFalse();
        assertThat(outcome.calls().get(0).result()).contains("内部炸了");
        assertThat(model.seen.get(1).get(model.seen.get(1).size() - 1).content())
                .contains("\"ok\":false").contains("内部炸了");
    }

    // ---------- 上限 ----------

    @Test
    void settlesRoundLimitWhenModelKeepsCallingTools() {
        Scripted model = new Scripted(call("echo", "{\"text\":\"再来\"}"));

        Outcome outcome = loop(model, new ArrayList<>(), Watcher.none(), new LoopLimits(2, 99, 5, 1))
                .run("系统提示词", "开工");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.status()).isEqualTo(Outcome.ROUND_LIMIT);
        assertThat(outcome.rounds()).isEqualTo(2);
        assertThat(outcome.modelCalls()).isEqualTo(2);
        assertThat(outcome.reason()).contains("轮次上限");
    }

    @Test
    void unparsableReplyIsRetriedOnceInTheSameRound() {
        Scripted model = new Scripted("我什么代码块都不给", ANSWER);

        Outcome outcome = loop(model, new ArrayList<>(), Watcher.none(), LoopLimits.defaults())
                .run("系统提示词", "开工");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.rounds()).isEqualTo(1);
        assertThat(outcome.modelCalls()).isEqualTo(2);
    }

    @Test
    void settlesUnparsableWhenModelNeverGivesABlock() {
        Scripted model = new Scripted("还是什么代码块都不给");

        Outcome outcome = loop(model, new ArrayList<>(), Watcher.none(), LoopLimits.defaults())
                .run("系统提示词", "开工");

        assertThat(outcome.status()).isEqualTo(Outcome.UNPARSABLE);
        // 补问两次（不是一次）：模型偶尔会把整轮回复写成一段解释、一个代码块都不给，
        // 而它是个概率问题——多花两次调用，比让整段运行白跑一遍便宜
        assertThat(outcome.modelCalls()).isEqualTo(3);
        assertThat(outcome.reason()).contains("认不出来");
    }

    @Test
    void settlesModelFailedWhenModelThrows() {
        Loop.Model model = messages -> {
            throw new IllegalStateException("连不上");
        };

        Outcome outcome = new Loop("测试", model, LoopLimits.defaults(),
                ToolRegistry.of(new SubmitTool()), Watcher.none(), Progress.quiet())
                .run("系统提示词", "开工");

        assertThat(outcome.status()).isEqualTo(Outcome.MODEL_FAILED);
        assertThat(outcome.reason()).contains("连不上");
    }

    // ---------- Watcher：先 starting、后 finished ----------

    @Test
    void watcherSeesStartingBeforeTheToolAndFinishedAfterIt() {
        Scripted model = new Scripted(
                call("echo", "{\"text\":\"hi\"}"),
                call("submit", "{\"note\":\"完\"}"));
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        List<Thread> startingThread = new ArrayList<>();
        List<Thread> finishingThread = new ArrayList<>();

        Watcher watcher = new Watcher() {
            @Override
            public void starting(int round, String tool, String argsJson) {
                startingThread.add(Thread.currentThread());
                trace.add("starting:" + round + ":" + tool);
            }

            @Override
            public void finished(int round, String tool, String argsJson, String summary,
                                 String resultFull, boolean ok, long millis) {
                finishingThread.add(Thread.currentThread());
                trace.add("finished:" + round + ":" + tool + ":" + ok);
                assertThat(summary).isNotBlank();
                assertThat(resultFull).isNotBlank();
            }
        };

        Outcome outcome = loop(model, trace, watcher, LoopLimits.defaults())
                .run("系统提示词", "开工");

        assertThat(outcome.ok()).isTrue();
        assertThat(trace).containsExactly(
                "starting:1:echo", "run:echo", "finished:1:echo:true",
                "starting:2:submit", "finished:2:submit:true");
        // 两声都在循环那个线程里（工具本体可能被挪到别的线程上去等超时）
        assertThat(startingThread).allMatch(one -> one == finishingThread.get(0));
        assertThat(startingThread.get(0)).isEqualTo(Thread.currentThread());
    }

    @Test
    void watcherFailureDoesNotKillTheLoop() {
        Scripted model = new Scripted(call("echo", "{\"text\":\"hi\"}"), ANSWER);
        Watcher angry = new Watcher() {
            @Override
            public void starting(int round, String tool, String argsJson) {
                throw new IllegalStateException("推送挂了");
            }

            @Override
            public void finished(int round, String tool, String argsJson, String summary,
                                 String resultFull, boolean ok, long millis) {
                throw new IllegalStateException("推送挂了");
            }
        };

        Outcome outcome = loop(model, new ArrayList<>(), angry, LoopLimits.defaults())
                .run("系统提示词", "开工");

        assertThat(outcome.ok()).isTrue();
    }

    // ---------- 工具超时与注入钩子 ----------

    @Test
    void toolTimeoutBecomesAFailureInsteadOfHangingTheLoop() {
        Loop.Model model = new Scripted(call("slow", "{}"), ANSWER)::complete;
        Tool slow = new Tool() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public String description() {
                return "睡很久";
            }

            @Override
            public Map<String, Param> params() {
                return Map.of();
            }

            @Override
            public ToolResult run(Map<String, Object> args) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.ok(Map.of("醒", "了"));
            }
        };

        Outcome outcome = new Loop("测试", model, new LoopLimits(4, 9, 1, 1),
                ToolRegistry.of(slow, new SubmitTool()), Watcher.none(), Progress.quiet())
                .run("系统提示词", "开工");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.calls().get(0).ok()).isFalse();
        assertThat(outcome.calls().get(0).result()).contains("超过 1 秒");
    }
}
