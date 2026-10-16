package com.specflow.env;

import com.specflow.core.Atomic;
import com.specflow.core.Cmd;
import com.specflow.core.Layout;
import com.specflow.core.Progress;
import com.specflow.core.Project;
import com.specflow.core.SpecException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentTest {

    /** 失败原文：断言「全文进了异常」，不只是第一行。 */
    private static final String RAW = """
            ERROR 1045 (28000): Access denied for user 'root'@'localhost'
            ERROR 1064 (42000): You have an error in your SQL syntax near 'CREATE TABEL'
            （最后一行也要留着）""";

    private static final String IMAGE = "eclipse-temurin:17-jdk";

    @TempDir
    Path root;

    /** 逐条记账的假执行器：不起任何进程。 */
    private static class Fake implements Environment.Runner {

        final List<List<String>> calls = new ArrayList<>();
        final List<String> stdins = new ArrayList<>();
        int failAtExec = -1;
        int execs;

        @Override
        public Cmd.Result run(List<String> argv, Path workdir, String stdin, long timeoutSeconds,
                              Consumer<String> onLine) {
            calls.add(List.copyOf(argv));
            stdins.add(stdin == null ? "" : stdin);
            if (onLine != null) {
                onLine.accept("（假执行器）");
            }
            if (argv.contains("exec")) {
                execs++;
                if (execs == failAtExec) {
                    return new Cmd.Result(1, RAW, false, 12);
                }
                return new Cmd.Result(0, "跑完了", false, 12);
            }
            if (argv.contains("up")) {
                return new Cmd.Result(0, "Container sf-x-app-1 Started", false, 900);
            }
            if (argv.contains("ps")) {
                return new Cmd.Result(0, "sf-x-app-1\nsf-x-db-1", false, 8);
            }
            if (argv.contains("volume")) {
                return new Cmd.Result(0, "sf-x_data", false, 6);
            }
            if (argv.contains("down")) {
                return new Cmd.Result(0, "Removed", false, 30);
            }
            return new Cmd.Result(-1, "起不了这条命令：找不到这个程序", false, 1);
        }

        /** 所有 {@code docker compose exec} 那几条 argv。 */
        List<List<String>> execCalls() {
            return calls.stream().filter(argv -> argv.contains("exec")).toList();
        }

        /** 每一条 exec 收到的脚本（按顺序）。 */
        List<String> execStdins() {
            List<String> fed = new ArrayList<>();
            for (int at = 0; at < calls.size(); at++) {
                if (calls.get(at).contains("exec")) {
                    fed.add(stdins.get(at));
                }
            }
            return fed;
        }

        boolean ranDownVolumes() {
            return calls.stream().anyMatch(argv -> argv.contains("down") && argv.contains("-v"));
        }
    }

    /** 问到第几次就当作「用户按了停下」，别的都转给 {@link Progress#quiet()}。 */
    private static final class Cancelling implements Progress {

        private final Progress real = Progress.quiet();
        private final int after;
        private int asks;

        Cancelling(int after) {
            this.after = after;
        }

        @Override
        public void step(String name, String command, long timeoutSeconds) {
            real.step(name, command, timeoutSeconds);
        }

        @Override
        public void stepDone(String name) {
            real.stepDone(name);
        }

        @Override
        public void line(String text) {
            real.line(text);
        }

        @Override
        public void warn(String text) {
            real.warn(text);
        }

        @Override
        public void fail(String text, String raw) {
            real.fail(text, raw);
        }

        @Override
        public void done(String text, Map<String, Object> extra) {
            real.done(text, extra);
        }

        @Override
        public boolean cancelled() {
            return asks++ >= after;
        }

        @Override
        public long elapsedSeconds() {
            return real.elapsedSeconds();
        }

        @Override
        public List<Map<String, Object>> steps() {
            return real.steps();
        }
    }

    private Layout layout() {
        return new Layout(root);
    }

    private Project project(String envYaml) {
        Atomic.write(layout().envYaml(), envYaml);
        return Project.open(root);
    }

    /** 一份只差 body 的声明（image 总是对的）。 */
    private static String declared(String body) {
        return "image: \"" + IMAGE + "\"\n" + body;
    }

    @Test
    void init逐条执行_而且原文落在当前流程的目录下() throws IOException {
        Fake fake = new Fake();
        // 当前流程 id 只从 flow/current 那一行读（Environment 不引 flow 包）
        Atomic.write(layout().current(), "f-1\n");

        Environment.Registration done = new Environment(project(declared("""
                init:
                  - "echo 一"
                  - "echo 二"
                  - "echo 三"
                """)), fake).up(Progress.quiet());

        assertThat(fake.execs).isEqualTo(3);
        assertThat(fake.execStdins()).containsExactly("echo 一", "echo 二", "echo 三");
        assertThat(done.containers()).containsExactly("sf-x-app-1", "sf-x-db-1");
        assertThat(done.volumes()).isEqualTo("sf-x_data");

        Path compose = Path.of(done.composeFile());
        assertThat(compose).exists();
        assertThat(compose.getParent().getParent()).isEqualTo(layout().envRuns("f-1"));
        assertThat(Files.readString(compose)).contains("services:").contains(IMAGE);
        assertThat(Files.readString(compose.getParent().resolve(Environment.LOG_FILE)))
                .contains("docker compose")
                .contains("Container sf-x-app-1 Started")
                .contains("成功");
    }

    @Test
    void 第二条init失败时第三条不跑_而且发了down_v() {
        Fake fake = new Fake();
        fake.failAtExec = 2;

        Environment environment = new Environment(project(declared("""
                init:
                  - "echo 一"
                  - "echo 二"
                  - "echo 三"
                """)), fake);

        assertThatThrownBy(() -> environment.up(Progress.quiet()))
                .isInstanceOf(SpecException.class)
                .as("要说清是哪一种失败，而且原始输出全文进异常，不许只留第一行")
                .hasMessageContaining("退出码 1")
                .hasMessageContaining("ERROR 1045")
                .hasMessageContaining("CREATE TABEL")
                .hasMessageContaining("（最后一行也要留着）");

        assertThat(fake.execs).isEqualTo(2);
        assertThat(fake.execStdins()).doesNotContain("echo 三");
        assertThat(fake.ranDownVolumes()).as("失败要把环境收干净").isTrue();
    }

    @Test
    void service那一档的argv是去那个容器里跑() {
        Fake fake = new Fake();

        new Environment(project(declared("""
                dependencies:
                  db:
                    image: "mysql:8.0"
                init:
                  - service: "db"
                    command: "mysql -e 'select 1'"
                """)), fake).up(Progress.quiet());

        assertThat(fake.execCalls()).hasSize(1);
        assertThat(fake.execCalls().get(0)).startsWith("docker", "compose");
        assertThat(fake.execCalls().get(0)).endsWith("exec", "-T", "db", "sh", "-s");
        assertThat(fake.execStdins()).containsExactly("mysql -e 'select 1'");
    }

    @Test
    void stdin那一档把项目里的文件内容喂进去() throws IOException {
        Fake fake = new Fake();
        Files.writeString(root.resolve("schema.sql"),
                "CREATE TABLE t (id int);\nSPECFLOW_STDIN_EOF\n");

        new Environment(project(declared("""
                init:
                  - command: "psql -U app"
                    stdin: "schema.sql"
                """)), fake).up(Progress.quiet());

        String fed = fake.execStdins().get(0);
        assertThat(fed).as("依赖容器没挂项目目录，所以文件内容要由引擎搬进去")
                .contains("CREATE TABLE t (id int);");
        assertThat(fed).contains("psql -U app < /tmp/specflow-stdin");
        // 内容里正好有同名的结束标记：分隔符要换一个，不然脚本会在那儿被截断
        assertThat(fed).contains("<<'SPECFLOW_STDIN_EOF_'");
    }

    @Test
    void docker不在时要说一句人能照着做的话() {
        Environment.Runner noDocker =
                (argv, workdir, stdin, timeout, onLine) ->
                        new Cmd.Result(-1, "起不了这条命令：Cannot run program \"docker\"", false, 3);

        assertThatThrownBy(() -> new Environment(project(declared("init: []")), noDocker)
                .up(Progress.quiet()))
                .isInstanceOf(SpecException.class)
                .hasMessageContaining("没找到 docker 命令")
                .hasMessageContaining("PATH");
    }

    @Test
    void 叫停之后不再往下跑_而且把半成品收掉() {
        Fake fake = new Fake();

        assertThatThrownBy(() -> new Environment(project(declared("""
                init:
                  - "echo 一"
                  - "echo 二"
                """)), fake).up(new Cancelling(1)))
                .isInstanceOf(SpecException.class)
                .hasMessageContaining("已叫停");

        assertThat(fake.execs).as("叫停之后那条 init 不该跑").isEqualTo(1);
        assertThat(fake.ranDownVolumes()).isTrue();
    }

    @Test
    void 没声明就说清楚往哪儿写() {
        Layout layout = layout();

        assertThat(new Environment(Project.open(root), new Fake()).status())
                .contains("没声明")
                .contains("env.yaml");

        assertThatThrownBy(() -> new Environment(Project.open(root), new Fake()).up(Progress.quiet()))
                .isInstanceOf(SpecException.class)
                .hasMessageContaining(layout.show(layout.envYaml()));
    }

    @Test
    void reset没起过环境就先说清楚() {
        Environment environment = new Environment(project(declared("reset: \"echo 一\"")), new Fake());

        assertThatThrownBy(() -> environment.reset(Progress.quiet()))
                .isInstanceOf(SpecException.class)
                .hasMessageContaining("环境还没起过");
    }

    @Test
    void reset在已经渲染过的那一份上跑() {
        Fake fake = new Fake();
        Environment environment = new Environment(project(declared("""
                reset:
                  - "mvn -q flyway:clean"
                """)), fake);

        environment.up(Progress.quiet());
        Environment.Registration done = environment.reset(Progress.quiet());

        assertThat(fake.execStdins()).containsExactly("mvn -q flyway:clean");
        assertThat(done.state()).isEqualTo("READY");
        assertThat(done.summarize()).contains("已重置数据");
    }
}
