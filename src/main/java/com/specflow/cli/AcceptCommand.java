package com.specflow.cli;

import com.specflow.env.TestEnvironment;
import com.specflow.history.RunStore;
import com.specflow.project.ProjectConfig;
import com.specflow.tests.Teardown;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code specflow accept} —— 接受上一次运行的改动（十五.8）。
 *
 * <p>「接受」在文件上只做一件事：<b>什么都不动</b>——改动已经是最终结果，
 * 快照留着只是为了让人还来得及撤回。其余的收尾（删快照、删测试产物、清环境数据、
 * 把「带着几条失败接受的」写进留档）在 {@link Teardown} 里，和界面上那两个按钮
 * 走的是<b>同一份实现</b>：分头各写一遍，迟早有一边少做一件。
 *
 * <p>为什么要有这条命令：引擎会拒绝在一个「还没被处置的改动」之上再开一次运行
 * （见 {@code DevelopmentAgent}）。命令行没有界面上的按钮，没有这条命令，命令行用户
 * 会在第一次成功之后被永久挡在门外。
 */
@Command(name = "accept", description = "接受上一次运行的改动：保留文件、删快照与测试产物、重置环境数据")
public final class AcceptCommand implements Callable<Integer> {

    @Option(names = {"-p", "--project"}, description = "项目根目录", defaultValue = CommandSupport.DEFAULT_PROJECT)
    Path projectDir;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);
        ProjectConfig project = CommandSupport.loadProjectConfig(root);
        // 没有待处置的改动时什么都不做：收场是幂等的，点第二次不该删掉别的运行留下的东西
        Teardown.Done done = Teardown.settle(root, project, CommandSupport.runStore(root),
                TestEnvironment.of(root), Teardown.Choice.ACCEPT);
        Console.ok("%s", done.summarize());
        return 0;
    }
}
