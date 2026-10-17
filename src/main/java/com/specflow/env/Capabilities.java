package com.specflow.env;

import com.specflow.core.Cmd;
import com.specflow.core.Progress;
import com.specflow.core.Project;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>「这个环境真能用吗」——由用它的人来验，不由搭它的人自证</b>。
 *
 * <h2>为什么要有这个类（一次真实事故）</h2>
 * ① 原先的闸是：编译命令非空 + 环境起过。而"编译"跑在<b>宿主机</b>上，宿主机装了 Maven，
 * 于是 ① 亮绿灯；可容器用的镜像是 {@code eclipse-temurin:17-jdk}——<b>只有 JDK，没有 Maven</b>。
 * ⑤ 一进去 `mvn` 找不到，只能自己下载安装，烧掉 20 轮 / 30 分钟，最后一条用例都没验成。
 *
 * <p>所以能力的判据只有一条：<b>在一个真容器里，用真项目跑真命令</b>。
 * 声明写得再漂亮，跑不通就是跑不通。
 *
 * <h2>两步，先便宜后贵</h2>
 * <ol>
 *   <li>{@link #probe}：从编译命令里认出"要哪个构建工具"，然后 {@code command -v} 问一句（秒级）。
 *       这一步就能挡住上面那次事故——缺工具是最常见、也最便宜能查出来的一种；</li>
 *   <li>{@link #build}：把编译命令<b>原样</b>在容器里跑一遍。它贵（首次要下依赖），
 *       所以有了依赖缓存卷之后才有意义：第二次起是秒级到分钟级。</li>
 * </ol>
 *
 * <p>工具不在就<b>不再往下跑构建</b>：那样既省时间，话术也更准（说"缺 mvn"，而不是让人去读一堆
 * 意料之外的报错）。
 */
public final class Capabilities {

    /** 我们认得的构建工具。认得就指名道姓地说，认不得就退回"命令里第一个词"。 */
    private static final Set<String> TOOLS = Set.of(
            "mvn", "mvnw", "gradle", "gradlew", "javac", "ant",
            "npm", "yarn", "pnpm", "bun", "node", "deno",
            "go", "cargo", "rustc", "dotnet", "msbuild",
            "python", "python3", "pip", "pip3", "poetry", "uv",
            "make", "cmake", "ninja", "gcc", "g++", "clang", "tsc", "webpack", "vite");

    /** 只是"换个目录/接个管道"的词：它们不是构建工具，跳过看下一个。 */
    private static final Set<String> NOISE = Set.of("cd", "sh", "bash", "zsh", "cmd", "powershell", "pwsh",
            "env", "time", "sudo", "exec", "eval", "set", "export", "if", "then", "else", "fi", "for", "do",
            "done", "while", "&&", "||", ";", "(", ")");

    /** 工具探测的时限：一次 {@code command -v} 不该拖住界面。 */
    private static final long PROBE_SECONDS = 60;

    private Capabilities() {
    }

    /**
     * 一次能力校验的结论。
     *
     * @param tool        从编译命令里认出来的构建工具（认不出是空串）
     * @param toolOk      容器里有没有它
     * @param buildOk     容器里真跑构建过没过
     * @param buildSeconds 构建花了多久
     * @param detail      给人看的一句话（失败时说清缺什么、该怎么改）
     * @param raw         原始输出（失败时是尾巴；界面"展开看全部"看它）
     * @param fixedCommand 校验顺手改对的编译命令（空串 = 没改）；非空时调用方要写回 project.yaml
     */
    public record Report(String tool, boolean toolOk, boolean buildOk, long buildSeconds,
                         String detail, String raw, String fixedCommand) {

        /** 探测通过、构建也通过，才算"这个环境真能用"。 */
        public boolean ok() {
            return toolOk && buildOk;
        }
    }

    /**
     * 完整校验：先探工具，再真构建。
     *
     * @param env      已经起过环境的那一个（{@link Environment#inApp} 走的是它渲染出的 compose）
     * @param timeoutSeconds 构建最多等多久
     */
    public static Report verify(Environment env, Project project, long timeoutSeconds,
                                Progress progress) {
        String compile = project.compile();
        String tool = toolOf(compile);
        Cmd.Result found = env.inApp("command -v " + (tool.isBlank() ? "sh" : tool), PROBE_SECONDS, progress);
        if (!found.ok()) {
            String where = tool.isBlank() ? "命令里认不出构建工具" : "容器里没有 `" + tool + "`";
            return new Report(tool, false, false, 0,
                    where + "，而项目配的编译命令是 `" + compile + "`。这不是测试该自己补的："
                            + "改 .specflow/env.yaml 的 image（换一个自带该工具的镜像，"
                            + "例如 Java 项目用 maven:3.9-eclipse-temurin-17），或者在 init 里装一次，"
                            + "然后重新起环境。",
                    tail(found.output(), 20), "");
        }

        long started = System.currentTimeMillis();
        Cmd.Result built = env.inApp(compile, timeoutSeconds, progress);
        long seconds = Math.max(0, (System.currentTimeMillis() - started) / 1000);
        if (!built.ok()) {
            // 自我修复一次：同一条命令要在宿主机和 Linux 容器两处都能跑，
            // Windows 风格的反斜杠在 sh 里是转义字符（`out\main` → `outmain`）。
            // 只在**第一次没过**且命令里真带反斜杠时才重试，代价是一次构建、收益是"不用人去猜哪里写错了"。
            Report repaired = repair(env, compile, timeoutSeconds, progress, tool, built);
            if (repaired != null) {
                return repaired;
            }
            return new Report(tool, true, false, seconds,
                    "容器里有 `" + tool + "`，但构建没过（" + built.summary() + "）。"
                            + "这是环境或项目本身的问题，⑤ 也一定会撞上——先在 ① 把它修好："
                            + "看下面的原始输出，多半是缺系统依赖、网络拉不到依赖，或者编译命令要改成容器里那套。",
                    tail(built.output(), 40), "");
        }
        return new Report(tool, true, true, seconds,
                "容器里能构建（`" + tool + "`，" + seconds + " 秒）", "", "");
    }

    /**
     * 把 {@code \} 换成正斜杠再试一次；过了就把它带回给调用方写回盘上。
     *
     * @return 试成功的那份结论；没得试、也没试成功时返回 {@code null}（让调用方按"真失败"收场）
     */
    private static Report repair(Environment env, String compile, long timeoutSeconds,
                                 Progress progress, String tool, Cmd.Result first) {
        String fixed = linuxSafe(compile);
        if (fixed.isEmpty()) {
            return null;
        }
        progress.line("容器里构建没过，先按 Linux 的写法再试一次：" + fixed);
        long started = System.currentTimeMillis();
        Cmd.Result again = env.inApp(fixed, timeoutSeconds, progress);
        long seconds = Math.max(0, (System.currentTimeMillis() - started) / 1000);
        if (!again.ok()) {
            return null;
        }
        return new Report(tool, true, true, seconds,
                "容器里能构建（`" + tool + "`，" + seconds + " 秒）。编译命令里的反斜杠引擎替你改成正斜杠了："
                        + "同一条命令要在宿主机和 Linux 容器里都跑，`\\` 在容器里是转义字符。",
                tail(first.output(), 20), fixed);
    }

    /**
     * <b>这条命令换到 Linux 容器里该怎么写</b>：没有可换的就返回空串。
     *
     * <p>只做一件事：把路径里的 {@code \} 换成正斜杠（{@code out\main} → {@code out/main}）。
     * 换与不换的判据刻意保守——<b>反斜杠在正则里是有意思的</b>，所以：
     * <ul>
     *   <li>带 {@code grep}/{@code sed}/{@code awk}/{@code perl}/{@code rg} 的命令一律不动（那多半是模式不是路径）；</li>
     *   <li>已经有 {@code \\} 的不动（有人已经在按 Linux 的转义写了，再换会把意思改掉）。</li>
     * </ul>
     *
     * <p>即便判错也不至于造成误伤：换了之后还要<b>真在容器里跑通</b>才会被采用，
     * 跑不通就按原来的失败收场（原始输出照样给人看）。
     */
    static String linuxSafe(String compile) {
        String text = compile == null ? "" : compile;
        if (text.indexOf('\\') < 0 || text.contains("\\\\")) {
            return "";
        }
        for (String regex: List.of("grep", "sed", "awk", "perl", "rg", "egrep", "tr")) {
            if (text.matches("(?s).*\\b" + regex + "\\b.*")) {
                return "";
            }
        }
        return text.replace('\\', '/');
    }

    /**
     * 从编译命令里认出构建工具。
     *
     * <p>认得的就按"我们认得的那几个名字"匹配（这样 {@code cd x && mvn package} 也能认出 {@code mvn}）；
     * 认不得就退回命令里第一个"不是 cd/sh/&& 之类噪音"的词——**猜错也不要紧**：
     * 猜错时后续探测会说"容器里没有 X"，而原始命令原文就在那句话里，用户看得出哪里不对。
     */
    public static String toolOf(String compile) {
        String text = compile == null ? "" : compile;
        String[] parts = text.split("\\s+");
        for (String one : parts) {
            String token = cleanup(one);
            if (!token.isEmpty() && TOOLS.contains(token)) {
                return token;
            }
        }
        boolean skipNext = false;
        for (String one : parts) {
            String token = cleanup(one);
            if (skipNext) {
                // `cd <目录>`：紧跟的那个词是它的参数，不是构建工具
                skipNext = false;
                continue;
            }
            if (token.isEmpty() || NOISE.contains(token) || token.startsWith("-") || token.contains("=")) {
                skipNext = "cd".equals(token);
                continue;
            }
            return token;
        }
        return "";
    }

    /** 去掉引号与 {@code .cmd}/{@code .exe} 后缀、小写。 */
    private static String cleanup(String raw) {
        String token = raw == null ? "" : raw.strip();
        token = token.replace("\"", "").replace("'", "");
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        if (slash >= 0) {
            token = token.substring(slash + 1);
        }
        String lower = token.toLowerCase(Locale.ROOT);
        for (String suffix : List.of(".cmd", ".bat", ".exe", ".ps1")) {
            if (lower.endsWith(suffix)) {
                lower = lower.substring(0, lower.length() - suffix.length());
            }
        }
        // `&mvn` 这种前缀要去掉；但 `&&` 是两个符号，不能被削成 `&`（那会变成一个认不出的词）
        if (lower.startsWith("&") && lower.length() > 1 && Character.isLetterOrDigit(lower.charAt(1))) {
            lower = lower.substring(1);
        }
        return lower;
    }

    /** 只留最后几行（原始输出常常几百行）。 */
    private static String tail(String output, int rows) {
        if (output == null || output.isBlank()) {
            return "";
        }
        List<String> all = output.lines().toList();
        int from = Math.max(0, all.size() - rows);
        return String.join("\n", all.subList(from, all.size()));
    }
}
