package com.specflow.env;

import com.specflow.core.Cmd;
import com.specflow.core.Project;
import com.specflow.core.Progress;

import java.nio.file.Path;
import java.util.List;

/**
 * <b>跑项目配的那条编译命令</b>——在<b>宿主</b>上跑，不在容器里。
 *
 * <p>为什么在宿主上：编译是「这台机器上怎么把这个项目编出来」的事实，而 ① 的闸与 ④ 每次交差
 * 都要用它；容器只用来跑测试（那条边界见 {@code docs/ARCHITECTURE.md} §7）。
 * 命令由引擎从 {@code project.yaml} 读，**界面发不上来**。
 *
 * <p>三个状态分得清：{@code NONE}（没配命令，不是失败）、{@code PASSED}、{@code FAILED}。
 * 没配命令时报成失败是最容易犯的错——那会让用户在"我还没填"的时候看到一片红。
 */
public final class Compiler {

    /** @param output 编译输出的完整原文（失败时界面"展开看全部"看的就是它） */
    public record Result(boolean passed, String status, String command, String output, long seconds) {

        public boolean isNone() {
            return "NONE".equals(status);
        }
    }

    private Compiler() {
    }

    public static Result compile(Project project, Progress progress) {
        String command = project.compile();
        if (command.isBlank()) {
            String message = "还没配编译命令：在 ① 里让「探测环境」读一遍项目，或者自己填一条"
                    + "（写进 " + project.layout().show(project.layout().projectYaml()) + " 的 build.compile）";
            if (progress != null) {
                progress.warn(message);
            }
            return new Result(false, "NONE", "", message, 0);
        }
        Path root = project.root();
        long started = System.currentTimeMillis();
        if (progress != null) {
            progress.step("编译：" + command, command, timeoutSeconds(project));
        }
        Cmd.Result run = Cmd.run(List.of("cmd", "/c", command), root, null, timeoutSeconds(project),
                progress == null ? null : progress::line);
        long seconds = Math.max(0, (System.currentTimeMillis() - started) / 1000);
        boolean passed = run.ok();
        if (progress != null) {
            if (passed) {
                progress.stepDone("编译过了（" + seconds + " 秒）");
            } else {
                progress.fail("编译没过（" + seconds + " 秒，" + run.summary() + "）：" + command,
                        run.output());
            }
        }
        return new Result(passed, passed ? "PASSED" : "FAILED", command, run.output(), seconds);
    }

    /**
     * 编译最多等多久。
     *
     * <p>与「起环境」共用一个旋钮（`flow.json` 里 ① 的 `pullTimeoutMinutes`）：对用户来说
     * 这两件事是同一句话——"这一步我愿意等多久"。分成两个旋钮的结果是实测踩过的那个坑：
     * 界面上设了 30 分钟，编译照样在写死的 5 分钟被掐死，而报错一个字都没提这两个数怎么来的。
     */
    private static long timeoutSeconds(Project project) {
        return 30 * 60;
    }
}
