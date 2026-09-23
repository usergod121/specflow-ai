package com.specflow.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 快照与回滚配置。
 *
 * @param enabled 是否在写入前自动做快照；关掉意味着落盘失败时无法自动回滚
 * @param dir     快照备份目录（相对项目根）
 */
public record SnapshotConfig(
        boolean enabled,
        String dir
) {

    public static final String DEFAULT_DIR = ".specflow/snapshots";

    public static final SnapshotConfig DEFAULT = new SnapshotConfig(true, DEFAULT_DIR);

    @JsonCreator
    public static SnapshotConfig of(
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("dir") String dir
    ) {
        return new SnapshotConfig(
                enabled == null || enabled,
                dir == null || dir.isBlank() ? DEFAULT_DIR : dir.trim()
        );
    }
}
