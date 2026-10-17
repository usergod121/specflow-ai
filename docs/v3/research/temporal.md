# Temporal SDK Java · 深读

> 目的：判断 Temporal 的重试 / 超时 / 心跳里，哪些能在 **无数据库、单机、文件状态** 的 specflow 上落地，代价多大。
> 源码：`temporalio/sdk-java` **@main**（注意：`master` 已 404，默认分支是 `main`；tarball 2,0MB，2026-02 拉取）。
> 本地解包：`E:\sf-research\temporal-sdk-java\sdk-java-main\`。下文行号均对应此份源码。
> 对侧：`E:\specflow-v2\src\main\java\com\specflow\`。

**一句话结论**：Temporal 的**重试字段**值得一次性补齐（尤其"失败分类"）；**心跳的"租约 + 进度游标"**能用我们已有的 `events.jsonl` 时间戳廉价模拟，而且正好补上 `StuckCheck` 的结构性盲区；**确定性 replay 那一整套坚决不抄**——它为"重放代码"付费，而我们的断点单位是"已落盘的副作用结果"，存结果就不需要重放代码。

---

## 1. RetryOptions 逐字段表（字段 / 默认值 / 含义 / 我们该不该有）

`RetryOptions` 只有 **5 个字段**。少得出人意料——重试这件事的难点不在字段多，在**判定**（下面「两个方法」）。

| 字段 | 默认值 | 含义（源码原意） | 我们该不该有 |
|---|---|---|---|
| `initialInterval` | **1 秒**（`DEFAULT_INITIAL_INTERVAL`，行 17） | 第一次重试前等多久。系数为 1.0 时，每次都等这么久。**必填**，且必须为正（行 137、141） | **该有**。现在 `Loop.attempt()` 是**零延迟重试**（见下），对"容器还没起来""docker daemon 正忙"这类失败等于没重试 |
| `backoffCoefficient` | **2.0**（行 16） | 下一次间隔 = 上一次 × 系数。必须 ≥ 1（行 154-156 有 `checkArgument`） | **该有**，和 `initialInterval` 是同一个概念（退避）；单列一个字段没必要 |
| `maximumAttempts` | **0 = 不限**（行 161-167，Javadoc「Default is unlimited」） | 次数到点就停，哪怕总时限还没到 | **已有等价物**：`LoopLimits.toolAttempts`。**不要再造一个**，把语义补齐即可 |
| `maximumInterval` | **100 × initialInterval**（`DEFAULT_MAXIMUM_MULTIPLIER = 100`，行 18、346） | 指数退避的上限；不设它就是 initial 的 100 倍。不能小于 `initialInterval`（行 103-110） | **暂不需要**。我们的重试次数是 2~3 次，`1→2→4` 秒根本撞不到上限；加了只是多一个旋钮 |
| `doNotRetry` | **空数组**（行 246）；`null` 与空数组语义**不同** | 按**失败类型名**不重试。`null` = 「去注解里取」；空数组 = 「什么都别重试」（行 291-294） | **最该现在就有**。见下 |

### 为什么 `doNotRetry` 是这 5 个里最关键的

失败**分类**决定了重试是省钱还是烧钱。我们现在 `Loop.attempt()`（`Loop.java:283-292`）：

```java
for (int tries = 1; tries <= limits.toolAttempts(); tries++) {
    last = once(tool, argsJson);
    if (last.ok()) return last;
}
```

**没有任何判定**——参数写错、路径不存在、语法错、超时，一律同样对待。默认 `toolAttempts = 1`（`LoopLimits.java:25`），所以现在是"根本不重试"；一旦有人把它调成 3，**确定性的失败会被重试 3 遍**，每遍烧掉最多 180 秒（`toolTimeoutSeconds` 默认值）且毫无成功可能。

Temporal 在这里给了三层递进（`shouldRethrow`，行 351-374）：

1. 先**剥壳**：`ActivityFailure` / `ChildWorkflowFailure` 取 `getCause()` 再判（行 354-356）——否则永远看到"可重试的外层"；
2. 按**类型名**比对 `doNotRetry`，命中直接抛（行 362-368）；
3. 再看次数（`attempt >= maximumAttempts`，行 370-372）与**总时限**。

还有两个细节值得抄：

- **`Error` 与 `CanceledFailure` 永远不重试**，甚至不进这个过滤器（行 213-215）。我们的对应物：`Outcome` 里"被中断 / 被闸拦下"不是失败，不该进重试。
- **`elapsed + sleepTime >= expiration` 就直接放弃**（行 373）：不是"睡完再发现超了"，而是"睡了必然超，那现在就别睡"。这条能让我们把 `Budgets` 的总预算守住，而不是被 N 次退避凿穿。

### `calculateSleepTime` 的真实算法（行 341-349）

```java
double sleepMillis = Math.pow(coefficient, attempt - 1) * initialInterval.toMillis();
if (maximumInterval == null) return (long) Math.min(sleepMillis, initialInterval.toMillis() * 100);
return Math.min((long) sleepMillis, maximumInterval.toMillis());
```

即 `initial × coeff^(attempt-1)`，上限兜底。这就是全部——**不需要库**，3 行。

### `ActivityOptions` 里那条容易踩的规则

`ActivityOptions.java:149-152`：

> If both `ScheduleToCloseTimeout` and `MaximumAttempts` are not set, the Activity will **not** be retried.
> To ensure zero retries, set `MaximumAttempts` to 1.

也就是"**无上限的 unlimited 重试"必须靠至少一个界（次数或总时限）兜住，否则它压根不重试**。这反过来印证：次数和总时限不是两个可选项，而是**同一个安全阀的两种表达**。

---

## 2. ActivityOptions 的四种超时（各自的语义边界 + 一句话例子）

四个 `Duration` 字段，默认全是 `null`（= 不限），**真正必填的只有一个**。

| 超时 | 边界（从哪到哪） | 默认值 | 一句话例子 |
|---|---|---|---|
| **Schedule-To-Start** | 任务进队列 → 被 worker 领走。**管排队，不管执行** | 不限（行 93） | "镜像拉取任务排了 10 分钟还没人接"——worker 被别的活占满了 |
| **Start-To-Close** | **单次尝试**开始 → 结束。管"这一次跑多久" | 无默认，若没给 Schedule-To-Close 则**必填**（行 109-111） | "单次容器构建最多 30 分钟" |
| **Schedule-To-Close** | 从被调度的**那一刻** → 最终结束，**含所有重试**。管"这条路总共给多久" | 不限（行 80） | "拉镜像最多试 5 次，但总共只给 30 分钟" |
| **Heartbeat** | 两次心跳之间的最大间隔（见 §3） | 无（`ActivityInfo` 注释说 absent 时是 `Duration.ZERO`） | "构建要 20 分钟，但每 10 秒得报一次进度" |

三条注释里的"为什么"，值得原样记下来：

**① 为什么 Start-To-Close 必须"尽量贴着真实最长耗时"**（行 103-107）：

> Note that the Temporal Server **doesn't detect Worker process failures directly**. It relies on this timeout to detect that an Activity didn't complete on time. So this timeout should be as short as the longest possible execution of the Activity body. Potentially long-running Activities must specify **HeartbeatTimeout** and call `heartbeat()` periodically for timely failure detection.

这是全篇最重的一句：**连 Temporal 也没有"进程崩了"的直接检测**，它靠的是**超时**。我们 `Journal.sweep()`（"有 BEGIN 没有 END 就是被杀过的"）本质上是同一招——只是我们只在**启动时**扫，Temporal 是**随时**由服务端计时。差异不在思想，在**谁在计这个时**。

**② 为什么 Schedule-To-Start 天生不可重试**（行 90-91）：

> ScheduleToStartTimeout is always **non-retryable**. Retrying after this timeout doesn't make sense as it would just put the Activity Task back into the same Task Queue.

排队超时再排一次，还是排在同一队尾——纯浪费。这条对我们单机几乎无意义（我们没有任务队列），但**它揭示了一个判据**：*"重试"只有在"这次失败的成因会随时间改变"时才有意义*。队列深度不会因为重试而改变；docker daemon 忙会。

**③ 四种超时的分工**：`Schedule-To-Close` 管**总预算**（含重试），`Start-To-Close` 管**单次**（行 75-76 明说这个分工）。

### 映射到 specflow，缺的是哪一层

| Temporal | spectflow 现状 | 缺口 |
|---|---|---|
| Start-To-Close | `LoopLimits.toolTimeoutSeconds`=180s；`Budgets`=30min（起环境 / 构建） | — 已有 |
| **Schedule-To-Close** | **无** | **真缺口**：现在只有"单条命令 30 分钟"，没有"这条用例 / 这一站总共给多久"。若把 `toolAttempts` 调成 3，最坏 3×30 分钟没有任何东西拦 |
| Schedule-To-Start | 无（单机无队列） | 不需要 |
| Heartbeat | 无（`StuckCheck` 不是它） | 见 §3 |

`Budgets` 的定位其实**同时**像 Start-To-Close（单条命令）和 Schedule-To-Close（用户心智里是"这一步我愿等多久"，`Budgets.java:4`），这层歧义迟早要拆——但那是一次小重构，不是引入语义机。

---

## 3. 心跳：它是怎么发现"卡死"的，与我们的 StuckCheck 有什么本质差别

### 3.1 心跳在 SDK 里到底做了什么（三件事，不是一件）

读 `internal/activity/HeartbeatContextImpl.java`（498 行）才看清心跳是**三合一**的：

**(a) 续租：由外部计时判定"还活着"**

- **发送节奏 = 心跳超时的 80%**，再被 worker 侧上限压一刀（行 488-497）：
  ```java
  long interval = activityHeartbeatTimeout.isZero()
      ? defaultHeartbeatThrottleInterval.toMillis()
      : (long) (0.8 * activityHeartbeatTimeout.toMillis());
  return Math.min(interval, maxHeartbeatThrottleInterval.toMillis());
  ```
  用户调 `heartbeat()` 的频率无所谓，SDK 按 80% 节流合并（`scheduleNextHeartbeatLocked`，行 327-347：没有新心跳就不发）。
- **本地死线 = 心跳超时 + 5 秒缓冲**（行 56、349-352）。到点就在**下一次 `heartbeat()` 调用时**抛 `ActivityCanceledException(TIMEOUT_TYPE_HEARTBEAT)`（行 354-368）。
  > ⚠️ 细节：本地死线只在 `heartbeat()` 里检查（行 186）——**如果活彻底挂住、再也不调 `heartbeat()`，本地这条查不出来**，只有服务端的心跳超时会判死。所以"判定权"始终在**第三方**手里，SDK 侧只是快路径。
- **心跳发送失败不让活动失败**（行 314-318）：只 `log.warn` 并 1 秒后重试。网络抖一下不该杀掉一个跑了 20 分钟的构建。

**(b) 进度游标：`details` 就是断点续跑的锚点**

这是心跳最被低估的用途。看 `ActivityExecutionContext.java` 行 38-68：

> If there were Heartbeat details reported by the last Activity Execution that failed, they would be delivered along with the Activity Task for the next retry attempt ... The Activity implementation can extract the details via `getHeartbeatDetails()` and **resume progress**.

实现上两极：本次尝试内返回 `lastDetails`（自己刚报的），否则回落到 `prevAttemptHeartbeatDetails`（上一次尝试失败时留下的，行 84、160、209-224）。所以 `heartbeat(details)` 是"**把进度写进下一次尝试能读到的地方**"。

**(c) 取消通道**

解包响应时看 `status.getCancelRequested()` → `requestCancelLocked()`（行 461-462）。**卡在长活里的时机，只有心跳这一条路能收到"别干了"**（`ActivityCancellationType` 的注释也在说这个）。所以"可恢复中断"里那个"中断"，Temporal 是挂在心跳上的。

另外，`internal/worker/HeartbeatManager.java` 是**另一个东西**：worker 进程自身的存活心跳（`RecordWorkerHeartbeat`），跟活动无关，别混。

### 3.2 我们的 StuckCheck 是什么

`StuckCheck.java`：一个 `Tool` 装饰器，把 `name()+args` 拼成 key，**连续 3 次完全相同**就拦下并回喂一句"你在原地打转"（`REPEATS=3`，第 4 次开始拦，行 26、65）。

它的自我定位非常诚实（行 14-17）：**只做一条判据**，"更复杂的（输出无变化、只读不写、只写不验）留到以后"。

### 3.3 本质差别：**判据的"信息源"和"裁定者"都不同**

| 维度 | Temporal 心跳 | 我们的 StuckCheck |
|---|---|---|
| 看什么 | **沉默**：有没有按时发出"我还活着" | **重复**：是不是在做一模一样的事 |
| 谁裁定 | **外部时钟**（服务端记死线）；SDK 本地只是快路径 | **自己**（装饰器里的 `last`/`same` 两个字段） |
| 什么时候能触发 | 命令**执行中**也有效——它不管你在干什么 | 只可能在一次调用**返回之后**触发 |
| 延迟 | ≤ `heartbeatTimeout`（典型 30s），**与活的性质无关** | 第 4 次相同调用返回时；一条命令挂 30 分钟期间**完全无能** |
| 能发现"一条命令挂死不动"吗 | **能** | **不能**（`StuckCheck` 根本没被调用） |
| 能发现"每条命令都不同、但整体没进展"吗 | **不能**（只要在报心跳就算活） | **不能**（key 一直在变，`same` 一直重置） |
| 带进度吗 | **带**：`details` 是下一次尝试的续跑锚点 | 不带任何载荷 |
| 能接收取消吗 | **能**（`getCancelRequested`） | 不能 |
| 重启后 | 状态在服务端，不丢 | `last`/`same`/`spent` 全是内存字段，**重启归零** |

**一句话**：心跳是**"由外部时钟裁定还活着吗"**（沉默即死，deadline）；StuckCheck 是**"由自身内容猜测在进步吗"**（重复即转，pattern）。前者对**一条挂死**有效、对**空转**无效；后者反之。**两者不互相替代，正好互补。**

而且我们补 StuckCheck 这个空档的**成本比想象的低**——因为 `Journal` 已经实现了同构的判据：

> `Journal.java:20-23`：「**有 BEGIN 而没有 END 的运行，就是被杀过的**。不查进程、不看时间戳、不问操作系统。」

把粒度从"运行"降到"一次工具调用"，就是"**有 `toolStart` 没有 `toolEnd`，就是被卡死/杀过的**"。而 `EventLog` 的 `toolStart` / `toolEnd` **已经共用 `callId` 成对出现**（`Event.java:78-89`），时间戳已有（`Event.now()`）。**这是纯查询，不需要新机制。**

### 3.4 还有一个比 StuckCheck 更该修的洞

`Loop.once()`（`Loop.java:294-313`）的超时是**弃线程**：

```java
Thread worker = new Thread(...);      // 守护线程
worker.start();
worker.join(timeout * 1000L);
if (worker.isAlive()) return ToolResult.failure(tool + " 超过 " + timeout + " 秒还没结束，引擎不再等它");
```

注释也承认（行 280-281）：「那条线程收不回来，但它不挡着循环」。于是：**超时之后那个命令可能还在跑、容器还在起**，而我们已经在下一轮让模型"换个办法"了——两个进程同时在动同一份工作区。心跳模型在这里给了一个正确的方向（**取消要能传到活里**），虽然我们做不到真取消，但至少应该在超时后写一条 `toolEnd(status=timeout)` 把账收掉，并在恢复时据此判死（见 §6）。

---

## 4. 确定性与 replay（我们能不能用 JSONL 事件流模拟，代价多大）

### 4.1 Temporal 为什么要确定性

`worker/NonDeterministicException.java` 全文只有一句实质内容（行 3-12）：

> Thrown if **history events from the server don't match commands issued by the execution or replay of workflow code**. This exception usually means that there is some form of non-determinism in workflow code that has lead to a difference in the execution path taken upon replay.

所以 Temporal 的持久化单位是**命令**（代码做出的决定），恢复方式是**把代码重跑到同一位置**。代价是**代码必须可重放**：

- 时间、随机、UUID 都得换成确定性实现（`Workflow.newRandom` / `Workflow.newTimer`；`Workflow.java:42-43` 把"Deterministic implementation of popular non-deterministic API" 列为该类的主要职责之一）；
- 需要 `Workflow.sideEffect`（行 1058-1065）作逃生口：**「Executes the provided function once, records its result into the workflow history. The recorded result on history will be returned without executing the provided function during replay.」**
- 还需要 `isReplaying()`（行 1042-1056）并附一句警告：「**Never make workflow logic depend on this flag** as it is going to break determinism」；
- 更麻烦的是那条"坏例子"（行 1067-1086）：`sideEffect` 里偷偷改外部状态，replay 时不执行函数 → 状态永远是初值 → 走岔路。**这是"重放代码"这条路自带的陷阱**。

### 4.2 我们能不能用 JSONL 模拟 replay？

**分两半，答案不一样，而且这 halves 的区分是全篇最有用的判断。**

**❌ 完整 replay（重跑代码到断点）：做不到，别承诺。**
因为闭环里最贵的一步（问模型）**不可重放**——同一个 prompt 两次结果不同。要 replay 就必须把模型输出也录下来当"命令"，那已经退化成"存结果"了，等于放弃了 replay 的意义。`NonDeterministicException` 这套机制在这里是纯负债。

**✅ 存结果 + 断点续跑（memo / resume cursor）：不但可行，而且我们已经付了 90% 的钱。**

盘点手上已有什么（全部是实测读过的代码，不是设想）：

| 已有的 | 在哪 | 对 replay 的意义 |
|---|---|---|
| append-only 事件流，`id` **恒等于行号**，重启接着数 | `EventLog.java:19` | 天然的、有序的、可回放的序号——**这就是 event history 的骨架** |
| 「盘上是唯一的真相」，读写一律以盘为准 | `EventLog.java:20-21` | 已避开"内存与盘两份真相"这个持久执行最经典的坑 |
| 单写者 + 损坏行不吞、改成 warn 交出去 | `EventLog.java:16-17, 24-26` | 失败可诊断——Temporal 也靠 history 完整性 |
| **`toolStart` / `toolEnd` 用同一 `callId` 成对** | `Event.java:78-89`；注释 `Event.java:12-17` | **等价于 Temporal 的 `ActivityTaskScheduled` / `Completed` 配对**。有这个，就能回答"这一次调用到底完成了没有" |
| 运行级 `BEGIN`/`END`/`STEP` + 启动 `sweep()` 补记 `INTERRUPTED` | `Journal.java:59-119` | **已经实现了 §2 里 Temporal"靠超时判死"的同一招**，只是粒度在"运行" |
| 每用例 `PENDING`/`SKIPPED`/终态 + 逐用例记账 | `CaseList`、`CaseBudget.java:87-94` | **天然的断点单位**：清单里第一条 `PENDING` 就是该从哪继续 |

**要补的只有两件事，都很小：**

**① 把"完成"这个事实变成可查询的**（而不是靠重新执行）。判据现成：
> 一条 `toolStart(callId=X)` 若**没有**对应的 `toolEnd(callId=X)`，那次调用就是"没交代"的——启动/续跑时按"被中断"补记一条 `toolEnd(status=interrupted)`，而不是重放它。

这跟 `Journal.sweep()` 是**同一个函数换粒度**。

**② 存"下一轮需要的最小上下文快照"**，因为 ReAct 的循环状态**不在盘上**。这是唯一真正的成本项。现在这些状态纯内存、重启即失：

| 内存状态 | 在哪 | 能否从事件流重建 |
|---|---|---|
| `CaseBudget.spent` / `calls` / `current` | `CaseBudget.java:53-56` | **能**（`case_update` 事件 + 调用计数都在流里） |
| `StuckCheck.last` / `same` | `StuckCheck.java:29-30` | **能**（最后几次 `toolStart` 的 key 就是） |
| 轮数 / 模型调用数 | `Loop` 内 | **能**（事件里有 `round`） |
| **消息历史**（给模型的上下文） | `Loop` 内 | **不能**——盘上只有截断/压缩后的 `resultFull` + `Compact`（`LoopLimits.MAX_OUTPUT_CHARS = 20_000`，超长会截断） |

**所以代价可以被精确定价：一条"快照事件" + 一个续跑入口。** 粒度选**用例边界**（`CaseList` 的 `PENDING`），不是"每条命令"——因为：
- 用例边界上本来就有一个天然的"记账"动作（`case_update`）；
- 用例内的重做成本可以接受（`CaseBudget` 给 12 轮，重跑一条用例 ≤ 若干次工具调用）；
- 若按命令粒度续跑，就必须逐条快照消息历史，事件流量和状态一致性都翻倍——**不划算**。

### 4.3 代价的一个隐藏项：至少一次 vs 恰好一次

Temporal 的语义是 **at-least-once**（活动可能被重派），幂等由用户自己解决——这也是心跳 `details` 的真正用途（让重派的那一次能"接着干"而不是"从零干"）。

落到我们身上：续跑**一定会**重放"最后那条没交代的命令"。所以**一定要有去重键**——就用现成的 `callId`。对**非幂等**命令（`git commit`、`docker run`、往 `flow.json` 写状态）必须在续跑前查"这个 `callId` 有没有 `toolEnd`"，**有就跳过、并把记录的结果直接交给模型**。这一步是安全的，因为结果就在 `resultFull` 里。

**记住这个判断**：我们抄的是 Temporal 的 **`getHeartbeatDetails` 那一半（进度游标）**，不是 `DeterministicRunner` 那一半（代码重放）。

---

## 5. 值不值得借鉴（机制 / 收益 / 在我们的代价 / 结论）

约束：**无数据库、单机、文件即状态**。

| 机制 | 收益 | 在我们的代价 | 结论 |
|---|---|---|---|
| **RetryOptions 五字段模型** | 把"重试"从散落的一行 `for` 变成一份可读的策略 | 极小，一个 `record` + 一个 `sleepBefore` | **借** |
| **`doNotRetry`（失败分类）** | 让 `toolAttempts>1` 变得**安全**：确定性的失败不再被白试 3 遍 | 极小；`ToolResult.reason` 已是现成判据（见 §6） | **立刻借（最高优先）** |
| **退避 `initial+coeff`** | 对"容器还没起""daemon 忙"这类**唯一真会随时间好转**的失败，才是真重试 | 3 行 `Math.pow`，外加一个 `Thread.sleep` | **借** |
| `maximumInterval` | 防止退避无限涨 | 次数只有 2~3，撞不到 | **不借**（用不上，加旋钮是负担） |
| **Schedule-To-Close（总预算含重试）** | 堵住"N 次重试 × 每次 30 分钟"这个现在没人拦的洞 | 小：一个 `deadline` + 在 `attempt` 循环里判一下 | **借**（真缺口，见 §2 映射表） |
| Schedule-To-Start | — | 单机没有任务队列 | 不借 |
| **Start-To-Close** | — | 已有（`toolTimeoutSeconds` / `Budgets`） | 已有，不重做 |
| **心跳租约（`toolStart` 时间戳 + 看门狗判死）** | 补上 `StuckCheck` 无法覆盖的"**一条命令挂死不动**"，且延迟从 30 分钟降到可配的秒级 | 小：`EventLog` 已有时间戳与 `callId` 配对，`Journal.sweep()` 已有同构判据 | **借（改造成本最低、收益最大的一个）** |
| **心跳 details（进度游标）** | 续跑能"接着干"，而不是"从零干" | 中：需要一个"进度"载荷 + 落盘时机 | **借，但只在用例边界** |
| 心跳作取消通道 | 长活能收到"别干了" | 我们做不到真取消（`Loop.once()` 弃线程）；只能做"超时后收账 + 续跑时判死" | **降级借** |
| **确定性 replay 全家桶**（`DeterministicRunner`、`sideEffect`、`isReplaying`、versioning、`NonDeterministicException`） | 让"重放代码"成立 | **不可承受**：模型不可重放；且要命中 `Workflow.java:1067-1086` 那类陷阱 | **明确不借** |
| `continueAs-new`（切新 run 承载新历史） | 限制单份历史体积 | 无：`events.jsonl` 本就 append-only，超大时已有 `truncateTo` + `events-invalidated-*.jsonl` 归档（`EventLog.java:140-152`） | **不借** |
| `Journal.sweep()` 型启动扫描 | 崩了能被发现 | 已有 | **已有，只是粒度粗（运行级）** |

### 汇总判断

**值得借鉴的只有两块，而且这两块的好处被"文件即状态"这个约束放大了**：

1. **重试策略化的 3 个字段**（分类 / 退避 / 总时限）——因为我们现在的重试是"要么不试、要么瞎试"。
2. **"完成这件事要靠时间戳契约来判定"**——Temporal 靠服务端计时，我们靠盘上的时间戳 + 启动扫描。`Journal` 已经证明了这条路在无数据库下走得通，只需降粒度。

**不值得的，是一整套"重放"基础设施**。它的存在理由（代码不可信、要重跑校验）在我们这里不成立：我们的循环不是代码路径，是**一串已落盘的副作用**。**存结果，就不需要确定性。**

---

## 6. 最小吸收方案（≤25 行 Java 草图）

只做一件事的前提取舍：**先把"失败分类 + 退避 + 总时限"补进 `Loop.attempt()`**，同时把每次重试的判据收成一行事件（顺带给 §5 的"租约判死"留下数据）。字段一次性补齐，但**只有三个**，`maximumInterval` 不加。

```java
package com.specflow.agent;

import java.util.concurrent.TimeUnit;

/**
 * <b>一次工具失败值不值得再试</b>——Temporal RetryOptions 的五字段内核，砍到三个。
 *
 * <p>只留三条判据，每个都对应我们真实撞过的失败：
 * <ul>
 *   <li><b>不重试</b>：参数错、路径不存在、语法错——重试只是把同一份错再要一遍，</li>
 *   <li><b>退避</b>：容器还没起来、daemon 正忙，这类是<b>唯一</b>会随时间好转的，零延迟重试等于没试；</li>
 *   <li><b>总时限</b>：<em>睡了也必然越过总预算，那现在就别睡</em>（抄 {@code RetryOptions:373}）。</li>
 * </ul>
 * 不加 {@code maximumInterval}：我们只试 2~3 次，退避撞不到上限。
 */
public record Retry(int attempts, long firstMillis, double factor) {

    /** 不重试的失败长得什么样（{@link ToolResult#reason} 里出现这些就不必再试）。 */
    private static final String[] NEVER = {"参数", "不存在", "读不出来", "不允许", "语法", "超过"};

    public static Retry once() { return new Retry(1, 0, 2.0); }

    /** 第 {@code attempt} 次失败后该睡多久；返回 {@code -1} = 别再试了。 */
    public long sleepBefore(int attempt, ToolResult last, long deadlineNanos) {
        if (last.ok() || attempt >= attempts) return -1;
        for (String why : NEVER) if (last.reason().contains(why)) return -1;
        long millis = (long) (firstMillis * Math.pow(factor, attempt - 1));
        // 睡了必然越过总时限：现在就别睡（Temporal: elapsed + sleepTime >= expiration）
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis) >= deadlineNanos ? -1 : millis;
    }
}
```

**接进 `Loop.attempt()` 的改法**（`Loop.java:283-292`，把 `for` 换成 `while` + 两行）：

```java
long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(limits.totalSeconds());
for (int tries = 1; ; tries++) {
    last = once(tool, argsJson);
    long sleep = retry.sleepBefore(tries, last, deadline);
    if (sleep < 0) return last;
    try { Thread.sleep(sleep); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return last; }
}
```

**这一版刻意不做的**（写下来免得以后有人以为是漏了）：
- 不落"快照事件"、不做续跑入口——那是 §4 第 ② 项，独立一步，别和重试混在一个改动里；
- 不做心跳看门狗线程——它需要先有"超时后补 `toolEnd`"的收账纪律，否则会误杀正在正常跑的长活；
- 不动 `Budgets`（唯一换算处的地位要保住，`Budgets.java:13-14`）。

---

## 7. 不借鉴的部分与理由

**① 确定性 replay 基础设施（最明确的一个）**
`DeterministicRunner` + 命令/历史比对 + `NonDeterministicException` + `Workflow.isReplaying()` + `sideEffect` + `VersioningIntent`。
**理由**：它为"**重放代码**"付费。我们的恢复单位是"**已落盘的副作用结果**"，只要存结果、按 `callId` 去重，就永远不需要把代码重跑到断点，也就不需要确定性。硬套的后果已经写在 `Workflow.java:1067-1086` 的"坏例子"里——半懂不懂地用 `sideEffect` 会造出"replay 时永远取初值"的幽灵 bug，而这类 bug 我们既没有 Temporal 的 history 工具，也没有能复现的手段。
**一句话**：**这是 Temporal 的成本，不是 Temporal 的能力**。

**② `Schedule-To-Start` / 任务队列 / `taskQueue` 优先级（`Priority`、`setVersioningIntent`）**
单机没有队列，排队时间恒为 ~0。所有"队列深度"相关的旋钮都是零收益的复杂度。

**③ `maximumInterval`**
只有 2~3 次重试，`1→2→4` 秒撞不到 100 倍上限。**多一个旋钮就多一处要解释、要测、要在界面上放的位置**——我们在 `Budgets` 上刚吃过"旋钮说有、代码没接"的亏（`Budgets.java:6-14`）。

**④ `continue-as-new`**
它的用途是"历史太长就把新历史放进新 run"。我们的 `events.jsonl` 是 append-only 且已有 `truncateTo()` 先留副本再截断（`EventLog.java:140-152`）、`sweep()` 会认 `INTERRUPTED`。**引入新 run 概念只会让"哪一份是当前账"变成需要解释的问题。**

**⑤ 心跳的完整协议栈（`RecordActivityTaskHeartbeat` RPC、节流 80%、`HeartbeatManager`）**
我们没有服务端，没有需要"续租"的第三方。**借鉴它的判据（时间戳 + 沉默即死），不要借鉴它的实现。**
特别注意：`internal/worker/HeartbeatManager.java` 是 **worker 进程存活心跳**，与活动心跳无关——翻源码时容易混，混了就会得出"心跳很复杂"的错误结论。

**⑥ `WorkflowTaskTimeout` 那套（默认 10s、上限 120s）**
它解决的是"workflow worker 崩了，任务卡多久重派"。我们没有 workflow task 这个概念。

---

## 8. 未核实点 + 证据清单（文件:行）

### 未核实 / 需注意

1. **`WorkflowUnrecoverableException` 在该版本不存在**。全库 `*.java` 搜文件名与 `Unrecoverable` 字样**均无命中**；最接近的是 `io/temporal/worker/NonDeterministicException.java`（本文件 §4.1 已引）。→ 任务里点名的这个类应该是**别的版本/别的语言的叫法**，或与 `InternalWorkflowTaskException` 混淆。**未下断言。**
2. **`master` 分支已 404**，实际默认分支是 `main`（GitHub API `default_branch: "main"`，tarball 1,996,176 字节）。任务给的 `master` 链接需换成 `main`。
3. **未读服务端**（`temporalio/temporal`）。所有"服务端如何计时判死"的说法，均来自 SDK **注释**（`ActivityOptions.java:103-107`）而非服务端源码。
4. **未验证 80% 节流与 `maxHeartbeatThrottleInterval` 默认值的实际数值**（默认值在 `WorkerOptions` 侧，本文件未追）。只确认了"80%"与"取 min"这两处逻辑。
5. **specflow 侧未验证**：`CaseList` 的 `PENDING`/`SKIPPED` 具体实现、`Json.line/read` 的稳定性、`Atomic.appendLine` 的写入原子性（是否 `fsync`）均未读。§4 里"能重建"的判断基于 `Event` 字段（`round`/`callId`/`status`）存在，**未做端到端验证**。
6. **`Loop.once()` 弃线程后命令是否真在跑**，未实测（`Shell` 实现的超时是否真能杀进程，未读）。

### 证据清单

**Temporal（`E:\sf-research\temporal-sdk-java\sdk-java-main\`）**

| 主题 | 文件:行 |
|---|---|
| 默认值常量（1s / 2.0 / 100×） | `temporal-sdk/src/main/java/io/temporal/common/RetryOptions.java:16-18` |
| `initialInterval` 必填且为正 | 同上 `:137-146` |
| `backoffCoefficient ≥ 1` | `:150-159` |
| `maximumAttempts` 0=不限 | `:161-173` |
| `maximumInterval` 默认 100×、不能小于 initial | `:175-191`, `:103-110` |
| `doNotRetry` 语义（null vs 空数组） | `:193-221`, `:291-297` |
| `Error`/`CanceledFailure` 永不重试 | `:213-215` |
| 退避算法 | `:341-349` |
| 放弃判定（剥壳→类型→次数→总时限） | `:351-374` |
| 四种超时注释与默认 | `temporal-sdk/src/main/java/io/temporal/activity/ActivityOptions.java:72-124` |
| STC/S2C 分工 | 同上 `:75-76` |
| STS 天生不可重试 | `:90-91` |
| 服务端不直接检测 worker 崩溃 | `:103-107` |
| 缺 STC 且缺 maxAttempts ⇒ 不重试 | `:149-152` |
| 服务器默认活动重试策略 | `:141-147` |
| 心跳超时 absent ⇒ ZERO | `io/temporal/activity/ActivityInfo.java:97-100` |
| `details` = 下一次尝试的续跑锚点 | `io/temporal/activity/ActivityExecutionContext.java:25-68` |
| 心跳 = 取消通道（`getCancelRequested`） | `io/temporal/internal/activity/HeartbeatContextImpl.java:461-462` |
| 心跳间隔 = 0.8 × timeout，min 上限 | 同上 `:488-497` |
| 本地死线 + 5s 缓冲 | `:56`, `:349-352`, `:354-368` |
| 本地死线只在 `heartbeat()` 里检查 | `:186` |
| 心跳失败不让活动失败 | `:314-318` |
| 心跳节流/合并 | `:327-347` |
| 本次内 vs 上一次的 details | `:209-240` |
| worker 自身心跳（易混淆） | `io/temporal/internal/worker/HeartbeatManager.java:13-16` |
| 非确定性异常 | `io/temporal/worker/NonDeterministicException.java:3-12` |
| 确定性 API 是主要职责 | `io/temporal/workflow/Workflow.java:42-43` |
| `sideEffect` 语义与坏例子 | 同上 `:1058-1086` |
| `isReplaying` 警告 | `:1042-1056` |
| `continueAsNew` | `:579-599`；`io/temporal/workflow/ContinueAsNewOptions.java:10-13` |
| 运行/执行/任务超时注释 | `io/temporal/client/WorkflowOptions.java:205-266` |
| 未设置 RetryOptions 时由服务端默认策略接管 | `ActivityOptions.java:135-157` |

**specflow（`E:\specflow-v2\src\main\java\com\specflow\`）**

| 主题 | 文件:行 |
|---|---|
| 单条命令超时唯一来源、30min 默认 / 1~240min 夹取 | `env/Budgets.java:25-44` |
| 零延迟重试、无分类 | `agent/Loop.java:283-292` |
| 超时=弃线程，不杀进程 | `agent/Loop.java:278-313` |
| 轮次/调用/超时/次数默认（12 / 60 / 180 / 1） | `agent/LoopLimits.java:8-25` |
| 卡死检测判据（连续 3 次相同） | `agent/StuckCheck.java:14-17`, `:26`, `:57-73` |
| 内存态，重启归零 | `agent/StuckCheck.java:29-30` |
| 事件流三条铁律（单写者、id=行号、盘为准） | `core/EventLog.java:14-29` |
| 截断前先留副本 | `core/EventLog.java:132-152` |
| `toolStart`/`toolEnd` 共用 callId | `core/Event.java:78-89`, `:12-17` |
| BEGIN/END 判死 + 启动 sweep | `flow/Journal.java:17-31`, `:89-119` |
| 逐用例记账、当前=第一条 PENDING | `test/CaseBudget.java:52-56`, `:87-94`, `:34-47` |
| `ToolResult` 形态（ok / data / reason，不抛异常） | `agent/ToolResult.java:12-26` |
| 输出 20000 字符上限（快照不可从事件流完整重建的原因） | `agent/LoopLimits.java:16` |
