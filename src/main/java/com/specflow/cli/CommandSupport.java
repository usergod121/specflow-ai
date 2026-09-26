package com.specflow.cli;

import com.specflow.exception.SpecflowException;
import com.specflow.project.ProjectConfig;
import com.specflow.project.ProjectConfigLoader;
import com.specflow.snapshot.WorkspaceSnapshot;
import com.specflow.spec.Spec;
import com.specflow.spec.SpecLoader;
import com.specflow.util.SafePathResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 命令行的公共前置步骤：把「项目根目录 + spec 路径」变成加载好的对象。
 *
 * <p>抽出来是因为 {@code run} 与 {@code validate} 的前半段完全一样，
 * 而这类「两个命令各写一遍」的重复，最终一定会演变成两边校验规则不一致。
 */
final class CommandSupport {

    static final String DEFAULT_SPEC = "spec.yaml";
    static final String DEFAULT_PROJECT = ".";

    private CommandSupport() {
    }

    static Path resolveProject(Path projectDir) {
        Path root = projectDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new SpecflowException("项目目录不存在: " + root);
        }
        return root;
    }

    static ProjectConfig loadProjectConfig(Path root) {
        return new ProjectConfigLoader().load(root);
    }

    /**
     * @param specPath 相对项目根目录的 spec 路径
     */
    static Spec loadSpec(Path root, String specPath) {
        Path file = root.resolve(specPath).normalize();
        return new SpecLoader().load(file);
    }

    /**
     * 磁盘上还没被处置的快照（按时间从早到晚）。
     *
     * <p>{@code accept} 与 {@code rollback} 找的是同一批东西，判据也必须一样：
     * 分头各写一遍，迟早会变成「一个命令说有、另一个说没有」。
     */
    static List<WorkspaceSnapshot> undisposedSnapshots(Path root, ProjectConfig project) {
        SafePathResolver resolver = new SafePathResolver(root);
        return WorkspaceSnapshot.undisposed(resolver, resolver.resolve(project.snapshot().dir()));
    }
}
