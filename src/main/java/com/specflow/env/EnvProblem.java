package com.specflow.env;

import com.specflow.exception.SpecflowException;

import java.util.List;

/**
 * 「这套环境没弄成」——一条<b>不该由模型负责</b>的失败。
 *
 * <p>它和断言失败是两件完全不同的事，所以必须是不同的类型：镜像拉不到、健康检查超时、
 * {@code init} 命令跑挂了，再跑一万次也还是那样。用户要做的是去把环境修好，
 * 而不是回喂给开发 Agent 让它改代码（十五.5：失败分档）。
 *
 * <p>三个字段缺一不可，对应十五.5 要的「立刻停 + 原始错误 + 待办」：
 * <ul>
 *   <li>{@link #step()}——<b>卡在哪一步</b>（起环境 / 初始化命令 / 重置数据 / 收环境）；</li>
 *   <li>{@link #command()}——<b>哪条命令</b>。用户最需要的就是这一条：他可以自己拿它去跑一遍；</li>
 *   <li>{@link #output()}——<b>原始错误</b>，原样，一个字都不改。掐掉它，人就只能猜。</li>
 * </ul>
 * 待办那句话由 {@link #detail()} 拼出来：它要回答「现在该怎么办」，
 * 而不是「发生了一个错误」。
 */
public class EnvProblem extends SpecflowException {

    /** 卡在哪个环节。措辞直接进给用户的那段话，所以写的是人话。 */
    public static final String START = "起测试环境";
    public static final String INIT = "初始化命令";
    public static final String RESET = "重置数据";
    public static final String DOWN = "收测试环境";
    public static final String CHECK = "检查测试环境";

    /** 原始错误最多留几行：compose 的报错总在最后，前面全是拉镜像的进度条。 */
    private static final int MAX_OUTPUT_LINES = 40;

    private final String step;
    private final String command;
    private final String output;
    private final String todo;

    public EnvProblem(String step, String command, String output, String todo) {
        super(render(step, command, output, todo));
        this.step = step;
        this.command = command == null ? "" : command;
        this.output = output == null ? "" : output;
        this.todo = todo == null ? "" : todo;
    }

    public String step() {
        return step;
    }

    /** 出问题的那条命令（原样的命令行）；说不出具体命令时是空串。 */
    public String command() {
        return command;
    }

    /** 命令的原始输出。给人看的，不做归纳。 */
    public String output() {
        return output;
    }

    public String todo() {
        return todo;
    }

    /** 给用户/留档的那段话——和异常消息<b>逐字相同</b>：两处各拼一遍，迟早有一处少一栏。 */
    public String detail() {
        return getMessage();
    }

    /**
     * 顺序是刻意的：先说<b>卡在哪一步</b>（用户据此知道该看哪儿），再说原始错误
     * （机器的事实），最后才是待办（人的下一步）。反过来先给建议，人就不知道
     * 那条建议是针对什么说的。
     */
    private static String render(String step, String command, String output, String todo) {
        StringBuilder out = new StringBuilder(step + "没成功。");
        if (command != null && !command.isBlank()) {
            out.append(System.lineSeparator()).append("  命令：").append(command);
        }
        if (output != null && !output.isBlank()) {
            out.append(System.lineSeparator()).append("  原始错误：")
                    .append(System.lineSeparator()).append(indent(output));
        }
        if (todo != null && !todo.isBlank()) {
            out.append(System.lineSeparator()).append("  怎么办：").append(todo);
        }
        return out.toString();
    }

    private static String indent(String text) {
        List<String> lines = text.lines().toList();
        int from = Math.max(0, lines.size() - MAX_OUTPUT_LINES);
        StringBuilder out = new StringBuilder();
        if (from > 0) {
            out.append("    …（前面还有 ").append(from).append(" 行输出，完整的那份在运行日志里）")
                    .append(System.lineSeparator());
        }
        for (String line : lines.subList(from, lines.size())) {
            out.append("    ").append(line).append(System.lineSeparator());
        }
        return out.toString().stripTrailing();
    }
}
