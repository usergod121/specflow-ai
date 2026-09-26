package com.specflow.cli;

import com.specflow.project.ProjectConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code specflow accept} —— 接受上一次运行的改动。
 *
 * <p>「接受」只做一件事：**把快照删掉**。磁盘上的改动一动不动——它们已经是最终结果，
 * 快照留着只是为了让人还来得及撤回。
 *
 * <p>为什么要有这条命令：引擎会拒绝在一个「还没被处置的改动」之上再开一次运行
 * （见 {@code DevelopmentAgent}）。命令行没有界面上的按钮，没有这条命令，命令行用户
 * 会在第一次成功之后被永久挡在门外。
 */
@Command(name = "accept", description = "接受上一次运行的改动：删掉快照，保留磁盘上的改动")
public final class AcceptCommand implements Callable<Integer> {

    @Option(names = {"-p", "--project"}, description = "项目根目录", defaultValue = CommandSupport.DEFAULT_PROJECT)
    Path projectDir;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);
        ProjectConfig project = CommandSupport.loadProjectConfig(root);
        List<WorkspaceSnapshot> waiting = CommandSupport.undisposedSnapshots(root, project);

        if (waiting.isEmpty()) {
            Console.info("没有待处置的改动");
            return 0;
        }
        for (WorkspaceSnapshot snapshot : waiting) {
            snapshot.discard();
            Console.ok("已接受 %s：改动保留在磁盘上", snapshot.directory().getFileName());
        }
        return 0;
    }
}
