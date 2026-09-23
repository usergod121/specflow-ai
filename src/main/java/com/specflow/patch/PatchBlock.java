package com.specflow.patch;

/**
 * 模型返回的一个补丁块。
 *
 * <p>三种形态：
 * <ul>
 *   <li>{@code search} 非空 —— 增量替换，要求锚点在目标文件里唯一命中</li>
 *   <li>{@code search} 为空 —— 整文件写入（仅 {@code mode=create} 允许）</li>
 * </ul>
 *
 * <p>{@code search}/{@code replace} 内部文本此时仍保留模型返回的原始换行符，
 * 由匹配阶段统一规范化，避免在解析阶段就丢失信息。
 *
 * @param index   在本次响应中的序号，从 0 开始，用于日志定位
 * @param path    spec 相对路径，来自 SEARCH 标记行
 * @param search  锚点原文
 * @param replace 替换后的文本
 */
public record PatchBlock(
        int index,
        String path,
        String search,
        String replace
) {

    public PatchBlock {
        if (path == null) {
            throw new IllegalArgumentException("path 不能为 null");
        }
        if (search == null || replace == null) {
            throw new IllegalArgumentException("search/replace 不能为 null");
        }
    }

    /**
     * 是否为整文件写入块。
     */
    public boolean isFullWrite() {
        return search.isEmpty();
    }
}
