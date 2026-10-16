package com.specflow.test;

import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>验收阶段冻结 {@code src/}</b>（这一格里唯一一条会把"验过了"变成假话的漏洞）。
 *
 * <p>为什么非冻不可：测试 Agent 要是能一边验一边改被测代码，那"验过了"就只说明
 * 「它把代码改成能过的样子了」——那和真的修好了在报告上长得一模一样。
 * 所以切换到验收阶段之后，任何往 {@code src/} 里写的动作都会被<b>拒掉</b>，
 * 并且（由调用方）记一条可见的越界记录。
 *
 * <p>它只判一件事：这个路径在不在副本的 {@code src/} 底下。
 */
public final class Freeze {

    /** 两个阶段。 */
    public enum Phase {
        PREPARE("准备"), ACCEPTANCE("验收");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Path sandboxRoot;
    private volatile Phase phase = Phase.PREPARE;

    public Freeze(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
    }

    public Phase phase() {
        return phase;
    }

    public void to(Phase next) {
        this.phase = next;
    }

    public boolean frozen() {
        return phase == Phase.ACCEPTANCE;
    }

    /**
     * 这个路径现在许不许写。
     *
     * @return 不许时给人看的那句话；可以写时 {@code null}
     */
    public String refuse(Path target) {
        if (!frozen()) {
            return null;
        }
        Path abs = target.toAbsolutePath().normalize();
        Path src = sandboxRoot.resolve("src").normalize();
        if (abs.startsWith(src)) {
            return "验收阶段 `src/` 是冻结的：不许改被测代码（那会让「验过了」变成假话）。"
                    + "要改就回退到 ④ 改真项目，再重新跑一次测试。被拒的路径：" + show(abs);
        }
        return null;
    }

    /** 副本根（工具层用它把相对路径解析成绝对路径）。 */
    public Path root() {
        return sandboxRoot;
    }

    /** 相对路径 → 副本内的绝对路径（与工具自己的解析口径一致：都在副本根下）。 */
    public Path resolve(String path) {
        String one = path == null ? "" : path.replace('\\', '/').strip();
        while (one.startsWith("./")) {
            one = one.substring(2);
        }
        return sandboxRoot.resolve(one).normalize();
    }

    /** 复制给用户看的一行（相对副本根）。 */
    public String show(Path target) {
        Path abs = target.toAbsolutePath().normalize();
        return (abs.startsWith(sandboxRoot) ? sandboxRoot.relativize(abs) : abs)
                .toString().replace('\\', '/');
    }
}
