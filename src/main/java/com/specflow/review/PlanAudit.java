package com.specflow.review;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 机器查一遍「这份方案执行不了的地方」。
 *
 * <p><b>为什么非要机器查。</b>检查阶段只看到清单和需求，它给出的方案完全可以要求
 * 「新建 com.library.controller.HealthController」——而目标文件清单是硬白名单，
 * 那种方案根本执行不了，开发阶段会在补丁被拒时才暴露，白跑一轮。
 * 这件事<b>不用问模型</b>，算一下就知道：方案里提到的文件，凡是清单里没有的，计划就不成立。
 *
 * <p><b>刻意保守</b>（宁可漏报，也不误报一堆噪音）：
 * <ul>
 *   <li>只认「像这个项目自己的文件」的东西：带路径的，或者命名空间对得上项目已有目录的全限定类名；
 *       `java.util.List` 这类外部类名一律不当文件；</li>
 *   <li>后缀差异不算两个文件（清单里写 `X`、方案里写 `X.java`，视为同一个）；</li>
 *   <li>拿不准的不报。</li>
 * </ul>
 */
public final class PlanAudit {

    /**
     * 一条「方案执行不了」的发现。
     *
     * @param path    方案里提到的那个文件（相对项目根，或全限定类名归一成的路径）
     * @param reason  为什么它让方案不成立
     * @param suggest 可以直接加进目标文件的路径；<b>拿不准就是 null</b>——
     *                界面只在它不为空时才给「加进目标文件」那个按钮，
     *                宁可不给，也不要塞一个错路径进去
     */
    public record Finding(String path, String reason, String suggest) {
    }

    /** 内部用：一个候选，以及它对应的、可以加进目标文件的路径（可能没有）。 */
    private record Candidate(String path, String suggest) {
    }

    /** 最多报几条：这是提醒，不是审计报告，堆一屏反而没人看。 */
    private static final int MAX_FINDINGS = 5;

    /** 带斜杠的路径：`src/main/java/com/library/dto/SummaryDTO.java`。 */
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+");

    /** 全限定类名：`com.library.controller.HealthController`（末段大写开头才算类）。 */
    private static final Pattern QUALIFIED = Pattern.compile(
            "(?:[a-z][A-Za-z0-9_]*\\.){2,}[A-Z][A-Za-z0-9_]*");

    private PlanAudit() {
    }

    /**
     * @param plan         检查阶段的方案
     * @param targets      本次允许改动的文件（相对项目根）
     * @param projectFiles 项目里真实存在的文件（相对项目根），用来判断「这像不像本项目的文件」
     * @param directories  项目里真实存在的目录（相对项目根），同上
     */
    public static List<Finding> check(PlanReview plan, List<String> targets,
                                     Collection<String> projectFiles, Collection<String> directories) {
        List<String> stems = targets.stream().map(PlanAudit::stem).toList();
        Set<String> seen = new LinkedHashSet<>();
        List<Finding> findings = new ArrayList<>();

        for (Candidate candidate : candidates(plan, projectFiles, directories)) {
            if (covered(candidate.path(), stems) || !seen.add(candidate.path())) {
                continue;
            }
            findings.add(new Finding(candidate.path(),
                    exists(candidate.path(), projectFiles)
                            ? "它在项目里，但不在本次的目标文件清单里：清单外的文件改不了"
                            : "清单里没有它（看起来是要新建）：清单外的文件建不了",
                    candidate.suggest()));
            if (findings.size() >= MAX_FINDINGS) {
                break;
            }
        }
        return List.copyOf(findings);
    }

    // ---------- 从方案里挑出「像文件」的东西 ----------

    private static List<Candidate> candidates(PlanReview plan, Collection<String> projectFiles,
                                              Collection<String> directories) {
        List<String> texts = new ArrayList<>();
        texts.add(plan.summary());
        texts.add(plan.flowchart());
        for (PlanReview.MissingItem item : plan.missing()) {
            texts.add(item.what());
            texts.add(item.impact());
            texts.add(item.business());
            texts.add(item.fallback());
        }

        List<Candidate> found = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            for (Matcher matcher = PATH.matcher(text); matcher.find(); ) {
                String path = normalize(matcher.group());
                if (inProject(directoryOf(path), directories)) {
                    // 写的就是相对路径：它本身就是能加进目标文件的那个路径
                    found.add(new Candidate(path, path));
                }
            }
            for (Matcher matcher = QUALIFIED.matcher(text); matcher.find(); ) {
                String path = matcher.group().replace('.', '/');
                // 命名空间对得上本项目的才算：`com/library/` 在项目里，`java/util/` 不在
                if (namespaceInProject(path, projectFiles)) {
                    // 全限定类名要能落到某个真实文件上，才谈得上「加进目标文件」；
                    // 落不到（要新建）就给不出路径，交给用户自己填
                    found.add(new Candidate(path, existingFileOf(path, projectFiles)));
                }
            }
        }
        return List.copyOf(found);
    }

    /** 项目里哪个文件对应这个全限定类名；找不到返回 null。 */
    private static String existingFileOf(String qualifiedPath, Collection<String> projectFiles) {
        for (String file : projectFiles) {
            if (stem(file).endsWith(qualifiedPath)) {
                return file;
            }
        }
        return null;
    }

    /** 目录（或它的某一级）得是项目里真实存在的目录，否则不认——避免把普通英文词组当成路径。 */
    private static boolean inProject(String directory, Collection<String> directories) {
        if (directory.isEmpty()) {
            return false;
        }
        for (String known : directories) {
            if (known.equals(directory) || directory.startsWith(known + "/")) {
                return true;
            }
        }
        return false;
    }

    /** `com/library/controller/HealthController` 这种命名空间，前两段要能在项目文件里找到。 */
    private static boolean namespaceInProject(String path, Collection<String> projectFiles) {
        String[] parts = path.split("/");
        if (parts.length < 3) {
            return false;
        }
        String namespace = "/" + parts[0] + "/" + parts[1] + "/";
        for (String file : projectFiles) {
            if (file.contains(namespace)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 比对 ----------

    /** 清单里的这个文件能不能覆盖方案提到的那个：同名同目录就算同一个（后缀差异不算）。 */
    private static boolean covered(String candidate, List<String> targetStems) {
        String stem = stem(candidate);
        for (String known : targetStems) {
            if (known.equals(stem) || known.endsWith(stem) || stem.endsWith(known)) {
                return true;
            }
        }
        return false;
    }

    private static boolean exists(String candidate, Collection<String> projectFiles) {
        for (String file : projectFiles) {
            if (stem(file).endsWith(candidate)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 路径处理 ----------

    private static String normalize(String path) {
        String value = path.replace('\\', '/').strip();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    /** 去掉最后一段里的后缀：`a/b/C.java` → `a/b/C`；没有后缀就原样返回。 */
    private static String stem(String path) {
        String value = normalize(path);
        int slash = value.lastIndexOf('/');
        int dot = value.lastIndexOf('.');
        return dot > slash ? value.substring(0, dot) : value;
    }

    private static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
