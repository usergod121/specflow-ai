package com.specflow.review;

/**
 * 「这两处写的是不是同一个文件」——检查阶段两处机器校验共用的比较口径。
 *
 * <p>为什么不各写一份：{@link PlanAudit} 判「方案里提到的文件在不在清单里」，
 * {@link StepAudit} 判「这一步要动的文件在不在清单里」，问的是同一个问题。
 * 两份实现迟早会漂移——一处认后缀差异、另一处不认，用户就会看到
 * 「方案说没问题、施工单说越界」这种自相矛盾的结论。
 *
 * <p>整体口径刻意<b>偏宽</b>（认后缀差异、认一端是另一端的前缀）：
 * 这里的用途是「拦住执行不了的方案」，报错要人能立刻认同；拿不准就放过，
 * 真正越界的补丁还会被 {@code PatchStrategy} 按白名单拒掉，兜得住。
 */
final class PathForms {

    private PathForms() {
    }

    /** 统一成 POSIX 相对路径：反斜杠换斜杠，去掉开头的 {@code ./} 与 {@code /}。 */
    static String normalize(String path) {
        String value = path.replace('\\', '/').strip();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    /** 去掉最后一段里的后缀：{@code a/b/C.java} → {@code a/b/C}；没有后缀就原样返回。 */
    static String stem(String path) {
        String value = normalize(path);
        int slash = value.lastIndexOf('/');
        int dot = value.lastIndexOf('.');
        return dot > slash ? value.substring(0, dot) : value;
    }

    /**
     * 清单里的这个文件能不能覆盖提到的那个。
     *
     * <p>判据是「同名同目录就算同一个」，并且允许一端是另一端的前缀——
     * 模型常常只写类名（{@code SummaryDTO.java}）而清单里是完整路径。宁可放过，不可误报。
     */
    static boolean covers(String target, String mentioned) {
        String known = stem(target);
        String candidate = stem(mentioned);
        return known.equals(candidate) || known.endsWith(candidate) || candidate.endsWith(known);
    }
}
