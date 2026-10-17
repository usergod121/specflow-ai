# OpenHands · 深读

> 阅读对象：**两代真实源码**，不是文档、不是博客。
> - **V0 = `0.62.0` tag**（经典 Python 单体，含 `Runtime` / `EventStream` / `Controller` / `State` / `StuckDetector`），已整包解压在 `E:\sf-research\openhands062\OpenHands-0.62.0\`
> - **V1 = `OpenHands/software-agent-sdk` main**（V1 重写版，`Runtime` 被 `Workspace` 取代，`StuckDetector` 重写），已整包解压在 `E:\sf-research\sdk\software-agent-sdk-main\`
>
> **重要前提（实测）**：`All-Hands-AI/OpenHands` 的 `main` 分支**已经没有 Python 代码了**——原仓库现已更名为 `OpenHands/OpenHands`，main 是 TypeScript 前端（`src/` + `react-router.config.ts`、`electron/`，共 2716 个文件，`openhands/` 目录数为 0）。题面里那批路径（`openhands/runtime/base.py` 等）只在 `0.62.0` 及以前存在。V1 的 Python 核心搬到了 `OpenHands/software-agent-sdk`，旧代码归档在 `OpenHands/legacy`。所以本文用 0.62.0 + agent-sdk main 双份源码，两边都读了。

---

## 1. 宏观：Runtime / EventStream / Controller / State 的关系

V0 是**四件套 + 一个闭环**，职责边界非常干净：

1. **EventStream 是唯一的真相源（single source of truth）**。所有东西——Agent 的思考、动作、观测、状态变更、循环恢复、任务清单——都是 `Event`，都进同一个 `EventStream`。它同时是**广播总线**和**持久化日志**（`EventStream(EventStore)`，`stream.py:43`）。
2. **Controller 不直接调工具**，它只做三件事：取历史 → 问 LLM 要 `Action` → 把 `Action` 塞进 EventStream（`agent_controller.py:_step()`，`L852`）。
3. **Runtime 是 EventStream 的一个订阅者**。它订阅 `Action` 事件，执行，然后把 `Observation` 打回 EventStream（`base.py:322 on_event()` → `run_action()`）。Controller 完全不知道沙箱是本地、Docker 还是远端。
4. **State 是 Controller 的脑内状态，但它把历史"借给"了 EventStream**。`State.history` 是 `list[Event]`，可是 pickle 落盘时 `__getstate__` 会把 `history` 清空（`state.py:191-209`）——**历史从事件日志重建，State 只存指针和计数**。这是它整个 checkpoint/resume 设计的支点。
5. **闭环**：`State.iteration_flag` / `budget_flag` 是独立的"控制闸门"对象（`control_flags.py`），在 `_step()` 里每次先 `run_control_flags()` 再问 LLM（`agent_controller.py:887`）。**卡死检测也是同一层的闸门**，就在它前面（`L880`）。

一句话总结：**"事件流是账本，State 是账本的游标，Runtime 是账本的一个执行器，Controller 是记账的人。"**
V1 保留了这个骨架，只把 `Runtime` 换成更窄的 `Workspace`、把 20 多种 Action 子类压成 1 个 `ActionEvent`、把 `Event.id` 从自增整数换成 UUID + `parent_id` 树。

---

## 2. StuckDetector 的判据清单（★最重要）

### 2.0 位置与入口

| 代 | 文件 | 入口 | 调用点 |
|---|---|---|---|
| V0 | `openhands/controller/stuck.py`（481 行） | `is_stuck(headless_mode=True)` `L38` | `agent_controller.py:880` |
| V1 | `openhands-sdk/openhands/sdk/conversation/stuck_detector.py`（370 行） | `is_stuck()` `L104` | `local_conversation.py:2022`、`L2240` |

**判据总数：V0 = 5 条，V1 = 5 条（但第 5 条是空壳，实际生效 4 条）。** 题面猜的"单调性"判据**不存在**——两代源码里都没有"输出没变化"或"指标不单调"这类判据。（"单调性"若指 `monotone`，全仓 grep `stuck` 只有 2 个文件命中。）

### 2.1 公共前置：先削历史，再判

两代都做同一件事，且**这一层比判据本身更值得抄**：

- **V0**：`is_stuck()` 先把 history 过滤一遍（`stuck.py:67-78`）——剔掉 `source==USER` 的 `MessageAction` 和 `NullAction`/`NullObservation`；`headless_mode=False`（交互模式）时**只检查最后一条用户消息之后的历史**（`L50-61`），并记 `filtered_history_offset` 用于回溯定位。**历史 < 3 条直接返回 False**（`L81`）。
- **V1**：同样的"最后一条用户消息之后"，但**窗口上限锁死 20 条事件**（`stuck_detector.py:18-21, L66-84`），注释写得很直白：*"Windowed rather than full-history to avoid materializing large file-backed event logs."* 这是为文件型事件日志专门做的优化。

> **对我们的直接含义**：我们的 `events.jsonl` 也是文件型日志，且会很长。**判据只能读尾部窗口，绝不能全量反序列化**。V1 的 20 条上限可以直接抄。

### 2.2 逐条判据

#### 判据 1 · 同动作 + 同观测循环（action-observation loop）

| 项 | V0 | V1 |
|---|---|---|
| 位置 | `stuck.py:133-164` | `stuck_detector.py:156-190` |
| 阈值 | **4 次**（硬编码） | **`action_observation=4`**（`types.py` 可配） |
| 判据 | 末 4 个 Action 两两全等 **且** 末 4 个 Observation 两两全等 | 同左 |
| 相等语义 | `_eq_no_pid()` `L457`：对 `CmdOutputObservation` 只比 `command` + `exit_code`，**故意忽略 pid/command_id**（`L474-478`）；其它类型走 `==` | `_event_eq()` `L325`：类型必须相同；`ObservationEvent` 比 `source`+`observation`+`tool_name` |
| 记录 | `loop_type='repeating_action_observation'`, `loop_repeat_times=4`, `loop_start_idx` | 只返回 bool + 打日志 |

#### 判据 2 · 同动作 + 连续报错（action-error loop）★ 与我们重合度最高

| 项 | V0 | V1 |
|---|---|---|
| 位置 | `stuck.py:166-237` | `stuck_detector.py:192-216` |
| 阈值 | **3 次** | **`action_error=3`**，但触发条件是 `streak > threshold`，即**第 4 次**才判死 |
| 判据 | 末 3 个 Action 全等，**且** 末 3 个 Observation 全是 `ErrorObservation` | 尾部长跑 `_action_error_streak()`（`L192-206`）：连续 `(同一 action, AgentErrorEvent)` 对的数量 |
| 子判据 2b | 末 3 个全是 `IPythonRunCellObservation` 且报同一句 SyntaxError（`L192-236`）：三种白名单错误串（`SYNTAX_ERROR_MESSAGES` `L22-26`），并做"行号一致 / 至少 6 行 / 末尾 2 行是我们的固定尾巴"的结构校验（`_check_for_consistent_invalid_syntax` `L239`、`_check_for_consistent_line_error` `L282`） | 无（V1 没有 IPython） |

**V1 新增的关键机制：先 nudge，再判死** —— `get_action_error_nudge()`（`stuck_detector.py:218-248`）：

- 当 streak **恰好等于** threshold（3）时，**不判死**，而是往会话里注入一条 `MessageEvent(source="environment", role="user")`，原文大意：
  > *"You've called `<tool>` with the same arguments 3 times in a row and gotten the same error each time: `<error>`. Repeating the exact same call again will not work — review the error message and either correct the arguments or try a different approach."*
- **同一 streak 只 nudge 一次**：用 `_last_nudged_error_event_id` 去重（`L45-48, L238-240`），防止"空响应/只有 reasoning 没有新 action"把 streak 冻住时反复刷同一条提醒。
- 调用方 `_check_stuck_or_nudge()`（`local_conversation.py:761-784`）的语义极清楚：`nudge != null` → 发消息，`return False`（继续跑）；否则 `is_stuck()` → `execution_status = STUCK`，`return True`（停）。

**所以 V1 的实际策略是：3 次 → 骂一句让它改；4 次还没改 → 判死。** 这一条是全篇里我最想让我们抄的东西。

#### 判据 3 · 独白（monologue）

| 项 | V0 | V1 |
|---|---|---|
| 位置 | `stuck.py:311-350` | `stuck_detector.py:250-275` |
| 阈值 | **3 条** agent 消息 | **`monologue=3`** |
| 判据 | 末 3 条 `MessageAction(source=AGENT)` 内容**完全相同**，**且这 3 条之间没有任何 Observation**（`L336-341`） | 从尾部倒数，**连续** `source=="agent"` 的 `MessageEvent` 计数 ≥ 3；遇到 `user` 消息 `break`，`CondensationSummaryEvent` `continue`（不算打断），**其它任何事件（action/observation）`break`** |
| 差异 | 要求"内容相同" | **不要求内容相同**，只要连续 3 条 agent 消息没有动作/观测插进来就算独白 |

V1 的写法更狠也更对：连续 3 轮只说不动手 = 死循环，跟说不说同一句话无关。

#### 判据 4 · 交替模式（alternating pattern）★ 我们完全没有

| 项 | V0 | V1 |
|---|---|---|
| 位置 | `stuck.py:352-400` | `stuck_detector.py:277-312` |
| 阈值 | **末 6 个 Action + 6 个 Observation** | **`alternating_pattern=6`** |
| 判据 | 收集末 6 动作、末 6 观测，然后 `a0==a2==a4` **且** `a1==a3==a5`，观测同理（`L374-389`）。即 A B A B A B 两个动作轮转 | 同左，泛化成 `last[i] == last[i+2] for i in range(threshold-2)`（`L299-306`） |
| 记录 | `loop_type='repeating_action_observation_pattern'`, `loop_repeat_times=3` | 只返回 bool |

这条捕获的是**"改一点点再改回来"**的振荡：`sed s/a/b/` → `sed s/b/a/` → `sed s/a/b/`… 单看每一条动作都不重复（所以判据 1/2 抓不到），但整体是死循环。**这是我们的"连续 3 次相同命令"判据的结构性盲区。**

#### 判据 5 · 上下文窗口错误循环（condensation loop）

| 项 | V0 | V1 |
|---|---|---|
| 位置 | `stuck.py:402-455` | `stuck_detector.py:314-323` |
| 阈值 | **≥10 个** `AgentCondensationObservation` | — |
| 判据 | 末 10 个 condensation 事件中，**存在相邻两个之间没有任何其它事件**（`L433-453`）→ `loop_type='context_window_error'`, `loop_repeat_times=2` | **`return False` 空实现**，注释：`# TODO: blocked by https://github.com/OpenHands/agent-sdk/issues/282` |

场景：上下文超限 → 触发压缩 → 压缩没生效 → 又超限 → 又压缩。**我们（specflow）根本没有 condensation 机制**，上下文超限会直接表现为 LLM 400/413，走的是异常路径而不是循环。**这条对我们价值为零**，而且 V1 自己都把它注释掉了——这是很硬的证据：连上游都认为它优先级最低。

### 2.3 触发后的处置（这条比判据更值得抄）

**V0 = 判死 + 可选的"回滚重来"**：

- 主路径：`_step()` → `_react_to_exception(AgentStuckInLoopError('Agent got stuck in a loop'))`（`agent_controller.py:880-884`）→ Agent 进入 `ERROR` 状态，本轮结束。
- CLI/交互模式增强路径：`attempt_loop_recovery()`（`L1110-1147`）—— 不直接死，而是
  1. 发一条 `LoopDetectionObservation`，正文里**明确告知 loop_type、当前迭代数、以及"可回滚到第 N 个事件之前"**（`L1126-1139`）；
  2. 发 `ChangeAgentStateAction(AgentState.PAUSED)` 把 Agent 暂停（`L1143-1146`）；
  3. 给用户三选一：`/resume 1` 从 `loop_start_idx` 回滚重来 / `/resume 2` 用最后一条用户消息重来 / `/exit` 退出。
- 配套 `_perform_loop_recovery(stuck_analysis)`（`L1273-1292`）与 `_restart_with_last_user_message`（`L1317-1358`）。

**回滚到底怎么实现的（逐行读过，这条最容易想错）** —— `_truncate_memory_to_point()`（`L1294-1315`）：

```python
events_to_keep = all_events[:recovery_point]        # L1303  只截"内存里的 history"
self.state.history = events_to_keep                 # L1306
self.state.end_id = events_to_keep[-1].id           # L1310  ★ 真正的开关：把游标拨回去
```

**它没有删任何事件文件。** 真正生效的是 `state.end_id` —— `state_tracker.py:118-138` 重建历史时，是拿 `(start_id, end_id)` 区间去**查询事件存储**（默认 `end_id = self.cur_id`，见 `event_store.py:105-120`）。所以：

> **"回滚"= 把 `end_id` 游标往回拨，事件日志保持 append-only 不动。**

这比"物理截断"高明得多：日志仍然完整可审计、回滚可逆、并发读不会被截断撕裂。**对我们的直接含义：`events.jsonl` 不要删行，只存一个 `lastEventId` 游标；回滚就是把游标设回 `loopStartEventId`。** 我们的 id ≡ 行号让 `end_id` 就是 `long`，这件事几乎零成本。**这是本次研究里对我们最直接可落地的一条。**

**V1 = 分级 + 终态化**：nudge → 仍卡 → `execution_status = STUCK`，并且 `STUCK` 被明确放进 `ConversationExecutionStatus.is_terminal()`（`state.py`，与 `FINISHED`/`ERROR` 并列）。**"卡死"是一等公民状态，不是普通异常。**

### 2.4 我们的现状对比

| OpenHands 判据 | 阈值 | specflow 现状 | 缺口判断 |
|---|---|---|---|
| ① 同动作 + 同观测 | 4 次（动作+输出全等） | 有"连续 3 次相同命令"，但**只比命令串，不比观测** | **双向都是坑**：既漏"同命令不同输出"的假死，也漏"不同命令同输出"；但只比命令又会对**合法的重复轮询**（等构建/等容器起）误杀。应改成 `(cmd, exitCode, outputHash)` 三元组全等才计重复 |
| ② 同动作 + 连续报错 | 3 次 nudge / 4 次判死 | 有"连续 3 次相同命令"（无错误条件、无 nudge） | **最该补的是"分级处置"**：我们第 3 次就杀，OpenHands 第 3 次只骂。**我们现在是在最有救的那一刻把 case 判死** |
| ③ 独白（只说不动） | 连续 3 条 agent 消息无动作 | 无 | 中价值。ReAct + 工具下 LLM 偶尔连续输出"我来看看…"却不发 tool_call；但这通常由"无 tool_call → 报错重试"的自然机制兜住。优先级低于 ①② |
| ④ 交替模式 A/B/A/B | 6 动作 6 观测 | 无 | **结构盲区**。成本极低（就是尾窗内取 6+6 做个 `==` 比较），且我们的场景（五站流水线，反复改同一个 spec/配置）正好容易振荡。建议补 |
| ⑤ 上下文压缩循环 | ≥10 次 condensation 紧邻 | 无（无 condensation） | **不补**。机制不存在 + 上游 V1 自己 `return False` |
| 处置：nudge 一次 | streak==阈值时注入纠正消息 | 无 | **必须补**，性价比最高 |
| 处置：回滚到 loop_start | 把 `state.end_id` 游标拨回 `loop_start_idx`（日志不动） | 无 | **建议补**，我们的行号 id 让这件事几乎零成本 |
| 处置：STUCK 作为终态 | `is_terminal()` 含 STUCK | 无（应是通用 failed） | **建议补**，让前端 SSE 能区分"卡死"和"真报错" |

---

## 3. Runtime 接口方法清单（值不值得抄这个抽象）

### 3.1 V0 的 `Runtime` 抽象方法（`openhands/runtime/base.py`，`class Runtime(FileEditRuntimeMixin)` `L91`）

13 个 `@abstractmethod`：

| 方法 | 签名 | 行 |
|---|---|---|
| `connect` | `async def connect(self) -> None` | `L1028` |
| `get_mcp_config` | `def get_mcp_config(self, extra_stdio_servers=None) -> MCPConfig` | `L1068` |
| `run` | `def run(self, action: CmdRunAction) -> Observation` | `L1078` |
| `run_ipython` | `def run_ipython(self, action: IPythonRunCellAction) -> Observation` | `L1082` |
| `read` | `def read(self, action: FileReadAction) -> Observation` | `L1086` |
| `write` | `def write(self, action: FileWriteAction) -> Observation` | `L1090` |
| `edit` | `def edit(self, action: FileEditAction) -> Observation` | `L1094` |
| `browse` | `def browse(self, action: BrowseURLAction) -> Observation` | `L1098` |
| `browse_interactive` | `def browse_interactive(self, action: BrowseInteractiveAction) -> Observation` | `L1102` |
| `call_tool_mcp` | `async def call_tool_mcp(self, action: MCPAction) -> Observation` | `L1106` |
| `copy_to` | `def copy_to(self, host_src: str, sandbox_dest: str, recursive: bool = False)` | `L1114` |
| `list_files` | `def list_files(self, path: str \| None = None) -> list[str]` | `L1118` |
| `copy_from` | `def copy_from(self, path: str) -> Path` | `L1126` |

三个实现：`runtime/impl/local/local_runtime.py`、`runtime/impl/docker/docker_runtime.py`、`runtime/impl/remote/remote_runtime.py`。

### 3.2 真正的设计精华：不是那 13 个方法，而是 3 个模式

**(a) 按 `action.action` 字符串反射分发 —— 零样板**
`run_action(action)`（`base.py:928-1015`）核心只有 5 行：

```python
action_type = action.action                      # L1000  e.g. 'run' / 'read' / 'edit'
if action_type not in ACTION_TYPE_TO_CLASS:      # L1001
    return ErrorObservation(f'Action {action_type} does not exist.')
if not hasattr(self, action_type):               # L1003
    return ErrorObservation(f'Action {action_type} is not supported in the current runtime.')
observation = getattr(self, action_type)(action) # L1014
return observation
```

**新增一种动作 = 加一个 Action 类 + 在 Runtime 上加一个同名方法**，不用改任何 switch/if-else，不用改 dispatcher。而**不支持的动作不会崩，会退化成一条 `ErrorObservation` 回给 LLM**（"not supported in the current runtime"）—— 这让"本地跑不了、容器能跑"这种能力差异变成了对模型可见的普通反馈。

**(b) Runtime 就是 EventStream 的订阅者**
`def on_event(self, event: Event) -> None:` → `if isinstance(event, Action): ...run_until_complete(self._handle_action(event))`（`L322-324`）。Runtime 不需要被 Controller 调用，它只听总线。**这是"事件流驱动"最干净的一处体现。**

**(c) 超时是 Action 的属性，不是 Runtime 的参数**
`Event.set_hard_timeout(value, blocking=True)`（`events/event.py:86-96`）—— 超时挂在**事件**上，且会自动把 `blocking` 置 True。默认值来自配置：`event.set_hard_timeout(self.config.sandbox.timeout, blocking=False)`（`base.py:373`）；`setup.sh` 给 600s（`base.py:518`）；action execution server 给 300s（`action_execution_server.py:369`）；LLM 还能自己传 `timeout` 参数（`function_calling.py:117`）。**这就是我们那个"单条命令 600 秒上限"的上游原型——他们做成了事件级、多层可覆盖的。**

### 3.3 V1 把 `Runtime` 收成了 `Workspace`（`openhands-sdk/.../workspace/base.py`）

只剩 5 个抽象方法（`L171-249`）：

```python
def execute_command(self, command: str, cwd: str | Path | None = None,
                    timeout: float = 30.0) -> CommandResult:   # L172
def file_upload(self, source_path, destination_path) -> FileOperationResult   # L195
def file_download(self, source_path, destination_path) -> FileOperationResult # L215
def git_changes(self, path) -> list[GitChange]                                # L235
def git_diff(self, path) -> GitDiff                                           # L249
```

外加非抽象钩子 `pause()` `L262` / `resume()` `L273` / `register_cost(cost)` `L66` / `accumulated_cost` `L80` / `_send_completion_callback()` `L88`。
实现：`workspace/local.py`、`workspace/remote/`。

**V1 的判断：把 13 个方法砍到 5 个，回归"命令 + 文件进出 + git 差异"。** ipython / browse / mcp 全部下沉成 tool，不再占 Runtime 接口位。这是官方的自我修正，分量很足。

### 3.4 对我们的结论

**不抄那 13 个方法，抄 3 个模式里最值钱的两条。**

- 我们的现实：**只有一个后端**（`docker compose exec -T app sh -s` 打进容器副本），五站流水线，工具是 ReAct 的少数几个。**"本地/容器/远端同一接口"这个需求我们现在并不存在**——引入 13 方法接口是纯负债：每个方法都要写两遍（本地 + 容器），而我们永远只跑容器。
- 但**"留一个窄接口 + 按名字分发的 dispatcher"**是有价值的：把 `docker compose exec` 关进一个 `interface Sandbox { ExecResult exec(String cmd, Duration timeout); byte[] read(String path); void write(String path, byte[] data); }`（3~4 个方法，不是 13 个），一旦将来要换远端执行器（`sbx`、K8s、`docker compose exec` 换成一次性 `docker run`）只换实现。**V1 收到 5 个方法这个事实，正好证明"窄接口"是上游自己走通的路。**
- **最该抄的是 3.2(c)：把超时挂到"命令事件"上而不是散在配置里。** 我们现在是一条全局 600s，OpenHands 是"事件级 + 默认 + 可被 LLM 覆盖"。至少让 `bash` 这个工具能按命令类型给不同上限（`docker compose build` 该给 600s，`ls` 给 30s 就够），否则 600s 对那些本该秒级报错的命令是巨大浪费。

---

## 4. 事件类型清单（动作 / 观察），我们缺哪些

### 4.1 V0 (`openhands/events/`)

**事件基类** `Event`（`event.py:35-131`）：`id`（自增整数，`INVALID_ID=-1`）、`timestamp`（ISO 串）、`source`（`agent`/`user`/`environment`）、`cause`（**父事件 id，因果链**）、`timeout`、`llm_metrics`、`tool_call_metadata`、`response_id`。
**`Observation(Event)`** 只加一个 `content: str`（`observation/observation.py:7-15`）。
**`Action(Event)`** 只加 `runnable: ClassVar[bool] = False`（`action/action.py:21-23`）。

**动作（Action）21 种**：
`NullAction`、`CmdRunAction`（`command, is_input, thought, blocking, is_static, cwd, hidden`）、`IPythonRunCellAction`、`FileReadAction`、`FileWriteAction`、`FileEditAction`、`BrowseURLAction`、`BrowseInteractiveAction`、`MCPAction`、`MessageAction`、`SystemMessageAction`、`ChangeAgentStateAction`、`AgentFinishAction`、`AgentThinkAction`、`AgentRejectAction`、`AgentDelegateAction`、`RecallAction`、`CondensationAction`、`CondensationRequestAction`、`TaskTrackingAction`、`LoopRecoveryAction`（`option: 1|2|3`，"该类对 LLM 不可见"，`action/agent.py:231-242`）

**观察（Observation）19 种**：
`NullObservation`、`CmdOutputObservation`、`IPythonRunCellObservation`、`FileReadObservation`、`FileWriteObservation`、`FileEditObservation`、`FileDownloadObservation`、`BrowserOutputObservation`、`MCPObservation`、`AgentStateChangedObservation`、`AgentCondensationObservation`、`AgentThinkObservation`、`RecallObservation`、`AgentDelegateObservation`、`ErrorObservation`、`UserRejectObservation`、`SuccessObservation`、`TaskTrackingObservation`、`LoopDetectionObservation`

**`CmdOutputObservation` 值得单独抄**（`observation/commands.py:96-204`）：它是 `command` + `metadata: CmdOutputMetadata`，而 `CmdOutputMetadata`（`L25-35`）是结构化的：

```python
exit_code: int = -1
pid: int = -1
username / hostname / working_dir / py_interpreter_path: str | None
prefix / suffix: str            # 从 PS1 里剥出来
```

`pid` 由 shell 的 `$!` 自动产出，`exit_code` 由 `$?` 自动产出（`to_ps1_prompt()` `L38-56`），**不靠正则猜**。另外 `_maybe_truncate()`（`L138-165`）对超长输出**掐中间留两头**（`[:half]` + `...[truncated]...` + `[-half:]`），不是截尾。还有 `hidden=True` 表示"不进 LLM、不进事件流"的命令。

### 4.2 V1 (`openhands-sdk/openhands/sdk/event/`)

**类型收敛成 5 个字面量**：`EventType = Literal["action","observation","message","system_prompt","agent_error"]`（`types.py`），`SourceType` 多了 `"hook"`。
**基类**：`id`（**UUID4 字符串，不是行号**）、`timestamp`、`source`、**`parent_id`**（会话树，`None`/`__root__` 为根，兄弟节点 = 分支）。
**类**：`ActionEvent`（`action: Action | None` + `tool_name` + `tool_call_id` + `tool_call` + `llm_response_id` + `thought` + `reasoning_content` + `thinking_blocks` + `security_risk` + `critic_result`）、`ObservationBaseEvent`（`tool_name` + `tool_call_id`）、`ObservationEvent`（`observation` + `action_id` + `extended_content`）、`UserRejectObservation`、`AgentErrorEvent`、`MessageEvent`、`SystemPromptEvent`。
**关键简化**：**21 种 Action 子类 → 1 个 `ActionEvent`**（动作内容装在 `action` 这个判别联合里），**19 种 Observation → 1 个 `ObservationEvent`**。新增工具不再新增事件类型。

### 4.3 我们缺哪些（按"值不值得补"排）

| 缺的 | 证据 | 对我们的价值 |
|---|---|---|
| **`action_id` / `cause`（观测指向其动作）** | V1 `ObservationEvent.action_id`（`observation.py:36`）；V0 `Event.cause`（`event.py:73`） | ★★★ **必补**。我们的 `events.jsonl` 靠**行序**隐含"这条 obs 属于那条 act"。行序在单线程 ReAct 下能用，但一旦有并行命令、超时补发、SSE 断线重连对齐，顺序假设就崩了。**成本：一个字段。** |
| **结构化 `exitCode`（而不是从文本里抠）** | `CmdOutputMetadata.exit_code`（`commands.py:28`） | ★★★ **必补**。判据②"同动作同错误"需要机器可读的 `exitCode`；现在我们只有命令串可判，这是根因。顺带 `working_dir`、`duration` |
| **`ErrorObservation` 一等公民** | `observation/error.py:8` | ★★★ **必补**。工具异常/沙箱不可达/超时必须落成事件，而不是只写 log 或让整步抛异常。否则"卡死"和"环境坏了"无法区分，前端也看不到 |
| **`AgentStateChangedObservation`** | `observation/agent.py:9` | ★★★ **必补**（对 checkpoint 有直接用处）。状态跃迁是事件，可回放、可审计、可恢复。我们现在状态只在 DB/内存里 |
| **`LoopDetectionObservation`** | `observation/loop_recovery.py:8` | ★★ 补。SSE 可以据此给前端一条醒目提示 + "回滚重跑"按钮，而不是只抛个 failed |
| **`NullAction` / `NullObservation`** | `action/empty.py:8`、`observation/empty.py:8` | ★★ 补。让"事件流 1:1 对应 step"恒成立（哪怕这一步啥也没干）。**卡死检测的"连续 N 步"计数会因为这行变得可靠** |
| **`SuccessObservation`** | `observation/success.py:8` | ★ 可选。工具"成功但无输出"与"空观测"的区分 |
| `CmdOutputObservation.hidden` | `commands.py:105` | ★ 可选。我们那些内部探测命令（`docker compose ps`、健康检查）不该污染 LLM 上下文，但该留在 events.jsonl 里 |
| `LoopRecoveryAction(option)` | `action/agent.py:231` | ★ 可选，但**思想值得抄**：把"回滚策略"本身也做成一条事件（"对 LLM 不可见"），恢复动作即被审计 |
| `SystemMessageAction` / `CondensationAction` / `RecallAction` / `TaskTrackingAction` / `DelegateAction` / `BrowseAction` / `MCPAction` / `IPythonRunCellAction` / `FileDownloadObservation` | 见 §4.1 | ✗ 不补。我们没有 condensation、微 agent 召回、任务清单工具、子 agent 委派、浏览器、MCP、Jupyter |

**另一个不该忽略的：我们比 OpenHands 强的地方。** V0 用**自增整数 id**（跟我们的行号同构），V1 却退回了 **UUID + `parent_id` 树**（`event/base.py:24-40`，还要专门用一个 `ROOT_PARENT_ID` 哨兵 + validator 防止 id 撞哨兵）。树结构换来的是"会话分支/undo"（`EventLog.path_to_root()`、`_effective_parent_id()`，`event_store.py:95-131`）。**我们不需要 undo 能力，就别付这个代价。行号 id 是我们的资产，不是负债——它让 `loop_start_idx` 直接可用，让 SSE 的 `Last-Event-ID` 直接可用。**

### 4.4 EventStream 本体（`events/stream.py`，291 行）值得看的 5 点

1. **订阅是 `(subscriber_id, callback_id) → callback` 两层字典**，每个 callback 独占一个 `ThreadPoolExecutor(max_workers=1)` 和独立事件循环（`subscribe()` `L130-148`）。订阅者枚举：`AGENT_CONTROLLER / RESOLVER / SERVER / RUNTIME / MEMORY / MAIN / TEST`（`L23-30`）。**一个事件，多路消费者，互不阻塞。**
2. **id 分配在锁内完成，随写随发**：`event._id = self.cur_id; self.cur_id += 1`（`L170-172`），并**拒绝已带 id 的事件回灌**（`L164-167`，抛 `ValueError`，注释直指"probably added back to the EventStream from inside a handler, triggering a loop"）。
3. **持久化 = 一事件一文件 + 分页缓存**：`file_store.write(self._get_filename_for_id(event.id, user_id), event_json)`（`L188-199`），满 `cache_size` 条再写一个页文件（`_store_cache_page` `L205-213`）。单个事件 JSON > 1MB 会告警（`L190-198`）。**读单条快、读一页快，这是我们单文件 `events.jsonl` 做不到的（我们要顺序扫）。**
4. **写盘前做密钥脱敏** `_replace_secrets()`（`L221-244`）：递归替换所有字符串值里的 secret 为 `<secret_hidden>`，但**顶层保护字段**（`timestamp/id/source/cause/action/observation/message`）跳过。**这是我们漏掉的安全项**——我们的 `events.jsonl` 会把 `docker compose exec` 的完整命令、环境变量原文都落盘，一旦日志外泄就是事故。
5. **V1 的 EventLog 是文件型日志的性能工程样板**（`sdk/conversation/event_store.py`）：一事件一文件 + `_marker_path()`/`_marker_matches_length()`/`_advance_length_marker()` 用长度标记做 O(1) 长度查询（`L246-288`），避免每次 `__len__` 都去数文件；`_sync_from_disk()` 处理多进程追加（`L289-310`）；`_scan_and_build_index()` 重建索引（`L320`）。**我们若将来要把 events.jsonl 做成"可跳读"，这是现成的参照。**

---

## 5. State 存了什么（对我们的"运行级 checkpoint"有直接参考）

### 5.1 V0 `State`（`openhands/controller/state/state.py:48-120`）

| 分组 | 字段 |
|---|---|
| 身份 | `session_id`, `user_id` |
| **闸门** | `iteration_flag: IterationControlFlag`、`budget_flag: BudgetControlFlag \| None`、`confirmation_mode` |
| **历史** | `history: list[Event]`、**`start_id: int = -1`**、**`end_id: int = -1`**（"track the range of events in history"） |
| 生命周期 | `agent_state: AgentState`、`resume_state: AgentState \| None` |
| 多 agent | `delegate_level`、`parent_metrics_snapshot`、`parent_iteration` |
| 输入输出 | `inputs: dict`、`outputs: dict`、`extra_data: dict[str, Any]` |
| 诊断 | **`last_error: str`**、`metrics: Metrics`、`conversation_stats` |

**三个关键设计：**

**(a) `history` 不进 pickle。** `__getstate__()`（`L191-209`）里 `state['history'] = []`，注释：*"don't pickle history, it will be restored from the event stream"*。同时清掉视图缓存 `_view` / `_history_checksum`。**State = 游标 + 计数 + 状态机，事件流 = 内容。** 我们"运行级 checkpoint"要做的正是这件事：**别把对话内容塞进 checkpoint，只存"读到第几行了"。**

**(b) `start_id` / `end_id` 是两个整数指针。** 这跟我们的 `events.jsonl`（id ≡ 行号）**完全同构**——他们用同样的办法把"历史"表达成一个区间。这是我们能直接照搬的字段对。

**(c) `RESUMABLE_STATES = [RUNNING, PAUSED, AWAITING_USER_INPUT, FINISHED]`**（`L28-33`），`restore_from_session()` 里：**若原状态可恢复 → 记入 `resume_state`，然后把 `agent_state` 强制置回 `LOADING`**（`L177-184`）。也就是说**恢复动作本身是一个状态跃迁，不是原地复活**。这比"读到哪跑哪"严谨得多——恢复要重新走一遍 LOADING 的初始化。

**`AgentState`（`core/schema/agent.py`）12 态**：`LOADING / RUNNING / AWAITING_USER_INPUT / PAUSED / STOPPED / FINISHED / REJECTED / ERROR / AWAITING_USER_CONFIRMATION / USER_CONFIRMED / USER_REJECTED / RATE_LIMITED`。

**控制闸门对象化**（`control_flags.py`，95 行）——这个模式很省事：
- `ControlFlag[T]` 基类：`limit_increase_amount` / `current_value` / `max_value` / `headless_mode` / `_hit_limit`；
- `IterationControlFlag.step()` 先 `reached_limit()` 再 `current_value += 1`，越界抛 `RuntimeError`（`L57-65`）；
- **`increase_limit()`：交互模式下自动续期**（`max_value += limit_increase_amount`，`L51-55`），headless 下不续；`BudgetControlFlag` 则是 `max_value = current_value + limit_increase_amount`（`L77-81`）。
- 我们已有"每用例预算"，但它是散落的常量；OpenHands 把它做成**一个能被 `step()` 检查、能被 `increase_limit()` 续期、能被 checkpoint 序列化的对象**。**做运行级 checkpoint 时，"预算用了多少"必须进 checkpoint，否则恢复后预算归零，等于没有预算。**

### 5.2 V1 `ConversationState`（`sdk/conversation/state.py`）—— 更贴近我们

公开字段：`id`、`agent`（**Agent 本身被持久化**，注释："to allow resuming conversations and check agent configuration to handle tool/LLM changes"）、`workspace`、`persistence_dir`、`max_iterations`、`stuck_detection`、**`execution_status`**、`confirmation_policy`、`security_analyzer`、`activated_knowledge_skills`、`activated_path_rules`、`invoked_skills`、`blocked_actions`/`blocked_messages`、**`last_user_message_id: EventID`**、**`leaf_event_id: EventID`**、`head_is_empty: bool`、`stats`、`secret_registry`、`tags`、`agent_state: dict`、`hook_config`。

私有：`_events: EventLog`（**历史的外置载体**）、`_view`、`_view_branch_leaf`、`_cipher`、`_lock: FIFOLock`、`_dirty`（脏标记，懒持久化）。

`ConversationExecutionStatus` 8 态：`IDLE / RUNNING / PAUSED / WAITING_FOR_CONFIRMATION / FINISHED / ERROR / STUCK / DELETING`，且 `is_terminal() = {FINISHED, ERROR, STUCK}`（注释还专门解释 `IDLE` 为什么不算终态——会让 WebSocket 首帧状态推送误判）。

**`leaf_event_id` 是 V1 版的 `end_id`**：把"我读到哪了"精确到一个事件 id（因为它是树，不能只存行号）。我们的行号天然等价，**一个 `long lastEventId` 就够**。

### 5.3 给我们的 `RunState`（建议字段清单）

```
runId, caseId, station             // 五站流水线的站点号，OpenHands 没有（这是我们的增量）
status                            // IDLE/RUNNING/PAUSED/STUCK/ERROR/FINISHED（抄 V1，STUCK 必须是一等公民）
lastEventId                       // = events.jsonl 行号，抄 V0 的 end_id / V1 的 leaf_event_id
loopStartEventId                  // 卡死回滚点，抄 V0 的 StuckAnalysis.loop_start_idx
iteration, iterationLimit         // 抄 IterationControlFlag
budgetSpent, budgetLimit          // ★ 必须持久化，否则恢复后预算归零
lastError                         // 抄 V0 的 State.last_error
resumeState                       // 抄 V0 的 resume_state（恢复要走 LOADING 重入，不是原地复活）
startedAt, updatedAt, version     // 布局版本，抄 V0 的 __setstate__ 旧版本兼容逻辑（L211-246）
```

**两个必须说清的约束（都是从 V0 读出来的，不是猜的）：**

1. **`lastEventId` 是游标，不是"已持久化的最大行号"。** OpenHands 的 `end_id` 一旦被回滚拨回去，它**小于**事件存储里的实际最大 id（`state.py:99-101` + `state_tracker.py:118-138`）。所以 checkpoint 里要存的是"**读到哪**"，而不是"**写了多少**"——两个数不是一个数。若混为一谈，回滚后重启会直接跳过回滚点继续跑。
2. **恢复必须走 `LOADING` 重入，不是原地复活。** `restore_from_session()`：可恢复状态先记进 `resume_state`，然后把 `agent_state` **强制置回 `LOADING`**（`state.py:178-184`）。恢复是一次真正的重新初始化，这比"读到哪跑哪"安全。

---

## 6. 值不值得借鉴（逐条）

| # | 机制 | 收益 | 我们的代价 | 结论 |
|---|---|---|---|---|
| 1 | **action-error 分级处置**（3 次 nudge / 4 次判死） | 极高。我们现在第 3 次就杀，**在最有救的那一刻判死**。nudge 把"重复同一条错误命令"从"致命"降级为"可自愈" | 极低：一条注入消息 + 一个 `alreadyNudged` 标记 | **✅ 借鉴（第一优先）** |
| 2 | **判据 ②改成 `(cmd, exitCode)` 全等** | 高。现在只比命令串 → 既漏"不同命令同错误"的假循环，又误杀"同命令不同输出"的合法重试 | 低：需要先把 `exitCode` 结构化成事件字段 | **✅ 借鉴** |
| 3 | **判据 ①`(cmd, exitCode, outputHash)` 三元组全等 4 次** | 中高。给"重复"一个真正的内容指纹，同时化解"轮询等构建"的误判（输出一直在变 → 不算重复） | 低：对输出做 hash（先剔除时间戳/耗时这类抖动行） | **✅ 借鉴** |
| 4 | **判据 ④交替模式 A/B/A/B/A/B** | 中。覆盖"改一点点再改回来"的振荡，这是我们"连续 3 次同命令"的**结构性盲区** | 极低：尾窗取 6+6 做 `==` | **✅ 借鉴** |
| 5 | **卡死 → 回滚到 loop_start_idx 重跑**（`LoopRecoveryAction` 三选一） | 高。我们 id ≡ 行号，**回滚 = 把一个 `long` 游标拨回去，日志 append-only 不删**；比"整个 case 失败"好得多 | 低（注：不是"截断文件"，是"移动游标"，所以不破坏审计也不可逆性问题）；但要限定每 case 最多 1 次回滚，否则会变成新的死循环 | **✅ 借鉴（做 1 次，硬上限）** |
| 6 | **`STUCK` 作为一等终态**（`is_terminal()` 含 STUCK） | 中高。前端能区分"卡死"和"报错"，SSE 可以给出不同 UI 与"回滚重跑"按钮 | 极低：枚举加一个值 | **✅ 借鉴** |
| 7 | **`CmdOutputObservation.metadata{exit_code, working_dir, duration}` 结构化** | 高。判据 ②④ 的前提；也是"预算/耗时分析"的数据基础。靠 PS1 自动采集而非正则猜 | 低：我们在容器里跑，直接在命令包装里 `echo $?` 采 exit code 即可 | **✅ 借鉴** |
| 8 | **每事件脱敏（`_replace_secrets`）** | 高（安全）。我们 `events.jsonl` 会落盘完整命令和环境变量 | 极低：写盘前对已知 secret 做字符串替换，保护顶层字段 | **✅ 借鉴** |
| 9 | **观测带 `action_id`/`cause`** | 中高。去掉"靠行序隐含因果"的脆弱假设 | 低：一个字段 | **✅ 借鉴** |
| 10 | **`AgentStateChangedObservation`（状态跃迁事件化）** | 中高。checkpoint/审计/replay 的地基 | 低 | **✅ 借鉴** |
| 11 | **判据窗口限长（V1 的 20 条上限）** | 中。文件型日志绝不能全量反序列化 | 极低：读尾部 N 行 | **✅ 借鉴** |
| 12 | **窄 `Sandbox` 接口 + 按名反射分发** | 中。现在只有一个后端，但把 `docker compose exec` 关进 3~4 方法接口，将来换执行器只换实现；且"不支持的动作 → 返回 ErrorObservation 给模型"这个降级语义很实用 | 中：要多一层接口；但**别照抄 13 个方法** | **⚠️ 部分借鉴（抄形状不抄宽度）** |
| 13 | **超时做成事件属性、多层可覆盖** | 中。600s 一刀切对秒级命令是浪费 | 低 | **⚠️ 部分借鉴（按命令类型分级）** |
| 14 | **`ControlFlag` 对象化 + `increase_limit()` 续期** | 中。预算进 checkpoint 的必要条件 | 低 | **⚠️ 部分借鉴（只取"对象化 + 可序列化"）** |
| 15 | **判据 ③独白（3 条连续 agent 消息无动作）** | 低中。我们"无 tool_call → 报错重试"已能兜住大半 | 低 | **❌ 暂不做**（见 §7 理由） |
| 16 | **判据 ⑤context-window 压缩循环** | 零。我们没有 condensation 机制；**上游 V1 自己都 `return False` 了** | — | **❌ 不借鉴** |
| 17 | **13 方法 `Runtime` 接口（含 ipython/browse/mcp）** | 负。我们永远只跑容器，永远没有浏览器/Jupyter/MCP | 高（每个方法两套实现） | **❌ 不借鉴** |
| 18 | **UUID + `parent_id` 会话树 / 分支 undo** | 负。我们要的是"截断重跑"，不是"分叉保留" | 高（`ROOT_PARENT_ID` 哨兵、`path_to_root`、`_effective_parent_id`、索引重建全套） | **❌ 不借鉴**（行号 id 是我们的优势） |
| 19 | **一事件一文件 + 分页缓存** | 低。我们 `events.jsonl` 单文件顺序追加+SSE 推送已经够用；拆分只在"需要随机跳读"时才值得 | 中（要维护页缓存一致性、marker 文件、跨进程 syncing） | **❌ 不借鉴（给 events.jsonl 加索引即可）** |
| 20 | **多 agent 委派 / 微 agent 召回 / TaskTracking** | 零。五站流水线的"站"就是我们的结构化拆分 | — | **❌ 不借鉴** |
| 21 | **每个用户消息后重置判据窗口**（`headless_mode` 二分） | 中（低成本、防误杀） | 极低 | **✅ 借鉴**（我们是 headless，但"每用例重置窗口"等价于它的语义，确认一下就好） |

---

## 7. 我们要补的卡死判据（≤20 行 Java）

**做 3 条，不做 2 条。**

```java
// 前置：tail(N) 读 events.jsonl 尾部窗口（N=24，抄 V1 的 20 条上限思想，绝不全量反序列化）
record Step(String tool, String cmd, int exitCode, long outHash) {   // ① 必须结构化 exitCode+观测 hash
    boolean sameAs(Step o) { return tool.equals(o.tool) && cmd.equals(o.cmd)
            && exitCode == o.exitCode && outHash == o.outHash; }
}
/** 返回 STUCK 判据编号（0=未卡），并在命中时给出回滚点事件 id。 */
int judge(List<Step> w, BitSet nudged) {                            // w 末位最新
    int k = 1;                                                      // ① 同动作+同观测（含 exitCode）连续 4 次
    while (k < w.size() && w.get(w.size()-1).sameAs(w.get(w.size()-1-k)) ) k++;
    if (k >= 2) { if (nudged.get(2)) return 1; nudged.set(2); return -1; }   // -1 = 先 nudge 不杀
    k = 1;                                                          // ② 同动作+同错误：3 次 nudge，4 次判死
    var last = w.get(w.size()-1);
    while (k < w.size() && last.cmd.equals(w.get(w.size()-1-k).cmd) && last.exitCode != 0
           && w.get(w.size()-1-k).exitCode == last.exitCode) k++;
    if (k >= 2) { if (nudged.get(2)) return 2; nudged.set(2); return -1; }
    if (w.size() >= 6) {                                            // ④ 交替 A/B/A/B/A/B（我们完全缺失）
        var s = w.subList(w.size()-6, w.size());
        if (s.get(0).sameAs(s.get(2)) && s.get(2).sameAs(s.get(4))
         && s.get(1).sameAs(s.get(3)) && s.get(3).sameAs(s.get(5))
         && !s.get(0).sameAs(s.get(1))) return 4;
    }
    return 0;
}
```

（`outHash` 计算前剔除时间戳/耗时/PID 这类抖动行，否则 ① 永远不命中；回滚点 = 命中判据时窗口首条 action 的事件 id，即行号。）

**回滚时不要删 `events.jsonl` 的行**——照 OpenHands 的做法，只把 `RunState.lastEventId` 拨回 `loopStartEventId`（`agent_controller.py:1294-1315` 就是这么做的：日志 append-only 不动，动的只有 `end_id` 游标）。**每 case 最多回滚 1 次**，第 2 次直接判 `STUCK` 终态，否则"卡死→回滚→又卡死→再回滚"会成为新的死循环，把预算烧光。

- **做 ① 同动作+同观测（含 exitCode，4 次）**：我们现有的"3 次同命令"是按命令串比，既漏也误杀；加 `exitCode` + `输出 hash` 就从"字符串重复"升级成"无进展"。同时化解"轮询等容器起来"这类输出一直在变的合法重试。
- **做 ② 同动作+同错误（3 次 nudge / 4 次判死）**：**这一条是本次研究最该补的**。nudge 的成本是一条注入消息，收益是把"第 3 次就把 case 判死"变成"第 3 次给它一次改正机会"。注意判死必须严格晚于 nudge 一次，否则等于没 nudge。
- **做 ④ 交替模式（6 步）**：约 6 行代码，补掉"改一点点又改回来"的结构盲区。这是纯增量，代价可以忽略。
- **不做 ③ 独白**：ReAct + 工具下"连续 3 条消息不发 tool_call"已被我们的"无 tool_call → 报错重试"机制兜住；再做一层是重复建设，且"什么算一条消息"在我们的扁平事件模型里语义不如 OpenHands 清晰（他们有独立的 `MessageEvent` 类型）。
- **不做 ⑤ 上下文压缩循环**：我们**没有 condensation 机制**（超限直接 LLM 400，走异常路径而非循环）。而且 **OpenHands V1 自己也把这条判据 `return False` 注释掉了**——上游的优先级判断就是最强的证据。

另外两个"不是判据但同等重要"的动作：**回滚点定位**（命中即记 `loopStartEventId`，每 case 最多回滚 1 次）与 **`STUCK` 终态化**（让 SSE 带上 `status=stuck` + 回滚点，前端给"从第 N 步重跑"）。

---

## 8. 不借鉴的部分（含理由）

1. **`Runtime` 的 13 方法接口 / 本地+容器+远端三实现。** 我们只有一个执行后端（`docker compose exec -T app sh -s` 打容器副本），5 站流水线的工具面很窄。13 个方法 × 2 套实现是纯负债。**连上游自己都从 13 砍到 5**（V0 `Runtime` → V1 `BaseWorkspace`）。
2. **`IPythonRunCellAction` / 浏览器 / MCP / Jupyter kernel 那套。** 我们跑 shell，不跑 notebook。连带 `_check_for_consistent_invalid_syntax` / `_check_for_consistent_line_error`（`stuck.py:239-309`，70 行只为解析 Jupyter 的 `Cell In[1], line`）一起不要——**这是 OpenHands 里最"重而专用"的一段代码，也是判据 2b 的全部实现**。
3. **condensation / 上下文压缩循环判据。** 机制不存在；V1 已 `return False`（`stuck_detector.py:314-323`）并挂 issue #282。
4. **UUID + `parent_id` 会话树与分支 undo。** 我们要的是"截断重跑"，不是"分叉保留"。代价很实：`ROOT_PARENT_ID` 哨兵与 id validator（`event/base.py:42-49`）、`path_to_root` / `_effective_parent_id`（`event_store.py:95-131`）、`_view_branch_leaf`、索引扫描重建。**我们的行号 id 让 `loop_start_idx`、SSE `Last-Event-ID`、区间截断全都免费——这是相对 V1 的优势，别用树把它换掉。**
5. **一事件一文件 + 页缓存 + marker 文件。** 收益只在"需要随机跳读"时才兑现；我们 SSE 是顺序推、replay 是顺序扫。要跳读，给 `events.jsonl` 加个偏移索引就够，不必拆文件。（但 `_marker_matches_length` 那套 O(1) 长度查询的**思路**值得记着。）
6. **多 agent 委派（`AgentDelegateAction` / `delegate_level` / `parent_metrics_snapshot`）、微 agent 召回（`RecallAction`）、`TaskTrackingAction`。** 五站流水线本身就是结构化分解，不需要再套一层 agent 委派。
7. **`SystemMessageAction` / `SecurityRisk` 分析器 / critic。** 我们的安全边界在容器副本 + 命令白名单，不需要 LLM 判风险。
8. **`TrafficControlState`（限流）/ `RATE_LIMITED` 状态。** 我们是内部引擎，LLM 限流按退避重试处理即可，不必进状态机。
9. **V0 的 `f-string` 日志式可观测性 + `LoopDetectionObservation` 里塞大段 CLI 中文提示**（`agent_controller.py:1128-1136`）。那是给终端用户的交互文案，我们的前端有自己的 SSE 契约。

---

## 9. 未核实点 + 证据清单

### 9.1 证据清单（文件 : 行）

**StuckDetector（V0 / 0.62.0）** — `openhands/controller/stuck.py`
- `L21` `class StuckDetector`；`L22-26` `SYNTAX_ERROR_MESSAGES`（3 条白名单）；`L28-32` `StuckAnalysis(loop_type, loop_repeat_times, loop_start_idx)`
- `L38` `is_stuck(headless_mode=True)`；`L49-64` 交互模式只取最后一条用户消息之后；`L67-78` 过滤 USER 消息与 Null 事件；`L81` 历史 <3 直接 False
- `L86-97` 取末 4 动作 + 末 4 观测；`L99-127` 依次跑 5 个场景；`L116`/`L123` 判据 4 需 ≥6 条、判据 5 需 ≥10 条
- `L133-164` 判据①（4 次）；`L166-237` 判据②（3 次，含 2b SyntaxError）；`L239-280`/`L282-309` SyntaxError 结构校验；`L311-350` 判据③独白（3 条且中间无 Observation）；`L352-400` 判据④交替（6+6）；`L402-455` 判据⑤condensation（≥10，相邻无其它事件，repeat=2）；`L457-481` `_eq_no_pid`（CmdOutput 只比 command+exit_code）
- 处置：`openhands/controller/agent_controller.py` `L31` import、`L190-191` 实例化、`L880-884` `_is_stuck()` → `AgentStuckInLoopError`、`L1098-1108` `_is_stuck`（delegate 递归）、`L1110-1147` `attempt_loop_recovery`（`LoopDetectionObservation` + `ChangeAgentStateAction(PAUSED)` + 三选一）、`L1273-1292` `_perform_loop_recovery`、**`L1294-1315` `_truncate_memory_to_point`（只截 `state.history` + 拨 `end_id`，不删事件文件）**、`L1317-1358` `_restart_with_last_user_message`
- 游标语义：`openhands/controller/state/state_tracker.py:118-138`（`(start_id, end_id)` 区间重建历史）、`openhands/events/event_store.py:105-120`（`end_id` 默认 `self.cur_id`，inclusive→exclusive）
- 测试：`tests/unit/controller/test_is_stuck.py`（34.5KB，26 个用例；`L101` `test_history_too_short`、`L121` `test_interactive_mode_resets_after_user_message`、`L180/246/438/574/626` 对应 5 判据、`L808` `test_is_stuck_delegate_stuck`）

**StuckDetector（V1 / software-agent-sdk main）** — `openhands-sdk/openhands/sdk/conversation/stuck_detector.py`
- `L18-21` `MAX_EVENTS_TO_SCAN_FOR_STUCK_DETECTION = 20`；`L45-48` `_last_nudged_error_event_id`；`L104-154` `is_stuck()` 5 场景；`L156-190` 判据①；`L192-206` `_action_error_streak`；`L208-216` 判据②（`streak > threshold`）；`L218-248` **`get_action_error_nudge()`**；`L250-275` 判据③（连续计数版）；`L277-312` 判据④；`L314-323` 判据⑤ `return False`（issue #282）；`L325-370` `_event_eq`
- 阈值默认值：`openhands-sdk/openhands/sdk/conversation/types.py` `StuckDetectionThresholds`：`action_observation=4`、`action_error=3`、`monologue=3`、`alternating_pattern=6`（均 `ge=1`）
- 调用与处置：`openhands-sdk/openhands/sdk/conversation/impl/local_conversation.py` `L199` 字段、`L515-527` 构造/可关、`L761-784` **`_check_stuck_or_nudge()`**（nudge→发 `MessageEvent(source="environment", role="user")` 返回 False；否则 `execution_status=STUCK` 返回 True）、`L2022`/`L2240` 调用点、`L787-789` `stuck_detector` property
- 终态化：`openhands-sdk/openhands/sdk/conversation/state.py` `ConversationExecutionStatus`（8 态，`is_terminal()` = `{FINISHED, ERROR, STUCK}`）；`ConversationState` 字段（`id`/`agent`/`workspace`/`max_iterations`/`stuck_detection`/`execution_status`/`last_user_message_id`/`leaf_event_id`/`head_is_empty`/`stats`/`secret_registry`/`tags`/`agent_state` + `_events: EventLog`）
- 构造参数：`conversation/conversation.py` `L76-79` `stuck_detection: bool = True`、`stuck_detection_thresholds`
- 示例：`examples/01_standalone_sdk/20_stuck_detector.py` `L40-46`（`stuck_detection=True`）、`L49-52`（让它连续 `ls` 5 次触发）
- TS 端口同构：`clients/typescript/src/conversation/stuck-detector.ts` `L22` `StuckDetectionThresholds`、`L36` `DEFAULT_STUCK_THRESHOLDS`、`L183/224` 阈值用法

**Runtime（V0）** — `openhands/runtime/base.py`
- `L91` `class Runtime(FileEditRuntimeMixin)`；`L928-1015` `run_action()`（`L1000-1002` 按 `action.action` 查表、`L1003-1006` `hasattr` → `ErrorObservation('not supported in the current runtime.')`、`L1014` `getattr` 反射）
- `L322-324` `on_event()`（Action → `_handle_action`）；`L370` `_handle_action`；抽象方法 `L1028/1068/1078/1082/1086/1090/1094/1098/1102/1106/1114/1118/1126`
- 超时：`L373` `event.set_hard_timeout(self.config.sandbox.timeout, blocking=False)`；`L518` setup.sh 600s；`events/event.py:86-96` `set_hard_timeout(value, blocking=True)`（会连带把 `blocking` 置 True）；`runtime/action_execution_server.py:369` 300s；`runtime/plugins/agent_skills/utils/function_calling.py:117` 从 LLM 参数取 timeout；`resolver/issue_resolver.py:365` `600 + 100 * n_retries`
- 实现：`runtime/impl/{local/local_runtime.py, docker/docker_runtime.py, remote/remote_runtime.py}`

**Runtime → Workspace（V1）** — `openhands-sdk/openhands/sdk/workspace/base.py`
- `L28` `class BaseWorkspace`；抽象 `L171-192` `execute_command(command, cwd=None, timeout=30.0) -> CommandResult`、`L195` `file_upload`、`L215` `file_download`、`L235` `git_changes`、`L249` `git_diff`；钩子 `L66` `register_cost`、`L80` `accumulated_cost`、`L262` `pause`、`L273` `resume`

**事件（V0）** — `openhands/events/`
- `event.py`：`L9-12` `EventSource`、`L35-131` `Event`（`id`/`timestamp`/`source`/`cause`/`timeout`/`llm_metrics`/`tool_call_metadata`/`response_id`）、`L86-96` `set_hard_timeout`、`L37` `INVALID_ID=-1`
- `action/action.py:21-23` `Action(Event)` 只有 `runnable`；`action/empty.py:8` `NullAction`；`action/agent.py:231-242` `LoopRecoveryAction(option)`
- `action/commands.py:12-39` `CmdRunAction(command, is_input, thought, blocking, is_static, cwd, hidden)`
- `observation/observation.py:7-15` `Observation(Event){content}`；`observation/commands.py:25-35` `CmdOutputMetadata`、`L96-204` `CmdOutputObservation`（`L138-165` 掐中间截断、`L167-185` `command_id`/`exit_code`/`error`/`success` property）
- 类型清单见 §4.1（21 Action / 19 Observation）

**EventStream（V0）** — `openhands/events/stream.py`
- `L23-30` `EventStreamSubscriber` 7 值；`L43-70` `EventStream(EventStore)` 初始化（queue + 线程）；`L130-148` `subscribe`（每 callback 一个单线程池 + 独立 loop）；`L163-203` `add_event`（`L170-172` 锁内 `_id = cur_id; cur_id += 1`；`L164-167` 拒绝已带 id；`L188-199` 一事件一文件；`L190-198` >1MB 告警）；`L205-213` `_store_cache_page`；`L221-244` `_replace_secrets`（`L225-233` 顶层保护字段）；`L254-275` `_process_queue` 扇出

**State（V0）** — `openhands/controller/state/state.py`
- `L28-33` `RESUMABLE_STATES`；`L48-120` `State` 全字段；`L91` `history`；`L99-101` `start_id`/`end_id`；`L122-147` `save_to_session`（pickle+base64）；`L149-189` `restore_from_session`（`L178-184` 可恢复→`resume_state`，`agent_state` 强制 `LOADING`）；`L191-209` `__getstate__` **`history=[]`**；`L211-246` `__setstate__` 旧版兼容
- `openhands/controller/state/control_flags.py` `L11-39` `ControlFlag`、`L42-65` `IterationControlFlag`（`L51-55` `increase_limit` 交互续期）、`L68-95` `BudgetControlFlag`（`L77-81` `max_value = current_value + limit_increase_amount`）
- `openhands/core/schema/agent.py` `AgentState` 12 态

**事件 / 状态（V1）**
- `openhands-sdk/openhands/sdk/event/base.py` `L20-49` `Event(id=uuid4, timestamp, source, parent_id)`、`L42-49` `_reject_reserved_id`；`L75-199` `LLMConvertibleEvent`（`events_to_messages` 处理并行 tool_call 批合并）
- `openhands-sdk/openhands/sdk/event/types.py` `EventType = Literal["action","observation","message","system_prompt","agent_error"]`、`SourceType = Literal["agent","user","environment","hook"]`、`ROOT_PARENT_ID = "__root__"`
- `event/llm_convertible/action.py:24-73` `ActionEvent`；`observation.py:17-45` `ObservationBaseEvent`/`ObservationEvent`（`L36` `action_id`）、`L138` `AgentErrorEvent`；`message.py:25` `MessageEvent`；`system.py:12` `SystemPromptEvent`
- `conversation/event_store.py` `EventLog`：`L69` `get_index`、`L76` `get_id`、`L95` `_effective_parent_id`、`L110` `path_to_root`、`L188` `append`、`L246-288` marker 机制、`L289-310` `_sync_from_disk`、`L320` `_scan_and_build_index`

### 9.2 未核实点

1. **`docker_runtime.run()` 内部到底怎么处理超时，我没有逐行读完。** 只确认了 `docker_runtime.py` 里没有 `timeout` 字面量命中（`L66/70` 那两处是 httpx 的 `ConnectTimeout`/`ReadTimeout`，不是命令超时），所以命令超时**只能**走 `Event.set_hard_timeout` → `base.py:373` → action execution server（`action_execution_server.py:369` 给 300s）这条链，中间还经过 tmux 的 blocking 语义。**结论"超时是事件属性"这一条证据链是完整的；但"tmux 里超时具体怎么打断"我没读到实现细节。**
2. ~~`attempt_loop_recovery` 的回滚如何截断事件流~~ → **已核实，且结论与原假设相反**。`_truncate_memory_to_point()`（`agent_controller.py:1294-1315`）**只截内存里的 `state.history`，并把 `state.end_id` 拨回 `events_to_keep[-1].id`，不删任何事件文件**；`state_tracker.py:118-138` 重建历史时用 `(start_id, end_id)` 区间查事件存储（`event_store.py:105-120`，`end_id` 默认 `self.cur_id`）。**"回滚"= 移动游标，不是截断日志。** 这条已回改到 §2.3 / §5.3 / §6 / §7。
3. **V0 的 `State.save_to_session` 落盘频率、何时触发，未追。** 我确认了它 pickle+base64 写 FileStore（`L122-147`），但**触发时机**（每次 step 后？定时？）未核实。这对"checkpoint 粒度"很关键，值得再追一次 `agent_controller.py` 里的调用点。
4. **V1 的 `is_stuck()` 在 `L2022` 处的 `continue` 具体跳出哪个循环层，我没完整读循环范围。** `_check_stuck_or_nudge` 的 docstring 写"Returns True if STUCK was set and the run loop should stop"，但 `L2022` 是 `if ...: continue`——所以停不停取决于外层 `while` 的状态判断。**"STUCK 会让 run 循环终止"这个结论是由 `is_terminal()` 含 STUCK + `ConversationExecutionStatus` 枚举支撑的，不是由这一段控制流静态确认的。**
5. **`MAX_CMD_OUTPUT_SIZE` 的具体数值没读**（`observation/commands.py:139` 引用，定义在别处）。§4.1 说"掐中间留两头"是确认的，但"阈值是多少字节"未核实。
6. **`0.62.0` 是不是最后一个带经典 `Runtime` 的版本，我没逐一验证。** 我确认的是：0.62.0 有完整经典结构；`v1.27.1` 与 `main` 的 `openhands/` 目录数为 0。中间（0.63~1.0）的迁移点没查。
7. **题面提到的"单调性"判据，我在两代源码里都没找到对应物。** 我 grep 了 0.62.0 全树与 SDK 全树的 `stuck|loop`，命中的只有上述 5 条 + TS 端口 + 配置/测试。**若"单调性"指某个外部资料里的描述，我倾向于认为那是误记或指别的项目（如 SWE-agent 的 replay/guard），但这是个否定性结论，不能百分百断言。** 另外 0.62.0 里确有一个**单调性味道**的东西：`IterationControlFlag.step()` 每次 `current_value += 1` 单调递增并在越界时抛错（`control_flags.py:57-65`）——**它是"预算单调性"，不是"卡死单调性"。**

---

*（本文所有行号均对应当次读取的源码版本：V0 = `OpenHands/OpenHands` tag `0.62.0`；V1 = `OpenHands/software-agent-sdk` `main`，抓取时间 2025 年当次会话。）*
