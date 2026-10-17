package com.specflow.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring 那一层的装配。它只做三件事：起内嵌 Tomcat、把命令行参数变成配置、
 * 把「当前打开的项目」放进容器。
 *
 * <p>刻意<b>只引 spring-boot-starter-web</b>：没有 JPA、没有 Security、没有 Validation。
 * 这个服务能读写用户的项目文件，它暴露的面越小越好。
 *
 * <p><b>只监听回环地址</b>（见 {@code Api}）：绑到 0.0.0.0 就等于让同网段的人能改你的代码。
 */
@SpringBootApplication
public class App {

    /** 启动时打开的项目；{@code null} = 先进欢迎页。 */
    static Path initialProject;

    public static void run(int port, Path project, boolean openBrowser) {
        initialProject = project;
        SpringApplication app = new SpringApplication(App.class);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("server.port", port);
        defaults.put("server.address", "127.0.0.1");
        // 静态资源就是前端产物（Vite 的 dist 被 pom 拷进 static/）
        defaults.put("spring.web.resources.static-locations", "classpath:/static/");
        defaults.put("spring.mvc.problemdetails.enabled", "false");
        defaults.put("logging.level.root", "warn");
        // 我们自己的级别由 logback-spring.xml 说了算（debug）；在这里覆盖会把 debug 关掉，
        // 而那正是排错时最需要的那一档（实测踩到：日志里看不到模型回了什么）
        // 监控端点挂在 /api 下面（与别的接口同一层，也就继承了"只监听回环地址"这条边界）：
        //   GET  /api/actuator/health   → 活着吗（带详情）
        //   GET  /api/actuator/metrics  → JVM / HTTP / 我们自己的计数器
        //   GET  /api/actuator/loggers   → 现在各级别是什么
        //   POST /api/actuator/loggers/com.specflow {"configuredLevel":"debug"} → **运行时**调级别
        // 最后这一条是排错时最值的一件事：出了 bug 不用重启就能把 debug 打开。
        defaults.put("management.endpoints.web.base-path", "/api/actuator");
        defaults.put("management.endpoints.web.exposure.include", "health,info,metrics,loggers");
        defaults.put("management.endpoint.health.show-details", "always");
        app.setDefaultProperties(defaults);
        ConfigurableApplicationContext context = app.run();
        // 把我们自己的计数器接上（没有这一步，/api/actuator/metrics 里只有框架的指标）
        try {
            com.specflow.core.Metrics.install(com.specflow.core.Metrics.of(
                    context.getBean(io.micrometer.core.instrument.MeterRegistry.class)));
        } catch (RuntimeException e) {
            // 没有 registry 就继续用空实现：监控缺失不该拦住引擎启动
            org.slf4j.LoggerFactory.getLogger(App.class)
                    .warn("业务计数器没接上（不影响使用）：{}", e.getMessage());
        }
        int actual = context.getEnvironment().getProperty("local.server.port", Integer.class, port);
        System.out.println("specflow 已启动：http://127.0.0.1:" + actual
                + (project == null ? "（先挑一个项目）" : "（项目：" + project + "）"));
        if (openBrowser) {
            open("http://127.0.0.1:" + actual);
        }
    }

    private static void open(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (java.io.IOException e) {
            System.out.println("浏览器没打开（不影响使用）：" + url);
        }
    }

    public static void main(String[] args) {
        run(8780, null, false);
    }
}
