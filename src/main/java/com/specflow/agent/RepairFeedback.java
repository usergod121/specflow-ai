package com.specflow.agent;

import com.specflow.exception.PatchConflictException;
import com.specflow.verify.VerificationResult;

import java.util.List;

/**
 * 重试时回喂给模型的反馈文本。
 *
 * <p>这是整套「自愈」能力的全部秘密：失败信息写得好，模型下一轮就能改对；
 * 写得含糊，模型只能瞎猜，重试预算就被浪费掉。因此这里的文案遵循三条：
 * <ol>
 *   <li>明确说「上一轮失败在哪」，不要只说「失败了」</li>
 *   <li>明确说<b>当前磁盘状态</b>——补丁冲突时文件没动，编译失败时文件已回滚。
 *       模型对「我的改动还在不在」判断错一次，后面全错</li>
 *   <li>明确说「请重新输出完整补丁」，而不是「请修正」——否则模型可能只回一段修正片段</li>
 * </ol>
 */
final class RepairFeedback {

    private RepairFeedback() {
    }

    static String forConflict(PatchConflictException failure) {
        StringBuilder out = new StringBuilder();
        out.append("你上一轮输出的补丁无法应用，引擎已拒绝落盘，**目标文件没有任何改动**。\n\n")
                .append("失败原因：").append(failure.getMessage()).append('\n');
        if (!failure.details().isEmpty()) {
            out.append("细节：\n");
            for (String detail : failure.details()) {
                out.append("- ").append(detail).append('\n');
            }
        }
        out.append("\n请重新输出完整的补丁。特别注意：SEARCH 段落必须逐字复制目标文件中的现有内容，")
                .append("且必须在该文件中唯一。");
        return out.toString();
    }

    static String forVerification(VerificationResult result) {
        return "你上一轮的修改已写入磁盘，但校验未通过。"
                + "**改动已被回滚，文件已恢复到你修改之前的内容**。\n\n"
                + "校验器：" + result.verifier() + '\n'
                + "命令：" + result.command() + "\n\n"
                + "输出：\n```\n" + result.output() + "\n```\n\n"
                + "请基于**原始代码**重新输出完整补丁（不是只给修正片段）。"
                + "所有 SEARCH 段落都要按回滚后的文件内容来写。";
    }

    static String summarize(List<VerificationResult> results) {
        StringBuilder out = new StringBuilder();
        for (VerificationResult result : results) {
            out.append(result.verifier()).append(": ").append(result.status()).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
