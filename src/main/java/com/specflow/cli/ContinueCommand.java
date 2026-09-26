package com.specflow.cli;

import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.agent.DevelopmentAgent;
import com.specflow.history.RunRecord;
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
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code specflow continue} —— 接着上一次「模型说缺料」的运行往下跑。
 *
 * <p>为什么不直接重跑一遍 {@code run}：重跑会把整份上下文（目标文件全文 +
 * 上下文依赖）再发一次，而这份钱用户已经付过了。续跑只多两条消息：
 * 它当时说了什么、接下来该怎么办。
 *
 * <p>补料的方式仍然是改 spec（把缺的文件加进 targets、或在 context 段补上），
 * 命令本身不猜你要什么。
 */
@Command(name = "continue", description = "接着上一次「模型要求补充信息」的运行继续（不重发上下文）")
public final class ContinueCommand implements Callable<Integer> {

    @Option(names = {"-p", "--project"}, description = "项目根目录", defaultValue = CommandSupport.DEFAULT_PROJECT)
    Path projectDir;

    @Option(names = {"-s", "--spec"}, description = "spec 文件（相对项目根目录）",
            defaultValue = CommandSupport.DEFAULT_SPEC)
    String specPath;

    @Option(names = "--force", description = "不补料，直接让它用现有信息做（它仍可能失败）")
    boolean force;

    @Option(names = "--run", description = "指定接着哪一条运行记录跑；默认是最近一次挂起的")
    String runId;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);
        ProjectConfig project = CommandSupport.loadProjectConfig(root);
        Spec spec = CommandSupport.loadSpec(root, specPath);
        new SpecValidator().validate(spec, new SafePathResolver(root));

        RunStore store = new RunStore(root.resolve(RunStore.DEFAULT_DIR));
        RunRecord suspended = runId == null
                ? store.suspended().orElse(null)
                : store.load(runId);
        if (suspended == null) {
            Console.info("现在没有挂起的运行，直接跑 specflow run 就行");
            return 0;
        }
        Console.info("接着 %s 跑（它上一次说）：", suspended.id());
        Console.detail("%s", suspended.detail());
        int repeated = store.repeatedNeedsContext();
        if (repeated >= 2) {
            Console.warn("它已经连着 %d 次说信息不足了——再补一次大概率还是缺，"
                    + "可以考虑改需求或换个做法", repeated);
        }

        AgentListener recorder = RunRecorder.start(store, spec, null, AgentListener.NOOP);
        DevelopmentAgent agent = new DevelopmentAgent(root, project,
                TemplateRegistry.load(root.resolve(TemplateRegistry.DEFAULT_DIR)),
                OpenAiCompatibleClient.from(project.llm(), root),
                List.of(new CompileVerifier()), recorder);
        AgentResult result = agent.resume(spec, null, suspended.detail(), force,
                suspended.planSteps());

        RunReport.report(result);
        return RunReport.exitCode(result.status());
    }
}
