package com.specflow.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.specflow.exception.SpecValidationException;
import com.specflow.project.ProjectConfigLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 读 {@code .specflow/env.yaml}，并把结构问题带上<b>行号</b>报出来。
 *
 * <p>两条口径和 {@code project.yaml} 那边保持一致，理由也一样：
 * <ul>
 *   <li><b>文件不在 = 没声明</b>（{@link Optional#empty()}），不是错误。
 *       没写这一份的项目照旧只能跑单元测试——那是这批之前的行为，一个字节都不该变；</li>
 *   <li><b>只有注释、只有空行的文件也算没声明</b>：那是「我建了这个文件但还没想好」，
 *       按错误处理会让项目直接打不开，而修它偏偏要先打开这个项目。</li>
 * </ul>
 *
 * <p>剩下的每一处不对都要报，<b>而且一次报全</b>：用户改一轮就能改完。
 * 遇到第一个问题就退出的话，他会来回跑五趟，每一趟只被告知一处错。
 *
 * <p>它只做<b>结构</b>校验（字段认不认识、类型对不对、必填项在不在）。
 * 「这条命令是不是高危」是另一道闸，在真正要执行它的时候判（见 {@link TestEnvironment}）——
 * 放在这里会让「读一份配置」变成「替你决定能不能跑」。
 */
public final class EnvConfigLoader {

    public static final String CONFIG_DIR = ".specflow";
    public static final String CONFIG_FILE = "env.yaml";

    private static final ObjectMapper YAML = new YAMLMapper();

    /** 认识的那几个字段。多一个都要报——写错一个字母就静默失效是最难查的一种错。 */
    private static final Set<String> TOP_LEVEL =
            Set.of("docker", "image", "workdir", "dependencies", "env", "init", "reset");

    private static final Set<String> DOCKER_FIELDS = Set.of("command");

    private static final Set<String> DEPENDENCY_FIELDS = Set.of("image", "env", "healthcheck");

    private static final Set<String> HEALTHCHECK_FIELDS =
            Set.of("test", "interval", "timeout", "retries", "start-period");

    /**
     * 服务名/卷名能用的字符。
     *
     * <p>管它是为了早报错：这个名字会被当成 compose 里的服务名（也是容器互连时的主机名），
     * 而 compose 只认这一套字符。写了大写、下划线之外的东西，用户拿到的是 docker 的
     * 一句语法错误——那句话不会告诉他「是你起的名字不对」。
     */
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /** 这条路径的字段在文件的第几行。 */
    private static String at(YamlLines lines, String path) {
        int line = lines.lineOf(path);
        return line > 0 ? "第 " + line + " 行：" : "";
    }

    /** 没声明 {@code env.yaml} 时返回空。 */
    public static Optional<EnvConfig> load(Path projectRoot) {
        Path file = fileOf(projectRoot);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SpecValidationException(List.of(
                    "读不了 " + shown(projectRoot, file) + "：" + e.getMessage()));
        }
        // 空文件、只有注释的文件 = 「还没想好」，等同于没声明（口径见类注释）
        if (ProjectConfigLoader.hasNoContent(source)) {
            return Optional.empty();
        }
        return Optional.of(parse(source.strip(), shown(projectRoot, file)));
    }

    /** {@code .specflow/env.yaml} 的绝对路径。 */
    public static Path fileOf(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().resolve(CONFIG_DIR).resolve(CONFIG_FILE);
    }

    /**
     * 这份声明文件在不在（<b>不解析</b>）。
     *
     * <p>为什么要一个「只看在不在」的口子：文件写坏了的时候，「有没有写这份声明」和
     * 「这份声明能不能用」是两个问题。混在一起，界面会把「写了但有错」显示成「没写」——
     * 而用户明明刚写完，他只会以为工具没看见那个文件。
     */
    public static boolean exists(Path projectRoot) {
        return Files.isRegularFile(fileOf(projectRoot));
    }

    /** 这个路径是不是那份声明文件（相对项目根，POSIX 风格）。 */
    public static String relativePath() {
        return CONFIG_DIR + "/" + CONFIG_FILE;
    }

    private static EnvConfig parse(String source, String shown) {
        YamlLines lines = YamlLines.of(source);
        JsonNode root;
        try {
            root = YAML.readTree(source);
        } catch (IOException e) {
            throw new SpecValidationException(List.of(shown + " 不是合法的 YAML：" + e.getMessage()));
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new EnvConfig(null, "", null, Map.of(), Map.of(), List.of(), List.of());
        }
        List<String> problems = new ArrayList<>();
        if (!root.isObject()) {
            throw new SpecValidationException(List.of(
                    at(lines, "") + shown + " 的最外层必须是一组「字段: 值」"));
        }
        knownFields(root, TOP_LEVEL, lines, "", problems,
                "可以用的是 docker / image / workdir / dependencies / env / init / reset");

        String dockerCommand = dockerCommand(root.get("docker"), lines, problems);
        String image = text(root.get("image"), lines, "image", problems);
        String workdir = workdir(root.get("workdir"), lines, problems);
        Map<String, EnvConfig.Dependency> dependencies =
                dependencies(root.get("dependencies"), lines, problems);
        Map<String, String> env = scalars(root.get("env"), lines, "env", problems);
        List<String> init = commands(root.get("init"), lines, "init", problems);
        List<String> reset = commands(root.get("reset"), lines, "reset", problems);

        if (image.isBlank()) {
            // 少它就没有那个挂项目目录的常驻容器，整套环境无从谈起——所以是必填，
            // 而且要说清「为什么要它」，不然用户只会觉得这工具在挑刺
            problems.add(at(lines, "image") + shown
                    + " 里没有 image：它是测试容器的镜像（项目目录挂进去、常驻着跑测试）");
        }
        if (!problems.isEmpty()) {
            throw new SpecValidationException(List.copyOf(problems));
        }
        return new EnvConfig(dockerCommand, image, workdir, dependencies, env, init, reset);
    }

    // ---------- 各字段 ----------

    private static String dockerCommand(JsonNode node, YamlLines lines, List<String> problems) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual() || node.isNumber()) {
            // 允许直接写字符串：这条捷径的代价只是多一个分支，而它省掉的是
            // 「为了填一个命令先写一层缩进」那种没人愿意干的事
            return node.asText();
        }
        if (!node.isObject()) {
            problems.add(at(lines, "docker") + "docker 要么是一行命令（docker: \"wsl docker\"），"
                    + "要么是 docker: 下面写 command");
            return null;
        }
        knownFields(node, DOCKER_FIELDS, lines, "docker", problems, "只有 command");
        JsonNode command = node.get("command");
        if (command == null || command.isNull()) {
            problems.add(at(lines, "docker") + "docker 下面没有 command："
                    + "要么补上它，要么把整个 docker 段删掉（删掉就按 PATH 和常见安装路径找）");
            return null;
        }
        if (!command.isValueNode() || command.asText().isBlank()) {
            problems.add(at(lines, "docker.command") + "docker.command 必须是一行非空命令，"
                    + "例如 \"wsl docker\" 或 \"C:/Program Files/Docker/Docker/resources/bin/docker.exe\"");
            return null;
        }
        return command.asText();
    }

    private static String workdir(JsonNode node, YamlLines lines, List<String> problems) {
        if (node == null || node.isNull()) {
            return EnvConfig.DEFAULT_WORKDIR;
        }
        String value = text(node, lines, "workdir", problems);
        if (value.isBlank()) {
            return EnvConfig.DEFAULT_WORKDIR;
        }
        if (!value.startsWith("/")) {
            problems.add(at(lines, "workdir") + "workdir 必须是容器里的绝对路径（以 / 开头），现在是「"
                    + value + "」：它是项目目录挂进容器的位置，相对路径会被 compose 当成命名卷，"
                    + "挂上去的就不是你的代码了");
        }
        return value;
    }

    private static Map<String, EnvConfig.Dependency> dependencies(JsonNode node, YamlLines lines,
                                                                  List<String> problems) {
        Map<String, EnvConfig.Dependency> found = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return found;
        }
        if (!node.isObject()) {
            problems.add(at(lines, "dependencies") + "dependencies 必须是一组「名字: 怎么起它」，"
                    + "例如 dependencies: 下面写 db: 再写 image");
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
                problems.add(at(lines, path) + "dependencies." + name
                        + " 下面要写它怎么起（image 等），现在不是一个字段组");
                continue;
            }
            knownFields(spec, DEPENDENCY_FIELDS, lines, path, problems, "可以用的是 image / env / healthcheck");
            String image = text(spec.get("image"), lines, path + ".image", problems);
            if (image.isBlank()) {
                problems.add(at(lines, path) + "dependencies." + name + " 里没有 image："
                        + "没有它就起不了这个依赖");
            }
            Map<String, String> env = scalars(spec.get("env"), lines, path + ".env", problems);
            EnvConfig.Healthcheck healthcheck =
                    healthcheck(spec.get("healthcheck"), lines, path + ".healthcheck", problems);
            found.put(name, new EnvConfig.Dependency(image, env, healthcheck));
        }
        return found;
    }

    private static EnvConfig.Healthcheck healthcheck(JsonNode node, YamlLines lines, String path,
                                                     List<String> problems) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            // 只写一条命令的那种简写：健康检查十有八九就是这个形状
            String test = node.asText().strip();
            if (test.isEmpty()) {
                problems.add(at(lines, path) + "healthcheck 是空的：要么写一条命令，要么把这一项删掉");
                return null;
            }
            return new EnvConfig.Healthcheck(test, null, null, null, null);
        }
        if (!node.isObject()) {
            problems.add(at(lines, path) + "healthcheck 要么是一条命令（healthcheck: \"mysqladmin ping\"），"
                    + "要么是 healthcheck: 下面写 test / interval / timeout / retries / start-period");
            return null;
        }
        knownFields(node, HEALTHCHECK_FIELDS, lines, path, problems,
                "可以用的是 test / interval / timeout / retries / start-period");
        String test = text(node.get("test"), lines, path + ".test", problems);
        if (test.isBlank()) {
            problems.add(at(lines, path) + "healthcheck 里没有 test："
                    + "没有要跑的那条命令，这份健康检查等于没写——要么补上 test，要么把 healthcheck 删掉，"
                    + "删掉之后 up 只等容器起来，不等它真的可用");
        }
        return new EnvConfig.Healthcheck(test,
                text(node.get("interval"), lines, path + ".interval", problems),
                text(node.get("timeout"), lines, path + ".timeout", problems),
                text(node.get("retries"), lines, path + ".retries", problems),
                text(node.get("start-period"), lines, path + ".start-period", problems));
    }

    /** {@code env:} 或者依赖的 {@code env:}：一组「名字: 值」，值只能是标量。 */
    private static Map<String, String> scalars(JsonNode node, YamlLines lines, String path,
                                               List<String> problems) {
        Map<String, String> found = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return found;
        }
        if (!node.isObject()) {
            problems.add(at(lines, path) + path + " 必须是一组「名字: 值」，例如 "
                    + path + ": 下面写 DB_HOST: db");
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
                        + " 的值必须是一行值（字符串/数字/布尔）：环境变量传不了嵌套结构，"
                        + "要传多个值就写多个变量");
                continue;
            }
            found.put(entry.getKey(), value.asText());
        }
        return found;
    }

    /** {@code init:} / {@code reset:}：一行命令，或者一组命令。 */
    private static List<String> commands(JsonNode node, YamlLines lines, String path,
                                         List<String> problems) {
        List<String> found = new ArrayList<>();
        if (node == null || node.isNull()) {
            return found;
        }
        if (node.isTextual()) {
            if (!node.asText().isBlank()) {
                found.add(node.asText().strip());
            }
            return found;
        }
        if (!node.isArray()) {
            problems.add(at(lines, path) + path + " 要么是一行命令，要么是一组命令（每行以 - 开头）");
            return found;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (item == null || !item.isTextual() || item.asText().isBlank()) {
                problems.add(at(lines, path + "[" + i + "]") + path + " 的第 " + (i + 1)
                        + " 条不是一行命令：每一条都要是能在容器里直接执行的字符串");
                continue;
            }
            found.add(item.asText().strip());
        }
        return found;
    }

    /** 这一层里有没有不认识的字段。 */
    private static void knownFields(JsonNode node, Set<String> known, YamlLines lines, String path,
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

    /** 读一个标量字段；它存在但不是标量时也要报。 */
    private static String text(JsonNode node, YamlLines lines, String path, List<String> problems) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isContainerNode()) {
            problems.add(at(lines, path) + path + " 要的是一个值，不是一个字段组");
            return "";
        }
        return node.asText().strip();
    }

    private static String shown(Path projectRoot, Path file) {
        Path root = projectRoot.toAbsolutePath().normalize();
        return file.startsWith(root)
                ? root.relativize(file).toString().replace('\\', '/')
                : file.toString();
    }
}
