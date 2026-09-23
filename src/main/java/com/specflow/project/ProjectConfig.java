package com.specflow.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 项目级配置。对应 {@code <root>/.specflow/project.yaml}，一次配好、长期复用。
 *
 * <p>与 {@link com.specflow.spec.Spec} 的分工：
 * spec 描述「这一次改什么」，project 描述「这个项目怎么构建、怎么连模型」。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ProjectConfig(
        BuildConfig build,
        LlmConfig llm,
        SnapshotConfig snapshot
) {

    public static final ProjectConfig DEFAULT =
            new ProjectConfig(BuildConfig.EMPTY, LlmConfig.DEFAULT, SnapshotConfig.DEFAULT);

    @JsonCreator
    public static ProjectConfig of(
            @JsonProperty("build") BuildConfig build,
            @JsonProperty("llm") LlmConfig llm,
            @JsonProperty("snapshot") SnapshotConfig snapshot
    ) {
        return new ProjectConfig(
                build == null ? BuildConfig.EMPTY : build,
                LlmConfig.merge(llm),
                snapshot == null ? SnapshotConfig.DEFAULT : snapshot
        );
    }
}
