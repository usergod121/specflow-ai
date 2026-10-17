package com.specflow.test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.specflow.core.Atomic;
import com.specflow.core.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>③ 的产物：用例清单</b>（{@code .specflow/flow/<id>/cases.json}），也是 ⑤ 的验收依据。
 *
 * <p>为什么是一个类而不是各处各读一遍：这份文件<b>人能改</b>（③ 那一页能增删改、打标签），
 * 所以「什么才算一份合法的清单」必须只有一处口径——{@link #save(List)} 落盘前判它、
 * {@link #problems()} 提交时判它。两处各判一套的结果是：界面放行的东西 ⑤ 打回，
 * 而两句话还不一样，用户不知道该听谁的。
 *
 * <p>文件形状（人的第一手材料，所以用一句 {@code {"cases":[…]}} 包着，将来加字段不破坏读者）：
 * <pre>
 * { "cases": [ { "id": "C1", "what": "…", "tags": ["功能"], "expect": "…",
 *                "status": "PENDING", "evidence": "", "note": "" } ] }
 * </pre>
 * 读的时候两种都认：上面这种，以及裸数组 {@code [ … ]}（有人可能手写成那样）。
 */
public final class CaseList {

    /** 一条用例的终态归属。<b>{@code ERROR} 也是终态</b>：没测成也是结论，只是它不是「通过」。 */
    public enum Status {
        PENDING, PASS, FAIL, ERROR,
        /**
         * <b>被跳过</b>（⑤ 的每用例预算用尽时自动打的）：它是终态，但<b>不是结论</b>——
         * 所以 {@link Case#decided()} 不认它，界面上要显眼地说「这条没验成」，
         * 用户可以在 ⑤ 页面对它点「重试这一条」。
         */
        SKIPPED;

        /**
         * 认大小写与前后空格：这文件是人手改的，写 {@code pass} 不该算错。
         * 认不出来时给一句人话（合法的几个词都在里面），而不是一句 Jackson 的英文。
         */
        @JsonCreator
        public static Status from(String raw) {
            String key = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
            for (Status one : values()) {
                if (one.name().equals(key)) {
                    return one;
                }
            }
            throw new IllegalArgumentException("用例状态认不出来：" + raw
                    + "（只能是 PENDING / PASS / FAIL / ERROR / SKIPPED）");
        }
    }

    /** 标签的固定集合（③ 那一页让人从这几个里点，不许自由发挥——自由发挥的标签等于没标签）。 */
    public static final List<String> TAGS = List.of("功能", "边界", "异常", "性能", "安全");

    /** 报错话术里给人看的那一份标签清单。 */
    private static final String TAG_TEXT = String.join(" / ", TAGS);

    /**
     * 一条用例。
     *
     * @param id       稳定编号（结论按它写回，所以界面上排序、增删之后它都不变）
     * @param what     这条验什么（一句话）
     * @param tags     标签，至少一个，从 {@link #TAGS} 里选
     * @param expect   <b>模糊期望</b>：一句话的意图（不写具体断言——断言由测试 Agent 在容器里定）
     * @param status   状态；测之前一律 {@code PENDING}
     * @param evidence 证据（{@code PASS} 必须有：容器里跑出来的原话）
     * @param note     原因（非 {@code PASS} 必须有：哪里不符合预期 / 为什么没测成）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Case(String id, String what, List<String> tags, String expect,
                       Status status, String evidence, String note) {

        public Case {
            id = id == null ? "" : id.strip();
            what = what == null ? "" : what.strip();
            expect = expect == null ? "" : expect.strip();
            evidence = evidence == null ? "" : evidence.strip();
            note = note == null ? "" : note.strip();
            status = status == null ? Status.PENDING : status;
            // 标签在这里去重、去空：手改的文件里常有 "功能,功能" 这种，别让它变成两条
            List<String> clean = new ArrayList<>();
            for (String tag : tags == null ? List.<String>of() : tags) {
                if (tag != null && !tag.isBlank() && !clean.contains(tag.strip())) {
                    clean.add(tag.strip());
                }
            }
            tags = List.copyOf(clean);
        }

        /** 改了结论的那一条（其余字段原样）。 */
        public Case withResult(Status next, String evidenceText, String noteText) {
            return new Case(id, what, tags, expect, next, evidenceText, noteText);
        }

        /** 这是一条<b>真结论</b>吗：{@code PASS/FAIL} 是，{@code ERROR/PENDING} 不是。 */
        public boolean decided() {
            return status == Status.PASS || status == Status.FAIL;
        }
    }

    private final Path file;

    /** @param file {@code cases.json} 的路径（由 {@code Layout.cases(flowId)} 给出，别处不许拼） */
    public CaseList(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("用例清单落在哪？路径是空的");
        }
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * 读回清单。<b>文件不在 = 空清单</b>（还没生成过，这不是错误）；读不出来才抛——
     * 一份坏文件静默变成空清单，会让人以为「用例丢了」，而真原因是文件坏了。
     */
    public List<Case> cases() {
        String text = Atomic.read(file, null);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            String body = text.strip();
            if (body.startsWith("[")) {
                return readArray(body);
            }
            JsonNode root = Json.read(body, JsonNode.class);
            JsonNode array = root == null ? null : root.get("cases");
            return array == null || array.isNull() ? List.of() : readArray(array.toString());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("用例清单读不出来（" + file + "）：" + e.getMessage()
                    + "；这份文件是能手改的，改好它或者删掉它再试", e);
        }
    }

    private static List<Case> readArray(String json) {
        Case[] parsed = Json.read(json, Case[].class);
        if (parsed == null) {
            return List.of();
        }
        List<Case> out = new ArrayList<>(parsed.length);
        for (int at = 0; at < parsed.length; at++) {
            if (parsed[at] == null) {
                // 文件里写了个 null：这跟「文件写坏了」是一回事，当场说清楚，别让它变成后面某处的空指针
                throw new IllegalArgumentException("第 " + (at + 1)
                        + " 条是空的（null）：删掉它，或者把它写成一条完整的用例");
            }
            out.add(parsed[at]);
        }
        return List.copyOf(out);
    }

    /**
     * 整份覆盖写（清单是人编辑的一份完整清单，覆盖写语义最直白）。
     *
     * <p><b>不合法就整份不落盘</b>：写一份「一半合法」的清单出去，等于把问题推到 ⑤ 才炸。
     * 抛出的话术里逐条列出是哪一条、哪里不对（见 {@link #invalid}）。
     *
     * @throws IllegalArgumentException 有一处不合规（正文逐条列出）
     */
    public void save(List<Case> cases) {
        // 用 ArrayList 而不是 List.copyOf：null 那一条要由 invalid() 给人话，而不是让 copyOf 抛个英文 NPE
        List<Case> next = cases == null ? List.of() : new ArrayList<>(cases);
        List<String> problems = invalid(next);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("用例清单有 " + problems.size()
                    + " 处不合规，整份都没有写盘：\n - " + String.join("\n - ", problems));
        }
        Atomic.write(file, Json.write(Map.of("cases", next)) + "\n");
    }

    /** 把一条改成终态（⑤ 的测试 Agent 唯一的写回口）。 */
    public void settle(String id, Status status, String evidence, String note) {
        String wanted = id == null ? "" : id.strip();
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("要改哪一条：带上用例编号（清单里能看到，比如 C1）");
        }
        if (status == null) {
            throw new IllegalArgumentException("要把 " + wanted + " 改成什么状态："
                    + "PENDING / PASS / FAIL / ERROR");
        }
        List<Case> all = cases();
        List<Case> next = new ArrayList<>(all.size());
        boolean hit = false;
        for (Case one : all) {
            // 只碰这一条：其余的原对象直接搬过去，顺序也不动（界面上人排过的顺序是他的东西）
            if (!hit && one.id().equals(wanted)) {
                next.add(one.withResult(status, evidence, note));
                hit = true;
            } else {
                next.add(one);
            }
        }
        if (!hit) {
            throw new IllegalArgumentException("清单里没有编号 " + wanted + " 的用例（现在有：" + ids()
                    + "）；结论只能写在清单上已有的那几条上——要加用例请回 ③ 那一页加完再冻结");
        }
        save(next);
    }

    // ---------- 事实（读的都是同一份，不重算第二遍） ----------

    /**
     * 把一条标成<b>跳过</b>（⑤ 的每用例预算用尽时由 {@link CaseBudget} 调）。
     *
     * <p>不写成 {@code settle(..., SKIPPED, ...)} 而是单开一个方法：调用点读起来就该是
     * "跳过这一条"，而不是"写一个 SKIPPED 结论"。
     */
    public void skip(String id, String why) {
        settle(id, Status.SKIPPED, "", why == null || why.isBlank() ? "被跳过（没写原因）" : why);
    }

    /** 用户点「重试这一条」：把一条改回 {@code PENDING}，并按条预算从零开始。 */
    public void reopen(String id) {
        settle(id, Status.PENDING, "", "");
    }

    public int count() {
        return cases().size();
    }

    public int pending() {
        int n = 0;
        for (Case one : cases()) {
            if (one.status() == Status.PENDING) {
                n++;
            }
        }
        return n;
    }

    /** 缺描述或缺标签的条数（③ 的闸用它：「每条都有描述与标签」）。 */
    public int missingTags() {
        int n = 0;
        for (Case one : cases()) {
            if (one.what().isBlank() || one.tags().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 清单里的编号连写（报错话术里给人看）。 */
    public String ids() {
        StringBuilder out = new StringBuilder();
        for (Case one : cases()) {
            if (out.length() > 0) {
                out.append("、");
            }
            out.append(one.id().isBlank() ? "（没编号）" : one.id());
        }
        return out.length() == 0 ? "（空）" : out.toString();
    }

    /**
     * <b>提交时的机器判据：⑤ 的核心闸门。</b>空表 = 可以提交。
     *
     * <p>五条，一条都不许少：
     * <ol>
     *   <li>清单非空（空清单 = 这次什么都没验，却看起来"没有待定"，最容易骗过去）；</li>
     *   <li>没有 {@code PENDING}；</li>
     *   <li>{@code PASS} 必须有证据；</li>
     *   <li>非 {@code PASS} 必须有原因（{@code FAIL} 是哪里不符合预期，{@code ERROR} 是为什么没测成）；</li>
     *   <li>至少有一条<b>真结论</b>——{@code PASS}/{@code FAIL} 才算，全是 {@code ERROR}
     *       是「环境起不来」，把它当通过等于把"没验"说成"没问题"。</li>
     * </ol>
     *
     * <p>返回的每一条都要是<b>人话</b>：界面上原样显示，用户看完就知道去改哪一条、补什么。
     */
    public List<String> problems() {
        List<Case> all = cases();
        if (all.isEmpty()) {
            return List.of("用例清单是空的：一条都没有，这次运行等于什么都没验"
                    + "（回 ③ 生成或自己写几条，冻结之后再验）");
        }
        List<String> problems = new ArrayList<>();
        int decided = 0;
        for (int at = 0; at < all.size(); at++) {
            Case one = all.get(at);
            String spot = spot(at, one);
            if (one.decided()) {
                decided++;
            }
            switch (one.status()) {
                case PENDING -> problems.add(spot + "还没有结论：它既没测也没写原因——"
                        + "测过的写 PASS 或 FAIL，没测成的写 ERROR 并说明");
                case PASS -> {
                    if (one.evidence().isBlank()) {
                        problems.add(spot + "标了 PASS 但没给证据："
                                + "把容器里跑出来的原话贴到 evidence 上，别只写「通过」");
                    }
                }
                case FAIL, ERROR -> {
                    if (one.note().isBlank()) {
                        problems.add(spot + "标了 " + one.status() + " 但没写原因："
                                + (one.status() == Status.FAIL
                                ? "哪里不符合预期（实际是什么、期望是什么）"
                                : "为什么没测成（哪一步卡住了）"));
                    }
                }
                case SKIPPED -> {
                    // 跳过是终态，但**不是结论**：所以要求写清"为什么跳"，
                    // 而且它不会让 decided 那一栏变多（见下面那条"没有一条真结论"）
                    if (one.note().isBlank()) {
                        problems.add(spot + "被跳过了但没写原因：写清卡在哪一步"
                                + "（比如「每用例预算用尽」），或者回去点「重试这一条」");
                    }
                }
            }
        }
        if (decided == 0) {
            problems.add("整份清单没有一条真跑出结论：全是「没测成」，这次等于什么都没验"
                    + "（先把环境起起来，或者把卡住的那一步写清楚）");
        }
        return List.copyOf(problems);
    }

    // ---------- 内部 ----------

    /** 报错话术里的「第几条」：位置 + 编号 + 描述，三样都在，用户才找得到那一条。 */
    private static String spot(int index, Case one) {
        String id = one.id().isBlank() ? "没有编号" : one.id();
        String what = one.what().isBlank() ? "没写描述" : one.what();
        return "第 " + (index + 1) + " 条用例（" + id + "：" + what + "）";
    }

    /**
     * 逐条挑不合规的地方（{@link #save} 的唯一判据）。
     *
     * <p>为什么不一条一条抛：人改了 8 条，一次只告诉他一处，他就得改 8 遍。
     */
    private static List<String> invalid(List<Case> cases) {
        List<String> problems = new ArrayList<>();
        Map<String, Integer> firstSeen = new LinkedHashMap<>();
        for (int at = 0; at < cases.size(); at++) {
            Case one = cases.get(at);
            if (one == null) {
                problems.add("第 " + (at + 1) + " 条是空的（null）：删掉它，或者把它补全");
                continue;
            }
            String spot = spot(at, one);
            if (one.id().isBlank()) {
                problems.add(spot + "没有编号：每条都要有一个能写回结论的编号（比如 C1）");
            } else {
                Integer before = firstSeen.putIfAbsent(one.id(), at + 1);
                if (before != null) {
                    problems.add(spot + "编号和第 " + before + " 条重了："
                            + "编号必须唯一，测试结论是按编号写回的，重了就会写错条目");
                }
            }
            if (one.what().isBlank()) {
                problems.add(spot + "没写这条验什么（what 不能为空）");
            }
            if (one.tags().isEmpty()) {
                problems.add(spot + "一个标签都没打：从 " + TAG_TEXT + " 里至少选一个");
            } else {
                for (String tag : one.tags()) {
                    if (!TAGS.contains(tag)) {
                        problems.add(spot + "标签「" + tag + "」不在固定集合里：只能用 " + TAG_TEXT);
                    }
                }
            }
            if (one.expect().isBlank()) {
                problems.add(spot + "没写期望（expect）：一句话说清怎样算符合预期，"
                        + "比如「重复入库同一件货应当报错」——具体断言交给测试 Agent 去定");
            }
        }
        return problems;
    }
}
