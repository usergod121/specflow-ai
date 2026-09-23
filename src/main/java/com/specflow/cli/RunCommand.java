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
import com.specflow.verify.VerificationResult;
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

        report(result);
        Console.detail("运行记录: %s", root.resolve(RunStore.DEFAULT_DIR));
        return exitCode(result.status());
    }

    private LlmClient llmClient(Path root, ProjectConfig project) {
        return OpenAiCompatibleClient.from(project.llm(), root);
    }

    private List<Verifier> verifiers() {
        return List.of(new CompileVerifier());
    }

    private void report(AgentResult result) {
        for (VerificationResult verification : result.verifications()) {
            Console.detail("%s: %s", verification.verifier(), verification.status());
        }
        switch (result.status()) {
            case SUCCESS -> {
                Console.ok("完成，共 %d 轮，改动 %d 处：", result.attempts(), result.changes().size());
                result.changes().forEach(change -> Console.detail("%s", change.describe()));
            }
            case SUCCESS_UNVERIFIED -> {
                Console.warn("%s", result.detail());
                result.changes().forEach(change -> Console.detail("%s", change.describe()));
            }
            case NEEDS_CONTEXT -> {
                Console.warn("模型声明信息不足，未改动任何文件：");
                Console.detail("%s", result.detail());
                Console.detail("请补充信息后重跑：把缺失的文件加入 targets，或在 context 段补充说明");
            }
            case FAILED -> {
                Console.fail("任务失败（%d 轮）：%s", result.attempts(), result.detail());
                Console.detail("磁盘状态已回滚到运行前");
            }
            case NEEDS_ENVIRONMENT -> {
                Console.fail("不是改代码能解决的（%d 轮）：%s", result.attempts(), result.detail());
                Console.detail("磁盘状态已回滚到运行前");
                Console.detail("请按上面的提示处理依赖或环境，然后重跑");
            }
            case CANCELLED -> {
                Console.warn("已中断（完成 %d 轮）", result.attempts());
                Console.detail("磁盘状态已回滚到运行前");
            }
        }
    }

    /**
     * 退出码：0 成功 · 1 失败（已回滚）· 2 模型要求补充信息（磁盘未动）· 3 人工中断（已回滚）·
     * 4 卡在环境/依赖上（已回滚，需要人去处理）。
     *
     * <p>中断单独给一个码，是为了让 CI 能区分「它自己做不到」和「是我叫停的」；
     * 环境问题单独给一个码，是为了让 CI 能区分「代码写错了」和「这台机器上跑不起来」。
     */
    private int exitCode(AgentResult.Status status) {
        return switch (status) {
            case SUCCESS, SUCCESS_UNVERIFIED -> 0;
            case FAILED -> 1;
            case NEEDS_CONTEXT -> 2;
            case CANCELLED -> 3;
            case NEEDS_ENVIRONMENT -> 4;
        };
    }
}
