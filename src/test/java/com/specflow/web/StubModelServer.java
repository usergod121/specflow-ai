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

/**
 * 一个只会背台词的本机「模型服务」。
 *
 * <p>接口层的灰盒测试需要<b>真的打 HTTP 请求</b>（那样才覆盖得到路由与响应体），
 * 但真实模型既慢又不确定。所以这里起一个本机的 OpenAI 兼容端点，按顺序吐准备好的答案，
 * 同时把每次收到的请求原文留下来——「它到底问了什么」也是被测的东西。
 *
 * <p>留在请求里的那几条断言是这套测试最值钱的部分：施工单有没有被重复生成、
 * 「直接放行」时提示词里还有没有那个出口，都只能从请求本身看出来。
 */
final class StubModelServer implements AutoCloseable {

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
     */
    static StubModelServer answering(String... answers) {
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
            content = answers.poll();
        }
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("content", content);
        ObjectNode choice = JsonNodeFactory.instance.objectNode().set("message", message);
        send(exchange, 200, JsonNodeFactory.instance.objectNode()
                .set("choices", JsonNodeFactory.instance.arrayNode().add(choice)).toString());
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
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 到这一刻为止调用了几次。 */
    int calls() {
        return requests.size();
    }

    /** 第 {@code index} 次调用（从 0 数）系统提示词的原文。 */
    String systemOf(int index) {
        JsonNode messages = messagesOf(index);
        for (JsonNode message : messages) {
            if ("system".equals(message.path("role").asText())) {
                return message.path("content").asText();
            }
        }
        return "";
    }

    /**
     * 第 {@code index} 次调用要的是不是「只产施工单」那份协议。
     *
     * <p>判据与引擎那边一致（见 {@code StepsProtocol}）：系统提示词里有 STEPS 标记、
     * 没有 FLOW 标记。**这是「为施工单多花了一次调用」的唯一证据**——
     * 从运行结果上看不出来它问过，钱却是真花了。
     */
    boolean askedForStepsOnly(int index) {
        String system = systemOf(index);
        return system.contains("<<<<<<< STEPS") && !system.contains("<<<<<<< FLOW");
    }

    /** 到这一刻为止，有几次调用是在「只产施工单」。 */
    int stepsOnlyCalls() {
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
