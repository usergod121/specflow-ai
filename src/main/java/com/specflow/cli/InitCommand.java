package com.specflow.cli;

import com.specflow.project.ProjectInitializer;
import com.specflow.project.ProjectScanner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code specflow init} —— 在项目里铺好配置骨架。
 *
 * <p>只做一件事：把「跑起来所需要的最小文件」放到位。骨架里带两个可直接使用的模板，
 * 因为「模板怎么写」是这套工具唯一需要用户学习的部分，留空让人自己摸索是最差的选择。
 *
 * <p>真正写文件的是 {@link ProjectInitializer}——界面上「打开一个还没配过的项目」
 * 走的也是它，两处不各写一份。
 *
 * <p>已有文件一律不覆盖。初始化命令把用户写了一半的 spec 冲掉，
 * 是这类工具最不可原谅的 bug。
 */
@Command(name = "init", description = "在当前项目生成 .specflow 配置骨架")
public final class InitCommand implements Callable<Integer> {

    @Option(names = {"-p", "--project"}, description = "项目根目录",
            defaultValue = CommandSupport.DEFAULT_PROJECT)
    Path projectDir;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);

        // 编译命令按项目扫出来，而不是写死 mvn——这套工具不该假定所有人都用 Maven
        String compileCommand = new ProjectScanner().scan(root).compileCommand();
        ProjectInitializer.Result result = new ProjectInitializer().initialize(root, compileCommand);

        for (String file : result.written()) {
            Console.ok("生成 %s", file);
        }
        if (result.wroteNothing()) {
            Console.warn("所有文件都已存在，未做任何改动");
        } else {
            Console.ok("已生成 %d 个文件", result.written().size());
            if (compileCommand == null) {
                Console.detail("没认出这个项目怎么构建，build.compile 留空了——填上才会做编译校验");
            } else {
                Console.detail("build.compile 按项目扫出来填的是：%s", compileCommand);
            }
            Console.detail("下一步：编辑 spec.yaml，然后运行 specflow validate");
            Console.detail("调用模型前先准备 SPECFLOW_API_KEY："
                    + "设成环境变量，或写进 .specflow/local.env");
        }
        return 0;
    }
}
