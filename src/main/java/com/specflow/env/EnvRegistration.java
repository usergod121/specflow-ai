package com.specflow.env;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 「这次起了什么」——<b>登记</b>，也是清理的依据（十五.8 的第一件）。
 *
 * <p>为什么非要有这一份：清理这件事最怕的不是漏，而是<b>不知道该清什么</b>。
 * 一次运行可能起了容器、建了卷、写了目录，而它们在磁盘和 docker 里长得和
 * 「用户自己起的东西」一模一样。没有登记，清理就只能靠按名字猜——
 * 猜错了删掉的是别人的东西，猜不到的攒到下一次。
 *
 * <p>它同时进<b>运行留档</b>：事后翻记录的人要能回答「那次跑测试的时候，
 * 环境是什么状态、跑过哪几条 init/reset」。留档里只有一句「测试没过」是不够的。
 *
 * <p>{@code services/containers/networks/volumes} 记的是<b>名字</b>，不是 id：
 * 名字是人能看懂、也能自己拿去 {@code docker rm} 的东西；id 只对机器有意义，
 * 而这份登记主要是给人看的。清理的时候按名字加标签双重核对（见
 * {@link TestEnvironment#cleanupLeftovers()}）。
 *
 * @param state          这套环境当时是什么状态，见 {@link State}
 * @param docker         用的哪条 docker 命令（{@code docker} 还是 {@code wsl docker}）
 * @param composeProject compose 项目名（{@code sf-<项目>}）。容器、网络、卷都挂在这个名字下
 * @param composeFile    那份生成的 compose 文件（相对项目根）。<b>它是把环境关掉的把手</b>：
 *                       容器留着复用时，这个文件必须一起留着，否则下次没法 down
 * @param services       compose 里的服务名（app 加上各个依赖）
 * @param containers     现在活着的容器名
 * @param networks       这个项目的网络名
 * @param volumes        这个项目的卷名
 * @param directories    这次建出来的目录（相对项目根）
 * @param commands       这次跑过的 init/reset 命令原文——留档要答得出「它当时跑了什么」
 * @param detail         一句话结论（没声明、没初始化、起不来、好了……）
 */
public record EnvRegistration(
        State state,
        String docker,
        String composeProject,
        String composeFile,
        List<String> services,
        List<String> containers,
        List<String> networks,
        List<String> volumes,
        List<String> directories,
        List<String> commands,
        String detail
) {

    /**
     * 这套环境处在哪一档。
     *
     * <p>它比布尔量多的那几档都是<b>真的会同时存在</b>的状态，不是细分出来的装饰：
     * 「没声明」和「声明了但没初始化」对用户是两件不同的事（前者要写文件，后者要点头），
     * 而「坏掉了」和「没起来」要做的事也不一样（前者要先疑、后者只要点一下）。
     */
    public enum State {

        /** 项目里没有 {@code env.yaml}：只能跑单元测试（这批之前的行为）。 */
        NOT_DECLARED("没有声明测试环境"),
        /** 声明了，但还没初始化——这时候集成测试还不能勾。 */
        NOT_READY("还没初始化"),
        /** 环境活着，可以用。 */
        READY("已就绪"),
        /** 起的过程中失败了，原始错误在上面。 */
        BROKEN("没起起来"),
        /** 环境被关掉了（用户手动、关项目、或者上一次收残局收掉了）。 */
        DOWN("已关闭");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public EnvRegistration {
        composeProject = composeProject == null ? "" : composeProject;
        composeFile = composeFile == null ? "" : composeFile;
        services = copy(services);
        containers = copy(containers);
        networks = copy(networks);
        volumes = copy(volumes);
        directories = copy(directories);
        commands = copy(commands);
        detail = detail == null ? "" : detail;
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /** 这次登记下来的东西一共有几件（容器 + 网络 + 卷 + 目录）——「残留超阈值告警」按它算。 */
    public int size() {
        return containers.size() + networks.size() + volumes.size() + directories.size();
    }

    /**
     * 给界面/留档的一行。
     *
     * <p>没起任何东西时不写「0 个容器 0 个网络」这种话：那是「什么都没有」，
     * 说出来只会让人以为发生过什么。
     *
     * <p>{@code READ_ONLY} 是必须的：它是<b>算出来的</b>，不是记录里的一个字段。
     * 只读属性让 Jackson 序列化时带上它（界面直接用），而反序列化时忽略它——
     * 不带这个访问级别，留档读回来会因为「多了个不认识的字段」整条失败，
     * 而读失败是静默跳过，用户只会发现历史里少了一条记录。
     */
    @JsonProperty(value = "summary", access = JsonProperty.Access.READ_ONLY)
    public String summarize() {
        if (size() == 0) {
            return state.label() + (detail.isEmpty() ? "" : "：" + detail);
        }
        StringBuilder out = new StringBuilder(state.label()).append("：");
        if (!containers.isEmpty()) {
            out.append("容器 ").append(String.join("、", containers)).append("；");
        }
        if (!volumes.isEmpty()) {
            out.append("卷 ").append(String.join("、", volumes)).append("；");
        }
        if (!networks.isEmpty()) {
            out.append("网络 ").append(String.join("、", networks)).append("；");
        }
        if (!directories.isEmpty()) {
            out.append("目录 ").append(String.join("、", directories)).append("；");
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }
}
