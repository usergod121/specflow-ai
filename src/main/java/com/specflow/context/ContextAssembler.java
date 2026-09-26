package com.specflow.context;

import com.specflow.exception.SpecflowException;
import com.specflow.spec.ContextItem;
import com.specflow.spec.Spec;
import com.specflow.template.PromptTemplate;
import com.specflow.template.TemplateRegistry;
import com.specflow.template.TemplateRenderer;
import com.specflow.util.ProjectFiles;
import com.specflow.util.SafePathResolver;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把 spec 组装成发给模型的对话。
 *
 * <p>这是「上下文工程」唯一集中的地方：渲染需求、附上约束与上下文依赖、
 * 读入目标文件、并在超出预算时<b>明确失败</b>而不是悄悄截断。
 *
 * <p>上下文依赖有两种形态，区别在于<b>什么时候读盘</b>：
 * <ul>
 *   <li>引用（{@code ref}）——<b>组装时才读</b>。文件改了，下一次运行拿到的是新内容，
 *       不会像快照那样悄悄过期，然后让模型照着旧版本写代码</li>
 *   <li>内联（{@code text}）——本来就存在 spec 里，直接贴进提示词</li>
 * </ul>
 *
 * <p>目标文件也走同一条预算，只是单独成段：它是<b>要被改的</b>，
 * 上下文依赖是<b>拿来参考的</b>，模型必须分得清这两者。
 *
 * <p>为什么不做「自动挑相关文件」：那需要一套检索机制，而检索错文件比不检索更危险——
 * 模型会照着错误的上下文写出一份自洽的错代码。当前取舍是让 spec 显式列出依赖，
 * 给得太多就报错，由人来收敛。
 */
public final class ContextAssembler {

    /**
     * 上下文正文的总字符预算。目标文件与上下文依赖<b>共用</b>这一份额度——
     * 只管住一半的门等于没管。
     */
    private static final int MAX_CONTEXT_CHARS = 180_000;

    /** 用四反引号围栏，避免与代码里可能出现的三反引号冲突。 */
    private static final String FENCE = "````";

    private final SafePathResolver pathResolver;

    public ContextAssembler(SafePathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    /**
     * 系统提示词 = 补丁协议 + 模板自带的角色说明。
     *
     * <p>协议部分是常量且不可被模板覆盖：模板可以改变模型「怎么想」，
     * 但不能改变引擎「怎么读」。
     *
     * <p>检查阶段和开发阶段看到的是<b>同一份上下文</b>，只是要回答的问题不同：
     * 一个说「你打算怎么做」，一个说「把代码交出来」。所以上下文组装只有一份，
     * 变的只是最前面那段协议——因此协议由调用方传进来。
     *
     * <p><b>这里刻意没有默认值</b>：开发阶段有两档协议
     * （{@link PatchProtocol#INSTRUCTIONS} 与
     * {@link PatchProtocol#INSTRUCTIONS_WITHOUT_NEED_CONTEXT}），
     * 差别正是「要不要给模型一个说信息不足的出口」。留个默认值，就等于让调用方
     * 有可能在「检查过、方案已确认」的场合悄悄拿到带出口的那份——那正是要避免的。
     */
    public String systemMessage(Spec spec, TemplateRegistry templates, String protocol) {
        if (spec.template() == null) {
            return protocol;
        }
        PromptTemplate template = resolveTemplate(spec, templates);
        String message = protocol + "\n\n---\n\n" + template.system();
        String block = conventions(template);
        return block.isEmpty() ? message : message + "\n\n" + block;
    }

    /**
     * 把标签拼成一段话。
     *
     * <p>标签本身只是几个词（{@code mybatis}），模型看完自己就知道有 {@code @Select} 这类注解——
     * 我们不该去枚举框架的 API，那是把训练数据抄一遍，而且抄不全。标签里既有产物形态
     * （{@code class}、{@code script}），也有技术栈（{@code java}、{@code mybatis}），
     * 引擎不区分它们，一律当成「这个项目这一侧的约定」。
     *
     * <p>后半句的<b>优先级</b>是关键：约定是「可以用什么」，本次需求是「要什么」。
     * 不说清楚，模型遇到「标签写着 MyBatis、需求写着 SQL 全部走 XML」时会无所适从。
     * 这条规则属于<b>内容</b>，所以拼在这里，而不是塞进只管输出格式的补丁协议。
     */
    private String conventions(PromptTemplate template) {
        if (template.tags().isEmpty()) {
            return "";
        }
        return "## 模板约定\n" + String.join("、", template.tags())
                + "\n\n产出物的形态与技术栈以上面这组约定为准，可以直接使用其中的注解与标准封装类；"
                + "但具体的设计细节以本次需求说明为准。";
    }

    public String userMessage(Spec spec, TemplateRegistry templates) {
        CharBudget budget = new CharBudget();
        StringBuilder out = new StringBuilder();

        out.append("## 需求\n").append(requirement(spec)).append("\n\n");
        appendAcceptance(spec, out);
        appendConstraints(spec, out);
        appendContext(spec, out, budget);
        appendTrace(spec, out);
        appendTargets(spec, out, budget);
        return out.toString();
    }

    // ---------- 各段落 ----------

    /**
     * 验收标准。
     *
     * <p>它排在约束前面，因为「怎样算做完」比「不许怎么做」更靠近需求本身。
     * 它同时是将来测试 Agent 的输入——「从需求/设计派生测试用例」派生的依据就是它。
     */
    private void appendAcceptance(Spec spec, StringBuilder out) {
        if (spec.acceptance().isEmpty()) {
            return;
        }
        out.append("## 验收标准\n");
        spec.acceptance().forEach(item -> out.append("- ").append(item).append('\n'));
        out.append('\n');
    }

    private void appendConstraints(Spec spec, StringBuilder out) {
        if (spec.constraints().isEmpty()) {
            return;
        }
        out.append("## 约束\n");
        spec.constraints().forEach(constraint -> out.append("- ").append(constraint).append('\n'));
        out.append('\n');
    }

    private void appendContext(Spec spec, StringBuilder out, CharBudget budget) {
        if (spec.context().isEmpty()) {
            return;
        }
        out.append("## 上下文依赖\n");
        for (ContextItem item : spec.context()) {
            out.append("### ").append(item.name());
            if (!item.note().isEmpty()) {
                out.append("　—　").append(item.note());
            }
            out.append('\n').append(fenced(readItem(item, budget))).append("\n\n");
        }
    }

    private void appendTrace(Spec spec, StringBuilder out) {
        if (!spec.trace().present()) {
            return;
        }
        String id = spec.trace().requirementId();
        out.append("## 追溯信息\n");
        out.append("需求编号: ").append(id).append('\n');
        out.append("请在每处修改的第一行上方插入「@requirement ").append(id).append("」注释。\n\n");
    }

    private void appendTargets(Spec spec, StringBuilder out, CharBudget budget) {
        // 这句是**事实**，不是要求。检查阶段和开发阶段都看得到这一段，所以只写一遍：
        // 不写清楚，检查阶段会给出「新建 xxx/HealthController.java」这种根本执行不了的方案，
        // 而开发阶段要等到补丁被拒才发现——那时已经白跑了一轮。
        out.append("## 目标文件\n");
        out.append("下面这些是你**唯一**可以改动的文件；清单之外的文件你动不了，新建也不行。\n")
                .append("如果这件事必须动清单外的文件，就直说「需要把某个文件加进目标文件」，")
                .append("不要换个做法硬做。\n\n");
        for (String target : spec.targets()) {
            Path file = pathResolver.resolve(target);
            String shown = pathResolver.relativize(file);
            out.append("### ").append(shown).append('\n');

            if (!Files.isRegularFile(file)) {
                out.append("（文件当前不存在，本次任务将新建它）\n\n");
                continue;
            }
            String content = ProjectFiles.read(file, shown);
            budget.add(shown, content.length());
            out.append(fenced(content)).append('\n');
        }
    }

    // ---------- 内部 ----------

    private PromptTemplate resolveTemplate(Spec spec, TemplateRegistry templates) {
        return templates.get(spec.template());
    }

    /**
     * 需求来自 spec 自己，<b>模板不参与</b>。
     *
     * <p>模板只提供角色与标签（那是项目那一侧的常量），需求每次都不一样——
     * 把它写进模板，就等于「一个模板只能匹配一种需求」。
     *
     * <p>仍然走一遍渲染：spec 的 prompt 里可以有 {@code {{占位符}}}，
     * 由 variables 填充；不写占位符时这一步等于原样返回。
     */
    private String requirement(Spec spec) {
        return TemplateRenderer.render(spec.prompt(), spec.variables());
    }

    /** 引用形态在这里才读盘；读不到就明确报错，并指出是哪一条依赖。 */
    private String readItem(ContextItem item, CharBudget budget) {
        if (!item.hasRef()) {
            budget.add("上下文「" + item.name() + "」", item.text().length());
            return item.text();
        }
        Path file = pathResolver.resolve(item.ref());
        String shown = pathResolver.relativize(file);
        if (!Files.isRegularFile(file)) {
            throw new SpecflowException("上下文引用的文件不存在: " + shown
                    + "（条目「" + item.name() + "」）");
        }
        String content = ProjectFiles.read(file, shown);
        budget.add("上下文「" + item.name() + "」", content.length());
        return content;
    }

    private String fenced(String body) {
        return FENCE + "\n" + body.stripTrailing() + "\n" + FENCE;
    }

    /**
     * 上下文正文的额度。
     *
     * <p>超了就抛，不截断——悄悄截断会让模型基于半份文件写锚点，
     * 而它会以为自己看到的是全部。
     */
    private static final class CharBudget {

        private int used;

        void add(String what, int chars) {
            used += chars;
            if (used > MAX_CONTEXT_CHARS) {
                throw new SpecflowException("上下文超出预算（上限 " + MAX_CONTEXT_CHARS
                        + " 字符），请减少目标文件或上下文依赖后重试；超限发生在 " + what);
            }
        }
    }
}
