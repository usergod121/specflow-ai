package com.specflow.tests;

import com.specflow.review.PlanReview;

import java.util.List;
import java.util.Locale;

/**
 * 测试用的入口脚本。
 *
 * <p>抽出来是因为好几个测试类都要造一个「能跑、退出码受控」的脚本，而里面有两个坑，
 * 各写一遍就会各踩一遍：
 * <ul>
 *   <li><b>竖线要转义</b>——Windows 的 {@code echo} 里 {@code |} 是管道符。
 *       不转义的话「FAIL | 2 | 期望 | 实际」会被 shell 吃掉，测的就不是引擎的解析而是 cmd 的转义；</li>
 *   <li><b>换行用 CRLF</b>——{@code .cmd} 只认 Windows 换行，纯 {@code \n} 的行尾在个别命令上会翻车。</li>
 * </ul>
 *
 * <p>还有第三种坑：{@link #anchored} 造的那两行<b>溯源锚点</b>。引擎在跑之前会机器核对
 * 「用例 ⇄ 测试代码」的连线（见 {@link CaseTraceCheck}），没有锚点的产物会被直接拒绝运行——
 * 所以凡是走到「跑测试」那一步的桩，都得用它。
 *
 * <p><b>锚点里的期望值必须是 ASCII</b>：这些桩只造一个产物文件（入口脚本），而
 * {@code PatchParser} 把落盘内容统一成 LF 行尾，cmd 又按本机代码页读脚本——
 * 正文里一出现中文，解码就错位，下一行开头的命令会被吃掉当成命令执行（实测过：
 * 行尾是 CRLF 时中文没事，LF 时必坏，而引擎写盘时统一成 LF）。
 * 真模型那边的规矩同样是「中文写进测试代码文件，入口脚本只用 ASCII」。
 */
public final class EntryScripts {

    public static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private EntryScripts() {
    }

    /** 入口脚本的文件名——和引擎按平台取的那一个一致。 */
    public static String name() {
        return WINDOWS ? "run.cmd" : "run.sh";
    }

    /**
     * 集成入口脚本的文件名（十五.4 的 {@code run-it}）——和引擎按平台取的那一个一致。
     *
     * <p>假模型也得知道它：勾了集成测试时，协议里写的是「这一次要两个入口脚本」，
     * 而它写出来的名字必须和引擎要找的那个一致，否则整批产物会因为「没有集成入口」
     * 被拒——那是测试自己的错，不是被测代码的。
     */
    public static String integrationName() {
        return WINDOWS ? "run-it.cmd" : "run-it.sh";
    }

    /** 每行都是一个 {@code echo}，最后按给定退出码退出。 */
    public static String body(int exit, String... outputs) {
        return script(WINDOWS ? "@echo off" : "#!/bin/sh", exit, outputs);
    }

    /**
     * 带<b>溯源锚点</b>的入口脚本：先按清单把每条用例的 {@code CASE 编号} 与
     * {@code expect: 期望} 写出来，再打结论。
     *
     * <p>锚点写成注释（cmd 是 {@code REM}、sh 是 {@code #}），所以它们进不了输出，
     * 不影响那些断言解析结果的用例。
     *
     * @param declared 这次冻结的用例清单——锚点必须和它逐字对得上，引擎才放行
     */
    public static String anchored(int exit, List<PlanReview.TestCase> declared, String... outputs) {
        String newline = newline();
        StringBuilder head = new StringBuilder(WINDOWS ? "@echo off" : "#!/bin/sh");
        for (PlanReview.TestCase testCase : declared) {
            head.append(newline).append(comment("CASE " + testCase.index()));
            head.append(newline).append(comment("expect: " + testCase.expected()));
        }
        return script(head.toString(), exit, outputs);
    }

    private static String comment(String text) {
        return (WINDOWS ? "REM " : "# ") + text;
    }

    /**
     * 行尾。整份脚本用同一种，和改动前那份 {@code body} 一致。
     *
     * <p>不过要记住：真正落盘时行尾由 {@code PatchParser} 统一成 LF，这里写成 CRLF
     * 只是让桩在「直接喂给 cmd 看」的时候也读得通（见类注释里那条中文的坑）。
     */
    private static String newline() {
        return WINDOWS ? "\r\n" : "\n";
    }

    private static String script(String head, int exit, String... outputs) {
        String newline = newline();
        StringBuilder out = new StringBuilder(head).append(newline);
        for (String output : outputs) {
            out.append("echo ").append(WINDOWS ? output.replace("|", "^|") : output).append(newline);
        }
        return out.append(WINDOWS ? "exit /b " : "exit ").append(exit).append(newline).toString();
    }
}
