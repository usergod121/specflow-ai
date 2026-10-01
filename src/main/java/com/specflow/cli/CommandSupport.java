package com.specflow.cli;

import com.specflow.exception.SpecflowException;
import com.specflow.history.RunStore;
import com.specflow.project.ProjectConfig;
import com.specflow.project.ProjectConfigLoader;
import com.specflow.spec.Spec;
import com.specflow.spec.SpecLoader;

import java.nio.file.Files;
import java.nio.file.Path;

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
     * 这个项目的运行留档在哪儿。
     *
     * <p>位置只有这一处算：{@code RunService}、{@code accept}/{@code rollback} 各自
     * {@code resolve} 一遍的话，某一天改了目录名就会变成「一条路径写、另一条路径读」——
     * 而它的表现是「留档里什么都没有」，看不出是路径错了。
     */
    static RunStore runStore(Path root) {
        return new RunStore(root.resolve(RunStore.DEFAULT_DIR));
    }
}
