package com.specflow.tests;

import com.specflow.review.PlanReview;

import java.util.List;

/**
 * 测试阶段的协议说明——会被逐字拼进系统提示词。
 *
 * <p>和 {@link com.specflow.context.PatchProtocol}、{@link com.specflow.review.ReviewProtocol}
 * 一样单独成类：这段文字是<b>引擎与模型之间的接口契约</b>，必须和
 * {@link TestReport} 的解析规则同步演进。改了一处不改另一处，表现是
 * 「脚本明明打印了失败，界面上的失败清单却是空的」。
 *
 * <p>它只说三件事：产物写在哪个目录、入口脚本叫什么、失败要打印成什么样。
 * <b>刻意不说用什么语言、用什么框架</b>——那是项目自己的事，模型看着目标文件就知道，
 * 而引擎一旦写死「用 JUnit」，它就只对 Java 项目有效了。
 *
 * <p>输出仍然走补丁块：模型对「照抄这几个标记」的执行力明显强于「输出合法 JSON」，
 * 而且落盘的代码里可能有任何字符，只有行级标记才不会被内容里的符号带偏。
 */
public final class TestProtocol {

    private TestProtocol() {
    }

    /** 一条失败用例那一行的开头，由 {@link TestReport} 解析。 */
    public static final String FAIL_PREFIX = "FAIL";

    /**
     * 一条<b>跑过并且过了</b>的用例那一行的开头，同样由 {@link TestReport} 解析。
     *
     * <p>为什么要专门有这么一行：退出码只说得出「有没有过」，说不出「验了几条」。
     * 没有它，「声明 3 条用例、脚本一条没跑就退出 0」和「3 条全过」在引擎眼里一模一样——
     * 界面上的通过率会虚高，而这是最坏的一种虚高：分母大的时候它看着还挺可信。
     */
    public static final String PASS_PREFIX = "PASS";

    /**
     * 「这台机器上根本跑不起来」那一行的开头。
     *
     * <p>为什么让它单独成一种行，而不是靠引擎去猜输出里的字样：环境问题和「测试代码写错了」
     * 长得完全不一样，但<b>都表现为非 0 退出码</b>。让脚本自己说清「我连跑都没跑起来」，
     * 比在引擎里堆一串「找不到命令 / 连不上库」的字符串匹配可靠得多——后者永远漏。
     * 它也拦不住脚本撒谎，所以引擎那边另有一道机器判得了的兜底（进程压根起不来、超时）。
     */
    public static final String BLOCKED_PREFIX = "BLOCKED";

    /** 四要素的分隔符。和施工单、缺失项同一套写法：模型对这个格式的执行力最好。 */
    public static final String SEPARATOR = "|";

    /**
     * 生成测试代码与入口脚本的协议。
     *
     * @param directory 产物目录（相对项目根，形如 {@code tools/20260930-120000}）
     * @param entry     入口脚本相对项目根的路径——引擎只会执行这一个文件。
     *                  它的后缀就是引擎按平台定下来的那一个（见 {@link TestArtifacts}），
     *                  所以下面那句「怎么引用兄弟文件」照着它写，不再另问一次平台
     */
    public static String instructions(String directory, String entry) {
        boolean windows = entry.endsWith(".cmd");
        return """
                现在进入「测试」阶段。代码已经写完并且编译通过了，你要做的是照着下面的用例清单，
                把它们变成**真的能跑的测试**，并给出一个引擎能执行的入口脚本。

                产物一律写进 %s/（相对项目根），就这一个目录。产品代码你一个字节都不要动。

                输出格式：和改代码一样，每处一个补丁块，块之间不要写解释性文字。
                全部都是新建文件，所以 SEARCH 段落留空，REPLACE 段落放完整文件内容：

                <<<<<<< SEARCH %s/测试文件名
                =======
                完整文件内容
                >>>>>>> REPLACE

                硬性规则：
                1. 路径必须以 %s/ 开头。写别的路径会被整批拒绝——测试代码不许去改产品代码，
                   自己给自己判卷的测试等于没测。
                2. 入口脚本必须是 %s，它自己就是一条完整可跑的命令序列：
                   %s
                   它必须能从「项目根目录」这个工作目录跑起来，编译产物和依赖都写在脚本里。
                3. 用什么写测试，看项目本身：项目里已经有测试框架（依赖里有、目录里已经有测试）
                   就按它写；没有就用**零依赖**的检查方式（一个能自己断言的程序或脚本），
                   不要为了测试去改依赖清单，也不要联网装东西。
                4. 退出码就是结论：全部通过 → 0；有任何一条没过 → 非 0。
                   不要用 0 表示「我跑完了但一条都没验」。
                5. **每条用例**都要留下一行结论，整行独占一行：
                   过了的：PASS | 用例编号
                   没过的：FAIL | 用例编号 | 期望什么 | 实际什么 | 你认为谁错了
                   例：PASS | 1
                   例：FAIL | 2 | 查不到时返回空集合 | 返回了 null | 代码错了
                   「你认为谁错了」这一栏写「代码错了」或「用例可能不合理」，可以再跟一句理由；
                   拿不准就写「说不清」。它是给人看的线索，不是结论。
                   为什么要逐条打：引擎拿它和用例清单对账——清单上 3 条、实际一条都没跑，
                   哪怕退出码是 0，这次也不算通过。
                6. 跑不起来的时候（找不到编译器/解释器、依赖装不上、连不上库、端口被占……）
                   打印一行 BLOCKED | 缺什么、要怎么办，然后非 0 退出。
                   **不许**把这种情况写成 FAIL——那会让人以为是产品代码错了。
                7. 断言要照着用例清单里的「期望什么」写，**不要**照着现在的实现写。
                   把实际行为抄成期望，测试永远是绿的，那比没有测试更糟：它会让人以为验过了。
                8. 不要写删除文件、动系统配置、挂载目录、提权、下载外网东西这类命令；
                   出现这类写法，整批产物会被拒绝落盘。
                9. 除了 PASS / FAIL / BLOCKED 这三种行，输出尽量少：不要整段整段地打日志。
                10. 入口脚本正文尽量只用 ASCII。Windows 的命令提示符是按本机代码页读脚本的，
                    正文里的中文可能变成乱码，严重时会把命令本身拆坏（连转义符都会被吃掉）；
                    要输出中文，就在脚本开头先切到 UTF-8 再往下写。
                """.formatted(directory, directory, directory, entry, workingDirectoryHint(windows));
    }

    /**
     * 入口脚本怎么找到自己目录里的文件。
     *
     * <p>必须说清楚：引擎执行脚本时的工作目录是<b>项目根</b>（不是脚本所在目录），
     * 而脚本要去编译它旁边那几个文件。这一步没交代，生成出来的脚本就会在
     * 「用相对路径找兄弟文件」上翻车——而这看起来像测试代码写错了。
     */
    private static String workingDirectoryHint(boolean windows) {
        return windows
                ? "工作目录是项目根目录；要引用自己旁边的文件，用 %~dp0（脚本所在目录），"
                        + "不要拿相对路径去猜。"
                : "工作目录是项目根目录；要引用自己旁边的文件，用 \"$(cd \"$(dirname \"$0\")\" && pwd)\" "
                        + "这样的写法定位脚本所在目录，不要拿相对路径去猜。";
    }

    /**
     * 把用例清单渲染成发给模型的那一段。
     *
     * <p>六栏原样给它，包括分级：分级在引擎里只做显示优先级，但对它有用——
     * 「必须过」的那几条要写扎实，「可选」的写不出来可以不写（宁可少一条用例，
     * 也不要让它为了凑数写一条假绿的断言）。
     */
    public static String caseList(List<PlanReview.TestCase> cases) {
        StringBuilder out = new StringBuilder("## 用例清单（这次要验的就是它们）\n")
                .append("编号").append(" | ").append("要测什么").append(" | ").append("怎么测")
                .append(" | ").append("分级").append(" | ").append("期望什么")
                .append(" | ").append("对应哪条验收标准").append('\n');
        for (PlanReview.TestCase testCase : cases) {
            out.append(testCase.index()).append(" | ").append(testCase.what()).append(" | ")
                    .append(testCase.how()).append(" | ").append(testCase.level().label()).append(" | ")
                    .append(testCase.expected()).append(" | ").append(testCase.acceptance()).append('\n');
        }
        out.append("\n每条用例至少对应一处断言；每条用例都要打一行结论，编号原样写进那行 "
                + "PASS（过了）或 FAIL（没过）里——引擎靠它对回清单，也算得出「验了几条」。");
        return out.toString();
    }
}
