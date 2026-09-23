package com.specflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specflow.exception.SpecflowException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * HTTP 收发与查询参数的解析。
 *
 * <p>抽出来纯粹是为了不让「读请求体、写响应、解析 query」这几行重复出现在
 * 每一个接口里，也让 {@link WebServer} 只剩路由本身。
 *
 * <p>它不认识任何业务类型，只处理字节与字符串——真正的判断在各自的接口类里。
 */
final class Http {

    static final ObjectMapper JSON = new ObjectMapper();

    private Http() {
    }

    /**
     * 读取并解析 JSON 请求体。
     *
     * <p>方法不对、解析失败、或者解析出来是 {@code null} 时，都已经写回响应并返回 {@code null}——
     * 调用方看到 {@code null} 直接 return 就行，不用自己再判断。
     *
     * <p>{@code null} 那一种是必须单独管的：JSON 字面量 {@code null} 会被正常反序列化成
     * Java 的 {@code null}，不进 catch，于是调用方一声不响地 return，
     * 最后连接被关掉——客户端看到的是 0 字节响应、没有任何状态码，
     * 只能当成「服务端挂了」。五个写接口原来全是这样。
     */
    static <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, java.util.Map.of("error", "该接口只接受 POST"));
            return null;
        }
        try {
            T parsed = JSON.readValue(exchange.getRequestBody(), type);
            if (parsed == null) {
                sendJson(exchange, 400, java.util.Map.of("error",
                        "请求体是空的。这个接口要一个 JSON 对象，例如 {\"name\":\"...\"}"));
            }
            return parsed;
        } catch (IOException e) {
            // 请求体不合法属于调用方的问题，不能让它变成没有响应的连接中断。
            // Jackson 会把我们自己抛的校验错误包成一大段带类名和 StreamReadFeature 的废话，
            // 那句话本来就是写给用户看的，直接用；只有 Jackson 自己的错误才需要加前缀。
            sendJson(exchange, 400, java.util.Map.of("error", describe(e)));
            return null;
        }
    }

    /**
     * 把解析异常翻成一句能直接摆在用户面前的话。
     *
     * <p>Jackson 的包装层（{@code Cannot construct instance of ...}、
     * {@code StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION}）对用户没有任何意义，
     * 真正该说的是包在最里面的那句——前提是那句是我们自己写的。
     */
    private static String describe(IOException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        boolean ours = root instanceof SpecflowException || root instanceof IllegalArgumentException;
        return ours && root.getMessage() != null
                ? root.getMessage()
                : "请求体解析失败：" + e.getMessage();
    }

    /** 要求是 POST，否则写回 405 并返回 {@code false}。 */
    static boolean requirePost(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        sendJson(exchange, 405, java.util.Map.of("error", "该接口只接受 POST"));
        return false;
    }

    /** 要求是 DELETE，否则写回 405 并返回 {@code false}。 */
    static boolean requireDelete(HttpExchange exchange) throws IOException {
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;
        }
        sendJson(exchange, 405, java.util.Map.of("error", "该接口只接受 DELETE"));
        return false;
    }

    /** 取查询参数；不存在时返回 {@code fallback}。 */
    static String query(HttpExchange exchange, String name, String fallback) {
        String raw = exchange.getRequestURI().getQuery();
        if (raw == null) {
            return fallback;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return fallback;
    }

    static long queryLong(HttpExchange exchange, String name, long fallback) {
        String value = query(exchange, name, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * 读取打包在 jar 里的界面资源。
     *
     * @param absoluteResource 以 {@code /} 开头的资源路径，例如 {@code /web/index.html}
     */
    static byte[] resource(String absoluteResource) {
        String path = absoluteResource.startsWith("/web/") ? absoluteResource : "/web" + absoluteResource;
        try (InputStream in = Http.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new SpecflowException("界面资源缺失: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new SpecflowException("读取界面资源失败 " + path + "：" + e.getMessage(), e);
        }
    }
}
