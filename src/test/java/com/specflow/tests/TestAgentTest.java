package com.specflow.tests;

import com.specflow.TestSpecs;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.project.ProjectConfig;
import com.specflow.review.PlanReview;
import com.specflow.spec.Spec;
import com.specflow.template.TemplateRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 测试 Agent 的整条链：一次模型调用 → 落盘 → 跑脚本 → 看退出码。
 *
 * <p>假模型是<b>照系统提示词里那个目录</b>来给答案的——和真模型一样：目录是引擎指定给它的。
 * 拿它当输入而不是写死一个目录，是因为目录名带时间戳，写死就只能在「正好那一秒」才跑得过。
 */
@DisplayName("测试 Agent")
class TestAgentTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private static final Pattern DIRECTORY = Pattern.compile("tools/\\d{8}-\\d{6}(-\\d+)?");

    @TempDir
    Path root;

    /**
     * 假模型被调用了几次。
     *
     * <p>它数的是<b>真实的生成次数</b>：这一批起「生成→跑」是一个循环（跑不出来就再来一版），
     * 「重试了几次」只能靠这个计数器看见（{@code outcome.calls()} 是它最终报出来的那个数）。
     */
    private int generation;

    @Test
    @DisplayName("一条断言没过：失败清单带四要素，产物留在磁盘上给人看")
    void reportsAssertionFailure() throws IOException {
        // 这里刻意用 ASCII：Windows 的 .cmd 是按本机代码页读的，脚本正文里的中文可能被拆坏
        // （连 `^|` 的转义都会被吃掉，命令就废了）。四要素的解析本身在 TestReportTest 里用中文验
        TestOutcome outcome = run(answer -> block(answer.entry(), anchoredScript(1,
                "PASS | 1", "FAIL | 2 | empty-collection | got null | code is wrong")));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.exit()).isEqualTo(1);
        assertThat(outcome.calls()).as("生成测试代码花的调用次数要记一笔").isEqualTo(1);
        assertThat(outcome.directory()).startsWith("tools/");
        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.ASSERTION);
            assertThat(failure.testCase()).isEqualTo("2");
            assertThat(failure.expected()).isEqualTo("empty-collection");
            assertThat(failure.actual()).isEqualTo("got null");
        });
        assertThat(outcome.cases()).as("逐条用例的下场要留着，界面上那个分母就是它")
                .extracting(TestOutcome.CaseResult::passed).containsExactly(true, false);
        assertThat(root.resolve(outcome.directory())).as("失败清单要能点开看测试代码").isDirectory();
        assertThat(outcome.files()).anyMatch(name -> name.endsWith(entryName()));
    }

    @Test
    @DisplayName("脚本退出码 0 且逐条报了结论：一条失败都没有")
    void reportsPassingRun() {
        TestOutcome outcome = run(answer -> block(answer.entry(),
                anchoredScript(0, "PASS | 1", "PASS | 2")));

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.exit()).isZero();
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.verification().passed()).isTrue();
        assertThat(outcome.cases()).hasSize(2)
                .allSatisfy(caseResult -> assertThat(caseResult.passed()).isTrue());
        assertThat(outcome.links()).as("接上线的证据要留着：界面每个 chip 那一行靠它")
                .hasSize(2)
                .allSatisfy(link -> {
                    assertThat(link.file()).endsWith(entryName());
                    assertThat(link.line()).as("行号是锚点那一行，要能点过去").isPositive();
                });
    }

    /**
     * 模型常犯的写法：打一堆 FAIL 行，最后 {@code exit /b 0}。
     * 旧代码见退出码 0 就收成「通过」并把日志删掉，于是这次运行在留档里是满分，
     * 而失败清单就在旁边——自相矛盾，而且错在宽的一边。
     */
    @Test
    @DisplayName("脚本打了 FAIL 行却 exit /b 0：不算通过，日志也不许删")
    void zeroExitWithFailLinesIsNotAPass() {
        TestOutcome outcome = run(answer -> block(answer.entry(),
                anchoredScript(0, "FAIL | 1 | expected-a | got-b | code is wrong")));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.failures()).isNotEmpty();
        assertThat(outcome.verification().passed()).isFalse();
        try (var logs = Files.list(root.resolve(".specflow/logs"))) {
            assertThat(logs).as("失败清单的现场就在那份日志里，不许顺手删").isNotEmpty();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 用例清单是分母。脚本一条都没跑、只打一句「all passed」就退出 0 时，
     * 旧代码会把它记成「测试通过」——界面上的通过率跟着虚高，而这是最坏的一种虚高。
     */
    @Test
    @DisplayName("声明两条、脚本一条都没报：不算通过，并说清哪几条没验")
    void refusesWhenNoCaseWasReportedAtAll() {
        TestOutcome outcome = run(answer -> block(answer.entry(), anchoredScript(0, "all passed")));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.cases()).extracting(TestOutcome.CaseResult::passed)
                .containsExactly(false, false);
        assertThat(outcome.detail()).contains("没验不等于验过了");
        assertThat(outcome.environmental()).as("漏跑用例不是环境问题，不许连累回滚").isFalse();
    }

    /**
     * Windows 上 {@code TOOLS/} 和 {@code tools/} 落到同一个目录，而 entry() 用的是真实拼写。
     * 返回值照抄模型写的那份拼写时，上游那句「产物里有没有入口脚本」会对不上——
     * 整批被拒，报出来的原因还是「没给入口脚本」这种和真实情况无关的话。
     */
    @Test
    @DisplayName("模型把产物目录写成大写：照样认，不谎称「没给入口脚本」")
    void acceptsUpperCaseArtifactsDirectory() {
        TestOutcome outcome = run(answer -> block(
                answer.dir().toUpperCase(Locale.ROOT) + "/" + entryName(),
                anchoredScript(0, "PASS | 1", "PASS | 2")));

        assertThat(outcome.detail()).doesNotContain("入口脚本");
        assertThat(outcome.passed()).isTrue();
    }

    /** 同一个错的另一半：目录拼对了，入口脚本自己的名字写成大写（Windows 上是同一个文件）。 */
    @Test
    @DisplayName("模型把入口脚本名写成大写：照样认，不谎称「没给入口脚本」")
    void acceptsUpperCaseEntryName() {
        TestOutcome outcome = run(answer -> block(
                answer.dir() + "/" + entryName().toUpperCase(Locale.ROOT),
                anchoredScript(0, "PASS | 1", "PASS | 2")));

        assertThat(outcome.detail()).doesNotContain("入口脚本");
        assertThat(outcome.passed()).isTrue();
    }

    /**
     * 模型调用没回来 = 这一轮一个字节都没生成。刚建的那个空 {@code tools/<时间戳>/}
     * 必须收掉：留着它会攒成一串空目录，看上去像「跑过好几次测试」，而实际什么都没跑。
     */
    @Test
    @DisplayName("模型调用失败：刚建的空产物目录也要收掉")
    void removesTheEmptyArtifactsDirectoryWhenTheModelCallFails() {
        assertThatThrownBy(() -> agent(messages -> {
            throw new com.specflow.llm.LlmException("network down");
        }).run(spec(), cases()))
                .isInstanceOf(com.specflow.llm.LlmException.class);

        try (var tools = Files.list(root.resolve(TestArtifacts.ROOT))) {
            assertThat(tools).as("空目录攒下来会让项目看着像跑过好几轮测试").isEmpty();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("模型把产物写到产物目录之外：整批拒绝，一个文件都不留")
    void refusesProductCodeEdits() throws IOException {
        TestOutcome outcome = run(answer -> block(answer.dir + "/ok.txt", "ok")
                + block("src/main/java/com/demo/Foo.java", "class Foo {}"));

        assertThat(outcome.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.kind())
                        .isEqualTo(TestOutcome.Failure.Kind.TEST_CODE));
        assertThat(outcome.detail()).contains("只能写在");
        assertThat(root.resolve("src/main/java/com/demo/Foo.java")).doesNotExist();
        assertThat(outcome.directory()).as("整批清掉了，别再指向一个不存在的地方").isEmpty();
        try (var tools = Files.list(root.resolve("tools"))) {
            assertThat(tools).as("半份产物不能留在磁盘上").isEmpty();
        }
    }

    @Test
    @DisplayName("没给入口脚本：拒绝，并说清引擎只会执行那一个文件")
    void refusesMissingEntryScript() {
        TestOutcome outcome = run(answer -> block(answer.dir + "/Check.txt", "某段检查"));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.detail()).contains("入口脚本");
        assertThat(outcome.exit()).isEqualTo(TestScriptVerifier.NO_EXIT_CODE);
    }

    @Test
    @DisplayName("产物里有高危命令：拒绝落盘、也不会执行")
    void refusesDangerousScript() throws IOException {
        TestOutcome outcome = run(answer -> block(answer.entry(),
                WINDOWS ? "@echo off\nrm -rf / && exit /b 0" : "#!/bin/sh\nrm -rf /\nexit 0"));

        assertThat(outcome.detail()).contains("高危命令");
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
        try (var tools = Files.list(root.resolve("tools"))) {
            assertThat(tools).isEmpty();
        }
    }

    @Test
    @DisplayName("模型没按协议给补丁块：报「测试代码问题」，而不是把整次运行掀掉")
    void refusesGarbageAnswer() {
        TestOutcome outcome = run(answer -> "好的，我来写测试。");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
        assertThat(outcome.verification().skipped()).isTrue();
    }

    /**
     * 脚本自己打的那行 {@code BLOCKED} 是<b>它的说法</b>。
     *
     * <p>旧行为是采信它：整批产物删掉、上头再回滚产品改动——实测里它拿这句话盖住了
     * 自己的编译错误，于是用户既看不到那份测试代码，也失去了编译通过的产品代码。
     * 现在：<b>停下等人，产物留着、产品改动不回滚</b>，原始输出摆出来。
     */
    @Test
    @DisplayName("脚本说跑不起来（BLOCKED）：记它的说法，产物留着、不停机")
    void keepsArtifactsWhenTheScriptSaysBlocked() throws IOException {
        TestOutcome outcome = run(answer ->
                block(answer.entry(), anchoredScript(2, "BLOCKED | javac not found")));

        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.BLOCKED);
        assertThat(outcome.environmental()).as("它说的话不等于引擎亲见的环境起不来").isFalse();
        assertThat(outcome.detail()).contains("脚本自己说它没跑起来");
        assertThat(outcome.directory()).as("产物留着：人要看得见它写成什么样").isNotEmpty();
        assertThat(outcome.calls()).as("跑不起来会自动重试到上限（一共三版），账要按版数记").isEqualTo(3);
        try (var tools = Files.list(root.resolve("tools"))) {
            assertThat(tools).as("中间那几版收掉，只留最后一版（它是给人看的那一份）").hasSize(1);
        }
    }

    /**
     * 生成后重试的那条规则（§18 的第 6 条）：<b>跑不出来就再生成一版</b>，
     * 而且要把上一版的原始错误带进下一次生成——重掷骰子只会再错一遍。
     *
     * <p>实测过它连着三版都选同一条错路（拿反射去改进程环境变量，JDK 17 一律拒绝）。
     * 到上限就停下，把原始错误摆给人。
     */
    @Test
    @DisplayName("第一版编不过、第二版就好了：自动重试一次就够，不惊动人")
    void retriesGenerationWhenTheScriptProducedNoConclusion() {
        FakeLlm llm = llm(answer -> ++generation == 1
                // 第一版：编译不过（一条用例的结论都没有）
                ? block(answer.entry(), anchoredScript(1, "error: cannot find symbol"))
                // 第二版：修好了
                : block(answer.entry(), anchoredScript(0, "PASS | 1", "PASS | 2")));

        TestOutcome outcome = agent(llm).run(spec(), cases());

        assertThat(generation).as("引擎自己又生成了一版（这就是「生成后重试」）").isEqualTo(2);
        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.calls()).as("两版就是两次真实调用").isEqualTo(2);
        assertThat(llm.user()).as("重试那次要把上一版的原始错误带上：重掷骰子只会再错一遍")
                .contains("上一版测试代码没跑起来").contains("cannot find symbol");
    }

    /**
     * 反面：<b>能跑起来但用例没过，绝不自动重跑</b>（十五.6）。
     * 那种失败机器判不了是谁的错，自动重跑只是烧调用，还会把判断从人手里抢走。
     */
    @Test
    @DisplayName("能跑但断言没过：一次都不重试，停在原地等人放行")
    void doesNotRetryWhenTheRunJustFailedAnAssertion() {
        FakeLlm llm = llm(answer -> {
            generation++;
            return block(answer.entry(), anchoredScript(1, "PASS | 1", "FAIL | 2 | a | b | code"));
        });

        TestOutcome outcome = agent(llm).run(spec(), cases());

        assertThat(generation).as("只生成了一版").isEqualTo(1);
        assertThat(outcome.failures()).isNotEmpty();
        assertThat(outcome.calls()).isEqualTo(1);
    }

    /**
     * <b>环境起不来时什么都不删</b>（用户 2026-10-02 拍板的那一条硬判据的收场）。
     *
     * <p>旧口径是「停下 + 产物整批删掉」（上头再连同产品改动一起回滚）：人手里什么都没剩，
     * 既看不到它当时写成什么样，也没法判断这次改动值不值得留。现在只停下——产物留着，
     * 改动进「待处置」，由人决定保留还是撤回。
     *
     * <p>造法：把「脚本输出」那个位置先占成一个<b>目录</b>——引擎执行脚本时起不了进程
     * （它要把输出重定向到那里），于是落成引擎亲见的那一档环境问题。占的位置是
     * {@code .specflow/logs/test-<时间戳>.log}，而时间戳是运行那一刻取的，所以按秒铺一段窗口。
     * 试过「让脚本自己把兄弟入口删掉」那种造法：高危闸当场拦下（脚本不许删东西），
     * 这本身是对的，所以换这一种。
     */
    @Test
    @DisplayName("环境起不来（脚本进程起不来）：只停下等人，产物一个都不删")
    void keepsArtifactsWhenTheEnvironmentIsAtFault() throws IOException {
        Path logs = root.resolve(".specflow/logs");
        Files.createDirectories(logs);
        LocalDateTime start = LocalDateTime.now();
        for (int second = 0; second < 120; second++) {
            Files.createDirectories(logs.resolve("test-"
                    + start.plusSeconds(second).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + ".log"));
        }

        TestOutcome outcome = run(answer -> block(answer.entry(),
                anchoredScript(0, "PASS | 1", "PASS | 2")));

        assertThat(outcome.environmental())
                .as("进程起不来 = 引擎亲见的环境问题：%s", outcome).isTrue();
        assertThat(outcome.hardStopped()).isTrue();
        assertThat(outcome.detail())
                .contains("环境起不来").contains("改动未回滚").contains("等你处置");
        assertThat(outcome.directory()).as("产物留着的，所以这句话指得出一个真实存在的目录")
                .isNotEmpty();
        assertThat(root.resolve(outcome.directory())).as("产物整批留着，人要看得见它写成什么样")
                .isDirectory();
        assertThat(outcome.output()).as("原始错误照原样给人").contains("入口脚本起不来");
    }

    // ---------- 重新生成也要先编译核对（和每次生成同一条规则） ----------

    /**
     * 「重新生成」这条路<b>不再有跳过编译核对的例外</b>。
     *
     * <p>它和开发那一轮走的是同一个 {@link TestAgent#run} 里的同一段 {@code attempt}：
     * 生成 → 落盘闸门 → 溯源核对 → <b>跑一次脚本</b> → 看有没有结论。编不过就自己再生成一版，
     * 并把上一版的原始错误带进下一次生成（重掷骰子只会再错一遍）。
     *
     * <p>实测过的教训：旧口径下这条路连跑都不跑，人点完「重新生成」拿到的可能是一批
     * 根本跑不起来的代码，而界面上写着「已重新生成」——一轮白跑被当成了进展。
     */
    @Test
    @DisplayName("重新生成：第一版编不过就自己再生成一版，第二版编得过")
    void regeneratesWhenTheNewCodeCannotBeCompiled() {
        FakeLlm llm = llm(answer -> ++generation == 1
                ? block(answer.entry(), anchoredScript(1, "error: cannot find symbol"))
                : block(answer.entry(), anchoredScript(0, "PASS | 1", "PASS | 2")));

        TestAgent.Generated generated = agent(llm).generate(spec(), cases());

        assertThat(generation).as("引擎自己又生成了一版（这就是「生成后先编译」）").isEqualTo(2);
        assertThat(generated.problem()).as("第二版跑得出结论：编得过，没什么可说的").isNull();
        assertThat(generated.directory()).startsWith("tools/");
        assertThat(generated.trace().ok()).isTrue();
        assertThat(llm.user()).as("重试那次要把上一版的原始错误带上：重掷骰子只会再错一遍")
                .contains("上一版测试代码没跑起来").contains("cannot find symbol");
        try (var tools = Files.list(root.resolve("tools"))) {
            assertThat(tools).as("中间那几版收掉，只留最后一版（它是给人看的那一份）").hasSize(1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 预算用尽：<b>把最后一版和它的原始错误一起交给人</b>，标着「它的代码编不过」。
     *
     * <p>判据与开发那一轮<b>同一条</b>（{@link TestReport#ranWithoutConclusions}）：
     * 脚本跑了、退出码拿得到、却一条用例的结论都没报出来。措辞用的是引擎里那一档的标签——
     * 它说的是「引擎亲见的没有结论」，不替模型判「你编译错了」。
     *
     * <p>中间那几版要收掉：{@code tools/} 只增不减的话，下一轮翻产物时会分不清哪一版是真的。
     */
    @Test
    @DisplayName("重新生成：三版都编不过就把原始错误交给人，标着「它的代码编不过」")
    void reportsTheOriginalErrorWhenNoVersionCompiles() throws IOException {
        FakeLlm llm = llm(answer -> {
            generation++;
            return block(answer.entry(), anchoredScript(1, "error: cannot find symbol"));
        });

        TestAgent.Generated generated = agent(llm).generate(spec(), cases());

        assertThat(generation).as("跑不出结论会自己重试到上限（独立预算 3 版）").isEqualTo(3);
        assertThat(generated.problem()).isNotNull();
        assertThat(generated.problem().text())
                .as("引擎那一档的标签原样用，不另编一句话")
                .contains(TestOutcome.Failure.Kind.UNRUNNABLE.label())
                .contains("3 版")
                .contains("cannot find symbol");
        assertThat(generated.problem().output()).as("原始错误原样带走，一个字节都不掐")
                .contains("cannot find symbol");
        assertThat(root.resolve(generated.directory())).as("最后一版留着：人要看得见它写成什么样")
                .isDirectory();
        try (var tools = Files.list(root.resolve("tools"))) {
            assertThat(tools).as("中间那几版收掉，只留最后一版").hasSize(1);
        }
    }

    // ---------- 溯源连线：四条机器核对，不通过就拒绝跑 ----------
    /**
     * 清单上有、代码里没扫到锚点 = 漏实现。
     *
     * <p>判据是「脚本<b>一个字节都没被执行</b>」：这一批产物的结论压根不该存在。
     * 只断言「报了错」是不够的——那样测试跑没跑还是不知道。
     */
    @Test
    @DisplayName("清单里有、代码里没扫到：拒绝跑，把缺的那几条摆出来")
    void refusesWhenACaseHasNoAnchor() {
        // 只给第 1 条的锚点：第 2 条漏了
        String script = EntryScripts.anchored(0, cases().subList(0, 1), "PASS | 1", "PASS | 2");
        TestOutcome outcome = run(answer -> block(answer.entry(), script));

        assertThat(outcome.exit()).as("脚本没有被执行过").isEqualTo(TestScriptVerifier.NO_EXIT_CODE);
        assertThat(outcome.output()).as("拒绝跑 = 它自己那句结论一个字都不该出现")
                .doesNotContain("PASS | 1");
        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
            assertThat(failure.testCase()).isEqualTo("2");
            assertThat(failure.actual()).contains("没有扫到 CASE 2");
        });
        assertThat(outcome.detail()).contains("没有被执行").contains("重新生成");
        assertThat(outcome.cases()).extracting(TestOutcome.CaseResult::passed)
                .as("一条都没验过：没验不能算过了").containsExactly(false, false);
        assertThat(outcome.links()).as("连上的那条仍然要记着——界面按它画「已连线」").hasSize(1);
    }

    /** 代码里的 expect 和清单对不上 = 偷偷改期望（哪怕只改了一个数字）。 */
    @Test
    @DisplayName("expect 与清单不一致：拒绝跑，两边的话都摆出来")
    void refusesWhenTheExpectationWasChanged() {
        String script = EntryScripts.anchored(0, cases(), "PASS | 1", "PASS | 2")
                .replace(cases().get(0).expected(), "实际跑出来是 0.01");

        TestOutcome outcome = run(answer -> block(answer.entry(), script));

        assertThat(outcome.exit()).isEqualTo(TestScriptVerifier.NO_EXIT_CODE);
        assertThat(outcome.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.testCase()).isEqualTo("1");
            assertThat(failure.expected()).as("清单里写的是什么").isEqualTo(cases().get(0).expected());
            assertThat(failure.actual()).as("代码里写的是什么").contains("实际跑出来是 0.01");
        });
    }

    /**
     * 清单外的编号（乱写）与同一个编号写两遍（重复）各报一条。
     *
     * <p>造法是「它写出来的锚点和我给它的清单不是一份东西」：多一条 9、第 2 条写了两遍。
     * 这两条在真模型身上都见过——多出来的那条会让通过率的分母凭空变大，重复的那条
     * 会让「这条到底验没验」变成一个说不清的问题。
     */
    @Test
    @DisplayName("清单外乱写 + 重复实现：拒绝跑，两条差异逐条列出")
    void refusesExtraAndDuplicateAnchors() {
        List<PlanReview.TestCase> sloppy = List.of(
                cases().get(0), cases().get(1), cases().get(1),
                new PlanReview.TestCase(9, "它自己加的一条", "随手验一下",
                        PlanReview.TestCase.Level.OPTIONAL, "无", "无"));
        String script = EntryScripts.anchored(0, sloppy, "PASS | 1", "PASS | 2");

        TestOutcome outcome = run(answer -> block(answer.entry(), script));

        assertThat(outcome.exit()).isEqualTo(TestScriptVerifier.NO_EXIT_CODE);
        assertThat(outcome.failures()).hasSize(2).allSatisfy(failure ->
                assertThat(failure.kind()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE));
        assertThat(outcome.failures()).anySatisfy(failure -> {
            assertThat(failure.testCase()).isEqualTo("2");
            assertThat(failure.actual()).contains("出现了 2 次");
        });
        assertThat(outcome.failures()).anySatisfy(failure -> {
            assertThat(failure.testCase()).isEqualTo("9");
            assertThat(failure.actual()).contains("清单里没有编号 9");
        });
        assertThat(outcome.detail()).contains("溯源").contains("没有被执行");
    }

    /** 生成阶段（只生成、不跑）也要报溯源结论：不然「修一轮只修掉一个症状」要等到再跑一次才发现。 */
    @Test
    @DisplayName("重新生成也核一遍连线：这一批没接上就直接说清")
    void reportsTraceOnRegeneration() {
        TestAgent.Generated generated = agent(llm(answer -> block(answer.entry(),
                EntryScripts.anchored(0, cases().subList(0, 1), "PASS | 1"))))
                .generate(spec(), cases());

        assertThat(generated.trace().ok()).isFalse();
        assertThat(generated.trace().summarize()).contains("漏实现");
        assertThat(generated.sources()).isNotEmpty();
    }

    @Test
    @DisplayName("发给模型的系统提示词是测试协议，用户消息里带着用例清单")
    void sendsTheCaseListToTheModel() {
        FakeLlm llm = llm(answer -> block(answer.entry(), anchoredScript(0, "all passed")));

        agent(llm).run(spec(), cases());

        String system = llm.system();
        assertThat(system).contains(TestProtocol.FAIL_PREFIX).contains(TestProtocol.BLOCKED_PREFIX);
        assertThat(system).as("入口脚本的名字由引擎定，不能让它自己猜").contains(entryName());
        assertThat(system).as("锚点约定要写给它：不写它不可能知道引擎在扫什么")
                .contains("CASE <编号>").contains("expect:");
        assertThat(llm.user())
                .contains("用例清单")
                .contains("按编号查订单能查到")
                .contains("必须过")
                .as("清单后面要再点一句：编号与期望都得照抄")
                .contains("expect");
    }

    // ---------- 集成测试：两个入口、环境变量（十五.5） ----------

    /**
     * 勾了集成测试却只给了单元入口：在<b>生成阶段</b>就拒。
     *
     * <p>不拒的话，失败会表现成「入口脚本不存在：…/run-it.cmd」——看着像环境的问题，
     * 其实是模型没给。分错方向的代价不对称：用户会去查 docker，而那边什么都没有。
     */
    @Test
    @DisplayName("勾了集成测试却只给了单元入口：拒，并说清缺的是哪个文件")
    void refusesWhenTheIntegrationEntryIsMissing() {
        TestOutcome outcome = agent(llm(answer -> block(answer.entry(),
                anchoredScript(0, "PASS | 1", "PASS | 2"))))
                .run(spec(), cases(), new TestSettings(true), variables());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.worst()).isEqualTo(TestOutcome.Failure.Kind.TEST_CODE);
        assertThat(outcome.detail()).contains(EntryScripts.integrationName());
    }

    /**
     * 勾了集成测试、两个入口都给：<b>两个都要跑</b>（十五.5「单元 + 集成都在容器里跑」）。
     *
     * <p>只跑其中一个的话，另一条路上的用例会被对账逻辑判成「没验」——而清单是一份、
     * 分母是整个清单，于是每次勾集成都会收到一份假的失败清单。所以这里两个脚本给出
     * 互补的结果：单元那个报用例 1 没过、集成那个报用例 1、2 都过，合起来才盖住清单。
     */
    @Test
    @DisplayName("勾了集成测试：两个入口都跑，按同一条用例「没过优先」合并")
    void runsBothEntryScripts() {
        TestOutcome outcome = agent(llm(answer -> block(answer.entry(),
                anchoredScript(1, "FAIL | 1 | unit-only | unit-only | code is wrong"))
                // 集成那个入口不带锚点：两条用例都实现（并接上线）在单元那个文件里，
                // 编号在整批产物里写两遍会被判成「重复实现」——那是引擎的规矩
                + block(answer.integrationEntry(), entryScript(0, "PASS | 1", "PASS | 2"))))
                .run(spec(), cases(), new TestSettings(true), variables());

        // 单元那一条失败还在（它是事实），但清单上的两条都被报到了——
        // 所以不该再冒出一条「脚本只报了 N 条」
        assertThat(outcome.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.kind())
                        .isEqualTo(TestOutcome.Failure.Kind.ASSERTION));
        assertThat(outcome.cases()).hasSize(2);
        assertThat(outcome.cases()).extracting(TestOutcome.CaseResult::passed)
                .as("用例 1 单元那边没过、集成那边过了：按没过算").containsExactly(false, true);
        assertThat(outcome.output())
                .as("两个脚本的输出都在（界面上的「原始输出」要看得见全部事实）")
                .contains(entryName()).contains(EntryScripts.integrationName());
    }

    @Test
    @DisplayName("连接信息原样进模型上下文，并写明「只许读环境变量、不许硬编码」")
    void sendsTheEnvironmentToTheModel() {
        FakeLlm llm = llm(answer -> block(answer.entry(), anchoredScript(0, "PASS | 1", "PASS | 2")));

        agent(llm).run(spec(), cases(), new TestSettings(false), variables());

        String system = llm.system();
        assertThat(system).contains("DB_HOST").contains("db");
        assertThat(system).contains("不许硬编码");
        assertThat(system).contains(com.specflow.env.TestEnvironment.COMPOSE_PROJECT_VAR);
    }

    @Test
    @DisplayName("环境变量真的注入给了脚本（测试代码读得到，不用猜）")
    void injectsTheEnvironmentIntoTheScript() {
        // 脚本把变量打出来：它就是「注入到位了没有」的证据
        TestOutcome outcome = agent(llm(answer -> block(answer.entry(),
                EntryScripts.anchored(1, cases(), "DB=" + (WINDOWS ? "%DB_HOST%" : "$DB_HOST")))))
                .run(spec(), cases(), new TestSettings(false), variables());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.failures()).anySatisfy(failure ->
                assertThat(failure.actual()).contains("DB=db"));
    }

    // ---------- 辅助 ----------

    private static java.util.Map<String, String> variables() {
        return java.util.Map.of("DB_HOST", "db", "DB_PORT", "3306",
                com.specflow.env.TestEnvironment.COMPOSE_PROJECT_VAR, "sf-demo");
    }

    private static String entryName() {
        return EntryScripts.name();
    }

    private static String entryScript(int exit, String... outputs) {
        return EntryScripts.body(exit, outputs);
    }

    /** 带溯源锚点的入口脚本——凡是会走到「跑测试」那一步的桩都得用它（见 {@link EntryScripts}）。 */
    private static String anchoredScript(int exit, String... outputs) {
        return EntryScripts.anchored(exit, cases(), outputs);
    }

    private TestOutcome run(Function<Answer, String> answer) {
        return agent(llm(answer)).run(spec(), cases());
    }

    private TestAgent agent(LlmClient llm) {
        return new TestAgent(root, ProjectConfig.DEFAULT, TemplateRegistry.empty(), llm);
    }

    private static FakeLlm llm(Function<Answer, String> answer) {
        return new FakeLlm(answer);
    }

    private static Spec spec() {
        return TestSpecs.spec(List.of("Foo.java"));
    }

    /**
     * 这份清单的「期望什么」刻意写成 ASCII。
     *
     * <p>因为这一批测试把锚点写进了 <b>Windows 的 .cmd</b>（这些桩只造一个产物文件），
     * 而引擎落盘时行尾统一是 LF、cmd 又按本机代码页读脚本：正文里一出现中文，解码就错位，
     * 下一行的命令会被吃掉当成命令执行。真模型那边的规矩是「中文写进测试代码文件，
     * 入口脚本只用 ASCII」（见 {@code TestProtocol}），这里照同一个规矩来。
     * 中文锚点的解析由 {@link CaseTraceCheckTest} 直接覆盖，不依赖脚本能不能跑。
     */
    private static List<PlanReview.TestCase> cases() {
        return List.of(
                new PlanReview.TestCase(1, "按编号查订单能查到", "用已有编号查一次",
                        PlanReview.TestCase.Level.MUST, "id == 1", "订单能按编号查询"),
                new PlanReview.TestCase(2, "查不到的编号不抛异常", "用不存在的编号查一次",
                        PlanReview.TestCase.Level.SHOULD, "empty collection", "无"));
    }

    private static String block(String path, String content) {
        return "<<<<<<< SEARCH " + path + "\n=======\n" + content + "\n>>>>>>> REPLACE\n";
    }

    /** 假模型从系统提示词里读出来的东西：产物目录，以及两个入口脚本的完整路径。 */
    private record Answer(String dir, String entry, String integrationEntry) {
    }

    private static final class FakeLlm implements LlmClient {

        private final Function<Answer, String> answer;
        private String system = "";
        private String user = "";

        FakeLlm(Function<Answer, String> answer) {
            this.answer = answer;
        }

        @Override
        public String complete(List<ChatMessage> messages) {
            for (ChatMessage message : messages) {
                if (ChatMessage.SYSTEM.equals(message.role())) {
                    system = message.content();
                } else {
                    user = message.content();
                }
            }
            Matcher matcher = DIRECTORY.matcher(system);
            if (!matcher.find()) {
                throw new IllegalStateException("系统提示词里没有产物目录，模型没法照它写：\n" + system);
            }
            String dir = matcher.group();
            return answer.apply(new Answer(dir, dir + "/" + entryName(),
                    dir + "/" + EntryScripts.integrationName()));
        }

        String system() {
            return system;
        }

        String user() {
            return user;
        }
    }
}
