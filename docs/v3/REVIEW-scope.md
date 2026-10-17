# v3 剩余 6 小时：该做什么，砍什么

> 立场：**宁可交付一个真能跑、文档说清楚的东西，也不要一堆半成品抽象。**
> 6 小时 − 1.5 小时交付窗口（测试/端到端/HTML/bat 复验）= **4.5 小时可用**。
> 本文件里的每一行都按这 4.5 小时判生死。

## 0. 读数说明（先看这三句，否则后面的行号会骗你）

1. 我看的是**工作区当前状态**（`git status` 有 20 个改动），不是 `v2-working` tag。**期间有别的
   subagent 在并发改 `web/Api.java`**：我第一次读它 863 行、第二次 950 行、第三次 995 行，而且
   `GET /api/node/{key}/stream` 的 `SseEmitter` 是在我读的过程中长出来的。**凡是我点了行号的结论，
   都当作"我读到那一刻的快照"**，动手前用 `Select-String` 复核一次。
2. 我**没有跑** `mvn -o test`（20 分钟盒子里跑一次 Maven 不划算，而且会跟并发改代码的人抢锁）。
   所以下面所有"看到了什么算过"我都写成**可执行的命令 + 期望输出**，不是"我看过了，是绿的"。
3. 我把 C1（缺陷修复）当成**已完成但未冻结**：`env/Capabilities.java`、`test/CaseBudget.java`、
   `test/Preflight.java` 三个新类都在工作区里，`test/CommandGuardTest.java`、
   `test/CaseBudgetTest.java` 也在。**这一堆还没提交**。这是下面第一条"必须做"的全部理由。

---

## 1. 必须做（最多 4 条，砍掉任何一条这一轮就不算升级）

### M1 · 把界面上那个**摆样子的超时旋钮**接上真超时（一处来源，四处使用）

**解决哪个实测事故**：`STATE.md` 缺陷 5 —— "`bash` 超时写死 300 秒把长构建掐死两次"，以及
`docs/ARCHITECTURE.md` §7 那条"界面上设了 30 分钟，编译照样在写死的 5 分钟被掐死，而报错一个字都
没提这两个数怎么来的"。

**为什么它排第一**：因为**文档已经在撒谎了**，而且撒得很具体：

- `env/Compiler.java:79-84` 的 Javadoc 明写"与「起环境」共用一个旋钮（`flow.json` 里 ① 的
  `pullTimeoutMinutes`）"，**然后那个方法体是 `return 30 * 60;`**。旋钮根本没读。
- `web/Api.java:906 pullTimeout()` 读出了 `pullTimeoutMinutes`，但**只用于 `GET/POST
  /api/node/env/settings` 的回显**（`Api.java:349-360`）。整个代码库里再没有第二个读者。
  也就是说：**用户改这个数字，什么都不会发生。**
- 同时散着 6 个互不相干的字面量：`Environment.UP_TIMEOUT_SECONDS = 30 * 60`（`:52`）、
  `Compiler` 的 `30 * 60`（`:84`）、`Api.java:273` 给 `Capabilities.verify(…, 30 * 60, …)`、
  `TestAgent.java:134` 的 `new BashTool(shell, sandboxRoot, approval, progress, 600)`、
  `TestAgent.java:139` 的 `new LoopLimits(rounds, 400, 600, 1)`、`TestSandbox.java:86,90` 两个 `600`。

一个"架构升级"里留着"文档说的和代码做的不是一回事"，比留着一个还没重构的 `Api.java` 难看得多。

**做什么**（半页到一页代码）：`exec/Limits.java` 一个 `record Limits(long timeoutSeconds, int attempts)`
+ 一个 `Limits.of(Project)`（唯一读数点：`pullTimeoutMinutes`，缺省 30 分钟）；
`Compiler` / `Environment.up` / `Capabilities.verify` / `TestAgent` 的 `BashTool` 与 `LoopLimits`
五处全改成从它取。**不做**重试/退避矩阵（那部分见"必须砍"）。

**判据（能验证）**
```powershell
# 1) 新单测：旋钮真的生效（把 1 分钟写进 facts，超时必须是 60 秒而不是 1800）
cd E:\specflow-v2; mvn -o -q test -Dtest=LimitsTest
#    期望：Tests run: 1, Failures: 0, Errors: 0
# 2) 字面量真的没了（下面每条都必须 0 命中）
Select-String -Path src\main\java\com\specflow\env\Compiler.java,
                    src\main\java\com\specflow\test\TestAgent.java,
                    src\main\java\com\specflow\test\TestSandbox.java `
               -Pattern '30 \* 60|\b600\b' | Measure-Object   # 期望 Count = 0
# 3) 回退判据：全绿
mvn -o -q test    # 期望 Tests run: 149, Failures: 0, Errors: 0
```

---

### M2 · SSE **收尾**（后端已在长，前端还差最后一步）

**解决哪个实测事故**：`STATE.md` 缺陷 8 "前端是**轮询**"；现场表现是"跑测试时界面半天不动，
用户以为卡死，去点停止/重复点开始"。这是用户点名唯一允许的前端改动，**也是这一轮唯一会在
浏览器里被看见的升级**——不做，用户打开页面会觉得"什么都没变"。

**诚实说明现状**：后端已经落了一半——`web/Api.java:141` 有
`@GetMapping(value="/api/node/{key}/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)`，
`:146-195` 有完整的 `streamTo()`（`event: events` / `ping` 心跳 / `done` 收场 + 客户端断开当正常）。
但前端 `src/main/frontend/src/store.js:175-204` 仍然是 `setInterval(tick, POLL_MS)`，
`api.js:58` 仍然只在拼 `?from=`。**所以 M2 的活是前端那一处 + 真浏览器验一遍。**

**做什么**：`api.js` 加一个 `streamUrl(key, from)`；`store.js:startStream` 里
`setInterval` → `new EventSource(streamUrl(node, store.cursor))`，`onerror` 时用
`store.cursor` 重连（游标语义与 `?from=` 完全一致，`events.jsonl` 的 `id ≡ 行号` 不变），
`done` 时 `close()` 再一次 `pullEvents()` 补齐。**页面结构、组件、CSS 一个字不动。**

**判据（能验证）**
```powershell
# 1) 后端推得动：另开一个窗口跑 start.bat，然后
curl.exe -N "http://127.0.0.1:8760/api/node/test/stream?from=1"
#    期望：不退出，持续出现 "event:events" + "data:{...}"；空闲时出现 "event:ping"
# 2) 断线续订阅：按 Ctrl-C 掐掉，记住最后一条 id N，再
curl.exe -N "http://127.0.0.1:8760/api/node/test/stream?from=<N+1>"
#    期望：第一条事件的 id 正好是 N+1，不重不漏
# 3) 前端：单测 + 冒烟（smoke.mjs 会真开浏览器，七路由零 console error）
cd E:\specflow-v2\src\main\frontend; npx vitest run; npx vite build; node tests\smoke.mjs
#    期望：105 passed / build 成功 / 7 页零报错
```

---

### M3 · `StuckCheck`（卡死检测）：三次同命令就打断

**解决哪个实测事故**：`ARCH-V3.md:153` 明写的那条——"卡死（**7 轮 `sleep` 空转 17.5 分钟**）"。
这是三件自保里**唯一还完全没有实现**的一件（预算有：`test/CaseBudget`；中断没有，见"必须砍"）。

**为什么值得 45 分钟**：它不改变任何节点语义，只加一个判据；而且它的判据能被单测钉死，
是"这一段能写进 HTML 书、且书里那句话是真的"的典型。17.5 分钟是真实用户时间。

**做什么**：`agent/StuckCheck.java` 一个约 60 行的类：最近 N 次工具调用的
`(工具名, 参数)` 指纹进一个 `Deque`；连续 3 次完全相同 → 返回一句"你在原地打转"的人话。
在 `agent/Loop.java` 的工具调用处分流：命中则不执行该次调用，把这句话当 `ToolResult` 回给模型，
并 `progress.warn(…)`。**阈值三条判据只做第一条**（同命令）；"只读不写"和"输出不增长"
这两条需要看输出内容，会把 `Loop` 的接口撑宽——留给 v4。**在 Javadoc 里写明只做了 1/3。**

**判据（能验证）**
```powershell
cd E:\specflow-v2; mvn -o -q test -Dtest=StuckCheckTest
#    期望：3 条通过——
#    a) 连续 3 次 `bash{"command":"sleep 60"}` → 第 3 次被拦，返回文本含"原地打转"
#    b) 3 次不同命令 → 一次都不拦
#    c) 同样的命令被拦之后换一条 → 计数清零，不误伤
# 且全绿：mvn -o -q test → Tests run: 152, Failures: 0（149 + 3）
```

---

### M4 · `flow/Gate` ×5 + 一张表（**只读重构，零行为变化**）

**解决哪个实测事故**：`ARCH-V3.md:20` 的第 ③ 条——"有格页面根本没有『冻结』按钮（策略是代码，
没有与页面共享同一份声明）"。M4 是这条的**最小兑现**：闸从"五个 switch 分支"变成"五个 `Gate`
实现 + 一张 `Map<Node,Gate>`"，于是"这一格要什么"第一次成为一个**能列出来的东西**，
`NodeSpec.factsShown` 也就有了唯一的取值来源。

**为什么它是四条里最"架构"的一条，也是最安全的一条**：`flow/Gates.java` 一共 157 行、纯函数、
已经被 `GatesTest` 钉死。重构成"表 + 5 个实现"后，**149 条测试一条都不许改**——测试不改就是
行为不变的机器证明。这也是唯一一条"做坏了能立刻退回"的结构性改动。

**做什么**：新建 `flow/Gate.java`（就是 `ARCH-V3.md:110-113` 那个 `@FunctionalInterface`）；
`Gates` 里 5 个 `private static String env/probe/cases/dev/test(Facts)` 改成 5 个 `Gate` 常量，
`Gates.blocked(node, facts)` 改成查表（**签名与文案一个字不改**，`Gates.blocked(node, facts, states)`
那个顺序层不动）。**不新建 `NodeRegistry`，不做 `FlowSpec.validate`**（见第 3 节）。

**判据（能验证）**
```powershell
cd E:\specflow-v2; git diff --stat src\test\java\com\specflow\flow\GatesTest.java
#    期望：**输出为空**（测试文件一个字节都没改）——这就是"零行为变化"的证明
mvn -o -q test    # 期望 Tests run: 149, Failures: 0
Select-String -Path src\main\java\com\specflow\flow\Gates.java -Pattern 'switch \(node\)'
#    期望：0 命中（switch 没了，只剩查表）
```

---

## 2. 可以做（还有 1 小时余量才上；按性价比排序，**最多挑一条**）

> 前提：M1–M4 全绿 + 交付窗口的 1.5 小时没被侵占。**三条同时上就是 4.5 小时变 7 小时。**

| # | 做什么 | 值多少 | 判据 |
|---|---|---|---|
| O1 | `POST /api/flow/action {node, action}` 统一入口，**只映射 6 条无 body 的规则动作**（`env/up`、`env/compile`、`env/skip`、`probe/agent`、`dev/run`、`test/run`），旧路由留薄别名 | `Api.java` 现在 995 行 / 35 条路由（23 POST + 11 GET + 1 DELETE），这个入口是书里"动作有名字"那一节唯一的实物 | 旧 6 条路由的现有测试全绿；`Select-String -Path src\main\java\com\specflow\web\Api.java -Pattern '@PostMapping' \| Measure-Object` 从 **23 降到 18**（23 − 6 + 1 条新入口） |
| O2 | `ToolHook` 链：`agent/ToolRegistry.java` 加 `use(ToolHook)`，把 `test/Frozen` + `test/CaseBudget` 换成 `FreezeHook` / `BudgetHook` | 消掉"读代码要在脑子里剥四层装饰器"（`ARCH-V3.md:231`） | `agent/ToolHook.java` 存在；`test/Frozen.java` 删除；`mvn -o -q test` 仍 149 绿 |
| O3 | `.specflow/flow.yaml` **只读校验器**：解析 + 未知字段拒 + `gate:` 里的名字必须存在于 `Facts`，**不接执行**（不驱动任何行为） | 书里"流程可校验"那一节能讲真话而不只是规划 | 单测：`gate: [compileConfiguredd]` 被拒且报错文本含 `compileConfiguredd` 与行号 |
| O4 | `core/EventLog.lines()` 的 O(n) 定点修复：内存计数 + 以盘上为准校正 | `STATE.md` 缺陷 8 前半句"append 是 O(总行数)" | `EventLogTest`：1000 行时 `append` 不读全文件（用 mock 或对 `lines()` 调用计数） |

**我的建议：O1**。它是四条里唯一能让 `Api.java` 这个"最丑的地方"**真的变短**的，
而且退路最硬（旧路由留着，前端不用动）。

---

## 3. 必须砍（点名 `ARCH-V3.md` §6 的条目；**砍了之后文档必须这么写**）

总规矩：**不许假装做了。** 每一处砍掉的，都要在三份文档（`ARCH-V3.md` §6、`PLAN.md` 的 ⊘ 表、
`docs/DELIVERY.md` 的"没做什么"）里用同一句话写三遍。写"规划中"也算假装——读者分不清
"规划中"和"这个仓库里没有"。

| §6 原文条目 | 砍什么 | 文档里该怎么写（照抄这句，别改写） |
|---|---|---|
| `flow.Gates`（5 个 switch）→ `flow.Gate` ×5 + **`NodeRegistry` 查表** | **`NodeRegistry`、`NodeSpec`、`FlowSpec.validate` 双向对账、`Action` 记录、`.specflow/flow.yaml` 驱动执行**全砍。只留 M4 那半条（`Gate` 接口 + 表） | "闸已拆成 5 个 `Gate` 实现 + 一张 `Map<Node,Gate>`（`flow/Gates.java`）。**流程仍写在代码里**：没有 `NodeRegistry`、没有 `NodeSpec`、没有 `.specflow/flow.yaml`、没有声明↔实现的双向对账。§1.1 那一节是**设计，不是本仓库的现状**。" |
| `web.Api`（每条路由一个方法 + 一堆 if）→ `flow.Action` + `POST /api/flow/action` | **整条砍**（若做了 O1，则是"6/35 条"） | "`web/Api.java` 仍是 995 行、35 条 `@*Mapping` 一个方法一条路由。`flow.Action` 不存在。这是这一版**已知最丑的地方**，也是 v4 的第一刀。" |
| `Loop` → `AgentRuntime`（预算 / **压缩** / 卡死 / **中断**） | 砍 **`Compact` 上下文压缩（§4.3）** 与 **`InterruptGate` 可恢复中断 + `POST /api/flow/resume`（§4.6）**。保留"预算"（已由 `test/CaseBudget` 按用例实现）+"卡死"（M3） | "`AgentRuntime` 不存在。预算在 `test/CaseBudget`（按用例 12 轮，超了标 `SKIPPED` 并推进下一条），卡死在 `agent/StuckCheck`（只做了『连续 3 次同命令』这一条判据）。**上下文压缩没有做，审批仍是阻塞等待、没有 checkpoint/resume 断点续跑。** 理由：两者都要改 `Loop` 的对话表示并多存一份恢复上下文，是 2 小时级的改动；而 §4.3/§4.6 对应的两个事故在当前配置下先被挡住（`LoopLimits` 默认 12 轮 + 单命令超时统一到 600 秒以上）。" |
| `test.Frozen` / `test.CaseBudget` / 审批判断 → `agent.ToolHook` | 砍（除非做 O2） | "工具策略仍是**装饰器**不是钩子链：`test/Frozen.java` 包一层、`test/CaseBudget` 包一层。『新增策略不用改任何工具』这句话本仓库还没兑现。" |
| `flow.Journal`（BEGIN/END）→ `exec.Step` 内建 + **`flow.Checkpoint`** | 砍 **`Checkpoint`（= `PLAN.md` C2.8 崩溃恢复）** 与 `PLAN.md` C2.3 的重试/退避矩阵 | "`flow/Journal.java` 的 BEGIN/END 已经收在 `flow/Runs.java:start(...)` 一处（359 行），调用点没有各写一遍——这一条**已经成立**。但**崩溃恢复没有做**：进程被杀之后，那次运行不会自动接着跑，`runs/<runId>.jsonl` 里能看到它死在哪一步，用户需要重新点一次。`POST /api/flow/resume` 不存在。" |
| `env.Compiler`（宿主）/ `Environment.inApp`（容器）→ **`exec.Step` 的两种实现** | 砍"统一"这个说法；**保留"两条编译"的事实**并把 M1 的 `Limits` 挂上去 | "`env/Compiler.java` 在**宿主机**上跑 `cmd /c`（这是 ① 的闸和 ④ 每轮交差都要的事实），`env/Capabilities.java` 在**容器**里再跑一遍同一命令（这是『真能用』的自证）——**两条编译路线是设计，不是重复**，因为 §4.7 事故正是『宿主机有 Maven、容器里没有』。超时两者共用 `exec/Limits`。" |

### 顺带必须砍（`PLAN.md` C2 里没落地的编号，别留在账上当"待办"）

`C2.1`（工作流 DSL）、`C2.2`（`NodeSpec`/`Action`/`Artifact` 全量）、`C2.3` 的重试退避矩阵、
`C2.4`（ToolHook）、`C2.5`（压缩 + 暂停/恢复）、`C2.8`（崩溃恢复）→ **在 `PLAN.md` 里标 `⊘` 并写原因**，
不要留 `◻`。留 `◻` 的后果是下一个人以为"这些是做了一半的"，会去补，然后补出半成品。

---

## 4. 最小可行升级（MVS）：4.5 小时执行清单

> 每一块结束都必须能**当场跑一条命令看到产出**。块与块之间不并行——并行是 4.5 小时变 7 小时的
> 唯一原因。**块 1 之前先 `git add -A && git commit`**，把工作区那 20 个改动冻住（否则任何一块搞砸
> 都会连带丢掉 C1 的成果）。

| 时间 | 做什么 | 可验证的产出（跑什么 → 看到什么算过） |
|---|---|---|
| **0:00–0:10** | 冻结现场：提交工作区、给 C1 打勾 | `git status --short` 输出为空；`git log --oneline -1` 是 "wip: C1 缺陷修复" |
| **0:10–0:55**<br>45min | **M1 超时单旋钮**：`exec/Limits` + 5 个调用点 | `mvn -o -q test -Dtest=LimitsTest` 1 通过；`Select-String … -Pattern '30 \* 60\|\b600\b'` 命中 0；`mvn -o -q test` 149 绿 |
| **0:55–1:55**<br>60min | **M2 SSE 收尾**：`api.js` + `store.js:startStream` 换 `EventSource`，`onerror` 用 `store.cursor` 重连 | `curl.exe -N ".../api/node/test/stream?from=1"` 连续出 `event:events`；换 `from=N+1` 续订不重不漏；`npx vitest run` 105 绿 + `node tests\smoke.mjs` 7 页零报错 |
| **1:55–2:40**<br>45min | **M3 `agent/StuckCheck`**：只做"连续 3 次同命令"，挂进 `Loop` | `mvn -o -q test -Dtest=StuckCheckTest` 3 通过；全量 `Tests run: 152` |
| **2:40–3:25**<br>45min | **M4 `flow/Gate` ×5 + 表**：`switch` 拆成查表，文案与签名不变 | `git diff --stat src\test\…\GatesTest.java` **输出为空**；`mvn -o -q test` 149 绿；`Gates.java` 里 `switch (node)` 命中 0 |
| **3:25–3:55**<br>30min | **O1（可以做，唯一一条）**：`POST /api/flow/action` 只接 6 条无 body 动作，旧路由留别名 | 旧 6 条路由现有测试绿；`@PostMapping` 数量从 **23 降到 18**；**若 15 分钟时还没编译过 → 立刻 `git checkout` 回滚，本块记 ⊘** |
| **3:55–4:30**<br>35min | **口径收口**：`ARCH-V3.md` §6 按第 3 节那张表改、`PLAN.md` 打 `⊘`、`DELIVERY.md` 加"没做什么"；`STATE.md` 的「缺陷 1-8」逐条标已修/未修 | 三份文档里"没做什么"那几行**字面一致**（`Select-String -Pattern '没有做|不存在|现状'` 能对上）；`git commit`；`git status` 干净 |

**留给交付的 1.5 小时（不许侵占）**：`mvn -o clean package` / `npx vitest run && npx vite build` /
`run2.mjs`（真模型 + 真 Docker，用 `E:\library-management\library-management-backend` 当样本）/
`start.bat` → `stop.bat` 复验 / `docs/BOOK.html` / `DELIVERY.md`。

**唯一允许超时的例外**：M2。因为它是用户唯一能在浏览器里看见的升级，超时 15 分钟就从 O1 那 30 分钟里挪。
反过来，**M4 超时就直接砍**（它零行为变化，砍了只影响书里一节）。

---

## 5. 如果只剩 2 小时

**一句话：只做 M1 + M2，把 C2 全部写成 `⊘` —— 让"这一轮到底升了什么"在文档里有一句真话。**

1. **M1 超时单旋钮**（45 分钟）：`exec/Limits` + `Compiler`/`Environment`/`TestAgent`/`TestSandbox`
   五处字面量收口；判据 `Select-String '30 \* 60|\b600\b'` 命中 0 + `LimitsTest` 绿。
2. **M2 SSE 前端**（60 分钟）：`store.js:startStream` 换 `EventSource` + 用 `store.cursor` 重连；
   判据 `curl.exe -N` 能推、`vitest run` 105 绿、`smoke.mjs` 7 页零报错。
3. **文档说真话**（15 分钟）：`DELIVERY.md` 的"没做什么"把 `flow.yaml` / `NodeRegistry` /
   `flow.Action` / `ToolHook` / `compact` / `Checkpoint` / 崩溃恢复**逐条点名**，
   每一条后面跟一句"所以现在遇到 X 你得手动 Y"。

**其余一切（M3、M4、O1–O4）全部 `⊘`，并在 `PLAN.md` 里写原因，不留 `◻`。**
理由很硬：2 小时里塞进任何一条结构改动，都会让 `run2.mjs`（真模型 + 真 Docker，20-40 分钟一次）
跑不完、或者跑完没时间修。**端到端跑不过的架构升级，用户拿到手就是 0。**
