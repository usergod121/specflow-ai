package com.specflow.cli;

import com.specflow.env.EnvConfigLoader;
import com.specflow.env.EnvProblem;
import com.specflow.env.EnvRegistration;
import com.specflow.env.TestEnvironment;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code specflow env} —— 测试环境这一摊的命令行入口（十五.5）。
 *
 * <p>四个动作，正好对应「人需要它的四件事」：
 * <ul>
 *   <li>{@code status}——这台机器上能不能跑集成、现在环境是什么状态。**先问再动手**，
 *       是这个工具一贯的口径：Docker 没装和 daemon 没起要做的事完全不同，
 *       合并成一句「不可用」会把人指向错误的方向；</li>
 *   <li>{@code init}——起环境（{@code up -d --wait} + {@code init} 命令）。
 *       界面上是「导入项目时问一次」的那个问题，命令行上就是这条命令：它不自动跑，
 *       因为起容器要拉镜像，不该在没人点头的时候发生；</li>
 *   <li>{@code reset}——跑一次 {@code reset} 命令，把数据恢复到一个已知状态。
 *       它和 {@code init} 的分工就是 {@code env.yaml} 里那两栏的分工：
 *       {@code init} 是「起完跑一次」，{@code reset} 是「每次跑测试之前都要重来的那一步」。
 *       界面上它藏在收场与跑测试之前（没有单独的按钮），所以命令行必须给一个入口——
 *       否则「上一轮的数据把这一轮的断言带偏」这件事，命令行用户没有任何办法处理；</li>
 *   <li>{@code clear}——{@code down -v}，连卷一起。这是那个「清空测试环境」的手动入口。</li>
 * </ul>
 *
 * <p>它<b>不</b>创建 {@code env.yaml}：那份文件的内容是「这个项目要连什么」，
 * 只有用户知道。工具替它编一份，编出来的必然是错的，而错的连接信息比没有更糟。
 */
@Command(name = "env", description = "测试环境：查状态、初始化、重置数据、清空")
public final class EnvCommand implements Callable<Integer> {

    /** 四个动作。写成常量是因为 {@code status} 是默认值，别处也要用同一个字面量。 */
    static final String STATUS = "status";
    static final String INIT = "init";
    static final String RESET = "reset";
    static final String CLEAR = "clear";

    @Option(names = {"-p", "--project"}, description = "项目根目录",
            defaultValue = CommandSupport.DEFAULT_PROJECT)
    Path projectDir;

    @Parameters(index = "0", defaultValue = STATUS,
            description = "动作：status（查状态）/ init（初始化并起环境）"
                    + " / reset（重置数据，容器不动）/ clear（清空环境，连卷一起）")
    String action;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);
        TestEnvironment environment = TestEnvironment.of(root);
        switch (action.toLowerCase(java.util.Locale.ROOT)) {
            case STATUS:
                return status(environment);
            case INIT:
                return init(environment);
            case RESET:
                return reset(environment);
            case CLEAR:
                return clear(environment);
            default:
                Console.fail("不认识的动作「%s」：可以用的是 status / init / reset / clear", action);
                return 1;
        }
    }

    /** 现在能不能跑集成测试。它一次都不改东西。 */
    private int status(TestEnvironment environment) {
        if (!environment.declared()) {
            Console.warn("这个项目没有 %s：只能跑单元测试", EnvConfigLoader.relativePath());
            Console.detail("要跑集成测试，先写一份环境声明（测试镜像、依赖、连接信息），再 %s",
                    "specflow env init");
            return 0;
        }
        TestEnvironment.Status state = environment.status();
        if (state.docker() != null) {
            Console.info("Docker：%s", state.dockerLabel());
        }
        Console.info("测试环境：%s", state.state().label());
        Console.info("compose 项目：%s", environment.composeProject());
        if (state.registration().size() > 0) {
            Console.detail("%s", state.registration().summarize());
        }
        if (TestEnvironment.shouldWarnAboutLeftovers(state.leftovers())) {
            Console.warn("残留有 %d 件，到 %d 件就该看一眼了：多半是之前有几次收尾没做成。"
                            + "打开项目时会自动收，也可以手工 %s",
                    state.leftovers(), TestEnvironment.LEFTOVER_WARN_THRESHOLD,
                    "docker compose -p " + environment.composeProject() + " down -v");
        }
        if (!state.todo().isEmpty()) {
            Console.detail("下一步：%s", state.todo());
        }
        return state.usable() ? 0 : 1;
    }

    private int init(TestEnvironment environment) {
        try {
            EnvRegistration done = environment.up();
            Console.ok("测试环境已就绪：%s", done.summarize());
            Console.detail("compose 文件：%s", done.composeFile());
            Console.detail("容器常驻复用；每次跑测试之前只会重置数据（reset）");
            return 0;
        } catch (EnvProblem problem) {
            // 原始错误和待办都在 detail 里：这是十五.5 要的「立刻停 + 原始错误 + 待办」。
            // 命令行下没有界面可看，这两样必须打印出来
            Console.fail("%s", problem.detail());
            return 1;
        }
    }

    /**
     * 跑一次 {@code reset}：把数据恢复到一个已知状态，<b>容器不动</b>。
     *
     * <p>没声明就没有可重置的东西：这不是失败（退出码 0），但必须说清「我什么都没做」——
     * 静默返回成功，用户会以为数据真被清干净了。这一条和 {@code clear} 是同一条道理。
     *
     * <p>声明了却没初始化过（没有 compose 文件）返回 1：这一次确实做不成，
     * 而原始错误（{@code EnvProblem}）里已经写着「先初始化」。
     */
    private int reset(TestEnvironment environment) {
        if (!environment.declared()) {
            Console.warn("这个项目没有 %s：没有可重置的数据", EnvConfigLoader.relativePath());
            return 0;
        }
        try {
            EnvRegistration done = environment.reset();
            Console.ok("%s", done.detail());
            Console.detail("容器留着复用：这一步只重置数据，不动环境");
            return 0;
        } catch (EnvProblem problem) {
            Console.fail("%s", problem.detail());
            return 1;
        }
    }

    private int clear(TestEnvironment environment) {
        if (!environment.declared()) {
            // 没声明就没有可关的东西。这不是失败（退出码 0），但必须说清
            // 「我什么都没做」——静默地返回成功，用户会以为环境真被清掉了
            Console.warn("这个项目没有 %s：没有测试环境可清", EnvConfigLoader.relativePath());
            return 0;
        }
        try {
            EnvRegistration done = environment.down(true);
            Console.ok("%s", done.summarize());
            return 0;
        } catch (EnvProblem problem) {
            Console.fail("%s", problem.detail());
            return 1;
        }
    }
}
