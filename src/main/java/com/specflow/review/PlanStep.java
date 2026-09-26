package com.specflow.review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 施工单上的一步。
 *
 * <p>为什么要把「一步」做成数据而不是一句自然语言：整份施工单是<b>循环的骨架</b>——
 * 引擎要按它在步与步之间回滚、要按步数分配轮次预算、要在某一步卡死时把整轮撤回去。
 * 这些判断没有一条能靠读散文做。
 *
 * <p>字段里有两条是<b>机器要校验</b>的（见 {@link StepAudit}），不是给人看的注解：
 * <ul>
 *   <li>{@code files} —— 必须落在 {@code spec.targets()} 里。清单是硬白名单，
 *       写了个清单外的文件，这一步<b>物理上做不了</b>，越早知道越好</li>
 *   <li>{@code intermediate} —— 这一步做完，整个项目<b>可能编不过</b>。
 *       按「功能」而不是按「层」切步时，这件事有时躲不掉：Java 接口加一个方法，
 *       所有实现类立刻编不过，而「加接口 → 加实现 → 改调用点」正是正确的顺序</li>
 * </ul>
 *
 * @param index        第几步，从 1 开始；给人和界面用，引擎本身按列表顺序走
 * @param goal         一句话说清这一步做什么
 * @param files        这一步要动的文件，相对项目根；必须都来自目标文件清单
 * @param check        这一步怎么算做完。<b>当前只显示</b>，
 *                     但字段留着——将来的 Test Agent 可以直接把它挂成 Verifier 执行，
 *                     到那时展示层一行都不用改
 * @param intermediate {@code true} 表示做完这一步整个项目可能编译不过
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanStep(
        int index,
        String goal,
        List<String> files,
        String check,
        boolean intermediate
) {

    public PlanStep {
        goal = goal == null ? "" : goal.strip();
        check = check == null ? "" : check.strip();
        files = cleanFiles(files);
    }

    @JsonCreator
    public static PlanStep of(
            @JsonProperty("index") Integer index,
            @JsonProperty("goal") String goal,
            @JsonProperty("files") List<String> files,
            @JsonProperty("check") String check,
            @JsonProperty("intermediate") Boolean intermediate) {
        return new PlanStep(index == null ? 0 : index, goal, files, check,
                intermediate != null && intermediate);
    }

    /** 一步的标题：界面上那行「第 N 步：……」都用它，免得各处自己拼。 */
    public String title() {
        return "第 " + index + " 步：" + (goal.isEmpty() ? "（这一步没写做什么）" : goal);
    }

    private static List<String> cleanFiles(List<String> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<String> clean = new ArrayList<>(files.size());
        for (String file : files) {
            if (file != null && !file.isBlank()) {
                clean.add(PathForms.normalize(file));
            }
        }
        return List.copyOf(clean);
    }
}
