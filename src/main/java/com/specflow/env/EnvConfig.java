package com.specflow.env;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试环境声明——对应项目里的 {@code .specflow/env.yaml}，<b>由用户写</b>。
 *
 * <p>它为什么单独成一份文件而不是塞进 {@code project.yaml}：两者的变化节奏不一样。
 * 项目配置是「这个项目怎么构建、怎么连模型」，一次配好基本不动；
 * 而测试环境是「这次要连哪个库、脚本跑完要怎么清数据」，用户会为不同需求改它。
 * 混在一起，改一次测试环境就要动那份碰不得的配置。
 *
 * <p><b>它是用户写的，所以每个字段都可能写错</b>，而写错的代价是「容器起不来」——
 * 那种失败看起来和代码错了没什么区别。所以解析这一层不追求宽容：认不出来的字段、
 * 缺了的字段、写错位置的行，都要在加载时带着<b>行号</b>报出来
 * （见 {@link EnvConfigLoader}），而不是等到 compose 起不来才让人去猜。
 *
 * <p>它描述的是「环境长什么样」，不是「引擎怎么起它」：{@code YAML → compose} 那段
 * 确定性映射在 {@link ComposeFile} 里，那张图是死的，不需要任何模型参与（十五.5）。
 *
 * @param dockerCommand 这台机器上 {@code docker} 命令怎么调（例如 {@code wsl docker}）。
 *                      可空：空着就按 PATH → 常见安装路径去找（见 {@link DockerProbe}）。
 *                      有这个字段是因为「Docker 装在 WSL 里」是 Windows 上很常见的一种装法，
 *                      而那种机器上 PATH 里根本没有 docker.exe
 * @param image         测试容器的镜像。必填——没有它就没有能挂项目目录的那个常驻容器
 * @param workdir       项目目录挂进容器里的位置；空着用 {@link #DEFAULT_WORKDIR}。
 *                      必须是容器内的绝对路径（以 {@code /} 开头）：它是 {@code -v} 的目标，
 *                      相对路径在 compose 里会以「命名卷」的形式被解释，挂上去的东西就不是你的代码了
 * @param dependencies  被测代码要连的中间件：名字 → 怎么起它。名字同时是容器互连用的服务名
 * @param env           连接信息，<b>原样</b>注入 app 容器、也原样进 AI 的上下文（十五.5）。
 *                      测试代码读它，不许硬编码连接串——所以它也是「模型不用猜」的落点
 * @param init          起完环境后跑一次的命令，在 app 容器里逐条执行、失败即停
 * @param reset         每次跑测试之前跑的命令：把数据恢复到一个已知状态。
 *                      它和 init 的分工是「一次性的准备」与「每次都要重来的那一步」
 */
public record EnvConfig(
        String dockerCommand,
        String image,
        String workdir,
        Map<String, Dependency> dependencies,
        Map<String, String> env,
        List<String> init,
        List<String> reset
) {

    /**
     * 没写 {@code workdir} 时项目目录挂进容器的位置。
     *
     * <p>挑 {@code /work} 而不是 {@code /app} 之类：镜像里的 {@code /app} 常被镜像自己占着
     * （有些镜像的 WORKDIR 就是它），挂上去会把镜像自带的东西盖掉。{@code /work} 是空的，
     * 冲突概率最低。
     */
    public static final String DEFAULT_WORKDIR = "/work";

    public EnvConfig {
        dockerCommand = blankToNull(dockerCommand);
        image = image == null ? "" : image.strip();
        workdir = workdir == null || workdir.isBlank() ? DEFAULT_WORKDIR : workdir.strip();
        // 按写下来的先后顺序留着，而不是 Map.copyOf：后者不保证顺序，
        // 而「YAML → compose」是一张**确定性**的映射（十五.5）——顺序一变，
        // 两次初始化出来的 compose 文件就不一样，diff 里全是噪声
        dependencies = ordered(dependencies);
        env = ordered(env);
        init = init == null ? List.of() : List.copyOf(init);
        reset = reset == null ? List.of() : List.copyOf(reset);
    }

    private static <V> Map<String, V> ordered(Map<String, V> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 一个依赖（一个中间件）怎么起。
     *
     * @param image       镜像，必填
     * @param env         它的环境变量（密码、库名这类），原样写进 compose
     * @param healthcheck 健康检查。写了它，{@code up --wait} 才会等这个依赖真的可用再往下走；
     *                    没写就只等容器起来——这两件事的差别在「测试第一次连库时它准备好了没有」，
     *                    而那种失败看起来正是「代码连不上库」
     */
    public record Dependency(String image, Map<String, String> env, Healthcheck healthcheck) {

        public Dependency {
            image = image == null ? "" : image.strip();
            env = env == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }
    }

    /**
     * 健康检查。
     *
     * <p>字段是 compose 自己的那五个，一个不多一个不少：这里不做封装、不做默认值，
     * 因为「等多久算超时」是环境的事，引擎替它定一个数字，只会在容器慢的机器上假装没问题。
     *
     * @param test        在容器里跑的那条命令；compose 的 {@code CMD-SHELL} 形式
     * @param interval    两次检查之间等多久
     * @param timeout     单次检查的超时
     * @param retries     连续失败几次算不健康
     * @param startPeriod 容器起来之后先宽限多久
     */
    public record Healthcheck(String test, String interval, String timeout,
                              String retries, String startPeriod) {

        public Healthcheck {
            test = test == null ? "" : test.strip();
            interval = blankToNull(interval);
            timeout = blankToNull(timeout);
            retries = blankToNull(retries);
            startPeriod = blankToNull(startPeriod);
        }
    }

    /** 连接信息加上引擎自己那几个变量——给测试脚本的那一份，见 {@link TestEnvironment#variables()}。 */
    public Map<String, String> variables() {
        return new LinkedHashMap<>(env);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
