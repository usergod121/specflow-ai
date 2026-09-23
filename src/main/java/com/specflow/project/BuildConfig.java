package com.specflow.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 项目级构建命令。对一个项目来说是固定的，因此放在 {@code .specflow/project.yaml} 而不是每次的 spec 里。
 *
 * @param compile 编译命令，如 {@code mvn -q -DskipTests compile}
 * @param lint    静态检查命令，可为空
 * @param test    测试命令，可为空（预留给后续的测试 Agent）
 */
public record BuildConfig(
        String compile,
        String lint,
        String test
) {

    public static final BuildConfig EMPTY = new BuildConfig(null, null, null);

    @JsonCreator
    public static BuildConfig of(
            @JsonProperty("compile") String compile,
            @JsonProperty("lint") String lint,
            @JsonProperty("test") String test
    ) {
        return new BuildConfig(blankToNull(compile), blankToNull(lint), blankToNull(test));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
