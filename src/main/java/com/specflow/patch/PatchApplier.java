package com.specflow.patch;

import com.specflow.util.ProjectFiles;
import com.specflow.util.SafePathResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 落盘执行器——两阶段提交的「第二阶段」。
 *
 * <p>职责边界刻意收窄：<b>这里不做任何业务判断</b>。
 * 锚点是否存在、是否唯一、是否越界，全部在 {@link PatchStrategy} 阶段完成。
 * 因此本类只可能因为磁盘原因失败（只读、无权限、磁盘满），
 * 不会因为「模型写错了」而失败——这正是两阶段提交的价值。
 *
 * <p>换行符处理：读入时探测原文件风格，比较与应用都在 {@code \n} 域完成，
 * 写回时还原为原风格。新建文件统一使用 {@code \n}，避免写入机器相关的行尾。
 */
public final class PatchApplier {

    private final SafePathResolver pathResolver;

    public PatchApplier(SafePathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    /**
     * 执行计划中的全部改动。
     *
     * <p>计划的合法性已由策略层保证，因此这里不设「部分成功」语义：
     * 一旦开始写入就尽量写完，遇到磁盘异常直接抛出并交由上层回滚快照。
     */
    public List<FileChange> apply(PatchPlan plan) {
        List<FileChange> changes = new ArrayList<>();
        for (Path file : plan.files()) {
            changes.add(applyFile(plan, file));
        }
        return changes;
    }

    private FileChange applyFile(PatchPlan plan, Path file) {
        String shown = pathResolver.relativize(file);
        boolean exists = Files.isRegularFile(file);

        String original = exists ? ProjectFiles.read(file, shown) : "";
        String separator = TextNormalizer.detectLineSeparator(original);
        String normalized = TextNormalizer.normalize(original);

        String rendered = plan.render(file, normalized);
        String output = TextNormalizer.applyLineSeparator(rendered, separator);

        ProjectFiles.write(file, output, shown);

        // 差异在这里算，因为原始内容此刻正好还在手上——换个地方算就得再读一次磁盘。
        String diff = TextDiff.unified(original, output);
        return new FileChange(file, shown, !exists,
                output.getBytes(StandardCharsets.UTF_8).length, diff);
    }

    /**
     * 单个文件的落盘结果。
     *
     * @param file      绝对路径
     * @param relative  相对项目根的 POSIX 路径，用于日志
     * @param created   是否为新建文件
     * @param bytes     写入的字节数
     * @param diff      行级差异：每行以空格（上下文）、减号（删除）、加号（新增）开头；
     *                  内容没有变化时为空串
     */
    public record FileChange(Path file, String relative, boolean created, int bytes, String diff) {

        public String describe() {
            return (created ? "新建 " : "修改 ") + relative + " (" + bytes + " 字节)";
        }
    }
}
