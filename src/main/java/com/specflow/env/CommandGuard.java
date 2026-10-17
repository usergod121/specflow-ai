package com.specflow.env;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>哪些写法算高危</b>——只有这一处定义，两种处置共用它。
 *
 * <h2>两种处置</h2>
 * <ul>
 *   <li>{@link #forbidden(String)}：<b>拒</b>。把宿主根挂进容器、借宿主网络栈、
 *       往块设备上写——这些在任何情况下都不许出现，问也不问（"要不要允许把 C: 挂进来"
 *       本身就是个坏问题）；</li>
 *   <li>{@link #highRisk(String)}：<b>停下来问</b>（合同 §6.6）。{@code sudo} / {@code chmod 777} /
 *       {@code dd} / {@code curl … | sh} 这些在受控的沙箱里**可能是正当的**
 *       （装依赖、调权限、复现一个环境问题），但值得让人看一眼再决定。</li>
 * </ul>
 *
 * <p>两条都先<b>把被换行切开的命令接回去</b>再判：拆开之后每一行都人畜无害，
 * {@code rm -r} 一行、{@code -f /} 一行，只有接回去才是那条真要命的命令。
 *
 * <p>每条规则都带 {@code CASE_INSENSITIVE}：Windows 的命令行不认大小写，
 * 只拦小写等于留了一条"把字母改大写就绕过去"的路。
 */
public final class CommandGuard {

    /** 高危且**直接拒**的那一类。 */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\bmkfs(\\.\\w+)?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("--privileged\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("docker\\.sock", Pattern.CASE_INSENSITIVE),
            // 把宿主根/盘根/家目录挂进容器：-v /:/host、-v C:\:/x、--mount source=/
            Pattern.compile("(-v|--volume)[= ]\\s*[\"']?(/|[a-z]:[\\\\/]?|/[a-z]/)(?=[:\\s\"']|$)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("--mount[^,]*(,|\\s)source\\s*=\\s*[\"']?(/|[a-z]:[\\\\/]?)(?=[,\\s\"']|$)",
                    Pattern.CASE_INSENSITIVE),
            // 借宿主的网络栈：借了之后容器隔离就只剩个壳
            Pattern.compile("(--net|--network)[= ]+host\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("network_mode\\s*[:=]\\s*[\"']?host\\b", Pattern.CASE_INSENSITIVE),
            // 往块设备上写
            Pattern.compile(">>?\\s*/dev/(sd|hd|nvme|vd|disk|mapper)", Pattern.CASE_INSENSITIVE));
    // 注意：这里**不再拦 `$HOME` / `%userprofile%`**（原意是防"把宿主家目录挂进容器"，
    // 而那条边界属于 Compose 渲染时的 `-v /` 那类写法）。在 bash 文本层面拦它，
    // 拦到的是容器里它自己的家目录——`echo $HOME` 是完全正常的写法，实测被硬拒了两次。

    /**
     * 高危但**值得问一句**的那一类（合同 §6.6 点名）。
     *
     * <p>`sudo` 不在这一档：见 {@link #privilege(String)}——容器是 uid 1000 + cap_drop ALL +
     * 压根没有 sudo 二进制，"要不要允许提权"是个不可能有答案的问题（实测害用户等了 187 秒，
     * 答完还是 `sudo: not found`）。
     */
    private static final List<Pattern> ASKABLE = List.of(
            Pattern.compile("\\bdd\\b[^\\n]*(if|of|bs|count)=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(curl|wget|iwr|invoke-webrequest)\\b[^|]*\\|\\s*(sudo\\s+)?(ba|z|da|k)?sh\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchmod\\b[^\\n]*\\b777\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchmod\\b[^\\n]*-[a-z]*r[a-z]*(\\s|$)", Pattern.CASE_INSENSITIVE));

    /** 「要提权」的写法：容器里一律做不到，所以**直接拒**，而且话术说清"为什么不是问一句"。 */
    private static final Pattern PRIVILEGE = Pattern.compile("\\bsudo\\b", Pattern.CASE_INSENSITIVE);

    /**
     * <b>「在给自己装工具链」</b>——环境的事，不是测试的事（这一版最贵的那次事故）。
     *
     * <p>实测：容器里没有 Maven，⑤ 的 Agent 就自己 {@code curl} 下载 maven、解开、改 PATH，
     * 花掉 20 轮 / 15 分钟；而**容器是一次性的**——下一轮这些东西一个字节都不剩，全白干。
     * 更糟的是，它把"env.yaml 写漏了"这件事掩盖成了一次看起来能跑通的运气。
     *
     * <p>所以这一档<b>直接拒</b>，回话指名出处：改 {@code env.yaml} 的 image 或 init。
     * 它只认"装工具链"这类；装自己的一次性验证脚手架（临时脚本、测试数据）不在此列。
     */
    private static final List<Pattern> BOOTSTRAP = List.of(
            Pattern.compile("\\b(apt-get|apt|yum|dnf|apk|zypper|pacman|brew|choco|scoop)\\s+(install|add)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpip3?\\s+install\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnpm\\s+(i|install)\\s+(-g|--global)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(yarn|pnpm)\\s+(global\\s+add|add\\s+-g)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(gem|cargo|go|dotnet)\\s+(install|tool\\s+install)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(nvm|sdkman?|sdk|jenv|pyenv|asdf|rustup)\\s+(install|use|default)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bexport\\s+(PATH|JAVA_HOME|MAVEN_HOME|GRADLE_HOME|NODE_PATH)\\s*=",
                    Pattern.CASE_INSENSITIVE),
            // 只认"下构建工具"的那几个名字：下测试数据/夹具不算
            Pattern.compile("\\b(curl|wget)\\b[^\\n]*(apache-maven|maven-\\d|gradle-\\d|jdk-?\\d|openjdk|"
                    + "node-v\\d|python-\\d|go1\\.|rustup)", Pattern.CASE_INSENSITIVE));

    /** 「成片地删东西」的两个特征，要一起命中才算（删自己产物的命令天天有）。 */
    private static final Pattern DELETES = Pattern.compile(
            "\\b(rm|unlink|shred|rmdir|rd|deltree|del|erase|remove-item|clear-content|truncate)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BULK_FLAG = Pattern.compile(
            "(^|\\s)(-[a-z]*[rf][a-z]*|--?[a-z]*(recursive|recurse|force)|/[sqf])(\\s|$)",
            Pattern.CASE_INSENSITIVE);

    private CommandGuard() {
    }

    /**
     * 这条命令是不是<b>一律不许</b>。
     *
     * @return 命中时给人看的那句话（说清是哪一条写法）；没命中 {@code null}
     */
    public static String forbidden(String content) {
        for (String line : logicalLines(content)) {
            for (Pattern pattern : FORBIDDEN) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    return "这里有高危写法「" + matcher.group().strip() + "」："
                            + "提权、把宿主挂进容器、借宿主的网络栈、往块设备上写——"
                            + "这一类在任何情况下都不接受，改掉它再来";
                }
            }
        }
        return null;
    }

    /**
     * 这条命令要不要<b>停下来问用户 y/n</b>。
     *
     * @return 命中时给人看的那句话；没命中 {@code null}
     */
    public static String highRisk(String content) {
        String bootstrap = bootstrap(content);
        if (bootstrap != null) {
            return bootstrap;
        }
        for (String line : logicalLines(content)) {
            for (Pattern pattern : ASKABLE) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    return "这条命令里有「" + matcher.group().strip() + "」——"
                            + "它在动设备，或者把网上下来的东西直接交给 shell。"
                            + "沙箱里做这些有时是正当的（复现环境问题），但值得你看一眼再决定。";
                }
            }
            if (DELETES.matcher(line).find() && BULK_FLAG.matcher(line).find()) {
                Matcher matcher = DELETES.matcher(line);
                matcher.find();
                return "这条命令在成片地删东西（「" + matcher.group().strip() + "」带批量旗标）——"
                        + "删的是什么只有看过命令原文才知道。删自己的产物是正常的，所以这里问一句而不是直接拒。";
            }
        }
        return null;
    }

    /**
     * 这条命令是不是在<b>试图提权</b>（{@code sudo}）。
     *
     * @return 命中时给人看的那句话（说清"这里不可能成功、别绕"）；没命中 {@code null}
     */
    public static String privilege(String content) {
        for (String line : logicalLines(content)) {
            Matcher matcher = PRIVILEGE.matcher(line);
            if (matcher.find()) {
                return "这条命令要提权（" + matcher.group().strip() + "），但沙箱容器里没有 sudo："
                        + "它跑在 uid 1000、capabilities 全被丢掉的身份上，提权在这里不可能成功。"
                        + "需要一个本来只有 root 能装的东西？那是环境声明的事——改 "
                        + ".specflow/env.yaml 的 image（换一个自带的镜像）或在 init 里装，然后重新起环境。"
                        + "这一格不会替你装。";
            }
        }
        return null;
    }

    /**
     * 这条命令是不是在<b>给自己装工具链</b>（装 Maven / 换 JDK / 改 PATH…）。
     *
     * @return 命中时给人看的那句话（指名去改 env.yaml）；没命中 {@code null}
     */
    public static String bootstrap(String content) {
        for (String line : logicalLines(content)) {
            for (Pattern pattern : BOOTSTRAP) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    return "这条命令在给自己装工具链（「" + matcher.group().strip() + "」），这不是测试该干的事："
                            + "它不是你的项目，而是这个环境缺了东西。两条路——"
                            + "① 改 .specflow/env.yaml 的 image（换一个自带该工具的镜像，"
                            + "例如 Java 项目用 maven:3.9-eclipse-temurin-17）；② 在 init 里装一次。"
                            + "然后在 ① 重新起环境（① 起完会在容器里真构建一次来验）。"
                            + "理由：容器是一次性的，你在这里装的东西下一轮一个字节都不剩。";
                }
            }
        }
        return null;
    }

    /**
     * 把被换行切开的命令接回去，再逐行看。
     *
     * <p>三个系统各有一套续行符：cmd 的 {@code ^}、sh 的 {@code \}、PowerShell 的反引号。
     */
    static List<String> logicalLines(String content) {
        String text = content == null ? "" : content.replace("\r\n", "\n");
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            boolean continues = line.endsWith("\\") || line.endsWith("^") || line.endsWith("`");
            current.append(continues ? line.substring(0, line.length() - 1) : line).append(' ');
            if (!continues) {
                lines.add(current.toString().strip());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString().strip());
        }
        return lines;
    }
}
