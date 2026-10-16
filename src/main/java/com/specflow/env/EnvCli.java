package com.specflow.env;

import com.specflow.core.Cmd;
import com.specflow.core.Layout;
import com.specflow.core.Project;
import com.specflow.core.Progress;
import com.specflow.core.SpecException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code specflow env …}：<b>不开界面也能伺候环境</b>。
 *
 * <pre>
 *   specflow env init    [-p 项目] [--timeout 分钟]   起环境（拉镜像 + init）
 *   specflow env reset   [-p 项目] [--timeout 分钟]   只重置数据
 *   specflow env status  [-p 项目]                    现在什么状态
 *   specflow env down    [-p 项目] [--purge]          收干净（连卷）；--purge 再删流程数据
 * </pre>
 *
 * <h2>为什么要有它</h2>
 * 一，闭掉界面也能收场——用户在界面上点了「起环境」，然后关掉窗口，容器还在跑；
 * {@code down} 是那条不用打开浏览器的收尾路。二，{@code start.bat} / {@code stop.bat}
 * 就架在它上面（脚本不该去猜 docker 参数）。
 *
 * <h2>收场为什么比「一句 down」多</h2>
 * 这台机器上属于这个项目的东西有三摊：① 主环境的容器/卷/网络；② ⑤ 测试沙箱
 * （{@code <项目名>-sandbox}，挂的是副本）；③ 副本目录本身。进程被硬杀时后两摊会留下，
 * 而它们<b>不归主环境那条 compose 管</b>——所以 {@code down} 按<b>项目名标签</b>扫一遍，
 * 而不是只跑一次 {@code docker compose down}。界面与脚本共用的就是这一条。
 */
public final class EnvCli {

    /** 用法不对（参数写错）——与「跑失败了」分开，好让脚本能分辨。 */
    public static final int EXIT_USAGE = 2;

    /** 真去跑、但没成功（起不来 / 收不掉）。 */
    public static final int EXIT_FAILED = 3;

    /** ⑤ 测试沙箱的 compose 项目名后缀（{@code TestSandbox} 里那一份口径）。 */
    public static final String SANDBOX_SUFFIX = "-sandbox";

    private static final long SWEEP_TIMEOUT_SECONDS = 180;

    private EnvCli() {
    }

    /** 解析后的一个动作。 */
    public record Plan(String action, Path project, long timeoutMinutes, boolean purge,
                       boolean verbose) {
    }

    /** 认得的动作（顺序就是帮助里的顺序）。 */
    public static final List<String> ACTIONS = List.of("prepare", "init", "reset", "status", "down");

    /**
     * 命令行 → 计划。<b>纯函数</b>，所以「参数写错」这一档能脱离 docker 被钉住。
     *
     * @throws SpecException 参数不认识（正文就是给人看的用法）
     */
    public static Plan plan(List<String> args) {
        if (args.isEmpty() || isHelp(args.get(0))) {
            throw new SpecException(usage());
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new SpecException("不认识的 env 子命令：" + action + "（只能是 "
                    + String.join(" / ", ACTIONS) + "）\n\n" + usage());
        }
        Path project = Path.of("").toAbsolutePath();
        long timeout = 0;
        boolean purge = false;
        boolean verbose = false;
        for (int at = 1; at < args.size(); at++) {
            String one = args.get(at);
            switch (one) {
                case "-p", "--project" -> project = Path.of(value(args, ++at, one));
                case "--timeout" -> timeout = minutes(value(args, ++at, one));
                case "--purge" -> purge = true;
                case "--verbose" -> verbose = true;
                default -> throw new SpecException("不认识的参数：" + one + "\n\n" + usage());
            }
        }
        if (purge && !"down".equals(action)) {
            throw new SpecException("--purge 只跟 down 一起用（它会删掉整个流程数据）");
        }
        return new Plan(action, project, timeout, purge, verbose);
    }

    public static String usage() {
        return """
                用法：specflow env <动作> [-p <项目>] [--timeout <分钟>] [--purge] [--verbose]

                  prepare  只铺配置：建 .specflow/project.yaml 与 local.env（不动 docker）
                  init     起环境：拉镜像、up -d --wait、逐条跑 init（失败就 down -v 收干净）
                  reset    只重置数据（环境照旧）
                  status   现在是什么状态（不会动任何东西）
                  down     收干净：主环境 + 测试沙箱的容器/卷/网络（连副本目录）
                           --purge  连流程数据（.specflow/flow）一起删——项目配置保留

                -p <项目>      项目目录，默认当前目录
                --timeout <N>  这一次「我愿意等多久」（分钟），默认 30
                --verbose      把 docker 的原始输出也打出来
                """;
    }

    public static int run(List<String> args) {
        Plan plan;
        try {
            plan = plan(args);
        } catch (SpecException e) {
            System.out.println(e.getMessage());
            return EXIT_USAGE;
        }

        Path root = plan.project().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.out.println("✗ 没有这个目录：" + root);
            return EXIT_USAGE;
        }
        Project project = Project.open(root);
        Console console = new Console(plan.verbose());
        System.out.println("specflow env " + plan.action() + "   项目：" + root);

        try {
            switch (plan.action()) {
                case "status" -> System.out.println("• " + new Environment(project).status());
                case "prepare" -> {
                    if (project.prepared() && Files.isRegularFile(project.layout().projectYaml())) {
                        System.out.println("• 配置已经在了：" + project.layout().show(project.layout().projectYaml()));
                    } else {
                        project.bootstrap();
                        System.out.println("• 铺好了一份最小配置：" + project.layout().show(project.layout().projectYaml())
                                + " 与 " + project.layout().show(project.layout().localEnv())
                                + "（密钥写进后者；env.yaml 要等 ① 环境 Agent 或你自己写）");
                    }
                }
                case "init" -> {
                    if (!project.prepared()) {
                        project.bootstrap();
                        System.out.println("• 这个目录还没有 .specflow：已经铺了一份最小配置（"
                                + project.layout().show(project.layout().projectYaml()) + "）");
                    }
                    Environment.Registration done = environment(project, plan).up(console);
                    System.out.println("✓ " + done.summarize());
                }
                case "reset" -> {
                    Environment.Registration done = environment(project, plan).reset(console);
                    System.out.println("✓ " + done.summarize());
                }
                case "down" -> teardown(project, plan);
                default -> throw new SpecException("不认识的 env 子命令：" + plan.action());
            }
            return 0;
        } catch (SpecException e) {
            console.fail(headline(e.getMessage()), e.getMessage());
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            console.fail(String.valueOf(e.getMessage()), String.valueOf(e));
            return EXIT_FAILED;
        }
    }

    // ---------- 收场 ----------

    /**
     * 把这个项目在这台机器上的东西收干净：<b>测试沙箱 → 主环境 →（可选）流程数据</b>。
     *
     * <p>顺序是有意的：先收沙箱再收主环境。沙箱挂的是副本、主环境挂的是真项目，
     * 反过来的话，主环境那条 {@code down -v} 删卷时可能碰上沙箱还占着同名卷。
     *
     * <p>每一步都跟着一次<b>按项目名标签的兜底扫</b>：主环境那条 {@code down} 靠的是
     * 「引擎渲染出来的那份 compose」，而进程被硬杀、或者流程 id 换过之后，
     * 那份文件可能已经不在——那时它会如实说「没起过」，容器却还在这台机器上跑着。
     * 用户按的是「停掉并收干净」，那就得真收干净。
     */
    private static void teardown(Project project, Plan plan) {
        Layout layout = project.layout();
        String mainName = Compose.projectName(project.root());

        int swept = sweepSandboxes(layout, mainName + SANDBOX_SUFFIX);
        System.out.println("• 测试沙箱（⑤ 副本）：" + (swept == 0 ? "没有残留" : "收掉 " + swept + " 处"));

        System.out.println("• " + new Environment(project).down());
        if (sweepByLabel(mainName, layout.root())) {
            System.out.println("• 主环境还有残留（那份 compose 已经不在了）：也按项目名收掉了");
        }

        if (plan.purge()) {
            Path flows = layout.flows();
            long files = countFiles(flows);
            deleteTree(flows);
            System.out.println("• 流程数据已删：" + layout.show(flows)
                    + (files == 0 ? "（本来就是空的）" : "（" + files + " 个文件）")
                    + "；project.yaml / env.yaml / local.env 都留着");
        }
    }

    /**
     * 测试沙箱的收法：<b>先按残留的副本收，再按项目名标签扫</b>。
     *
     * <p>为什么两遍都要：副本还在时用它的 compose 最准（网络、卷都归它管）；
     * 而进程被硬杀之后副本可能已经没了、容器却还在，这时只能靠 compose 打在资源上的
     * {@code com.docker.compose.project} 标签去找它们——只跑一遍会留下"看不见的容器"。
     */
    private static int sweepSandboxes(Layout layout, String sandboxName) {
        int swept = 0;
        for (Path compose : leftoverSandboxComposes(layout)) {
            Cmd.Result result = Cmd.run(List.of("docker", "compose", "-p", sandboxName, "-f",
                            compose.toString(), "down", "-v", "--remove-orphans"),
                    compose.getParent(), null, SWEEP_TIMEOUT_SECONDS, null);
            if (!result.ok() && result.output() != null && !result.output().isBlank()) {
                System.out.println("  （副本那份 compose 没收干净：" + one(result.output()) + "）");
            }
            deleteTree(compose.getParent());
            swept++;
        }
        if (sweepByLabel(sandboxName, layout.root())) {
            swept++;
        }
        return swept;
    }

    /** 按 {@code com.docker.compose.project=<name>} 标签收容器 → 网络 → 卷。 */
    private static boolean sweepByLabel(String name, Path workdir) {
        String label = "label=com.docker.compose.project=" + name;
        List<String> containers = ids(List.of("docker", "ps", "-aq", "--filter", label), workdir);
        List<String> networks = ids(List.of("docker", "network", "ls", "-q", "--filter", label), workdir);
        List<String> volumes = ids(List.of("docker", "volume", "ls", "-q", "--filter", label), workdir);
        if (containers.isEmpty() && networks.isEmpty() && volumes.isEmpty()) {
            return false;
        }
        // 顺序不能换：容器不先删，网络和卷会报「还有东西在用」
        run(List.of("docker", "rm", "-f"), containers, workdir);
        run(List.of("docker", "network", "rm"), networks, workdir);
        run(List.of("docker", "volume", "rm"), volumes, workdir);
        return true;
    }

    /** 残留的副本：{@code flow/<id>/sandbox/<runId>/compose.yaml}。 */
    private static List<Path> leftoverSandboxComposes(Layout layout) {
        Path flows = layout.flows();
        if (!Files.isDirectory(flows)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(flows, 4)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().equals(Environment.COMPOSE_FILE))
                    .filter(file -> {
                        Path runId = file.getParent();
                        Path sandbox = runId == null ? null : runId.getParent();
                        return sandbox != null && "sandbox".equals(
                                String.valueOf(sandbox.getFileName()));
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---------- 小工具 ----------

    private static Environment environment(Project project, Plan plan) {
        return new Environment(project).withPullTimeoutMinutes(plan.timeoutMinutes());
    }

    private static List<String> ids(List<String> argv, Path workdir) {
        Cmd.Result result = Cmd.run(argv, workdir, null, 60, null);
        if (!result.ok() || result.output() == null) {
            return List.of();
        }
        return result.output().lines().map(String::strip).filter(one -> !one.isEmpty()).toList();
    }

    private static void run(List<String> head, List<String> ids, Path workdir) {
        // 没有 id 要删时别去敲 docker：空 argv 只会得到一句与真实原因无关的用法错误
        if (ids.isEmpty()) {
            return;
        }
        Cmd.run(concat(head, ids), workdir, null, SWEEP_TIMEOUT_SECONDS, null);
    }

    private static List<String> concat(List<String> head, List<String> tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(tail);
        return all;
    }

    private static long countFiles(Path tree) {
        if (!Files.isDirectory(tree)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(tree)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 删一棵树（删不掉的跳过：副本与历史数据都不是「必须删成功」才算收干净的东西）。 */
    private static void deleteTree(Path tree) {
        if (!Files.exists(tree)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tree)) {
            all.addAll(walk.sorted(Comparator.reverseOrder()).toList());
        } catch (IOException e) {
            return;
        }
        for (Path one : all) {
            try {
                Files.deleteIfExists(one);
            } catch (IOException ignored) {
                // 被容器占着的那种：下一次起环境会先删再建
            }
        }
    }

    private static String one(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty())
                .findFirst().orElse("");
    }

    private static String headline(String text) {
        return text == null || text.isBlank() ? "出错了" : text.lines().findFirst().orElse(text);
    }

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg) || "help".equals(arg);
    }

    private static String value(List<String> args, int at, String flag) {
        if (at >= args.size() || args.get(at).isBlank()) {
            throw new SpecException(flag + " 后面要跟一个值\n\n" + usage());
        }
        return args.get(at);
    }

    private static long minutes(String text) {
        try {
            long value = Long.parseLong(text.strip());
            if (value <= 0) {
                throw new NumberFormatException("要正数");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new SpecException("--timeout 要一个正整数（分钟），拿到的是「" + text + "」");
        }
    }

    /**
     * 命令行的进度口：<b>一行一件事</b>，不刷屏。
     *
     * <p>docker 的原始输出默认只在 {@code --verbose} 下打：拉镜像那几万行会把
     * 「哪一步失败了」冲出窗口，而失败时 {@link #fail} 会把原文<b>全文</b>补上
     * ——也就是说，平时干净、出事时不缺信息。
     */
    static final class Console implements Progress {

        private final boolean verbose;
        private final long started = System.currentTimeMillis();
        private final List<Map<String, Object>> steps = new ArrayList<>();

        Console(boolean verbose) {
            this.verbose = verbose;
        }

        @Override
        public void step(String name, String command, long timeoutSeconds) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", name);
            one.put("command", command == null ? "" : command);
            one.put("ok", false);
            one.put("seconds", 0);
            steps.add(one);
            System.out.println("→ " + name
                    + (timeoutSeconds > 0 ? "（最多等 " + timeoutSeconds + " 秒）" : "")
                    + "…" + elapsed());
            if (verbose && command != null && !command.isBlank()) {
                System.out.println("  $ " + command);
            }
        }

        @Override
        public void stepDone(String name) {
            steps.stream().filter(one -> name.equals(one.get("name"))).findFirst()
                    .ifPresent(one -> one.put("ok", true));
            System.out.println("  ✓ " + name);
        }

        @Override
        public void line(String text) {
            if (verbose && text != null && !text.isBlank()) {
                System.out.println("  │ " + text);
            }
        }

        @Override
        public void warn(String text) {
            System.out.println("  ! " + text);
        }

        @Override
        public void fail(String text, String raw) {
            System.out.println("✗ " + text);
            if (raw != null && !raw.isBlank() && !raw.equals(text)) {
                System.out.println(raw);
            }
        }

        @Override
        public void done(String text, Map<String, Object> extra) {
            System.out.println("✓ " + text + elapsed());
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public long elapsedSeconds() {
            return (System.currentTimeMillis() - started) / 1000;
        }

        @Override
        public List<Map<String, Object>> steps() {
            return List.copyOf(steps);
        }

        private String elapsed() {
            return "（" + elapsedSeconds() + " 秒）";
        }
    }
}
