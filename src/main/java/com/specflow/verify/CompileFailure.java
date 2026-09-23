package com.specflow.verify;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 判断一次编译失败是「模型代码写错了」还是「这个项目的依赖/环境不对」。
 *
 * <p><b>为什么非要分。</b>代码写错了，把编译输出喂回去它多半能修；而项目里根本没有那个依赖时，
 * 再给它六轮也修不好——它只会把用到那个包的地方删掉，于是<b>编译过了、需求却没实现</b>。
 * 用户看到的是绿灯，拿到的是错东西，比直接失败更糟。所以这类失败要<b>立刻停下交给人</b>。
 *
 * <p><b>为什么宁可漏判。</b>规则是保守的：只有当输出里出现明确的「缺依赖 / 环境不对」字样才算
 * {@link VerificationResult.Kind#ENVIRONMENT}，其余一律算代码问题（继续重试）。
 * 判错的代价不对称——把代码问题误判成环境问题会白停一轮（用户点一下就能继续），
 * 反过来则是烧轮次换来一个假绿灯。
 */
public final class CompileFailure {

    /**
     * 缺依赖 / 环境不对的典型字样。
     *
     * <p>刻意只收「一眼就是环境问题」的：Java 的 {@code 程序包 X 不存在}、
     * Maven 的解析失败、JDK 级别不符、路径与权限、找不到工具。
     * 像 {@code 找不到符号} 这种也可能是模型自己写错了方法名，所以<b>不</b>收进来。
     */
    private static final List<Pattern> ENVIRONMENT = List.of(
            // javac：引用了 classpath 上根本没有的包
            Pattern.compile("程序包\\s*\\S+\\s*不存在"),
            Pattern.compile("package\\s+\\S+\\s+does not exist"),
            // Maven / Gradle：依赖解析不了
            Pattern.compile("Could not resolve dependencies", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Could not find artifact", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Non-resolvable", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Failed to resolve", Pattern.CASE_INSENSITIVE),
            Pattern.compile("unable to resolve class", Pattern.CASE_INSENSITIVE),
            Pattern.compile("no required module provides package", Pattern.CASE_INSENSITIVE),
            // 编译级别 / JDK
            Pattern.compile("无效的目标发行版"),
            Pattern.compile("invalid target release", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Unable to locate a Java Runtime", Pattern.CASE_INSENSITIVE),
            Pattern.compile("release version \\d+ not supported", Pattern.CASE_INSENSITIVE),
            // 工具找不到 / 路径与权限 / 磁盘
            Pattern.compile("Cannot run program", Pattern.CASE_INSENSITIVE),
            Pattern.compile("系统找不到指定的路径"),
            Pattern.compile("Access is denied", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Permission denied", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Read-only file system", Pattern.CASE_INSENSITIVE),
            Pattern.compile("No space left", Pattern.CASE_INSENSITIVE));

    /** 摘给用户看的一句话的长度上限：够看清是什么，又不至于把整段日志糊上去。 */
    private static final int MAX_QUOTE = 200;

    private CompileFailure() {
    }

    static VerificationResult.Kind classify(String output) {
        return evidence(output) == null
                ? VerificationResult.Kind.CODE
                : VerificationResult.Kind.ENVIRONMENT;
    }

    /**
     * 命中的那一行原文——给用户看的「凭什么说是环境问题」。
     *
     * <p>报「缺依赖」却不说缺什么，等于把人又推回去翻日志。所以把原句摘出来。
     */
    static String evidence(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            for (Pattern pattern : ENVIRONMENT) {
                if (pattern.matcher(text).find()) {
                    return clip(text);
                }
            }
        }
        return null;
    }

    /**
     * 给用户的一段话：为什么停在这里、凭什么、以及去哪儿看全。
     *
     * <p>无论如何都要带上原话。摘不到特征词的时候（比如校验器自己炸了、日志读不出来），
     * 更要带——那种失败的全部信息就是那一行，只回一句「环境有问题」等于什么也没说。
     */
    public static String explain(String output) {
        String quote = evidence(output);
        if (quote == null) {
            quote = firstLine(output);
        }
        StringBuilder text = new StringBuilder(
                "这不是改代码能解决的：缺依赖、JDK 级别不符，或者编译/校验环境本身有问题。")
                .append(System.lineSeparator())
                .append("  出错的原话：")
                .append(quote == null ? "（没有任何输出，请看完整日志）" : quote);
        String where = logHint(output);
        if (where != null) {
            text.append(System.lineSeparator()).append("  ").append(where);
        }
        return text.toString();
    }

    /**
     * 输出里那句「完整日志在哪」。
     *
     * <p>编译输出会被截断，而给用户的那段只摘了「原话」——不把这一句带上去，
     * 用户知道缺的是什么依赖，却不知道该去哪儿看完整的报错。
     */
    private static String logHint(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.contains(CompileVerifier.LOG_HINT)) {
                return clip(text);
            }
        }
        return null;
    }

    /** 输出里第一行有内容的——摘不到特征词时的兜底。 */
    private static String firstLine(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (!text.isEmpty()) {
                return clip(text);
            }
        }
        return null;
    }

    private static String clip(String text) {
        return text.length() > MAX_QUOTE ? text.substring(0, MAX_QUOTE) + "…" : text;
    }
}
