package com.specflow.agent;

import com.specflow.agent.tools.ReadFileTool;
import com.specflow.agent.tools.ReadListTool;
import com.specflow.agent.tools.Roots;
import com.specflow.agent.tools.SubmitTool;
import com.specflow.core.Json;
import com.specflow.core.Llm;
import com.specflow.core.Project;
import com.specflow.core.Progress;
import com.specflow.env.Capabilities;
import com.specflow.env.EnvConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>① 的「探测环境」Agent</b>：只读地读一遍项目，产出 <b>env.yaml 全文</b> + <b>一条编译命令</b>。
 *
 * <h2>它最容易犯的那个错（上一版在真项目上炸过）</h2>
 * {@code compileCommand} 是在<b>用户这台机器上</b>直接跑的（引擎起本机进程），
 * 而 {@code env.yaml} 里的 {@code init}/{@code reset} 是在<b>容器里</b>跑的。
 * 两处是不同的 shell。模型不知道这件事的时候会一律按 Linux 写：给 Windows 上的项目
 * 写出 {@code javac -d classes $(find src -name '*.java')}——那句话在 cmd 里一个字符都跑不动。
 * 所以本类把<b>本机的事实</b>灌进 user 消息（见 {@link #hostFacts()}），并在提示词里点名两处各在哪跑。
 *
 * <p>第二条：<b>产出必须过引擎的校验</b>。{@code env.yaml} 有它自己的字段与语义
 * （{@code image} 必填、{@code workdir} 必须是容器内绝对路径、依赖名只能是服务名那种字符…），
 * 校验不过就把<b>校验给的那几句人话</b>原样回喂，再要一版——而不是等用户点了保存才发现读不进去。
 */
public final class EnvAgent {

    private static final Logger log = LoggerFactory.getLogger(EnvAgent.class);

    /** 产出：env.yaml 全文 + 一条编译命令。 */
    public record Draft(String envYaml, String compileCommand, String notes) {
    }

    public record Result(String status, String statusLabel, String reason, Draft draft, Outcome loop) {

        public boolean ok() {
            return "DONE".equals(status) && draft != null;
        }
    }

    private final Project project;
    private final Llm llm;
    private final String note;
    private final Progress progress;
    private final Watcher watcher;
    private final int rounds;

    public EnvAgent(Project project, Llm llm, String note, Progress progress, Watcher watcher,
                    int rounds) {
        this.project = project;
        this.llm = llm;
        this.note = note == null ? "" : note.strip();
        this.progress = progress == null ? Progress.quiet() : progress;
        this.watcher = watcher == null ? Watcher.none() : watcher;
        this.rounds = rounds <= 0 ? 16 : rounds;
    }

    public Result run() {
        ToolRegistry tools = ToolRegistry.of(
                StuckCheck.wrap(new ReadListTool(new Roots(project.root()))),
                StuckCheck.wrap(new ReadFileTool(new Roots(project.root()))),
                new SubmitTool());
        Loop loop = new Loop("环境 Agent", llm, new LoopLimits(rounds, 200, 300, 1), tools, watcher, progress);
        String system = systemPrompt();
        String message = userMessage(null);

        for (int attempt = 1; attempt <= 3; attempt++) {
            Outcome outcome;
            try {
                outcome = loop.run(system, message);
            } catch (RuntimeException e) {
                log.warn("环境 Agent 的模型调用失败：{}", e.toString());
                return new Result("MODEL_FAILED", "模型没回来",
                        "连不上模型或者它连续报错：" + e.getMessage(), null, null);
            }
            if (!outcome.ok()) {
                return new Result(outcome.status(), outcome.statusLabel(),
                        outcome.reason() == null || outcome.reason().isBlank()
                                ? "它没交出一份能用的声明" : outcome.reason(), null, outcome);
            }
            Submitted submitted = parse(outcome.answer());
            if (submitted == null) {
                return new Result("UNPARSABLE", "它交的东西读不出来",
                        "交差那一轮给的不是一份 json（引擎不会替你猜要起什么环境）："
                                + shortText(outcome.answer()), null, outcome);
            }
            List<String> problems = validate(submitted);
            if (problems.isEmpty()) {
                // 模型的判断可能靠不住（提示词里那张表也不能保证它照做），所以再走一遍**确定性**兜底：
                // 编译命令要 mvn、镜像是纯 JDK 时，直接把 image 换成自带该工具的那个，并说明换了什么。
                Submitted fixed = withBuildImage(submitted);
                if (!fixed.envYaml().equals(submitted.envYaml())) {
                    progress.warn("镜像里没有编译要用的工具，已换成 " + imageOf(fixed.envYaml())
                            + "（原来是 " + imageOf(submitted.envYaml()) + "）");
                }
                log.info("环境 Agent 交差：env.yaml {} 行、编译命令「{}」、镜像 {}",
                        fixed.envYaml().split("\n").length, shortText(fixed.compileCommand()),
                        imageOf(fixed.envYaml()));
                return new Result("DONE", "已完成", "", fixed.toDraft(), outcome);
            }
            log.warn("环境 Agent 交的声明通不过校验（第 {} 版）：{}", attempt, String.join("；", problems));
            if (attempt == 3) {
                return new Result("REJECTED", "交出来的声明不合法",
                        "它交了三版都通不过校验：" + String.join("；", problems), null, outcome);
            }
            message = userMessage(problems);
        }
        return new Result("REJECTED", "交出来的声明不合法", "它没能交出一份合法的声明", null, null);
    }

    // ---------- 提示词 ----------

    private String systemPrompt() {
        return """
                你是这个项目里的**环境 Agent**。你只读、不改：整段运行里一个字节都不许写进项目。

                你的任务：读一遍这个项目，交出两样东西——

                1. **`envYamlLines`** —— `.specflow/env.yaml` 的全文，**一行一项**（数组里每个字符串就是一行，
                   YAML 的缩进写在字符串里，别指望引擎替你缩进）。它描述"这台机器上怎么把这个项目跑起来"：
                   - `image`   必填：测试容器的镜像（项目目录会挂进去，测试在它里面跑）
                   - `workdir` 可选：容器内挂载点，必须是**以 / 开头的绝对路径**（默认 `/work`）
                   - `dependencies` 可选：要连的中间件（`名字: {image, env, healthcheck}`），
                     名字只能用**小写字母、数字、点、下划线、短横线**（它同时是容器互连的服务名）
                   - `env`     可选：连接信息（`DB_HOST: db` 这种），测试代码照它读，不用猜
                   - `init`    可选：**起完环境跑一次**的准备命令
                   - `reset`   可选：**每次跑测试之前**把数据恢复到一个已知状态
                2. **`compileCommand`** —— **一行**，来自你在这个项目里真读到的构建方式
                   （Maven 就是 `mvn -q -DskipTests compile`，有 wrapper 用 `./mvnw …`；npm 就是 `npm run build`）。
                   **你不需要执行它。**

                ## 两条最容易搞错的事（一定要看 user 消息里「运行环境」那一节）

                1. `compileCommand` 在**用户这台机器上**直接跑 —— 必须符合那台机器的 shell。
                   Windows 上**不许**写 `$(...)`、`find`、`grep`、单引号包参数；
                   串好几步用 `&&`，不要用 `;`。
                2. `init` / `reset` 在**容器里**跑（Linux），**只能用 `image` 那个镜像里本来就有的命令**。
                   一个纯 JDK / 纯 Node 镜像里**没有** `mysql`、`psql`、`redis-cli`：
                   写进去会得到一句 `sh: 1: mysql: not found`，环境永远起不来。
                   要用这类客户端，就把那一条写成**三栏**交给依赖容器：

                   ```yaml
                   init:
                     - service: db                      # 去 db 那个容器里跑
                       command: "mysql -uroot -p123456 mydb"
                       stdin: "sql/init.sql"            # 要喂给它的文件**在你项目里**，引擎搬进容器
                   ```

                   **不要**在 init 里 `apt-get install`：沙箱是非 root + 能力全丢，装系统包必失败。

                ## 第三条，最难的一条：**image 必须自带构建工具**

                引擎起完环境会**在容器里**再跑一遍 `compileCommand`，跑不通 ① 就冻结不了。
                而"跑不通"最常见的原因只有一个：**镜像里没有那个工具**。

                所以：`compileCommand` 用什么工具，`image` 就必须自带它。照这张表选：

                | compileCommand 里认出的工具 | image 该用 |
                |---|---|
                | `mvn` / `mvnw` | `maven:3.9-eclipse-temurin-17`（要 21 就用 `maven:3.9-eclipse-temurin-21`） |
                | `gradle` / `gradlew` | `gradle:8-jdk17` |
                | `javac`（没有 maven/gradle 的纯 Java 项目） | `eclipse-temurin:17` —— **只有这种项目才用它** |
                | `npm` / `node` / `npx` | `node:20` |
                | `pnpm` | `node:20`，并在 init 里 `corepack enable` |
                | `go` | `golang:1.22` |
                | `cargo` / `rustc` | `rust:1` |
                | `python` / `pytest` / `pip` | `python:3.12` |
                | `dotnet` | `mcr.microsoft.com/dotnet/sdk:8.0` |

                **`eclipse-temurin:17` / `openjdk:17` 里没有 `mvn`。** 这是本项目最常见的一次失败：
                项目是 Maven 的、镜像却是纯 JDK —— 宿主机编译能过、容器里 `sh: mvn: not found`，
                ① 永远冻结不了。**看到 `pom.xml` 就该选 `maven:` 那个镜像。**

                项目里已经有 env.yaml 时，`image` 不要因为"上一版写着"就照抄：
                先在 `compileCommand` 里认出工具，再回头确认这一版镜像自带它；不带就换掉，
                并在 `notes` 里写一句"原镜像没有 mvn，换成了 maven:3.9-eclipse-temurin-17"。

                ## 规矩

                1. **只写你读到的**：镜像版本、依赖、构建方式都要能追到你读过的那份文件
                   （`pom.xml` / `build.gradle` / `package.json` / `Dockerfile` / `docker-compose*.yml` /
                   `application*.yml` / `.env*` / `README`）。拿不准就在 `notes` 里说拿不准。
                2. 别自己发明字段：env.yaml 只认上面那六栏。
                3. 先用 `read_list` 摸结构，再 `read_file` 读构建文件与配置，最后交差。

                怎么调工具、怎么交差：见下面那段协议。
                """ + "\n\n" + Protocol.instructions() + """
                ## 你的回复只能是下面两种之一（**不许有任何别的内容**）

                1. 一个围栏标记为 `specflow-tool` 的代码块 —— 调一次工具：
                   `specflow-tool
                   {"name":"read_list","arguments":{"path":"."}}
                   `
                2. 一个围栏标记为 `json` 的代码块 —— 交差（形状见上）。

                **不要写解释、不要写「好的」「我先看看」、不要在代码块前后加任何一句话。**
                引擎只读那个代码块，别的内容一律当作这一轮什么都没说。""";
    }

    private String userMessage(List<String> problems) {
        StringBuilder out = new StringBuilder();
        if (!note.isBlank()) {
            out.append("## 用户给的说明（有就照它，没有就别自己发明）\n\n").append(note).append('\n');
        }
        out.append("\n## 运行环境（引擎给的；写命令时必须照它）\n\n").append(hostFacts()).append('\n');
        String top = String.join("  ", project.topLevel());
        if (!top.isBlank()) {
            out.append("\n## 项目根下有什么（引擎列的）\n\n").append(top).append('\n');
        }
        out.append("\n## ").append(EnvConfigLoader.FILE).append(" 现在是什么状态\n\n")
                .append(existing()).append('\n');
        if (problems != null && !problems.isEmpty()) {
            out.append("""

                    ## 你上一版交的声明通不过引擎校验

                    """);
            for (String one : problems) {
                out.append("- ").append(one).append('\n');
            }
            out.append("\n照这几条改，再交一版。**其他部分不要动**。\n");
        }
        out.append("""

                现在开始。交差的 json 形状（三栏都要有）：

                ```json
                {
                  "envYamlLines": ["image: maven:3.9-eclipse-temurin-17", "workdir: /work",
                                   "init:", "  - \\"mvn -q -DskipTests compile\\""],
                  "compileCommand": "mvn -q -DskipTests compile",
                  "notes": "读了 pom.xml（Java 17、Maven）与 README；镜像用 maven:3.9-eclipse-temurin-17，因为编译要 mvn，而纯 JDK 镜像里没有它"
                }
                ```
                """);
        return out.toString();
    }

    /**
     * 本机是什么系统——写 {@code compileCommand} 时必须知道的一件事。
     *
     * <p>这段是上一版缺失、真项目上炸过的那一块（Linux 写法在 cmd 里跑不动）。判据只有这一处。
     *
     * <h2>为什么还要说"容器里也要跑"（实测撞出来的第二次）</h2>
     * 这条命令**不止在一处跑**：④ 每次交差在<b>宿主机</b>上跑它，而 ① 起完环境还要在
     * <b>Linux 容器</b>里跑同一行（不跑就无法保证 ⑤ 不会卡在这里）。所以它得同时是
     * cmd.exe 和 sh 都认的写法。上一版这段的示例写的是 `javac -d out\main …`——
     * 宿主机过了、容器里挂（`\` 在 sh 里是转义），于是 ① 冻结被闸拦下，
     * 而错误信息看起来像"环境有问题"。**路径一律用正斜杠**：两边都认。
     */
    private static String hostFacts() {
        String os = System.getProperty("os.name", "未知");
        String version = System.getProperty("os.version", "");
        String arch = System.getProperty("os.arch", "");
        boolean windows = os.toLowerCase(Locale.ROOT).contains("win");
        StringBuilder out = new StringBuilder();
        out.append("- 本机系统：").append(os)
                .append(version.isBlank() ? "" : " " + version)
                .append(arch.isBlank() ? "" : "（" + arch + "）").append('\n');
        out.append("- `compileCommand` 在**这台机器上直接跑**，用的是 ")
                .append(windows ? "Windows 的 cmd.exe" : "本机的 sh").append("；\n");
        out.append("""
                - **同一行命令还要在 Linux 容器里再跑一次**（起完环境验证容器真能构建）。
                  所以它必须**两处都认**：路径一律写**正斜杠**（`out/main` 而不是 `out\\main`
                  —— cmd.exe 也认正斜杠，而 sh 里 `\\` 是转义字符，会变成 `outmain`）。
                """);
        out.append("- `init` / `reset` 在**容器里跑**（Linux），只能用 `image` 里本来就有的命令。\n");
        if (windows) {
            out.append("""
                    - 这台机器是 Windows，`compileCommand` 里**不许**出现：
                      `$(...)`、`find`、`grep`、`sed`、单引号包参数、`;` 串命令。
                      照 cmd 写、但路径用正斜杠：`javac -d out/main src/main/java/com/demo/*.java`、
                      `mvn -q -DskipTests compile`、`gradlew.bat build`。串步骤用 `&&`。
                    """);
        }
        return out.toString();
    }

    private String existing() {
        boolean has = java.nio.file.Files.isRegularFile(project.layout().envYaml());
        return has
                ? "项目里**已经有**这一份了。先 `read_file` 读一遍，把里面仍然对的东西保留下来"
                + "（你的草稿会把整份换掉，别把用户已经写对的部分弄丢）。"
                : "项目里还没有这一份（你的草稿会被新建出来）。";
    }

    // ---------- 解析与校验 ----------

    /** 交差那一轮的三栏。 */
    /** 交差那一轮的三栏（包内可见：镜像兜底那一组测试要构造它）。 */
    record Submitted(String envYaml, String compileCommand, String notes) {

        Draft toDraft() {
            return new Draft(envYaml, compileCommand, notes);
        }
    }

    @SuppressWarnings("unchecked")
    private static Submitted parse(String raw) {
        String json = Probe.firstJsonBlock(raw);
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> map = Json.map(json);
            List<String> lines = new ArrayList<>();
            Object rawLines = map.get("envYamlLines");
            if (rawLines instanceof List<?> items) {
                for (Object one : items) {
                    lines.add(one == null ? "" : String.valueOf(one));
                }
            }
            String yaml = lines.isEmpty()
                    ? String.valueOf(map.getOrDefault("envYaml", "")).strip()
                    : String.join("\n", lines) + "\n";
            String compile = String.valueOf(map.getOrDefault("compileCommand", "")).strip();
            String notes = String.valueOf(map.getOrDefault("notes", "")).strip();
            if (yaml.isBlank()) {
                return null;
            }
            return new Submitted(yaml, compile, notes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 交出来的能不能用；不能用时逐条给人话（拿引擎自己的校验去判，别自己写一套）。 */
    private static List<String> validate(Submitted submitted) {
        List<String> problems = new ArrayList<>();
        if (submitted.compileCommand().isBlank()) {
            problems.add("compileCommand 是空的：没有它，① 过不了闸，开发提交之后也只会拿到"
                    + "「编译没有执行」");
        } else if (submitted.compileCommand().contains("\n")) {
            problems.add("compileCommand 必须是一行：多行在这里读不了");
        }
        try {
            EnvConfigLoader.parse(submitted.envYaml());
        } catch (RuntimeException e) {
            problems.add(e.getMessage() == null ? "env.yaml 读不出来" : e.getMessage());
        }
        return problems;
    }

    // ---------- 确定性兜底：镜像里必须有编译要用的那个工具 ----------

    /**
     * 工具 → 该用的镜像。**认不出就不动**（宁可不修，也不要瞎换）。
     *
     * <p>这张表的用法只有一处：模型交的 image 跑不了它的 compileCommand 时，替它换成对的。
     * 为什么不只靠提示词：提示词里那张表是"建议"，而这件事是**硬事实**
     * （`eclipse-temurin:17` 里就是没有 `mvn`）；模型偶尔照抄上一版声明，用户就得自己回来改。
     */
    private static final Map<String, String> TOOL_IMAGES = Map.ofEntries(
            Map.entry("mvn", "maven:3.9-eclipse-temurin-17"),
            Map.entry("mvnw", "maven:3.9-eclipse-temurin-17"),
            Map.entry("gradle", "gradle:8-jdk17"),
            Map.entry("gradlew", "gradle:8-jdk17"),
            Map.entry("npm", "node:20"),
            Map.entry("node", "node:20"),
            Map.entry("npx", "node:20"),
            Map.entry("pnpm", "node:20"),
            Map.entry("yarn", "node:20"),
            Map.entry("go", "golang:1.22"),
            Map.entry("cargo", "rust:1"),
            Map.entry("rustc", "rust:1"),
            Map.entry("python", "python:3.12"),
            Map.entry("python3", "python:3.12"),
            Map.entry("pip", "python:3.12"),
            Map.entry("pytest", "python:3.12"),
            Map.entry("dotnet", "mcr.microsoft.com/dotnet/sdk:8.0"));

    /** env.yaml 里的 `image:` 那一行（读不出来就是空串）。 */
    static String imageOf(String yaml) {
        for (String row : (yaml == null ? "" : yaml).split("\n")) {
            String line = row.strip();
            if (line.startsWith("image:")) {
                return line.substring("image:".length())
                        .replace("\"", "").replace("'", "").strip();
            }
        }
        return "";
    }

    /** 这个镜像自带 {@code tool} 吗（按仓库名认，认不出就当作"自带"，不误改）。 */
    static boolean imageHas(String image, String tool) {
        String repo = (image == null ? "" : image).toLowerCase(java.util.Locale.ROOT);
        String name = (tool == null ? "" : tool).toLowerCase(java.util.Locale.ROOT);
        if (repo.isBlank() || name.isBlank()) {
            return true;
        }
        return switch (name) {
            // 纯 JDK 镜像（temurin / openjdk / jdk）里有 javac，但没有 mvn/gradle
            case "javac" -> repo.contains("jdk") || repo.contains("temurin") || repo.contains("openjdk")
                    || repo.contains("java");
            case "mvn", "mvnw" -> repo.startsWith("maven") || repo.contains("/maven");
            case "gradle", "gradlew" -> repo.startsWith("gradle") || repo.contains("/gradle");
            case "npm", "node", "npx", "yarn" -> repo.startsWith("node") || repo.contains("/node");
            // pnpm 靠 corepack：node:20 就够
            case "pnpm" -> repo.startsWith("node") || repo.contains("/node");
            case "go" -> repo.startsWith("golang") || repo.contains("/golang");
            case "cargo", "rustc" -> repo.startsWith("rust") || repo.contains("/rust");
            case "python", "python3", "pip", "pytest" -> repo.startsWith("python") || repo.contains("/python");
            case "dotnet" -> repo.contains("dotnet");
            default -> true;
        };
    }

    /**
     * 编译命令要什么工具、镜像就换成自带它的那个。
     *
     * @return 改过的那一份（没得改就原样返回）
     */
    static Submitted withBuildImage(Submitted submitted) {
        String tool = Capabilities.toolOf(submitted.compileCommand());
        String image = imageOf(submitted.envYaml());
        if (tool.isBlank() || imageHas(image, tool)) {
            return submitted;
        }
        String wanted = TOOL_IMAGES.get(tool.toLowerCase(java.util.Locale.ROOT));
        if (wanted == null) {
            return submitted;
        }
        List<String> lines = new ArrayList<>();
        boolean replaced = false;
        for (String row : submitted.envYaml().split("\n")) {
            if (!replaced && row.strip().startsWith("image:")) {
                String indent = row.substring(0, row.indexOf("image:"));
                lines.add(indent + "image: " + wanted);
                replaced = true;
            } else {
                lines.add(row);
            }
        }
        String notes = submitted.notes().isBlank()
                ? "原镜像 " + image + " 里没有 " + tool + "，已换成 " + wanted
                : submitted.notes() + "；原镜像 " + image + " 里没有 " + tool + "，已换成 " + wanted;
        return new Submitted(String.join("\n", lines), submitted.compileCommand(), notes);
    }

    private static String shortText(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    static Map<String, Object> nothing() {
        return new LinkedHashMap<>();
    }
}
