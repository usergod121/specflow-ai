package com.specflow.flow;

/**
 * <b>闸要的那几个事实</b>——判据的输入，只有一处读它（{@link Gates}）。
 *
 * <h2>它为什么是一个记录、而不是各处各读一遍</h2>
 * 上一版每一条路由、每一个动作都自己去盘上推一遍「环境起没起过」「用例几条」，
 * 于是同一件事有几个口径；最要命的那一次是：写事实的地方写 {@code envConfirmed}、
 * 读的地方读 {@code envReady}，于是「起环境成功过」这件事<b>闸从来没看见过</b>，
 * ① 永远冻结不了，后面四格一格都动不了。所以这一版：
 * <ul>
 *   <li>事实的<b>名字</b>只在这里出现一次（下面那些 {@code public static final String}）；</li>
 *   <li>事实的<b>取值</b>也只有一处（{@code FlowService.facts()}）——
 *       {@link Gates} 是纯函数，同样的 facts 永远给同样的答案，闸因此能被单元测试钉住。</li>
 * </ul>
 *
 * <p>每一条事实的落点（写它的地方用常量，别写字面量）：
 * <pre>
 * compile        项目里那条编译命令（{@code .specflow/project.yaml} 的 build.compile），不走 facts
 * envReady       起环境成功过                ① 的闸要它     {@code env/up} 成功时写
 * envNotNeeded   用户显式声明不需要环境       ① 的闸要它     界面上那个「跳过环境」写
 * envDeclared    env.yaml 在不在盘上         只在话术里出现  盘上现读（或已记档）
 * probeFiles     探针挑出了可读文件          ② 的闸要它     {@code probe/save} 写
 * probePrompt    给开发的 prompt 非空        ② 的闸要它     {@code probe/save} 写
 * caseCount      用例几条                    ③ 的闸要它     {@code cases/save} 写
 * casePending    还有几条没到终态            ⑤ 的闸要它     测试 Agent 每条写回时更新
 * testRun        这一轮测试真跑过            只进话术       {@code test/run} 开始时写
 * submitted      测试提交过（带总结）        ⑤ 的闸要它     {@code test/submit} 写
 * compilePassed  最近一次编译过没过          ④ 的闸要它     开发每次交差编译后写
 * </pre>
 */
public record Facts(String compile, boolean envReady, boolean envNotNeeded, boolean envDeclared,
                    boolean probeFiles, boolean probePrompt, int caseCount, int casePending,
                    boolean testRun, boolean submitted, boolean compilePassed) {

    // ---------- 事实的名字：写它的与读它的都用这里那一个（不变量 2） ----------

    /** ① 「起环境跑成功了」。**这个名字只许出现这一次。** */
    public static final String ENV_READY = "envReady";

    /** ① 「这个项目不需要环境」（必须由人显式声明：没配和不要是两件事）。 */
    public static final String ENV_NOT_NEEDED = "envNotNeeded";

    /** ① 「env.yaml 声明过」（盘上没有也可能是因为人在别处声明过）。 */
    public static final String ENV_DECLARED = "envDeclared";

    /** ② 「探针挑出了开发能读的文件」。 */
    public static final String PROBE_FILES = "probeFiles";

    /** ② 「给开发的那段 prompt 组装好了」。 */
    public static final String PROBE_PROMPT = "probePrompt";

    /** ③ 「清单里几条用例」。 */
    public static final String CASE_COUNT = "caseCount";

    /** ⑤ 「清单里还有几条没到终态」。 */
    public static final String CASE_PENDING = "casePending";

    /** ④ 「最近一次编译过没过」。 */
    public static final String COMPILE_PASSED = "compilePassed";

    /** ⑤ 「这一轮测试真跑过」。 */
    public static final String TEST_RUN = "testRun";

    /** ⑤ 「提交过（带总结）」。 */
    public static final String SUBMITTED = "submitted";

    /** 界面那枚「冻结」徽章读的就是它（{@code lib/facts.js} 的 isFrozen）。 */
    public static final String FROZEN = "frozen";

    /** 最近一段跑成没成——界面据此在状态条上写「失败」。 */
    public static final String FAILED = "failed";

    /** 最近一段跑了几轮。 */
    public static final String ROUNDS = "rounds";

    /**
     * 内部锚点：冻结这一步那一刻，事件流写到第几行。
     *
     * <p><b>为什么它必须记在盘上</b>：回退要「把事件流截到该节点开始之前」，
     * 而「开始之前」只有一处知道——上一步冻结的那一刻。事后从事件里反推是不可靠的
     * （锁着的下游节点也会写事件：用户点过它、被闸拦下，那一条的 node 就是下游）。
     *
     * <p>键名带下划线：它不进 {@link NodeView}（界面把 facts 里每个键画成一枚徽章，
     * 这个数对人没有意义）。
     */
    public static final String CURSOR = "_cursor";

    public Facts {
        compile = compile == null ? "" : compile.strip();
    }

    /** 一条编译命令都没配（① 的闸两个条件里的第一个）。 */
    public boolean compileConfigured() {
        return !compile.isBlank();
    }

    /** 什么都还不知道的一份（刚开一条流程、以及单元测试用）。 */
    public static Facts empty() {
        return new Facts("", false, false, false, false, false, 0, 0, false, false, false);
    }
}
