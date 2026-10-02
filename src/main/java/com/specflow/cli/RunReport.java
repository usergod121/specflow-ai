package com.specflow.cli;

import com.specflow.agent.AgentResult;
import com.specflow.verify.VerificationResult;

/**
 * 一次运行的结果怎么打印、用什么退出码。
 *
 * <p>{@code run} 与 {@code continue} 打印的是同一种东西——两个命令各写一份的话，
 * 迟早会出现「同一个状态，一个命令说成功、另一个说失败」。退出码尤其不能分叉：
 * CI 就是靠它判断该不该继续的。
 */
final class RunReport {

    private RunReport() {
    }

    static void report(AgentResult result) {
        for (VerificationResult verification : result.verifications()) {
            Console.detail("%s: %s", verification.verifier(), verification.status());
        }
        switch (result.status()) {
            case SUCCESS -> {
                Console.ok("完成，共 %d 轮，改动 %d 处：", result.attempts(), result.changes().size());
                result.changes().forEach(change -> Console.detail("%s", change.describe()));
            }
            case SUCCESS_UNVERIFIED -> {
                Console.warn("%s", result.detail());
                result.changes().forEach(change -> Console.detail("%s", change.describe()));
            }
            case TESTS_FAILED -> {
                Console.warn("测试没全过（%d 轮）：", result.attempts());
                Console.detail("%s", result.detail());
                Console.detail("改动还在磁盘上（没有回滚）：看完失败清单再决定接受还是撤回");
                Console.detail("保留改动：specflow accept    撤回改动：specflow rollback");
            }
            case NEEDS_CONTEXT -> {
                Console.warn("模型声明信息不足，未改动任何文件：");
                Console.detail("%s", result.detail());
                Console.detail("接着跑：specflow continue（补过料之后）；硬要它做：specflow continue --force");
            }
            case FAILED -> {
                Console.fail("任务失败（%d 轮）：%s", result.attempts(), result.detail());
                Console.detail("磁盘状态已回滚到运行前");
            }
            case NEEDS_ENVIRONMENT -> {
                Console.fail("不是改代码能解决的（%d 轮）：%s", result.attempts(), result.detail());
                // 磁盘的去留不在这里替它下结论：同是这一档，开发阶段的编译校验是回滚的，
                // 而测试阶段那两条硬判据（环境起不来 / 超时）是留着的（见 TestOutcome.hard）——
                // 写死一句「已回滚」就说错了其中一次。当前状态在 detail 的最后一句里
                Console.detail("磁盘上那份改动按上面最后一句处置；留着就 specflow accept，"
                        + "撤掉就 specflow rollback（两种都是 0）");
            }
            case CANCELLED -> {
                Console.warn("已中断（完成 %d 轮）", result.attempts());
                Console.detail("磁盘状态已回滚到运行前");
            }
            case PENDING_DECISION -> {
                Console.warn("%s", result.detail());
                Console.detail("保留改动：specflow accept    撤回改动：specflow rollback");
            }
            case PLAN_OUTDATED -> {
                Console.fail("没有开工：%s", result.detail());
            }
        }
    }

    /**
     * 退出码：0 成功 · 1 失败（已回滚）· 2 模型要求补充信息（磁盘未动）· 3 人工中断（已回滚）·
     * 4 卡在环境/依赖上（要不要回滚见结论里那句话，需要人去处理）· 5 上一次的改动还没处置（磁盘未动）·
     * 6 上一次留下的施工单对不上现在的清单（磁盘未动）· 7 测试没全过（改动还在磁盘上）。
     *
     * <p>中断单独给一个码，是为了让 CI 能区分「它自己做不到」和「是我叫停的」；
     * 环境问题单独给一个码，是为了让 CI 能区分「代码写错了」和「这台机器上跑不起来」；
     * 施工单过期单独给一个码，是为了让 CI 能区分「它做砸了」和「你得先把目标清单改回去」——
     * 这两种情况下用户要做的事完全不同。
     *
     * <p>测试没过单独给 7，而不是并进 0（它确实改动了磁盘）：CI 上「测试没过」必须是红的，
     * 而它和 1（已回滚、什么都没留下）要做的事也不一样——这一档的改动还在，得有人看失败清单。
     */
    static int exitCode(AgentResult.Status status) {
        return switch (status) {
            case SUCCESS, SUCCESS_UNVERIFIED -> 0;
            case FAILED -> 1;
            case NEEDS_CONTEXT -> 2;
            case CANCELLED -> 3;
            case NEEDS_ENVIRONMENT -> 4;
            case PENDING_DECISION -> 5;
            case PLAN_OUTDATED -> 6;
            case TESTS_FAILED -> 7;
        };
    }
}
