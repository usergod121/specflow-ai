package com.specflow.history;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specflow.env.EnvRegistration;
import com.specflow.review.AcceptanceCoverage;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanStep;
import com.specflow.spec.ContextItem;
import com.specflow.tests.Refeed;
import com.specflow.tests.TestOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一次运行的完整留档。
 *
 * <p>存在的理由：模型说「我还缺某样东西」而停下来的时候，<b>代码已经回滚了</b>——
 * 磁盘上看不出它到底做了什么。那份信息只存在于这次运行的过程中，
 * 不留下来就等于没有。所以这里存的不是代码，是<b>过程</b>：
 * 它打算做什么、实际改了哪些文件、为什么停。
 *
 * <p>它同时是出错时的第一手材料：把这份记录贴给任何人（或任何模型），
 * 对方都能完整还原当时发生了什么，不需要你在一旁回忆。
 *
 * @param id        时间戳形式的标识，同时也是文件名
 * @param startedAt 开始时间，ISO 格式
 * @param status    终态，与 {@code AgentResult.Status} 同名
 * @param template  使用的模板名；不用模板时为空
 * @param prompt    当时的<b>需求</b>——留档里最该有的一项
 * @param acceptance 当时的验收标准
 * @param context   当时给模型的上下文依赖。它同样是「当时依据什么」的一部分：
 *                  引用形态只记路径，文件早改了；内联文本则被截断过（见 {@link RunRecorder}）
 * @param targets   本次允许改动的文件
 * @param attempts  实际调用了模型几次
 * @param detail    面向人的结论
 * @param missing   检查阶段报出的缺失依赖；没跑过检查时为空
 * @param changes   落盘过的改动（失败时这些改动已回滚，但差异本身留在这里）
 * @param steps     按施工单的步记下的过程。分步执行时每一步一条；
 *                  单步执行（没有施工单）时也是<b>一条</b>——那一步就是整次运行，
 *                  引擎为它不发步级事件，由录制器按运行结果补上（见 {@code RunRecorder}），
 *                  否则同一次运行在历史详情里会有两种说法。
 *                  老记录里没有这一项，读出来是 {@code null}
 * @param planSteps 这次运行定下来的<b>完整施工单</b>（引擎拿去走的那些步，一步不缺）。
 *                  与 {@code steps} 的分工：那个是<b>跑完之后</b>的账（只记跑到了的步子、
 *                  带着每步的终态与改动），这个是<b>开工时</b>的图。续跑要用的是它——
 *                  挂起时磁盘已经回滚，只有这份留档还答得出「上次打算分几步做」，
 *                  否则续跑会为同一份单子再付一次模型调用。
 *                  老记录里没有这一项，读出来是 {@code null}（那一次续跑只能现生成）
 * @param stepsSource 施工单是从哪来的（{@code APPROVED} / {@code GENERATED} / {@code RESUMED}
 *                    / {@code SINGLE}）；没有施工单这个概念的记录里是 {@code null}
 * @param testCases 检查阶段定下来的<b>用例清单</b>（这次打算验什么）。它和 {@code tests} 的分工：
 *                  这个是<b>开工时</b>的清单，那个是<b>跑完之后</b>的账。要算「必须过 3/3」这种
 *                  通过率，两个都得有——只记失败的那几条，分母就没了。
 *                  老记录、以及没跑过检查的运行里没有这一项，读出来是 {@code null}
 * @param tests     测试阶段那一次的结果（产物、退出码、失败清单）。没有用例清单就不会有它
 * @param environment <b>测试环境</b>这一次的登记：起了哪些容器、卷、网络，跑了哪几条
 *                  init/reset，环境是好是坏（十五.8 的第一件「登记」）。
 *                  没声明环境（没有 {@code env.yaml}）、或这次只跑了单元测试时是 {@code null}——
 *                  留档里于是没有这一项，而不是写一个空壳假装跑过环境
 * @param verdicts 人对<b>每一条失败用例</b>的判断（十五.6 里落在用例上的那三条路）：
 *                 开发 AI 错了（回喂）、测试代码错了（重新生成）、不重要 / 误报（标成已知失败）。
 *                 为什么它必须落档：这些是<b>人做的证据</b>——只留在界面上就等于刷新一下就没了，
 *                 而事后翻记录的人正是靠它们解释「为什么那几条红的最后没被当成问题」。
 *                 <b>旧记录里这一栏叫 {@code knownFailures}</b>，只有编号与时间，见 {@link Verdict}
 * @param settlement 用户把这次运行<b>怎么了结</b>的（十五.8：接受 / 中断），以及那一刻还带着
 *                 几条失败用例。它和 {@code verdicts} 的分工：那个是逐条怎么判的，
 *                 这个是整次运行最后怎么收的场
 * @param regenerated 人判定「测试代码错了」之后<b>重新生成</b>的那几份产物目录（十五.6 第二条路）。
 *                 为什么要单独记：重新生成会开一个新的 {@code tools/<时间戳>/}，而留档里本来只有
 *                 它跑过的那一份——收场时按留档删产物就会漏掉这几份，{@code tools/} 于是只增不减
 * @param timeline  逐条的过程记录
 * @param coverage  这次<b>机器数出来的验收覆盖</b>：哪条验收标准一条用例都没覆盖、哪几条必须过的
 *                 用例没写对应哪条验收标准（见 {@code AcceptanceCoverage}）。两个数都该是 0。
 *                 与 {@code testCases} 的分工：那个是「打算验什么」，这个是「验收标准那边有没有被漏掉」。
 *                 没写验收标准、或者没走过检查的记录里没有这一项，读出来是 {@code null}
 * @param refeed    <b>这一轮回喂给开发的失败用例</b>（十五.6 第一条路真正落地的那一步）：
 *                 回喂了哪几条、以及拼进提示词的那段原文（见 {@code Refeed}）。
 *                 为什么要连原文一起留：它是「这一轮到底按什么在改」的唯一答案，
 *                 而实测里那一轮它<b>一个字节都没改</b>（写入内容与旧版逐字相同）——
 *                 不留这一段，事后谁也说不清那次为什么白跑。不是「下一轮」的运行里没有它
 * @param unchanged 回喂之后<b>它到底改没改</b>：这一轮有回喂、而产品改动一处差异都没有时为
 *                 {@code true}（「它没有改动」）。不是回喂的运行里没有这一项——普通运行没改文件
 *                 是另一件事，不该顶着这句话出现在结果面板上
 * @param caseSwitches <b>谁在什么时候停用了哪几条用例</b>（用户口头语是「删掉它」，
 *                 但这里记的是可恢复的停用）。它是一份<b>追加的流水</b>，不是「当前状态」：
 *                 停用与恢复各记一条，当下的状态由 {@link #disabledIndexes()} 从流水里折出来。
 *                 为什么非要留这一栏：停用会让一条用例<b>退出所有分母</b>
 *                 （通过率、溯源、回喂、覆盖核对都不再算它），事后翻记录的人必须答得出
 *                 「当时是谁、为什么这几条不见了」——只留一个状态字段，恢复过的那几条就查无实据。
 *                 老记录里没有这一项，读出来是 {@code null}（= 一条都没停用过）
 * @param session   这次运行<b>属于哪个会话的第几轮</b>（见 {@link SessionRef}）。
 *                 它为什么必须落档：会话是 N 轮串起来的一件事，「按会话翻记录」要答得出
 *                 「这个会话一共几轮、每轮什么结论」——只靠时间先后去猜，翻的人分不清
 *                 「同一件事的第三轮」和「另一件恰好接着做的事」。
 *                 老记录、以及命令行那条路（没有会话这个概念的调用方）读出来是 {@code null}
 * @param finishedAt 这次运行<b>跑完的时刻</b>，ISO 格式。它和 {@code startedAt} 一起回答
 *                 「这一轮花了多久」——会话视图上要显示的成本（调用次数 + 耗时）里的那一半。
 *                 为什么不拿记录文件的修改时间去算：那份文件跑完之后还会被人改写
 *                 （判决、停用、收场都重写它），改完那个时间戳就成了「人最后一次点按钮的时刻」。
 *                 老记录里没有这一项，读出来是 {@code null}（耗时那一栏于是不显示，不编一个数）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunRecord(
        String id,
        String startedAt,
        String status,
        String template,
        String prompt,
        List<String> acceptance,
        List<ContextItem> context,
        String requirementId,
        List<String> targets,
        int attempts,
        String detail,
        List<PlanReview.MissingItem> missing,
        List<Change> changes,
        List<Step> steps,
        List<PlanStep> planSteps,
        String stepsSource,
        List<PlanReview.TestCase> testCases,
        TestOutcome tests,
        EnvRegistration environment,
        @JsonAlias("knownFailures") List<Verdict> verdicts,
        Settlement settlement,
        List<String> regenerated,
        List<Line> timeline,
        AcceptanceCoverage.Report coverage,
        Refeed refeed,
        Boolean unchanged,
        List<CaseSwitch> caseSwitches,
        SessionRef session,
        String finishedAt
) {

    /**
     * 这次运行属于<b>哪个会话的第几轮</b>（会话 = N 轮，见 {@code Session}）。
     *
     * <p>为什么是「会话 id + 轮次序号」两个字段，而不是一条链（上一轮是哪条记录）：
     * 轮次序号就是链——第 N 轮的上一轮必然是第 N-1 轮，而序号还顺带答得出
     * 「这是第几轮」这个界面上要显示、事后也最常被问到的数。存一条指针链的话，
     * 翻记录的人得自己往回爬 N 步才知道现在是第几轮。
     *
     * <p>{@code id} 就是<b>第 1 轮那条记录的 id</b>（会话是那一轮开的），
     * 所以两者同形、同排序规则；落档那一刻才知道第一条记录的 id，见 {@link #withId(String)}。
     *
     * @param id    会话 id；空串 = 「这一轮开一个新会话」，落档时补上这一轮的记录 id
     * @param round 第几轮，从 1 开始
     */
    public record SessionRef(String id, int round) {

        public SessionRef {
            id = id == null ? "" : id.strip();
            // 轮次序号不接受 0 或负数：这个类型的存在本身就意味着「属于某个会话的某一轮」，
            // 不属于会话的运行里这一栏整个是 null（见 RunRecord.session）
            if (round < 1) {
                throw new IllegalArgumentException("会话的轮次序号从 1 开始，收到的是 " + round);
            }
        }

        /** 开一个新会话的第一轮：会话 id 还不知道，落档时补。 */
        public static SessionRef opening() {
            return new SessionRef("", 1);
        }

        /** 接着一个已有会话往下走。 */
        public static SessionRef next(String sessionId, int round) {
            return new SessionRef(sessionId, round);
        }

        /**
         * 落档那一刻补上会话 id（= 这一轮的记录 id）。
         *
         * <p>为什么不在开工前就把 id 定下来：id 就是记录 id，而记录 id 是录制器
         * 构造时按时刻生成的（见 {@code RunRecorder}）。在这里补，是为了让
         * 「会话 id == 第 1 轮的记录 id」这条对应关系只有一个来源——两处各生成一个 id，
         * 迟早出现「会话文件指向一条不存在的记录」。
         */
        public SessionRef withId(String recordId) {
            return id.isEmpty() ? new SessionRef(recordId, round) : this;
        }
    }

    /**
     * 一次「停用 / 恢复」的动作。
     *
     * <p><b>为什么是可恢复的停用，而不是删掉。</b>用户的原话是「删掉它」，但真正想表达的
     * 是「这条别再算了」——而删除是不可逆的：清单是冻结的，删掉一条之后既没有依据说清
     * 「当时为什么少了一条」，也没法把它拿回来。停用把这两件事都保住：它退出所有分母、
     * 不参与回喂、引擎也不再要求它被实现，而人随时可以恢复。
     * 这也是留档里那条流水存在的理由（见 {@code caseSwitches}）。
     *
     * @param index    用例编号（和 {@code testCases} 对得上）
     * @param disabled {@code true} = 这一步是停用，{@code false} = 恢复
     * @param by       谁做的。本机工具没有登录这一回事（{@link #actor()}），
     *                 操作系统账号是最诚实的那一个「谁」
     * @param at       什么时候，ISO 格式
     */
    public record CaseSwitch(int index, boolean disabled, String by, String at) {

        public CaseSwitch {
            by = by == null ? "" : by.strip();
            at = at == null ? "" : at;
        }

        /**
         * 本机这台工具没有登录概念——它就是一个人在自己机器上用的东西。
         * 与其编一个「当前用户」出来，不如照实记操作系统账号：它至少是可核对的。
         */
        public static String actor() {
            String name = System.getProperty("user.name", "");
            return name == null || name.isBlank() ? "（不知道是谁）" : name;
        }

        /**
         * 往流水尾部追加几步动作，返回新的流水。
         *
         * <p>两条规矩：<b>只追加、不改写</b>（恢复也要留一条，否则「谁什么时候停用过它」
         * 就没了）；<b>同一条用例最后一步已经是这个状态时不重复记</b>——
         * 界面重画一次就多一条流水的话，真正的动作会被噪声埋掉。
         *
         * @param existing 已有的流水（可能是 {@code null}）
         * @param indices  这一次动的用例编号
         * @param at       这次动作的时刻
         */
        public static List<CaseSwitch> append(List<CaseSwitch> existing, List<Integer> indices,
                                              boolean disabled, String at) {
            List<CaseSwitch> log = new ArrayList<>();
            if (existing != null) {
                existing.stream().filter(item -> item != null).forEach(log::add);
            }
            if (indices == null) {
                return List.copyOf(log);
            }
            String now = at == null ? "" : at;
            String by = actor();
            for (Integer index : new TreeSet<>(indices)) {
                if (index == null || index <= 0 || stateOf(log, index) == disabled) {
                    continue;
                }
                log.add(new CaseSwitch(index, disabled, by, now));
            }
            return List.copyOf(log);
        }

        /**
         * 从流水里折出「此刻还停用着的编号」。
         *
         * <p>折出来而不是另存一个状态字段：两份数据迟早对不上，而对不上的表现是
         * 「界面上停着、引擎里没停」——那会让一条用例既不进分母、又照样被要求实现。
         */
        public static Set<Integer> disabledIn(List<CaseSwitch> log) {
            Set<Integer> off = new TreeSet<>();
            if (log == null) {
                return off;
            }
            for (CaseSwitch item : log) {
                if (item == null) {
                    continue;
                }
                if (item.disabled()) {
                    off.add(item.index());
                } else {
                    off.remove(item.index());
                }
            }
            return off;
        }

        /** 这条用例此刻是不是停着（流水里最后一次动作说了算）。 */
        private static boolean stateOf(List<CaseSwitch> log, int index) {
            return disabledIn(log).contains(index);
        }
    }

    public RunRecord {
        caseSwitches = caseSwitches == null ? null : List.copyOf(caseSwitches);
    }

    /**
     * 此刻还停用着的用例编号。
     *
     * <p>它才是分母口径的唯一来源：通过率、溯源连线、回喂、覆盖核对四处都问它。
     * 老记录里那一栏是 {@code null}，折出来就是空集合——「老记录里一条都没停用过」。
     */
    public Set<Integer> disabledIndexes() {
        return CaseSwitch.disabledIn(caseSwitches);
    }

    /**
     * 人对<b>一条失败用例</b>的判断（十五.6）。
     *
     * <p>三档不是三种严重度，而是「接下来该谁动手」：产品代码错了就回喂给开发，
     * 测试代码错了就重新生成，两条都不认就是「不重要 / 误报」。机器判不了这一条——
     * 硬判就会逼出「为了过一条写错的用例，把正确代码改成错的」——所以它只能由人写下来。
     *
     * @param index 用例编号（和 {@code testCases} 对得上）
     * @param owner 谁错了，见 {@link #CODE} / {@link #TEST} / {@link #KNOWN}
     * @param at    判定的时间，ISO 格式。<b>它记的是「哪一刻人的判断变了」</b>——
     *              同一条用例上一轮算问题、这一轮不算，只有时间分得清
     */
    public record Verdict(int index, String owner, String at) {

        /** 开发 AI 错了：这几条被<b>回喂</b>给开发（十五.6 第一条路）。 */
        public static final String CODE = "CODE";
        /** 测试代码错了：Test Agent <b>重新生成</b>过这批测试代码（第二条路）。 */
        public static final String TEST = "TEST";
        /** 不重要 / 误报：标成已知失败，接受时不算它（第三条路）。 */
        public static final String KNOWN = "KNOWN";

        public Verdict {
            owner = normalize(owner);
            at = at == null ? "" : at;
        }

        /**
         * 认这一栏。
         *
         * <p><b>空值必须落成 {@link #KNOWN}</b>：旧记录里这一栏叫 {@code knownFailures}，
         * 每条只有 {@code index} 和 {@code at}——那时候唯一存在的判断就是「已知失败」。
         * 不认这个形状，历史面板里那几次运行的记号会整个消失，而消失是<b>静默</b>的
         * （{@code RunStore.read} 捕掉异常返回空），用户只会看到历史莫名少了几条。
         *
         * <p>认不出来的词<b>不假装认识</b>：原样留着，界面上显示成「没认出来的判断」——
         * 归到三档里的任何一档都是在替人改口供。
         */
        private static String normalize(String owner) {
            if (owner == null || owner.isBlank()) {
                return KNOWN;
            }
            String text = owner.strip().toUpperCase(Locale.ROOT);
            return switch (text) {
                case CODE, TEST, KNOWN -> text;
                default -> text;
            };
        }

        /** 这一档的中文说法。 */
        @JsonProperty(value = "label", access = JsonProperty.Access.READ_ONLY)
        public String label() {
            return switch (owner) {
                case CODE -> "开发 AI 错了（已回喂）";
                case TEST -> "测试代码错了（已重新生成）";
                case KNOWN -> "不重要 / 误报（已知失败）";
                default -> "没认出来的判断：" + owner;
            };
        }
    }

    /**
     * 人把这次运行<b>怎么了结</b>的（十五.8）。
     *
     * <p>两种收场都落这一栏，因为它们回答的是同一个问题：磁盘上的改动最后留没留。
     * 「接受时带着几条失败用例」也必须写在这里——那是这次运行最重要的一个事实，
     * 而它过几天再看就不明显了：失败清单还挂在记录里，但「人是知道它红着也接受了」这件事，
     * 只有这一栏说得出来。
     *
     * @param choice  {@link #ACCEPT}（保留改动）或 {@link #INTERRUPT}（恢复到运行前）
     * @param at      收场的时间，ISO 格式
     * @param failing 收场那一刻<b>还带着的失败用例编号</b>。中断时同样记下来：
     *                文件是回滚了，但「它当时红在哪几条上」是这次运行的结论，不该跟着一起没
     */
    public record Settlement(String choice, String at, List<Integer> failing) {

        /** 接受：磁盘上的改动留着，快照与测试产物删掉。 */
        public static final String ACCEPT = "ACCEPT";
        /** 中断（恢复到初始）：文件按快照回到运行前，快照与测试产物删掉。 */
        public static final String INTERRUPT = "INTERRUPT";
        /**
         * 撤回本轮：这一轮的改动被撤掉，磁盘回到<b>上一轮结束时</b>的样子。
         *
         * <p>它和 {@link #INTERRUPT} 的分工：中断是<b>会话的出口</b>（撤完就不再往下跑了），
         * 撤回本轮只是一步撤销——会话还开着，用户随时可以点「下一轮」再来一遍。
         */
        public static final String UNDO_ROUND = "UNDO_ROUND";
        /**
         * 撤回整个会话：磁盘回到<b>会话最开始</b>的样子，会话仍然开着。
         */
        public static final String UNDO_SESSION = "UNDO_SESSION";

        /**
         * 这个收场是不是<b>会话的出口</b>（接受了 / 中断了）。
         *
         * <p>它决定「这个会话还开着吗」：会话开着 = 最后那一轮的收场不是这两档。
         * 撤回也是收场（它一样要落档），但撤回完会话还开着——把撤回也算成出口的话，
         * 用户撤一次就再也点不了「下一轮」了。
         */
        public static boolean closes(String choice) {
            return ACCEPT.equals(normalizeChoice(choice)) || INTERRUPT.equals(normalizeChoice(choice));
        }

        /**
         * 这个收场是不是「人把某几轮撤掉了」。
         *
         * <p>会话里「哪几轮还算数」就靠它折出来：被撤掉的轮次不进累计通过率，
         * 也不再有可撤的快照。
         */
        public static boolean undoes(String choice) {
            return UNDO_ROUND.equals(normalizeChoice(choice))
                    || UNDO_SESSION.equals(normalizeChoice(choice));
        }

        private static String normalizeChoice(String choice) {
            return choice == null ? "" : choice.strip().toUpperCase(Locale.ROOT);
        }

        public Settlement {
            choice = choice == null ? "" : choice.strip().toUpperCase(Locale.ROOT);
            at = at == null ? "" : at;
            failing = failing == null ? List.of() : List.copyOf(failing);
        }

        /** 收场那一刻带着几条失败用例——「用户接受时带着 N 条失败」里的那个 N。 */
        @JsonProperty(value = "failures", access = JsonProperty.Access.READ_ONLY)
        public int failures() {
            return failing.size();
        }

        /** 给界面与历史的一行。 */
        @JsonProperty(value = "summary", access = JsonProperty.Access.READ_ONLY)
        public String summarize() {
            String what = switch (choice) {
                case ACCEPT -> "已接受（改动留在磁盘上）";
                case INTERRUPT -> "已中断（文件恢复到这个运行开始前）";
                case UNDO_ROUND -> "已撤回本轮（文件回到上一轮结束时的样子）";
                case UNDO_SESSION -> "已撤回整个会话（文件回到会话最开始的样子）";
                default -> "已收场：" + choice;
            };
            if (failing.isEmpty()) {
                return what + "；当时没有失败用例";
            }
            StringBuilder indexes = new StringBuilder();
            for (Integer index : failing) {
                indexes.append(indexes.length() == 0 ? "" : "、").append(index);
            }
            return what + "；当时带着 " + failing.size() + " 条失败用例（用例 " + indexes + "）";
        }
    }

    /**
     * 一条落盘的改动。
     *
     * @param diff 行级差异，在回滚<b>之前</b>就算好了——回滚之后文件内容还原，差异再也算不出来
     */
    public record Change(String path, boolean created, int bytes, String diff) {
    }

    /**
     * 施工单上的一步，跑完之后的样子。
     *
     * <p>为什么要按步存而不是只存一份总的 changes：一次分步运行的改动是<b>按步累积</b>的，
     * 事后想回答「第 3 步当时动了什么、为什么重试了两次」，只有总账是答不出来的。
     *
     * @param index        第几步，从 1 开始
     * @param goal         这一步做什么
     * @param intermediate 这一步做完是否允许整项目编不过
     * @param state        终态：{@code SUCCESS} / {@code INTERMEDIATE} / {@code FAILED}，
     *                     与 {@code AgentListener.StepState} 同名
     * @param rounds       这一步实际调了几次模型（含失败的那些）
     * @param changes      这一步落盘的改动。步内重试前被回滚掉的那一版不算在它名下
     *                     （磁盘上已经不在了）；但整次运行回滚时已经落盘的改动会留着——
     *                     磁盘上同样看不出来了，留档是唯一还答得出「它当时改了什么」的地方
     */
    public record Step(int index, String goal, boolean intermediate, String state,
                       int rounds, List<Change> changes) {
    }

    /**
     * 时间线上的一行。
     *
     * @param round 第几轮；未开始/未分轮时为 0
     * @param level {@code info} / {@code warn} / {@code error}
     */
    public record Line(int round, String level, String text) {
    }

    /** 列表页用的投影：不带差异与时间线，避免拉一次历史把几 MB 全读进来。 */
    public record Summary(String id, String startedAt, String status, String template,
                          List<String> targets, String detail) {
    }

    public Summary summary() {
        return new Summary(id, startedAt, status, template, targets, detail);
    }

    /**
     * 只换「跑完之后人写下的那三笔」，其余原样。
     *
     * <p>为什么要这一组方法：那三笔（判决 / 收场 / 重新生成过哪几份产物）都是<b>跑完之后</b>
     * 人做的动作，而留档是一次写死的。要改其中一栏只能整份重建——把它收在这里，
     * 就只有一个地方知道「重建时哪些字段要原样带着」，漏一个字段就丢一栏历史。
     *
     * <p>三个入参同时给，是为了不让「改 A 的时候顺手把 B 抹掉」发生：谁想改哪一栏就调哪个方法，
     * 另外两栏原样传下去。
     */
    private RunRecord with(List<Verdict> newVerdicts, Settlement newSettlement,
                           List<String> newRegenerated) {
        return new RunRecord(id, startedAt, status, template, prompt, acceptance, context,
                requirementId, targets, attempts, detail, missing, changes, steps, planSteps,
                stepsSource, testCases, tests, environment, newVerdicts, newSettlement,
                newRegenerated, timeline, coverage, refeed, unchanged, caseSwitches,
                session, finishedAt);
    }

    /** 换「谁什么时候停用了哪几条」那一栏（追加式流水，见 {@link CaseSwitch}）。 */
    public RunRecord withCaseSwitches(List<CaseSwitch> newCaseSwitches) {
        return new RunRecord(id, startedAt, status, template, prompt, acceptance, context,
                requirementId, targets, attempts, detail, missing, changes, steps, planSteps,
                stepsSource, testCases, tests, environment, verdicts, settlement,
                regenerated, timeline, coverage, refeed, unchanged, newCaseSwitches,
                session, finishedAt);
    }

    /** 换「每一条失败用例怎么判的」那一栏。 */
    public RunRecord withVerdicts(List<Verdict> newVerdicts) {
        return with(newVerdicts, settlement, regenerated);
    }

    /** 换「这次运行怎么收的场」那一栏。 */
    public RunRecord withSettlement(Settlement newSettlement) {
        return with(verdicts, newSettlement, regenerated);
    }

    /** 换「重新生成过哪几份测试产物」那一栏。 */
    public RunRecord withRegenerated(List<String> newRegenerated) {
        return with(verdicts, settlement, newRegenerated);
    }
}
