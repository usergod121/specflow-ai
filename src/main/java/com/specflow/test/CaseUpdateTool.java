package com.specflow.test;

import com.specflow.agent.Tool;
import com.specflow.agent.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code case_update}：把一条用例的结论写回清单。
 *
 * <p>三条硬要求（写成校验，不靠提示词）：
 * <ol>
 *   <li><b>id 必须是清单里有的</b>：它自己编一个 id 等于在验一件不存在的事；</li>
 *   <li><b>PASS 必须带 evidence</b>：没有证据的"通过"和不验是一样的，而它看起来更可信；</li>
 *   <li><b>非 PASS 必须带 note</b>：失败/报错要说清哪儿不对，否则下一轮开发无从下手。</li>
 * </ol>
 * 状态只认那三个终态（{@code PASS} / {@code FAIL} / {@code ERROR}）——
 * 不许写 {@code PENDING}（那不是结论），也不许自己发明第四种。
 */
public final class CaseUpdateTool implements Tool {

    private final CaseList cases;
    private final Runnable afterEach;
    /** 已经执行过几次工具调用（引擎侧计数，用来判"这条 PASS 期间到底动过手没有"）。 */
    private final java.util.function.IntSupplier toolCalls;
    /** 上一次写结论时的调用次数。 */
    private int seenCalls;

    public CaseUpdateTool(CaseList cases) {
        this(cases, null, null);
    }

    /**
     * @param afterEach 每写回一条结论之后的回调（用来落运行级断点：哪几条已经到终态了）。
     *                  它<b>不该</b>抛异常——抛了会盖住"结论已经写成功"这件事，所以这里兜住。
     */
    public CaseUpdateTool(CaseList cases, Runnable afterEach) {
        this(cases, afterEach, null);
    }

    /**
     * @param toolCalls 引擎侧的工具调用计数（{@code Loop} 那个）。
     *                  <b>为什么要它</b>：结论里的 {@code evidence} 是模型自己写的一段话，
     *                  引擎没法核对。但"这条 PASS 期间到底跑没跑过东西"是引擎自己数的——
     *                  一次都没动过手就宣布 PASS，那和不验一样（而且看起来更可信）。
     */
    public CaseUpdateTool(CaseList cases, Runnable afterEach,
                          java.util.function.IntSupplier toolCalls) {
        this.cases = cases;
        this.afterEach = afterEach;
        this.toolCalls = toolCalls;
    }

    @Override
    public String name() {
        return "case_update";
    }

    @Override
    public String description() {
        return "把一条用例的结论写回清单：PASS 要带证据原文，FAIL/ERROR 要说清为什么。"
                + "每条都必须到终态，不许留 PENDING。";
    }

    @Override
    public Map<String, Param> params() {
        Map<String, Param> params = new LinkedHashMap<>();
        params.put("id", Param.required("string", "用例 id（清单里那个，比如 C1）"));
        params.put("status", Param.required("string", "PASS / FAIL / ERROR"));
        params.put("evidence", Param.optional("string", "PASS 必填：你实际跑出来的那几行原文"));
        params.put("note", Param.optional("string", "FAIL/ERROR 必填：哪儿不对"));
        return params;
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String id = String.valueOf(args.getOrDefault("id", "")).strip();
        String statusText = String.valueOf(args.getOrDefault("status", "")).strip().toUpperCase(java.util.Locale.ROOT);
        String evidence = String.valueOf(args.getOrDefault("evidence", "")).strip();
        String note = String.valueOf(args.getOrDefault("note", "")).strip();

        if (id.isEmpty()) {
            return ToolResult.failure("id 是空的：要写哪一条的结论？");
        }
        if (!cases.ids().contains(id)) {
            return ToolResult.failure("清单里没有 " + id + " 这一条。有的：" + cases.ids());
        }
        CaseList.Status status;
        try {
            status = CaseList.Status.valueOf(statusText);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("status 只能是 PASS / FAIL / ERROR，收到：" + statusText);
        }
        if (status == CaseList.Status.PENDING) {
            return ToolResult.failure("PENDING 不是结论：这一条要么 PASS（带证据），要么 FAIL/ERROR（带原因）");
        }
        if (status == CaseList.Status.PASS && evidence.isEmpty()) {
            return ToolResult.failure(id + " 判 PASS 但没有证据：把实际跑出来的那几行原文放进 evidence"
                    + "（没有证据的「通过」和不验是一样的，而它看起来更可信）");
        }
        if (status != CaseList.Status.PASS && note.isEmpty()) {
            return ToolResult.failure(id + " 判 " + statusText + " 但没说原因：note 里写清哪儿不对，"
                    + "下一轮开发照它改");
        }
        // 「一次都没动过手就宣布 PASS」直接拒：证据是模型自己写的一段话，引擎核不了；
        // 但"这条用例期间跑没跑过工具"是引擎自己数的，谁说了算很清楚。
        if (status == CaseList.Status.PASS && toolCalls != null) {
            int now = toolCalls.getAsInt();
            if (now <= seenCalls) {
                return ToolResult.failure(id + " 判 PASS，但这一条期间<b>一次工具调用都没发生</b>："
                        + "没有命令、没有请求、没有查询——那不叫验过。"
                        + "先真跑一遍（bash 里跑命令/打接口/查库），再带着它的输出回来写结论。");
            }
            seenCalls = now;
        } else if (toolCalls != null) {
            seenCalls = toolCalls.getAsInt();
        }
        try {
            cases.settle(id, status, evidence, note);
        } catch (RuntimeException e) {
            return ToolResult.failure("写回清单失败：" + e.getMessage());
        }
        if (afterEach != null) {
            try {
                afterEach.run();
            } catch (RuntimeException e) {
                // 断点落不下不该让"结论写成功了"这件事看起来失败：吞掉，下一次写回会再写一遍
                ignored();
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", status.name());
        data.put("pending", cases.pending());
        return ToolResult.ok(data);
    }

    /** 明确的"这里故意什么都不做"（免得后来人以为漏了）。 */
    private static void ignored() {
    }
}
