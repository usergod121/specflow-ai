package com.specflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个只会背台词的本机「模型服务」。
 *
 * <p>接口层的灰盒测试需要<b>真的打 HTTP 请求</b>（那样才覆盖得到路由与响应体），
 * 但真实模型既慢又不确定。所以这里起一个本机的 OpenAI 兼容端点，按顺序吐准备好的答案，
 * 同时把每次收到的请求原文留下来——「它到底问了什么」也是被测的东西。
 *
 * <p>留在请求里的那几条断言是这套测试最值钱的部分：施工单有没有被重复生成、
 * 「直接放行」时提示词里还有没有那个出口，都只能从请求本身看出来。
 *
 * <p>它是 {@code public} 的，因为<b>命令行的端到端测试也用它</b>（{@code com.specflow.cli}
 * 要验的是「{@code --refeed 7,8} 真的进了发给模型的那条提示词」）。两个包各写一个假模型，
 * 迟早有一边漏掉「请求原文要留下来」这件事，而那种漏洞看起来只是某条断言「没测到」。
 */
public final class StubModelServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final Deque<String> answers;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    private StubModelServer(HttpServer server, List<String> answers) {
        this.server = server;
        this.answers = new ArrayDeque<>(answers);
    }

    /**
     * 起一个按顺序回话的假模型。
     *
     * <p>调用次数超出给的答案数时直接报错，而不是悄悄重复最后一条：
     * 多数用例都在断言「一共调了几次」，悄悄重复会让多出来的一次调用看起来是正常的。
     *
     * <p>答案里可以写 {@code {{ENTRY}}} / {@code {{ITENTRY}}}：它们是系统提示词里那两个
     * <b>入口脚本的完整路径</b>。测试产物的目录带时间戳，桩写不出那个值——真模型也是从
     * 提示词里读到的，所以这里照同一个办法填（见 {@code DevelopmentAgentTest} 里那个桩）。
     */
    public static StubModelServer answering(String... answers) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubModelServer stub = new StubModelServer(server, List.of(answers));
            server.createContext("/chat/completions", stub::answer);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("起不了假模型服务", e);
        }
    }

    private void answer(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(request);
        String content;
        synchronized (answers) {
            if (answers.isEmpty()) {
                // 用 500 回过去，而不是在这里抛：调用方看到的是「模型调用失败」，
                // 与真模型炸掉时同一个样子——多出来的那次调用会因此变得显眼
                send(exchange, 500, "{\"error\":{\"message\":\"脚本已用尽\"}}");
                return;
            }
            content = fill(answers.poll(), systemIn(request));
        }
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("content", content);
        ObjectNode choice = JsonNodeFactory.instance.objectNode().set("message", message);
        send(exchange, 200, JsonNodeFactory.instance.objectNode()
                .set("choices", JsonNodeFactory.instance.arrayNode().add(choice)).toString());
    }

    /** 入口脚本的完整路径——系统提示词里按「先单元、再集成」的顺序出现。 */
    private static final Pattern ENTRY_PATH = Pattern.compile(
            "tools/\\d{8}-\\d{6}(-\\d+)?/[A-Za-z0-9._-]+\\.(cmd|sh)");

    /** 一次请求里系统提示词的原文（填占位符用）。 */
    private static String systemIn(String request) {
        try {
            for (JsonNode message : JSON.readTree(request).path("messages")) {
                if ("system".equals(message.path("role").asText())) {
                    return message.path("content").asText();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("这次请求读不出来", e);
        }
        return "";
    }

    /**
     * 把答案里的 {@code {{ENTRY}}} / {@code {{ITENTRY}}} 换成提示词里那两个路径。
     *
     * <p>找不到时留空（这次没要集成入口时不填）——那时候答案里也不该提到它。
     */
    private static String fill(String answer, String system) {
        Matcher matcher = ENTRY_PATH.matcher(system);
        String unit = matcher.find() ? matcher.group() : "";
        String integration = matcher.find() ? matcher.group() : "";
        return answer.replace("{{ENTRY}}", unit).replace("{{ITENTRY}}", integration);
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** 项目配置里的 {@code llm.base-url}——不含 {@code /chat/completions} 后缀。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 到这一刻为止调用了几次。 */
    public int calls() {
        return requests.size();
    }

    /** 第 {@code index} 次调用（从 0 数）系统提示词的原文。 */
    public String systemOf(int index) {
        JsonNode messages = messagesOf(index);
        for (JsonNode message : messages) {
            if ("system".equals(message.path("role").asText())) {
                return message.path("content").asText();
            }
        }
        return "";
    }

    /**
     * 第 {@code index} 次调用的<b>用户消息</b>原文（多条时按顺序接起来）。
     *
     * <p>「回喂那一段有没有进提示词」只能从这里看：它排在用户消息里，
     * 而系统提示词那一份是协议——两件事，各看各的。
     */
    public String userOf(int index) {
        StringBuilder out = new StringBuilder();
        for (JsonNode message : messagesOf(index)) {
            if ("user".equals(message.path("role").asText())) {
                out.append(message.path("content").asText()).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * 第 {@code index} 次调用要的是不是「只产施工单」那份协议。
     *
     * <p>判据与引擎那边一致（见 {@code StepsProtocol}）：系统提示词里有 STEPS 标记、
     * 没有 FLOW 标记。**这是「为施工单多花了一次调用」的唯一证据**——
     * 从运行结果上看不出来它问过，钱却是真花了。
     */
    public boolean askedForStepsOnly(int index) {
        String system = systemOf(index);
        return system.contains("<<<<<<< STEPS") && !system.contains("<<<<<<< FLOW");
    }

    /** 到这一刻为止，有几次调用是在「只产施工单」。 */
    public int stepsOnlyCalls() {
        int count = 0;
        for (int index = 0; index < calls(); index++) {
            if (askedForStepsOnly(index)) {
                count++;
            }
        }
        return count;
    }

    private JsonNode messagesOf(int index) {
        try {
            return JSON.readTree(requests.get(index)).path("messages");
        } catch (IOException e) {
            throw new IllegalStateException("第 " + index + " 次请求读不出来", e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
