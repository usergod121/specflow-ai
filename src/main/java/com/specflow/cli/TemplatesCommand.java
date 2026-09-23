package com.specflow.cli;

import com.specflow.template.PromptTemplate;
import com.specflow.template.TemplateRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code specflow templates} —— 列出项目里可用的提示词模板。
 *
 * <p>模板是用户自己的资产，工具必须能回答「我这儿有哪些模板、各自要哪些变量」。
 * 没有这个命令，用户只能去翻目录、读 yaml、再猜占位符该填什么。
 */
@Command(name = "templates", description = "列出可用的提示词模板")
public final class TemplatesCommand implements Callable<Integer> {

    @Option(names = {"-p", "--project"}, description = "项目根目录", defaultValue = CommandSupport.DEFAULT_PROJECT)
    Path projectDir;

    @Override
    public Integer call() {
        Path root = CommandSupport.resolveProject(projectDir);
        Path dir = root.resolve(TemplateRegistry.DEFAULT_DIR);
        TemplateRegistry templates = TemplateRegistry.load(dir);

        if (templates.size() == 0) {
            Console.warn("未找到任何模板（目录: %s）", dir);
            return 0;
        }

        Console.info("模板目录: %s", dir);
        for (String name : templates.names()) {
            PromptTemplate template = templates.get(name);
            Console.info("");
            Console.ok("%s", name);
            if (!template.description().isEmpty()) {
                Console.detail("%s", template.description());
            }
            Console.detail("标签: %s", template.tags().isEmpty()
                    ? "(无)" : String.join(", ", template.tags()));
        }
        return 0;
    }
}
