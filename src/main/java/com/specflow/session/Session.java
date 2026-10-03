package com.specflow.session;

import com.specflow.history.RunRecord;
import com.specflow.review.AcceptanceCoverage;
import com.specflow.review.PlanReview;
import com.specflow.tests.TestOutcome;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>一次会话</b>：N 轮，由用户一轮一轮驱动（§19）。
 *
 * <p>用户的口径是「一直到满意」，但引擎<b>不加自动循环</b>：每一轮都要人点「下一轮」才开，
 * 会话只有两个出口——接受（定稿）或中断（回到会话最初）。所以这个类不是一台状态机，
 * 它是<b>一次折叠</b>：把属于同一个会话 id 的那几条运行留档，按轮次序号折成
 * 「现在第几轮、每轮什么结论、这一轮还能不能撤」。折出来而不是另存一份状态，
 * 是因为状态文件与留档迟早对不上，而对不上的表现是「界面上说第 3 轮、引擎里其实第 2 轮」。
 *
 * <p><b>「哪几轮还算数」怎么折</b>（三条判据，缺一条就会数错）：
 * <ul>
 *   <li><b>被撤回</b>（{@code settlement} 是 {@code UNDO_ROUND}，或者老记录里的
 *       {@code UNDO_SESSION}）：这一轮的改动已经被撤掉了，它不再进累计通过率；
 *       连着的失败也不从它数起；</li>
 *   <li><b>自己回滚了</b>（跑失败、被中断、模型说缺料）：它压根没在磁盘上留下东西——
 *       判据是<b>它那段时间里没有快照</b>，而不是去数状态名。状态名是另一件事，
 *       而 {@code NEEDS_ENVIRONMENT} 这一档开发阶段回滚、测试阶段保留（同一名字两种事实）；</li>
 *   <li><b>还算数</b>（{@code live}）：没被撤回、而且它的快照还在——「撤回本轮」
 *       要的正是这一轮那份快照，它恢复到的是<b>上一轮结束时</b>的样子。</li>
 * </ul>
 *
 * <p><b>快照和轮次怎么对上：按时间窗口</b>（这是本类里唯一一处「靠时间猜」的地方，
 * 风险见 {@link #snapshotIn}）。
 */
public record Session(String id, List<Round> rounds) {

    /** 快照目录名的格式，与 {@code WorkspaceSnapshot} 同一个（按它才能把快照归到轮上）。 */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /**
     * 连着几轮「还有失败」就给一条软提示。
     *
     * <p>3 这个数是用户定的口径（「连续 3 轮仍有失败给一条软提示」）。
     * 它<b>只用来提示</b>：不做最大轮数自动放弃，也不拦任何按钮——
     * 该不该继续是人的判断，机器只负责把「你已经连着三轮都没过」这件事说清楚。
     */
    public static final int SOFT_HINT_ROUNDS = 3;

    public Session {
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
    }

    /**
     * 会话里的一轮。
     *
     * @param round             第几轮，从 1 开始（会话内单调递增，撤回过也不复用序号）
     * @param recordId          这一轮那条运行留档的 id
     * @param status            这一轮的终态名（与 {@code AgentResult.Status} 同名）
     * @param detail            这一轮面向人的结论
     * @param startedAt         开工时刻，ISO 格式
     * @param finishedAt        跑完时刻，ISO 格式；老记录里没有 → 空串（耗时那一栏于是不显示）
     * @param calls             这一轮花了<b>几次模型调用</b>（{@code RunRecord.modelCalls}）：
     *                          开发轮次 + 现生成施工单 + 第二段补「怎么测」+ 生成测试代码，
     *                          口径与不算进来的那两笔见那一边的注释。老记录只有开发轮次
     * @param cost              这一轮那几笔调用的明细（老记录没有这一栏时是 {@code null}）；
     *                          界面把它摊在成本那句话上，让「这个数是怎么来的」看得见
     * @param millis            这一轮耗时（毫秒）；算不出来时是 0
     * @param snapshot          这一轮拍的那份快照的目录名；没拍到（或者已经撤掉了）是 {@code null}
     * @param undone            这一轮被<b>人撤回了</b>
     * @param settlement        收场是哪一档；还没收场是 {@code null}
     * @param settlementSummary 收场的中文说法；没收场是空串
     * @param failing           这一轮<b>还有失败</b>（跑过测试而失败清单非空，或者压根没成功）
     * @param testCases         这一轮冻结的用例清单（老记录可能是 {@code null}）
     * @param tests             这一轮的测试结论（没跑过测试是 {@code null}）
     * @param changes           这一轮改了哪些文件（<b>轮次间 diff</b> 就是它）
     * @param verdicts          人判了谁错
     * @param disabled          这一轮开工时停用着的用例编号
     * @param refeed            这一轮回喂给开发的失败用例编号；不是回喂时为空
     * @param coverage          覆盖核对那一块；老记录可能是 {@code null}
     */
    public record Round(
            int round,
            String recordId,
            String status,
            String detail,
            String startedAt,
            String finishedAt,
            int calls,
            RunRecord.Cost cost,
            long millis,
            String snapshot,
            boolean undone,
            String settlement,
            String settlementSummary,
            boolean failing,
            List<PlanReview.TestCase> testCases,
            TestOutcome tests,
            List<RunRecord.Change> changes,
            List<RunRecord.Verdict> verdicts,
            List<Integer> disabled,
            List<Integer> refeed,
            AcceptanceCoverage.Report coverage
    ) {

        public Round {
            changes = changes == null ? List.of() : List.copyOf(changes);
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
            disabled = disabled == null ? List.of() : List.copyOf(disabled);
            refeed = refeed == null ? List.of() : List.copyOf(refeed);
        }

        /**
         * 这一轮的改动<b>还在磁盘上</b>（没被撤回、快照还在）。
         *
         * <p>为什么判据是「快照还在」：快照被 {@code markPending} 过就等于引擎自己的那一个 bit
         * ——「这份改动过了校验、正等人处置」。自己去列状态名，迟早会和这一 bit 对不上，
         * 而不对上的后果是「撤回本轮」去撤一份磁盘上根本不在的改动。
         */
        public boolean live() {
            return !undone && snapshot != null;
        }

        /** 这一轮<b>没留下改动</b>（跑失败/被中断/模型说缺料，它自己回滚了）。 */
        public boolean removed() {
            return !undone && snapshot == null;
        }

        /**
         * 这一轮通过率的<b>分母</b>：这一轮冻结清单里活着的用例条数。
         *
         * <p>口径与界面上那一行完全一样（{@code casePassRate}）：分母是清单，不是脚本报回来几条
         * ——脚本一条都没跑时，「清单上有 5 条」才是事实。停用的用例退出分母（十八.20）。
         */
        public int total() {
            Set<Integer> off = new TreeSet<>(disabled);
            if (testCases != null && !testCases.isEmpty()) {
                return (int) testCases.stream().filter(one -> !off.contains(one.index())).count();
            }
            return tests == null || tests.cases() == null ? 0 : tests.cases().size();
        }

        /**
         * 这一轮通过率的<b>分子</b>：清单里活着的用例中报成「过了」的那几条。
         *
         * <p>还没跑到的算没过——「没验」不能算「过了」（十五.9 那条口径）。
         */
        public int passed() {
            Set<Integer> off = new TreeSet<>(disabled);
            Map<Integer, Boolean> reported = reported();
            if (testCases != null && !testCases.isEmpty()) {
                return (int) testCases.stream()
                        .filter(one -> !off.contains(one.index()))
                        .filter(one -> Boolean.TRUE.equals(reported.get(one.index())))
                        .count();
            }
            return (int) reported.values().stream().filter(Boolean.TRUE::equals).count();
        }

        /** 「必须过 3/3 · 建议过 4/5 …」的那种分档通过率不在这一层算：它是界面上的说法（见前端同名函数）。 */
        private Map<Integer, Boolean> reported() {
            Map<Integer, Boolean> byIndex = new LinkedHashMap<>();
            if (tests != null && tests.cases() != null) {
                for (TestOutcome.CaseResult one : tests.cases()) {
                    byIndex.put(one.index(), one.passed());
                }
            }
            return byIndex;
        }
    }

    /**
     * 从留档里折出一个会话。
     *
     * @param id        会话 id（= 第 1 轮那条记录的 id）
     * @param records   属于这个会话的全部留档（顺序无所谓，这里按轮次序号排）
     * @param snapshots 快照根目录下现存的快照目录名（{@code WorkspaceSnapshot.names}）；
     *                  传空表就是「一份快照都没有」——没开快照的项目、以及刚撤完的时候
     */
    public static Session of(String id, List<RunRecord> records, List<String> snapshots) {
        List<RunRecord> ordered = new ArrayList<>(records == null ? List.of() : records);
        ordered.sort(Comparator.comparingInt(record -> record.session().round()));
        List<String> names = snapshots == null ? List.of() : snapshots;
        List<Round> rounds = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            RunRecord record = ordered.get(i);
            String until = i + 1 < ordered.size() ? ordered.get(i + 1).startedAt() : null;
            rounds.add(roundOf(record, snapshotIn(record.startedAt(), until, names)));
        }
        return new Session(id, rounds);
    }

    /**
     * 这个会话<b>已经收场了</b>吗（被接受或中断过）。
     *
     * <p>判据是「这个会话里<b>有没有哪一轮</b>带着出口那一档」，而不是「最后那一轮的收场是什么」：
     * 收场是<b>会话级</b>的动作，落档时会给当时还没收场的每一轮都写上同一档（见 {@code Teardown}），
     * 但<b>被撤回过的那几轮不覆盖</b>——它们保留 {@code UNDO_ROUND}（以及老记录里的
     * {@code UNDO_SESSION}），因为那才是那一轮真实的下场。于是「最后那一轮」完全可能顶着一个
     * 撤回档，而会话其实已经接受过了：只看最后那一轮，会把一个已经收场的会话永远当成开着的。
     *
     * <p>撤回<b>不算</b>收场：撤完会话还开着，用户随时能接着跑下一轮
     * （见 {@code RunRecord.Settlement.closes}）。
     */
    public static boolean closed(List<RunRecord> records) {
        for (RunRecord record : records == null ? List.<RunRecord>of() : records) {
            if (record == null || record.session() == null || record.settlement() == null) {
                continue;
            }
            if (RunRecord.Settlement.closes(record.settlement().choice())) {
                return true;
            }
        }
        return false;
    }

    /** 一条留档折成一轮。 */
    private static Round roundOf(RunRecord record, String snapshot) {
        RunRecord.Settlement settlement = record.settlement();
        String choice = settlement == null ? null : settlement.choice();
        boolean undone = settlement != null && RunRecord.Settlement.undoes(choice);
        List<Integer> refeed = record.refeed() == null ? List.of() : record.refeed().cases();
        List<PlanReview.TestCase> cases = record.testCases();
        TestOutcome tests = record.tests();
        Round round = new Round(record.session().round(), record.id(), record.status(), record.detail(),
                record.startedAt(), record.finishedAt() == null ? "" : record.finishedAt(),
                // 成本用 modelCalls 而不是 attempts：attempts 只是开发轮次，一轮下来
                // 还有第二段、生成测试那几笔（口径见 RunRecord.modelCalls），实测差过 1 vs 3
                record.modelCalls(), record.cost(), millisOf(record),
                // 收场那句话由记录那一层算（`RunRecord.settlementSummary`）：会话里的中断
                // 撤的是整个会话，说法和单次运行不一样，而「属不属于会话」只有它知道
                snapshot, undone, choice, record.settlementSummary(),
                stillFailing(record),
                cases, tests, record.changes(), record.verdicts(),
                sorted(record.disabledIndexes()), refeed, record.coverage());
        return round;
    }

    /**
     * 这一轮<b>还有失败</b>吗（连续三轮那条软提示数的就是它）。
     *
     * <p>两种都算：跑过测试而失败清单非空，或者这一轮压根没成功（终态不是成功那两档）。
     * 只看失败清单会漏掉「编译就没过」的那几轮——那种轮次连测试都没跑到，
     * 而它显然是「没做成」；只看终态又会漏掉「改动落盘了、测试红着」的那一档，
     * 而那一档的终态名恰恰不叫失败（{@code TESTS_FAILED} 是「等人判」，不是「失败」）。
     */
    private static boolean stillFailing(RunRecord record) {
        boolean succeeded = "SUCCESS".equals(record.status()) || "SUCCESS_UNVERIFIED".equals(record.status());
        if (!succeeded) {
            return true;
        }
        return record.tests() != null && !record.tests().failingCases().isEmpty();
    }

    /** 这一轮花了多久：跑完时刻减开工时刻。算不出来（老记录、时钟乱）时是 0，不编一个数。 */
    private static long millisOf(RunRecord record) {
        if (record.finishedAt() == null || record.finishedAt().isBlank()) {
            return 0L;
        }
        try {
            return java.time.Duration.between(LocalDateTime.parse(record.startedAt()),
                    LocalDateTime.parse(record.finishedAt())).toMillis();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    private static List<Integer> sorted(Set<Integer> indexes) {
        return List.copyOf(new TreeSet<>(indexes == null ? Set.of() : indexes));
    }

    /**
     * 落在 {@code [from, until)} 这段时间里的那份快照（没有就返回 {@code null}）。
     *
     * <p>判据的来路：快照目录名是拍它的那一刻（毫秒），而每一轮的开工时刻记在留档的
     * {@code startedAt} 里，且快照一定拍在开工之后（录制器先建记录、引擎再拍快照）、
     * 下一轮开工之前（一次只跑一个运行）。于是「落在第 k 轮窗口里的那份快照」就是第 k 轮拍的。
     * {@code until} 是下一轮的开工时刻；最后那一轮传 {@code null} = 不设上界。
     *
     * <p><b>为什么不干脆在留档里存一个快照名</b>（这样就没有「猜」了）：快照目录名是一个
     * <b>会变</b>的东西（过了校验就加 {@code .pending} 后缀），把一个会变的字符串钉进留档，
     * 就多出一种「留档说快照叫 A、磁盘上只有 A.pending」的假故障。
     *
     * <p><b>⚠️ 这条判据的风险（用户 2026-10-03 拍板「先这样」，但风险必须写在这里）：
     * 它假设两个时间戳的精度是一致的，而它们本来不是。</b>
     * 留档的 {@code startedAt} 是 {@code LocalDateTime.toString()}——带<b>纳秒</b>
     * （{@code 2026-10-03T12:35:55.541343500}），快照名只到<b>毫秒</b>
     * （{@code …-123555-541}）。精度对不上时窗口会整体错位：同一毫秒里拍的那份快照
     * 会被算成「比开工还早」，于是<b>这一轮认不到自己的快照</b>——它被当成「没留下改动」，
     * 那一枚撤销按钮变灰、累计通过率把它漏掉。<b>这个坑真踩过一次</b>（§19.3），
     * 现在两边一律 {@link #parse} 到毫秒兜住。
     *
     * <p>兜不住的那一种只剩「<b>窗口和窗口本身对不上</b>」：同一毫秒里开两轮
     * （{@code from} 与 {@code until} 相等），后一轮会认领到前一轮的快照，
     * 也就是<b>撤回调错一份快照</b>、或者某一轮被当成没留下改动。真实运行里每一轮要几分钟，
     * 不可能发生；测试里靠 30ms 的间隔避开它（见 {@code SessionSettleTest.session}）。
     * 以后真要做「闪电一样快的无模型运行」，这条判据就得换成「在留档里存一个稳定 ID
     * + 快照目录名里带上那个 ID」，而不是把窗口调得更细。
     */
    private static String snapshotIn(String from, String until, List<String> names) {
        LocalDateTime start = parse(from);
        LocalDateTime end = parse(until);
        String found = null;
        for (String name : sortedNames(names)) {
            LocalDateTime at = parse(name);
            if (at == null || start == null || at.isBefore(start)) {
                continue;
            }
            if (end != null && !at.isBefore(end)) {
                continue;
            }
            // 一个窗口里理论上只会有一份（一次只跑一个运行）。真出现两份（比如上一份的残骸）
            // 就取<b>最后</b>那份：撤回到最近的进入点，比撤回到一个中间态安全
            found = name;
        }
        return found;
    }

    /** 快照目录名按时间排（名字本身就是时间戳，所以按字符串排就等于按时间排）。 */
    private static List<String> sortedNames(List<String> names) {
        List<String> sorted = new ArrayList<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                sorted.add(name);
            }
        }
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    /**
     * 快照目录名 → 时刻：去掉 {@code .pending} 后缀再按格式解。解不出来返回 {@code null}。
     *
     * <p><b>一律取到毫秒</b>：快照名里的时刻只有毫秒（{@code yyyyMMdd-HHmmss-SSS}），
     * 而留档的 {@code startedAt} 是 {@code LocalDateTime.toString()}——它带着<b>纳秒</b>
     * （{@code 2026-10-03T12:35:55.541343500}）。两边按原样比，同一毫秒里拍的那份快照会被算成
     * 「比开工还早」，于是这一轮被认为「没留下改动」——实测就是这么踩到的：
     * 会话里每一轮都认不到自己的快照，那一枚撤销按钮全灰。
     * 往下取整只会把窗口放宽，不会把别人的快照算进来（那一轮的开工时刻差着几十毫秒以上）。
     */
    private static LocalDateTime parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String text = name.strip();
        if (text.endsWith(".pending")) {
            text = text.substring(0, text.length() - ".pending".length());
        }
        try {
            // 记录里的 startedAt 是 ISO 的（LocalDateTime.toString()），快照名是紧凑时间戳，两种都认
            LocalDateTime at = text.contains("T") ? LocalDateTime.parse(text)
                    : LocalDateTime.parse(text, STAMP);
            return at.truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 最新那一轮；一轮都没有时是空。 */
    public Optional<Round> current() {
        return rounds.isEmpty() ? Optional.empty() : Optional.of(rounds.get(rounds.size() - 1));
    }

    /** 现在第几轮（最新那一轮的序号）；一轮都没有时是 0。 */
    public int round() {
        return current().map(Round::round).orElse(0);
    }

    /**
     * 还<b>算数</b>的轮次：没被撤回、改动还在磁盘上。
     *
     * <p>三个地方问它：累计通过率（被撤回的不算）、那一步撤销要动哪份快照、
     * 以及「这一次运行能不能开工」（开着的会话，它自己那几份快照不算挡路）。
     */
    public List<Round> live() {
        return rounds.stream().filter(Round::live).toList();
    }

    /** 没被撤回的轮次（含「自己回滚了」的那种）：连续失败那条软提示数的就是它。 */
    public List<Round> chain() {
        return rounds.stream().filter(round -> !round.undone()).toList();
    }

    /** 全部轮次，新 → 旧（界面上的历史轮次按这个顺序铺）。 */
    public List<Round> newestFirst() {
        List<Round> reversed = new ArrayList<>(rounds);
        reversed.sort(Comparator.comparingInt(Round::round).reversed());
        return List.copyOf(reversed);
    }

    /**
     * 从最新一轮往回数，连着几轮「还有失败」。
     *
     * <p>被撤回的轮次不算（用户已经回到前面去了，从那儿往后数没有意义）。
     */
    public int consecutiveFailing() {
        int count = 0;
        List<Round> chain = chain();
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (!chain.get(i).failing()) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * 连续 {@value #SOFT_HINT_ROUNDS} 轮都还有失败时的那句软提示；没到就是空串。
     *
     * <p>措辞要<b>温和</b>，而且必须自己说清「它不挡任何事」：这行字的用处是让人停下来想一想
     * 「是不是需求本身要改，或者用例写错了」，不是替人叫停。引擎不做最大轮数自动放弃——
     * 用户的口径是「一直到满意」。
     */
    public String softHint() {
        int streak = consecutiveFailing();
        if (streak < SOFT_HINT_ROUNDS) {
            return "";
        }
        return "你已经连着 " + streak + " 轮都还有失败。要不要先看一眼：是需求本身要改，"
                + "还是这几条用例写错了？（这只是提醒，不影响你接着点「下一轮」。）";
    }

    /** 会话累计通过率的分子：没被撤回的轮次里报成「过了」的用例条数。 */
    public int passed() {
        return chain().stream().mapToInt(Round::passed).sum();
    }

    /** 会话累计通过率的分母：没被撤回的轮次里清单上活着的用例条数。 */
    public int total() {
        return chain().stream().mapToInt(Round::total).sum();
    }

    /**
     * 「撤回本轮」现在能不能点。
     *
     * <p>不能点的两种情形各有各的话（见 {@link #undoRoundWhy()}）：没有轮次、
     * 或者最新那一轮一个字节都没留在磁盘上（它自己回滚了）——那种时候磁盘本来就已经在
     * 上一轮结束时的样子，再撤一次是撤掉别人的改动。
     */
    public boolean canUndoRound() {
        return undoRoundWhy().isEmpty();
    }

    /** 「撤回本轮」点不了的原因，一句人话；能点时空串。 */
    public String undoRoundWhy() {
        Round last = current().orElse(null);
        if (last == null) {
            return "这个会话一轮都还没跑过";
        }
        if (last.undone()) {
            return "最后一轮已经撤过了：要接着跑就点「下一轮」";
        }
        if (last.removed()) {
            return "最后一轮没在磁盘上留下改动（它自己回滚了）：这会儿没有可撤的东西";
        }
        return "";
    }

    /**
     * 这份快照是<b>这个会话的</b>吗（它落在某一轮的时间里）。
     *
     * <p>它的用处只有一处，但很关键：引擎拒绝在「还有没处置的快照」之上开工，
     * 而会话开着的正常状态就<b>是</b>磁盘上挂着几份快照（等用户点接受或中断）。
     * 不区分「自己人的」和「别人的」，就没法区分「接着跑下一轮」和
     * 「上一次别的运行留下了东西还没处置」——前者是会话的日常，后者必须拦。
     */
    public boolean owns(String snapshotName) {
        if (snapshotName == null || rounds.isEmpty()) {
            return false;
        }
        for (int i = 0; i < rounds.size(); i++) {
            String from = rounds.get(i).startedAt();
            String until = i + 1 < rounds.size() ? rounds.get(i + 1).startedAt() : null;
            if (snapshotName.equals(snapshotIn(from, until, List.of(snapshotName)))) {
                return true;
            }
        }
        return false;
    }
}
