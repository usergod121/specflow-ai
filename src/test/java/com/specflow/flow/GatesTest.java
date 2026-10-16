package com.specflow.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 闸：<b>唯一判据</b>。五档各一条（`TASKS.md` T2 的验收），外加两条硬规则的钉子。
 *
 * <p>它全是纯函数，所以这个测试不碰盘、不起服务——上一版闸的判据散在路由与服务里，
 * 只能靠端到端跑一遍才发现「起环境成功过」闸根本没看见。
 */
class GatesTest {

    @Test
    @DisplayName("① 编译命令非空 + 起环境成功过 → 能冻结（上一版这里永远为假）")
    void envFreezesWhenCompileConfiguredAndEnvironmentRan() {
        assertThat(Gates.blocked(Node.ENV, facts("mvn -q -DskipTests compile", true, false, false)))
                .isEmpty();
    }

    @Test
    @DisplayName("① 编译命令是空的 → 拒，且说的是「编译命令」")
    void envBlockedWithoutCompileCommand() {
        assertThat(Gates.blocked(Node.ENV, facts("", true, false, false)))
                .contains("编译命令").contains("空");
    }

    @Test
    @DisplayName("① 环境既没起过也没声明不需要 → 拒")
    void envBlockedWhenEnvironmentNeverRan() {
        String blocked = Gates.blocked(Node.ENV, facts("mvn -q compile", false, false, true));
        assertThat(blocked).contains("环境还没起来").contains("不需要环境");
        // 盘上有 env.yaml 但没起成功过时，话里要说清这一点（否则用户以为已经起过了）
        assertThat(blocked).contains("env.yaml");
    }

    @Test
    @DisplayName("① 显式声明「这个项目不需要环境」→ 能冻结")
    void envFreezesWhenDeclaredNotNeeded() {
        assertThat(Gates.blocked(Node.ENV, facts("mvn -q compile", false, true, false))).isEmpty();
    }

    @Test
    @DisplayName("② 探针：可读文件与 prompt 缺一不可")
    void probeNeedsFilesAndPrompt() {
        assertThat(Gates.blocked(Node.PROBE, facts("mvn compile", true, false, false))).isEmpty();
        assertThat(Gates.blocked(Node.PROBE,
                new Facts("mvn compile", true, false, false, false, true, 3, 0, true, true, true)))
                .contains("能读的文件");
        assertThat(Gates.blocked(Node.PROBE,
                new Facts("mvn compile", true, false, false, true, false, 3, 0, true, true, true)))
                .contains("prompt");
    }

    @Test
    @DisplayName("③ 用例：一条都没有 → 拒")
    void casesNeedAtLeastOne() {
        assertThat(Gates.blocked(Node.CASES, facts("mvn compile", true, false, false))).isEmpty();
        assertThat(Gates.blocked(Node.CASES,
                new Facts("mvn compile", true, false, false, true, true, 0, 0, false, false, true)))
                .contains("一条用例都没有");
    }

    @Test
    @DisplayName("④ 开发：编译没过 → 拒")
    void devNeedsCompilePassed() {
        assertThat(Gates.blocked(Node.DEV, facts("mvn compile", true, false, false))).isEmpty();
        assertThat(Gates.blocked(Node.DEV,
                new Facts("mvn compile", true, false, false, true, true, 3, 0, true, true, false)))
                .contains("编译");
    }

    @Test
    @DisplayName("⑤ 测试：还有 PENDING、或者没提交 → 拒")
    void testNeedsNoPendingAndSubmitted() {
        assertThat(Gates.blocked(Node.TEST, facts("mvn compile", true, false, false))).isEmpty();
        assertThat(Gates.blocked(Node.TEST,
                new Facts("mvn compile", true, false, false, true, true, 3, 2, true, true, true)))
                .contains("2 条").contains("PENDING");
        assertThat(Gates.blocked(Node.TEST,
                new Facts("mvn compile", true, false, false, true, true, 3, 0, true, false, true)))
                .contains("提交");
    }

    @Test
    @DisplayName("硬规则：前置没 DONE → 下游被拒，且那句人话说出了差哪一步")
    void downstreamBlockedWhilePreviousNotDone() {
        assertThat(Gates.blocked(Node.PROBE, facts("mvn compile", true, false, false),
                states(Node.ENV, State.READY)))
                .contains("环境搭建").contains("还没做完");
        // 「还没开始」与「做了但没冻结」是两种场合，劝的话不该一样
        assertThat(Gates.blocked(Node.PROBE, facts("mvn compile", true, false, false),
                states(Node.ENV, State.LOCKED)))
                .contains("还没开始");
        // 前置做完了就不该再拦（本节点自己的门槛由另一个重载判）
        assertThat(Gates.blocked(Node.PROBE, facts("mvn compile", true, false, false),
                states(Node.ENV, State.DONE)))
                .isEmpty();
    }

    @Test
    @DisplayName("硬规则：顺序拦在门槛之前（先解决「该去做哪一个」）")
    void orderComesBeforeThreshold() {
        String blocked = Gates.blocked(Node.TEST, Facts.empty(), states(Node.DEV, State.READY));
        assertThat(blocked).contains("开发").doesNotContain("PENDING");
    }

    @Test
    @DisplayName("判闸要节点：传 null 是编程错误，不当成「能做」")
    void nodeIsRequired() {
        assertThatThrownBy(() -> Gates.blocked(null, Facts.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gates.blocked(null, Facts.empty(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 五档门槛全过的一份事实（只改要点名的那几栏时用完整构造，读起来更直白）。 */
    private static Facts facts(String compile, boolean envReady, boolean envNotNeeded,
                               boolean envDeclared) {
        return new Facts(compile, envReady, envNotNeeded, envDeclared,
                true, true, 3, 0, true, true, true);
    }

    /** 一份状态表：只把点名的那一格设成给定状态，其余当「前置都做完了」（其余与本条无关）。 */
    private static Map<Node, State> states(Node which, State state) {
        Map<Node, State> out = new EnumMap<>(Node.class);
        for (Node node : Node.values()) {
            out.put(node, node == which ? state : State.DONE);
        }
        return out;
    }
}
