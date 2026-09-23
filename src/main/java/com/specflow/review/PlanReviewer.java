package com.specflow.review;

import com.specflow.context.ContextAssembler;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.spec.Spec;
import com.specflow.template.TemplateRegistry;

import java.util.List;

/**
 * 检查阶段：让模型在动手之前先说清楚「打算怎么做」。
 *
 * <p>它和 {@link com.specflow.agent.DevelopmentAgent} 看到的是<b>同一份上下文</b>——
 * 目标文件的当前内容、上下文依赖、需求字段，一模一样。这一点很关键：
 * 如果检查时不给它看目标文件，它给出的判断就是悬空的，而它后面写锚点也离不开这些内容。
 *
 * <p>变的只有最前面那段协议：一个问「你打算怎么做」，一个说「把代码交出来」。
 * 所以这里复用 {@link ContextAssembler}，而不是另建一套上下文组装。
 *
 * <p>刻意<b>不</b>给模型任何工具：它看不到项目里还有什么文件。
 * 缺什么只能靠说，由人决定要不要补。让模型自己去翻项目需要在架构里引入工具调用，
 * 那一步的成本远大于收益——「引擎替你搜候选、你点一下」能覆盖绝大多数场景。
 */
public final class PlanReviewer {

    private final ContextAssembler assembler;
    private final TemplateRegistry templates;
    private final LlmClient llm;
    private final PlanParser parser = new PlanParser();

    public PlanReviewer(ContextAssembler assembler, TemplateRegistry templates, LlmClient llm) {
        this.assembler = assembler;
        this.templates = templates;
        this.llm = llm;
    }

    /**
     * 执行一次检查调用。
     *
     * <p>同步阻塞：它只有一次模型调用，界面显示一个「检查中」即可，
     * 不需要像开发阶段那样为多轮重试建一套进度推送。
     *
     * @throws com.specflow.llm.LlmException    调用失败
     * @throws com.specflow.exception.SpecflowException 响应里没有流程图
     */
    public PlanReview review(Spec spec) {
        String response = llm.complete(List.of(
                ChatMessage.system(assembler.systemMessage(spec, templates, ReviewProtocol.INSTRUCTIONS)),
                ChatMessage.user(assembler.userMessage(spec, templates))));
        return parser.parse(response);
    }
}
