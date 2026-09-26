package com.specflow;

import com.specflow.cli.AcceptCommand;
import com.specflow.cli.InitCommand;
import com.specflow.cli.RollbackCommand;
import com.specflow.cli.RunCommand;
import com.specflow.cli.TemplatesCommand;
import com.specflow.cli.ValidateCommand;
import com.specflow.cli.WebCommand;
import com.specflow.exception.SpecValidationException;
import com.specflow.exception.SpecflowException;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * 命令行入口。
 *
 * <p>这是整个程序<b>唯一</b>的异常边界。内部各层一律直接抛
 * {@link SpecflowException}，不去 catch——因为每一层都不知道「怎么向用户交代」，
 * 只有这里知道：要么列成若干条可读的问题，要么打一行原因。
 * 把 try-catch 收在边界上，是这套代码里刻意维持的一个约束。
 */
@Command(
        name = "specflow",
        mixinStandardHelpOptions = true,
        version = "specflow 0.1.0",
        description = "规格驱动的代码修改 Agent：模型产出补丁，本地校验后落盘。",
        subcommands = {
                RunCommand.class,
                WebCommand.class,
                ValidateCommand.class,
                TemplatesCommand.class,
                InitCommand.class,
                AcceptCommand.class,
                RollbackCommand.class
        }
)
public final class SpecflowCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** 构造命令行；测试通过 {@link #execute} 复用同一条装配路径。 */
    private static CommandLine buildCommandLine() {
        return new CommandLine(new SpecflowCli())
                .setExecutionExceptionHandler(new SpecflowExceptionHandler());
    }

    /**
     * 不退出 JVM 地执行一次命令，供测试与内嵌调用使用。
     */
    public static int execute(String... args) {
        return buildCommandLine().execute(args);
    }

    /**
     * 把业务异常渲染成人能读的输出。
     *
     * <p>校验失败时逐条列出问题——{@link SpecValidationException} 特意携带了
     * 「全部问题」而不是「第一个问题」，就是为了在这里一次性说清楚。
     */
    private static final class SpecflowExceptionHandler implements CommandLine.IExecutionExceptionHandler {

        @Override
        public int handleExecutionException(Exception ex, CommandLine commandLine,
                                            CommandLine.ParseResult parseResult) {
            if (ex instanceof SpecValidationException validation) {
                System.out.println("[FAIL] 校验未通过，共 " + validation.problems().size() + " 个问题：");
                validation.problems().forEach(problem -> System.out.println("       - " + problem));
                return 1;
            }
            if (ex instanceof SpecflowException business) {
                System.out.println("[FAIL] " + business.getMessage());
                return 1;
            }
            System.out.println("[FAIL] 未预期的错误：" + ex);
            ex.printStackTrace(System.out);
            return 1;
        }
    }
}
