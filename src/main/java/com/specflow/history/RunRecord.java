package com.specflow.history;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specflow.env.EnvRegistration;
import com.specflow.review.PlanReview;
import com.specflow.review.PlanStep;
import com.specflow.spec.ContextItem;
import com.specflow.tests.TestOutcome;

import java.util.List;
import java.util.Locale;

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
        List<Line> timeline
) {

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
                newRegenerated, timeline);
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
