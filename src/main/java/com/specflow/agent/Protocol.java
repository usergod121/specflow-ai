package com.specflow.agent;

import com.specflow.core.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型答复的解析：一段<b>工具调用块</b>，或一段<b>最终答复</b>。
 *
 * <p>这一版走的是 OpenAI 兼容的纯文本接口（{@code Llm.complete} 只回一段文本），
 * 没有结构化的工具字段可用，所以工具调用也写成一个 fenced 代码块——
 * 与补丁协议同一个思路：引擎认得很死，模型照抄样例就能对。
 *
 * <p><b>解析层不许抛</b>：认不出来就是 {@link Kind#UNPARSABLE} 加一句「为什么认不出来」，
 * 循环拿这句话去补问一次。
 */
public final class Protocol {

    /** 工具调用那一块代码块的围栏标记。 */
    public static final String TOOL_FENCE = "specflow-tool";

    /** 最终答复那一块代码块的围栏标记。 */
    public static final String ANSWER_FENCE = "json";

    /**
     * 认出来的东西。
     *
     * @param tool    工具名（只有 {@link Kind#TOOL} 时有值）
     * @param args    工具参数原文（紧凑 JSON；只有 {@code TOOL} 时有值）
     * @param answer  最终答复正文（只有 {@link Kind#ANSWER} 时有值）
     * @param problem 为什么认不出来（只有 {@link Kind#UNPARSABLE} 时有值）
     */
    public record Parsed(Kind kind, String tool, String args, String answer, String problem) {

        public Parsed {
            answer = answer == null ? "" : answer;
            problem = problem == null ? "" : problem;
        }
    }

    public enum Kind {TOOL, ANSWER, UNPARSABLE}

    private Protocol() {
    }

    public static Parsed parse(String modelReply) {
        List<Block> blocks = blocksOf(modelReply == null ? "" : modelReply);
        List<Block> tools = blocks.stream().filter(b -> TOOL_FENCE.equals(b.lang())).toList();
        List<Block> answers = blocks.stream().filter(b -> ANSWER_FENCE.equals(b.lang())).toList();
        int found = tools.size() + answers.size();
        if (found == 0) {
            return unparsable(blocks.isEmpty()
                    ? "模型没有给出任何代码块"
                    : "这一轮只给了 " + langsOf(blocks) + " 代码块；引擎只认 "
                    + TOOL_FENCE + "（调工具）与 " + ANSWER_FENCE + "（交差）两种围栏");
        }
        if (found > 1) {
            return unparsable("一轮里出现了 " + found + " 个引擎认得的代码块：一轮只能给一个"
                    + "——又调工具又交差，引擎没法替你决定先干哪个");
        }
        if (!answers.isEmpty()) {
            String body = answers.get(0).body();
            return body.isEmpty()
                    ? unparsable("那个 " + ANSWER_FENCE + " 代码块是空的")
                    : new Parsed(Kind.ANSWER, null, null, body, "");
        }
        return toolCall(tools.get(0).body());
    }

    private static Parsed toolCall(String body) {
        Map<String, Object> call;
        try {
            call = Json.map(body);
        } catch (RuntimeException e) {
            return unparsable("工具调用块不是合法 JSON：" + ToolRegistry.reason(e));
        }
        Object name = call.get("name");
        if (!(name instanceof String text) || text.isBlank()) {
            return unparsable("工具调用块里没有 name：要写成 "
                    + "{\"name\":\"read_file\",\"arguments\":{\"path\":\"pom.xml\"}}");
        }
        Object arguments = call.get("arguments");
        if (arguments == null) {
            return new Parsed(Kind.TOOL, text.strip(), "{}", null, "");
        }
        if (!(arguments instanceof Map<?, ?> map)) {
            return unparsable("工具调用块的 arguments 必须是一个 JSON 对象，收到的是："
                    + arguments.getClass().getSimpleName());
        }
        return new Parsed(Kind.TOOL, text.strip(), Json.line(map), null, "");
    }

    private static Parsed unparsable(String problem) {
        return new Parsed(Kind.UNPARSABLE, null, null, null, problem);
    }

    /**
     * 认不出来的那一次补问（同一轮里的第二次尝试，不算新的一轮）。
     *
     * <p>要把「你上一轮给的东西为什么不能用」说清楚，否则模型只会把同一段话再发一遍。
     */
    public static String retryNotice(String problem) {
        return """
                你上一轮的输出引擎认不出来：%s

                请严格按协议**只输出一个代码块**：
                - 调工具：围栏标记 %s，内容形如 {"name":"read_file","arguments":{"path":"pom.xml"}}
                - 交差：围栏标记 %s，内容就是你要交的那份 JSON
                一轮只给一个代码块，**前后不要写任何解释、不要写「好的」、不要复述要求**。
                你整条回复的内容必须从 ``` 开始、以 ``` 结束。""".formatted(problem, TOOL_FENCE, ANSWER_FENCE);
    }

    /** 给系统提示词用的协议说明（怎么调工具、怎么收场）。 */
    public static String instructions() {
        return """
                ## 怎么跟引擎说话

                引擎只认**代码块**，而且**一轮只认一个**：

                - 要调工具 → 只输出一个围栏标记为 `%s` 的代码块，里面是 JSON：
                  `{"name":"read_file","arguments":{"path":"pom.xml"}}`。
                  `arguments` 里只写该工具声明的参数，没有参数就写 `{}`。
                - 要交差 → 调 `submit` 工具（这次在做什么，写进它的 note），
                  或者只输出一个围栏标记为 `%s` 的代码块，里面就是你要交的那份 JSON。

                **一轮里这两样只能有一样。** 又调工具又交差、或者两样都不给，
                引擎会停下并把原因告诉你，这一轮不会有人替你猜。

                工具失败（路径不存在、参数写错、命令退出码非零）不算你的错，是正常的一轮：
                它返回的失败原因会原样注入下一轮，你照着改一轮就行。

                ## 循环怎么走

                1. 先用 read_list 看清结构，再用 read_file 读你要动的文件。
                2. 读多少轮由你决定：轮次、单次工具耗时都有上限，到顶会被叫停。
                3. 干完了就交差；交差之后引擎会把它自己要做的事（编译、验收）的结果投回来。"""
                .formatted(TOOL_FENCE, ANSWER_FENCE);
    }

    /** 一段代码块：围栏标记 + 正文。 */
    private record Block(String lang, String body) {
    }

    private static List<Block> blocksOf(String text) {
        List<Block> blocks = new ArrayList<>();
        String lang = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (lang == null) {
                if (trimmed.startsWith("```")) {
                    String info = trimmed.substring(3).strip();
                    lang = info.isEmpty() ? "" : info.split("\\s+")[0].toLowerCase(Locale.ROOT);
                    body.setLength(0);
                }
            } else if (trimmed.startsWith("```")) {
                blocks.add(new Block(lang, body.toString().strip()));
                lang = null;
            } else {
                body.append(line).append('\n');
            }
        }
        if (lang != null) {
            // 围栏没写完：仍然当成一块交出去，让上面报出更具体的原因
            blocks.add(new Block(lang, body.toString().strip()));
        }
        return blocks;
    }

    private static String langsOf(List<Block> blocks) {
        List<String> names = new ArrayList<>();
        for (Block block : blocks) {
            names.add(block.lang().isEmpty() ? "（没写标记的）" : block.lang());
        }
        return String.join("、", names);
    }
}
