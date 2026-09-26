package com.specflow.spec;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 落盘后的自检配置。
 *
 * <p>当前只做「编译通过」这一最低门槛——它挡得住绝大多数低级错误。
 * 更重的验证（单测、黑盒用例）由后续的测试 Agent 承担，通过
 * {@code com.specflow.verify.Verifier} 扩展点接入，不在此处声明。
 *
 * @param compile        是否在落盘后执行编译自检
 * @param compileCommand 编译命令；留空则回退到 {@code .specflow/project.yaml} 的配置
 * @param maxRetry       编译失败后携带报错重试的次数；0 表示只跑一次不重试。
 *                       有施工单时它是<b>每一步</b>的上限，不是整次运行的
 * @param maxRounds      整次运行允许调用模型的总次数；0 表示自动（{@link #roundBudget}）。
 *                       和 {@code maxRetry} 是两道不同的闸：那个管「每一步试几次」，
 *                       这个管「一整单最多烧几次调用」
 */
public record VerifySpec(
        boolean compile,
        String compileCommand,
        int maxRetry,
        int maxRounds
) {

    /**
     * 默认重试 6 次。
     *
     * <p>定得比直觉中的「一两次」高是有原因的：每一轮都是从原始代码重新来过，
     * 模型第一次往往只修掉最显眼的那个错误，第二次才暴露出被它掩盖的第二个。
     * 而中途随时可以人工中断——**上限给宽、出口给人**，比上限卡死更划算。
     */
    public static final int DEFAULT_MAX_RETRY = 6;

    /** 没配总轮次时，每一步给几轮。定成 3 是因为「修第一个错、暴露第二个错、第三次收尾」是常态。 */
    public static final int ROUNDS_PER_STEP = 3;

    /** {@link #maxRounds} 的「没配」取值：按步数自动算。 */
    public static final int AUTO_ROUNDS = 0;

    public static final VerifySpec DEFAULT = new VerifySpec(true, null, DEFAULT_MAX_RETRY, AUTO_ROUNDS);

    @JsonCreator
    public static VerifySpec of(
            @JsonProperty("compile") Boolean compile,
            @JsonProperty("compile-command") @JsonAlias("compile_command") String compileCommand,
            @JsonProperty("max-retry") @JsonAlias("max_retry") Integer maxRetry,
            @JsonProperty("max-rounds") @JsonAlias("max_rounds") Integer maxRounds
    ) {
        return new VerifySpec(
                compile == null || compile,
                blankToNull(compileCommand),
                maxRetry == null ? DEFAULT_MAX_RETRY : Math.max(0, maxRetry),
                maxRounds == null ? AUTO_ROUNDS : Math.max(0, maxRounds)
        );
    }

    /**
     * 整次运行允许调用模型几次。
     *
     * <p>自动值 = {@value #ROUNDS_PER_STEP} × 步数。为什么要有这道闸：
     * 每步的重试上限是「每步各自算」的，施工单越长，最坏情况下的总调用次数就越多，
     * 而用户是按次付费的。没有总预算，一份 7 步的施工单最坏能烧掉 7 × (6 + 冲突重试) 次。
     *
     * <p>{@code steps <= 1} 表示这次<b>没有施工单</b>（单步执行），此时自动值不设闸：
     * 那条路是老行为，它本来就被「补丁冲突 3 次 / 每步 maxRetry 次」两份预算兜住了，
     * 再套一个 {@code 3 × 1 = 3} 轮只会比今天更紧。手写的 {@code max-rounds} 仍然生效。
     *
     * @param steps 施工单的步数；没有施工单时传 1
     * @return 允许的总轮次；{@code 0} 表示不设这道闸
     */
    public int roundBudget(int steps) {
        if (maxRounds > 0) {
            return maxRounds;
        }
        return steps > 1 ? ROUNDS_PER_STEP * steps : 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---------- 输出侧的字段名 ----------
    // 读取端认的是 kebab-case，而序列化默认用访问器名（compileCommand、maxRetry）。
    // 不显式声明的话，写出来的 YAML 自己读不回去——存下来就打不开了。

    @JsonProperty("compile-command")
    public String compileCommand() {
        return compileCommand;
    }

    @JsonProperty("max-retry")
    public int maxRetry() {
        return maxRetry;
    }

    @JsonProperty("max-rounds")
    public int maxRounds() {
        return maxRounds;
    }
}
