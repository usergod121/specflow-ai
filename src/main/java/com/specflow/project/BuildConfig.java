package com.specflow.project;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 项目级构建命令。对一个项目来说是固定的，因此放在 {@code .specflow/project.yaml} 而不是每次的 spec 里。
 *
 * <p><b>只有编译一条，这是有意的。</b>测试那一段的入口脚本由模型现写（语言怎么编、依赖怎么进来
 * 全在那个脚本里，见 {@code TestProtocol}），引擎不读用户配的「测试命令」——
 * 真读它就会把「测试怎么跑」从一个可被模型适配的地方，挪回一个只认某一种构建工具的地方。
 *
 * <p>这里曾经预留过 {@code lint} / {@code test} 两个字段（注释还写着「预留给后续的测试 Agent」）。
 * 测试 Agent 2026-09/10 建好了，它落在 {@code DevelopmentAgent.testPhase}，<b>一个都没用上</b>：
 * 两个字段能从 YAML 解析进来，全仓库却没有一处读它们。2026-10-03 删掉——
 * 留着它们只会让配置文件看上去支持一件根本没发生的事，而 {@code project.yaml}
 * 对未知字段是报错而不是忽略（见 {@code ProjectConfigLoader}），删掉之后写过的项目会得到一句
 * 指名道姓的「存在未知字段 'lint'」，比默默不起作用好。
 *
 * @param compile 编译命令，如 {@code mvn -q -DskipTests compile}
 */
public record BuildConfig(
        String compile
) {

    public static final BuildConfig EMPTY = new BuildConfig(null);

    @JsonCreator
    public static BuildConfig of(
            @JsonProperty("compile") String compile
    ) {
        return new BuildConfig(blankToNull(compile));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
