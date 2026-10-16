package com.specflow.env;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code .specflow/env.yaml} 的模型（校验在 {@link EnvConfigLoader} 里，这里只管形状）。
 *
 * <p>它描述的是「环境长什么样」，不是「引擎怎么起它」：{@code YAML → compose} 是一张
 * <b>死图</b>（见 {@link Compose}），不需要任何模型参与——输入定了，输出就该定下来。
 *
 * @param image        项目跑在哪个镜像里；必填，没有它就没有那个挂项目目录的常驻容器
 * @param workdir      项目目录挂进容器的位置（容器内绝对路径）
 * @param dependencies 被测代码要连的中间件：名字 → 怎么起它。名字同时是容器互连的服务名
 * @param env          连接信息，原样注入 app 容器
 * @param init         起完环境跑一次的命令：逐条执行、失败即停
 * @param reset        每次跑测试之前跑的命令：把数据恢复到一个已知状态
 */
public record EnvConfig(String image, String workdir, Map<String, Dependency> dependencies,
                        Map<String, String> env, List<Step> init, List<Step> reset) {

    /** 没写 {@code workdir} 时挂哪：{@code /app} 常被镜像自己的 WORKDIR 占着，{@code /work} 是空的。 */
    public static final String DEFAULT_WORKDIR = "/work";

    public EnvConfig {
        image = image == null ? "" : image.strip();
        workdir = workdir == null || workdir.isBlank() ? DEFAULT_WORKDIR : workdir.strip();
        dependencies = ordered(dependencies);
        env = ordered(env);
        init = init == null ? List.of() : List.copyOf(init);
        reset = reset == null ? List.of() : List.copyOf(reset);
    }

    /**
     * 一条环境准备命令：跑什么、在哪个容器里跑、喂它哪个文件。
     *
     * <p>后两栏是用户踩出来的：{@code mysql} / {@code psql} / {@code redis-cli} 只长在依赖镜像里，
     * 而 app 容器是非 root，装不了系统包；{@code init.sql} 在<b>项目里</b>、不在依赖容器里
     * （依赖容器没挂项目目录），所以那个文件必须由引擎搬运。
     *
     * @param command 命令原文（引擎把它喂给容器里的 {@code sh} 的标准输入）
     * @param service 在哪个容器里跑；空 = app 容器。只能写 dependencies 里声明过的名字
     * @param stdin   要喂给这条命令标准输入的、<b>项目里</b>的文件（相对项目根）；空 = 不喂
     */
    public record Step(String command, String service, String stdin) {

        public Step {
            command = command == null ? "" : command.strip();
            service = service == null ? "" : service.strip();
            stdin = stdin == null ? "" : stdin.strip();
        }

        /** 一条跑在 app 容器里的命令（最常见的那一种）。 */
        public static Step inApp(String command) {
            return new Step(command, "", "");
        }

        public boolean inApp() {
            return service.isBlank();
        }
    }

    /**
     * 一个依赖（一个中间件）怎么起。
     *
     * @param image       镜像，必填
     * @param env         它的环境变量（密码、库名这类），原样写进 compose
     * @param healthcheck 健康检查要跑的那条命令；写了它，{@code up --wait} 才会等它真的可用再往下走
     *                    ——不等的话，第一次连库必然是「连不上」，而那看起来正是被测代码的错
     */
    public record Dependency(String image, Map<String, String> env, String healthcheck) {

        public Dependency {
            image = image == null ? "" : image.strip();
            env = ordered(env);
            healthcheck = healthcheck == null || healthcheck.isBlank() ? null : healthcheck.strip();
        }
    }

    /**
     * 按写下来的先后留着，而不是 {@code Map.copyOf}：后者不保证顺序，而「声明 → compose」
     * 必须是确定性的——顺序一变，两次渲染出来的文件就不一样，diff 里全是噪声。
     */
    private static <V> Map<String, V> ordered(Map<String, V> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
