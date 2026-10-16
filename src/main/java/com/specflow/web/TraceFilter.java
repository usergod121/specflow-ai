package com.specflow.web;

import com.specflow.core.Logs;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * <b>每一次请求一条链路 id + 一头一尾两行日志</b>。
 *
 * <p>它解决的是一件很具体的事：用户说「我点了一下，什么都没发生」的时候，
 * 你要能回答「那一下到底有没有到服务端、到了哪条路由、花了多久、回了什么」。
 * 没有这两行，就只剩"猜"。
 *
 * <p>三条口径：
 * <ol>
 *   <li>id 放在 MDC 里 → 这次请求里所有日志（包括后台线程继承下去的）都带同一个 {@code req}；</li>
 *   <li><b>不记请求体</b>：里面有 env.yaml、有 prompt，甚至可能有人不小心贴了密钥。
 *       要查内容去事件流与产物文件，那里本来就是给人看的；</li>
 *   <li>失败的请求也记（状态码 + 耗时 + 异常一句话），但**堆栈交给 {@link Errors}**，
 *       免得同一个异常在日志里出现两遍。</li>
 * </ol>
 */
@Component
public class TraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String query = request.getQueryString();
        String where = request.getMethod() + " " + request.getRequestURI()
                + (query == null || query.isBlank() ? "" : "?" + query);
        try (AutoCloseable scope = Logs.scope(Logs.REQUEST, Logs.shortId())) {
            long started = System.currentTimeMillis();
            log.info("→ {}", where);
            try {
                chain.doFilter(request, response);
            } finally {
                long millis = System.currentTimeMillis() - started;
                if (response.getStatus() >= 400) {
                    log.warn("← {} {}（{}ms）", response.getStatus(), where, millis);
                } else {
                    log.info("← {} {}（{}ms）", response.getStatus(), where, millis);
                }
            }
        } catch (Exception e) {
            // scope.close() 自己不会抛；这一档是兜底，避免把 try-with-resources 的异常吞掉
            throw new ServletException(e);
        }
    }

    /** 静态资源不进日志（一行 JS/PNG 的请求会把真正有用的那几行冲散）。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/assets/") || path.endsWith(".js") || path.endsWith(".css")
                || path.endsWith(".svg") || path.endsWith(".png") || path.endsWith(".ico");
    }
}
