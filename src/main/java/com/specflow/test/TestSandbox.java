package com.specflow.test;

import com.specflow.core.Atomic;
import com.specflow.core.Cmd;
import com.specflow.core.Layout;
import com.specflow.core.Project;
import com.specflow.core.Progress;
import com.specflow.env.Compose;
import com.specflow.env.EnvConfig;
import com.specflow.env.EnvConfigLoader;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>给 ⑤ 拍一份副本，并让容器挂副本那一份</b>（不是挂真项目）。
 *
 * <h2>为什么非要有这一层</h2>
 * 测试 Agent 会在容器里装依赖、写脚本、删文件——那是"把环境弄成能验的样子"的正常动作。
 * 如果容器挂的是真项目，这些动作改的就是用户的代码；而"验过了"这三个字会被这些改动污染，
 * 用户也拿不回一个原样的项目。所以：<b>真项目只读挂进去的是副本</b>。
 *
 * <h2>它做四件事</h2>
 * <ol>
 *   <li>把项目树复制到 {@code flow/<id>/sandbox/<runId>}（排除 {@code .specflow} / {@code .git} /
 *       {@code target} / {@code node_modules} 这些"不属于源码"的东西）；</li>
 *   <li>用同一份 {@code env.yaml} 渲染一份 compose——<b>只有挂载源换成副本根</b>；</li>
 *   <li>{@code docker compose up -d --wait} 把它起来；</li>
 *   <li>收场时 {@code down -v} 并把副本删掉（下一轮从干净的一份开始）。</li>
 * </ol>
 *
 * <p>它 {@link AutoCloseable}：调用方用 try-with-resources，容器与副本都不会留在机器上。
 */
public final class TestSandbox implements AutoCloseable {

    /** 复制时跳过的东西（都不是被测源码）。 */
    private static final Set<String> SKIP = Set.of(
            Layout.DIR, ".git", "target", "build", "out", "node_modules", ".idea", ".gradle", "dist");

    private final Project project;
    private final Path root;
    private final Path composeFile;
    private final String composeName;
    private final EnvConfig config;

    private TestSandbox(Project project, Path root, Path composeFile, String composeName,
                        EnvConfig config) {
        this.project = project;
        this.root = root;
        this.composeFile = composeFile;
        this.composeName = composeName;
        this.config = config;
    }

    /**
     * 拍副本 + 起容器。
     *
     * @param runId 这一次运行的 id（副本目录名就是它）
     * @throws IllegalStateException 项目没声明环境、或者容器起不来——**当场说清**，
     *                               别让调用方跑到一半才发现命令无处可跑
     */
    public static TestSandbox prepare(Project project, String runId, Progress progress) {
        EnvConfig config = EnvConfigLoader.load(project.layout())
                .orElseThrow(() -> new IllegalStateException(
                        "这个项目没有 " + EnvConfigLoader.FILE + "：测试要在容器里跑，先回 ① 把环境声明写好"));
        Layout layout = project.layout();

        Path copy = layout.flow(currentFlowId(layout)).resolve("sandbox").resolve(runId);
        copyTree(project.root(), copy);

        String projectName = Compose.projectName(project.root()) + "-sandbox";
        Path composeFile = copy.resolve("compose.yaml");
        Atomic.write(composeFile, Compose.render(config, copy, projectName));
        // 沙箱也有自己的依赖缓存卷（与主环境那一个分开：⑤ 里装的东西不该污染 ①，反之亦然）
        com.specflow.env.Environment.ensureHomeVolume(Compose.homeVolume(projectName),
                config.image(), project.root());

        if (progress != null) {
            progress.step("起测试容器（挂副本）：docker compose up -d --wait", "", 600);
        }
        Cmd.Result up = Cmd.run(List.of("docker", "compose", "-p", projectName, "-f",
                        composeFile.toString(), "up", "-d", "--wait"),
                copy, null, 600, progress == null ? null : progress::line);
        if (!up.ok()) {
            if (progress != null) {
                progress.fail("测试容器没起来（" + up.summary() + "）：" + projectName, up.output());
            }
            throw new IllegalStateException("测试容器没起来（" + up.summary() + "）："
                    + System.lineSeparator() + up.output());
        }
        if (progress != null) {
            progress.stepDone("测试容器已起");
        }
        return new TestSandbox(project, copy, composeFile, projectName, config);
    }

    /** 副本根。 */
    public Path root() {
        return root;
    }

    /** 副本那份 compose。 */
    public Path composeFile() {
        return composeFile;
    }

    /** 在副本里跑命令的入口（测试 Agent 的 bash 走它）。 */
    public Sandbox shell() {
        return new Sandbox(project, composeFile, Compose.APP_SERVICE, composeName);
    }

    @Override
    public void close() {
        try {
            Cmd.run(List.of("docker", "compose", "-p", composeName, "-f", composeFile.toString(),
                    "down", "-v", "--remove-orphans"), root, null, 180, null);
        } catch (RuntimeException e) {
            // 收不掉不该盖住真正的结论（测试结论才是这一段要交的东西）
        }
        delete(root);
    }

    // ---------- 内部 ----------

    private static String currentFlowId(Layout layout) {
        String id = Atomic.read(layout.current(), "").strip();
        return id.isEmpty() ? "standalone" : id;
    }

    /** 复制项目树（跳过 {@link #SKIP} 里那几样）。 */
    private static void copyTree(Path from, Path to) {
        delete(to);
        try {
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (!dir.equals(from) && SKIP.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Path target = to.resolve(from.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("复制副本失败（" + from + " → " + to + "）：" + e.getMessage(), e);
        }
    }

    /** 删一棵树（删不掉就算了：它只是副本，下一次会被覆盖）。 */
    private static void delete(Path tree) {
        if (!Files.exists(tree)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (var stream = Files.walk(tree)) {
            all.addAll(stream.sorted(java.util.Comparator.reverseOrder()).toList());
        } catch (IOException e) {
            return;
        }
        for (Path one : all) {
            try {
                Files.deleteIfExists(one);
            } catch (IOException ignored) {
                // 有个别文件被容器占着：下一次 prepare 会先删再复制，删不掉会当场报出来
            }
        }
    }
}
