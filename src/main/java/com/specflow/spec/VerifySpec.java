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
 * @param maxRetry       编译失败后携带报错重试的次数；0 表示只跑一次不重试
 */
public record VerifySpec(
        boolean compile,
        String compileCommand,
        int maxRetry
) {

    /**
     * 默认重试 6 次。
     *
     * <p>定得比直觉中的「一两次」高是有原因的：每一轮都是从原始代码重新来过，
     * 模型第一次往往只修掉最显眼的那个错误，第二次才暴露出被它掩盖的第二个。
     * 而中途随时可以人工中断——**上限给宽、出口给人**，比上限卡死更划算。
     */
    public static final int DEFAULT_MAX_RETRY = 6;

    public static final VerifySpec DEFAULT = new VerifySpec(true, null, DEFAULT_MAX_RETRY);

    @JsonCreator
    public static VerifySpec of(
            @JsonProperty("compile") Boolean compile,
            @JsonProperty("compile-command") @JsonAlias("compile_command") String compileCommand,
            @JsonProperty("max-retry") @JsonAlias("max_retry") Integer maxRetry
    ) {
        return new VerifySpec(
                compile == null || compile,
                blankToNull(compileCommand),
                maxRetry == null ? DEFAULT_MAX_RETRY : Math.max(0, maxRetry)
        );
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
}
