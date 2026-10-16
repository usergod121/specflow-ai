package com.specflow.env;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.specflow.core.Json;
import com.specflow.core.Layout;
import com.specflow.core.SpecException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 读 {@code .specflow/env.yaml}，把结构问题<b>带上行号、一次报全</b>。
 *
 * <p>三条口径：
 * <ul>
 *   <li>文件不在 = 没声明（{@link Optional#empty()}），不是错误；
 *       只有注释/只有空行的文件同样算「还没想好」，不然刚建完文件就点不开这个项目；</li>
 *   <li>剩下的每一处不对都要报，而且一次报全——遇到第一个就退出，用户得来回跑五趟；</li>
 *   <li>只做<b>结构</b>校验（字段认不认识、类型对不对、必填项在不在）。
 *       「这条命令危不危险」是执行那一刻的事，放在这里会让「读一份配置」变成「替你决定能不能跑」。</li>
 * </ul>
 *
 * <p>它和 {@link Environment} 共用 {@link #parse} 一个口子：界面里 AI 交出来的草稿必须走
 * 同一套规则，否则会出现「AI 说没问题、写下去起不来」。
 */
public final class EnvConfigLoader {

    public static final String FILE = "env.yaml";

    private static final ObjectMapper YAML = new YAMLMapper();

    /** 认识的那几个字段。多一个都要报：写错一个字母就静默失效是最难查的一种错。 */
    private static final Set<String> TOP =
            Set.of("docker", "image", "workdir", "dependencies", "env", "init", "reset");
    private static final Set<String> DEPENDENCY = Set.of("image", "env", "healthcheck");
    private static final Set<String> STEP = Set.of("command", "service", "stdin");
    private static final Set<String> DOCKER = Set.of("command");

    /** 依赖名会变成 compose 的服务名（也是容器互连的主机名），只能用它认的那一套字符。 */
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private EnvConfigLoader() {
    }

    /** 文件不在 → 空；在但写错了 → 抛（正文是一句一句的问题）。 */
    public static Optional<EnvConfig> load(Layout layout) {
        Path file = layout.envYaml();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SpecException("读不了 " + layout.show(file) + "：" + e.getMessage());
        }
        return Json.hasNoContent(source) ? Optional.empty() : Optional.of(parse(source));
    }

    /** 校验一段 env.yaml 原文（不落盘、不读磁盘）：界面里 AI 草稿用这个口子。 */
    public static EnvConfig parse(String yaml) {
        String source = yaml == null ? "" : yaml;
        Lines lines = Lines.of(source);
        JsonNode root;
        try {
            root = YAML.readTree(source);
        } catch (IOException e) {
            throw new SpecException(FILE + " 不是合法的 YAML：" + e.getMessage());
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw new SpecException(FILE + " 是空的：至少要写 image（项目跑在什么镜像里）");
        }
        if (!root.isObject()) {
            throw new SpecException(FILE + " 的最外层必须是一组「字段: 值」");
        }

        List<String> problems = new ArrayList<>();
        known(root, TOP, lines, "", problems, "可以用的是 image / workdir / dependencies / env / init / reset");
        docker(root.get("docker"), lines, problems);
        String image = text(root.get("image"), lines, "image", problems);
        String workdir = workdir(root.get("workdir"), lines, problems);
        Map<String, EnvConfig.Dependency> dependencies =
                dependencies(root.get("dependencies"), lines, problems);
        Map<String, String> env = scalars(root.get("env"), lines, "env", problems);
        List<EnvConfig.Step> init = steps(root.get("init"), "init", lines, dependencies, problems);
        List<EnvConfig.Step> reset = steps(root.get("reset"), "reset", lines, dependencies, problems);
        if (image.isBlank()) {
            problems.add(at(lines, "image") + "没有 image：它是项目跑在里面的那个镜像"
                    + "（项目目录挂进去、常驻着跑测试），没有它就没有容器");
        }
        if (!problems.isEmpty()) {
            throw new SpecException(FILE + " 有 " + problems.size() + " 处问题，改完再试：\n  "
                    + String.join("\n  ", problems));
        }
        return new EnvConfig(image, workdir, dependencies, env, init, reset);
    }

    /** 一份带注释的示例（界面「新建」用）。整份都是注释，所以它等于「还没声明」。 */
    public static String template() {
        return """
                # 这个项目跑在容器里 —— 声明清楚「长什么样」，起环境时引擎照着渲染 compose。
                #
                # image: 必填。项目目录会挂进这个容器，之后所有命令都在里面跑
                # image: "eclipse-temurin:17-jdk"
                #
                # workdir: 项目目录挂进容器的位置（默认 /work）
                # workdir: "/work"
                #
                # dependencies: 测试要连的中间件。名字就是容器之间互连用的服务名
                # dependencies:
                #   db:
                #     image: "mysql:8.0"
                #     env:
                #       MYSQL_ROOT_PASSWORD: "root"
                #       MYSQL_DATABASE: "app"
                #     healthcheck: "mysqladmin ping -h 127.0.0.1 -uroot -proot"
                #
                # env: 连接信息，原样注入 app 容器（测试代码读它，别写死连接串）
                # env:
                #   DB_HOST: "db"
                #   DB_PORT: "3306"
                #
                # init: 起完环境跑一次（逐条执行，失败即停）。可以写一行命令，
                #       也可以写 command / service / stdin 三栏 —— service 让命令去依赖容器里跑
                #       （mysql 客户端只在 mysql 镜像里），stdin 是项目里要喂给它的那个文件
                # init:
                #   - "mvn -q -DskipTests compile"
                #   - service: "db"
                #     command: "mysql -h127.0.0.1 -uroot -proot app"
                #     stdin: "sql/schema.sql"
                #
                # reset: 每次跑测试之前跑（把数据恢复到已知状态）
                # reset:
                #   - "mvn -q flyway:clean flyway:migrate"
                """;
    }

    // ---------- 各字段 ----------

    /**
     * {@code docker:} —— 两种写法都认（一行命令 / 下面写 {@code command}）。
     *
     * <p>v2 <b>不用它的值</b>：命令恒为 {@code docker compose ...}（只有一处，见 {@link Environment}）。
     * 认它只是为了老文件里写了这一栏时不说「不认识的字段」，另外把写错的字段名报出来。
     */
    private static void docker(JsonNode node, Lines lines, List<String> problems) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return;
        }
        if (!node.isObject()) {
            problems.add(at(lines, "docker") + "docker 要么是一行命令（docker: \"wsl docker\"），"
                    + "要么是 docker: 下面写 command");
            return;
        }
        known(node, DOCKER, lines, "docker", problems, "只有 command");
        if (text(node.get("command"), lines, "docker.command", problems).isBlank()) {
            problems.add(at(lines, "docker") + "docker 下面没有 command：要么补上，要么把整个 docker 段删掉");
        }
    }

    private static String workdir(JsonNode node, Lines lines, List<String> problems) {
        String value = text(node, lines, "workdir", problems);
        if (value.isBlank()) {
            return EnvConfig.DEFAULT_WORKDIR;
        }
        if (!value.startsWith("/")) {
            problems.add(at(lines, "workdir") + "workdir 必须是容器里的绝对路径（以 / 开头），现在是「"
                    + value + "」：它是项目目录挂进去的位置，相对路径会被 compose 当成命名卷，"
                    + "挂上去的就不是你的代码了");
        }
        return value;
    }

    private static Map<String, EnvConfig.Dependency> dependencies(JsonNode node, Lines lines,
                                                                  List<String> problems) {
        Map<String, EnvConfig.Dependency> found = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return found;
        }
        if (!node.isObject()) {
            problems.add(at(lines, "dependencies") + "dependencies 必须是一组「名字: 怎么起它」，"
                    + "例如 db: 下面写 image / env / healthcheck");
            return found;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            String path = "dependencies." + name;
            if (!SERVICE_NAME.matcher(name).matches()) {
                problems.add(at(lines, path) + "依赖名「" + name + "」不能用：它同时是容器互连的服务名，"
                        + "只能用小写字母、数字、点、下划线、短横线，并且以字母或数字开头");
            }
            JsonNode spec = entry.getValue();
            if (spec == null || !spec.isObject()) {
                problems.add(at(lines, path) + path + " 下面要写它怎么起（image 等），现在不是一个字段组");
                continue;
            }
            known(spec, DEPENDENCY, lines, path, problems, "可以用的是 image / env / healthcheck");
            String image = text(spec.get("image"), lines, path + ".image", problems);
            if (image.isBlank()) {
                problems.add(at(lines, path) + path + " 里没有 image：没有它就起不了这个依赖");
            }
            found.put(name, new EnvConfig.Dependency(image,
                    scalars(spec.get("env"), lines, path + ".env", problems),
                    healthcheck(spec.get("healthcheck"), lines, path + ".healthcheck", problems)));
        }
        return found;
    }

    /**
     * {@code healthcheck}：一条命令，或者 {@code {test: "命令", ...}}（只取 test，
     * 另外几个旋钮交给容器自己的默认值——引擎替用户定一个「等多久算超时」只会在慢机器上假装没事）。
     */
    private static String healthcheck(JsonNode node, Lines lines, String path, List<String> problems) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            String test = node.asText("").strip();
            if (test.isEmpty()) {
                problems.add(at(lines, path) + path + " 是空的：要么写一条命令，要么把这一项删掉");
            }
            return test.isEmpty() ? null : test;
        }
        if (!node.isObject()) {
            problems.add(at(lines, path) + path + " 要么是一条命令（healthcheck: \"mysqladmin ping\"），"
                    + "要么写成 {test: \"...\"}");
            return null;
        }
        String test = text(node.get("test"), lines, path + ".test", problems);
        if (test.isBlank()) {
            problems.add(at(lines, path) + path + " 里没有 test：没有要跑的那条命令，这份健康检查等于没写"
                    + "（删掉它，up 就只等容器起来、不等它真的可用）");
            return null;
        }
        return test;
    }

    /** {@code env:}：一组「名字: 值」，值只能是标量（数字、布尔也照收成文本）。 */
    private static Map<String, String> scalars(JsonNode node, Lines lines, String path,
                                               List<String> problems) {
        Map<String, String> found = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return found;
        }
        if (!node.isObject()) {
            problems.add(at(lines, path) + path + " 必须是一组「名字: 值」");
            return found;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                // 空值 = 没写这个变量。留着它会让容器里多一个空字符串的变量，
                // 而「没设」和「设成空」在代码里往往是两条不同的路
                continue;
            }
            if (value.isContainerNode()) {
                problems.add(at(lines, path + "." + entry.getKey()) + path + "." + entry.getKey()
                        + " 的值必须是一行（字符串/数字/布尔）：环境变量传不了嵌套结构");
                continue;
            }
            found.put(entry.getKey(), value.asText());
        }
        return found;
    }

    /**
     * {@code init:} / {@code reset:}：每一条要么是一行命令（跑在 app 容器里），
     * 要么是 {@code command / service / stdin} 三栏。
     */
    private static List<EnvConfig.Step> steps(JsonNode node, String path, Lines lines,
                                              Map<String, EnvConfig.Dependency> dependencies,
                                              List<String> problems) {
        List<EnvConfig.Step> found = new ArrayList<>();
        if (node == null || node.isNull()) {
            return found;
        }
        if (node.isValueNode()) {
            if (!node.asText("").isBlank()) {
                found.add(EnvConfig.Step.inApp(node.asText()));
            }
            return found;
        }
        if (!node.isArray()) {
            problems.add(at(lines, path) + path + " 要么是一行命令，要么是一组命令（每行以 - 开头）");
            return found;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            String where = path + "[" + i + "]";
            if (item != null && item.isValueNode()) {
                if (item.asText("").isBlank()) {
                    problems.add(at(lines, where) + where + " 是空的：每条要么是一行命令，"
                            + "要么写成 command / service / stdin 三栏");
                    continue;
                }
                found.add(EnvConfig.Step.inApp(item.asText()));
                continue;
            }
            if (item == null || !item.isObject()) {
                problems.add(at(lines, where) + where + " 既不是一行命令、也不是一个字段组："
                        + "写 \"命令原文\"（跑在 app 容器里），或者写 command / service / stdin 三栏");
                continue;
            }
            known(item, STEP, lines, where, problems, "只有 command / service / stdin");
            String command = text(item.get("command"), lines, where + ".command", problems);
            String service = text(item.get("service"), lines, where + ".service", problems);
            String stdin = text(item.get("stdin"), lines, where + ".stdin", problems);
            if (command.isBlank()) {
                problems.add(at(lines, where) + where + " 里没有 command：这一条要跑什么？");
                continue;
            }
            if (!service.isBlank() && !dependencies.containsKey(service)) {
                // 判据是「声明过的依赖」：没声明的服务名在 compose 里根本不存在，
                // 而 docker compose exec 报的是「没有这个服务」——那句话帮不到用户
                problems.add(at(lines, where + ".service") + where + ".service 写的是「" + service
                        + "」，但 dependencies 里没有它。能写的是："
                        + (dependencies.isEmpty() ? "（现在一个依赖都没声明）"
                        : String.join("、", dependencies.keySet()))
                        + "。要用某个容器里才有的客户端（mysql / psql / redis-cli 那类），"
                        + "先把它作为依赖声明出来");
                continue;
            }
            found.add(new EnvConfig.Step(command, service, stdin));
        }
        return found;
    }

    /** 这一层里有没有不认识的字段。 */
    private static void known(JsonNode node, Set<String> known, Lines lines, String path,
                              List<String> problems, String hint) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!known.contains(name)) {
                String where = path.isEmpty() ? name : path + "." + name;
                problems.add(at(lines, where) + (path.isEmpty() ? "" : path + " 里的 ")
                        + "「" + name + "」不是这个文件认识的字段（" + hint + "）");
            }
        }
    }

    /** 读一个标量字段；存在但不是标量时也要报。 */
    private static String text(JsonNode node, Lines lines, String path, List<String> problems) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isContainerNode()) {
            problems.add(at(lines, path) + path + " 要的是一个值，不是一个字段组");
            return "";
        }
        return node.asText("").strip();
    }

    /** 「第 N 行：」——行号是给手写这份文件的人看的，指得越准他改得越快。 */
    private static String at(Lines lines, String path) {
        int line = lines.lineOf(path);
        return line > 0 ? "第 " + line + " 行：" : "";
    }

    /**
     * 「这一项在文件里的第几行」。
     *
     * <p>怎么做到的：解析出来的那棵树和词法记号流是同一份文档的两种看法，顺序一致。
     * 这里沿记号流走一遍、只记行号，结构校验仍然交给树——两边各走各的，就不会出现
     * 「为了拿行号把解析也写坏了」。YAML 本身写坏时给不出行号，那不影响谁报错。
     */
    private static final class Lines {

        private final Map<String, Integer> lines;

        private Lines(Map<String, Integer> lines) {
            this.lines = lines;
        }

        static Lines of(String source) {
            Map<String, Integer> found = new HashMap<>();
            Deque<Frame> stack = new ArrayDeque<>();
            stack.push(new Frame(""));
            try (JsonParser parser = new YAMLFactory().createParser(source)) {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    int line = parser.currentTokenLocation().getLineNr();
                    switch (token) {
                        case START_OBJECT, START_ARRAY -> {
                            String path = pathOf(stack.peek());
                            found.putIfAbsent(path, line);
                            stack.push(new Frame(path, token == JsonToken.START_ARRAY));
                        }
                        // 根那一层永远留着：不能让它被多出来的 END 弹掉
                        case END_OBJECT, END_ARRAY -> {
                            if (stack.size() > 1) {
                                stack.pop();
                            }
                        }
                        // 字段名用覆盖：同一个键写两遍时，解析出来的是最后一份，行号也得指到最后那行
                        case FIELD_NAME -> {
                            stack.peek().field = parser.currentName();
                            found.put(pathOf(stack.peek()), line);
                        }
                        // 值这一侧先记的赢：不然 `dependencies:` 那一行会被它下一行的内容顶掉
                        default -> found.putIfAbsent(pathOf(stack.peek()), line);
                    }
                }
            } catch (IOException | RuntimeException e) {
                return new Lines(Map.of());
            }
            return new Lines(found);
        }

        int lineOf(String path) {
            Integer line = lines.get(path);
            return line == null ? -1 : line;
        }

        /** 当前这个记号该挂在哪个路径上：对象看字段名，数组看第几个元素。 */
        private static String pathOf(Frame frame) {
            if (frame.array) {
                return frame.path + "[" + frame.index++ + "]";
            }
            if (frame.field == null) {
                return frame.path;
            }
            return frame.path.isEmpty() ? frame.field : frame.path + "." + frame.field;
        }

        private static final class Frame {

            private final String path;
            private final boolean array;
            private String field;
            private int index;

            Frame(String path) {
                this(path, false);
            }

            Frame(String path, boolean array) {
                this.path = path;
                this.array = array;
            }
        }
    }
}
