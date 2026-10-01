package com.specflow.cli;

import com.specflow.env.TestEnvironment;
import com.specflow.project.ProjectConfig;
import com.specflow.tests.Teardown;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code specflow rollback} —— 中断上一次运行的改动（十五.8）。
 *
 * <p>按快照把文件写回改动之前的样子：改前有内容的写回原文，改前没有的删掉；
 * 然后和 {@code accept} 一样收尾（删测试产物、清环境数据、落档）。
 * 两条收场路的差别只有「文件留还是撤」这一个参数，见 {@link Teardown#settle}。
 */
@Command(name = "rollback", description = "中断上一次运行的改动：文件恢复原样，再删快照与测试产物、重置环境数据")
public final class RollbackCommand implements Callable<Integer> {

    @Option(names = {"-p", "--project"}, description = "项目根目录", defaultValue = CommandSupport.DEFAULT_PROJECT)
    Path projectDir;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);
        ProjectConfig project = CommandSupport.loadProjectConfig(root);
        Teardown.Done done = Teardown.settle(root, project, CommandSupport.runStore(root),
                TestEnvironment.of(root), Teardown.Choice.INTERRUPT);
        Console.ok("%s", done.summarize());
        return 0;
    }
}
