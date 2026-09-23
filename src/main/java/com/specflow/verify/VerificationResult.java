package com.specflow.verify;

/**
 * 一次校验的结果。
 *
 * <p>刻意区分 {@code SKIPPED} 与 {@code PASSED}：没有配置编译命令时，
 * 「没做校验」和「校验通过」是完全不同的事实。把跳过伪装成通过，
 * 就是在报告里说谎，而它会直接导致后续的自动重试逻辑判断错误。
 *
 * @param verifier 校验器名称，用于日志
 * @param status   结果状态
 * @param command  实际执行的命令，跳过时为空串
 * @param output   命令输出（失败时会被截断后喂回给模型）
 * @param kind     失败是谁的问题：{@link Kind#CODE} 让模型改代码去修，
 *                 {@link Kind#ENVIRONMENT} 只能由人去处理依赖/环境
 */
public record VerificationResult(
        String verifier,
        Status status,
        String command,
        String output,
        Kind kind
) {

    public enum Status {
        /** 通过。 */
        PASSED,
        /** 未通过，输出中应包含可供模型修正的错误信息。 */
        FAILED,
        /** 未执行——缺少配置或被 spec 显式关闭。 */
        SKIPPED
    }

    /**
     * 失败的责任方。
     *
     * <p>这个区分是<b>为了决定要不要再给模型几轮</b>：代码写错了，把编译输出喂回去它多半能修；
     * 项目里根本没有那个依赖，再给它六轮也修不好——它只会把用到那个包的地方删掉，
     * 于是编译过了、需求却没实现，<b>这比直接失败更糟</b>。
     */
    public enum Kind {
        /** 与失败责任无关（通过、跳过，或还没判过）。 */
        NONE,
        /** 模型改代码能解决。 */
        CODE,
        /** 改代码解决不了：缺依赖、编译级别、路径权限、工具找不到之类。 */
        ENVIRONMENT
    }

    public static VerificationResult passed(String verifier, String command, String output) {
        return new VerificationResult(verifier, Status.PASSED, command, output, Kind.NONE);
    }

    public static VerificationResult failed(String verifier, String command, String output) {
        return failed(verifier, command, output, Kind.NONE);
    }

    public static VerificationResult failed(String verifier, String command, String output, Kind kind) {
        return new VerificationResult(verifier, Status.FAILED, command, output, kind);
    }

    public static VerificationResult skipped(String verifier, String reason) {
        return new VerificationResult(verifier, Status.SKIPPED, "", reason, Kind.NONE);
    }

    public boolean passed() {
        return status == Status.PASSED;
    }

    public boolean failed() {
        return status == Status.FAILED;
    }

    public boolean skipped() {
        return status == Status.SKIPPED;
    }

    /** 这次失败改代码解决不了，得人去看依赖或环境。 */
    public boolean environmental() {
        return kind == Kind.ENVIRONMENT;
    }
}
