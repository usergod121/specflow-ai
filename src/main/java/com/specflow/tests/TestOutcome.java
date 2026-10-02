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
 * @param links        溯源连线：每条用例的测试代码落在哪个文件的第几行
 *                     （见 {@link CaseTraceCheck}）。界面上每个用例 chip 那一行
 *                     「✅ 已连线 + 文件:行」就是它；清单上有、这里没有的那几条，
 *                     就是界面要标红的「未连线」。老记录里没有这一项，读出来是 {@code null}
 */
public record TestOutcome(
        String directory,
        List<String> files,
        int calls,
        int exit,
        VerificationResult verification,
        List<Failure> failures,
        List<CaseResult> cases,
        List<CaseTraceCheck.Link> links
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
         * 失败类型。<b>这里只回答「机器看见了什么」，不回答「谁错了」</b>——
         * 谁错了由人看失败清单来定（十五.1）。
         *
         * <p>六档按「凭什么这么说」排，前两档是<b>引擎自己看见的硬判据</b>，
         * 后四档是脚本的说法或机器的猜测，都<b>不许自动回喂</b>：
         * <ul>
         *   <li>{@link #ENVIRONMENT}（硬）—— 命令/环境<b>起不来</b>：入口脚本不存在、进程起不来、
         *       输出读不出来。引擎亲见，再跑一万次也一样，所以立刻停 + 把原始错误给人；
         *       但<b>不动磁盘</b>：产品改动与测试产物都留着等人处置；</li>
         *   <li>{@link #TIMEOUT}（硬）—— 没在时限内跑完，引擎亲见；单独一档：环境弄好了它也还是慢，
         *       同样不该连累磁盘上那份改动被回滚；</li>
         *   <li>{@link #UNRUNNABLE} —— 跑完了、退出码非 0，却<b>一条用例的结论都没报出来</b>
         *       （多半是它写的代码编不过）。引擎亲见的是「没有结论」，所以按现象说，
         *       不替它判「编译不过」这个原因。这一档会先自动重试生成（见 {@code TestAgent}）；
         *   <li>{@link #BLOCKED} —— 脚本自己打了一行 {@code BLOCKED}（或者输出里有带报错形状的
         *       「命令不存在 / 连不上」）。<b>那是它的说法</b>：实测过它把「编译不过」也写成
         *       BLOCKED，所以引擎只转述、采信它就没有下文了——停下等人看，不回滚、不删产物；</li>
         *   <li>{@link #ASSERTION} —— 输出里有 {@code FAIL | …} 行：脚本说这几条没过。机器只看见
         *       现象，是产品代码错了还是用例写错了，<b>机器判不了也不判</b>，交给人；</li>
         *   <li>{@link #TEST_CODE} —— 机器<b>核对测试产物本身</b>得出的结论：溯源没接上线、
         *       清单上的用例一条都没报、脚本报了清单外的编号、产物没能落盘。这一档不是猜的，
         *       是算出来的。</li>
         * </ul>
         */
        public enum Kind {
            ENVIRONMENT("环境起不来"),
            TIMEOUT("测试超时"),
            UNRUNNABLE("它的代码编不过"),
            BLOCKED("脚本报跑不起来"),
            ASSERTION("断言没过"),
            TEST_CODE("测试代码问题");

            private final String label;

            Kind(String label) {
                this.label = label;
            }

            public String label() {
                return label;
            }

            /**
             * 这是不是那两条<b>机器说了算</b>的硬判据（十五.5 的第①条与第②条）。
             *
             * <p>它的意思是「不用再往下跑了」——这两档再跑一万次也还是这样。
             * <b>它不等于「把磁盘收掉」</b>：从这一批起，硬判据同样不回滚、不删产物，
             * 改动进「待处置」、现场原样留着等人处置（判错的代价不对称：机器一次误判
             * 若顺手替人把改动收掉，人手里就什么都不剩了）。
             */
            public boolean hard() {
                return this == ENVIRONMENT || this == TIMEOUT;
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
        links = links == null ? List.of() : List.copyOf(links);
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
     * 这次失败是不是「命令/环境压根起不来」——<b>引擎亲见的那一条</b>。
     *
     * <p>它是上层的刹车信号：这一档要立刻停下，并把原始错误交给人——和编译那边判「缺依赖」
     * 是同一个道理。<b>但它不动磁盘</b>：产品改动与测试产物都留在原处，和改动一起进「待处置」。
     *
     * <p>它<b>只认引擎自己看见的那一档</b>（进程起不来、输出读不出来）。脚本自己打一行
     * {@code BLOCKED} 说的「跑不起来」不在这里：那句话是它的说法，实测过它拿这句话
     * 盖过自己的编译错误，采信它就会把编译通过的产品改动一起回滚掉（见 {@link Failure.Kind#BLOCKED}）。
     *
     * <p>超时<b>不</b>在这一类里：环境弄好了也还是慢，而且磁盘上那份改动未必有错（见 {@link Failure.Kind#TIMEOUT}）。
     */
    public boolean environmental() {
        return failures.stream().anyMatch(failure -> failure.kind() == Failure.Kind.ENVIRONMENT);
    }

    /**
     * 这次失败里有没有那两条硬判据（起不来 / 超时）。
     *
     * <p>它是「机器判得了」与「机器判不了」的分界：硬的那两条可以停机；其余每一档都只摆事实。
     * <b>停机不等于回滚</b>——两条硬判据同样把改动和产物原样留着（见 {@link Failure.Kind#hard()}）。
     * 分开成方法而不是让各处自己写 {@code failures.stream().anyMatch(...)}：
     * 判据只有一处，改一处就够。
     */
    public boolean hardStopped() {
        return failures.stream().anyMatch(failure -> failure.kind().hard());
    }

    /**
     * 这次测试阶段算哪一档——给运行终态用，也和界面上那一行字一一对应。
     *
     * <p>顺序按「机器有多确定」排，不按严重度排：
     * 硬判据在前（环境起不来 &gt; 超时——它们意味着后面那些结论都不算数），
     * 然后是机器看见或算出来的（一条结论都没跑出来 &gt; 脚本说它没跑起来 &gt; 测试产物本身对不上线），
     * 最后才是现象（断言没过）。
     *
     * <p><b>BLOCKED 排在 TEST_CODE 前面</b>：脚本说「我压根没跑起来」时，另一条「清单上这几条没报」
     * 只是它的后果；把后果当成结论（「测试代码问题」）会把人的注意力引到改测试上去。
     */
    public Failure.Kind worst() {
        if (failures.isEmpty()) {
            return null;
        }
        for (Failure.Kind kind : List.of(Failure.Kind.ENVIRONMENT, Failure.Kind.TIMEOUT,
                Failure.Kind.UNRUNNABLE, Failure.Kind.BLOCKED, Failure.Kind.TEST_CODE)) {
            if (failures.stream().anyMatch(failure -> failure.kind() == kind)) {
                return kind;
            }
        }
        return Failure.Kind.ASSERTION;
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
            // 脚本压根没被执行：生成阶段就被拒了，或者溯源核对没过，再或者它起不来（环境）。
            // 产物留着时要说清在哪儿——「现场给你留着了」这句话得能落到一个具体的目录上
            out.append("这一次测试脚本没有被执行。");
            if (!directory.isEmpty()) {
                out.append("测试产物还在 ").append(directory).append("/。");
            }
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
        out.append(System.lineSeparator()).append(closing(worst));
        return out.toString();
    }

    /**
     * 收尾那句「接下来怎么办」——<b>按档分，因为每一档的下一步完全不同</b>。
     *
     * <p>两条硬判据说「停下来把环境弄好，现场给你留着」；其余每一档都明说「机器判不了谁错，
     * 证据都在这儿，你看着定」——实测过的教训是：机器下的结论会被当结论读，
     * 而一句错方向的结论比不下结论糟得多。
     *
     * <p>硬判据那一档还非说清「改动没回滚」不可：用户 2026-10-02 拍板「环境起不来也保留现场」，
     * 而这一档以前是自动回滚的——旧说法留着不改，人就不知道该去「待处置」里找那份改动。
     */
    private String closing(Failure.Kind worst) {
        if (worst == Failure.Kind.ENVIRONMENT) {
            return "环境起不来，改动未回滚，等你处置：这一类是「命令/环境压根起不来」，"
                    + "引擎亲眼看见的（脚本自己打的 BLOCKED 不算）——改测试代码解决不了，"
                    + "先把环境弄好再说。产品改动与测试产物都原样留着（进「待处置」），"
                    + "你可以修好环境重跑、也可以直接保留或撤回它。";
        }
        if (worst == Failure.Kind.TIMEOUT) {
            return "超时不是环境问题，也不是断言没过：它要么真的慢，要么卡住了。"
                    + "磁盘上的改动我们没回滚、测试产物也留着——先看看它卡在哪儿，"
                    + "再决定重跑还是改测试；改动本身进「待处置」，等你处置。";
        }
        if (worst == Failure.Kind.UNRUNNABLE) {
            return "它写的测试代码一条用例的结论都没跑出来（多半是编不过）——换了 "
                    + Math.max(1, calls) + " 版都是这样，就不再自动重试了。"
                    + "原始错误在下面的输出里；改动<b>没有回滚</b>，产物也留着。";
        }
        if (worst == Failure.Kind.BLOCKED) {
            return "脚本自己说它没跑起来。引擎<b>只转述这句话，不当结论</b>（实测过它拿这句话"
                    + "盖住自己的编译错误），所以这里不猜是谁的错：改动没有回滚、产物也没有删，"
                    + "原始输出在下面，你看完再决定。";
        }
        if (worst == Failure.Kind.TEST_CODE) {
            return "这几条是引擎核对测试产物本身算出来的（溯源没接上线、清单上的用例没报、"
                    + "产物没能落盘），不是猜的。它和「产品代码错了」是两件事——"
                    + "修它走「测试代码错了 → 重新生成」，别去改产品代码。";
        }
        return "这几条是「脚本报了 FAIL」，机器只看见现象：可能是产品代码错了，也可能是用例写错了，"
                + "机器判不了，交给你定。改动还在磁盘上，等你看完再决定。";
    }
}
