# specflow v2 · 任务清单（唯一进度来源）

> 规矩：一条任务 = 一个**包**（或一个文件夹）的完整所有权。
> 谁写哪个包，别人不碰。每条都要能独立验收：`mvn -o test -Dtest=<类> -Dfrontend.skip=true` 或 `npx vitest run`。
> 状态：`TODO / DOING / DONE / BLOCKED`。**做完就改这里**——这是给"下一个不记得发生什么的人"看的。

## 冻结的接口（动手前先看这一节；要改先改这里）

```java
// com.specflow.core
record Event(long id, String type, String at, String node, String level, String text,
             String tool, String args, String result, String callId, String status,
             Long millis, String resultFull, int round, Object payload)
    // 工厂：log / toolStart / toolEnd / result / gate
class EventLog {                    // 单写者；id 恒等于行号；只读盘，不缓存
    EventLog(Path file)
    Event append(Event e)           // 盖 id、追加一行、返回盖好 id 的那条
    List<Event> read(long from, int limit)   // 按行号读（from 含）
    long next()  long lines()
    Path truncateTo(long keepLines)  // 回退作废：先留副本再截
}
interface Progress {                // 长任务的唯一过程口（不变量 4）
    void step(String name, String command, long timeoutSeconds);
    void stepDone(String name);
    void line(String text);
    void warn(String text);
    void fail(String text, String raw);
    void done(String text, Map<String,Object> extra);
    boolean cancelled();  long elapsedSeconds();
    static Progress quiet()
}
class Atomic {  static void write(Path,String);  static String read(Path,String def); }
class Layout {  Path projectYaml(); envYaml(); localEnv(); flowDir(); currentFlow();
                flowJson(id); events(id); cases(id); probe(id); summary(id);
                snapshot(id); runsDir(id); envDir(id); }
class Cmd {     record Result(int exit,String output,boolean timedOut,long millis){boolean ok()}
                static Result run(List<String> argv, Path workdir, String stdin,
                                  long timeoutSeconds, Consumer<String> onLine) }
class Llm {     static Llm from(Project p);  String complete(List<Msg> messages);  record Msg(String role,String content) }
class Project { static Project open(Path root); Path root(); String compile(); LlmConfig llm(); }
```

```java
// com.specflow.flow
enum Node { ENV, PROBE, CASES, DEV, TEST }     // ordinal 就是顺序
enum State { LOCKED, READY, DONE }
record Facts(String compile, boolean envReady, boolean envNotNeeded, boolean envDeclared,
             boolean probeFiles, boolean probePrompt, int caseCount, int casePending,
             boolean testRun, boolean submitted, boolean compilePassed)
record NodeView(Node node, State state, String blockedReason, Map<String,String> facts)
class Gates { static String blocked(Node n, Facts f) }   // 空串 = 能做。**唯一判据**
class FlowStore { FlowState load(); void save(FlowState); }   // flow.json，原子写
class FlowService {  // 五节点的动作都在这里；路由只做参数翻译
    NodeView[] view();  void freeze(Node);  List<String> gotoNode(Node);
    void note(Node, Map<String,String>);  EventLog events();
}
```

## 任务

| # | 任务 | 包 / 目录 | 依赖 | 状态 |
|---|---|---|---|---|
| T0 | **核心地基**：Json / Atomic / Layout / Event / EventLog / Progress / Cmd / Llm / Project | `core/` | — | DOING（我） |
| T1 | **环境**：env.yaml 模型与校验、compose 渲染、up/down/init/reset/cancel | `env/` | T0 | TODO |
| T2 | **流程**：五节点状态机、闸、flow.json、回退作废、快照、崩溃恢复 | `flow/` | T0 | TODO |
| T3 | **前端**：七个页面 + 组件 + token + 纯函数库 + 单测 | `src/main/frontend/` | — | DOING（subagent） |
| T4 | **ReAct 循环 + 工具层**：Tool/ToolRegistry/结果形状、Loop、六个工具 | `agent/` | T0 | DONE |
| T5 | **两个 Agent**：探针（只读）、开发（白名单内改 + 编译回喂） | `agent/` | T4 | TODO |
| T6 | **测试 Agent**：用例清单模型、容器沙箱、逐条验收、审批 | `test/` | T4 | T6a DONE |
| T7 | **路由**：`/api/*` 全表 + 错误翻译 + Spring 装配 | `web/`、`Main.java` | T0–T6 | TODO（我） |
| T8 | **接线与真跑**：真模型 + 真容器，五节点走通 | — | T7 | TODO（我） |
| T9 | **验收**：单测 + web 级 + 浏览器冒烟 + 交付文档 | `src/test/`、`docs/` | T8 | TODO（我） |

## 每个任务的验收（Definition of Done）

- **T0**：`mvn -o -q test-compile -Dfrontend.skip=true` 过；`EventLogTest` 钉住「id = 行号」「重启接着数」「截断留副本」。
- **T1**：`EnvConfigTest`（两种 init 写法、非法声明被拒）+ `ComposeTest`（渲染出的 compose 不含 init、只有 app 挂项目目录）+ `EnvironmentTest`（用假 runner：逐条 init、失败即停并收环境）。
- **T2**：`GatesTest`（五档各一条，含「起环境成功过 → ① 能冻结」）+ `FlowServiceTest`（回退作废下游 + 事件流截断留副本）+ `SnapshotTest`（拍/回/接受）+ `JournalTest`（没有 END 的运行被收成中断）。
- **T3**：`npx vitest run` 全绿；`npx vite build` 过；**七个路由在浏览器里零 JS 报错**（`tests/smoke.mjs`）。
- **T4**：`LoopTest`（轮次上限、工具结果注入、submit 收场、observer 推两条带 callId 的事件）+ 每个工具的 `ToolXxxTest`。
- **T5**：`ProbeTest` / `DevTest`（脚本化假模型：走通一轮、回喂、白名单外的写被拒）。
- **T6**：`CaseListTest`（终态校验）+ `TestAgentTest`（假沙箱：逐条写回、无 PENDING 才算完）。
- **T7**：`ApiTest`（每条路由 200/409 各一条；错误一律 `{"error":…}`）。
- **T8**：`run.mjs` 对 `fixture/` 跑通一遍：① 起容器 → ② 产物 → ③ 用例 → ④ 编译过 → ⑤ 全终态 → 闭环。
- **T9**：`mvn -o clean test` 全绿；`smoke.mjs` 六页零报错；`docs/DELIVERY.md` 写清怎么跑、怎么验、没做什么。
