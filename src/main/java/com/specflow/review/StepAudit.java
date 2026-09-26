package com.specflow.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 机器核一遍施工单「照着做会不会卡住」。
 *
 * <p>和 {@link PlanAudit} 同一个道理：这几件事<b>不用问模型，算一下就知道</b>，
 * 而且都是「照着做会卡住」而不是「可能不够好」：
 * <ul>
 *   <li><b>步数越界</b>（3～7）——一步太粗等于没分步，太多则每步分到的重试预算少得可怜；</li>
 *   <li><b>最后一步标了中间态</b>——施工单跑完必须是一个能编译的项目，
 *       否则「跑成功了」这句话就没有意义；</li>
 *   <li><b>某一步要动目标清单外的文件</b>——清单是硬白名单，
 *       那一步<b>物理上做不了</b>，会退化成一次白烧的调用。</li>
 * </ul>
 *
 * <p>除这三条外只给提示不给拦：**中间态超过三分之一**只说明这份拆法偏碎，
 * 换一种切法可能更好，但它并不必然失败——把它做成硬拦，只会逼着模型为了过关而撒谎。
 *
 * <p>之所以放在「检查阶段」而不是「开发阶段」：这些判断的输入是需求与清单，
 * 检查阶段全都拿得到，而等到动手时才发现，用户已经为整轮上下文付过钱了。
 */
public final class StepAudit {

    /** 步数下限。少于 3 步说明这件事本来就不用拆，或者是想问的问题没问清。 */
    public static final int MIN_STEPS = 3;

    /** 步数上限。每一步都要重新装配一次上下文、重跑一次编译，步子多了总预算反而摊薄。 */
    public static final int MAX_STEPS = 7;

    /**
     * 一条发现。
     *
     * @param step   哪一步的问题；<b>0 表示整份施工单的问题</b>（步数越界这一类）
     * @param reason 为什么照着它做会卡住
     */
    public record Finding(int step, String reason) {
    }

    /**
     * 机器核完的结果。
     *
     * <p>分成两块是刻意的：{@code findings} 拦人，{@code hints} 只提示。
     * 混在一个列表里，早晚会有人拿「提示」去挡用户。
     *
     * @param findings 硬拦：照着做会卡住
     * @param hints    只提示，不拦
     */
    public record Result(List<Finding> findings, List<String> hints) {

        public Result {
            findings = findings == null ? List.of() : List.copyOf(findings);
            hints = hints == null ? List.of() : List.copyOf(hints);
        }

        /** 要不要拦人。界面据此决定弹不弹那个「我知道，仍然继续」。 */
        public boolean blocking() {
            return !findings.isEmpty();
        }
    }

    private StepAudit() {
    }

    /** 没有施工单时的结果：没什么可核的，也不该编出一条提示。 */
    public static Result none() {
        return new Result(List.of(), List.of());
    }

    /**
     * 核一遍施工单。
     *
     * @param steps   施工单；为空表示这份方案里没有施工单
     * @param targets 本次允许改动的文件（相对项目根）
     */
    public static Result check(List<PlanStep> steps, List<String> targets) {
        if (steps == null || steps.isEmpty()) {
            // 不报错，但要说话：没有施工单意味着这次会退化成「一次调模型做完全部」，
            // 而上面那张流程图会让人以为它是分步做的。静默降级是最坏的一种做法
            return new Result(List.of(), List.of("这份方案里没有施工单（STEPS 块缺失或没认出来）："
                    + "这次运行会按单步执行，也就是一次调模型把整件事做完，"
                    + "中途失败只能整轮重来。分步要重跑一次检查"));
        }
        List<Finding> findings = new ArrayList<>();
        checkCount(steps.size(), findings);
        checkIntermediates(steps, findings);
        checkFiles(steps, targets, findings);
        return new Result(findings, hints(steps));
    }

    // ---------- 三条硬拦 ----------

    private static void checkCount(int total, List<Finding> findings) {
        if (total < MIN_STEPS) {
            findings.add(new Finding(0, "施工单只有 " + total + " 步：少于 " + MIN_STEPS
                    + " 步说明这件事本来就不用分步。要么把相邻的步骤合并成 " + MIN_STEPS
                    + " 步以内的一次改动，要么就别拆"));
        }
        if (total > MAX_STEPS) {
            findings.add(new Finding(0, "施工单有 " + total + " 步：上限是 " + MAX_STEPS
                    + " 步。请把相邻的步骤合并——每一步都要重新装配一次上下文、重跑一次编译，"
                    + "步子越多，每一步能分到的重试预算越少"));
        }
    }

    /**
     * 最后一步不许是中间态。
     *
     * <p>这条不是格式洁癖：跑完施工单之后「成功」的含义就是「项目能编译」。
     * 允许最后一步是中间态，等于允许整个运行以一份编不过的代码报成功。
     */
    private static void checkIntermediates(List<PlanStep> steps, List<Finding> findings) {
        PlanStep last = steps.get(steps.size() - 1);
        if (last.intermediate()) {
            findings.add(new Finding(last.index(), "最后一步（第 " + last.index()
                    + " 步）标了「中间态」：施工单跑完必须是一个能编译的项目。"
                    + "请把这一步里剩下的接续工作补上，或者把它并进前一步"));
        }
    }

    /**
     * 每一步要动的文件都得在目标清单里。
     *
     * <p>只看第 3 栏这一处，不去扫目标文字——施工单里的文件是<b>声明</b>，
     * 扫散文会像 {@link PlanAudit} 那样掺进不少噪音，而这里本来就有一栏专门放文件。
     */
    private static void checkFiles(List<PlanStep> steps, List<String> targets, List<Finding> findings) {
        Set<String> reported = new LinkedHashSet<>();
        for (PlanStep step : steps) {
            for (String file : step.files()) {
                boolean allowed = targets.stream().anyMatch(target -> PathForms.covers(target, file));
                if (allowed || !reported.add(file)) {
                    continue;
                }
                findings.add(new Finding(step.index(), "第 " + step.index() + " 步要动 " + file
                        + "：它不在目标文件清单里，这一步执行不了（清单外的文件改不了、新建也不行）"));
            }
        }
    }

    // ---------- 只提示不拦 ----------

    /**
     * 中间态超过三分之一时提一句。
     *
     * <p>不拦的理由：中间态是「按功能切」时<u>真实存在</u>的东西，而三分之一是一条
     * 拍脑袋划的线。把它做成硬拦，模型就会为了过关把「中间态」改写成「自洽」——
     * 那是把真话改成好听话，比多一个中间态糟得多。
     */
    private static List<String> hints(List<PlanStep> steps) {
        long intermediates = steps.stream().filter(PlanStep::intermediate).count();
        // 用整数比较避开浮点：共 3 步时允许 1 个中间态，第 2 个就超线
        if (intermediates * 3 <= steps.size()) {
            return List.of();
        }
        return List.of("有 " + intermediates + " 步标了「中间态」（共 " + steps.size()
                + " 步，超过三分之一）：这几步做完项目是编不过的，中途停下来会留下一份半成品。"
                + "可能的改法是按「功能」而不是按「层」重切。");
    }
}
