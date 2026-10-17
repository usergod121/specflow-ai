# specflow v3 · 架构

> 写给下一个接手的人（也包括压缩了上下文之后的我自己）。
> 事实来源分两类，本文里逐处标注：**【实测】** = 这一版代码真跑出来的；**【常识】** = 我对那些
> 项目的了解（未在本机逐行核对源码）。研究子代理这一轮没落盘（会话被打断），所以本文的对比部分
> 以我的知识为主，**取舍理由**比"引用别人的代码"更重要——理由是可验证的。

## 0. 核心认知：这个系统到底是什么

一句话：**它是"一条有闸的流水线"，Agent 只是流水线上那几站的手**。

- **不是**"一个能自己规划的大 Agent"：五站的顺序、每站的产物、站与站之间的闸，都是**写死的**。
  这是这一版最值钱的性质——它让"现在卡在哪一站、为什么卡"永远有唯一答案。
- **是**"人 + Agent 一起填的一张工单"：人挑项目、写需求、按接受；Agent 在每一格干活；
  闸保证"上一格没做完，下一格连点都点不动"。
- **因此**所有顶层抽象都要服务同一件事：**让"这一格做完了没有"可判、可续、可解释**。

【实测】这条认知不是设计洁癖，是被三次事故逼出来的：
① 编译过了但容器里没 Maven（闸问错了对象）；② 60 轮用尽 12 条用例 0 结论（预算的粒度错了）；
③ 有格页面根本没有"冻结"按钮（策略是代码，没有与页面共享同一份声明）。

## 1. 顶层抽象（7 个，加一个"数据"）

上一版的问题是：**判据在服务里、动作在路由里、约束在页面里**——同一件事有三份真相。
v3 把"什么是流程"收成一份**数据**（`flow.yaml`），把"怎么执行"收成**一条链**（中间件），
其余全是这条链上的普通对象。

```
        ┌──────────────────────────── 数据（唯一的「流程真相」）────────────────────────────┐
        │  dsl/FlowSpec  ←  .specflow/flow.yaml（可校验、可版本化、可覆盖；缺失时用内置默认）   │
        └───────────────────────────────────┬──────────────────────────────────────────────┘
                                            │ 解析成
                        ┌───────────────────▼────────────────────┐
                        │  flow/NodeSpec   每站：id 标题 顺序 闸 动作 产物键 │
                        └───────────────────┬────────────────────┘
                                            │ 注册进
   ┌──────────────┐   ┌──────────────┐   ┌──▼───────────┐   ┌──────────────┐   ┌────────────┐
   │  flow/Gate   │   │ flow/Action  │   │ NodeRegistry │   │  flow/State  │   │ flow/Store │
   │ 判据（纯函数）│   │ 人点的动作    │   │ 唯一的登记处  │   │ 五格状态+产物 │   │ 原子落盘    │
   └──────────────┘   └──────┬───────┘   └──────────────┘   └──────┬───────┘   └────────────┘
                             │ 执行体是一个 Step
                        ┌────▼─────────────────────────────────────┐
                        │  exec/Step     一次可执行动作             │
                        │  （超时 / 重试 / 退避 / 取消 / 进度 / 落档）│
                        └────┬─────────────────────────────────────┘
                             │ 需要"手"时装配
                        ┌────▼─────────────────────────────────────┐
                        │  agent/AgentRuntime  ReAct 循环           │
                        │  预算 / 上下文压缩 / 卡死检测 / 可恢复中断  │
                        │  工具链：ToolRegistry + ToolHook 中间件     │
                        └────┬─────────────────────────────────────┘
                             │ 工具的落地
              ┌──────────────┴───────────────┬────────────────────┐
        ┌─────▼─────┐              ┌─────────▼────────┐   ┌───────▼───────┐
        │ 文件工具   │              │ bash（容器里）    │   │ 验收动作       │
        │ Roots 守边界│              │ Shell→Sandbox   │   │ case_update   │
        └───────────┘              └─────────────────┘   └───────┬───────┘
                                                                 │ 写
                                                          ┌──────▼───────┐
                                                          │ test/Verdict │
                                                          │ 裁决：PASS/…  │
                                                          └──────────────┘
```

### 1.1 `dsl/FlowSpec` —— 流程是一份可校验的数据

```yaml
# .specflow/flow.yaml（可选：不写就用内置默认，语义完全一样）
version: 1
nodes:
  - id: env
    title: 环境搭建
    gate: [compileConfigured, envReadyOrNotNeeded, containerBuild]   # 闸引用的"事实名"
    actions:
      - { id: agent,    label: 探测环境, primary: true }
      - { id: up,       label: 起环境 }
      - { id: compile,  label: 编译 }
      - { id: freeze,   label: 冻结 }
  - id: probe
    title: 上下文探针
    gate: [probeFiles, probePrompt]
    ...
```

三条硬规矩（都是被事故逼出来的）：
1. **闸引用的只能是 `Facts` 里登记过的名字**：写错一个字母 → 校验器当场报，而不是"闸永远为假"。
2. **动作 id 必须同时存在于 `NodeRegistry`**：声明里有、代码里没有 → 校验器报。
   （这正是"页面没冻结按钮"那类事故的机器化防线：声明与实现必须对齐。）
3. **不认识的字段拒**：与 `env.yaml` 同一套口径（写错一个字母静默失效最难查）。

### 1.2 `flow/NodeSpec` / `NodeRegistry` —— 节点有统一形状

```java
public record NodeSpec(Node node, String title, String subtitle,
                       Gate gate, List<Action> actions, Set<String> factsShown) {}

public final class NodeRegistry {           // 唯一登记处
    public static NodeRegistry builtin();   // 内置五站
    public NodeSpec of(Node node);
    public List<NodeSpec> all();
    public void validate(FlowSpec spec);    // 声明 ↔ 实现 双向对账
}
```
**`Node` 枚举（ENV..TEST）保留**：它是"顺序"本身，不因为抽象层改变。

### 1.3 `flow/Gate` —— 判据是纯函数，但不再是 if-else

```java
@FunctionalInterface
public interface Gate {
    /** 空串 = 能做。返回的那句话直接给用户看（409 正文原样）。 */
    String blocked(Facts facts);
}
```
`Gates` 从"五个 switch 分支"变成"五个 `Gate` 实现 + 一张表"，
表由 `NodeRegistry` 提供；`Gates.blocked(node, facts)` 保留为兼容入口（内部查表）。

### 1.4 `flow/Action` —— 人点的东西有名字、有条件、有落点

```java
public record Action(String id, String label, String tip, boolean primary,
                     boolean danger, Predicate<Facts> enabled, Step body) {}
```
路由不再写 `@PostMapping("/api/node/env/up")` 一长串：统一成
`POST /api/flow/action {node, action}` → 查表 → `requireReachable` → `body.run(ctx)`。
（旧路径保留为薄别名，前端不用改。）

### 1.5 `exec/Step` —— 一次可执行动作的唯一形状

```java
public interface Step {
    String name();
    exec/Ctx ctx();                       // 项目、流程、事件流、进度
    exec/Result run(exec/Ctx ctx) throws Exception;
    default exec/Limits limits() { return exec/Limits.defaults(); }  // 超时 / 重试 / 退避
}
public record Limits(long timeoutSeconds, int attempts, long backoffMillis, boolean idempotent) {}
```
**所有**"跑一下、可能要等"的事都走它：起环境、编译、跑 Agent、跑一条用例的命令。
它负责把 `Journal`（BEGIN/END）、`Progress`、`Jobs`（可叫停）三件事一次办好——
上一版这三件事在每个调用点各写一遍。

### 1.6 `agent/AgentRuntime` —— 循环 + 三种自保

```java
public final class AgentRuntime {
    Budget budget;      // 轮次 / 工具调用 / 每用例 / 墙钟
    Compact compact;    // 上下文压缩：超阈值 → 摘要 + 保留锚点
    StuckCheck stuck;   // 卡死：连续 N 步同一命令 / 无输出增长 / 只读不写
    InterruptGate gate; // 可恢复中断（审批）：挂起这一轮，重启后接着跑
}
```
【实测】三种自保各对应一次事故：预算（60 轮 0 结论）、卡死（7 轮 `sleep` 空转）、
中断（审批阻塞线程 187 秒）。

### 1.7 `web/Stream` —— SSE 而不是轮询

```
GET /api/node/{key}/stream?from=N   → text/event-stream
event: tool   data: {"callId":"c7","tool":"bash","status":"running",...}
event: line   data: {"text":"..."}
event: done   data: {"id":123,"status":"ok","millis":1900}
```
事件源仍然是那一份 `events.jsonl`（`id ≡ 行号` 不变）：SSE 只是"多了一个订阅者"，
游标语义与 `?from=` 完全一致，所以断线重连 = 带上最后收到的 id 再订阅。

## 2. 一次运行的生命周期（执行图）

```
人点「跑测试」
  │
  ├─ Gate 判闸（纯函数）──── 不过 → 409 + 一句话，事件流留一条 type=gate
  │
  ├─ Step 开工：Journal.BEGIN（runs/<runId>.jsonl）+ Jobs 登记（可叫停）
  │     │
  │     ├─ Preflight（便宜的一问）：容器里有没有构建工具？没有 → 立刻失败，指名 ①
  │     │
  │     ├─ 装配 AgentRuntime
  │     │     ├─ 工具链 = ToolRegistry + [预算 hook → 审批 hook → 冻结 hook]
  │     │     ├─ 预算：每用例 12 轮（超了标 SKIPPED 并推进下一条）
  │     │     └─ compact：对话超阈值就摘成"锚点 + 结论"，锚点里放"我改过哪些文件"
  │     │
  │     ├─ 每一条用例：执行 → 裁决（Verdict）→ 写回 cases.json
  │     │     └─ 全程每一条工具事件 → events.jsonl → SSE → 界面的 chip
  │     │
  │     └─ 收场：Journal.END(status) + Progress.done/fail + 事件流留档
  │
  └─ 人按「接受」→ 闭环（或按「再优化一轮」→ 回喂给 ④）
```

## 3. 五个节点在新抽象里的位置（语义一个字没改）

| 站 | 闸（`Gate`） | 主要动作（`Action`） | 产物（facts / 文件） |
|---|---|---|---|
| ① 环境 | 编译命令非空 ＋（起过环境 或 声明不需要）＋ **容器里真能构建** | 探测环境 / 起环境 / 编译 / 跳过 / 冻结 | `env.yaml`、`compose.yaml`、`envReady`、`containerBuild` |
| ② 探针 | 有可读文件 ＋ prompt 非空 | 跑探针 / 存需求 / 保存产物 / 冻结 | `probe.json`、`probeFiles`、`probePrompt` |
| ③ 用例 | 至少一条用例 | 让 AI 写 / 人改 / 保存 / 冻结 | `cases.json`、`caseCount` |
| ④ 开发 | 最近一次编译通过 | 跑开发 / 回滚 / 冻结 | 快照、`compilePassed`、`rounds` |
| ⑤ 测试 | 无 PENDING ＋ 已提交 | 跑测试 / 重试这一条 / 提交 / 接受 / 再优化 | `cases.json` 结论、`summary.md`、`submitted` |

**"冻结 = 这一步做完了"没变**，变的只是它现在由 `NodeSpec` 声明、由 `FlowService` 执行，
两边看的是同一份数据。

## 4. 关键设计（每条都对着一次实测事故）

### 4.1 闸只问一个对象：`Facts`
`Facts` 是纯数据，`Gate` 是纯函数。好处是闸能被单测钉死（149 条测试里有 14 条是闸）。
v3 只加了一个事实：`containerBuild`（**容器里**真构建过）——
因为"宿主机能编译"和"下游能用"不是一件事。

### 4.2 预算分三层，粒度一次比一次细
| 层 | 上限 | 超了怎么办 | 对应事故 |
|---|---|---|---|
| 整轮 | `max(60, 用例数×12+20)` 轮 | 失败收场，但**如实报已写下几条结论** | 60 轮 0 结论 + 摘要谎报 0 |
| 每条用例 | 12 轮工具调用 | 那一条标 `SKIPPED`（带原因），注意力推进到下一条 | 一条用例吃掉整轮 |
| 单条命令 | 600 秒（可配） | 超时终止并说清"它可能还在容器里跑" | 写死 300 秒把构建掐死两次 |

### 4.3 上下文压缩（compact）：什么时候压、压掉什么
- **触发**：消息总字符数 > 阈值（默认 6 万）或轮次 > 20 → 压一次。
- **压什么**：把"中间过程"压成一张锚点表，**保留**：改过哪些文件、当前用例做到哪、
  已知失败原因；**丢掉**：完整的工具输出（只留首尾各 3 行）。
- **为什么必须留锚点**：压缩失忆是真实故障——模型会重复做同一件事、或者忘了自己刚把环境弄坏。

### 4.4 卡死检测（`StuckCheck`）
三条判据，命中任一就打断并回喂一句"你在原地打转"：
1. 连续 3 次完全相同的命令；
2. 连续 5 次工具调用只读不写（在验收阶段这是合法的，所以阈值放宽到 8）；
3. 单条命令的输出与上一次一致且退出码相同。

### 4.5 工具层是"中间件链"而不是"装饰器堆"
【实测】上一版用装饰器：`Frozen(WriteFileTool)`、`Budget.wrap(BashTool)`……
三处各包一层，读代码时要在脑子里剥四层。v3 改成：

```java
ToolRegistry.of(tools).use(new BudgetHook()).use(new ApprovalHook()).use(new FreezeHook());
```
`ToolHook` 的生命周期：`before(ctx, tool, args)` → `after(ctx, call, result)` → `onError(...)`；
`before` 返回"拒绝"就短路（这一次调用不执行，结果回给模型）。审批、预算、冻结三件事都变成钩子，
**新增策略不用改任何工具**。

### 4.6 可恢复中断（审批不再阻塞线程）
上一版：Agent 线程 `future.get(600s)` 死等用户点 y/n。
v3：`InterruptGate` 把"等待"表达成一个**运行状态**——`runs/<runId>.jsonl` 里写一条
`WAITING(approvalId)`，线程**让出**（这个 Step 以 `PAUSED` 收场）；用户回答后，
`POST /api/flow/resume` 从那条 checkpoint 继续。
代价：要多存一份"循环恢复到哪儿"的上下文；收益：不占线程、重启也不丢、超时=默认拒。

### 4.7 容器能力校验（把"能用"变成可执行）
`env.Capabilities`：起完环境 → 从编译命令认出构建工具 → `command -v` → 真跑一次构建。
不通过 → `containerBuild=false` → ① 的闸拒绝并指名"改 image 或 init"。
**并且** ⑤ 开工前再做一次 `Preflight`（只问工具在不在），缺了立刻失败——
一层是"自证"，一层是"复检"，因为用户可能换过镜像，也可能跳过 ①。

### 4.8 依赖缓存跨轮、数据每轮重置
`compose` 里给 app 挂一个 `external` 命名卷到 `/specflow-home`：
- **外部卷不随 `down -v` 删除** → Maven 的 `~/.m2`、npm 的缓存跨轮复用；
- 项目树每轮仍是新副本 → 数据、产物、装过的东西都不留。
【实测】不这么做的话，每次 `up` 后构建都要重下依赖（17 分钟 / 7 轮 `sleep`）。

## 5. 借鉴与取舍（对比表）

| 来源 | 抄了什么 | 明确**不**抄什么 | 理由 |
|---|---|---|---|
| **Dify** | 节点有"基类 + 注册表"、流程可 DSL 化、运行状态按节点落库、SSE 推进度 | 它的"节点类型爆炸"（几十种节点、插件体系）、可视化编排器 | 我们只有 5 站，节点类型多了就是负资产 |
| **LangGraph** | `interrupt` 是**可恢复中断**而非阻塞；checkpointer 按 thread 存；流式有"事件模式" | Pregel 那套 channel/reducer 抽象 | 它解决的是"任意图 + 并行超步"，我们是"一条线" |
| **Temporal** | Activity 级重试策略（attempts/backoff/非重试错误）、**心跳超时**当卡死判据、signal 进运行中的流程 | 完整事件溯源引擎、确定性重放约束 | 那套是给分布式多机的；我们单机 + 文件，replay 用 JSONL 就够 |
| **Airflow / Prefect / Dagster** | 任务级 retries/backoff/timeout、`skip` 与 `fail` 的语义区分、`allow_failure` | 调度器/回填/DAG 依赖图 | 我们不需要"到点跑"和"跨天回填" |
| **Argo Workflows** | YAML 定义模板、`retryStrategy{limit,backoff}`、`exit handler`、artifact 传参 | CRD/集群那套 | 我们要的是"声明可校验"，不是"K8s 编排" |
| **SWE-agent / Aider** | ACI 原则：工具少而专、错误写给模型看、行号视图省 token、**用 YAML 定义工具** | repo map 的完整实现（我们项目小，一步 `read_file` 够） | 项目规模差一个量级 |
| **Claude Code 生态** | 权限三档（allow/ask/deny）、PreToolUse/PostToolUse 钩子、**自动 compact**、子代理隔离上下文 | 它的前端/生态集成 | — |
| **OpenHands** | `Runtime` 抽象（本地/容器/远端同一接口）、事件流订阅与重放、stuck detector | 多 agent 编排、浏览器环境 | 我们没有远端运行时 |
| **OpenAI Agents SDK** | `Run` 结果对象形状、工具生命周期钩子、`interruptions` 的恢复语义 | handoff（多专家转交） | 我们的"转交"是人点按钮回上一站 |
| **CrewAI / AutoGen** | Task 的 `expected_output` + `guardrail` 回调（=我们的 Verdict）、终止条件可组合 | 多智能体群聊/层级编排 | 五个节点已经是编排；agent 之间自由对话只会让"卡在哪"失去答案 |
| **12-factor agents** | 自有 prompt/上下文/控制流、unify state、launch/pause/resume、**compact errors into context** | 不用它的库，只当原则清单 | — |
| **Skyvern / browser-use** | "确定性块 + AI 块"混合：能写死的别交给模型；失败留证据 | 浏览器观测裁剪那套 | 我们不做浏览器 |

**一句话取舍**：抄"约束的形状"（接口、声明、状态机），**不抄**"能力的规模"（节点类型、插件、
分布式运行时）。5 个节点是我们的优势不是缺陷。

## 6. 落地映射（现有类 → v3 类，实施按这张表走）

| 现在 | 之后 | 动作 |
|---|---|---|
| `flow.Gates`（5 个 switch 分支） | `flow.Gate` ×5 + `NodeRegistry` 查表 | 拆开，保留 `Gates` 兼容入口 |
| `web.Api`（每条路由一个方法 + 一堆 if） | `flow.Action` + `POST /api/flow/action` | 旧路由保留为别名 |
| `test.Frozen` / `test.CaseBudget` / 审批判断 | `agent.ToolHook`（FreezeHook / BudgetHook / ApprovalHook） | 装饰器 → 钩子链 |
| `env.Compiler`（宿主）/ `Environment.inApp`（容器） | `exec.Step` 的两种实现 | 统一超时/重试/留档 |
| `flow.Journal`（BEGIN/END） | `exec.Step` 内建 + `flow.Checkpoint` | 调用点不再各写一遍 |
| 前端 `?from=` 轮询 | `GET /api/node/{k}/stream`（SSE） | 前端改一个订阅函数 |
| `Loop` | `AgentRuntime`（预算/压缩/卡死/中断） | 保留 `Loop` 作为它的内核 |
| 流程只存在于代码 | `.specflow/flow.yaml`（可选、可校验） | 内置默认与原语义完全一致 |

## 7. 不做（明确写出来，免得后来人以为是漏了）
- **不换存储**（不上 SQLite）：事件流 `id ≡ 行号` + 人能打开看，是本项目最值钱的性质；
  只做定点优化（追加时缓存行数、以盘上为准校正）。
- **不把引擎塞进容器**：引擎要调 docker 造沙箱，给了 `docker.sock` 就等于给了宿主 root。
- **不做多智能体群聊**：五个节点已经是编排；自由对话会让"卡在哪一站"失去唯一答案。
- **不改五站的语义与顺序**（用户明确要求）。
