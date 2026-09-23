package com.specflow.util;

import java.nio.file.Path;

/**
 * 把 spec 中的相对路径解析到项目根之下，并拒绝任何越界写法。
 *
 * <p>存在的理由：spec.yaml 可能写 {@code ../../etc/passwd} 或绝对路径。
 * 引擎在写入前必须保证目标落在项目内，否则一次误配就能改到仓库外。
 */
public final class SafePathResolver {

    private final Path root;

    public SafePathResolver(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /**
     * 解析相对路径并校验其位于根目录之下。
     *
     * @param relative spec 中声明的目标路径
     * @return 归一化后的绝对路径
     * @throws IllegalArgumentException 路径为绝对路径、为空、或逃逸出根目录
     */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path candidate = Path.of(relative.trim());
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException("只接受相对路径，收到绝对路径: " + relative);
        }
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("路径越出项目根目录: " + relative);
        }
        if (resolved.equals(root)) {
            throw new IllegalArgumentException("路径指向项目根目录本身: " + relative);
        }
        return resolved;
    }

    /**
     * 把绝对路径还原为相对项目根的 POSIX 风格路径，用于日志与追溯锚点。
     */
    public String relativize(Path absolute) {
        Path normalized = absolute.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return normalized.toString();
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }
}
