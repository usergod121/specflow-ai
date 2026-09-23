package com.specflow.review;

import com.specflow.exception.SpecflowException;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析检查阶段的响应。
 *
 * <p>与 {@link com.specflow.patch.PatchParser} 同一套思路：逐行扫描标记、
 * 容忍块前后的解释性文字、<b>不猜测</b>。
 *
 * <p>唯一的宽容之处在缺失清单那一行：五个字段用竖线分隔，少写几个字段
 * 仍然能用（只取能认出来的部分）。因为这一块是<b>给人看的提示</b>，
 * 格式不完美也不该让整次检查失败——真正必须存在的只有流程图。
 */
public final class PlanParser {

    private static final String FIELD_SEPARATOR = "|";

    /**
     * @throws SpecflowException 响应里没有 FLOW 块——那是唯一不可缺的东西
     */
    public PlanReview parse(String response) {
        if (response == null || response.isBlank()) {
            throw new SpecflowException("模型响应为空，无法解析出检查结果");
        }
        List<String> lines = List.of(response.replace("\r\n", "\n").split("\n", -1));

        String summary = block(lines, ReviewProtocol.SUMMARY_MARKER).strip();
        String flowchart = block(lines, ReviewProtocol.FLOW_MARKER).strip();
        List<PlanReview.MissingItem> missing = parseMissing(block(lines, ReviewProtocol.MISSING_MARKER));

        if (flowchart.isEmpty()) {
            throw new SpecflowException("模型的检查结果里没有 '" + ReviewProtocol.FLOW_MARKER
                    + "' 块，无法给出实现方案；请重试一次");
        }
        return PlanReview.of(summary, stripFence(flowchart), missing);
    }

    // ---------- 分块 ----------

    /**
     * 取出某个标记块的内容。
     *
     * <p>结束标记按「以 {@code >>>>>>>} 开头」识别，不要求后面的名字完全匹配——
     * 模型偶尔会把 {@code >>>>>>> FLOW} 写成 {@code >>>>>>>}。
     */
    private String block(List<String> lines, String marker) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(marker)) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        int end = lines.size();
        for (int i = start; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(ReviewProtocol.END_SUFFIX)) {
                end = i;
                break;
            }
        }
        return String.join("\n", lines.subList(start, end));
    }

    /** 模型有时会把流程图整个包在 markdown 围栏里，剥掉一层。 */
    private String stripFence(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (lines.size() >= 2 && lines.get(0).strip().startsWith("```")
                && lines.get(lines.size() - 1).strip().startsWith("```")) {
            return String.join("\n", lines.subList(1, lines.size() - 1)).strip();
        }
        return text;
    }

    // ---------- 缺失清单 ----------

    /**
     * 每行一条：{@code 缺什么 | 严重度 | 影响（技术） | 影响（业务） | 建议默认值}。
     *
     * <p>宽容到底：字段少写几个、严重度写了个认不出来的词、前面加了 `-`，都不该让整次检查失败。
     * 但<b>不猜</b>——认不出来的严重度就标成「未标」，而不是替它当成阻断（那会白白拦住人）。
     */
    private List<PlanReview.MissingItem> parseMissing(String block) {
        List<PlanReview.MissingItem> items = new ArrayList<>();
        for (String line : block.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || isNothingMissing(trimmed)) {
                continue;
            }
            // 去掉模型可能加的列表符号
            trimmed = trimmed.replaceFirst("^[-*•]\\s*", "").strip();
            String[] parts = trimmed.split("\\" + FIELD_SEPARATOR);
            items.add(item(parts));
        }
        // 越严重的越靠前，界面上一眼就能看到该补哪几项
        return PlanReview.bySeverity(items);
    }

    /**
     * 把一行拆成一条缺失项。
     *
     * <p>老的三段格式（{@code 缺什么 | 为什么需要 | 建议怎么补}）还会出现——用户自己的模板里
     * 可能就写着老格式。认法只有一条：<b>新格式的第二栏一定是严重度词</b>，
     * 三段且第二段不是严重度，那就按老格式摆（为什么→技术影响，怎么补→建议默认值），
     * 否则老格式那一行的后两段会整体错位一格，「建议默认值」栏会显示成一句影响描述。
     */
    private PlanReview.MissingItem item(String[] parts) {
        PlanReview.MissingItem.Severity second = PlanReview.MissingItem.Severity.parse(field(parts, 1));
        if (parts.length == 3 && second == PlanReview.MissingItem.Severity.UNKNOWN) {
            return new PlanReview.MissingItem(field(parts, 0),
                    PlanReview.MissingItem.Severity.UNKNOWN, field(parts, 1), "", field(parts, 2));
        }
        return new PlanReview.MissingItem(field(parts, 0), second,
                field(parts, 2), field(parts, 3), field(parts, 4));
    }

    private String field(String[] parts, int index) {
        return parts.length > index ? parts[index].strip() : "";
    }

    private boolean isNothingMissing(String line) {
        String stripped = line.replaceFirst("^[-*•]\\s*", "").strip();
        return stripped.equals(ReviewProtocol.NOTHING_MISSING)
                || stripped.equalsIgnoreCase("none")
                || stripped.equals("无。")
                || stripped.equals("没有了");
    }
}
