package com.specflow.patch;

/**
 * 文本规范化工具。
 *
 * <p>存在的唯一理由：换行符是「AI 给的锚点匹配不上本地文件」的头号原因。
 * Windows 工作区是 {@code \r\n}，模型几乎总是回 {@code \n}，直接比对必然失败。
 *
 * <p>策略：<b>比较前统一抹平为 {@code \n}，写回时还原为目标文件原有的换行风格</b>。
 * 这样既保证匹配稳定，又不会把整个文件的行尾风格改掉。
 */
public final class TextNormalizer {

    public static final String LF = "\n";
    public static final String CRLF = "\r\n";

    private TextNormalizer() {
    }

    /**
     * 把所有换行风格统一成 {@code \n}。
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        if (text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace(CRLF, LF).replace("\r", LF);
    }

    /**
     * 探测文本的主要换行风格。出现任一 {@code \r\n} 即判定为 CRLF 文件。
     */
    public static String detectLineSeparator(String text) {
        if (text != null && text.contains(CRLF)) {
            return CRLF;
        }
        return LF;
    }

    /**
     * 把 {@code \n} 还原为指定换行风格。
     */
    public static String applyLineSeparator(String text, String separator) {
        if (text == null || text.isEmpty() || LF.equals(separator)) {
            return text == null ? "" : text;
        }
        return text.replace(LF, separator);
    }
}
