package com.specflow.cli;

import com.specflow.project.ProjectConfig;
import com.specflow.snapshot.WorkspaceSnapshot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code specflow rollback} —— 撤回上一次运行的改动。
 *
 * <p>按快照把文件写回改动之前的样子：改前有内容的写回原文，改前没有的删掉。
 * 撤回完快照就删了——工作区已经回到起点，它没有别的用处。
 *
 * <p>和 {@code accept} 共用同一份「哪些快照还没处置」的判据，见
 * {@link CommandSupport#undisposedSnapshots}。
 */
@Command(name = "rollback", description = "撤回上一次运行的改动：按快照把文件恢复原样")
public final class RollbackCommand implements Callable<Integer> {

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
        // 从最早的一份开始恢复：多份叠在一起时，回到最初始的状态最保守
        for (WorkspaceSnapshot snapshot : waiting) {
            List<String> restored = snapshot.restore();
            snapshot.discard();
            Console.ok("已撤回 %s：恢复 %d 个文件", snapshot.directory().getFileName(), restored.size());
            restored.forEach(path -> Console.detail("%s", path));
        }
        return 0;
    }
}
