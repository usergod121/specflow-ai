package com.specflow.tests;

import com.specflow.context.ContextAssembler;
import com.specflow.llm.ChatMessage;
import com.specflow.llm.LlmClient;
import com.specflow.patch.PatchApplier;
import com.specflow.review.PlanReview;
import com.specflow.spec.Spec;
import com.specflow.template.TemplateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用例生成的<b>第二段</b>：代码写完、有了这次改动的 diff 之后，给每条用例补上「怎么测」。
 *
 * <p><b>为什么要分成两段。</b>第一段（检查阶段，见 {@code ReviewProtocol}）只给
 * <b>需求 + 验收标准 + 设计（施工单）</b>，产出「验什么、期望什么、对应哪条验收标准、分级」——
 * 那时还一行代码都没有。老口径是让模型在这一刻把「怎么测」一起写掉：它没看过代码，
 * 只能照着脑子里的实现写，而人还没确认过那份实现；等到真跑起来，用例描述的其实是
 * 「它打算怎么写」，需求被代码稀释掉了，而这件事在界面上看不出来。
 * 所以「怎么测」（从哪个入口、哪个文件进）挪到<b>第二段</b>——那时 diff 已经在手里，
 * 写出来的是真的能照着做的步骤。
 *
 * <p><b>第二段唯一不许动的东西是期望。</b>看完代码之后顺手把期望改成「代码现在的行为」，
 * 是这条路上最省事、也最致命的做法：那等于让被测方给自己定答案，测试永远是绿的。
 * 所以机器在这里把模型照抄回来的期望与第一段<b>逐字（只忽略空白）</b>比一遍：
 * 对不上就整批打回、把差异摆给它看、再要一次（{@value #MAX_ATTEMPTS} 次为限）。
 * 两次都对不上的那几条，<b>「怎么测」就不补了</b>——宁可留一栏空着让人看得见，
 * 也不能把改过的期望放进去。期望与分级、验收标准一律取自第一段，一个字节都不许从第二段取。
 *
 * <p>它和 {@link CaseTraceCheck} 是两道不同的闸，都在防「被测方自己给自己判卷」：
 * 那一道查的是<b>测试代码</b>有没有照抄清单里的期望，这一道查的是<b>清单本身</b>
 * 有没有在看过代码之后被改。两者用的是同一个归一化（{@code CaseTraceCheck.normalize}），
 * 「逐字」只有一个意思。
 */
public final class CaseHowStage {

    private static final Logger log = LoggerFactory.getLogger(CaseHowStage.class);

    /**
     * 同一条用例的期望对不上时，最多重来几次（含首版）。
     *
     * <p>2 = 首版 + 一次「把差异摆出来再要一次」。只给一次是刻意的：这是<b>规矩</b>，
     * 不是讨价还价——反复求它别改期望，等于把这道闸当成摆设。两次都对不上就照实报出去，
     * 人自己看得见（那几条的「怎么测」空着，原因写在时间线与界面上）。
     */
    private static final int MAX_ATTEMPTS = 2;

    /** 一行三栏：{@code 编号 | 怎么测 | 期望（照抄第一段）}。 */
    private static final String FIELD_SEPARATOR = "|";

    /** 单份 diff 最多贴这么多字符。它给人看的那一版在磁盘上，贴全了会把上下文撑爆。 */
    private static final int MAX_DIFF_PER_FILE = 4000;

    /** 整段 diff 的上限。 */
    private static final int MAX_DIFF_TOTAL = 12000;

    /**
     * 第二段的协议——会被逐字拼进系统提示词。
     *
     * <p>它必须和下面 {@link #parse} 的解析规则同步演进：改了一处不改另一处，表现是
     * 「它明明补了怎么测，清单上却是空的」。公开可见，因为假模型（测试里的 stub）
     * 也要认得出「这一次是第二段」——它每次跑测试阶段都会先发生一次。
     */
    public static final String INSTRUCTIONS = """
            现在进入「测试」的**第二段**：代码已经写完并且编译通过了，这次改动的 diff 也在下面。
            第一段（检查阶段）已经定下了每条用例「要测什么、期望什么、对应哪条验收标准、分级」，
            那时它还没看过任何代码。你这一步<b>只做一件事</b>：给每一条用例补上「怎么测」。

            输出格式，每行一条，三个字段用竖线 | 分隔，不要加粗、不要写单元格外的话：

            <编号> | <怎么测> | <期望>

            硬性规则：
            1. 编号照抄第一段那份清单：一条不许少、一条不许多、一条不重复。
            2. 第 3 栏必须把第一段里这条用例的「期望什么」**逐字照抄**，一个字都不许改
               （连标点、数字、括号都不许动）。机器会把这两栏逐字比一遍（只忽略空白），
               对不上就整批打回来重来，并把差异摆出来。
               你的活儿是<b>补怎么测</b>，不是重新决定「期望应该是什么」：看完代码之后把期望
               改成代码现在的行为，等于让被测方给自己定答案，那样测试永远是绿的——比没有测试更糟。
               第一段那一栏本来就空着时，第 3 栏也留空（没有可照抄的对象，机器不比较它）。
            3. 第 2 栏「怎么测」要写到能照着做：**从哪个文件、哪个入口进**（类名/方法名/
               接口路径/脚本名，按这个项目自己的写法和入口），造什么数据，看哪里的结果。
               例：拿 OrderService.query(1) 调一次，看返回的 DTO 里 amount 是不是 100.00。
               这里说的是从**产品的哪个入口**进去验，**不是**让你指定测试文件放在哪儿——
               测试产物由引擎安排在自己的目录里，写成「在 src/test/java/… 里 new 一个」等于
               给了一个用不上的地址（2026-10-04 的真项目实测里它就是这么写的）。
               不许写「调用一下看看」「验证功能正常」这种等于没写的。
            4. 只补「怎么测」，其他一个字都不许多写：你写的期望、分级、验收标准都不会被采用
               （期望与分级一律取自第一段）。

            样例（三条用例的第二段）：

            1 | 用 OrderService.query(1) 调一次，看返回 DTO 的 amount 字段 | 100.00
            2 | 用 OrderService.query(999) 调一次，看它返回空而不是抛异常 | 返回 empty，不抛异常
            3 | 直接构造 page=0 调 OrderController 的 /orders 接口，看状态码 | 400
            """;

    private final ContextAssembler assembler;
    private final TemplateRegistry templates;
    private final LlmClient llm;

    public CaseHowStage(ContextAssembler assembler, TemplateRegistry templates, LlmClient llm) {
        this.assembler = assembler;
        this.templates = templates;
        this.llm = llm;
    }

    /**
     * 一段的结果。
     *
     * @param cases 补完之后那份清单：期望、分级、验收标准与第一段<b>逐字相同</b>，
     *              只有「怎么测」是新补的（没补上的那几条保持空着）
     * @param calls 为这一段花掉的模型调用次数。它<b>不算运行轮次</b>（和生成施工单那几次一样），
     *              但一样是用户掏的钱，所以单独记一笔、写进留档的时间线
     * @param note  给人与留档的一句话：补了几条、哪几条没补上、有没有出现过「想改期望被拦下」。
     *              <b>它必须说得出后者</b>——期望被改过而没人说，正是这一段要防的事
     */
    public record Result(List<PlanReview.TestCase> cases, int calls, String note) {

        public Result {
            cases = cases == null ? List.of() : List.copyOf(cases);
            note = note == null ? "" : note;
        }
    }

    /** 模型写回来的一行。 */
    private record Row(int index, String how, String expect) {
    }

    /**
     * 把第二段补上的那一栏<b>并回完整的那份清单</b>。
     *
     * <p>为什么非并不可：交给这一段的用例是**去掉停用之后的**那几条（见 {@code PlanReview.live}），
     * 而留档要留的是<b>完整</b>那份冻结清单——直接拿这一段的产物当留档，
     * 被停用的用例就会从留档里消失，于是「恢复」再也恢复不了
     * （{@code RunStore.disable} 会回一句「这条不在那次冻结的清单里」），
     * 停用就成了不可逆的删除——正是用户明确不要的那种做法。
     *
     * <p>合并规则只有一条：<b>编号找得到的就换「怎么测」</b>，其余一个字节都不动
     * （期望、分级、验收标准、要测什么一律以完整那份为准）。第二段的产物本来就只有这一栏。
     *
     * @param frozen  完整那份冻结清单；空表（这次没有清单）时原样返回第二段的产物
     * @param refined 第二段补完的那份（只有活着的用例）
     */
    public static List<PlanReview.TestCase> merge(List<PlanReview.TestCase> frozen,
                                                  List<PlanReview.TestCase> refined) {
        List<PlanReview.TestCase> full = frozen == null ? List.of() : frozen;
        List<PlanReview.TestCase> filled = refined == null ? List.of() : refined;
        if (full.isEmpty()) {
            return filled;
        }
        Map<Integer, String> how = new LinkedHashMap<>();
        for (PlanReview.TestCase one : filled) {
            if (!one.how().isBlank()) {
                how.put(one.index(), one.how());
            }
        }
        if (how.isEmpty()) {
            return full;
        }
        List<PlanReview.TestCase> out = new ArrayList<>();
        for (PlanReview.TestCase one : full) {
            String text = how.get(one.index());
            out.add(text == null ? one : new PlanReview.TestCase(one.index(), one.what(), text,
                    one.level(), one.expected(), one.acceptance()));
        }
        return List.copyOf(out);
    }

    /**
     * 补一遍。
     *
     * @param spec    这次的需求（含验收标准与目标文件），和第一段看到的是同一份
     * @param cases   第一段定下来、人确认并冻结的那份清单（<b>活着的</b>那几条：
     *                停用的用例不在这儿，它们不参与任何分母，也就没有「怎么测」要补）
     * @param changes 这次运行真正落盘的改动——第二段唯一的依据就是它
     */
    public Result fill(Spec spec, List<PlanReview.TestCase> cases,
                       List<PatchApplier.FileChange> changes) {
        List<PlanReview.TestCase> frozen = cases == null ? List.of() : cases;
        if (frozen.isEmpty()) {
            return new Result(frozen, 0, "");
        }
        // 依据拼一次就够：重来那一版只在末尾多一段「哪里对不上」，前面那几个字都不变
        String basis = assembler.userMessage(spec, templates)
                + "\n" + diffSection(changes)
                + "\n" + TestProtocol.firstStageList(frozen);

        List<String> complaints = List.of();
        // 每一版「哪里不按规矩」都留下来（带版号），而不是只留最后一次。
        // 为什么：只留最后一次时，第 1 版「想改期望被拦下」这条信号会被第 2 版的别的毛病
        // （比如漏写一条）整段挤掉——而它正是这一段最该报出来的那件事（见 Result.note 的注释）。
        // complaints 仍然只装**最近一版**：它是喂回给模型的「上一版哪里不对」，
        // 把前几版的旧账一起塞进去，模型会去修一个当前这一版根本不存在的问题
        List<String> history = new ArrayList<>();
        List<Row> lastRows = List.of();
        int calls = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String question = complaints.isEmpty() ? basis : basis + "\n\n" + redoNotice(complaints);
            // 模型调用本身失败会从这里冒泡（整次运行都要停）：它不是「这段补不上」，
            // 是「这一轮跑不下去」——两种收场完全不同，不能在这里咽掉
            String response = llm.complete(List.of(
                    ChatMessage.system(assembler.systemMessage(spec, templates, INSTRUCTIONS)),
                    ChatMessage.user(question)));
            calls++;
            List<Row> rows = parse(response);
            lastRows = rows;
            List<String> problems = problems(frozen, rows);
            if (problems.isEmpty()) {
                log.info("第二段补上了 {} 条用例的「怎么测」（花了 {} 次调用）", frozen.size(), calls);
                return new Result(apply(frozen, rows), calls, successNote(frozen, history, calls));
            }
            log.warn("第二段写回来的东西不按规矩（第 {} 版）：{}", attempt, String.join("；", problems));
            for (String problem : problems) {
                history.add("第 " + attempt + " 版：" + problem);
            }
            complaints = problems;
        }
        // 到这里说明两版都不按规矩：**期望一个字都不许改**，所以只把「编号对得上、期望逐字相同」
        // 的那几行补进去，其余几条的「怎么测」留空。差异原样写出去——人得看得见它想改什么，
        // 那正是「需求被代码稀释」的现场
        List<PlanReview.TestCase> kept = apply(frozen, lastRows);
        log.warn("第二段两次都没按规矩，只补了对得上的那几条，差异交给人看：{}",
                String.join("；", history));
        return new Result(kept, calls, refusalNote(frozen, kept, history, calls));
    }

    /**
     * 补成功时那句话。
     *
     * <p>为什么成功也要把前几版的毛病说出来：第 1 版很可能正是<b>想改期望被机器拦下</b>、
     * 第 2 版才守规矩的。「最后成功了」不等于「没发生过」——「它在照着代码改需求」的第一个信号
     * 就是那一次，把它咽掉，人就只能在事后从「怎么这一版这么慢」里猜。
     *
     * @param history 每一版不按规矩的地方（带版号），一次都没出问题时是空表
     */
    private static String successNote(List<PlanReview.TestCase> frozen, List<String> history, int calls) {
        StringBuilder out = new StringBuilder("第二段补上了 " + frozen.size()
                + " 条用例的「怎么测」（从哪个入口/文件进、造什么数据、看哪个结果），"
                + "期望与分级照旧、一个字都没改（机器逐字核过）。为它花了 " + calls
                + " 次模型调用。");
        if (!history.isEmpty()) {
            out.append("它前几版不按规矩的地方（引擎逐条核出来、当场拦下，一个字都没进结果）：")
                    .append(String.join("；", history)).append("。");
        }
        return out.toString();
    }

    /**
     * 拆回话。宽容度与 {@code PlanParser} 一致（剥列表符号、跳过表格分隔行、去掉首尾竖线）：
     * 这三件事都是排版，不是内容，为它们打回一次整批重来不值得。
     */
    private static List<Row> parse(String response) {
        List<Row> rows = new ArrayList<>();
        if (response == null || response.isBlank()) {
            return rows;
        }
        for (String raw : response.replace("\r\n", "\n").split("\n", -1)) {
            String line = stripBullet(raw);
            if (line.isEmpty() || isTableRule(line)) {
                continue;
            }
            String[] parts = trimPipes(line).split("\\" + FIELD_SEPARATOR);
            int index = number(field(parts, 0));
            if (index <= 0) {
                // 认不出编号的行不当成一条：它接不回任何一条用例，
                // 而「清单上那条没补上」下面照样会报出来，不重复刷屏
                continue;
            }
            rows.add(new Row(index, field(parts, 1), field(parts, 2)));
        }
        return rows;
    }

    /**
     * 这一段写回来的东西，哪里不按规矩。
     *
     * <p>三档各有各的说法，不合并成一句：多了、少了、改了期望，对应的下一步完全不同
     * （改措辞 / 补一条 / 这一条干脆不补）。期望那一档要<b>把两句原文都摆出来</b>——
     * 「它把 100.00 改成了 100.0」这种事，只报一个编号是看不出来的。
     */
    private static List<String> problems(List<PlanReview.TestCase> frozen, List<Row> rows) {
        List<String> issues = new ArrayList<>();
        Map<Integer, Row> byIndex = new LinkedHashMap<>();
        for (Row row : rows) {
            if (byIndex.containsKey(row.index())) {
                issues.add("用例 " + row.index() + " 写了两遍");
                continue;
            }
            if (frozen.stream().noneMatch(one -> one.index() == row.index())) {
                issues.add("编号 " + row.index() + " 不在这份清单里，它却写了");
                continue;
            }
            byIndex.put(row.index(), row);
        }
        for (PlanReview.TestCase one : frozen) {
            Row row = byIndex.get(one.index());
            if (row == null) {
                issues.add("用例 " + one.index() + " 它没写（一条都不许少）");
                continue;
            }
            if (row.how().isBlank()) {
                issues.add("用例 " + one.index() + " 的「怎么测」是空的");
            }
            // 第一段那一栏本来就空着时不比较：没有「照抄」的对象，
            // 判它改了期望是替清单背锅（与 CaseTraceCheck 同一条口径）
            if (one.expected().isEmpty()
                    || CaseTraceCheck.normalize(one.expected())
                            .equals(CaseTraceCheck.normalize(row.expect()))) {
                continue;
            }
            issues.add("**期望被改了**：用例 " + one.index() + " 第一段写的是「" + one.expected()
                    + "」，它写的是「" + row.expect() + "」");
        }
        return issues;
    }

    /**
     * 把对得上的那几行的「怎么测」贴回清单。
     *
     * <p><b>只贴「怎么测」这一栏</b>，而且只贴编号找得到、期望逐字相同的那几行：
     * 期望、分级、验收标准、要测什么一律取自第一段。哪怕模型这一行把期望写歪了、
     * 分级写高了，也进不来——这一段的产物只有一栏。
     */
    private static List<PlanReview.TestCase> apply(List<PlanReview.TestCase> frozen, List<Row> rows) {
        Map<Integer, String> how = new LinkedHashMap<>();
        for (Row row : rows) {
            PlanReview.TestCase one = frozen.stream()
                    .filter(item -> item.index() == row.index())
                    .findFirst()
                    .orElse(null);
            if (one == null || row.how().isBlank()) {
                continue;
            }
            // 第一段那一栏空着时不比较（没有可照抄的对象），其余一律要求逐字相同
            if (!one.expected().isEmpty() && !CaseTraceCheck.normalize(one.expected())
                    .equals(CaseTraceCheck.normalize(row.expect()))) {
                continue;
            }
            how.put(row.index(), row.how());
        }
        if (how.isEmpty()) {
            return frozen;
        }
        List<PlanReview.TestCase> out = new ArrayList<>();
        for (PlanReview.TestCase one : frozen) {
            String filled = how.get(one.index());
            out.add(filled == null ? one : new PlanReview.TestCase(one.index(), one.what(), filled,
                    one.level(), one.expected(), one.acceptance()));
        }
        return List.copyOf(out);
    }

    /** 重来那一版末尾那段：把差异摆出来，并说清「期望不许改」。 */
    private static String redoNotice(List<String> problems) {
        StringBuilder out = new StringBuilder("## 上一版哪里不按规矩（引擎逐条核出来的）\n");
        for (String problem : problems) {
            out.append("- ").append(problem).append('\n');
        }
        out.append("""

                请**重给一份**（每一行都写全，三栏：编号 | 怎么测 | 期望）。
                记住：期望那一栏是把第一段那份清单里的「期望什么」**逐字照抄**，
                不是你认为它应该是什么；编号一条不少、一条不多。
                """);
        return out.toString();
    }

    /**
     * 两次都对不上时那段话：留下什么、缺什么、它想改什么，逐条说清。
     *
     * @param history 每一版不按规矩的地方（带版号）。<b>不是「最后一版」</b>：
     *                第 1 版想改期望、第 2 版改成漏写一条时，两条都要在——只报后一条
     *                就把「它在照着代码改需求」这个最该看见的信号丢了（见 {@code fill} 的注释）
     */
    private static String refusalNote(List<PlanReview.TestCase> frozen,
                                      List<PlanReview.TestCase> kept,
                                      List<String> history, int calls) {
        long filled = kept.stream().filter(one -> !one.how().isEmpty()).count();
        StringBuilder out = new StringBuilder("第二段两次都没按规矩来（花了 " + calls + " 次模型调用）：")
                .append(String.join("；", history))
                .append("。对得上的 ").append(filled).append('/').append(frozen.size())
                .append(" 条已经补上「怎么测」，其余几条那一栏仍然空着——")
                .append("**期望一个字都没改**：它想改的那些被机器拦下了（期望与分级一律取自第一段）。")
                .append("这几条照样会拿去生成测试代码（锚点用的是第一段那份期望）。");
        return out.toString();
    }

    /**
     * 第二段要看的改动：这次改了哪些文件、改了什么。
     *
     * <p>为什么非给它 diff、而不只是「目标文件现在长什么样」：第二段要回答的是
     * 「<b>这次</b>改了什么，所以从哪儿验」——整份文件是现状，diff 才是这次的改动。
     *
     * <p><b>但它不是第二段看到的全部。</b>这一段只是拼在用户消息里的一节，前面还有
     * 需求、验收标准、约束、上下文依赖、目标文件全文与第一段那份清单（见 {@code fill}）。
     * 这一节的措辞不能说成「这是唯一依据」：说成那样，模型会以为可以不管需求和验收标准，
     * 只照着 diff 写「怎么测」——而那正是「需求被代码稀释」的入口。
     *
     * <p>太长时按文件掐，并<b>明说掐了</b>（还差多少字符、完整内容在磁盘上哪个文件）：
     * 静默截断会让它以为改动就这么多，于是把「怎么测」写在半截事实上。
     */
    private static String diffSection(List<PatchApplier.FileChange> changes) {
        StringBuilder out = new StringBuilder("## 这次改动的 diff（这一轮改了什么，以它为准）\n");
        if (changes == null || changes.isEmpty()) {
            out.append("这一次没有改任何文件。那就照第一段那份清单写「怎么测」："
                    + "从哪个入口/文件进、造什么数据、看哪个结果。\n");
            return out.toString();
        }
        int left = MAX_DIFF_TOTAL;
        for (PatchApplier.FileChange change : changes) {
            String body = change.diff() == null ? "" : change.diff();
            String head = change.relative() + "（" + (change.created() ? "新建" : "修改") + "，"
                    + change.bytes() + " 字节）";
            if (body.isBlank()) {
                // 实测里出现过「把文件原样再交一遍」：diff 为空不是没改动，是<b>没有差异</b>，
                // 这句话得说出来，否则它会以为这一段收到的是空依据
                out.append("- ").append(head).append("：内容与改动前逐字相同（没有实际差异）\n");
                continue;
            }
            out.append("- ").append(head).append("\n```diff\n");
            if (body.length() > MAX_DIFF_PER_FILE || body.length() > left) {
                int shown = Math.max(0, Math.min(Math.min(MAX_DIFF_PER_FILE, left), body.length()));
                out.append(body, 0, shown)
                        .append("\n…（diff 太长，这里只贴了前 ").append(shown).append(" 个字符，"
                                + "还有 ").append(body.length() - shown)
                        .append(" 个字符没贴；完整内容在项目里的 ").append(change.relative()).append("）\n");
                left = Math.max(0, left - shown);
            } else {
                out.append(body);
                if (!body.endsWith("\n")) {
                    out.append('\n');
                }
                left = Math.max(0, left - body.length());
            }
            out.append("```\n");
        }
        return out.toString();
    }

    // ---------- 逐行解析用的三个小助手 ----------
    // 和 PlanParser 那三个同源（剥列表符号 / 跳过表格分隔行 / 去掉首尾竖线），
    // 但那是 review 包的私有实现，跨包复用要把它们升成公共 API——为六行代码新开一个共享 Util
    // 不值得（§18.15 记过同一条取舍）。

    private static String field(String[] parts, int index) {
        return parts.length > index ? parts[index].strip() : "";
    }

    private static String stripBullet(String line) {
        return line.strip().replaceFirst("^[-*•]\\s*", "").replaceFirst("^\\d+[.)]\\s+", "").strip();
    }

    private static String trimPipes(String line) {
        String row = line.startsWith(FIELD_SEPARATOR) ? line.substring(1) : line;
        return row.endsWith(FIELD_SEPARATOR) ? row.substring(0, row.length() - 1) : row;
    }

    /** {@code |---|---|} 这种表格分隔行：那是排版不是内容。 */
    private static boolean isTableRule(String line) {
        return line.matches("\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?");
    }

    /** 编号那一栏：认「3」「第 3 条」两种写法（和 PlanParser 一样宽容）。 */
    private static int number(String text) {
        String value = text.strip().replaceFirst("^第", "").replaceFirst("[条步]$", "").strip();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
