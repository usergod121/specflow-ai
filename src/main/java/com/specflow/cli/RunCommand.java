package com.specflow.cli;

import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.agent.DevelopmentAgent;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.llm.LlmClient;
import com.specflow.llm.OpenAiCompatibleClient;
import com.specflow.project.ProjectConfig;
import com.specflow.spec.Spec;
import com.specflow.spec.SpecValidator;
import com.specflow.template.TemplateRegistry;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.CompileVerifier;
import com.specflow.verify.Verifier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code specflow run} —— 执行一次开发任务。
 *
 * <p>退出码是给 CI 用的，因此必须稳定且可区分：
 * <ul>
 *   <li>{@code 0} 成功（含「落盘成功但没校验」——改动确实在磁盘上）</li>
 *   <li>{@code 1} 失败，磁盘已回滚</li>
 *   <li>{@code 2} 模型声明信息不足，磁盘未被触碰</li>
 *   <li>{@code 3} 人工中断，磁盘已回滚</li>
 *   <li>{@code 4} 卡在环境/依赖上，磁盘已回滚</li>
 *   <li>{@code 5} 上一次的改动还没处置，本次一个字节都没碰（先跑 accept 或 rollback）</li>
 * </ul>
 */
@Command(name = "run", description = "按 spec 让模型产出补丁，校验后落盘")
public final class RunCommand implements Callable<Integer> {

    @Option(names = {"-p", "--project"}, description = "项目根目录", defaultValue = CommandSupport.DEFAULT_PROJECT)
    Path projectDir;

    @Option(names = {"-s", "--spec"}, description = "spec 文件（相对项目根目录）",
            defaultValue = CommandSupport.DEFAULT_SPEC)
    String specPath;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);
        ProjectConfig project = CommandSupport.loadProjectConfig(root);
        Spec spec = CommandSupport.loadSpec(root, specPath);
        new SpecValidator().validate(spec, new SafePathResolver(root));

        Console.info("项目: %s", root);
        Console.info("spec: %s (strategy=%s, targets=%d 个)",
                specPath, spec.strategy().name().toLowerCase(), spec.targets().size());

        // 命令行同样留一份运行记录：失败时那份「它到底做了什么」的记录，
        // 在这里比在界面上更常用——命令行下没有 diff 面板可看
        AgentListener recorder = RunRecorder.start(
                new RunStore(root.resolve(RunStore.DEFAULT_DIR)), spec, null, AgentListener.NOOP);
        DevelopmentAgent agent = new DevelopmentAgent(root, project, TemplateRegistry.load(
                root.resolve(TemplateRegistry.DEFAULT_DIR)), llmClient(root, project), verifiers(), recorder);
        AgentResult result = agent.run(spec);

        RunReport.report(result);
        Console.detail("运行记录: %s", root.resolve(RunStore.DEFAULT_DIR));
        return RunReport.exitCode(result.status());
    }

    private LlmClient llmClient(Path root, ProjectConfig project) {
        return OpenAiCompatibleClient.from(project.llm(), root);
    }

    private List<Verifier> verifiers() {
        return List.of(new CompileVerifier());
    }
}
