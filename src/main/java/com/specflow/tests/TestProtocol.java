package com.specflow.tests;

import com.specflow.env.TestEnvironment;
import com.specflow.review.PlanReview;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    /**
     * 协议里那三种结论行的判据：前缀后面必须跟空白、竖线、冒号，或者直接就是行尾。
     *
     * <p><b>为什么必须是这一份、而且只有这一份。</b>这三行原来有两套判据：解析器这边要求
     * {@code ^FAIL(\s|\||:|$)}，而「这一版算不算通过」（{@code TestScriptVerifier.isPlainPass}）
     * 那边只判 {@code startsWith("FAIL")}。两套的差别在一次真事上会露出来：脚本退出码是 0、
     * 输出里只有一行 {@code FAILURE: ...}（或 pytest 的 {@code FAILED tests/x.py}）、
     * 一条协议结论都没有时，两套判据得出<b>相反</b>的结论——引擎会报「它的代码编不过」，
     * 并据此白白重生成两版，而真实的下一步是「让脚本按行规把结论打出来」。
     *
     * <p>{@link TestReport} 与 {@code TestScriptVerifier} 都从这里取，谁也别再自己写一份。
     */
    public static final Pattern FAIL_LINE = Pattern.compile("^FAIL(\\s|\\||:|$)");

    /** 见 {@link #FAIL_LINE}。 */
    public static final Pattern BLOCKED_LINE = Pattern.compile("^BLOCKED(\\s|\\||:|$)");

    /** 见 {@link #FAIL_LINE}。 */
    public static final Pattern PASS_LINE = Pattern.compile("^PASS(\\s|\\||:|$)");

    /** 四要素的分隔符。和施工单、缺失项同一套写法：模型对这个格式的执行力最好。 */
    public static final String SEPARATOR = "|";

    /**
     * 第二版起附在用户消息最后的「上一版为什么跑不起来」（见 {@code TestAgent} 的重试循环）。
     *
     * <p>为什么要把原始错误喂回去、而不是让它重新想一遍：实测过它连着三版都选同一条错路
     * （三次都拿反射去改进程环境变量，三次都被 JDK 17 拒），而它并没有看到自己上一版的报错。
     * 带上这段，它至少知道要修什么；同时明说「重给一份完整的产物」——
     * 补丁协议只认整文件，给半份等于整批作废。
     *
     * <p>只给<b>输出</b>，不给上一版的测试代码：那份代码已经不在盘上了（跑不起来的那版被收掉了），
     * 而重新贴一遍只会把上下文撑大，它要的是「哪儿错了」。
     */
    public static String retryNotice(TestOutcome previous) {
        return """
                ## 上一版测试代码没跑起来（引擎原样转述它的输出）
                你上一版给的产物跑完之后**一条用例的结论都没报出来**——引擎判断是编不过或者跑不起来。
                下面是它当时看到的原始输出（可能被掐过头，头部与尾部都保留了）：

                ```
                %s
                ```

                请照着这段原始错误修掉那一处，然后**重新给一份完整的产物**（每个文件一整份，
                SEARCH 段落留空）。不要换个写法重来一遍：同一个错连着两次，说明上一版的判断错了，
                先看清错在哪一行。锚点（CASE / expect）照旧都要写全。
                """.formatted(previous == null ? "" : previous.output());
    }

    /**
     * 上一版产物<b>被安全闸拦下</b>时附在用户消息最后的那一段（见 {@code TestAgent} 的重试）。
     *
     * <p>为什么单独有一段，而不是复用 {@link #retryNotice}：那一段说的是「脚本跑起来之后报错了，
     * 照着原始错误修」，而这一版<b>压根没落盘、一行都没跑</b>。把空输出当错误喂过去，
     * 它多半会去修一个不存在的运行环境问题——真正要它改的是<b>写法</b>：
     * 那条命令触了闸门，换成不删东西、不动系统的做法。
     *
     * <p>拒绝原因原样转述：引擎那句话里已经写清「哪个文件里的哪一段」，
     * 归纳一遍只会把最要紧的那个证据（它到底写了什么）弄丢。
     */
    public static String refusalNotice(String reason) {
        return """
                ## 上一版产物被安全闸拦下了（一个字都没落盘、也没执行）
                你上一版给的产物里有高危命令，引擎当场拒绝落盘、也拒绝执行。拒绝原因原文：

                ```
                %s
                ```

                请**换一种写法**重给一份完整的产物：测试脚本只负责跑测试。
                **不要自己删除目录或文件**——无论是产物目录、临时目录还是产品代码：
                跑前的 reset 与跑后的清理由引擎负责，脚本里出现删除命令一律会被拒。
                同理，不要动系统配置、不要挂载目录、不要提权、不要联网下载东西。
                其余部分（锚点 CASE / expect、PASS / FAIL / BLOCKED 的行规）照旧都要写全。
                """.formatted(reason == null ? "" : reason);
    }

    /**
     * 这一次要生成几个入口脚本、它们在哪儿跑。
     *
     * @param unit        单元入口（任何项目都有）
     * @param integration 集成入口（勾了集成测试才有）；没有时是 {@code null}
     * @param inContainer 这两个脚本在<b>容器里</b>跑吗。由 {@code ExecutionLocation} 一处判，
     *                    协议只照着它措辞——「脚本里该不该自己去调 docker」取决于这个事实，
     *                    说反了就是让一个已经在容器里的脚本再 exec 一次，而镜像里没有 docker
     */
    public record Entries(String unit, String integration, boolean inContainer) {

        public Entries {
            unit = unit == null ? "" : unit;
            integration = integration == null || integration.isBlank() ? null : integration;
        }
    }

    /**
     * 把测试环境（{@code env.yaml} 里的 {@code env:} 与引擎自己那几个把手）写成给模型的一段。
     *
     * <p>为什么要写进提示词：这条链上最贵的一种错是「测试代码把连接串写死了」——
     * 换台机器就全错，而错的方式看起来像功能坏了。所以十五.5 定的是
     * <b>{@code env:} 段原样进上下文</b>，再加上一条硬规则：只许从环境变量读。
     * 它<b>永远不用猜</b>，也就没有理由去硬编码。
     *
     * <p>「怎么够到那些中间件」这一段按<b>执行位置</b>分两种说法：脚本已经被引擎送进容器时，
     * 中间件就在同一张容器网络里、用服务名直连；宿主上就只能让脚本自己去
     * {@code docker compose exec}，那就把那条命令的拼法写给它
     * （compose 项目名和文件路径都是引擎生成的，它自己猜必然猜错）。
     *
     * @param variables   连接信息 + 引擎那三个把手；{@code null} 表示这次没有环境（不写这一段）
     * @param inContainer 脚本会不会被引擎送进容器里跑
     */
    private static String environment(Map<String, String> variables, boolean inContainer) {
        if (variables == null || variables.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("""

                ## 测试环境（连接信息，照这里给的键读环境变量）
                这些变量在跑测试时已经注入好了，**直接读它们，一个都不许硬编码**：

                """);
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            out.append("  ").append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        }
        out.append("""

                **硬规则**：连接串、主机名、端口、库名、密码一律从环境变量读——
                例如 Python 写 os.environ["DB_HOST"]，Java 写 System.getenv("DB_HOST")，
                shell 写 "$DB_HOST"。把 "db:3306" 或 "root/123456" 这种字面量写进测试代码，
                换一台机器就全错，而那种错看起来像功能坏了。测试代码里出现写死的连接串，
                整批产物会被判成不合格。

                """);
        out.append(inContainer ? """
                真正要验的东西就在容器里跑：引擎会把你写的入口脚本送进 app 容器
                （项目目录已经挂在那儿，工作目录就是项目根）。中间件在**同一张容器网络**里，
                用服务名直连（就是上面 env: 里那些主机名），宿主上有没有端口一个都不用管。
                **不要在你的脚本里再调 docker**——测试镜像里没有 docker 命令，
                调了只会得到一句「命令不存在」。
                """ : """
                真正要验的东西要进容器里跑：中间件只在容器网络里有名字（宿主上看不到它们，
                也没有端口映射）。上面那三个 SPECFLOW_ 开头的变量就是把容器叫起来的把手，
                照这个样子用它们（尖括号里是变量的值，别照抄这一行）：
                  docker compose -p <SPECFLOW_COMPOSE_PROJECT> -f "<SPECFLOW_COMPOSE_FILE>" \\
                    exec -T app sh -c "cd <SPECFLOW_WORKDIR> && <你的检查命令>"
                Windows 的 .cmd 里写成 %SPECFLOW_COMPOSE_PROJECT%，sh 里写成 $SPECFLOW_COMPOSE_PROJECT。
                """);
        return out.toString();
    }

    /**
     * 「这个项目怎么构建」那一段——拼在<b>测试生成</b>那条用户消息的最后。
     *
     * <p><b>为什么非有这一段。</b>2026-10-04 的真项目实测（JDK17 + Maven + 一个第三方库）：
     * 三版入口脚本都写同一句 {@code BLOCKED | gson jar not found under lib or libs |
     * place gson jar in lib\}——它把依赖的路径<b>猜</b>成了一个没人满足的约定，
     * 还把它当成对用户的要求。根因不是模型笨：那七次调用的提示词里
     * {@code pom.xml} 出现 <b>0 次</b>、{@code mvn} 出现 <b>0 次</b>——
     * 协议让它「看项目本身」，却没给它任何关于这个项目的信息。
     *
     * <p><b>为什么给的是「命令」而不是「语言」。</b>这一段的内容全部来自
     * {@code project.yaml} 里用户自己配的 {@code build.compile}（或 spec 里的覆盖），
     * 引擎<b>一个分支都没有加</b>：换一个构建工具，这一段跟着那条命令一起变。
     * 与 {@code env.yaml} 的连接信息进上下文是同一个路子——差异由用户声明，引擎只转述。
     *
     * @param compileCommand 这一次真正会跑的编译命令（见 {@code CompileVerifier.compileCommandOf}）；
     *                       为空表示两处都没配，那就如实说没配
     */
    public static String buildNotice(String compileCommand) {
        String command = compileCommand == null || compileCommand.isBlank()
                ? "（这个项目没有配编译命令：.specflow/project.yaml 的 build.compile 是空的，"
                        + "spec 里也没覆盖它）"
                : compileCommand.strip();
        return """
                ## 这个项目怎么构建（用户配在 .specflow/project.yaml 里）

                ```
                %s
                ```

                这就是**这个项目自己的**构建方式。写测试脚本时，三条一起守：
                - 编译产物、依赖的类路径，都要**从这个构建工具拿**——它知道这个项目的依赖都在哪；
                - 不要假设某个 jar 躺在某个目录里，更不要把「请把 jar 放到某处」当成对用户的要求：
                  没有谁会去满足它，这一轮就白跑了；
                - 拿不到就按硬性规则第 7 条打 `BLOCKED`，并写清「缺什么、用哪条命令能拿到」——
                  那一行是给人看的，要能照着做。
                """.formatted(command);
    }

    /**
     * 生成测试代码与入口脚本的协议。
     *
     * @param directory   产物目录（相对项目根）
     * @param entries     要生成哪几个入口脚本（单元一定，集成看这次勾没勾）
     * @param variables   {@code env:} 那一组变量；没有环境时传 {@code null}
     */
    public static String instructions(String directory, Entries entries,
                                      Map<String, String> variables) {
        boolean windows = entries.unit().endsWith(".cmd");
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
                %s
                %s
                3. **每段测试代码都要接上线**：引擎靠两行注释把「用例 ⇄ 测试代码」接起来，
                   验每一条用例的那一段代码前面，连着写这两行，**整行只写这一句**：
                     CASE <编号>        ← 编号照抄清单里「编号」那一列
                     expect: <期望>     ← 把清单里这条用例「期望什么」那一栏**逐字照抄**，一个字都不许改
                   注释符按语言来（Java 用 //，Python 和 shell 用 #，SQL 用 --，HTML 用 <!--，
                   Windows 的 .cmd 用 REM），但这两行的**内容格式完全一样**——
                   单元测试和集成测试用的是**同一套锚点**，别在集成那边换一种写法。
                   一条用例在**同一个文件里只写一处**：同一个编号在**一个文件内**出现两次算「重复实现」；
                   （单元与集成各写一遍是允许的——那本来就是两条路，锚点不会因此被判重）
                   清单上没有的编号一个都不许多写。
                   例：
                     // CASE 1
                     // expect: 返回 0.00
                   引擎照着它机器核对四件事：清单上有、代码里没扫到 = **漏实现**；代码里有、
                   清单上没有 = **清单外乱写**；**同一个文件里**同一个编号出现多次 = **重复实现**；
                   expect 与清单对不上（哪怕只改了一个数字）= **偷偷改期望**。
                   **任何一条不通过，这批测试一次都不会被运行**，会被打回来重新生成——
                   所以宁可少写一条用例，也不要为了凑数把编号或期望写歪。
                4. 用什么写测试：**看这个项目自己的构建命令**——它在下面那条用户消息的最后一段
                   （「这个项目怎么构建」）。编译产物与依赖的类路径都从那套工具链拿，
                   这是唯一可靠的路子：你看不到这个项目的依赖清单，也看不到它的目录里
                   有没有现成的测试框架。
                   例（构建命令是 `mvn …` 的工程）：用
                   `mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt`
                   把依赖路径导成一个文件，编译产物在 `target/classes`；
                   别的构建工具有它们自己的任务，照同一套路子来。
                   有一件事**不许**做：自己发明一个「把某个 jar 放到某个目录」的约定，
                   再把它当成对用户的要求——没有人会去满足它，这一轮就白跑了
                   （2026-10-04 的真项目实测：三版脚本连卡三次，都卡在这儿）。
                5. 退出码就是结论：全部通过 → 0；有任何一条没过 → 非 0。
                   不要用 0 表示「我跑完了但一条都没验」。
                6. **每条用例**都要留下一行结论，整行独占一行：
                   过了的：PASS | 用例编号
                   没过的：FAIL | 用例编号 | 期望什么 | 实际什么 | 你认为谁错了
                   例：PASS | 1
                   例：FAIL | 2 | 查不到时返回空集合 | 返回了 null | 代码错了
                   「你认为谁错了」这一栏写「代码错了」或「用例可能不合理」，可以再跟一句理由；
                   拿不准就写「说不清」。它是给人看的线索，不是结论。
                   为什么要逐条打：引擎拿它和用例清单对账——清单上 3 条、实际一条都没跑，
                   哪怕退出码是 0，这次也不算通过。
                7. 跑不起来的时候（找不到编译器/解释器、依赖装不上、连不上库、端口被占……）
                   打印一行 BLOCKED | 缺什么、要怎么办，然后非 0 退出。
                   **不许**把这种情况写成 FAIL——那会让人以为是产品代码错了。
                8. 断言要照着用例清单里的「期望什么」写，**不要**照着现在的实现写。
                   把实际行为抄成期望，测试永远是绿的，那比没有测试更糟：它会让人以为验过了。
                9. 不要写删除文件、动系统配置、挂载目录、提权、下载外网东西这类命令；
                   出现这类写法，整批产物会被拒绝落盘。
                   **测试脚本不许自己删目录、删文件**——连产物目录里的临时文件也不要删：
                   跑前的数据 reset 和跑后的清理由**引擎**负责（它会按登记清单收干净），
                   脚本只管跑测试。实测过连着三版都在入口脚本里写 rm -rf "$OUT_DIR"
                   （想把上一次的输出目录清掉再跑），三版全被闸门拒掉，一轮白跑。
                   要一个干净的输出目录，就在脚本里换一个新目录名，或者直接覆盖写。
                10. 除了 PASS / FAIL / BLOCKED 这三种行，输出尽量少：不要整段整段地打日志。
                11. %s
                %s""".formatted(directory, directory, directory, entries.unit(),
                workingDirectoryHint(windows), whereHint(entries), entryScripts(entries),
                asciiRule(entries), environment(variables, entries.inContainer()));
    }

    /**
     * 入口脚本正文的编码这一条。
     *
     * <p>宿主上那条是按本机代码页读脚本的（cmd 的坑），容器里是 Linux，没有这一条问题。
     * 说反了不会立刻出事，但会让模型为一件不存在的事加一堆防御代码。
     */
    private static String asciiRule(Entries entries) {
        return entries.inContainer()
                ? "入口脚本正文尽量只用 ASCII，要输出中文就按 UTF-8 写——容器里是 Linux，"
                        + "编码不会像 Windows 控制台那样被本机代码页带偏。\n"
                : "入口脚本正文**只用 ASCII**（注释也算正文）。Windows 的命令提示符是按本机代码页读脚本的，\n"
                        + "    而引擎落盘时行尾统一是 LF：正文里一出现中文，解码就会错位——\n"
                        + "    下一行开头的命令被当成上一行的一部分，那一行直接被拿去当命令执行（实测过，\n"
                        + "    报出来的错和测试本身毫无关系）。要打印中文内容（比如用例的期望值），\n"
                        + "    把它写在**测试代码文件**里（.java / .py 是 UTF-8，没有这个问题），\n"
                        + "    入口脚本只打印 ASCII 的结论行。\n";
    }

    /**
     * 入口脚本<b>在哪儿跑</b>——模型必须知道这一条，它决定脚本里能不能用 docker。
     *
     * <p>不写这一段的话，模型会照着自己对这台机器的印象猜：它有可能会写一条
     * 面向宿主的命令（宿主的路径、宿主才有的 docker），而脚本会被引擎送进 Linux 容器里执行。
     * 那种错的表现是「命令不存在」，看起来像环境没装好。
     */
    private static String whereHint(Entries entries) {
        return entries.inContainer()
                ? "   引擎会把这两个入口脚本送进 **app 容器** 里执行（项目目录已经挂在那儿，"
                        + "工作目录就是项目根）：写 **Linux/POSIX** 的脚本，"
                        + "用项目测试镜像里本来就有的工具链，别在脚本里调 docker。\n"
                : "   引擎会在 **本机（宿主）** 上执行这两个入口脚本：写本机平台的脚本（"
                        + (entries.unit().endsWith(".cmd") ? "Windows 的 .cmd" : "POSIX 的 sh")
                        + "），用这台机器上已有的工具链。\n";
    }

    /**
     * 这一次要做几个入口脚本，各是什么。
     *
     * <p>写成一段话而不是一句话，是因为「要两个文件」这件事模型漏得最多：
     * 它看到「入口脚本」四个字，天然只会写一个——而少的那一个的结果是
     * 「集成测试一条都没跑」，不是「跑挂了」。
     */
    private static String entryScripts(Entries entries) {
        if (entries.integration() == null) {
            return "";
        }
        String where = entries.inContainer() ? "在容器里" : "在宿主上";
        return """
                2b. 这一次**要两个**入口脚本，两个都要写出来：
                    %s —— 单元测试：不需要任何外部服务，%s就能跑完。
                    %s —— 集成测试：要连库/中间件的那几条用例放这里，按上面那段环境说明
                    连过去（服务名只在容器网络里解析得到）。它同样要逐条打 PASS/FAIL，
                    跑不起来同样打 BLOCKED。
                    两个入口的**锚点写法完全一样**（同一个编号可以各写一处，那是正常的）。
                    两条路上的用例**别把同一件事验两遍**：同一个断言写两处，
                    一处过了另一处没过时，没人说得清该信哪个。
                """.formatted(entries.unit(), where, entries.integration());
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
                + "PASS（过了）或 FAIL（没过）里——引擎靠它对回清单，也算得出「验了几条」。"
                + "脚本报出来的编号必须是这一栏里的编号：报了清单上没有的编号，引擎会把它"
                + "单独报成「清单外用例」，既不算通过也不算没过。\n"
                + "另外，上面第 3 条那行锚点里的 expect 要照抄这里的「期望什么」原文。\n"
                // 「怎么测」是第二段补的，说清它和期望的来历不同：期望在看过代码之前就定死了
                // （那正是它可信的原因），怎么测是看过这次改动之后补的
                + "「怎么测」那一栏是看过这次改动之后补的（第二段）；「期望什么」是需求那边定下来的，"
                + "一个字都没跟着代码改过——别拿现在的实现去改它。");
        return out.toString();
    }

    /**
     * <b>第一段</b>定下来的那几栏，发给第二段看。
     *
     * <p>它<b>没有「怎么测」</b>：在第二段之前那一栏本来就是空的（第一段还没看过代码，
     * 写出来的「怎么测」只能是它照着脑子里的实现猜的）。把它印成表头反而会诱导模型
     * 顺手填一栏上去，而第二段的产物只该是那一栏。
     *
     * <p>为什么连「要测什么 / 期望什么 / 验收标准」一起再给它一遍、而不只给编号：
     * 它要<b>照抄期望</b>，那就有个「照抄的对象」必须摆在眼前——只给编号，它只能凭记忆写，
     * 而机器会逐字比。这个比较是这一段唯一不能出错的地方。
     */
    public static String firstStageList(List<PlanReview.TestCase> cases) {
        StringBuilder out = new StringBuilder("## 第一段定下来的清单（期望照抄这里的原文）\n")
                .append("编号").append(" | ").append("要测什么").append(" | ").append("分级")
                .append(" | ").append("期望什么").append(" | ").append("对应哪条验收标准").append('\n');
        for (PlanReview.TestCase testCase : cases) {
            out.append(testCase.index()).append(" | ").append(testCase.what()).append(" | ")
                    .append(testCase.level().label()).append(" | ").append(testCase.expected())
                    .append(" | ").append(testCase.acceptance()).append('\n');
        }
        out.append("\n你只补「怎么测」那一栏，行格式是：编号 | 怎么测 | 期望（照抄上面这一栏）。\n");
        return out.toString();
    }
}
