package com.specflow.template;

import com.specflow.exception.SpecflowException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 项目内的提示词模板集合，只读。
 *
 * <p>它只是 {@link TemplateStore} 加载结果的一个查询视图——文件怎么读、怎么校验、
 * 怎么增删改都在 Store 里。这样分工的理由很实际：
 * <b>Agent 运行期只需要查</b>，不该拿着一份能改文件的引用；
 * 而界面要改的也是磁盘上的文件，改完重新加载一份新的注册表即可。
 *
 * <p>模板目录不存在不是错误：spec 也可以直接写 {@code prompt} 而不引用模板。
 */
public final class TemplateRegistry {

    public static final String DEFAULT_DIR = TemplateStore.DEFAULT_DIR;

    private final Map<String, PromptTemplate> templates;
    private final List<TemplateStore.BrokenTemplate> broken;

    private TemplateRegistry(Map<String, PromptTemplate> templates,
                             List<TemplateStore.BrokenTemplate> broken) {
        this.templates = templates;
        this.broken = broken;
    }

    public static TemplateRegistry empty() {
        return new TemplateRegistry(Map.of(), List.of());
    }

    /**
     * 从目录加载。
     *
     * <p>读不回来的那几份不会让加载失败，只会在 {@link #broken()} 里——
     * 它们是用户手写的文件，一个手滑就能写坏，而加载失败意味着连界面都进不去，
     * 也就没有地方能把它删掉。
     */
    public static TemplateRegistry load(Path directory) {
        TemplateStore.Loaded loaded = new TemplateStore(directory).loadAll();
        return new TemplateRegistry(loaded.templates(), loaded.broken());
    }

    /** 目录里读不回来的那几份，附原因。 */
    public List<TemplateStore.BrokenTemplate> broken() {
        return broken;
    }

    /**
     * @throws SpecflowException 模板不存在，错误信息里列出所有可用模板名
     */
    public PromptTemplate get(String name) {
        PromptTemplate template = name == null ? null : templates.get(name);
        if (template == null) {
            // 「存在但读不出来」和「压根没有」是两件事，混成一句会让人对着
            // 「找不到模板 x；可用模板: …, x」里那个 x 发懵
            for (TemplateStore.BrokenTemplate item : broken) {
                if (item.name().equals(name)) {
                    throw new SpecflowException("模板 '" + name + "' 读不出来：" + item.reason());
                }
            }
            throw new SpecflowException("找不到模板 '" + name + "'；可用模板: "
                    + (templates.isEmpty() ? "(无，请检查 " + DEFAULT_DIR + ")" : String.join(", ", names())));
        }
        return template;
    }

    public boolean contains(String name) {
        return templates.containsKey(name);
    }

    public List<String> names() {
        return templates.keySet().stream().sorted().toList();
    }

    public int size() {
        return templates.size();
    }
}
