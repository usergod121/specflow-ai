package com.specflow.test;

import com.specflow.agent.Shell;
import com.specflow.core.Progress;
import com.specflow.env.Capabilities;

/**
 * <b>⑤ 开工前的预检</b>：这个容器能不能把这个项目构建出来？
 *
 * <h2>为什么值得单独一步（这一步是最划算的改动）</h2>
 * 一次实测：容器里没有 Maven，⑤ 的 Agent 用 <b>60 轮里的 28 轮</b>去下载安装它，
 * 最后一条用例都没验成。整轮跑了 34 分钟，而"容器里没有 mvn"这件事，
 * <b>一条命令就能问出来</b>（{@code command -v mvn}，几百毫秒）。
 *
 * <p>所以现在的顺序是：先问一句，缺东西就<b>当场失败并指名 ①</b>，而不是让它自己想办法。
 * 这不是"更严格"，是"更早"——失败得越早，用户看到的建议越准，浪费的轮次越少。
 *
 * <p>与 {@link Capabilities} 的分工：那个是 ① 起完环境后的<b>自证</b>（我还要真跑一次构建），
 * 这个是 ⑤ 开工前的<b>复检</b>（只问工具在不在，因为 ① 已经真构建过了）。
 * 两层都留着是有意的：用户可能换过镜像、也可能跳过 ① 直接来 ⑤。
 */
public final class Preflight {

    /** 只问一句 {@code command -v}，不该拖住界面。 */
    private static final long PROBE_SECONDS = 60;

    private Preflight() {
    }

    /**
     * @param ok      能不能开工
     * @param tool    从编译命令里认出来的构建工具（认不出是空串）
     * @param message 不能开工时的完整人话（直接进事件流/界面）
     */
    public record Verdict(boolean ok, String tool, String message) {
    }

    /**
     * @param shell        沙箱的命令出口（{@link TestSandbox#shell()}）
     * @param buildCommand 项目配的编译命令（{@code project.yaml} 的 build.compile）
     */
    public static Verdict check(Shell shell, String buildCommand) {
        String tool = Capabilities.toolOf(buildCommand);
        if (tool.isEmpty()) {
            // 认不出工具就别拦：拦了也只会给一句含糊的话，比不拦更糟
            return new Verdict(true, "", "");
        }
        Shell.Result probe;
        try {
            probe = shell.run("command -v " + tool, null, PROBE_SECONDS, null);
        } catch (RuntimeException e) {
            return new Verdict(false, tool, "问不了容器（" + e.getMessage() + "）：先回 ① 起一次环境");
        }
        if (probe.ok()) {
            return new Verdict(true, tool, "");
        }
        return new Verdict(false, tool, "容器里没有 `" + tool + "`，而项目配的编译命令是 `"
                + buildCommand + "`。这一格不会替你去装它（容器是一次性的，装了下一轮也不剩）——"
                + "改 .specflow/env.yaml 的 image，换一个自带 `" + tool + "` 的镜像"
                + "（Java 项目常用 maven:3.9-eclipse-temurin-17），或者在 init 里装一次，"
                + "然后回 ① 重新起环境（① 会在容器里真构建一次来验）。");
    }

    /** 预检没过时，把这句话也写进事件流（用户翻旧账时要看得到原因）。 */
    public static void report(Progress progress, Verdict verdict) {
        if (progress != null && !verdict.ok()) {
            progress.fail("⑤ 开工前预检没过（缺 " + verdict.tool() + "）", verdict.message());
        }
    }
}
