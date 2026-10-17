# Argo Workflows · 深读

> **读的是真源码。** 版本：`argoproj/argo-workflows` `main` 分支，tarball 抓取并解包到本机
> `E:\sf-research\argo\argo-workflows-main`（`curl codeload` + `tar`，解包时有 60 余条 Windows
> 保留名/符号链接失败，均在 `test/e2e/**`、`dev/nix`、`workflow/executor/osspecific` —— 本报告引用的
> 文件全部完整落盘，已逐一 `Test-Path` + 行数核对）。
>
> **判定对象**：specflow（无数据库 / 单机 / Java / 五站流水线，每站有闸，已有 `env.yaml` 手写校验器）。
> 本文只回答两个问题：**重试/跳过/收尾语义值不值得抄**、**"流程"要不要也做成 YAML**。
>
> 全文引用格式 `路径:行号`，路径相对于 `argo-workflows-main/`。凡结论为读码推得而未实跑验证的，第 8 节列明。

---

## 1. RetryStrategy 逐字段表

### 1.1 类型原文

`pkg/apis/workflow/v1alpha1/workflow_types.go:2381-2399`：

```go
// RetryStrategy provides controls on how to retry a workflow step
type RetryStrategy struct {
	// Limit is the maximum number of retry attempts when retrying a container. It does not include the original
	// container; the maximum number of total attempts will be `limit + 1`.
	Limit *intstr.IntOrString `json:"limit,omitempty"`

	// RetryPolicy is a policy of NodePhase statuses that will be retried
	RetryPolicy RetryPolicy `json:"retryPolicy,omitempty"`

	// Backoff is a backoff strategy
	Backoff *Backoff `json:"backoff,omitempty"`

	// Affinity prevents running workflow's step on the same host
	Affinity *RetryAffinity `json:"affinity,omitempty"`

	// Expression is a condition expression for when a node will be retried. If it evaluates to false, the node will not
	// be retried and the retry strategy will be ignored
	Expression string `json:"expression,omitempty"`
}
```

`RetryPolicy` 是四值枚举，`workflow_types.go:2344-2353`：

```go
// +kubebuilder:validation:Enum=Always;OnFailure;OnError;OnTransientError
type RetryPolicy string
const (
	RetryPolicyAlways           RetryPolicy = "Always"
	RetryPolicyOnFailure        RetryPolicy = "OnFailure"
	RetryPolicyOnError          RetryPolicy = "OnError"
	RetryPolicyOnTransientError RetryPolicy = "OnTransientError"
)
```

`Backoff`，`workflow_types.go:2355-2370`：`duration`（默认单位秒，也可 `"2m"`/`"1h"`）、`factor`
（`IntOrString`，每次失败乘的系数）、`maxDuration`、`cap`（乘法结果的上限封顶）。

`RetryAffinity`，`workflow_types.go:2372-2379`：只有一个 `nodeAntiAffinity`，且是空结构体
（`RetryNodeAntiAffinity struct{}`，注释 `:2372-2373`「placeholder for future expansion, only empty
nodeAntiAffinity is allowed」，实际语义靠 `kubernetes.io/hostname` 标签硬编码实现，见
`workflow/controller/retry_tweak.go:26-39`）。

### 1.2 默认值 —— 全部来自代码，不是文档

**`retryPolicy` 的默认值是三段式推导的**，`workflow_types.go:2401-2414`：

```go
func (s RetryStrategy) RetryPolicyActual() RetryPolicy {
	if s.RetryPolicy != "" { return s.RetryPolicy }
	if s.Expression == "" { return RetryPolicyOnFailure }
	return RetryPolicyAlways
}
```

即：**写了 `expression` 又没写 `retryPolicy` → 实际策略是 `Always`**，理由是「让 expression 成为唯一
的控制变量，最不意外」（注释 `:2403-2404`）。这个隐式翻转值得单独记住 —— 它就是我们最该避免的
那种「两个开关互相影响」的设计，但在**必须**支持 expression 的前提下，这个取舍是对的。

**`limit` 没有默认值**：`Limit == nil` 时下面 `:1184` 的守卫整条跳过，配合 `Backoff == nil` 的
`:1119` 也跳过 —— 结果是**永远重试**。这是 Argo 里一个真实的地雷（不写 limit 又配上 backoff，
就是无限重试到 workflow deadline）。

**`backoff` 的默认值**：`Backoff == nil` → `RetryBackoffWait` 直接返回 0
（`workflow/common/retry.go:21-23`）。`Backoff.Duration == ""` → **报错**，不是默认 0
（`retry.go:24-26`），即「只写 factor 不写 duration」是非法组合。`factor` 为 nil 或 ≤0 → 用
`duration` 本身不减也不加（`retry.go:36`）。

### 1.3 退避公式与执行位置

唯一实现在 `workflow/common/retry.go:12-55`，注释把口径钉死（`:12-19`）：

```go
// RetryBackoffWait returns how long to wait before the next retry attempt of
// a retry node that has already made `attempts` attempts, per the strategy's
// backoff: duration * factor^(attempts-1), capped by backoff.cap. It returns
// 0 when no backoff is configured. backoff.maxDuration is a deadline measured
// from the first attempt, not a per-attempt wait, so it is not applied here.
```

- 公式：`duration * factor^(attempts-1)`，第一次重试等 `duration` 整（`retry.go:37-45`）。
- 溢出保护：乘法会超 `MaxInt64` 时饱和成 `MaxInt64`（`retry.go:41-45`）。
- `cap` 在乘法**之后**取 min（`retry.go:47-53`）。
- **`maxDuration` 不是"每次等多久"，是"从第一次尝试起算的绝对截止时刻"** —— 这一句是上引注释里的
  关键，也是它不在这个函数里参与计算的原因。

执行侧 `workflow/controller/operator.go:1119-1162`：

| 行 | 做了什么 |
|---|---|
| `:1122-1131` | `maxDurationDeadline = firstChildNode.StartedAt.Add(maxDuration)` —— 从**第一次尝试的开始时刻**算，不是从最近一次 |
| `:1132-1135` | 已过 `maxDurationDeadline` → 直接失败，消息 `"Max duration limit exceeded"` |
| `:1139` | 算这次的 `timeToWait` |
| `:1146-1148` | **如果"等到下次"本身就会越过 maxDuration，就别等了**，立刻失败，消息 `"Backoff would exceed max duration limit"` |
| `:1152-1156` | 还没到等待截止点且 `attempts <= limit` → `requeueAfter(timeToWait)`，节点留在原态，消息 `"Backoff for 3s"` |
| `:1184-1187` | `len(childNodeIds) > *limit` → 失败，消息 `"No more retries left"` |

注意 `:1152` 比较的是 `int32(len(childNodeIds)) <= int32(RetryStrategy.Limit.IntValue())`，而
`:1184` 比较的是 `> limit`。两处口径一致：**`limit` 数的是"重试次数"，已经失败 `limit+1` 次才放弃**；
`childNodeIds` 里第一个是原始尝试，所以 `len == limit+1` 时正好用完。

`retryPolicy` 的实际判定在 `operator.go:1218-1237`：

```go
func retryPolicyAllows(ctx context.Context, lastChild *wfv1.NodeStatus, retryStrategy wfv1.RetryStrategy) (retryOnFailed, retryOnError bool, err error) {
	switch retryStrategy.RetryPolicyActual() {
	case wfv1.RetryPolicyAlways:           return true, true, nil
	case wfv1.RetryPolicyOnError:          return false, true, nil
	case wfv1.RetryPolicyOnTransientError:
		if (lastChild.Phase == wfv1.NodeFailed || lastChild.Phase == wfv1.NodeError) &&
			errorsutil.IsTransientErr(ctx, argoerrors.InternalError(lastChild.Message)) { return true, true, nil }
		return false, false, nil
	case wfv1.RetryPolicyOnFailure:        return true, false, nil
	default: return false, false, fmt.Errorf("%s is not a valid RetryPolicy", ...)
	}
}
```

**这是整个 RetryStrategy 里信息量最大的一段**：它把失败切成两个轴 ——

- **`Failed`** = 「跑了，结果不对」（容器非 0 退出）。`NodePhase` 注释
  `workflow_types.go:27-28`：`Failed = terminal: non-zero exit code`。
- **`Error`** = 「根本没跑起来 / 编排层出错」。`:29`：`Error = terminal: controller-level error
  unrelated to process exit`。

默认 `OnFailure` 的含义就是：**跑起来的失败重试，跑不起来的错不重试**。而我们项目里
「镜像拉不下来」「docker compose 起不来」「白名单拒绝」全属于 `Error` 类 —— 重试三次是三次同样的
错误。这一点第 5 节展开。

`Applied` 位置在 `operator.go:1170-1173`：

```go
if ((lastChildNode.Phase == wfv1.NodeFailed || lastChildNode.IsDaemoned() && (lastChildNode.Phase == wfv1.NodeSucceeded)) && !retryOnFailed) ||
	(lastChildNode.Phase == wfv1.NodeError && !retryOnError) {
	return woc.markNodePhase(ctx, node.Name, lastChildNode.Phase, lastChildNode.Message), true, nil
}
```

### 1.4 结构上的一个关键决定：重试是一个"父节点"

重试不是「节点原地重跑」，而是**一个 `NodeTypeRetry` 父节点 + N 个尝试子节点**
（`operator.go:1449`、`:2205`、`:2582`；`processNodeRetries` 的入参就是父节点，靠
`getChildNodeIdsAndLastRetriedNode` `:1075` 找出所有尝试）。好处：每次尝试是一次独立可查的历史，
失败消息保留在失败的那次上（`:1172` 把 `lastChildNode.Message` 抬到父节点）。

### 1.5 与 specflow 现状对比

| 字段 | 默认 | 语义（源码口径） | specflow 现状 |
|---|---|---|---|
| `limit` | **无（=无限重试）** | 重试次数，总尝试 = `limit+1`（`:2383-2385`、`:1184`） | **不存在**。无站点级重试计数器。 |
| `retryPolicy` | `OnFailure`；有 `expression` 时为 `Always`（`:2406-2414`） | `Failed`（跑了但不对）与 `Error`（没跑起来）分别授权（`:1221-1237`） | **不存在**。唯一近似是 ④ 的编译失败自动回喂（`web/Api.java:567`、`Dev.java` 40 轮上限），那是 Agent 轮次，不是重试。 |
| `backoff.duration` | 无（`nil` → 不等待） | 第一次退避时长，必填否则报错（`retry.go:24-26`） | **不存在**。 |
| `backoff.factor` | 无 | `duration * factor^(n-1)`（`retry.go:39`） | **不存在**。 |
| `backoff.cap` | 无 | 乘法结果封顶（`retry.go:47-53`） | **不存在**。 |
| `backoff.maxDuration` | 无 | **从第一次尝试起算的截止时刻**，不是每次等待（`retry.go:15-16`、`operator.go:1131`） | **不存在**。但已有同构的东西：`env/Budgets.java`（「这一步我愿意等多久」单一来源）+ `test/CaseBudget.java`（每条用例轮次预算）—— 我们其实已经有"总时长预算"这个概念，只是没用在重试上。 |
| `expression` | 无 | 求值为 false 则不重试（`operator.go:1189-1198`）；本地作用域由 `buildRetryStrategyLocalScope` 构造（`:2243`） | **不存在，也不该有**（见第 5 节）。 |
| `affinity.nodeAntiAffinity` | 无 | 靠 `kubernetes.io/hostname` 把重试打到别的机器（`retry_tweak.go:26-39`） | **不适用**。单机，无此概念。 |

---

## 2. 失败传播：ContinueOn / exit handler / 与闸的关系

### 2.1 ContinueOn 逐字段

`workflow_types.go:4026-4046`：

```go
type ContinueOn struct {
	// +optional
	Error bool `json:"error,omitempty"`
	// +optional
	Failed bool `json:"failed,omitempty"`
}

func continues(c *ContinueOn, phase NodePhase) bool {
	if c == nil { return false }
	if c.Error && phase == NodeError { return true }
	if c.Failed && phase == NodeFailed { return true }
	return false
}
```

**只有两个 bool，各自精确对应一个终态，不交叉。** `ContinueOn{nil}` = 不继续。挂载点：
`DAGTask.ContinueOn`（`workflow_types.go:3663-3665`）、`WorkflowStep.ContinueOn`。
`continues()` 只认 `NodeError` 和 `NodeFailed`，**不认 `Skipped`/`Omitted`** —— 因为这两者本来就
不算失败（见 2.3）。

### 2.2 传播路径：三处，都在"边界节点"上算

Argo 的节点树是 `DAG/Steps 边界节点 → 子任务节点`。失败不往上"冒泡"，而是**边界节点 assess
时读子节点的终态**。

**① Steps 模板** —— `workflow/controller/engine.go:389-418`，注释 `:377-388` 把规则写全了：

```go
if stepPhase.FailedOrError() && !step.ContinuesOn(stepPhase) && message == "" {
	failed := e.failedNode(node)
	phase, message = wfv1.NodeFailed, fmt.Sprintf("child '%s' failed", failed.ID)
	if failed.Phase == wfv1.NodeError {
		phase, message = wfv1.NodeError, fmt.Sprintf("step group deemed errored due to child %s error: %s", ...)
	}
}
```

要点：**「第一个没被 ContinueOn 放过的失败」决定整组**（`message == ""` 是"只认第一个"的守卫）；
且**保留 `Failed`/`Error` 的区别**往上传（失败 → 组是 `Failed`；出错 → 组是 `Error`）。

**② DAG 模板** —— `engine.go:1060-1104`：

```go
failFast := e.tmpl.DAG.FailFast == nil || *e.tmpl.DAG.FailFast      // :1082  默认 true
for _, name := range e.evaluator.GetTargetTasks(ctx) {              // :1085
	if e.getTaskNode(ctx, name) == nil { phase = wfv1.NodeRunning; if !failFast { break }; continue }
	if branch := e.branchPhase(ctx, name, memo); branch.FailedOrError() && !e.evaluator.GetTask(name).ContinuesOn(branch) {
		phase = branch                                              // :1093-1094
		if failFast { break }
	}
}
```

**③ 分支相位继承** —— `engine.go:1118-1134` `branchPhase`：一个任务自己完成后传自己的相位；
若它是 `Skipped`/`Omitted`/还在跑，就传它所依赖分支里**最坏**的相位（`dag.WorsePhase`）。这是
"`Skipped` 不该洗白一个失败分支"的实现。

`FailFast` 的精确语义很重要，`workflow_types.go:3608-3613` 的注释（原文）说：DAG 内置 fail-fast
会在**发现某个节点失败的瞬间停止调度新步骤**，然后**等所有已启动的节点都完成**，最后才把 DAG
判为失败。`FailFast: false` 则允许所有分支跑到底，不管失败结果。

### 2.3 三种"没跑"必须分清

`node_phase_sm.go:22-31` 的状态图注释是全仓最值得抄的一段文档：

```
Skipped   = terminal: when-clause evaluated to false
Omitted   = terminal: DAG depends condition was not met, a StepGroup whose every step was
            omitted, or a TaskGroup a hook error stopped before any item started
```

- **`Skipped`** = 条件主动判否（`when:` 求值为 false）。求值实现在
  `workflow/controller/dag/expansion.go:92-115`（`govaluate`，空 `when` 直接 true）。
- **`Omitted`** = 依赖条件**永远不可能满足**，不是"这次不跑"而是"这条分支废了"。
  创建点 `engine.go:140-150`，消息前缀 `"omitted: "` + 原因，缺省原因
  `"depends condition not met"`（`:144-147`）。
- **两者都算 workflow 成功**：`operator.go:465` `NodeSkipped → WorkflowSucceeded`、
  `:468` `NodeOmitted → WorkflowSucceeded`。

`node_phase_sm.go:32-70` 还有一张完整的状态迁移表，并且 `:9-21` 明确交代了它**不覆盖**哪些相位写入
（`markNodePhase` 之外的 `Nodes.Set` 路径），`:62-69` 声明终态无出边。这是把"不变量"写成代码而不是
写在 wiki 里的范例。

### 2.4 exit handler / LifecycleHook

类型 `workflow_types.go:1848-1858`：`Template`（要跑的模板名）、`Arguments`、`TemplateRef`、
`Expression`（条件）。

`docs/lifecyclehook.md` 把语义钉死（这份文档很短，71 行，直接读）：

| 行 | 规则 |
|---|---|
| `:7` | 每个 workflow/step/task **最多触发一次**；带 `withItems` 的展开任务**每个 item 各触发一次** |
| `:9` | 表达式满足后，hook **与它的 step/template 并行**跑 |
| `:11` | **名字叫 `exit` 的 hook 就是 exit handler**，其他名字无特殊含义 |
| `:13` | 依赖它的 steps/tasks **要等它所有 hook 结束**；step 的 exit hook 要等其 lifecycle hook 结束后才开始 |
| `:14` | hook 结束在 `Error`（起不来/`Pending` 超时）→ **判该 DAG/Steps 失败，且 `continueOn` 不覆盖它** |
| `:15` | hook 结束在 `Failed` → **忽略** |
| `:37` | **hook 里拿不到自己被 hook 那一步的 `outputs`**（执行期就开始跑，outputs 还没产出）；要用就用 exit handler |

`:14`+`:15` 这一对不对称（`Error` 致命、`Failed` 忽略）是刻意的：收尾钩子起不来，等于"该做的清理
没做"，比清理脚本自己返回非 0 更严重。

`onExit` 字段已废弃（`workflow_types.go:3667-3672`，注释原文 `Deprecated: Use Hooks[exit].Template instead.`），
但保留兼容 —— `GetExitHook` 里 `onExit` 优先于 `hooks[exit]`
（`DAGTask.GetExitHook` `:3696-3704`、`WorkflowStep.GetExitHook` `:1874-1882`）。

**注意 exit handler 不是 Workflow 层面的唯一收尾**：还有 `WorkflowSpec.OnExit`（workflow 级）、
`hooks`（workflow 级，可挂 `running` 等任意状态，见 `lifecyclehook.md:50-56` 的例子）。

### 2.5 关键设计：ContinueOn 与 depends 互斥

这是全篇最该记住的一条。`pkg/apis/workflow/v1alpha1/workflow_types.go:3619-3621`：

```go
// +kubebuilder:validation:XValidation:rule="!has(self.depends) || !has(self.dependencies)",message="cannot use both 'depends' and 'dependencies'"
// +kubebuilder:validation:XValidation:rule="!has(self.depends) || !has(self.continueOn)",message="cannot use 'continueOn' when using 'depends'"
// +kubebuilder:validation:XValidation:rule="!(has(self.depends) || has(self.dependencies)) || !self.name.matches('^[0-9]')",message="task name cannot begin with a digit when using 'depends' or 'dependencies'"
```

同一条规则在手写校验器里也有一份，`workflow/validate/validate.go:1476-1482`：

```go
if usingDepends && len(task.Dependencies) > 0 {
	return errors.Errorf(errors.CodeBadRequest, "templates.%s cannot use both 'depends' and 'dependencies' in the same DAG template", tmpl.Name)
}
if usingDepends && task.ContinueOn != nil {
	return errors.Errorf(errors.CodeBadRequest, "templates.%s cannot use 'continueOn' when using 'depends'. Instead use 'dep-task.Failed'/'dep-task.Errored'", tmpl.Name)
}
```

**理由**：`depends` 表达式里已经能写出 `.Failed` / `.Errored`，两种机制表达同一件事 = 两个真相，
必然出现过「我明明写了 continueOn 怎么没生效」。**宁可少一个能力，也不要两个入口。**

`depends` 表达式可用的八个结果，`workflow/controller/dag/types.go:46-57`：

```go
type taskResult struct {
	Succeeded bool `json:"Succeeded"`;  Failed   bool `json:"Failed"`
	Errored   bool `json:"Errored"`;    Skipped  bool `json:"Skipped"`
	Omitted   bool `json:"Omitted"`;    Daemoned bool `json:"Daemoned"`
	AnySucceeded bool `json:"AnySucceeded"`;  AllFailed bool `json:"AllFailed"`
}
```

语法上就是布尔组合（`workflow/common/ancestry.go:29-30`；测试
`workflow/controller/dag/ancestry_test.go:41`：`"(task-1 || task-2.AnySucceeded) && !task-3.Succeeded"`）。
`AnySucceeded`/`AllFailed` 是**展开任务专属**（按 item 聚合），所以校验器要求用这两个词的任务必须真的
有 `withItems`/`withParam`/`withSequence`（`validate.go:1506-1509`）。

### 2.6 与 specflow 的"闸"是什么关系

**它们不是同一个东西，这是最重要的一条对比。** 我们的闸：

`src/main/java/com/specflow/flow/Gate.java:26-34`

```java
@FunctionalInterface
public interface Gate {
    String blocked(Facts facts);   // 空串 = 能做；否则是直接给用户看的那句话
}
```

**闸 = 进入前的前置条件（precondition）**。Argo 里最接近的东西是 `when:`（→ `Skipped`）和
`depends` 表达式（→ `Omitted`），都是**能不能开始**。而 `ContinueOn`/exit handler 是
**结束了怎么算（postcondition）**。

对照表：

| 我们的概念 | Argo 最接近的机制 | 差异 |
|---|---|---|
| `Gate.blocked(facts)` 返回人话，非空即拦 | 无。Argo 只有布尔表达式，没有人话 | **我们更好**。Argo 的 `depends` 失败消息是自动拼的（`engine.go:410` `"step group deemed errored due to child %s error: %s"`），用户读不懂 |
| 闸只有一处判据（`Gates.TABLE`），前端算不了 | Argo 无"闸"概念；`when`/`depends` 在 YAML 里，前端（Argo UI）同样不判 | 同构 |
| 上一个站 `DONE` 才允许下一个（`Gates.java:47-54`） | `dependencies: [A]` | 我们是全序，Argo 是任意图 |
| 失败即停（无跳过） | `ContinueOn` / `depends` 里的 `B.Failed` | **我们缺这个**，见第 5 节 |
| 回退即作废（`FlowService.gotoNode:210-252`） | 无对应物（Argo 只有 `retryStrategy` 和 `argo retry` 整条重跑） | **我们独有**，且是正确的 |
| 无站点级收尾 | exit handler / `hooks[exit]` | **我们缺这个**，见第 5 节 |

---

## 3. 数据传递：parameter vs artifact，大小边界在哪

### 3.1 Parameter：小、具名、存进工作流对象本体

`workflow_types.go:1078-1104`：

```go
type Parameter struct {
	Name       string      `json:"name"`        // +kubebuilder:validation:Pattern=`^[-a-zA-Z0-9_]+$`
	Default    *AnyString   `json:"default,omitempty"`
	Value      *AnyString   `json:"value,omitempty"`
	ValueFrom  *ValueFrom   `json:"valueFrom,omitempty"`
	GlobalName string      `json:"globalName,omitempty"`
	Enum       []AnyString  `json:"enum,omitempty"`   // +kubebuilder:validation:MinItems=1
	Description *AnyString  `json:"description,omitempty"`
}
```

`AnyString` 就是 `type AnyString string`（`pkg/apis/workflow/v1alpha1/anystring.go:12`）—— 一个标量
字符串（带自定义 JSON 编解码以接受非字符串标量）。**`Parameter.Value` 装不下结构化数据**，只能装
JSON 序列化后的字符串。

`ValueFrom`（`:1106-1135`）是"值从哪来"的七选一：`Path`（容器里某个文件）、`JSONPath`、`JQFilter`、
`Event`、`Parameter`（引用别的节点的输出，`steps.mystep.outputs.myparam`）、`Supplied`（CLI/API 现填）、
`ConfigMapKeyRef`、`Default`（取不到时的兜底）、`Expression`。

`Parameter.GetValue()`（`:1141-1149`）的优先级：`Value` > `Default` > 空串。

### 3.2 Artifact：大、按路径、存进对象存储

`workflow_types.go:1154-1200`：`Name`、`Path`（容器内路径）、`Mode`（`0..511`）、`From`（引用前一步的
artifact）、内嵌 `ArtifactLocation`、`GlobalName`、`Archive`、`Optional`、`SubPath`、`RecurseMode`、
`FromExpression`、`ArtifactGC`、`Deleted`。

关键差别在"值"这个字段：**`Artifact` 没有 `Value`，只有 `Path`**。数据不在工作流对象里，在容器文件系统
里，由 executor 打包上传到 artifact repository。

`Artifact.CleanPath()`（`:1211-1249`）还额外做了目录穿越防护（`..` 不允许逃出安全子目录），并明确
处理了 Windows 路径分隔符（`:1231-1234`）。

### 3.3 边界到底在哪 —— 1MB，来自 etcd

`docs/offloading-large-workflows.md:3`（原文）：

> Argo stores workflows as Kubernetes resources (i.e. within EtcD). This creates a limit to their size as
> resources must be under **1MB**. Each resource includes the status of each node, which is stored in the
> `/status/nodes` field for the resource. This can be over 1MB. If this happens, we try and compress the
> node status and store it in `/status/compressedNodes`. If the status is still too large, we then try to
> store it in an SQL database.

配套证据：
- `docs/workflow-controller-configmap.md:191`：`NodeStatusOffload` —— 「saves node status only to the
  persistence DB **to avoid the 1MB limit in etcd**」。
- `docs/offloading-large-workflows.md:39`：超了会报 `Failed to submit workflow: etcdserver: request is too large.`
- `docs/memoization.md:48`：memoization 撞的是 **ConfigMap 的 1MB 上限**（另一个 1MB）。
- artifacts 侧无体积上限，走对象存储；默认 **tar 打包 + gzip**（`docs/artifacts.md:98`；
  `docs/configure-artifact-repository.md:417/822` 注释「by default, all output artifacts are automatically
  tarred and ...」）。

**所以 Argo 的实际分界线不是"参数 vs 产物"这个抽象，而是"会不会把工作流对象撑过 1MB"**：
- 参数值攒在 `/status/nodes` 里 → 全部参数 + 全部节点状态加起来 < 1MB，实际可用的每参数额度是**几 KB 量级**。
- artifact 只留一个路径引用 → 几十 GB 都行。

### 3.4 与 specflow 对比

我们已有的两样东西正好覆盖这两个生态位，且**不需要任何新机制**：

| Argo | specflow 现成物 | 评价 |
|---|---|---|
| `Parameter` / `GlobalName` / `ValueFrom.Parameter` | `Facts`（`flow/Facts.java`，站点间共享的小标量，如 `compilePassed`/`caseCount`/`frozen`）；`NodeView.blockedReason`（`FlowService.java:88-92`） | 同构，且我们的更强：Facts 是**有名字的类型化事实**，不是字符串 |
| `Artifact` / `ArtifactLocation` / `archive` / `ArtifactGC` | 项目目录本身就是共享区，站点产物直接落盘（`Snapshot.java` 写前快照、`CaseList` 清单文件） | 同构。**我们不需要"artifact"这一层抽象**，因为单机 + 同一个工作目录 = 文件系统就是传递介质 |
| 1MB 边界 | 无数据库 → 无 etcd → **无此边界**；状态落在文件里（`Store`/`Runs`） | **我们这边完全不存在这个问题** |

**结论（第 3 节）**：parameter/artifact 的二分在 Argo 里是**被基础设施逼出来的**（etcd 1MB + 对象存储）。
specflow 无数据库、单机、共享工作目录，这个二分对我们**没有借鉴价值**。唯一值得抄的是那条**纪律**：
「**小值随状态走，大块走引用**」—— 而我们已经天然做到了。

---

## 4. DSL 校验的做法，与我们 env.yaml 的校验器对比

### 4.1 Argo 是四层，不是一层

| 层 | 位置 | 管什么 | 报错时机 |
|---|---|---|---|
| **L1 CRD 结构 schema** | `pkg/apis/workflow/v1alpha1/openapi_generated.go`（生成的，8900+ 行）；源标记在 `workflow_types.go` 的 `+kubebuilder:*` | 类型、枚举、正则、长度、条数上下限 | apiserver 拒绝 |
| **L2 CEL 跨字段规则** | `workflow_types.go:3619-3621` 的 `+kubebuilder:validation:XValidation` | 「`depends` 与 `dependencies` 不能同时用」这类**同一对象内**的互斥 | apiserver 拒绝 |
| **L3 手写 Go 语义校验** | `workflow/validate/validate.go`（**1747 行**） | 模板存在性、环检测、拓扑排序、变量能否解析、参数/产物名字符集 | lint / submit 时 |
| **L4 运行时** | controller / engine | 表达式求值、模板解析失败 | 执行中 |

**L1 到底标记了多少"必填"？** 全文件 `Required: []string{...}` 只有 **71 处**，且绝大多数不是模板相关的。
真正与工作流模板相关的是：

- `openapi_generated.go:2578` `Required: []string{"name"}` —— `DAGTask`（函数定义在 `:2452`）
- `openapi_generated.go:2628` `Required: []string{"tasks"}` —— `DAGTemplate`（函数定义在 `:2586`）

而 **`Template`（定义在 `:6625`）本身一个 `Required` 都没有** —— 连 `Template.name` 都不是 schema 必填
（Go 里是 `json:"name,omitempty"`，`workflow_types.go:710`）。它靠 L3 手写兜：
`validate.go:649-651` 是 `templateRef` 的
`"resource name is required"` / `"template name is required"`，模板名的字符集靠
`isValidWorkflowFieldName`（`:1721-1738`）、`isValidStepName`（`:1740-1744`）。

**这说明什么**：即使是一个被几千个团队用的编排引擎，**schema 能表达的"结构必填"极其有限（71 处），
真正的规则 99% 在 1747 行手写 Go 里。** 因为工作流的约束绝大多数是**关系型**的：
「模板名 X 存在吗」「这张图有环吗」「这个依赖在不在前面」「这个变量在那一步的 scope 里可见吗」——
没有一个能写成 `Required`。

看 `validateDAG`（`validate.go:1437-1516`）就知道手写部分在干什么：

```go
err = sortDAGTasks(ctx, tmpl, dagValidationCtx)            // :1458  拓扑排序（顺带发现环/未定义依赖）
err = validateWorkflowFieldNames(tmpl.DAG.Tasks)            // :1463  名字合法性与唯一性
...
for depName, depType := range dagValidationCtx.GetTaskDependenciesWithDependencyTypes(ctx, task.Name) {
	task, ok := dagValidationCtx.tasks[depName]
	if !ok {
		return ... "templates.%s.tasks.%s dependency '%s' not defined"   // :1503-1505  ← 关系型
	} else if depType == common.DependencyTypeItems && len(task.WithItems) == 0 && task.WithParam == "" && task.WithSequence == nil {
		return ... "dependency '%s' uses an items-based condition such as .AnySucceeded or .AllFailed but does not contain any items"  // :1506-1509 ← 跨字段+跨对象
	}
}
if err = verifyNoCycles(ctx, tmpl, dagValidationCtx); err != nil { return err }   // :1514  ← 图算法
```

### 4.2 但 Argo **不做"一次报全"**

`workflow/validate/validate.go` 里所有函数返回的是 `error`，**不是 `[]error`，遇到第一个就 return**
（上引 `:1443`、`:1460`、`:1465`、`:1473`、`:1477`、`:1481`、`:1487`、`:1497`、`:1504`、`:1508` 全是
`return errors.Errorf(...)`）。服务端 `server/workflow/workflow_server.go` 的 `LintWorkflow` 也只有
一次 `validate.Workflow(...)`，失败就 `return nil, err`。

CLI 侧 `cmd/argo/lint/lint.go:111-142` `Lint` 对**每个文件**建一个 `Result{File, Errs}`，看起来像聚
合 —— 但每个对象的 `Errs` 最多一条（`:222-224`），因为底层只回一个错。所以 Argo 的 lint 体验是
**「改一个 → 再 lint → 发现下一个」**。

另外注意 `cmd/argo/lint/lint.go:31-41` 有 `Strict bool` 选项，透传到
`common.ParseObjects(ctx, data, opts.Strict)`（`:150`）—— **未知字段默认不报错**（K8s 结构化 schema
静默剪枝），要显式开 strict。

### 4.3 我们的校验器做了什么

`src/main/java/com/specflow/env/EnvConfigLoader.java`（465 行）：

| 能力 | 位置 | Argo 对应 | 谁更好 |
|---|---|---|---|
| **一次报全**：`List<String> problems` 攒完再一次性抛（`"%s 有 %d 处问题，改完再试：\n  "` + join） | `:95`、`:109-112` | 无（只报第一个） | **我们明显更好** |
| **带行号**：`Lines.of(source)` 走一遍词法记号流建 `path → 行号` 映射，结构校验仍然走树 | `:382-430`（注释 `:38-39` 解释"两边各走各的"）、`:376-380` `at()` | 无（Argo 报 `templates.foo.tasks.bar` 路径，没有行号） | **我们明显更好** |
| **未知字段报错**：`known()` 遍历 `fieldNames()`，多一个就报 | `:350-362` | 默认静默剪枝，需 `--strict` | **我们更好**（配置是手写的，拼错一个字母静默失效最难查） |
| **关系型校验**：`service:` 必须出现在 `dependencies:` 里，报错时列出所有合法值 | `:334-344` | 同构（`validate.go:1500-1511`） | 持平，我们的提示更好（列出可选项） |
| **语义校验**：`workdir` 必须以 `/` 开头，并解释"相对路径会被 compose 当成命名卷" | `:184-188` | 同构（Argo 也多处解释原因） | 持平 |
| **`Line` 精确到重复键**：`FIELD_NAME` 用覆盖（`putIfAbsent` 不用），同一键写两遍时行号指到最后一行 | `:417-421` 注释 | 无 | **我们更好**，这是个很细的坑 |
| 一份源码两个入口（读盘 / 界面 AI 草稿），同一套规则 | `:64-76` `load` vs `:79-114` `parse` | Argo 也是同一套（`LintWorkflow` 与 submit 共用 `validate.Workflow`） | 持平 |
| 没有 schema 文件，规则写在 Java 里 | 全文 | 四层 | 这是取舍，不是缺陷 |

### 4.4 结论（第 4 节）

**Argo 的四层校验对我们唯一有价值的启示是"分层"这个念头，而不是任何一层本身**：

1. **L1 schema 层的价值被高估了**：71 处 `Required` vs 1747 行手写 Go。对 specflow 的流程 DSL 而言，
   结构必填大概只有 `station.name`、`station.gate`，写完不到 10 行 Java —— 引入 JSON Schema/YAML
   schema 只会换来一个"两个地方定义同一个规则"的新问题。
2. **L2 CEL 明确不要抄**。Argo 需要 CEL 是因为它必须让规则在 apiserver 侧生效（L3 的 Go 代码跑在
   controller 里，拦不住 `kubectl apply`）。specflow 只有一个进程，L3 就够了 —— 这也是为什么
   `continueOn`+`depends` 互斥规则在 Argo 里**同时**写在 CEL（`:3620`）和 Go（`:1481`）两处，
   这恰恰是我们应该避免的重复。
3. **我们已有的三件事应当保留并当成标准**：一次报全、带行号、未知字段报错。这三件是 Argo 没做的，
   而且理由充分（它的用户是 YAML 老手 + 有 IDE 插件；我们的用户是"刚建完文件点不开项目"的人）。

---

## 5. 值不值得借鉴 A：重试与跳过语义

### 结论

**值得借鉴，但要"拆件抄"，不要"整套抄"。** 具体：

| 件 | 抄不抄 | 理由 |
|---|---|---|
| `retryPolicy` 的 **`Failed` / `Error` 二分**，默认 `OnFailure` | ✅ **抄** | 见 5.1，这是最便宜、收益最高的一条 |
| `backoff.maxDuration` = **从第一次尝试起算的绝对截止时刻** + "等到下次会越过截止点就不等了" | ✅ **抄** | 见 5.2 |
| `limit` 的口径 = **重试次数**（总尝试 = `limit+1`），且**必须有默认值** | ✅ 抄口径，但**必须补 Argo 的坑** | 见 5.3 |
| `backoff.duration` / `factor` / `cap` | 🟡 抄，10 行代码 | 有 `Budgets` 现成概念，接上即可 |
| `ContinueOn{error,failed}` 两个 bool | 🟡 **只抄"允许指定在什么终态下继续"这个形状** | 见 5.4，我们的闸是前置条件，形态不同 |
| **站点级 exit handler / `hooks[exit]`** | ✅ **这是最该抄、且我们现在完全缺的** | 见 5.5 |
| `expression`（求值决定要不要重试） | ❌ **不抄** | 见 5.6 |
| `affinity.nodeAntiAffinity` | ❌ **不抄** | 单机无意义 |
| `depends` 八种结果（`AnySucceeded`/`AllFailed`/…） | ❌ **不抄** | 见 5.7 |

### 5.1 为什么 `Failed`/`Error` 二分是最高性价比的一条

我们的站点动作失败，实际可分成两类，而且**代码里已经有能力区分**：

| 类 | specflow 里的实际例子 | Argo 对应 | 重试有用吗 |
|---|---|---|---|
| **Error（没跑起来）** | 镜像拉不下来、`docker compose up` 失败、`workdir` 不是绝对路径、`service:` 指向不存在的依赖、白名单拒绝、容器 OOM 起不来 | `NodeError` | **没用**。同样的输入必然同样的错，重试 N 次 = N 次相同的错误 + N 倍等待 |
| **Failed（跑了但结果不对）** | 编译没过、用例失败、探针没挑出文件 | `NodeFailed` | **可能有用**（flaky、临时资源竞争） |

Argo 用 `retryPolicyAllows`（`operator.go:1221-1237`）把这件事变成一个 4 值枚举，默认值
`OnFailure` 就是「只重试 Failed」。**代价**：一个枚举 + 一处 if。**收益**：省掉绝大部分无意义重试，
以及随之而来的"用户盯着进度条等了三轮才发现是镜像名写错"。

对我们的落地形状（一行判据，不需要枚举四值）：

```java
// FAILED = 跑了但结果不对；ERROR = 根本没跑起来。ERROR 不重试。
enum FailureType { FAILED, ERROR }
```

我们的失败本来就已经分类过（`CommandGuard` 三档判定、`EnvCli` 的退出码、`SandboxTest` 的 fail-closed），
只是没把"可重试性"这个维度接出来。

### 5.2 为什么 `backoff.maxDuration` 值得抄 —— 它治的是"总时长"而不是"次数"

`:15-16` 的注释是这条的全部精髓：

> `backoff.maxDuration` is a deadline measured from the first attempt, not a per-attempt wait.

再配合 `operator.go:1146-1148` 那个额外的短路 —— **"如果等到下次就已经越过截止时刻，那现在就放弃，
别等了"** —— 这两条加起来定义了一个用户在意的语义：**「这件事最多纠缠 N 分钟，到点就认输」**。

比 `limit: 3` 好在哪里：`limit: 3` 配 `factor: 2` + `duration: 30s`，总时长 = 30+60+120 = 210 秒，
但这个数字**不在配置里**，用户得自己算。`maxDuration: "5m"` 是直接可读的承诺。

而 specflow 已经有这个概念了 —— `env/Budgets.java` 就是「这一步我愿意等多久」的**单一来源**，
`test/CaseBudget.java` 是每条用例的轮次预算。**把 `maxDuration` 接进 `Budgets` 是补一条规则，不是加一层机制。**

### 5.3 `limit` 的口径要抄，实现上的坑要躲

口径（`workflow_types.go:2383-2385`）：「`Limit` 不含原始那次；总尝试次数是 `limit + 1`」。执行侧一致：
`operator.go:1184` 用 `len(childNodeIds) > *limit` 判放弃（`childNodeIds` 含原始尝试）。

**但 Argo 在这里留了个地雷**：`Limit == nil` 时 `:1184` 的守卫整条跳过 → **无限重试**。而 `Backoff`
为 nil 时连等待都没有 → **热循环无限重试**。这是"可选 + 无默认"导致的经典事故。

我们要抄的是口径，要躲的是"没写就当无上限"：**`limit` 必须有一个有限默认值（比如 0 或 1），
不能是"没写 = 无限"。**

### 5.4 `ContinueOn` 的形状值得抄：**只允许显式声明在哪一种终态下继续**

`ContinueOn` 只有两个 bool，`continues()` 精确匹配一个终态、不交叉（`workflow_types.go:4035-4046`）。
好处是**没有任何隐式行为**：不写就是"不继续"，写了 `failed: true` 就只放过 `Failed`，`Error` 照样停。

对 specflow 的映射：我们的闸是前置条件，那"跳过"就应该是**闸的一条显式出路**。
好消息是 —— 我们已经有这个模式了：`/api/node/env/skip`（`web/Api.java:348-354`）写
`envNotNeeded=true`，闸的 `env` 实现里读它放行（`Gates.java:99-116` 的「环境起过 **或** 声明不需要」）。
**这就是我们版本的 `ContinueOn`，而且比 Argo 更好**：它不是一个新字段，是闸的一个判据，
所以**闸仍然只有一处真相**。

**所以第 5.4 的结论是否定的那半**：不要新增一个 `Node.continueOn` 字段 —— 那会在
`Gate.blocked()` 之外开第二个"能不能往下走"的判断入口，直接违反 `ARCHITECTURE.md:88`
「闸只有一处判据」。要加跳过，就加进对应站的 `Gate` 实现。

### 5.5 exit handler 是真正缺失的一块（最该抄）

现状（第 8 节证据）：**全仓 `onExit` 0 命中；没有站点级统一收尾；`Main.java` 无 shutdown hook；
`FlowService.close():267-272` 存在但无调用方；`Journal.sweep()`（`Journal.java:89`，用于扫
"没有 END 的运行"）**定义有、从未被调用**。**

Argo 的 exit handler 语义里，对我们直接可用的是这三条：

1. **`lifecyclehook.md:11`：名字叫 `exit` 的 hook 就是 exit handler** —— 收尾不是一类特殊对象，
   就是"挂在这个节点上的一个动作，条件永远是'我结束了'"。
2. **`lifecyclehook.md:7`：每个 step/task 最多触发一次** —— 幂等保证来自"记一笔已触发"，而不是靠
   收尾动作本身幂等。这点对我们极重要：我们的收尾动作是 `docker compose down` / 删容器副本，
   本身不幂等。
3. **`lifecyclehook.md:14`：hook 自己 `Error` → 致命，且 `continueOn` 不覆盖它；`Failed` → 忽略。**
   这条对我们最有用：我们的收尾动作是"把容器副本删掉"。如果删失败了（`Error`）—— 那台机器上就
   留着垃圾，比主流程失败更需要人知道。如果 `docker compose down` 返回非 0 但其实清理完了（`Failed`）
   —— 不该因此判这一步失败。

落到我们的形状：**一个站点动作结束时，无论成功/失败/抛异常，都跑一次该站的 `cleanup`，
`cleanup` 失败要单独上报（不吞、也不改主结论）。** 现在 `Api.java` 里每个站点动作都是
`progress.fail(...)` + 写 fact 就返回，半成品留在盘上要人自己点 `/api/flow/goto`（证据见第 8 节）
—— 这正是 exit handler 要解决的病。最小实现就是 `try/finally` + 一个 `Closeable`，我们已经有范例：
`test/TestSandbox.java:41 implements AutoCloseable` + `close():130`，用 try-with-resources 在
`Api.java:660` 调用 —— **把这个模式从「⑤ 测试」推广到「所有站点」即可，不需要任何新框架。**

### 5.6 `expression` 明确不抄

`RetryStrategy.Expression` 是一个通用表达式求值口（`operator.go:1189-1194`，作用域由
`buildRetryStrategyLocalScope` `:2243` 构造）。Argo 为此付出的代价：

1. 引入第三个表达式语言（`govaluate` 用于 `when`，`expr-lang` 用于 `expression`/`event`，CEL 用于
   校验），`workflow_types.go:1117` 注释里直接写着 `Selector (https://github.com/expr-lang/expr)`。
2. `RetryPolicyActual()`（`:2406-2414`）那个"写了 expression 就偷偷变成 Always"的隐式翻转 ——
   纯粹是两个开关互相影响造成的理解成本。
3. 表达式求值失败会让节点进 `Error`（`engine.go:131-138`），把"配置写错"变成"运行期错误"。

**我们的用户是"手写三五行 env.yaml 还要行号提示"的人**，不是写 CEL 的人。给一个表达式口只会换来
一堆"为什么我的表达式求值是 false"。要有限的重试条件，就再给一个枚举值 —— 这是
`retryPolicy` 四值枚举（`Always`/`OnFailure`/`OnError`/`OnTransientError`）教我们的：
**能力用枚举表达，不用语言表达。**

### 5.7 depends 八种结果不抄

`taskResult` 的八个字段（`dag/types.go:48-57`）+ `depends` 的布尔语法
（`common/ancestry.go`，测试 `ancestry_test.go:41`：`"(task-1 || task-2.AnySucceeded) && !task-3.Succeeded"`）
是为**任意有向图和 fan-out 聚合**服务的。specflow 的流程是 `Node.previous() == values()[ordinal()-1]`
（`Node.java:57-59`）—— **全序，一条链**。在链上，「前一步失败后我要不要继续」只有一个问题，
答案只有一个 bool；给八种结果 + 布尔语法是拿加农炮打蚊子，而且会立刻违反"闸只有一处判据"。

---

## 6. 值不值得借鉴 B：把"流程"做成 YAML

### 结论

**不值得。而且我认为这个问题问反了 —— 你真正想解决的"多处硬编码"，YAML 不解决，反而会加重。**

理由是五条，每条都有代码证据。

#### 理由 1：会变成**第五份**真相，而现有的四份才是病根

现在站点的"真相"分散在：

| # | 位置 | 内容 |
|---|---|---|
| 1 | `src/main/java/com/specflow/flow/Node.java:21-31` | 五个站点 + 顺序（`ordinal()` 即步骤号） |
| 2 | `src/main/java/com/specflow/flow/Gates.java:64-69` | `Map<Node, Gate>` 派发表 |
| 3 | `src/main/java/com/specflow/web/Api.java` | 每站动作的路由注解（env `:214/246/267/322/348/361/454`；probe `:373/385/390/482`；cases `:413/425/521`；dev `:555/598/824`；test `:616/634/704/716/732/762/771`） |
| 4 | 前端四张表 | `lib/flow.js:4-10`（`NODES`）、`lib/pages.js:4-12`（`PAGES`）、`router.js:11`（站点→组件）、各视图自带动作数组（`EnvNode.vue:108-124`、`ProbeNode.vue:88-95`、`CasesNode.vue:100-108`、`DevNode.vue:57-62`、`TestNode.vue:70-76`）+ `lib/facts.js:61-70`（`KNOWN_FACTS`） |

`Gate.java:9-12` 的注释自己承认了这件事：「加一站必须同步改 6 个落点，缺一处不会报错，只会表现为
'这一格永远不动'」。**加一个 `flow.yaml` 是第 7 个落点，不是第 1 个。**

#### 理由 2：前端**必然**还是硬编码，YAML 去不掉它

`router.js:11` 是 `{env: EnvNode, probe: ProbeNode, cases: CasesNode, dev: DevNode, test: TestNode}` ——
一个字符串到 Vue 组件的静态映射。**Vue 组件不能被 YAML 里的字符串动态拉进来**：打包期就要确定
import（这是 Vite 静态分析的硬约束）。所以哪怕流程完全由 YAML 描述，前端仍然需要这张表，
YAML 只是把「表里有什么」从编译期变成运行期 —— 而站点动作（按钮、菜单、facts 徽章）是
**每站 bespoke 的 Vue 代码**（`EnvNode.vue:108-124` 有 6 个动作，`CasesNode.vue:100-108` 只有 1 个主
动作 + 2 个菜单），根本不可能由一致的 YAML 结构描述。

**只有当所有站点的 UI 长得一模一样时，YAML 驱动才有意义。而它们不是，也不该是。**

#### 理由 3：我们的图是**一条链**，Argo 的 DSL 复杂度全在"图"上

`Node.java:57-59`：`previous()` = `values()[ordinal()-1]`。`Gates.java:47-54` 的顺序闸只看
`node.previous()` 是否 `DONE`。`FlowService.refresh()`（`:293-308`）按枚举顺序前向重算。

Argo DSL 里有而链式流程需要的：

| Argo 字段 | 为什么存在 | 链式流程需要吗 |
|---|---|---|
| `DAGTask.Dependencies []string` | 任意前驱集合 | ❌ 只有一个前驱 |
| `DAGTask.Depends string`（布尔表达式 + 8 种结果） | 条件性依赖 | ❌ |
| `DAGTemplate.Target` / `FailFast` | 多终点、并行分支 | ❌ |
| `WithItems` / `WithParam` / `WithSequence` | fan-out | ❌（我们的 fan-out 在**站点内部**：用例清单、文件清单） |
| `Steps` 的"list of lists"（`workflow-concepts.md:145-161`） | 串行组 + 并行组 | ❌ |
| `validate.go:1629` `verifyNoCycles` | 环检测 | ❌ 全序不可能有环 |
| `validate.go:1458` `sortDAGTasks` | 拓扑排序 | ❌ 顺序就是 `ordinal()` |

**为一条链引入一套图引擎，是全部成本、零收益。**

#### 理由 4：闸是**函数**，不是数据

`Gate.java:26-34` 的接口是 `String blocked(Facts facts)` —— 返回**给人看的那句话**，空串才放行。
`Gates.java:99-116` 的 env 闸是三条有序判断：

```
编译命令非空 → (环境起过 或 声明不需要) → 容器里真能构建
```

`Gates.java:119-158` 另外四个站各是一段 bespoke 逻辑，读 `Facts` 里的字段组合
（`caseCount`、`compilePassed`、`rounds`、`testPassed`、`frozen`…）。

**这些逻辑不可能写成 YAML**，除非再给 YAML 一个表达式语言 —— 那就是第 5.6 节明确不要抄的东西。
Argo 正是走到了那一步：为了在 YAML 里表达条件，它需要 `govaluate`（`when`）+ `expr-lang`
（`expression`/`event`）+ CEL（校验），然后**仍然**要写 1747 行 Go 校验
（`workflow/validate/validate.go`）。**YAML 化流程不会消掉闸的代码，只会给它套一个解析器。**

#### 理由 5：YAML 化会削弱闸的**唯一真相**保证

`ARCHITECTURE.md:88` 的不变量 5：「闸只有一处判据，前端一行判闸逻辑都不许有」。
`GatesTest`（14 个测试）是这个不变量的唯一钉子，也是全仓唯一被测试钉死的核心类。

一旦流程可配置，用户能写出 `Gates.TABLE`（`Gates.java:64-69`，`TABLE.get(node)` 为 null 就
`IllegalStateException`，`:81-84`）里没有登记的站点 —— 那我们就要么在加载时硬校验"每个站必须有闸"
（那就等于把 `Node` 枚举换个写法写进 YAML 校验器），要么允许无闸站点（那就是**闸不再是判据**，
整条不变量崩塌）。**两条路都比现状差。**

### 那"多处硬编码"该怎么办？

**把现有的四份压成一份，而不是引入第五份。** 具体做法（这条比 YAML 化便宜得多）：

后端已经有一个天然的单一出口 —— `FlowService` 组的 `NodeView`（`FlowService.java:88-92`，含
`blockedReason`，前端只读 `NodeShell.vue:44`）。把**站点清单**也从这个接口下发：

```json
// GET /api/flow 已经返回的东西，补上站点数组
{ "nodes": [ { "key":"env", "seq":"①", "name":"环境", "route":"/env",
               "state":"READY", "blockedReason":"", "facts":["envReady","compilePassed"] }, ... ] }
```

前端把四张表（`flow.js` / `pages.js` / `router.js` 的 key 部分 / `facts.js` 的 `:facts`）
改为读这一个接口，**`router.js` 的 key→组件映射保留**（那是打包期约束，去不掉，但它会缩成一行、
且只在 `key` 新增时才需要动）。

这样：站点集合仍然是 `Node.java:21-31` 一处定义（**Java 枚举就是我们的 schema**，而且它已经有
`previous()`/`from()`/`of()` 三个方法在提供"关系"），4 份 → 1 份 + 1 行路由表，
**且不需要任何解析器、不需要任何新校验器、不需要给用户一个能写坏的东西。**

### 一句话

> **`env.yaml` 之所以值得做成 YAML，是因为它的内容是真·用户数据（镜像名、依赖、初始化命令），
> 每个项目都不一样，硬编码不可能。而"流程"是程序的结构 —— 它和 `Api.java` 的路由、
> `EnvNode.vue` 的按钮、`Gates.java` 的判据是同一件事，把它们拆成 YAML + 代码两份，只会让
> "这一格永远不动"这类故障从 6 个落点变成 7 个，且第 7 个是运行期才发现的。**

---

## 7. Java 最小吸收草图

只吸收第 5 节判定为 ✅ 的部分：`Failed`/`Error` 二分、`limit` 口径（含有限默认值）、
`duration*factor^(n-1)` + `cap` 封顶、`maxDuration` 作为**从第一次尝试起算的截止时刻**。
不引入表达式语言、不引入新 DSL、不新增配置字段之外的机制。

```java
/** 只回答「要不要再试一次、等多久」。判据与执行分开：不执行、不改状态、不抛异常。 */
public final class Retry {
    /** 抄 retryStrategy.retryPolicy 的默认值 OnFailure：跑了但不对→重试；没跑起来→不重试。 */
    public enum Policy { ON_FAILURE, ON_ERROR, ALWAYS }
    /** limit 是「重试次数」，总尝试 = limit+1；必须给有限默认值，不能像 Argo 那样 nil=无限。 */
    public record Strategy(int limit, Duration base, int factor, Duration cap, Duration maxTotal) {
        public static Strategy none() { return new Strategy(0, Duration.ZERO, 1, null, null); }
    }
    /** again=true 时 wait 是这次该等多久；why 直接给用户看（与闸口径一致：给结论不给猜测）。 */
    public record Verdict(boolean again, Duration wait, String why) {}
    // attempts=已失败次数；startedAt=第一次尝试的开始（maxTotal 从这里起算，retry.go:15-16）
    public static Verdict next(Strategy s, Policy p, int attempts,
                               Instant startedAt, Instant now, FailureType type) {
        if (p == Policy.ON_FAILURE && type == FailureType.ERROR)
            return new Verdict(false, Duration.ZERO, "这一步没跑起来，重试也是同一个错");
        if (attempts > s.limit())                                  // 抄 operator.go:1184 的口径
            return new Verdict(false, Duration.ZERO, "重试次数用完了（已试 " + attempts + " 次）");
        Duration wait = s.base().multipliedBy((long) Math.pow(s.factor(), attempts - 1)); // retry.go:39
        if (s.cap() != null && wait.compareTo(s.cap()) > 0) wait = s.cap();               // retry.go:47
        if (s.maxTotal() != null && now.plus(wait).isAfter(startedAt.plus(s.maxTotal())))
            return new Verdict(false, Duration.ZERO, "总时长到顶了，再等也是白等");       // op.go:1146
        return new Verdict(true, wait, "第 " + (attempts + 1) + " 次，等 " + wait.toSeconds() + " 秒");
    }
}
```

24 行。配套（不在 25 行预算内，但必须一起做）：

- `FailureType` 与现有分类对接：`CommandGuard` / `EnvCli` / `SandboxTest` 的失败已经能分出
  「跑了但不对」和「没跑起来」，加一个字段而不是加一套判定。
- `maxTotal` 从 `env/Budgets.java`（「这一步我愿意等多久」的唯一来源）取，**不新增配置项**。
- exit handler 那一半（第 5.5 节）不需要新类：把 `TestSandbox implements AutoCloseable`
  （`test/TestSandbox.java:41,130`，已经在 `Api.java:660` 用 try-with-resources）这个模式推广到
  所有站点动作的 `try/finally`。

---

## 8. 未核实点 + 证据清单

### 8.1 未核实 / 证据强度不足（不要当作结论用）

1. **`OnTransientError` 的具体判据没读**。`operator.go:1228` 调
   `errorsutil.IsTransientErr(ctx, argoerrors.InternalError(lastChild.Message))` —— 我只确认了调用点，
   没读 `IsTransientErr` 的实现（它在 `util/errors` 或 vendor 的 `k8s.io/apimachinery` 侧）。所以
   「什么算 transient」我**没有**一手结论。本文因此没推荐抄这个枚举值。
2. **`markNodePhase` 的完整实现未逐行读**。我读的是它被 `node_phase_sm.go:9-21` 描述的行为
   （哪些迁移会被拒、哪些路径绕过它）。`operator.go` 里 `markNodePhase` 的真实定义行号我未定位。
3. **没有任何一个结论来自实际运行 Argo**。全部是读 `main` 分支源码 + 其自带文档。行为层面
   （如 `requeueAfter` 的实际时序、hook 与主流程的并行度）我引用的是注释与文档的自述。
4. **参数体积的"1MB"是推断，不是直接表述**。`docs/offloading-large-workflows.md:3` 说的是**整个
   workflow 资源**必须 < 1MB，参数值存在 `/status/nodes` 里是这个文件第 3 行明说的。我据此推
   「参数可用额度是几 KB 量级」。**我没有找到任何一份文档给出"单个参数最多多少"的明确数字**
   （全 docs 搜 `size limit|1MB|large parameter` 无直接命中）。
5. **`Template` 无 `Required` 是我从生成文件结构判断的**：`schema_pkg_apis_workflow_v1alpha1_Template`
   在 `openapi_generated.go:6625`，其后到下一个函数之间的 `Required:` 出现在 `:7066`（且内容是
   `{"expression"}`，属另一个类型）。我没有逐字段核对 `Template` 的完整 schema 块，只是确认了
   「它自己那一块里没有 `Required: []string{"name"}`」。**`Template.name` 在 schema 层非必填**这个
   结论，反证是 `workflow_types.go:710` 的 `json:"name,omitempty"`。
6. **`docs/fields.md` 的 `RetryStrategy` 表格没有独立信息**。我核对了
   `docs/fields.md:1868-1872`（`affinity`/`backoff`/`expression`/`limit`/`retryPolicy` 五行）与
   `:3103-3109`（`Backoff` 四字段），内容与 Go 注释逐字一致 —— 它是从 Go 注释生成的，**没有额外
   语义说明**。所以本文的默认值全部取自**代码**（`RetryPolicyActual`、`RetryBackoffWait`、
   `processNodeRetries`），不是取自文档。
7. **`failFast` 的默认值我采信了两处独立证据但没跑**：类型注释
   `workflow_types.go:3611`（"The FailFast flag default is true"）+ 代码
   `engine.go:1082`（`e.tmpl.DAG.FailFast == nil || *e.tmpl.DAG.FailFast`）。两处一致。
8. **specflow 侧的证据全部来自只读勘查**（子代理完成，未改动任何文件）：`Node.java`、`Gates.java`、
   `Api.java`、`FlowService.java`、前端四张表、`Journal.sweep()` 无调用方、`Main.java` 无 shutdown hook
   —— 这些是我基于该勘查结论引用的，**我本人没有逐一打开这些 specflow 文件复核行号**。
   引用它们时请以"勘查报告"为证据强度参考，而非"我已亲读"。

### 8.2 证据清单（Argo 侧，全部亲读）

| # | 文件:行 | 支撑本文的哪个结论 |
|---|---|---|
| 1 | `pkg/apis/workflow/v1alpha1/workflow_types.go:2381-2399` | §1.1 `RetryStrategy` 五字段原文 |
| 2 | `.../workflow_types.go:2344-2353` | §1.1 `RetryPolicy` 四值枚举 + `+kubebuilder:validation:Enum` |
| 3 | `.../workflow_types.go:2355-2370` | §1.1 `Backoff` 四字段 |
| 4 | `.../workflow_types.go:2372-2379` | §1.1/§5 `RetryAffinity` 是空占位结构体 |
| 5 | `.../workflow_types.go:2401-2414` | §1.2 `RetryPolicyActual()` 三段式默认值（expression→Always） |
| 6 | `.../workflow_types.go:2383-2385` | §5.3 `limit` 不含原始尝试，总尝试 = limit+1 |
| 7 | `.../workflow_types.go:4026-4046` | §2.1 `ContinueOn` 两字段 + `continues()` 精确匹配 |
| 8 | `.../workflow_types.go:1848-1858` | §2.4 `LifecycleHook` 四字段 |
| 9 | `.../workflow_types.go:3663-3679` | §2.4 `DAGTask.ContinueOn/OnExit/Hooks` |
| 10 | `.../workflow_types.go:3696-3708` | §2.4 `onExit` 优先于 `hooks[exit]` |
| 11 | `.../workflow_types.go:1874-1882` | §2.4 `WorkflowStep` 同构逻辑 |
| 12 | `.../workflow_types.go:3619-3621` | §2.5 `depends` × `continueOn` 互斥的 CEL 规则 |
| 13 | `.../workflow_types.go:3608-3614` | §2.2 `FailFast` 语义与默认 true |
| 14 | `.../workflow_types.go:706-810` | §4.1 `Template` 字段（含 `RetryStrategy` `:794`、`FailFast` `:804`、`+kubebuilder` 标记） |
| 15 | `.../workflow_types.go:3595-3615` | §4.1 `DAGTemplate`（`Target`/`Tasks` MinItems=1 MaxItems=200/`FailFast`） |
| 16 | `.../workflow_types.go:3617-3680` | §1/§6 `DAGTask` 全字段 + 三条 CEL |
| 17 | `.../workflow_types.go:1078-1135` | §3.1 `Parameter` / `ValueFrom` |
| 18 | `.../workflow_types.go:1154-1200` | §3.2 `Artifact`（无 `value`，只有 `path`） |
| 19 | `.../workflow_types.go:1211-1249` | §3.2 `CleanPath()` 目录穿越防护 + Windows 分隔符 |
| 20 | `pkg/apis/workflow/v1alpha1/anystring.go:12` | §3.1 `AnyString` 是标量字符串 |
| 21 | `workflow/common/retry.go:12-55` | §1.3 退避公式、溢出保护、`cap`、`maxDuration` 非每次等待 |
| 22 | `workflow/controller/operator.go:1119-1162` | §1.3 maxDuration 截止时刻、提前放弃、`requeueAfter` |
| 23 | `workflow/controller/operator.go:1184-1187` | §1.3/§5.3 `len(children) > limit` → "No more retries left" |
| 24 | `workflow/controller/operator.go:1189-1198` | §1.3/§5.6 `expression` 求值决定是否重试 |
| 25 | `workflow/controller/operator.go:1218-1237` | §1.3/§5.1 `retryPolicyAllows` 四值语义（核心证据） |
| 26 | `workflow/controller/operator.go:1170-1173` | §1.3 policy 拒绝重试时的实际行为 |
| 27 | `workflow/controller/operator.go:465-468` | §2.3 `Skipped`/`Omitted` 都算 workflow 成功 |
| 28 | `workflow/controller/operator.go:1070-1105` | §1.4 重试是父节点（`NodeTypeRetry` + 子尝试） |
| 29 | `workflow/controller/retry_tweak.go:26-39` | §1.1/§5 `nodeAntiAffinity` 靠 hostname 落地，单机无意义 |
| 30 | `workflow/controller/node_phase_sm.go:9-78` | §2.3 状态迁移表 + 终态无出边 + `Skipped`/`Omitted` 定义 |
| 31 | `workflow/controller/engine.go:377-418` | §2.2 Steps 组失败相位推导（含 Failed/Error 保留） |
| 32 | `workflow/controller/engine.go:1060-1104` | §2.2 DAG 相位评估 + `failFast` 默认 |
| 33 | `workflow/controller/engine.go:1118-1134` | §2.2 `branchPhase` 分支相位继承 |
| 34 | `workflow/controller/engine.go:128-156` | §2.3 `Omitted` 节点创建 + `"omitted: "` 消息 |
| 35 | `workflow/controller/dag/types.go:46-57` | §2.5/§5.7 `taskResult` 八种结果 |
| 36 | `workflow/controller/dag/expansion.go:92-115` | §2.3 `when` 求值（空→true） |
| 37 | `workflow/common/ancestry.go:29-42` | §2.5 `TaskResult*` 常量 + `DependencyType` |
| 38 | `workflow/validate/validate.go:1437-1516` | §4.1/§2.5 手写关系型校验（依赖存在性、拓扑、环） |
| 39 | `workflow/validate/validate.go:1476-1482` | §2.5 `depends`×`continueOn` 互斥的 Go 侧重复实现 |
| 40 | `workflow/validate/validate.go:649-651` | §4.1 模板名必填靠手写（非 schema） |
| 41 | `workflow/validate/validate.go:1721-1744` | §4.1 `isValidWorkflowFieldName` / `isValidStepName` |
| 42 | `pkg/apis/workflow/v1alpha1/openapi_generated.go`（全文 71 处 `Required`） | §4.1 schema 层"必填极稀" |
| 43 | `openapi_generated.go:2452/2578` | §4.1 `DAGTask` 的 `Required:{name}` |
| 44 | `openapi_generated.go:2586/2628` | §4.1 `DAGTemplate` 的 `Required:{tasks}` |
| 45 | `openapi_generated.go:6625` | §4.1 `Template` schema 起点（本块无 `Required`） |
| 46 | `cmd/argo/lint/lint.go:111-142, 222-224` | §4.2 lint 每对象最多一条错误 |
| 47 | `cmd/argo/lint/lint.go:31-41, 150` | §4.2 未知字段默认不报错，需 `Strict` |
| 48 | `docs/lifecyclehook.md:7-15, 37` | §2.4/§5.5 hook 全部行为规则 |
| 49 | `docs/offloading-large-workflows.md:3, 39` | §3.3 workflow < 1MB（etcd） |
| 50 | `docs/workflow-controller-configmap.md:191` | §3.3 `NodeStatusOffload` 避开 1MB |
| 51 | `docs/memoization.md:48` | §3.3 ConfigMap 1MB（另一个 1MB） |
| 52 | `docs/artifacts.md:98` | §3.3 artifact 默认 tar+gzip |
| 53 | `docs/workflow-concepts.md:24-39, 145-186` | §6 `Template`/`Steps`/`DAG` 概念区分 |
| 54 | `docs/fields.md:1868-1872, 3103-3109` | §8.1-6 文档由 Go 注释生成，无额外语义 |
| 55 | `pkg/apis/workflow/v1alpha1/register.go:1-51` | §4 反证：该文件**只做 scheme 注册，无校验** |

### 8.3 specflow 侧证据（来自只读勘查，未由本文作者复核行号）

| 主张 | 位置 |
|---|---|
| 站点集合唯一定义（顺序 = `ordinal()`） | `flow/Node.java:21-31`、`:57-59`、`:62-64`、`:84-92` |
| 无任何 `switch (node)` 分派 | 全仓 11 处 `switch(` 无一按 `Node` |
| 站点→闸派发表 | `flow/Gates.java:64-69`；无登记即抛 `:81-84` |
| 闸 = 返回人话的纯函数 | `flow/Gate.java:26-34`；`Gates.java:43-56`（顺序+门槛）、`:99-158`（五个实现） |
| 三态 + 前向重算 | `flow/State.java:13-15`；`FlowService.java:293-308` |
| 闸的三种用法 | `FlowService.java:139`（`requireOpen`）、`:163`（`requireReachable`）、`:182`（`freeze`） |
| 站点动作路由（硬编码） | `web/Api.java`：env `:214/246/267/322/348/361/454`；probe `:373/385/390/482`；cases `:413/425/521`；dev `:555/598/824`；test `:616/634/704/716/732/762/771`；跨站 `:88/93/99/108` |
| `env/skip` = 闸的一条出路（不是跳站） | `web/Api.java:348-354`；`Gates.java:99-116`；`EnvNode.vue:123` |
| 前端四张表 + 各站动作数组 | `lib/flow.js:4-10`；`lib/pages.js:4-12`；`router.js:11`；`EnvNode.vue:108-124/129`；`ProbeNode.vue:88-95/100`；`CasesNode.vue:100-108/114`；`DevNode.vue:57-62/68`；`TestNode.vue:70-76/86`；`lib/facts.js:61-70` |
| 后端无"推进器"类 | 推进 = `FlowService.java:182-197` `freeze` + `:293-308` `refresh` |
| **无站点级重试** | 未找到；仅用例级 `Api.java:634-651`、协议回喂 `agent/Protocol.java:105`+`agent/Loop.java:185`、编译回喂 `Api.java:567`（`Dev.java` 40 轮上限） |
| **无站点级跳过** | 未找到；仅 `envNotNeeded`（`Api.java:348-354`）与用例级 `SKIPPED`（`test/CaseBudget.java`） |
| **无 `onExit`** | 全仓 0 命中 |
| **无站点统一收尾/补偿** | 失败只 `progress.fail` + 写 fact（`Api.java:584-588`），半成品留盘，须人工 `/api/flow/goto` |
| **无 shutdown hook** | `Main.java` 搜 `shutdown|hook|close|收场` 0 命中；`FlowService.close():267-272` 无调用方 |
| **`Journal.sweep()` 从未被调用** | 定义 `Journal.java:89`；全仓仅 `:25/:63/:154` 三处注释提及；唯一构造点 `Runs.java:109` 只用 `begin/end` → `ARCHITECTURE.md:94-95` 不变量 7 文档有、代码未接线 |
| 容器收尾的现有范例（可推广） | `test/TestSandbox.java:41 implements AutoCloseable`、`close():130`；调用点 `Api.java:660` |
| 加一站需同步改 6 处（自认） | `flow/Gate.java:9-12` |
| 闸的唯一测试钉子 | `flow/GatesTest.java`（14 个 `@Test`）；`FlowService`/`Journal`/`Snapshot`/`Store`/`Runs`/`Api` 均无单测 |
| 文档与代码漂移 | `ARCHITECTURE.md:87` 写 `Gate.check(node,facts)`，实际 `Gates.blocked(node,facts,states)`；不变量 7 未接线 |
| `env.yaml` 校验器（本文唯一亲读的 specflow 文件） | `env/EnvConfigLoader.java:95/109-112`（一次报全）、`:376-380/382-430`（行号）、`:350-362`（未知字段）、`:334-344`（关系型）、`:184-188`（语义） |

### 8.4 一句话总账

- **RetryStrategy 值得抄的**：`retryPolicy` 的 `Failed`/`Error` 二分（默认 `OnFailure`）+
  `backoff.maxDuration` 作为"从第一次尝试起算的截止时刻"。
- **跳过值得抄的**：不是新字段，而是把"跳过"当成**闸的一条出路**（我们已有 `env/skip` 这个正确范例）。
- **最该补的缺口**：站点级 exit handler（收尾），而 `TestSandbox implements AutoCloseable` 已经是
  现成的实现范式，不需要任何框架。
- **流程 YAML 化**：不值。要解决"多处硬编码"，应把前端四张表压成后端下发的一份，而不是引入第五份。
