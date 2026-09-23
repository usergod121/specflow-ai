package com.specflow.spec;

import com.specflow.exception.SpecValidationException;
import com.specflow.util.SafePathResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link Spec} 的语义校验。
 *
 * <p>设计约束：一次性收集**全部**问题再抛出，而不是发现第一个就中断。
 * 用户改一次 spec 只跑一次校验，避免「改一个错、再跑、再发现一个错」的来回。
 *
 * <p>校验分层：格式合法性由 {@link SpecLoader} 负责，这里只管业务规则。
 */
public final class SpecValidator {

    /** 占位符名允许的字符集，必须与模板渲染器的解析规则一致。 */
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public void validate(Spec spec, SafePathResolver pathResolver) {
        List<String> problems = new ArrayList<>();

        validateVersion(spec, problems);
        validatePromptSource(spec, problems);
        validateTargets(spec, pathResolver, problems);
        validateVariables(spec, problems);
        validateContext(spec, pathResolver, problems);

        if (!problems.isEmpty()) {
            throw new SpecValidationException(problems);
        }
    }

    private void validateVersion(Spec spec, List<String> problems) {
        if (spec.version() < 1) {
            problems.add("version 必须为正整数，当前为 " + spec.version());
            return;
        }
        if (spec.version() > Spec.CURRENT_VERSION) {
            problems.add("version=" + spec.version() + " 高于本工具支持的最高版本 "
                    + Spec.CURRENT_VERSION + "，请升级 specflow");
        }
    }

    /**
     * 需求必填；模板是可选的角色说明，两者<b>不再互斥</b>。
     *
     * <p>过去这里要求「prompt 与 template 二选一」，是因为模板里带着需求提示词，
     * 两者同时存在会打架。现在模板只提供角色与标签，需求永远来自 spec 自己，
     * 同时存在才是常态。
     */
    private void validatePromptSource(Spec spec, List<String> problems) {
        if (spec.prompt().isBlank()) {
            problems.add("prompt 不能为空——它是需求的源头，模板不提供它");
        }
    }

    private void validateTargets(Spec spec, SafePathResolver pathResolver, List<String> problems) {
        if (spec.targets().isEmpty()) {
            problems.add("targets 不能为空——引擎需要知道这次改动涉及哪些文件");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String target : spec.targets()) {
            String trimmed = target == null ? "" : target.trim();
            if (trimmed.isEmpty()) {
                problems.add("targets 中存在空路径");
                continue;
            }
            if (!seen.add(trimmed)) {
                problems.add("targets 中存在重复路径: " + trimmed);
                continue;
            }
            try {
                pathResolver.resolve(trimmed);
            } catch (IllegalArgumentException e) {
                problems.add("targets 非法: " + e.getMessage());
            }
        }
    }

    private void validateVariables(Spec spec, List<String> problems) {
        for (String key : spec.variables().keySet()) {
            if (key == null || !VARIABLE_NAME.matcher(key).matches()) {
                problems.add("variables 中的键名非法: '" + key + "'（需匹配 " + VARIABLE_NAME.pattern() + "）");
            }
        }
    }

    /**
     * 上下文条目：名字不能重复（界面上要能分辨），引用路径必须落在项目内。
     *
     * <p>路径越界在这里挡掉，而不是等组装提示词时才炸——那时候用户已经
     * 点过「运行」、模型也快调用了，错误出现得太晚。
     */
    private void validateContext(Spec spec, SafePathResolver pathResolver, List<String> problems) {
        Set<String> names = new HashSet<>();
        for (ContextItem item : spec.context()) {
            if (!names.add(item.name())) {
                problems.add("context 中存在重名条目: " + item.name());
            }
            if (!item.hasRef()) {
                continue;
            }
            try {
                pathResolver.resolve(item.ref());
            } catch (IllegalArgumentException e) {
                problems.add("context 引用非法（" + item.name() + "）: " + e.getMessage());
            }
        }
    }
}
