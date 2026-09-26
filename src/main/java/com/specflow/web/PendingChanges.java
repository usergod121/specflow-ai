package com.specflow.web;

import com.specflow.snapshot.WorkspaceSnapshot;

import java.util.List;

/**
 * 「磁盘上有一份改动还没被处置」的界面视图。
 *
 * <p>它是现算的：逐个比对快照里的原文副本与当前文件，算出到底动了哪些文件。
 * 不另存一份改动清单——清单可以推导，存下来只会多一处可能和磁盘对不上的状态。
 *
 * @param present   有没有待处置的改动
 * @param id        快照目录名（带后缀），界面显示与日志用
 * @param canAccept true = 校验通过的改动，接受和撤回都能选；
 *                  false = 上一次是被打断的，只能选「恢复原样」或「保留当前内容（未经校验）」
 * @param summary   给人看的一句话摘要
 * @param files     真正变了的内容
 */
public record PendingChanges(
        boolean present,
        String id,
        boolean canAccept,
        String summary,
        List<File> files
) {

    public record File(String path, boolean created, String diff) {
    }

    public static PendingChanges none() {
        return new PendingChanges(false, null, false, "没有待处置的改动", List.of());
    }

    public static PendingChanges of(WorkspaceSnapshot snapshot) {
        List<File> files = snapshot.changes().stream()
                .map(change -> new File(change.path(), change.created(), change.diff()))
                .toList();
        long created = files.stream().filter(File::created).count();
        String summary = files.isEmpty()
                ? "磁盘上没留下改动"
                : "%d 个文件：新增 %d、修改 %d".formatted(files.size(), created, files.size() - created);
        return new PendingChanges(true, snapshot.directory().getFileName().toString(),
                snapshot.isPending(), summary, files);
    }
}
