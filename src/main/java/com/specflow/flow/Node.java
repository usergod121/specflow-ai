package com.specflow.flow;

import java.util.List;
import java.util.Locale;

/**
 * <b>五个节点</b>（`docs/ARCHITECTURE.md` §5）。
 *
 * <p><b>顺序就是这个枚举自带的</b>：{@link #ordinal()} 就是步骤号，所以
 * 「节点 N 不是 DONE → N+1 的动作一律拒」这条闸不需要任何外部顺序表——
 * 少一份表就少一处「表和枚举对不上」的可能。
 *
 * <pre>
 * ① env    环境搭建   闸：编译命令非空 ＋（环境起过 或 声明不需要环境）
 * ② probe  上下文探针 闸：有可读文件 ＋ prompt 非空
 * ③ cases  测试用例   闸：至少一条用例
 * ④ dev    开发       闸：编译通过
 * ⑤ test   测试       闸：清单无 PENDING ＋ 已提交
 * </pre>
 */
public enum Node {

    ENV("环境搭建", "让只读的 AI 把项目摸一遍，写出 env.yaml 与编译命令；起环境与编译是两件事"),

    PROBE("上下文探针", "写下需求，让只读的 AI 挑出开发要读的文件、要用的文本，并把 prompt 组装好"),

    CASES("测试用例", "让 AI 依需求与设计生成用例（带标签），人改完冻结"),

    DEV("开发", "开发 Agent 在白名单内改真项目，每次交差编译一次，编译没过自动回喂"),

    TEST("测试", "在容器副本里按清单逐条验收，结论与证据写回清单；提交要附一段总结");

    private final String title;
    private final String what;

    Node(String title, String what) {
        this.title = title;
        this.what = what;
    }

    /** 界面上那一格的名字。 */
    public String title() {
        return title;
    }

    /** 这一格在干什么（悬停才出现的那句长解释）。 */
    public String what() {
        return what;
    }

    /** 线上形状：小写名（`env` / `probe` / `cases` / `dev` / `test`）。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 直接前置；第一个（{@link #ENV}）没有前置，给 {@code null}。 */
    public Node previous() {
        return ordinal() == 0 ? null : values()[ordinal() - 1];
    }

    /** 自己以及它下游的所有节点——**回退作废要清掉的正是这一串**。 */
    public List<Node> from() {
        return List.of(values()).subList(ordinal(), values().length);
    }

    /** 五个键的连写（报错话术里给人看）。 */
    public static String keys() {
        StringBuilder text = new StringBuilder();
        for (Node node : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(node.key());
        }
        return text.toString();
    }

    /**
     * 认一个线上形状的键。<b>认不出来就抛，不猜</b>：
     * 猜出来的那个节点会让用户在一个他没点的格子上干活，而这件事在界面上看不出来。
     *
     * <p>小写键与枚举名都认（`dev` / `DEV`），因为键一半来自路由、一半来自人手写的文件。
     */
    public static Node of(String key) {
        String wanted = key == null ? "" : key.strip();
        for (Node node : values()) {
            if (node.key().equalsIgnoreCase(wanted) || node.name().equalsIgnoreCase(wanted)) {
                return node;
            }
        }
        throw new IllegalArgumentException("节点认不出来：" + key + "，只允许 " + keys());
    }
}
