package com.specflow.web;

import com.sun.net.httpserver.HttpExchange;

import java.net.URI;
import java.util.Locale;

/**
 * 只放行「来自本机页面」的请求。
 *
 * <p>这个服务没有认证，谁能发 HTTP 谁就能让它读写文件、调用模型。
 * 以前它只服务一个你启动时用 {@code -p} 指定的目录，误伤范围有限；
 * 一旦支持打开任意项目，范围就变成「这台机器上你点过的任何目录」——
 * 所以这道门必须在放开那条路之前就关上。
 *
 * <p>挡的是两件事：
 * <ul>
 *   <li><b>跨站请求</b>：你正在浏览别的网页时，那个网页的脚本可以往
 *       {@code http://127.0.0.1:8770} 发请求。它读不到响应（跨域），
 *       但副作用——改文件、跑一次模型——是实打实的。浏览器会给这种请求带上它自己的
 *       {@code Origin}，所以看一眼 {@code Origin} 就能挡掉。</li>
 *   <li><b>DNS 重绑定</b>：攻击者让自己的域名解析到 {@code 127.0.0.1}，
 *       浏览器就以为是同源了，这时候 {@code Origin} 是那个域名，
 *       但请求确实打到了本机。只看 {@code Origin} 挡不住，还得看 {@code Host}。</li>
 * </ul>
 *
 * <p>这是<b>纯防御</b>，不改任何正常行为：页面直接从 127.0.0.1 / localhost 打开，
 * 两者本来就会带上对应的 Host 和 Origin。
 */
final class LocalOnly {

    private LocalOnly() {
    }

    static boolean allows(HttpExchange exchange) {
        // 没有 Host 的请求不是浏览器发出来的（HTTP/1.1 要求必带），
        // 但既然判断不了来源，就当作可疑拒绝掉
        return isLoopbackHost(header(exchange, "Host"))
                && isLoopbackOrigin(header(exchange, "Origin"));
    }

    private static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    /** {@code Host} 必须是本机。支持 {@code 127.0.0.1}、{@code localhost}、{@code [::1]}，端口随意。 */
    static boolean isLoopbackHost(String host) {
        return isLoopbackName(withoutPort(host));
    }

    /**
     * {@code Origin} 可以是本机，也可以没有——同源导航和一些简单请求本来就不带它。
     * 带了就必须是本机。
     */
    static boolean isLoopbackOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            return isLoopbackName(URI.create(origin).getHost());
        } catch (IllegalArgumentException e) {
            // Origin 不是个合法 URI（浏览器偶尔会发字面量 "null"）——不放行
            return false;
        }
    }

    private static boolean isLoopbackName(String host) {
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        return name.equals("127.0.0.1") || name.equals("localhost") || name.equals("[::1]") || name.equals("::1");
    }

    /** 去掉 {@code host:port} 里的端口；IPv6 的方括号形式也要处理。 */
    private static String withoutPort(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.strip();
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            return close < 0 ? trimmed : trimmed.substring(0, close + 1);
        }
        int colon = trimmed.lastIndexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(0, colon);
    }
}
