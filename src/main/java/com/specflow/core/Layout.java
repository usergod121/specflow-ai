package com.specflow.core;

import java.nio.file.Path;

/**
 * <b>项目里每一样东西落在哪</b>——只有这一处定义。
 *
 * <p>为什么它必须是一个类、而不是各处各拼一遍字符串：落点是跨模块的约定
 * （写的人、读的人、清理的人、界面上显示路径的人），四处各拼一遍的结果是
 * 「有一处少了一层目录」，而那种错只在真跑起来时才现形。这一版把它收成一条：
 * 想知道某个东西在哪，问 {@code Layout}。
 *
 * <p>形状（也是文档 `ARCHITECTURE.md` §3 里那一份）：
 * <pre>
 * &lt;项目&gt;/.specflow/
 *   project.yaml  local.env  env.yaml
 *   flow/current                    当前流程 id
 *   flow/&lt;id&gt;/flow.json             五节点状态 + facts
 *   flow/&lt;id&gt;/events.jsonl          统一归档日志（行号 = 事件 id）
 *   flow/&lt;id&gt;/cases.json  probe.json  summary.md
 *   flow/&lt;id&gt;/snapshot/             写前快照
 *   flow/&lt;id&gt;/runs/&lt;runId&gt;.jsonl     逐轮留档
 *   flow/&lt;id&gt;/env/&lt;时间戳&gt;/compose.yaml
 * </pre>
 *
 * @param root 项目根（构造时归一化成绝对路径：相对路径会被解析到服务进程的工作目录上，
 *             那种错在真机上表现为"找不到文件"，而文件明明就在项目里）
 */
public record Layout(Path root) {

    public static final String DIR = ".specflow";
    public static final String FLOWS = "flow";

    public Layout {
        root = root.toAbsolutePath().normalize();
    }

    public Path specflow() {
        return root.resolve(DIR);
    }

    public Path projectYaml() {
        return specflow().resolve("project.yaml");
    }

    public Path localEnv() {
        return specflow().resolve("local.env");
    }

    public Path envYaml() {
        return specflow().resolve("env.yaml");
    }

    public Path flows() {
        return specflow().resolve(FLOWS);
    }

    /** 当前流程 id 落在这个文件里（一行文本）。 */
    public Path current() {
        return flows().resolve("current");
    }

    public Path flow(String id) {
        return flows().resolve(id);
    }

    public Path flowJson(String id) {
        return flow(id).resolve("flow.json");
    }

    public Path events(String id) {
        return flow(id).resolve("events.jsonl");
    }

    public Path cases(String id) {
        return flow(id).resolve("cases.json");
    }

    public Path probe(String id) {
        return flow(id).resolve("probe.json");
    }

    public Path summary(String id) {
        return flow(id).resolve("summary.md");
    }

    public Path snapshot(String id) {
        return flow(id).resolve("snapshot");
    }

    public Path runs(String id) {
        return flow(id).resolve("runs");
    }

    public Path envRuns(String id) {
        return flow(id).resolve("env");
    }

    /**
     * 运行级断点（{@code flow/<id>/checkpoint.json}）：跑到哪、下一步干什么。
     *
     * <p>它是<b>一份可以被删掉的提示</b>，不是真相：真相永远是
     * {@code cases.json}（哪几条到了终态）与 {@code events.jsonl}（发生过什么）。
     */
    public Path checkpoint(String id) {
        return flow(id).resolve("checkpoint.json");
    }

    /** 相对项目根的展示路径（界面与报错里给人看的那种，永远是正斜杠）。 */
    public String show(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        return abs.startsWith(root)
                ? root.relativize(abs).toString().replace('\\', '/')
                : abs.toString().replace('\\', '/');
    }
}
