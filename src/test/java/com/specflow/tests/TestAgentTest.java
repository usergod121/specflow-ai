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

    @Test
    @DisplayName("一条断言没过：失败清单带四要素，产物留在磁盘上给人看")
    void reportsAssertionFailure() throws IOException {
        // 这里刻意用 ASCII：Windows 的 .cmd 是按本机代码页读的，脚本正文里的中文可能被拆坏
        // （连 `^|` 的转义都会被吃掉，命令就废了）。四要素的解析本身在 TestReportTest 里用中文验
        TestOutcome outcome = run(answer -> block(answer.entry(), entryScript(1,
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
                entryScript(0, "PASS | 1", "PASS | 2")));

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.exit()).isZero();
        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.verification().passed()).isTrue();
        assertThat(outcome.cases()).hasSize(2)
                .allSatisfy(caseResult -> assertThat(caseResult.passed()).isTrue());
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
                entryScript(0, "FAIL | 1 | expected-a | got-b | code is wrong")));

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
        TestOutcome outcome = run(answer -> block(answer.entry(), entryScript(0, "all passed")));

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
                entryScript(0, "PASS | 1", "PASS | 2")));

        assertThat(outcome.detail()).doesNotContain("入口脚本");
        assertThat(outcome.passed()).isTrue();
    }

    /** 同一个错的另一半：目录拼对了，入口脚本自己的名字写成大写（Windows 上是同一个文件）。 */
    @Test
    @DisplayName("模型把入口脚本名写成大写：照样认，不谎称「没给入口脚本」")
    void acceptsUpperCaseEntryName() {
        TestOutcome outcome = run(answer -> block(
                answer.dir() + "/" + entryName().toUpperCase(Locale.ROOT),
                entryScript(0, "PASS | 1", "PASS | 2")));

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

    @Test
    @DisplayName("脚本说跑不起来（BLOCKED）：算环境问题，产物整批清掉（这次运行要回滚）")
    void clearsArtifactsOnEnvironmentFailure() throws IOException {
        TestOutcome outcome = run(answer ->
                block(answer.entry(), entryScript(2, "BLOCKED | javac not found")));

        assertThat(outcome.environmental()).isTrue();
        assertThat(outcome.detail()).contains("环境问题");
        assertThat(outcome.directory()).isEmpty();
        try (var tools = Files.list(root.resolve("tools"))) {
            assertThat(tools).isEmpty();
        }
    }

    @Test
    @DisplayName("发给模型的系统提示词是测试协议，用户消息里带着用例清单")
    void sendsTheCaseListToTheModel() {
        FakeLlm llm = llm(answer -> block(answer.entry(), entryScript(0, "all passed")));

        agent(llm).run(spec(), cases());

        String system = llm.system();
        assertThat(system).contains(TestProtocol.FAIL_PREFIX).contains(TestProtocol.BLOCKED_PREFIX);
        assertThat(system).as("入口脚本的名字由引擎定，不能让它自己猜").contains(entryName());
        assertThat(llm.user())
                .contains("用例清单")
                .contains("按编号查订单能查到")
                .contains("必须过");
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
                entryScript(0, "PASS | 1", "PASS | 2"))))
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
                entryScript(1, "FAIL | 1 | unit-only | unit-only | code is wrong"))
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
        FakeLlm llm = llm(answer -> block(answer.entry(), entryScript(0, "PASS | 1", "PASS | 2")));

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
                WINDOWS
                        ? "@echo off\r\necho DB=%DB_HOST%\r\nexit /b 1\r\n"
                        : "#!/bin/sh\necho DB=$DB_HOST\nexit 1\n")))
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

    private static List<PlanReview.TestCase> cases() {
        return List.of(
                new PlanReview.TestCase(1, "按编号查订单能查到", "用已有编号查一次",
                        PlanReview.TestCase.Level.MUST, "返回的那条 id 等于传入的编号",
                        "订单能按编号查询"),
                new PlanReview.TestCase(2, "查不到的编号不抛异常", "用不存在的编号查一次",
                        PlanReview.TestCase.Level.SHOULD, "返回空集合而不是抛异常", "无"));
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
