package com.specflow.tests;

import com.specflow.review.PlanReview;
import com.specflow.verify.VerificationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把「跑完脚本看到的那堆输出」判成几档之一，并解析出失败清单与逐条用例的下场。
 *
 * <p>这是本批里唯一<b>机器自己下判断</b>的地方，所以判据写得非常窄，
 * 而且刻意<b>不问被测脚本的意见</b>：脚本可以打印「我认为代码错了」，
 * 那只是给人看的线索；「这次失败算哪一档」由引擎按下面这套顺序定。
 * 让被测方给自己定性，就是让写错的那一方投票。
 *
 * <p>判定顺序（先到先算）：
 * <ol>
 *   <li><b>引擎自己看见的</b>：进程压根起不来、输出读不出来 → 环境问题；</li>
 *   <li><b>跑过头了</b> → 测试超时（单独一档，见 {@link TestOutcome.Failure.Kind#TIMEOUT}）；</li>
 *   <li>有 {@link TestProtocol#FAIL_PREFIX} 行 → 断言失败（一条一行，进失败清单）；</li>
 *   <li>脚本打印了 {@link TestProtocol#BLOCKED_PREFIX} 行 → 环境问题（它自己说没跑起来）；</li>
 *   <li>输出里有「找不到命令 / 连不上」这类字样，<b>而且那一行本身像条报错</b> → 环境问题；</li>
 *   <li>非 0 退出但一行失败都没打印 → <b>测试代码问题</b>（它没能跑出结论：
 *       多半是编译不过、引用了不存在的 API、或者脚本自己写错了）。</li>
 * </ol>
 *
 * <p><b>为什么第 3 条必须排在第 5 条前面。</b>用例自己打印「连不上」「找不到命令」是
 * <b>很正常</b>的事（验「连不上时该返回 503」就会把 Connection refused 原样打出来）。
 * 先看环境字样的话，一次正常的断言失败会被判成环境问题——而环境问题的收场是
 * <b>整次回滚</b>（把编译通过的改动一起撤掉）外加一句「改测试代码解决不了」。
 * 判错的代价两边不对称，顺序就不能只是「看起来顺眼」。
 *
 * <p><b>为什么最后一条不能并进第 3 条。</b>「测试代码编译不过」和「产品代码错了」是两件事：
 * 前者要人去修测试（或者让 Test Agent 重生成），后者才轮到开发那一侧。
 * 合并成一句「测试没过」，用户第一个动作必然是去翻产品代码——方向从一开始就错了。
 */
public final class TestReport {

    /**
     * 「压根没跑起来」的字样。
     *
     * <p>刻意<b>只收这一类</b>：找不到命令、连不上、拉不到镜像。像
     * 「程序包 X 不存在」「找不到符号」这种<b>故意不收</b>——那是测试代码自己写错了，
     * 按用户口径要标成「测试代码问题」。收进来会造成两个后果：类型报错，
     * 而且环境问题会触发<b>回滚</b>（把编译通过的改动一起撤掉），代价不对称，宁可不收。
     *
     * <p>同样不收「Permission denied」：测试脚本忘了给自己加执行位也会长这样，
     * 而那属于测试代码写错了。
     *
     * <p><b>每一条都带着「报错的样子」，不是「一行里出现关键字就算」。</b>
     * shell 报的「命令不存在」有它固定的版式（引号里是被调的程序名，或者
     * {@code sh: 1: python3: command not found}），连不上则是异常对象打出来的
     * （行里还有 Exception / Error / Traceback）。只认关键字的写法会把用例自己打的
     * 那句话（{@code case 5: expect ECONNREFUSED handling}）也算成环境问题，
     * 而那正是这一条要避免的错判。
     */
    private static final List<Pattern> ENVIRONMENT = List.of(
            // Windows cmd：『javac』不是内部或外部命令，也不是可运行的程序
            Pattern.compile("[\"'\u2018\u2019\u201c\u201d][^\"'\u2018\u2019\u201c\u201d]{1,80}"
                    + "[\"'\u2018\u2019\u201c\u201d]\\s*不是内部或外部命令"),
            // Windows cmd：系统找不到指定的路径
            Pattern.compile("系统找不到指定的路径"),
            // POSIX shell：sh: 1: python3: command not found / bash: line 3: pytest: command not found
            Pattern.compile("\\b(sh|bash|dash|zsh|ksh|/\\S*sh):\\s*(\\S{1,20}):\\s*(\\S{1,40}:\\s*)?"
                    + "(command )?not found", Pattern.CASE_INSENSITIVE),
            // 连不上（库、服务、端口）：异常/错误对象在前
            Pattern.compile("(exception|\\berror\\b|traceback|caused by)[^\\n]{0,120}"
                    + "(connection refused|econnrefused|could not connect|connection timed out"
                    + "|unknownhost|no route to host)", Pattern.CASE_INSENSITIVE),
            // 同一个意思的另一种行序：Connection refused: connect / ECONNREFUSED ...
            Pattern.compile("(connection refused|econnrefused|could not connect|connection timed out"
                    + "|unknownhost|no route to host)[^\\n]{0,120}"
                    + "(exception|\\berror\\b|traceback|caused by)", Pattern.CASE_INSENSITIVE),
            // 容器/镜像拿不到（这一批不跑 Docker，但脚本里可能写了）——它自己就带着 Error/Cannot
            Pattern.compile("cannot connect to the docker daemon|error response from daemon"
                    + "|pull access denied for", Pattern.CASE_INSENSITIVE));

    private static final Pattern FAIL_LINE = Pattern.compile("^FAIL(\\s|\\||:|$)");
    private static final Pattern BLOCKED_LINE = Pattern.compile("^BLOCKED(\\s|\\||:|$)");
    private static final Pattern PASS_LINE = Pattern.compile("^PASS(\\s|\\||:|$)");

    private static final String SEPARATOR = "\\|";

    /** 用例编号：宽容地取那一栏里的第一串数字（脚本可能写成「用例 2」）。 */
    private static final Pattern CASE_NUMBER = Pattern.compile("\\d+");

    /** 摘给用户看的原话长度上限：够看清是什么，又不至于把整段日志糊上去。 */
    private static final int MAX_QUOTE = 200;

    /** 溯源失败最多列几行：模型写歪时可能一次报几十条，全铺上去会把界面淹掉。 */
    private static final int MAX_TRACE_ROWS = 20;

    private TestReport() {
    }

    /**
     * 把一次「生成 → 跑」的结果收成一次测试阶段的结论。
     *
     * @param run       执行器给出来的结论与退出码
     * @param directory 产物目录（相对项目根）
     * @param files     生成了哪些文件（相对项目根）
     * @param calls     为了生成它花了多少次模型调用
     */
    public static TestOutcome conclude(TestScriptVerifier.ScriptResult run, String directory,
                                       List<String> files, int calls) {
        VerificationResult result = run.verification();
        String output = result.output() == null ? "" : result.output();
        return new TestOutcome(directory, files, calls, run.exit(), result,
                classify(result, output), reported(output), List.of());
    }

    /**
     * <b>拒绝跑</b>：溯源核对没过（见 {@link CaseTraceCheck}），这批测试一个字节都不执行。
     *
     * <p>为什么是「拒绝跑」而不是「跑完记一笔」：四条判据每一条都意味着<b>清单和代码不是同一份东西</b>——
     * 少写一条、多写一条、写重了、或者把期望改成了实际值。这种产物跑出来的结论没有任何意义，
     * 它只会产出一份「看着像证据」的东西，而人还要花时间去分辨哪几条是真的（十五.9 那条：
     * 绝不用「跑过了」冒充「验过了」）。所以引擎在这一步就停，把差异摆出来，
     * 让人走「测试代码错了 → 重新生成」那条路。
     *
     * <p>产物<b>留在磁盘上</b>：那正是要给人看的东西（哪一段没接线、期望被改成什么样），
     * 而且重新生成之后收场时按留档一起删（十五.8）。
     *
     * @param cases 冻结的那份清单：一条都没验过，所以每条都记成<b>没过</b>——
     *              「没验」不能算「过了」，这也是界面上那条通过率的分母
     */
    public static TestOutcome traceRefused(int calls, String directory, List<String> files,
                                           List<PlanReview.TestCase> cases,
                                           CaseTraceCheck.Report trace) {
        List<TestOutcome.Failure> failures = new ArrayList<>();
        for (CaseTraceCheck.Finding finding : trace.findings()) {
            if (failures.size() >= MAX_TRACE_ROWS) {
                // 不静默截断：剩下的有多少条要说出来（模型写歪时可能一次报几十条）
                failures.add(new TestOutcome.Failure(TestOutcome.Failure.Kind.TEST_CODE, "", "",
                        "还有 " + (trace.findings().size() - MAX_TRACE_ROWS)
                                + " 处差异没有列出来（一共 " + trace.findings().size() + " 处）", ""));
                break;
            }
            failures.add(new TestOutcome.Failure(TestOutcome.Failure.Kind.TEST_CODE,
                    String.valueOf(finding.index()), expectedOf(cases, finding.index()),
                    // 把判据名摆在最前面：界面那一行只有「哪条 / 期望 / 实际」三栏，
                    // 不说清是四条里的哪一条，人看到的只是一句「测试代码有问题」
                    finding.kind().label() + "：" + finding.detail() + where(finding), ""));
        }
        List<TestOutcome.CaseResult> results = new ArrayList<>(cases.size());
        for (PlanReview.TestCase testCase : cases) {
            results.add(new TestOutcome.CaseResult(testCase.index(), false));
        }
        return new TestOutcome(directory, files, calls, TestScriptVerifier.NO_EXIT_CODE,
                VerificationResult.skipped(TestScriptVerifier.NAME,
                        "溯源核对不通过，这批测试没有被执行：" + trace.summarize()),
                failures, results, trace.links());
    }

    /** 溯源失败那一行里「期望」那一栏：清单上这条写的是什么（对不上时人一眼就能看出差异）。 */
    private static String expectedOf(List<PlanReview.TestCase> cases, int index) {
        return cases.stream()
                .filter(testCase -> testCase.index() == index)
                .map(PlanReview.TestCase::expected)
                .findFirst()
                .orElse("");
    }

    private static String where(CaseTraceCheck.Finding finding) {
        return finding.where().isEmpty() ? "" : "（" + finding.where() + "）";
    }

    /**
     * 拿检查阶段的清单和脚本实际报出来的对账，把「验了几条」这一笔补上。
     *
     * <p>为什么必须有这一步：脚本可能一条用例都没跑就退出 0（写一句「all passed」最省事），
     * 也可能只跑了清单里的一半。只看退出码和失败清单的话，这两种都会被当成「通过」，
     * 而界面上的通过率还会跟着虚高——<b>分母是清单，分子却没人算过</b>。
     *
     * <p>没报出来的用例<b>算没过</b>，并且单独出一条「测试代码问题」：它不是断言上失败，
     * 而是压根没验（清单上有、脚本没做），那多半是生成的测试代码漏了。
     *
     * <p><b>清单外的编号也一样要报出来。</b>脚本打了 {@code PASS | 9}，而清单只有 1~8 时，
     * 旧实现把它<b>静默丢掉</b>了（真模型实测：集成脚本自造了 9、10 两条，全过，
     * 界面上和留档里一个字都没有）。丢掉等于让脚本自己给自己加用例、自己给自己算通过率，
     * 所以现在把它单独报成一条失败——它既不算通过也不算没过，只是「清单对不上」这个事实。
     *
     * @param declared 检查阶段定下来、用户确认过的那份清单（没有它就没有分母）
     * @param links    溯源连线（见 {@link CaseTraceCheck}）：界面上「已连线 / 未连线」那一栏
     */
    public static TestOutcome coverage(TestOutcome outcome, List<PlanReview.TestCase> declared,
                                       List<CaseTraceCheck.Link> links) {
        if (declared == null || declared.isEmpty()) {
            return outcome;
        }
        Map<Integer, Boolean> ran = new LinkedHashMap<>();
        for (TestOutcome.CaseResult result : outcome.cases()) {
            ran.put(result.index(), result.passed());
        }
        List<TestOutcome.CaseResult> cases = new ArrayList<>(declared.size());
        List<Integer> notRan = new ArrayList<>();
        for (PlanReview.TestCase testCase : declared) {
            Boolean passed = ran.get(testCase.index());
            if (passed == null) {
                notRan.add(testCase.index());
            }
            cases.add(new TestOutcome.CaseResult(testCase.index(), Boolean.TRUE.equals(passed)));
        }
        List<TestOutcome.Failure> failures = outcome.failures();
        List<Integer> outside = ran.keySet().stream()
                .filter(index -> declared.stream().noneMatch(one -> one.index() == index))
                .sorted()
                .toList();
        if (!outside.isEmpty()) {
            failures = new ArrayList<>(failures);
            failures.add(new TestOutcome.Failure(TestOutcome.Failure.Kind.TEST_CODE,
                    join(outside), "",
                    "脚本报出来的编号 " + join(outside) + " 不在用例清单里（清单是第 "
                            + join(declared.stream().map(PlanReview.TestCase::index).toList())
                            + " 条）：这些结论对不回任何一条用例，既不算通过也不算没过",
                    ""));
        }
        // 脚本压根没跑起来（环境问题）或没跑完（超时）时，不再补那条「哪几条没报」：
        // 那两种情况下一条都没报是当然的，再列一遍只是噪声，还会把真正的原因挤到后面
        boolean explain = !notRan.isEmpty() && !outcome.environmental()
                && outcome.worst() != TestOutcome.Failure.Kind.TIMEOUT;
        if (explain) {
            failures = new ArrayList<>(failures);
            failures.add(new TestOutcome.Failure(TestOutcome.Failure.Kind.TEST_CODE, "", "",
                    "清单上共 " + declared.size() + " 条用例，脚本只报了 "
                            + (declared.size() - notRan.size()) + " 条；没报的是第 "
                            + join(notRan) + " 条——没验不等于验过了", ""));
        }
        return new TestOutcome(outcome.directory(), outcome.files(), outcome.calls(), outcome.exit(),
                outcome.verification(), failures, cases, links);
    }

    /** 一串编号写成「1、2、3」。 */
    private static String join(List<Integer> indexes) {
        StringBuilder out = new StringBuilder();
        for (Integer index : indexes) {
            out.append(out.length() == 0 ? "" : "、").append(index);
        }
        return out.toString();
    }

    /**
     * 还没跑到脚本就收场了：模型没按协议给补丁块、路径写到产物目录外面、命中高危命令。
     *
     * <p>归「测试代码问题」而不是「产品代码错了」：坏的是这一批测试产物本身，
     * 产品代码刚才还编译通过了。产物已经被删掉，所以 {@code directory} 给空串——
     * 留一个指向空目录的路径只会让人去翻一个不存在的地方。
     *
     * <p>结论那一栏给 {@code SKIPPED}：脚本确实没跑，把它记成「通过」或「失败」
     * 都是在替一次没发生的事下判断。
     */
    public static TestOutcome rejected(int calls, String reason) {
        return new TestOutcome("", List.of(), calls, TestScriptVerifier.NO_EXIT_CODE,
                VerificationResult.skipped(TestScriptVerifier.NAME, "测试产物没能落地：" + reason),
                List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.TEST_CODE,
                        "", "", reason, "")),
                List.of(), List.of());
    }

    /**
     * 测试脚本压根没跑起来：环境问题（镜像拉不到、健康检查超时、init/reset 失败、容器不在）。
     *
     * <p>和 {@link #rejected} 的区别就是这批里最要紧的一条分档：那个是「测试代码不能用」，
     * 这个是「这台机器上跑不起来」。判错的代价不对称——把环境问题记成测试代码问题，
     * 用户会去改一份本来就对的东西；反过来则会让一次真的写错的测试被当成环境的锅。
     * 所以调用方（{@code DevelopmentAgent}）必须自己知道手里的是哪一类，engine 不猜。
     *
     * <p>{@code reason} 里要带<b>原始错误和待办</b>（十五.5）：只有一句「环境问题」，
     * 用户既不知道该修哪台机器、也不知道该改哪一行。
     */
    public static TestOutcome environmental(int calls, String reason) {
        return new TestOutcome("", List.of(), calls, TestScriptVerifier.NO_EXIT_CODE,
                VerificationResult.failed(TestScriptVerifier.NAME, "", reason,
                        VerificationResult.Kind.ENVIRONMENT),
                List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.ENVIRONMENT,
                        "", "", reason, "")),
                List.of(), List.of());
    }

    /**
     * 把几次脚本执行的结果合成一份。
     *
     * <p>为什么会有「几次」：勾了集成测试时要跑<b>两个</b>入口脚本（单元一个、集成一个，
     * 两个都在同一个位置跑——有可用环境就是容器里，否则都回退宿主；见
     * {@link ExecutionLocation}），而用例清单是一份——只跑其中一个，
     * 另一条路上的用例会被对账逻辑判成「没验」，于是每次勾集成都会收到一份假的失败清单。
     *
     * <p>合并规则都朝着「宁可让人来看一眼」的一边：
     * <ul>
     *   <li>失败清单<b>直接相加</b>：两个脚本各自报的失败都要留着；</li>
     *   <li>同一条用例两边都报了：按<b>没过</b>算（和 {@link #reported} 里同一个口径）；</li>
     *   <li>结论那一栏取<b>最重</b>的那一个（环境问题 &gt; 超时 &gt; 其它）：环境问题意味着
     *       这次运行要立刻停并回滚，超时意味着后面的结论不算数——两者都不该被
     *       「另一个脚本跑得好好的」冲淡。</li>
     * </ul>
     * 输出正文是两段接起来的（各自带一行「哪个脚本、退出码多少」），
     * 所以界面上的「脚本的原始输出」看到的仍然是全部事实。
     */
    public static TestOutcome merge(List<TestOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return environmental(0, "没有跑任何测试脚本");
        }
        if (outcomes.size() == 1) {
            return outcomes.get(0);
        }
        List<TestOutcome.Failure> failures = new ArrayList<>();
        Map<Integer, Boolean> cases = new LinkedHashMap<>();
        StringBuilder output = new StringBuilder();
        int calls = 0;
        int exit = 0;
        for (TestOutcome outcome : outcomes) {
            failures.addAll(outcome.failures());
            for (TestOutcome.CaseResult result : outcome.cases()) {
                cases.merge(result.index(), result.passed(), (first, second) -> first && second);
            }
            if (output.length() > 0) {
                output.append(System.lineSeparator());
            }
            output.append("--- ").append(outcome.verification() == null
                            ? "（没有结论）" : outcome.verification().command())
                    .append("：退出码 ").append(outcome.exit()).append(" ---")
                    .append(System.lineSeparator()).append(outcome.output());
            calls += outcome.calls();
            if (exit == 0 && outcome.exit() != 0) {
                exit = outcome.exit();
            }
        }
        // 结论取最重的那一个；都是「跑完了」时按有没有失败收成通过/未通过
        VerificationResult heaviest = outcomes.stream()
                .map(TestOutcome::verification)
                .filter(result -> result != null && (result.environmental() || result.timedOut()))
                .findFirst()
                .orElse(null);
        boolean passed = failures.isEmpty() && outcomes.stream().allMatch(TestOutcome::passed);
        VerificationResult combined = heaviest != null
                ? failedWith(heaviest.kind(), output.toString())
                : passed ? VerificationResult.passed(TestScriptVerifier.NAME, entriesOf(outcomes),
                        output.toString())
                : VerificationResult.failed(TestScriptVerifier.NAME, entriesOf(outcomes),
                        output.toString(), VerificationResult.Kind.NONE);
        List<TestOutcome.CaseResult> merged = cases.entrySet().stream()
                .map(entry -> new TestOutcome.CaseResult(entry.getKey(), entry.getValue()))
                .toList();
        return new TestOutcome(outcomes.get(0).directory(), outcomes.get(0).files(), calls, exit,
                combined, failures, merged, outcomes.get(0).links());
    }

    /** 「哪几个脚本」——合并之后的那一栏要能看出它不止一个。 */
    private static String entriesOf(List<TestOutcome> outcomes) {
        return String.join(" + ", outcomes.stream()
                .map(outcome -> outcome.verification() == null ? "?" : outcome.verification().command())
                .toList());
    }

    private static VerificationResult failedWith(VerificationResult.Kind kind, String output) {
        return VerificationResult.failed(TestScriptVerifier.NAME, "", output, kind);
    }

    /**
     * 产物已经清掉的那一版。
     *
     * <p>环境问题这条路会连同产品改动一起回滚，测试产物没有可测的代码了，
     * 于是整批删掉——留档里那条结论还留着（它答得出「当时想验什么、为什么没跑成」），
     * 但目录与文件清单得清空：指向一个不存在的地方比留空更糟。
     */
    public static TestOutcome cleared(TestOutcome outcome) {
        return new TestOutcome("", List.of(), outcome.calls(), outcome.exit(),
                outcome.verification(), outcome.failures(), outcome.cases(), outcome.links());
    }

    // ---------- 分档 ----------

    private static List<TestOutcome.Failure> classify(VerificationResult result, String output) {
        // 1) 引擎自己看见的：起不来、读不出来。这类失败不属于某一条用例
        if (result.environmental()) {
            return List.of(environmental(firstUsefulLine(output)));
        }
        // 2) 跑过头了：单独一档。既不算环境问题（环境弄好了它也还是慢），
        //    也不算断言失败（它压根没跑到结论那一行）
        if (result.timedOut()) {
            return List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.TIMEOUT,
                    "", "", firstUsefulLine(output), ""));
        }
        // 3) 有 FAIL 行就是断言失败——**先看它**，理由见类注释
        List<TestOutcome.Failure> failures = failures(output);
        if (!failures.isEmpty()) {
            return failures;
        }
        // 4) 脚本自己说「我连跑都没跑起来」
        String blocked = markerLine(output, BLOCKED_LINE);
        if (blocked != null) {
            return List.of(environmental(blocked));
        }
        // 5) 输出里有「命令不存在 / 连不上」这类字样（脚本忘了打印 BLOCKED 时的兜底）
        String evidence = environmentEvidence(output);
        if (evidence != null) {
            return List.of(environmental(evidence));
        }
        // 6) 退出码 0、也没有上面任何一种迹象：这次就是过了
        if (result.passed()) {
            return List.of();
        }
        // 7) 非 0 退出、却一行失败都没打印：它没能跑出结论
        return List.of(new TestOutcome.Failure(TestOutcome.Failure.Kind.TEST_CODE,
                "", "", firstUsefulLine(output), ""));
    }

    private static TestOutcome.Failure environmental(String actual) {
        return new TestOutcome.Failure(TestOutcome.Failure.Kind.ENVIRONMENT, "", "", actual, "");
    }

    /**
     * 解析 {@code FAIL | 用例编号 | 期望 | 实际 | 它认为} 这些行。
     *
     * <p>宽容到底：字段少写几个、中间少一根竖线，都不该让整条失败消失——
     * 失败清单是给用户看的证据，宁可它缺一栏，也不要整条不见。
     */
    static List<TestOutcome.Failure> failures(String output) {
        List<TestOutcome.Failure> found = new ArrayList<>();
        for (String text : marked(output, FAIL_LINE)) {
            String[] fields = markerFields(text, TestProtocol.FAIL_PREFIX);
            found.add(new TestOutcome.Failure(TestOutcome.Failure.Kind.ASSERTION,
                    field(fields, 0), field(fields, 1), field(fields, 2), field(fields, 3)));
        }
        return found;
    }

    /**
     * 脚本自己报出来的每条用例的下场：{@code PASS | 编号} 与 {@code FAIL | 编号 | …}。
     *
     * <p>只认脚本打印的那两行，顺序按编号。清单上有没有这些编号、有没有漏报，
     * <b>不在这里判</b>——那是 {@link #coverage} 的活，因为只有它拿得到清单。
     */
    static List<TestOutcome.CaseResult> reported(String output) {
        Map<Integer, Boolean> byOutcome = new LinkedHashMap<>();
        for (String text : marked(output, PASS_LINE)) {
            Integer index = caseIndex(markerFields(text, TestProtocol.PASS_PREFIX));
            if (index != null) {
                byOutcome.put(index, true);
            }
        }
        for (String text : marked(output, FAIL_LINE)) {
            Integer index = caseIndex(markerFields(text, TestProtocol.FAIL_PREFIX));
            if (index != null) {
                // 同一条既报过 PASS 又报过 FAIL：按没过算。宁可让人来看一眼，
                // 也不要让一条真的失败被前面那行 PASS 盖掉
                byOutcome.put(index, false);
            }
        }
        return byOutcome.entrySet().stream()
                .map(entry -> new TestOutcome.CaseResult(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 输出里那句「这台机器上跑不起来」的原话；没有就返回 {@code null}。
     *
     * <p>报环境问题却不说凭什么，等于把人推回去翻日志，所以命中的那一行原样摘出来。
     */
    static String environmentEvidence(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            for (Pattern pattern : ENVIRONMENT) {
                if (pattern.matcher(text).find()) {
                    return clip(text);
                }
            }
        }
        return null;
    }

    private static String markerLine(String output, Pattern marker) {
        List<String> lines = marked(output, marker);
        return lines.isEmpty() ? null : clip(lines.get(0));
    }

    /** 输出里所有以某个标记起头的行（已去首尾空白）。 */
    private static List<String> marked(String output, Pattern marker) {
        List<String> found = new ArrayList<>();
        if (output == null) {
            return found;
        }
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (marker.matcher(text).find()) {
                found.add(text);
            }
        }
        return found;
    }

    /** 把标记行拆成字段：第一个字段是用例编号，后面按各自的行规。 */
    private static String[] markerFields(String text, String prefix) {
        String rest = text.substring(prefix.length()).strip();
        rest = rest.startsWith(":") ? rest.substring(1).strip() : rest;
        rest = rest.startsWith(TestProtocol.SEPARATOR) ? rest.substring(1) : rest;
        return rest.split(SEPARATOR);
    }

    /** 那一行认得出用例编号吗——认不出就返回 {@code null}（它进不了「已跑」的账）。 */
    private static Integer caseIndex(String[] fields) {
        Matcher matcher = CASE_NUMBER.matcher(field(fields, 0));
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }

    /** 输出里第一行有内容的——摘不到特征词时的兜底（那也正是用户要看的原话）。 */
    private static String firstUsefulLine(String output) {
        if (output == null) {
            return "";
        }
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (!text.isEmpty()) {
                return clip(text);
            }
        }
        return "";
    }

    private static String field(String[] fields, int index) {
        return fields.length > index ? fields[index].strip() : "";
    }

    private static String clip(String text) {
        String value = text == null ? "" : text;
        return value.length() > MAX_QUOTE ? value.substring(0, MAX_QUOTE) + "…" : value;
    }
}
