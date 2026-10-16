package com.specflow.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 工具层的路径边界：模型给的路径<b>必须落在允许的根之内</b>。
 *
 * <p>允许根由装配方给（开发 Agent 给项目根、测试 Agent 给沙箱根），工具自己不知道是哪一个。
 *
 * <p>三条从宽，其余一律拒：
 * <ol>
 *   <li><b>拒绝对路径</b>（{@code C:\...}、{@code /etc/...}）：模型写绝对路径只有两种可能——
 *       笔误，或试探边界；</li>
 *   <li><b>拒绝任何 {@code ..} 段</b>，<b>不是</b>规范化之后放行：{@code a/../b} 这种写法
 *       看起来还在根里，但它说明模型对路径的理解是错的，该看见一句明确的拒绝；</li>
 *   <li><b>拒软链/联接逃逸</b>：前缀比对只看字符串，而根下的一个联接能指向磁盘上任何地方。
 *       Windows 的目录联接在 Java 里 {@code isSymbolicLink} 是 false，所以判据只能是
 *       {@code toRealPath()} 之后是否仍在根下。</li>
 * </ol>
 */
public final class Roots {

    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:");

    private final Path root;
    private final Path real;

    public Roots(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("允许根不能为空：工具不知道能碰哪里就等于哪里都能碰");
        }
        this.root = root.toAbsolutePath().normalize();
        Path resolved = realOrNull(this.root);
        this.real = resolved == null ? this.root : resolved;
    }

    public Path root() {
        return root;
    }

    /** 读操作的目标；{@code path} 留空 = 根目录本身。 */
    public Path read(String path) {
        return resolve(path, true);
    }

    /** 写操作的目标；不许是根目录本身（那是「整棵树都归你写」的意思，本层不表达这种授权）。 */
    public Path write(String path) {
        return resolve(path, false);
    }

    /** 相对根的展示路径（永远是正斜杠）；根之外的原样给出。 */
    public String show(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        return abs.startsWith(root)
                ? root.relativize(abs).toString().replace('\\', '/')
                : abs.toString().replace('\\', '/');
    }

    /**
     * 密钥文件一律不给读写（fail closed，判据写死在这里，不看模型怎么说）。
     *
     * <p>密钥只从环境变量与 {@code .specflow/local.env} 由引擎自己取：
     * 它一旦进了模型上下文，就会跟着进留档、进事件流、进副本。
     */
    public static boolean secret(String path) {
        String lower = path == null ? "" : path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return lower.equals("local.env") || lower.endsWith("/local.env")
                || lower.endsWith(".env") || lower.endsWith(".key") || lower.endsWith(".pem");
    }

    private Path resolve(String path, boolean allowRoot) {
        String cleaned = path == null ? "" : path.strip();
        if (cleaned.isEmpty()) {
            if (allowRoot) {
                return root;
            }
            throw new IllegalArgumentException("要写哪个文件？路径不能为空");
        }
        if (cleaned.startsWith("/") || cleaned.startsWith("\\") || DRIVE.matcher(cleaned).find()) {
            throw new IllegalArgumentException("只接受项目内的相对路径，收到：" + cleaned);
        }
        for (String segment : cleaned.split("[\\\\/]")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("路径里不许出现 ..（收到：" + cleaned + "）");
            }
        }
        Path target = root.resolve(cleaned).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("路径越出了允许的根：" + cleaned);
        }
        if (target.equals(root)) {
            throw new IllegalArgumentException("不能写根目录本身");
        }
        rejectLinkEscape(cleaned, target);
        return target;
    }

    /**
     * 解「最近的已存在祖先」的真实路径：新文件自己不存在，但它所在的目录可能是别人放进来的链接。
     * 解不开（坏链、权限不足）就放行，让后面的读写去报那个更直接的原因。
     */
    private void rejectLinkEscape(String cleaned, Path target) {
        Path existing = target;
        while (existing != null && !existing.equals(root)
                && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        Path resolved = existing == null ? null : realOrNull(existing);
        if (resolved != null && !resolved.startsWith(real)) {
            throw new IllegalArgumentException("路径经软链/联接指向了允许的根之外：" + cleaned);
        }
    }

    private static Path realOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }
}
