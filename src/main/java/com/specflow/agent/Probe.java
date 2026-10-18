package com.specflow.agent;
import com.specflow.agent.tools.ReadFileTool;
import com.specflow.agent.tools.ReadListTool;
import com.specflow.agent.tools.SubmitTool;

import com.specflow.core.Llm;
import com.specflow.core.Project;
import com.specflow.core.Progress;
import com.specflow.agent.tools.Roots;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>② 上下文探针</b>：只读地读一遍项目，交出四样东西。
 *
 * <p>上一版这里绕了一层「任务草稿」（{@code TaskDraft}），然后由另一处把它翻译成这四样。
 * 那一层带来的全是麻烦：两个真相、一个翻译层、以及"草稿里说改 A、翻译出来成了 B"。
 * 这一版<b>没有中间概念</b>——探针交出来的就是这四样，落的也是这四样。
 *
 * <p>四样：
 * <ol>
 *   <li>{@code files} —— 开发<b>能读</b>的文件（可读清单）；</li>
 *   <li>{@code changes} —— 写白名单：**要改的**（必须在 files 里）+ **要新建的**（可以还不存在）；</li>
 *   <li>{@code context} —— 几段要喂给开发的文本，一条一句"为什么相关"；</li>
 *   <li>{@code prompt} —— 组装好的 prompt，能直接交给开发。</li>
 * </ol>
 *
 * <p>它<b>只读</b>：整段运行里一个字节都不许写进项目（唯一的写盘是"落这四样"，
 * 而那件事由调用方做，见 {@code web.Api}）。
 */
public final class Probe {

    /** 探针交出来的四样。 */
    public record Artifacts(List<String> files, List<String> changes, List<String> context,
                            String prompt) {

        public Artifacts {
            files = files == null ? List.of() : List.copyOf(files);
            changes = changes == null ? List.of() : List.copyOf(changes);
            context = context == null ? List.of() : List.copyOf(context);
            prompt = prompt == null ? "" : prompt.strip();
        }

        public Map<String, Object> view() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("files", files);
            body.put("changes", changes);
            body.put("context", context);
            body.put("prompt", prompt);
            return body;
        }
    }

    /**
     * 一次探针的结论。
     *
     * @param status  {@code DONE}（交出了四样）/ {@code ROUND_LIMIT} / {@code UNPARSABLE} /
     *                {@code REJECTED}（交出来的通不过校验）/ {@code MODEL_FAILED}
     * @param reason  没成时那句人话（界面直接显示它）
     */
    public record Result(String status, String statusLabel, String reason, Artifacts artifacts,
                         Outcome loop) {

        public boolean ok() {
            return "DONE".equals(status) && artifacts != null;
        }
    }

    private final Project project;
    private final Llm llm;
    private final Progress progress;
    private final Watcher watcher;
    private final int rounds;

    public Probe(Project project, Llm llm, Progress progress, Watcher watcher, int rounds) {
        this.project = project;
        this.llm = llm;
        this.progress = progress == null ? Progress.quiet() : progress;
        this.watcher = watcher == null ? Watcher.none() : watcher;
        this.rounds = rounds <= 0 ? 20 : rounds;
    }

    public Result run(String requirement, String design) {
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("需求是空的：探针照它去读项目、判断该动哪些文件");
        }
        ToolRegistry tools = ToolRegistry.of(
                StuckCheck.wrap(new ReadListTool(new Roots(project.root()))),
                StuckCheck.wrap(new ReadFileTool(new Roots(project.root()))),
                new SubmitTool());

        Loop loop = new Loop("探针", llm, new LoopLimits(rounds, 200, 300, 1), tools, watcher, progress);
        Outcome outcome;
        try {
            outcome = loop.run(systemPrompt(), userMessage(requirement, design));
        } catch (RuntimeException e) {
            return new Result("MODEL_FAILED", "模型没回来",
                    "连不上模型或者它连续报错：" + e.getMessage(), null, null);
        }
        if (!outcome.ok()) {
            return new Result(outcome.status(), outcome.statusLabel(),
                    outcome.reason() == null || outcome.reason().isBlank()
                            ? "它没交出一份能用的产物" : outcome.reason(), null, outcome);
        }
        Answer answer = parse(outcome.answer());
        if (answer == null) {
            return new Result("UNPARSABLE", "它交的东西读不出来",
                    "交差那一轮给的不是一份 json（引擎不会替你猜该动哪些文件）："
                            + shortText(outcome.answer()), null, outcome);
        }
        String problem = validate(answer);
        if (problem != null) {
            return new Result("REJECTED", "交出来的产物不合法", problem, null, outcome);
        }
        return new Result("DONE", "已完成", "", answer.artifacts(), outcome);
    }

    // ---------- 提示词 ----------

    private String systemPrompt() {
        return """
                你是这个项目里的**上下文探针**。你只读、不改：整段运行里一个字节都不许写进项目。

                你的任务：照着用户给的需求与设计，把这个项目读一遍，然后交出**四样东西**：

                - `files`   —— 开发**能读**的文件（相对项目根的路径，比如 `src/main/java/com/demo/Foo.java`）
                - `changes` —— 开发**允许改动**的文件（= 写白名单）。**要改的**与**要新增的**都放这一栏，
                  而且尽量少：能改一个就不要列两个
                - `context` —— 几段要喂给开发的文本，一条一句"为什么相关"（例：`Foo.java（库存扣减都在这一个方法里）`）
                - `prompt`  —— 组装好的 prompt：能**直接**交给开发用。里面要写清"改什么、改成什么样、不许动什么"

                ## 最容易漏的一栏：**要新增的文件**（真踩过）

                用户拿 api 文档来重做接口时，要的东西往往**原来并不存在**：DTO、VO、Entity、Converter、
                新 Controller 的分支……开发那边只能动写白名单里的文件，**所以这些新文件必须由你在
                `changes` 里列全**，否则它想建也建不了，交出来的就只有"要改哪几个"，一个新增文件都没有。

                判断方法：把"这个需求要落地"需要的文件逐个过一遍——
                - 已有、要改 → 列进 `changes`；
                - **已有的同层目录里通常就有对应物**（`dto/` 下有 `BookDTO`？那新接口多半要 `XxxDTO`）：
                  先 `read_list` 看一眼同层，再照同样的包名/命名风格把**新路径**写出来（例如
                  `src/main/java/com/library/dto/BookCreateDTO.java`）；
                - 新路径要写**完整的相对路径**（含包目录）。**只放进 `changes`，不要放进 `files`**
                  （`files` 是「要读的文件」，列一个还不存在的路径会被引擎驳回）；
                - `context` 里用一句话说明"这个文件是新增的、放哪、照着哪个已有文件的样子写"。

                别怕列多：列进 `changes` 只是"允许它动"，不是"必须动"。**漏列才是硬伤**——
                漏了它连建都建不出来。

                规矩：

                1. **只写你读到的**。已有文件的路径必须是你用 `read_list` / `read_file` 真看到过的；
                   猜出来的路径一律不许出现。**唯一的例外是上一条说的"新增文件"**：那必须由你读过的
                   同层目录 + 命名风格推出来，并在 `context` 里说明理由。拿不准就在 `context` 里说拿不准。
                2. `changes` 要**克制**：需求没要求的地方不要列进去（但"需求要求的新增类"必须列）。
                3. `prompt` 里不许写死你猜的类名/方法名——用你读到的原文。
                4. 先用 `read_list` 摸结构，再用 `read_file` 读关键文件（**不要**一次读十几个），
                   读完就交差。**不要**改文件，你没有那个工具。

                怎么调工具、怎么交差：见下面那段协议。
                """ + "\n\n" + Protocol.instructions() + """
                ## 你的回复只能是下面两种之一（**不许有任何别的内容**）

                1. 一个围栏标记为 `specflow-tool` 的代码块 —— 调一次工具：
                   `specflow-tool
                   {"name":"read_list","arguments":{"path":"."}}
                   `
                2. 一个围栏标记为 `json` 的代码块 —— 交差（形状见上）。

                **不要写解释、不要写「好的」「我先看看」、不要在代码块前后加任何一句话。**
                引擎只读那个代码块，别的内容一律当作这一轮什么都没说。""";
    }

    private String userMessage(String requirement, String design) {
        StringBuilder out = new StringBuilder();
        out.append("## 需求（用户原话，一个字都不许改写）\n\n").append(requirement.strip()).append('\n');
        if (design != null && !design.isBlank()) {
            out.append("\n## 设计（用户自己的说明）\n\n").append(design.strip()).append('\n');
        }
        String top = String.join("  ", project.topLevel());
        if (!top.isBlank()) {
            out.append("\n## 项目根下有什么（引擎列的）\n\n").append(top).append('\n');
        }
        out.append("""

                现在开始：先 read_list 摸结构，再 read_file 读关键文件，最后只输出一个 json 代码块交差。

                交差的 json 形状（**四栏都要有**）：

                ```json
                {
                  "files": ["src/main/java/com/demo/Foo.java"],
                  "changes": ["src/main/java/com/demo/Foo.java"],
                  "context": ["Foo.java（要改的那个方法在这里）"],
                  "prompt": "需求：… 要改：… 改成：… 不许动：…"
                }
                ```
                """);
        return out.toString();
    }

    // ---------- 解析与校验 ----------

    /** 交差那一轮的 json。 */
    private record Answer(List<String> files, List<String> changes, List<String> context, String prompt) {

        Artifacts artifacts() {
            return new Artifacts(files, changes, context, prompt);
        }
    }

    @SuppressWarnings("unchecked")
    private static Answer parse(String raw) {
        String json = firstJsonBlock(raw);
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> map = com.specflow.core.Json.map(json);
            return new Answer(strings(map.get("files")), strings(map.get("changes")),
                    strings(map.get("context")), String.valueOf(map.getOrDefault("prompt", "")).strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 从一段模型输出里取出第一个 json 代码块（没有围栏时退化成"整段就是 json"）。 */
    static String firstJsonBlock(String raw) {
        if (raw == null) {
            return null;
        }
        int fence = raw.indexOf("```json");
        if (fence >= 0) {
            int start = raw.indexOf('\n', fence);
            int end = raw.indexOf("```", start + 1);
            if (start > 0 && end > start) {
                return raw.substring(start + 1, end).strip();
            }
        }
        String trimmed = raw.strip();
        return trimmed.startsWith("{") && trimmed.endsWith("}") ? trimmed : null;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (Object one : items) {
            String text = one == null ? "" : String.valueOf(one).strip();
            if (!text.isBlank()) {
                found.add(text);
            }
        }
        return List.copyOf(found);
    }

    /** 交出来的四样通不通；通过返回 {@code null}。每一条都是人话。 */
    private String validate(Answer answer) {
        if (answer.files().isEmpty()) {
            return "它一个可读文件都没给（files 是空的）：开发没有东西可读，这一步等于没做";
        }
        if (answer.prompt().isBlank()) {
            return "prompt 是空的：开发照它干活，空 prompt 等于让它自己发明一个需求";
        }
        // `files` = 开发要**读**的：必须真存在（读一个不存在的文件没有意义，多半是猜的）。
        List<String> missing = new ArrayList<>();
        for (String file : answer.files()) {
            if (!java.nio.file.Files.isRegularFile(project.root().resolve(file))) {
                missing.add(file);
            }
        }
        if (!missing.isEmpty()) {
            return "这些路径在项目里不存在：" + String.join("、", missing)
                    + "（files 是「要读的文件」，只能是读到的）。"
                    + "要**新增**的文件请只放进 changes，不要放进 files。";
        }
        // `changes` = 写白名单：**已有**的必须同时可读（能改的必须先能读）；
        // **还没有**的算"新建"——这类不需要出现在 files 里，否则等于要它去读一个不存在的文件。
        List<String> outside = new ArrayList<>();
        for (String change : answer.changes()) {
            boolean exists = java.nio.file.Files.isRegularFile(project.root().resolve(change));
            if (exists && !answer.files().contains(change)) {
                outside.add(change);
            }
        }
        if (!outside.isEmpty()) {
            return "changes 里有已存在、但不在 files 里的路径：" + String.join("、", outside)
                    + "（要改已有文件，得先把它列进 files 让开发读一遍）";
        }
        return null;
    }

    private static String shortText(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /** 探针只读：这一包不提供任何写工具（清单在这里，测试也钉它）。 */
    static List<Path> readonlyRoots(Project project) {
        return List.of(project.root());
    }
}
