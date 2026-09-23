package com.specflow.exception;

import java.util.List;

/**
 * AI 返回的补丁无法安全落到目标文件上。
 *
 * <p>触发条件：锚点找不到、锚点不唯一、块之间区间重叠、目标文件不在 targets 内等。
 * 出现该异常时一定尚未写入任何字节——校验阶段与写入阶段是严格分离的。
 */
public class PatchConflictException extends SpecflowException {

    /**
     * 冲突种类。每一种对应一类可操作的修复动作，便于 CLI 给出针对性提示。
     */
    public enum Kind {
        /** 补丁块没有声明目标路径。 */
        MISSING_TARGET_PATH,
        /** 目标路径不在 spec.targets 中，或越出项目根目录。 */
        TARGET_NOT_ALLOWED,
        /** SEARCH 锚点在目标文件中不存在（缩进或内容不一致）。 */
        ANCHOR_NOT_FOUND,
        /** SEARCH 锚点在目标文件中出现多次，无法判断该改哪一处。 */
        ANCHOR_AMBIGUOUS,
        /** 同一文件内多个改动区间互相重叠。 */
        EDIT_OVERLAP,
        /** 补丁块想整文件写入，但目标文件已经存在——引擎不覆盖已有文件。 */
        TARGET_EXISTS,
        /** 补丁块给了 SEARCH 锚点，但目标文件不存在——锚点无处可寻。 */
        TARGET_MISSING,
        /** AI 的响应里解析不出任何补丁块。 */
        NO_BLOCK_PARSED
    }

    private final Kind kind;
    private final List<String> details;

    public PatchConflictException(Kind kind, String message) {
        this(kind, message, List.of());
    }

    public PatchConflictException(Kind kind, String message, List<String> details) {
        super(render(kind, message, details));
        this.kind = kind;
        this.details = List.copyOf(details);
    }

    public Kind kind() {
        return kind;
    }

    public List<String> details() {
        return details;
    }

    private static String render(Kind kind, String message, List<String> details) {
        StringBuilder sb = new StringBuilder("补丁落盘失败[").append(kind).append("]：").append(message);
        if (details != null) {
            for (String detail : details) {
                sb.append(System.lineSeparator()).append("  · ").append(detail);
            }
        }
        return sb.toString();
    }
}
