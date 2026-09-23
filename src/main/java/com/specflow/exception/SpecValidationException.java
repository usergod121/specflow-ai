package com.specflow.exception;

import java.util.List;

/**
 * spec.yaml 结构或语义非法。
 *
 * <p>携带全部问题，而不是遇到第一个就退出，这样用户一次就能改完。
 */
public class SpecValidationException extends SpecflowException {

    private final List<String> problems;

    public SpecValidationException(List<String> problems) {
        super(render(problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    private static String render(List<String> problems) {
        if (problems == null || problems.isEmpty()) {
            return "spec 校验失败";
        }
        if (problems.size() == 1) {
            return "spec 校验失败：" + problems.get(0);
        }
        StringBuilder sb = new StringBuilder("spec 校验失败（").append(problems.size()).append(" 项）：");
        for (String problem : problems) {
            sb.append(System.lineSeparator()).append("  - ").append(problem);
        }
        return sb.toString();
    }
}
