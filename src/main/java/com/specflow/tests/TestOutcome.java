package com.specflow.tests;

import com.specflow.verify.VerificationResult;

import java.util.List;

/**
 * 测试阶段跑完之后留下的东西。
 *
 * <p>它存在的理由不是「好看」，而是<b>留档要答得出的那几个问题</b>：
 * 生成了哪些文件、脚本的退出码是多少、哪几条用例没过（期望什么、实际什么、是哪一类失败）。
 * 少了任何一条，界面上的失败清单就只剩一句「测试没过」，而人拿到那句话什么也做不了。
 *
 * <p>结论那一栏用的是<b>既有的</b> {@link VerificationResult}，不是另造一个类型：
 * 它在这个项目里就是「写入之后、收尾之前那段可失败的检查」的通用结论，
 * CLI、留档、将来挂进校验器列表都认它。差别只在于测试这一档还要带上失败清单。
 *
 * <p><b>它不判定谁对谁错。</b>按十五.1 的口径，测试不是自动门槛，是给人看的证据：
 * 机器摆事实（哪条、期望、实际、类型），判断「代码错了还是用例写错了」由人做。
 * 引擎里唯一自作主张的地方是 {@link #environmental()}——那种失败再跑一万次也还是那样。
 *
 * @param directory    测试产物落在哪儿（相对项目根的路径，形如 {@code tools/20260930-120000}）；
 *                     产物已经清掉时是空串，免得把留档的人指向一个不存在的地方
 * @param files        这次生成的文件（相对项目根，含入口脚本），给人按图索骥用
 * @param calls        为了生成它花掉的模型调用次数。它<b>不算开发轮次</b>，
 *                     但一样是用户掏的钱，所以单独记一笔
 * @param exit         入口脚本的退出码；没拿到（起不来、超时、生成就没成功）是
 *                     {@link TestScriptVerifier#NO_EXIT_CODE}
 * @param verification 执行器给出的结论：通过 / 未通过 / 没跑成，附带原始输出
 * @param failures     失败清单，见 {@link Failure}；通过时为空
 * @param cases        每条用例这次是什么下场，见 {@link CaseResult}。它有分母的作用：
 *                     只看 {@code failures} 的话，「声明 3 条、脚本一条都没跑、退出码 0」
 *                     会显示成一次满分
 */
public record TestOutcome(
        String directory,
        List<String> files,
        int calls,
        int exit,
        VerificationResult verification,
        List<Failure> failures,
        List<CaseResult> cases
) {

    /**
     * 一条用例这次的下场。
     *
     * <p>为什么失败清单不够、还要单独记它：失败清单只答得出「哪几条没过」，
     * 答不出「一共验了几条」——而「必须过 3/3」里的那个 3 就是清单的长度。
     * 少了它，界面上的通过率会虚高，最极端的一档是<b>一条都没跑</b>，
     * 却因为没有失败清单而显示成满分（实测过：脚本打了「all passed」就退出 0）。
     *
     * @param index  用例编号，和检查阶段那份清单对得上
     * @param passed 它过了没有。没跑到的也是 {@code false}——「没验」不能算「过了」
     */
    public record CaseResult(int index, boolean passed) {
    }

    /**
     * 一条失败。
     *
     * <p>四个字段是生成的测试代码<b>必须打印出来的四要素</b>（见 {@link TestProtocol}）：
     * 哪条、期望、实际、它认为。引擎只负责把它们原样收好，不去判断谁错——
     * 所以最后那一栏叫 {@code opinion}（它认为），不叫结论。
     *
     * <p>{@code kind} 是<b>机器判的</b>（怎么判见 {@link TestReport}），和
     * {@code opinion} 是两件事：脚本说「代码错了」，引擎该记的类型仍然是「断言没过」。
     * 让被测方自己给自己定性，就是让写错的那一方投票。
     *
     * @param kind     失败类型：断言 / 测试代码 / 环境
     * @param testCase 哪条用例。断言失败时是脚本打印出来的编号或那句话；
     *                 整个脚本就没跑起来时为空——那种失败不属于某一条用例
     * @param expected 期望什么（脚本原话）
     * @param actual   实际什么（脚本原话）
     * @param opinion  脚本自己认为谁错了（「代码错了」/「用例可能不合理」）。可能是错的，
     *                 所以界面要标明这是它的猜测
     */
    public record Failure(
            Kind kind,
            String testCase,
            String expected,
            String actual,
            String opinion
    ) {

        /**
         * 失败类型。这几档是<b>收场方式</b>的分界，不是措辞上的分类：
         * <ul>
         *   <li>{@link #ENVIRONMENT} —— 立刻停、回滚，把原始错误给人（再跑也没用）；</li>
         *   <li>{@link #TIMEOUT} —— 没在时限内跑完，单独一档：不算环境问题（环境弄好了它也还是慢），
         *       也不该连累磁盘上那份改动被回滚；</li>
         *   <li>{@link #ASSERTION} —— 交给人（机器判不了是代码错还是用例错），<b>不自动回喂</b>；</li>
         *   <li>{@link #TEST_CODE} —— 和「产品代码错」分开标：坏的是测试代码本身
         *       （编译不过、引用了不存在的 API、压根没跑出结论）。</li>
         * </ul>
         */
        public enum Kind {
            ASSERTION("断言失败"),
            TEST_CODE("测试代码问题"),
            ENVIRONMENT("环境问题"),
            TIMEOUT("测试超时");

            private final String label;

            Kind(String label) {
                this.label = label;
            }

            public String label() {
                return label;
            }
        }

        public Failure {
            testCase = text(testCase);
            expected = text(expected);
            actual = text(actual);
            opinion = text(opinion);
            kind = kind == null ? Kind.ASSERTION : kind;
        }

        /** 失败清单里的一行，也是时间线上那一行。 */
        public String describe() {
            if (testCase.isEmpty()) {
                return kind.label() + "：" + actual;
            }
            return "用例 " + testCase + "：" + kind.label()
                    + "，期望 " + (expected.isEmpty() ? "（没写）" : expected)
                    + "，实际 " + (actual.isEmpty() ? "（没写）" : actual);
        }

        private static String text(String value) {
            return value == null ? "" : value.strip();
        }
    }

    public TestOutcome {
        directory = directory == null ? "" : directory;
        files = files == null ? List.of() : List.copyOf(files);
        failures = failures == null ? List.of() : List.copyOf(failures);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /** 脚本的原始输出（失败时是掐过头的，完整的那份在 {@code .specflow/logs/} 里）。 */
    public String output() {
        return verification == null || verification.output() == null ? "" : verification.output();
    }

    /** 什么都没失败，而且脚本自己说通过（退出码 0）。 */
    public boolean passed() {
        return exit == 0 && failures.isEmpty();
    }

    /**
     * 没过的那些用例编号，升序。
     *
     * <p>它是「这次运行带着几条失败用例」的那份名单，收场时要照着它落档（十五.8）。
     * 判据和 {@link CaseResult#passed()} 一样，<b>没跑到的也算没过</b>——
     * 「没验过」不能算「过了」，否则一次「脚本一条都没跑、退出码 0」的运行会在留档里
     * 变成一次满分（那正是 {@code CaseResult} 存在的理由）。
     */
    public List<Integer> failingCases() {
        return cases.stream()
                .filter(one -> !one.passed())
                .map(CaseResult::index)
                .sorted()
                .toList();
    }

    /**
     * 这次失败是不是「再跑也没用」的那一类。
     *
     * <p>它是上层的刹车信号：环境问题要立刻停下、回滚，并把原始错误交给人——
     * 和编译那边判「缺依赖」是同一个道理。
     *
     * <p>超时<b>不</b>在这一类里：环境弄好了也还是慢，而且磁盘上那份改动未必有错（见 {@link Failure.Kind#TIMEOUT}）。
     */
    public boolean environmental() {
        return failures.stream().anyMatch(failure -> failure.kind() == Failure.Kind.ENVIRONMENT);
    }

    /**
     * 这次测试阶段算哪一档——给运行终态用，也和界面上那一行字一一对应。
     *
     * <p>环境问题优先：一次运行里同时出现「连不上」和「断言没过」时，
     * 断言那条结果本身就不可信（脚本是跑到一半崩的），先说环境。
     * 超时排第二：它同样意味着「后面那些结论都不算数」。
     */
    public Failure.Kind worst() {
        if (failures.isEmpty()) {
            return null;
        }
        if (environmental()) {
            return Failure.Kind.ENVIRONMENT;
        }
        if (failures.stream().anyMatch(failure -> failure.kind() == Failure.Kind.TIMEOUT)) {
            return Failure.Kind.TIMEOUT;
        }
        return failures.stream().anyMatch(failure -> failure.kind() == Failure.Kind.TEST_CODE)
                ? Failure.Kind.TEST_CODE
                : Failure.Kind.ASSERTION;
    }

    /**
     * 给用户看的一段话：这次测试怎么样、哪几条没过、为什么。
     *
     * <p>刻意<b>不</b>替用户下结论（「产品代码错」这种话一句都不说）：能说的只有事实。
     * 说错方向的代价不对称——一句「代码写错了」会让人去改一份本来就对的代码。
     */
    public String detail() {
        StringBuilder out = new StringBuilder();
        Failure.Kind worst = worst();
        if (worst == Failure.Kind.TIMEOUT) {
            // 超时要写在最前面：这一档和「跑完了但没过」是两件事，
            // 事后翻留档的人要一眼看出「那次根本没跑完」，而不是以为断言失败了
            out.append("测试超时：脚本没在时限内结束，已经被强制终止（整棵进程树一起收掉了）。");
        } else if (exit < 0) {
            out.append("测试阶段没能跑起来。");
        } else {
            out.append("测试脚本退出码 ").append(exit).append("。");
            if (!directory.isEmpty()) {
                out.append("产物在 ").append(directory).append("/。");
            }
        }
        if (failures.isEmpty()) {
            return exit == 0
                    ? out.append("没有报出失败。").toString()
                    : out.append("它非 0 退出，但没打印出任何失败用例——见下面的原始输出。").toString();
        }
        out.append(System.lineSeparator()).append("失败清单：");
        for (Failure failure : failures) {
            out.append(System.lineSeparator()).append("  - ").append(failure.describe());
            if (!failure.opinion().isEmpty()) {
                out.append("（它认为：").append(failure.opinion()).append("）");
            }
        }
        if (worst == Failure.Kind.ENVIRONMENT) {
            out.append(System.lineSeparator())
                    .append("这一类是环境问题：缺命令、连不上、脚本本身起不来——")
                    .append("改测试代码解决不了，先把环境弄好再说。");
        } else if (worst == Failure.Kind.TIMEOUT) {
            out.append(System.lineSeparator())
                    .append("超时不是环境问题，也不是断言没过：它要么真的慢，要么卡住了。")
                    .append("磁盘上的改动我们没回滚——先看看它卡在哪儿，再决定重跑还是改测试。");
        } else if (worst == Failure.Kind.TEST_CODE) {
            out.append(System.lineSeparator())
                    .append("坏的是测试代码本身（编译不过、引用了不存在的 API、没跑出结论），")
                    .append("不是产品代码——两者要分开看。");
        } else {
            out.append(System.lineSeparator())
                    .append("这几条是断言没过：可能是产品代码错了，也可能是用例写错了，")
                    .append("机器判不了，交给你定。改动还在磁盘上，等你看完再决定。");
        }
        return out.toString();
    }
}
