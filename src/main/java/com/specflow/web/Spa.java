package com.specflow.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 单页应用兜底：**不带点号的路径**一律交给前端路由（`/env`、`/probe`… 刷新之后照样打开）。
 *
 * <p>为什么不匹配所有路径：带点号的（`/assets/index-abc.js`）必须继续由静态资源处理器接，
 * 交给 index.html 的话前端会拿到一份 HTML 当脚本执行，报的错与真正的原因毫无关系。
 * `/api/**` 都是多段路径，也落不到这个单段模式里。
 */
@Controller
public class Spa {

    @GetMapping({"/", "/{one:[^\\.]*}", "/{one:[^\\.]*}/{two:[^\\.]*}"})
    public String index() {
        return "forward:/index.html";
    }
}
