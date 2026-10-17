package com.specflow.agent;

import com.specflow.core.Llm;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>上下文压缩</b>：对话太长的时候，把"中间过程"压成一张<b>锚点表</b>，保住尾巴。
 *
 * <h2>为什么要有它</h2>
 * ReAct 的每一轮都往对话里追加一条工具结果，而 {@code read_file} 一次能带进来两万字符。
 * 一条长运行（60 轮）下来，光是"看过的东西"就能把上下文塞满，模型开始丢前面的线索——
 * 表现是<b>重复劳动</b>：同一个文件读三遍、同一个错误犯两次、忘了自己已经改过哪个文件。
 *
 * <h2>为什么<b>不</b>让模型来写摘要（这一条是刻意的）</h2>
 * 摘要调一次模型 = 多一次延迟、多一次花钱、多一个"摘要本身出错"的故障点，
 * 而它要保住的其实只有三件事：<b>动过哪些文件、最近为什么失败、现在做到哪一条</b>。
 * 这三件事引擎自己就知道（工具参数、工具结果、用例清单），所以这里是<b>确定性拼装</b>：
 * 同一段对话永远压出同一份锚点，能被单测钉死。
 *
 * <h2>压缩的形状</h2>
 * <pre>
 *   [system]                ← 原样保留（协议、规矩都在里面）
 *   [user] 锚点表           ← 我们拼的：动过的文件 / 最近的失败 / 当前进度
 *   [user] 第一条任务书      ← 原样保留（"需求是什么"不能丢）
 *   ……（中间全部丢掉）……
 *   [assistant/user] 最后 keepTail 条 ← 原样保留（模型需要看到"刚才在干什么"）
 * </pre>
 */
public final class Compact {

    /** 合计字符超过它就压。6 万字符 ≈ 1.5～2 万汉字，对主流模型是"还宽裕但开始担心"的位置。 */
    public static final int DEFAULT_CHARS = 60_000;

    /** 尾巴保留几条（一轮 = assistant + user 两条，所以 8 条 ≈ 最近三四轮）。 */
    public static final int DEFAULT_KEEP_TAIL = 8;

    /** 一次压缩的结果（给事件流那一行用）。 */
    public record Result(boolean applied, int before, int after, long charsBefore, long charsAfter) {

        public String describe() {
            return "上下文压到 " + after + " 条（原来 " + before + " 条），"
                    + charsAfter + " 字符（原来 " + charsBefore + " 字符）";
        }
    }

    /** 压缩自己插进去的那两条的抬头：下一次压缩要认得出它们，<b>替换</b>而不是叠加。 */
    static final String ANCHOR_HEAD = "【压缩前的锚点】";
    static final String NOTE_HEAD = "【压缩说明】";

    private Compact() {
    }

    /** 现在要不要压（只看大小，不看内容——内容的好坏不该由长度决定）。 */
    public static boolean needed(List<Llm.Msg> messages, int chars, int keepTail) {
        if (messages == null || messages.size() <= keepTail + 3) {
            return false;
        }
        return totalChars(messages) > Math.max(1_000, chars);
    }

    /**
     * 压一次（原地改 {@code messages}）。
     *
     * <h2>它必须能被压很多次（这一条是测试逼出来的）</h2>
     * 一段长运行会压<b>不止一次</b>：压完还剩两万字符，再跑几轮又过线了。所以这个方法
     * 不能假设"第 1 条永远是任务书"——第一次压缩之后，第 1 条已经变成<b>我们自己插的锚点</b>，
     * 真正的任务书漂到后面去了。老写法照抄 {@code messages.get(1)}，第二次压缩就会：
     * <ol>
     *   <li>把锚点表当成任务书抄下来 → 表里出现两份「【压缩前的锚点】」；</li>
     *   <li><b>把原始任务书扔掉</b> → 模型再也看不到"需求是什么"，只剩"刚才在干什么"。</li>
     * </ol>
     * 两种都不会报错，只会表现为"跑着跑着它跑偏了"。所以现在的形状是：
     * <b>认头（system + 第一条不是我们插的 user 消息 = 任务书），丢掉自己上次插的那两条，再接尾巴</b>。
     * 同一个输入压两次，第二次和第一次结果一样（幂等）。
     *
     * @param messages 对话（第 0 条是 system）
     * @param anchors  <b>锚点表</b>：引擎拼好的那几句（可以为空串，那就只留任务书与尾巴）
     * @param chars    触发阈值（{@link #DEFAULT_CHARS}）
     * @param keepTail 尾巴保留几条
     * @return 压没压、压前压后各多少
     */
    public static Result maybe(List<Llm.Msg> messages, String anchors, int chars, int keepTail) {
        int keep = keepTail <= 0 ? DEFAULT_KEEP_TAIL : keepTail;
        if (!needed(messages, chars, keep)) {
            return new Result(false, messages.size(), messages.size(), totalChars(messages),
                    totalChars(messages));
        }
        int before = messages.size();
        long charsBefore = totalChars(messages);

        Llm.Msg system = messages.get(0);
        Llm.Msg task = originalTask(messages);

        List<Llm.Msg> kept = new ArrayList<>(keep + 4);
        kept.add(system);                                // system：协议与规矩
        String table = anchors == null ? "" : anchors.strip();
        if (!table.isEmpty()) {
            kept.add(Llm.Msg.user(ANCHOR_HEAD + "（这一段是引擎按事实拼的，不是模型的记忆）\n" + table));
        }
        if (task != null) {
            kept.add(task);                              // 原始任务书：需求不能丢
        }
        kept.add(Llm.Msg.user(NOTE_HEAD + "中间的工具输出为了腾出上下文已经省略。"
                + "不要重复已经做过的事：先看上面的锚点表，再决定下一步。"));

        // 尾巴：最后 keep 条，但要跳过"我们上次插的那两条"和"头里已经有的那两条"
        for (int i = Math.max(0, before - keep); i < before; i++) {
            Llm.Msg one = messages.get(i);
            if (one == null || one == system || isOwn(one) || same(one, task)) {
                continue;
            }
            kept.add(one);
        }

        messages.clear();
        messages.addAll(kept);
        return new Result(true, before, kept.size(), charsBefore, totalChars(kept));
    }

    /**
     * 原始任务书：<b>第一条不是压缩自己插的 user 消息</b>。
     *
     * <p>为什么不直接取 {@code messages.get(1)}：见 {@link #maybe} 的注释——
     * 第二次压缩时第 1 条已经是我们插的锚点表了。
     */
    static Llm.Msg originalTask(List<Llm.Msg> messages) {
        for (int i = 1; i < messages.size(); i++) {
            Llm.Msg one = messages.get(i);
            if (one == null) {
                continue;
            }
            boolean user = "user".equals(one.role());
            String text = one.content() == null ? "" : one.content();
            if (user && !text.startsWith(ANCHOR_HEAD) && !text.startsWith(NOTE_HEAD)) {
                return one;
            }
        }
        return null;
    }

    /** 这条是不是压缩自己插的（锚点 / 说明）。 */
    private static boolean isOwn(Llm.Msg one) {
        String text = one.content() == null ? "" : one.content();
        return text.startsWith(ANCHOR_HEAD) || text.startsWith(NOTE_HEAD);
    }

    private static boolean same(Llm.Msg one, Llm.Msg other) {
        return other != null
                && String.valueOf(one.role()).equals(String.valueOf(other.role()))
                && String.valueOf(one.content()).equals(String.valueOf(other.content()));
    }

    /** 合计字符（各条 content 长度之和；role 不算）。 */
    public static long totalChars(List<Llm.Msg> messages) {
        long sum = 0;
        for (Llm.Msg one : messages) {
            String text = one == null ? null : one.content();
            sum += text == null ? 0 : text.length();
        }
        return sum;
    }
}
