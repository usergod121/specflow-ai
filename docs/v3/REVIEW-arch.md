# specflow v3 · 架构锐评（只问一件事：6 小时能不能落地、会不会砸现状）

> 口径：剩余 ~6h，含测试/端到端/交付文档。已有 149 后端 + 105 前端全绿。
> 评审对象：`ARCH-V3.md`（下称 §）、`STATE.md`、`PLAN.md`、当前工作树（`git status` 脏树）。
> 判定标准只有两条：**能不能在盒子里做完**；**做完之后 149 + 105 会不会红、端到端会不会再过不去**。

## 0. 动手前先纠正三个前提（不纠正，后面全是白干）

**0.1 `PLAN.md` 与磁盘不是一回事——C1 已经做完大半，账上一个勾都没有。**
`PLAN.md` C1.1–C1.9 全是 ◻，但代码里已经有：`env/Capabilities.java`（容器能力校验 = C1.1）、
`test/Preflight.java` 且已接进 ⑤ 开工前（`Api.java:581-587` = C1.3）、`test/CaseBudget.java`（每用例 12 轮、
超限标 SKIPPED = C1.5）、`Api.java:589` 的 `Math.max(60, count*12+20)`（C1.9 的半边）、
`CaseList.reopen` + `/api/node/test/case/retry`（C1.5 重试续跑）。而 `git status` 是 **13 改 + 6 新增未跟踪**。
→ 后果有两个：① 6 小时里你极可能把已有代码重写一遍（这是"砸现状"最现实的路径）；
② 此刻**没有回滚点**——`v2-working` tag 的代码里没有 CaseBudget/Preflight/Capabilities。

**0.2 "149 条绿"给不了你想要的安全感。**
`src/test` 里没有任何一个文件覆盖 `web/Api.java`（950 行）、`flow/FlowService.java`、`flow/Runs.java`、
`flow/Store.java`、`flow/Journal.java`、`flow/Snapshot.java`。149 条测试按文件分布是：
`GatesTest` 14、`CaseListTest` 18、`LoopTest` 13、`ToolsTest` 12、`ToolRegistryTest` 9、`EnvironmentTest` 9、
`EnvCliTest` 9、`ProtocolTest` 8、`ApplyPatchTest` 8、`EnvConfigLoaderTest` 8、`ReadFileTest` 7、`SandboxTest` 7、
`CommandGuardTest` 6、`ComposeTest` 6、`ApprovalsTest` 5、`CaseBudgetTest` 5、`JsonTest` 5。
→ 也就是说：**把 Api / FlowService / SSE 全改坏，149 条一条都不会红。** 绿 ≠ 安全，这一点决定了下面风险表的排法。

**0.3 `ARCH-V3.md` 把若干"已经做完的事"写成"v3 要做的事"。**
§4.1「v3 只加了一个事实：`containerBuild`」——它已经在 `Facts.java:70` 并且已经在闸里（`Gates.java:98`）。
§6 映射表里「`test.Frozen`/`test.CaseBudget`/审批判断 → `agent.ToolHook`」——`Frozen` 与 `CaseBudget` 已在，
已接进 `TestAgent`。**ARCH 其实是一份"现状说明书 + 愿望清单"的混合体**，读的时候必须自己拆开，
否则相当于按说明书重新实现一遍现有代码。

---

## 1. 必须改（不改就不能落地 / 会砸现状）

### M1. 先落一个绿色回滚点，并补 3 条 Api 冒烟（20 分钟，全流程唯一不能省的保险）
改法：
```powershell
mvn -o test                      # 确认脏树上 149 条也是绿的
git add -A; git commit -m "wip: C1（Capabilities/Preflight/CaseBudget/容器构建事实）"; git tag v3-c1
```
再加一个 `src/test/java/com/specflow/web/ApiSmokeTest.java`：`MockMvc` 打三个 GET —— `/api/state`、
`/api/flow`、`/api/node/env/events?from=0`，断言 200 且 `flow.nodes` 有 5 项。
理由：见 0.1 + 0.2。`Api.java` 950 行零覆盖，而你要在它上面动 `Action`/`Step`（§1.4、§1.5）。
这 20 分钟买到的是"改坏了 30 秒内知道"，没有它，后面每一小时都在赌。

### M2. `Gate` 的签名装不下"顺序"，照 §1.3 直接写会让 ① / ② 重新自锁
§1.3（第 109–114 行）把闸定义成 `String blocked(Facts facts)`，只有一个参数。
但现在的闸是**两层**（`Gates.java:43` 与 `Gates.java:63` 两个重载）：第一层看
`Map<Node, State>` 判"上一步没做完 → 一律拒"，第二层才看本节点自己的门槛；
而 `FlowService.requireReachable`（`FlowService.java:163`）**故意绕过第二层**，
因为「跑探针」正是产出探针产物的那一步，按完整闸判会死锁（类注释第 152–161 行记着这个坑，端到端撞过一次）。
改法：`Gate` 至少是 `String blocked(Facts facts, Map<Node, State> states)`，或者显式引入 `GateCtx{facts, states, node}`；
并且 `Action` 必须能声明"我这个动作只判前置"（对应 `requireReachable` 那个口子，否则 §1.4 的
`Predicate<Facts> enabled` 会把 ① 的「探测环境」按钮永久 disable）。
理由：这是全篇**唯一一条已经真撞过的死锁**。签名一错，用户在 ① 上第一次进来连按钮都点不动，
比"149 红"严重得多——它直接砸掉端到端。

### M3. §1.4 的 `Action` 装不下请求体，通用分发只能覆盖一半路由
`record Action(..., Predicate<Facts> enabled, Step body)`，`Step.run(Ctx)` 只有一个参数。
而现在带 payload 的动作至少 10 条：`env/save(envYaml, compile)`（`Api.java:139`）、`env/settings`、
`probe/brief`、`probe/save`、`cases/save`、`test/case/retry(id)`、`test/approve(id, allow)`、
`test/submit(summary)`、`flow/goto(node)`、`flow/freeze(node)`。
`POST /api/flow/action {node, action}` 这个信封根本带不了它们。
改法（二选一，别含糊）：
- **A（推荐，省时）**：`Step` 加 `run(Ctx ctx, Map<String,Object> body)`，`/api/flow/action` 的 body 原样传下去；
- **B（更省时）**：`/api/flow/action` **只允许无参动作**（`env/up`、`env/compile`、`env/skip`、`env/agent`、
  `probe/agent`、`cases/agent`、`dev/run`、`dev/rollback`、`test/run`、`test/approve`（走原路由）、`test/accept`、
  `test/optimize`、`freeze`），其余保留现有路由，并在 `Action` 上标 `needsBody=true`。
理由：不做这个判断，C2.1/C2.2 一开工就会在 `envSave` 那里卡住，然后你会把 950 行 Api 改成
"950 行 + 一层 `Map<String,Object>` 转发"——比现在更冗余，直接违反 `STATE.md` 硬约束 6。

### M4. `AgentRuntime` 必须是 `Loop` 的包装，不是替换（§6 表最后一行要落到代码动作上）
§6 写"保留 `Loop` 作为它的内核"，但 §1.6 的形态（`Budget/Compact/StuckCheck/InterruptGate` 四个字段）读起来像重写。
`Loop` 有 13 条 `LoopTest` 钉着三条"不会破的规矩"（一次调用恰好一前一后两声、注册表不抛、上限永远在），
`Loop.onToolResult`（第 89 行）+ `LoopLimits` 已经是钩子位。
改法：新建 `agent/AgentRuntime.java`，内部**只持有** `Loop` 并组合调用；预算/卡死/压缩都做成
`LoopLimits` 的补充与 `onToolResult` 钩子。`Loop.java` 与 `LoopTest.java` 在**同一个提交里不许被改**。
理由：`Loop.attempt()` 那条"工具挪到守护线程 + join 超时"（第 257–287 行）是超时的真实现，
任何"顺手重构"都会把 13 条测试的信心变成 0，而它换来的收益是 0——因为逻辑本来就在。

### M5. `Facts` 一个字都别动；`flow.yaml` 若坚持要做，先做"事实登记表"，否则不变量 2 会被破坏
`Facts` 是 13 组件的 record，位置构造，`GatesTest`（14 条）与 `FlowService.facts()`（第 310–328 行）都在按位置建它。
而 §1.1 规矩 1「闸引用的只能是 `Facts` 里登记过的名字」要求一个**名字 → 归属节点 → 类型**的登记表；
现在这个映射只以隐含形式存在于 `FlowService.facts()`（`ENV_READY` 从 `Node.ENV` 的袋子里读、`COMPILE_PASSED`
从 `Node.DEV` 读）。另外 `CURSOR = "_cursor"`（`Facts.java:103`）这种内部键根本不是闸的事实。
改法：
- 不动 `Facts` record（要新增事实就在末尾加，同时保留旧构造器；最好不要新增——`containerBuild` 已在）；
- 若要做 `flow.yaml`，**先**把 13 个字段升级成 `enum Fact { ENV_READY(Node.ENV, BOOL), … }` 并让
  `FlowService.facts()` 由这张表驱动，再谈校验器。
理由：`Facts.java:37-41` 那句"这个名字只许出现这一次"是全项目最值钱的不变量。加一层 yaml 名字表而不重构
事实来源，等于把它从 1 处变成 2 处——正好复现 `Facts.java` 类注释里记录的那次事故（写 `envConfirmed`、
读 `envReady`，① 永远冻结不了，后面四格一格都动不了）。

---

## 2. 建议改（按性价比排序）

### S1. `EventLog` 追加时缓存行数（§7 已点名，20 分钟，收益最直接）
现在每次 append 都要数一遍总行数（`id ≡ 行号`），而前端每 800ms 用 `?from=` 拉一次（`store.js:202`），
端到端跑一轮是几千行。改法：`EventLog` 里缓存 `lines`，写时自增，**读盘时以盘上为准校正**（启动/换流程时重算一次）。
验收：一条单测 `append 三次 → lines()==3` + 重启后 `lines()` 仍等于盘上真实行数。

### S2. `StuckCheck` 只做判据 1（§4.4 的第一条，10 行代码）
在 `Loop` 里加 `lastTool + lastArgs` 与计数器，连续 3 次完全相同 → 往 `messages` 插一句"你在原地打转，
换个做法或者写下当前结论"。判据 2（连续 5 次只读不写）阈值还要按阶段放宽到 8，判据 3 要比对输出与退出码——
这两个在"改完再测"的循环里误报率很高，10 行变 100 行。
理由：`STATE.md` 缺陷 5 记的是"7 轮纯 sleep 空转 17.5 分钟"，判据 1 正好打中它。

### S3. `compact` 只做"工具输出首尾各 N 行"（§4.3 的后半，20 分钟）
`LoopLimits.MAX_OUTPUT_CHARS` 与 `Loop.head()`（第 197/298 行）已经在做全局截断；把注入侧改成
"每条工具输出保留首尾各 3 行 + 中段省略标注"。**摘要模型调用那一半不做**（要再花一次 LLM 调用、
要维护锚点表"我改过哪些文件"，那是新的失败源）。
验收：单测——塞一个 5 万字符的 `read_file` 结果，断言注入文本 < 2k 且含首尾行。

### S4. `Step` 只抽三处，不许覆盖全部路由
只做 `env/up`、`env/compile`、`test/run`（+`dev/run`）。这三处重复的正是
Journal.BEGIN/END、`Progress`、`Jobs` 登记、`Preflight` 四件套（见 `Api.java:574-616` 那一整段）。
验收一条单测：`Step` 超时 → `runs/<runId>.jsonl` 里有 `END(timeout)`，且 `Progress.fail` 收到的是人话。
理由：§1.5 那句"**所有**跑一下可能要等的事都走它"是 6 小时里最贵的一句话，实际收益集中在这三处。

### S5. SSE 做成"可开关"，前端默认仍走轮询
加 `GET /api/node/{k}/stream`，但 `store.js` 用一个常量（或 `/api/state` 里一个 flag）决定走 SSE 还是 `startStream`。
理由：`stopStream` 的终态判定挂在 `pullEvents` 的 `running===false` 上（`store.js:232-234`），
SSE 下这个信号必须由 `done` 事件给出，而 `runAction`（第 238–249 行）又要"起活后才开始收流"。
这条链路重写一遍 + 前端 105 条重测，是 C3.1 的真实成本。做成开关，砸了就是一行回退。

---

## 3. 建议砍（明确点名）

### K1. 砍 `.specflow/flow.yaml` + `FlowSpec` 校验器（§1.1、PLAN C2.1）
**会失去**：一份"可声明、可版本化"的流程定义。
**为什么砍**：它会是这个流程的**第四份真相**——`Node` 枚举（顺序）、`NodeSpec`、前端
`lib/flow.js` 的 `NODES`（第 4–10 行）、五个 Vue 里硬编码的按钮表（`CasesNode.vue:108`、
`DevNode.vue:59` 都是 `{ id:'freeze', label:'冻结', tip, on }`），再加 yaml 就是第四份。
§1.1 规矩 2 只能校验 yaml ↔ `NodeRegistry`，**校不到 yaml ↔ Vue**，所以它防不住它自己引用的那次事故
（"页面根本没有冻结按钮"）。而 `STATE.md` 硬约束 2 只允许前端改 SSE → 这个对账永远补不上。
真正能防那次事故的是 M3 里"后端把 `Action` 列给前端渲染"，但那违反硬约束 2。
→ 结论：**收益是文档上的，成本是 1.5h + 一个永远对不上的真相源。**
（顺带：解析器本身不贵，`jackson-dataformat-yaml` 已在 pom，`EnvConfigLoader` 的"带行号一次报全"可以照抄。）

### K2. 砍 `InterruptGate` + `runs/<runId>.jsonl` 的 `WAITING` + `POST /api/flow/resume`（§4.6、PLAN C2.8）
**会失去**：进程被杀之后从 checkpoint 续跑一轮。
**为什么砍**：它声称要解决"审批阻塞线程 187 秒"，但那 187 秒的真因写在 `STATE.md` 缺陷 4 里——
**没有 sudo 的容器里还问用户**（判据问题），不是阻塞问题。现在的 `Approvals` 已经是
"待答表 + `CompletableFuture` + `WAIT_SECONDS=600` 超时按拒"（`Approvals.java:29,59`），
线程确实占着，但一次只占一条，且超时自愈。做 §4.6 要同时动 `Approvals`/`TestAgent`/`test/BashTool`/`ApprovalsTest`（5 条），
换来的只是"杀进程能续跑"。6 小时里这是最差的性价比。

### K3. 砍/降级 `GET /api/node/{k}/stream`（§1.7、PLAN C3.1）
**会失去**：chip 从 ~800ms 级粒度变成实时。
**为什么**：见 S5。若时间还有富余就按 S5 做成开关；若没有，交付文档里明写
"轮询 800ms，按 `?from=` 增量，不重画"——用户看不出区别，因为事件流本身已经是增量的。

### K4. 砍 `docs/v3/DECISIONS.md` 的"≥3 subagent 投票"（PLAN B2），并把 A 阶段的 13 份 research 收成 1 份
**会失去**：答辩材料与"争议点有投票记录"。
**为什么**：`ARCH-V3.md` 开头自己写着"研究子代理这一轮没落盘"、"对比部分以我的知识为主【常识】"。
PLAN 给 A 阶段 3h、B 0.5h，那是**已经花掉的时间**，不是还剩的时间。再跑一轮投票 = 0 代码。
→ 保留 `ARCH-V3.md` §5 那张表当结论；`DECISIONS.md` 只留一个 10 行 stub（否则 `STATE.md` 文件地图在撒谎），
内容直接引用本文件。

### K5. 砍 `NodeSpec` 的 `factsShown`（§1.2）与 C2.2 里的 `Artifact`
**会失去**：无。
**为什么**：`FlowService.visible()`（第 336–348 行）现在用 `!key.startsWith("_")` 就把 `_cursor` 过滤掉了，
再加一个白名单等于同一件事两处口径——正是 `Facts.java` 类注释在骂的那种错。

---

## 4. 风险清单（按"会不会让 149 条变红"排序）

| # | 风险 | 会红哪些测试 | 规避手段 |
|---|---|---|---|
| **R1** | 动 `Facts` record（加/删/重排组件，或改成 Map） | **编译期全红**：`GatesTest` 14 条 + `FlowService.facts()` 唯一现场；比运行时红更难查 | 不动 record。要新事实就**加在最后并保留旧构造器**；`containerBuild` 已在（`Facts.java:70`），别再加 |
| **R2** | `Loop` → `AgentRuntime` 重写 | `LoopTest` 13 + `ToolsTest` 12 + `ToolRegistryTest` 9 = 34 条，且 `LoopTest` 钉的是时序不变量 | 只做包装（M4）；`Loop.java` 与 `LoopTest.java` 分提交、同一提交内不改 |
| **R3** | 工具装饰器 → `ToolHook` 链（§4.5） | `CaseBudgetTest` 5 + `ApprovalsTest` 5 + `ToolsTest` 12 | `ToolRegistry.use(hook)` 内部按 `wrap()` 实现；wrapper 必须透传 `name()/params()/description()`——`CaseBudget.Counted`（第 111–152 行）就是现成模板 |
| **R4** | `Gate` 接口化 + 文案常量抽取（§1.3 / §6 第一行） | `GatesTest` 14（连 409 正文都比对，`Gates.java` 里那几句人话是断言目标） | `Gates.blocked` 两个重载**一个字不改**，`Gate` 只做它内部的表；字符串可以移到常量，但**文案内容不许改一个字** |
| **R5** | 不会红的那些：`Api.java` / `FlowService` / `Runs` / `Store` / SSE 全是零覆盖 | 149 条**一条都不会红**，而端到端可能已经废了 | M1 的 `ApiSmokeTest` 3 条 + `D3` 七页冒烟 + 每改完一处就跑 `npx vitest run`（105 条是 SSE/前端改动的唯一护栏） |

补充两条同级别但排不进前五的：`EnvironmentTest` 9 / `ComposeTest` 6 / `EnvConfigLoaderTest` 8 会被
"`env.Compiler` 与 `Environment.inApp` 统一成 `exec.Step`"（§6 第 4 行）打到——`env/up` 与编译的超时语义
（`STATE.md` 缺陷 5：写死 300 秒 vs 配置 600）正在这些测试覆盖范围内，改超时口径必红。

---

## 5. 一句话结论

**部分做。** `ARCH-V3.md` 的方向是对的（`Facts`+`Gate` 纯函数、每用例预算、容器能力校验、把三件套收进 `Step`），
但它把"已经做完的事"写成了"要做的事"，把"6 小时里做不完的事"（`flow.yaml`、可恢复中断、SSE 硬切）写成了主线——
照 §6 那张表从上往下做，6 小时后你会得到一份更漂亮的架构和一条跑不起来的端到端。

**推荐实施顺序（前 3 步）：**

1. **锁基线（0.5h）**：`mvn -o test` 绿 → `git commit` + `git tag v3-c1` → 补 `ApiSmokeTest` 3 条；
   顺手把 `PLAN.md` 的 C1.1/C1.3/C1.5/C1.9 打上 ☑ 并注明"已在 v3-c1 内"。
2. **闭环优先（1.5h）**：按 `STATE.md` 那 8 条缺陷逐条收尾（其中 4 条已在脏树里，只需验证），
   然后用 `E:\library-management\library-management-backend` 真跑一次端到端 + `run2.mjs`，
   确保"每用例预算/跳过/重试"在真模型下真的生效。**这一步不做完，后面所有重构都是盲改。**
3. **只上三处 `Step` + 行数缓存 + 卡死判据 1（1.5h）**：`env/up`、`env/compile`、`test/run` 三处收编
   `Journal/Progress/Jobs/Preflight`；`EventLog` 缓存行数；`Loop` 加"连续 3 次相同命令"打断。
   每步一提交、每次提交后 `mvn -o test` + `npx vitest run`。
4. 余下 2.5h 全给交付：`docs/BOOK.html`（从 `ARCH-V3.md` 的图与 `FlowService`/`Gates`/`CaseBudget` 三份 javadoc 里
   直接搬运，那几段注释本身就是讲解稿）+ `DELIVERY.md` 如实写"没做的"（`flow.yaml`/可恢复中断/SSE）。
5. **任何时刻只剩 1 小时**：停手，回到 `v3-c1`，只做文档。
