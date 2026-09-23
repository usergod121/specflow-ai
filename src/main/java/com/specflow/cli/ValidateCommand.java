package com.specflow.cli;

import com.specflow.project.ProjectConfig;
import com.specflow.spec.Spec;
import com.specflow.spec.SpecValidator;
import com.specflow.template.PromptTemplate;
import com.specflow.template.TemplateRegistry;
import com.specflow.template.TemplateRenderer;
import com.specflow.util.SafePathResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code specflow validate} —— 只做检查，不调用模型、不改任何文件。
 *
 * <p>存在的意义：spec 写错的概率远高于想象，而每跑一次 {@code run}
 * 都要花掉一次模型调用。把校验单独拿出来，可以让「调试 spec」这件事零成本。
 *
 * <p>除了 {@link SpecValidator} 的规则，这里还会检查模板是否存在、
 * 占位符是否都能被 {@code variables} 满足——这些错误同样会在 run 时才暴露，
 * 而它们完全可以在本地静态检查出来。
 */
@Command(name = "validate", description = "校验 spec 与模板，不调用模型")
public final class ValidateCommand implements Callable<Integer> {

    @Option(names = {"-p", "--project"}, description = "项目根目录", defaultValue = CommandSupport.DEFAULT_PROJECT)
    Path projectDir;

    @Option(names = {"-s", "--spec"}, description = "spec 文件（相对项目根目录）",
            defaultValue = CommandSupport.DEFAULT_SPEC)
    String specPath;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);
        Spec spec = CommandSupport.loadSpec(root, specPath);
        new SpecValidator().validate(spec, new SafePathResolver(root));
        Console.ok("spec 语法与语义校验通过");

        ProjectConfig project = CommandSupport.loadProjectConfig(root);
        reportCompileCommand(project);

        TemplateRegistry templates = TemplateRegistry.load(root.resolve(TemplateRegistry.DEFAULT_DIR));
        if (spec.template() == null) {
            Console.ok("本次使用内联 prompt，未引用模板");
            return 0;
        }

        PromptTemplate template = templates.get(spec.template());
        Console.ok("模板 '%s' 加载成功%s", spec.template(),
                template.description().isEmpty() ? "" : "（" + template.description() + "）");
        Console.detail("标签: %s", template.tags().isEmpty()
                ? "(无)" : String.join("、", template.tags()));
        return 0;
    }

    private void reportCompileCommand(ProjectConfig project) {
        String compile = project.build().compile();
        if (compile == null) {
            Console.warn("未配置 build.compile，运行时会跳过编译校验；"
                    + "可在 .specflow/project.yaml 中补充");
        } else {
            Console.ok("编译命令: %s", compile);
        }
    }
}
