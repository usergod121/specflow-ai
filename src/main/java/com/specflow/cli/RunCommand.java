package com.specflow.cli;

import com.specflow.agent.AgentListener;
import com.specflow.agent.AgentResult;
import com.specflow.agent.DevelopmentAgent;
import com.specflow.env.TestEnvironment;
import com.specflow.exception.SpecflowException;
import com.specflow.history.RunRecorder;
import com.specflow.history.RunStore;
import com.specflow.llm.LlmClient;
import com.specflow.llm.OpenAiCompatibleClient;
import com.specflow.project.ProjectConfig;
import com.specflow.spec.Spec;
import com.specflow.spec.SpecValidator;
import com.specflow.template.TemplateRegistry;
import com.specflow.tests.Refeed;
import com.specflow.tests.TestSettings;
import com.specflow.util.SafePathResolver;
import com.specflow.verify.CompileVerifier;
import com.specflow.verify.Verifier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code specflow run} —— 执行一次开发任务。
 *
 * <p>退出码是给 CI 用的，因此必须稳定且可区分：
 * <ul>
 *   <li>{@code 0} 成功（含「落盘成功但没校验」——改动确实在磁盘上）</li>
 *   <li>{@code 1} 失败，磁盘已回滚</li>
 *   <li>{@code 2} 模型声明信息不足，磁盘未被触碰</li>
 *   <li>{@code 3} 人工中断，磁盘已回滚</li>
 *   <li>{@code 4} 卡在环境/依赖上（磁盘的去留见结论里那句话）</li>
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

    /**
     * 这次要不要跑集成测试（十五.5）。
     *
     * <p>它<b>默认关</b>，和界面上那个勾选框是同一件事：不勾就完全不碰 Docker，
     * 连探都不探——老用法一个字节都没变。勾上就必须先 {@code specflow env init}，
     * 环境没就绪时当场拒绝并说清原因（而不是跑一半才失败）。
     */
    @Option(names = {"-i", "--integration"},
            description = "这一次跑集成测试（需要先 specflow env init）")
    boolean integration;

    /**
     * 把上一轮失败的哪几条喂回给开发（十五.6 的第一条路），命令行上的「下一轮」。
     *
     * <p>写编号（{@code --refeed 7,8}）或者 {@code all}（= 上一轮失败清单里的全部）。
     * 内容<b>不由命令行拼</b>：它和界面走同一条实现（{@code RunStore.refeed} → {@code Refeed.of}），
     * 按十五.7 的固定模板从上一轮那条留档里取用例语义、失败类型、期望 vs 实际、目标文件；
     * 测试代码与断言源码不给（给了它就会照着断言改代码）。
     *
     * <p>没写这一栏 = 不是「下一轮」，提示词里不多那一段。
     */
    @Option(names = "--refeed",
            description = "把上一轮失败的哪几条喂回给开发：编号列表（7,8）或 all")
    String refeed;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);
        ProjectConfig project = CommandSupport.loadProjectConfig(root);
        Spec spec = CommandSupport.loadSpec(root, specPath);
        new SpecValidator().validate(spec, new SafePathResolver(root));

        Console.info("项目: %s", root);
        Console.info("spec: %s (strategy=%s, targets=%d 个)",
                specPath, spec.strategy().name().toLowerCase(), spec.targets().size());

        // 勾了集成就先核一遍环境：这里能同步发现的问题，不该留到测试阶段才发现——
        // 那时候开发阶段的钱已经花掉了，而失败看起来还像「代码写错了」
        TestEnvironment environment = TestEnvironment.of(root);
        if (integration) {
            TestEnvironment.Status state = environment.status();
            if (!state.usable()) {
                Console.fail("勾了集成测试，但测试环境不可用（%s）：%s",
                        state.state().label(),
                        state.todo().isEmpty() ? "先 specflow env status 看为什么" : state.todo());
                return 4;
            }
            Console.info("测试环境：%s", state.registration().summarize());
        }

        // 回喂在开工前定下来：它在提示词里、也进留档，而它的原料是**上一轮那条留档**。
        // 这一栏写错了（没有可回喂的东西、编号认不出来）就不开工——一次什么都没喂进去的运行
        // 看起来和正常的运行一模一样，那正是要避免的
        RunStore store = CommandSupport.runStore(root);
        Refeed fed = refeedOf(refeed, store);
        if (fed.present()) {
            Console.info("回喂：带着上一轮的 %d 条失败（用例 %s）",
                    fed.cases().size(), join(fed.cases()));
        }

        // 命令行同样留一份运行记录：失败时那份「它到底做了什么」的记录，
        // 在这里比在界面上更常用——命令行下没有 diff 面板可看
        AgentListener recorder = RunRecorder.start(
                store, spec, null, AgentListener.NOOP);
        DevelopmentAgent agent = new DevelopmentAgent(root, project, TemplateRegistry.load(
                root.resolve(TemplateRegistry.DEFAULT_DIR)), llmClient(root, project), verifiers(),
                recorder, integration ? new TestSettings(true) : TestSettings.UNIT_ONLY,
                environment);
        AgentResult result = agent.run(spec, null, fed);

        RunReport.report(result);
        Console.detail("运行记录: %s", root.resolve(RunStore.DEFAULT_DIR));
        return RunReport.exitCode(result.status());
    }

    /**
     * {@code --refeed} 那一栏写成什么，就换成哪几条编号。
     *
     * <p>三种写法各有各的说法，一种都不许静默：
     * <ul>
     *   <li>没写 → 空（这次不是「下一轮」）；</li>
     *   <li>{@code all} → 上一轮失败清单里的全部（和界面上的「全选」同一件事）；</li>
     *   <li>编号列表 → 就是它。认不出编号时当场拒（{@code --refeed foo} 什么都不喂，
     *       而这一次运行看上去照样是「跑过了」）。</li>
     * </ul>
     *
     * @throws SpecflowException 这一栏写错、或者上一轮没有可回喂的失败清单
     */
    static Refeed refeedOf(String selection, RunStore store) {
        if (selection == null || selection.isBlank()) {
            return Refeed.none();
        }
        String value = selection.strip();
        // 用的是留档那一层唯一的一份拼法（和 /api/run 那条路完全相同）
        try {
            if ("all".equalsIgnoreCase(value)) {
                return store.refeed(store.failingCases());
            }
            List<Integer> picked = numbersIn(value);
            if (picked.isEmpty()) {
                throw new SpecflowException("--refeed 要写编号（7,8）或者 all，收到的是："
                        + value + "；上一轮失败清单里的编号见运行记录");
            }
            return store.refeed(picked);
        } catch (IllegalStateException e) {
            // 留档那一层的拒绝原话（和界面上点「下一轮」时看到的是同一句）在这里换成人话抛出去：
            // SpecflowCli 会把它打成一行的 [FAIL]，而不是一句「未预期的错误 + 类名」
            throw new SpecflowException(e.getMessage(), e);
        }
    }

    /** 编号写成「1、2、3」。 */
    private static String join(List<Integer> indexes) {
        StringBuilder out = new StringBuilder();
        for (Integer index : indexes) {
            out.append(out.length() == 0 ? "" : "、").append(index);
        }
        return out.toString();
    }

    /** 一栏里认出来的用例编号，升序去重。分隔符随便写（逗号、空格、顿号都认）。 */
    private static List<Integer> numbersIn(String value) {
        List<Integer> found = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d+").matcher(value);
        while (matcher.find()) {
            try {
                int index = Integer.parseInt(matcher.group());
                if (!found.contains(index)) {
                    found.add(index);
                }
            } catch (NumberFormatException e) {
                // 长得像数字但超出 int 的一串：它不是用例编号，忽略
            }
        }
        found.sort(null);
        return found;
    }

    private LlmClient llmClient(Path root, ProjectConfig project) {
        return OpenAiCompatibleClient.from(project.llm(), root);
    }

    private List<Verifier> verifiers() {
        return List.of(new CompileVerifier());
    }
}
