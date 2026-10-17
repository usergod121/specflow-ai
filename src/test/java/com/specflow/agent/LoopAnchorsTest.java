package com.specflow.agent;

import com.specflow.core.Llm;
import com.specflow.core.Progress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>压缩之后模型到底看见了什么</b>——这张锚点表是压缩唯一保住的东西。
 *
 * <h2>为什么这一组必须存在（一条只接了一半的线）</h2>
 * 压缩本身早就在（{@code Loop} 每轮第一件事就 {@code Compact.maybe}），但锚点表的内容
 * 原来<b>全靠调用方自己拼</b>，而五格 Agent 里只有 ⑤ 拼了。于是 ②③④ 一旦真的压了，
 * 模型看到的就只剩「system + 任务书 + 压缩说明 + 尾巴」——上下文是省下来了，
 * 但"它动过哪些文件、刚才为什么失败"这两句<b>最该留的话一句都没有</b>，
 * 于是它会重读同一个文件、重犯同一个错误。
 *
 * <p>这件事在盘上、在事件流里、"跑通没跑通"上都看不出来（压了、运行也成功），
 * 所以只能<b>把模型真实收到的那几条消息扣下来看</b>——假模型把每轮的 {@code messages} 抄一份，
 * 跑完直接断言压缩后那一条里有锚点。
 *
 * <p><b>阈值调小不是为了测着方便</b>：线上是 6 万字符才压，测试里用几百字符才可能在几轮之内
 * 发生；但走的是<b>同一条代码路径</b>（{@code Loop.run → Compact.maybe}），不是另写一份假压缩。
 */
class LoopAnchorsTest {

    /** 每一轮把模型看到的全部消息抄下来（压缩发生在问模型之前，所以看到的已是压缩后的）。 */
    private static final class Recorder implements Loop.Model {

        private final List<String> replies;
        private final List<List<String>> seen = new ArrayList<>();
        private int at;

        Recorder(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public String complete(List<Llm.Msg> messages) {
            List<String> flattened = new ArrayList<>();
            for (Llm.Msg one : messages) {
                flattened.add(one.role() + ":" + one.content());
            }
            seen.add(List.copyOf(flattened));
            String reply = replies.get(Math.min(at, replies.size() - 1));
            at++;
            return reply;
        }

        /** 最后一轮模型看到的那一整段（压缩之后的那份）。 */
        String lastPrompt() {
            return String.join("\n", seen.get(seen.size() - 1));
        }
    }

    private static String call(String tool, String args) {
        return "```" + Protocol.TOOL_FENCE + "\n{\"name\":\"" + tool + "\",\"arguments\":" + args + "}\n```";
    }

    private static final String ANSWER = """
            ```json
            {"done":true}
            ```""";

    /**
     * 认 {@code path} 的工具：成功时吐一大坨（好把上下文顶过阈值），每一份都带自己的
     * {@code MARKER:路径}——这样"哪一份还在、哪一份已经被压掉"可以精确断言。
     */
    private static final class Touch implements Tool {

        private final boolean ok;

        Touch(boolean ok) {
            this.ok = ok;
        }

        @Override
        public String name() {
            return "read_file";
        }

        @Override
        public String description() {
            return "读一个文件";
        }

        @Override
        public Map<String, Param> params() {
            return Map.of("path", Param.required("string", "路径"));
        }

        @Override
        public ToolResult run(Map<String, Object> args) {
            String path = String.valueOf(args.get("path"));
            if (!ok) {
                return ToolResult.failure("文件不存在：" + path
                        + "（先在 read_list 里看一眼这层有哪些文件，别再凭印象猜路径了；猜错的每一轮都在浪费预算。"
                        + "如果你已经确认过它在别的位置，就直接用那个正确的相对路径，不要重复试同一个错路径——"
                        + "重复试同一个错路径会被引擎判成原地打转，那时这一轮就白跑了。"
                        + "顺带一提：真项目里路径大小写敏感，Windows 上能过的写法在容器里不一定能过，"
                        + "所以尽量从 read_list 的输出里复制那一行，别自己敲。）");
            }
            return ToolResult.ok(Map.of("content", "y".repeat(400) + " MARKER:" + path));
        }
    }

    /** 阈值调到 300 字符、尾巴只留 2 条：几轮之内必压。 */
    private static Loop loop(Recorder model, boolean toolOk, LoopLimits limits) {
        ToolRegistry tools = ToolRegistry.of(new Touch(toolOk));
        return new Loop("锚点测试", model, limits, tools, Watcher.none(), Progress.quiet());
    }

    private static LoopLimits tiny() {
        return new LoopLimits(9, 40, 60, 1, 300, 2);
    }

    @Test
    @DisplayName("压过之后模型看得见「读过/改过的路径」——不管调用方有没有装钩子")
    void touchedPathsSurviveCompaction() {
        Recorder model = new Recorder(
                call("read_file", "{\"path\":\"src/Main.java\"}"),
                call("read_file", "{\"path\":\"README.md\"}"),
                call("read_file", "{\"path\":\"pom.xml\"}"),
                ANSWER);

        Loop loop = loop(model, true, tiny());
        Outcome outcome = loop.run("系统：你是开发", "改一处");

        assertThat(outcome.ok()).isTrue();
        assertThat(loop.lastCompact().applied()).as("阈值 300 字符，两三轮之后必压").isTrue();

        String prompt = model.lastPrompt();
        assertThat(prompt).contains("【压缩前的锚点】");
        assertThat(prompt).contains("读过/改过的路径");
        assertThat(prompt).contains("src/Main.java").contains("README.md").contains("pom.xml");
        // 压缩必须留住的那几样：system、原始任务书、说明
        assertThat(prompt).contains("系统：你是开发");
        assertThat(prompt).contains("改一处");
        assertThat(prompt).contains("不要重复已经做过的事");
        // 而被压掉的东西真的没了：第一轮那一大坨输出不在（只留尾巴最后一轮）
        assertThat(prompt).doesNotContain("MARKER:src/Main.java");
        assertThat(prompt).contains("MARKER:pom.xml");
    }

    @Test
    @DisplayName("失败原因也进锚点表（不然它会重犯同一个错）")
    void failuresSurviveCompaction() {
        Recorder model = new Recorder(
                call("read_file", "{\"path\":\"nope.java\"}"),
                call("read_file", "{\"path\":\"nope2.java\"}"),
                call("read_file", "{\"path\":\"nope3.java\"}"),
                call("read_file", "{\"path\":\"nope4.java\"}"),
                ANSWER);

        Loop loop = loop(model, false, tiny());
        Outcome outcome = loop.run("系统", "任务");

        Compact.Result compact = loop.lastCompact();
        assertThat(compact.applied())
                .as("status=%s rounds=%s messages=%s chars=%s", outcome.status(), outcome.rounds(),
                        compact.before(), compact.charsBefore())
                .isTrue();
        String prompt = model.lastPrompt();
        assertThat(prompt).contains("最近的失败").contains("文件不存在");
    }

    @Test
    @DisplayName("调用方补的那几句（进度）也在同一张表里，且排在循环自己攒的后面")
    void callerExtraAnchorsAreAppended() {
        Recorder model = new Recorder(
                call("read_file", "{\"path\":\"a.txt\"}"),
                call("read_file", "{\"path\":\"b.txt\"}"),
                call("read_file", "{\"path\":\"c.txt\"}"),
                ANSWER);

        Loop loop = loop(model, true, tiny());
        loop.anchors(() -> "- 进度：清单 10 条，还有 4 条没到终态");
        loop.run("系统", "任务");

        String prompt = model.lastPrompt();
        int paths = prompt.indexOf("读过/改过的路径");
        int progress = prompt.indexOf("- 进度：清单 10 条");
        assertThat(paths).isGreaterThanOrEqualTo(0);
        assertThat(progress).isGreaterThan(paths);
    }

    @Test
    @DisplayName("调用方补锚点时抛异常：循环照跑，能留的锚点照样留（观察者坏了不掀链子）")
    void brokenAnchorSupplierDoesNotKillTheRun() {
        Recorder model = new Recorder(
                call("read_file", "{\"path\":\"a.txt\"}"),
                call("read_file", "{\"path\":\"b.txt\"}"),
                call("read_file", "{\"path\":\"c.txt\"}"),
                ANSWER);

        Loop loop = loop(model, true, tiny());
        loop.anchors(() -> {
            throw new IllegalStateException("拼进度的时候炸了");
        });

        Outcome outcome = loop.run("系统", "任务");

        assertThat(outcome.ok()).isTrue();
        assertThat(model.lastPrompt()).contains("读过/改过的路径").contains("a.txt");
    }

    @Test
    @DisplayName("没到阈值就不动它（压缩不是每轮都干的事）")
    void staysOutOfTheWayWhenSmall() {
        Recorder model = new Recorder(
                call("read_file", "{\"path\":\"tiny.txt\"}"),
                ANSWER);

        // 阈值调到 10 万字符：这一段对话永远够不着
        Loop loop = loop(model, true, new LoopLimits(9, 40, 60, 1, 100_000, 2));
        loop.run("系统", "任务");

        assertThat(loop.lastCompact().applied()).isFalse();
        assertThat(model.lastPrompt()).doesNotContain("【压缩前的锚点】");
    }
}
