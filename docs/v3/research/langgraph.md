# LangGraph · 深读（star / 语言 / 我会看的 commit：源码快照 main 分支，`libs/langgraph/pyproject.toml` → `version = "1.2.15"`；无 git 元数据，取不到 `git describe`）

> 所有结论都对应下面 §7 的 `文件:行`。凡是我没读到的，一律进 §6，不猜。

| 项 | 值 |
|---|---|
| star 数 | 未核实（本会话未联网成功取到权威数字，见 §6） |
| 语言 / 构建 | Python `>=3.10`；uv/pnpm monorepo，核心包 `libs/langgraph` |
| 版本 | `1.2.15`（`libs/langgraph/pyproject.toml:7`） |
| 快照 | `codeload.github.com/langchain-ai/langgraph/tgz/refs/heads/main` → `E:\sf-research\langgraph\langgraph-main` |
| 读过的包 | `libs/langgraph`（types / pregel / managed / graph）、`libs/checkpoint`（base / memory / serde / jsonplus）、`libs/prebuilt`（chat_agent_executor） |

---

## 1. 宏观：它的顶层抽象与谁约束谁（≤10 行）

1. **Channel**：状态的基本单位，带 reducer（`add_messages`、`operator.add`），是"可以合并的账本"。
2. **Node / Task**：node 是 `state -> partial_state` 的纯函数；一次调度的实例叫 task（有 `task_id`、`path`、`writes`）。
3. **Pregel loop**：BSP/superstep 模型。第 N 步的写入在第 N+1 步才可见，步末统一 `apply_writes`（`main.py:3050-3075`）。
4. **Checkpointer**：在 superstep 边界把「channels 快照 + 每个节点见过哪些版本（`versions_seen`）」落盘，按 `thread_id` 分线。
5. **谁约束谁**：**loop 是唯一的时钟**；node 不能决定自己何时结束（由 `_triggers` + `versions_seen` 决定下一步跑谁）；checkpointer 反过来是 `interrupt()` 的**前提**（`types.py:924-925`："To use an `interrupt`, you must enable a checkpointer, as the feature relies on persisting the graph state."）。
6. 结论式的一句：**它不是"让 LLM 调工具的框架"，是"把图执行状态做成可持久化、可从任意 superstep 边界重启的运行时"**。ReAct 只是它的一个特例。

---

## 2. 逐点证据（每点：文件:行 + 代码片段 + 它解决了什么问题）

### 2.1 interrupt / Command(resume) —— 可恢复中断到底怎么实现

**关键结论：中断不是"暂停一个活着的线程"，是"抛异常让整个 run 结束、把 resume 值当作下一次输入的 pending write 落盘"。恢复时从节点函数的第一行重新执行。**

**（a）`interrupt()` 本体 —— `libs/langgraph/langgraph/types.py:903-1046`**

签名 `def interrupt(value, *, response_schema=None) -> Any`。函数体只有 40 行，是整件事的核心：

```python
# types.py:1019-1046
    # track interrupt index
    scratchpad = conf[CONFIG_KEY_SCRATCHPAD]
    idx = scratchpad.interrupt_counter()
    # find previous resume values
    if scratchpad.resume:
        if idx < len(scratchpad.resume):
            v = scratchpad.resume[idx]
            validated = _validate_resume(adapter, v) if adapter else v
            conf[CONFIG_KEY_SEND]([(RESUME, scratchpad.resume[: idx + 1])])
            return validated
    # find current resume value
    v = scratchpad.get_null_resume(True)
    if v is not None:
        assert len(scratchpad.resume) == idx, (scratchpad.resume, idx)
        validated = _validate_resume(adapter, v) if adapter else v
        scratchpad.resume.append(v)
        conf[CONFIG_KEY_SEND]([(RESUME, scratchpad.resume)])
        return validated
    # no resume value found
    raise GraphInterrupt(
        (
            Interrupt.from_ns(
                value=value,
                ns=conf[CONFIG_KEY_CHECKPOINT_NS],
                response_schema=adapter.json_schema() if adapter else response_schema,
            ),
        )
    )
```

三件事，每一件都值得抄：

1. **`interrupt_counter()` 是一个每节点实例的原子自增计数**（`_algo.py:1339`，注释 `# using itertools.count as an atomic counter (+= 1 is not thread-safe)`）。同一个节点里第 N 个 `interrupt()` 就对应 `resume` 列表的第 N 个位置。**没有全局 interrupt id，靠"位置"匹配。**
2. **`scratchpad.resume` 是一个 list，不是单值**。文档写得很直白（`types.py:920-922`）："If a node contains multiple `interrupt` calls, LangGraph matches resume values to interrupts based on their order in the node. This list of resume values is scoped to the specific task executing the node and is not shared across tasks."
3. **没有 resume 值 → `raise GraphInterrupt`**，把 `value`（给人类看的问题）和一个 `id` 包进异常。`id` 是 `xxh3_128_hexdigest(ns)`（`types.py:644`），`ns` 是 checkpoint namespace —— 也就是**"这个中断在图的哪个位置"的哈希**，不是随机 UUID。这一点很重要：**同一个位置的中断 id 稳定可复现**。

**（b）`GraphInterrupt` 往上冒，被 loop 吃掉，然后落盘 —— `_loop.py:1500-1552`**

```python
# _loop.py:1500-1511
        # persist current checkpoint and writes
        if self.durability == "exit" and (
            not self.is_nested or exc_value is not None
            or all(NS_END not in part for part in self.checkpoint_ns)
        ):
            self._put_exit_delta_writes()
            self._put_checkpoint(self.checkpoint_metadata)
            self._put_pending_writes()
        # suppress interrupt
        if isinstance(exc_value, GraphInterrupt) and not self.is_nested:
            ...
            return True          # ← 吃掉异常，run 正常结束（不是失败）
```

`_retry.py:632-634` 也确认了：`except GraphBubbleUp: # if interrupted, end / raise` —— **中断不参与重试**。`GraphInterrupt` 是 `GraphBubbleUp` 的子类。

所以整个 run 的结局是 "status = interrupt"，**线程/进程可以完全结束**。客户端拿到 `Interrupt(value=..., id=...)`。

**（c）恢复：`Command(resume=v)` 变成一条 pending write —— `_io.py:74-78`**

```python
# _io.py:56-78
def map_command(cmd: Command) -> Iterator[tuple[str, str, Any]]:
    ...
    if cmd.resume is not None:
        yield (NULL_TASK_ID, RESUME, cmd.resume)
```

`_loop.py:1014-1056` 再把它们按 task_id 分组，`self.put_writes(tid, ws)` —— **resume 值就是一条写进 checkpoint_writes 表里的普通 write**，channel 名是 `"__resume__"`（`_constants.py:11`）。

**（d）恢复时如何重新调度被打断的那个节点 —— `_loop.py:935-946`**

```python
        is_resuming = bool(self.checkpoint["channel_versions"]) and bool(
            configurable.get(
                CONFIG_KEY_RESUMING,
                self.input is None                        # invoke(None, config)
                or input_is_command                       # 任何 Command
                or (not self.is_nested and self.config.get("metadata", {}).get("run_id")
                    == self.checkpoint_metadata.get("run_id", MISSING))
            ),
        )
```

即：**输入是 `None` 或 `Command` 就是在恢复**。恢复时会把 `versions_seen[INTERRUPT]` 补齐到当前版本（`_loop.py:1073-1078`），于是 `should_interrupt`（`_algo.py:156-186`）认为"没有新变化"，不会立刻又中断；而 `_triggers`（`_algo.py:1259-1276`）因为该 task 的待办 write 还没消费，会把它重新排进 `self.tasks`。

**（e）`scratchpad.resume` 是从哪里来的 —— `_algo.py:1279-1344`**

```python
def _scratchpad(parent_scratchpad, pending_writes, task_id, namespace_hash, resume_map, step, stop):
    if len(pending_writes) > 0:
        for w in pending_writes:
            if w[0] == task_id and w[1] == RESUME:
                task_resume_write = w[2]
                if not isinstance(task_resume_write, list):
                    task_resume_write = [task_resume_write]
                break
        ...
    def get_null_resume(consume: bool = False) -> Any:
        if null_resume_write is None:
            if parent_scratchpad is not None:
                return parent_scratchpad.get_null_resume(consume)
            return None
        if consume:
            try:
                pending_writes.remove(null_resume_write)   # ← "消费掉"
                return null_resume_write[2]
            except ValueError:
                return None
        return null_resume_write[2]
```

注意 `get_null_resume(consume=True)` 里的 `pending_writes.remove(...)`：**resume 值是被"取走"的，一次性**。这样同一份 checkpoint 若被重放第二次，`interrupt()` 会重新抛异常（需要你再答一次），而不是静默复用旧答案。

**（f）为什么必须"从头重放节点" —— `types.py:918`**

> "The graph resumes from the start of the node, **re-executing** all logic."

以及 `_algo.py:1295-1297` 的注释点出了设计取向：

```python
            # None cannot be used as a resume value, because it would be difficult to
            # distinguish from missing when used over http
```

**它解决的问题**：把"等人"这件事从"进程/线程/协程挂起"降级成"持久化一个待办写 + 结束本次 run"。代价是**节点必须可重放**（LangGraph 不做任何检查点续跑，节点体内的副作用会重做）。

---

### 2.2 checkpointer —— 存什么、怎么按 thread 恢复、格式

**（a）`thread_id` 是唯一主键 —— `libs/checkpoint/langgraph/checkpoint/base/__init__.py:199-230`**

```python
class BaseCheckpointSaver(Generic[V]):
    """Base class for creating a graph checkpointer.
    ...
    The `thread_id` is the primary key used to store and retrieve checkpoints. Without
    it, the checkpointer cannot save state, resume from interrupts, or enable
    time-travel debugging.
    ...
    - **Single-shot workflows**: Use a unique ID (e.g., uuid4) for each run when
        executions are independent.
    - **Conversational memory**: Reuse the same `thread_id` across invocations
        to accumulate state (e.g., chat history) within a conversation.
    """
```

compile 时若挂了 checkpointer 而 config 里没有这三个 key，直接报错（`main.py:2682-2686`）：

```python
        if checkpointer and not config.get(CONF):
            raise ValueError(
                "Checkpointer requires one or more of the following 'configurable' "
                "keys: thread_id, checkpoint_ns, checkpoint_id"
            )
```

**（b）一个 Checkpoint 存什么 —— `base/__init__.py:93-125`**

```python
class Checkpoint(TypedDict):
    v: int
    """The version of the checkpoint format. Currently `1`."""
    id: str
    """The ID of the checkpoint. This is both unique and monotonically increasing,
       so can be used for sorting checkpoints from first to last."""
    ts: str
    channel_values: dict[str, Any]
    channel_versions: ChannelVersions        # ch -> 单调递增版本
    versions_seen: dict[str, ChannelVersions]  # node_id -> {ch: 该节点见过的版本}
    updated_channels: list[str] | None
```

`versions_seen` 是**整个机制的灵魂**。它答的不是"现在状态是什么"，而是"每个节点已经消费到哪一版"，等价于"下一步该跑谁"。`_algo.py:1259-1276` 的 `_triggers` 就是拿 `channel_versions` 和 `versions_seen` 比大小来决定调度。

**（c）元数据 —— `base/__init__.py:39-87`**

```python
class CheckpointMetadata(TypedDict, total=False):
    source: Literal["input", "loop", "update", "fork"]
    step: int          # "input" 的首个为 -1，"loop" 的首个为 0
    parents: dict[str, str]
    run_id: str
    counters_since_delta_snapshot: dict[str, tuple[int, int]]   # beta，DeltaChannel 用
```

**（d）存 = 全量快照，每 superstep 一次 —— `pregel/_checkpoint.py:296-341`**

```python
    else:
        values = {}
        channel_versions = dict(checkpoint["channel_versions"])
        for k in channels:
            ch = channels[k]
            ...
            if k in channels_to_snapshot:
                values[k] = _DeltaSnapshot(ch.get())
            else:
                v = ch.checkpoint()
                if v is not MISSING:
                    values[k] = v          # ← 每个 channel 的完整当前值
```

`_loop.py:803` 在 `after_tick()` 里 `self._put_checkpoint({"source": "loop"})` —— **每个 superstep 一次**。这就是它敢承诺 "time travel 到任意一步" 的原因，也是它存储成本的根本来源：**状态多大，乘 superstep 数**。（他们自己在 `1.2.x` 加了 beta 的 `DeltaChannel` + `_DeltaSnapshot` 就是为了削这个成本 —— `base/__init__.py:64-87`、`_checkpoint.py:282-291`。）

**（e）InMemorySaver 的实际数据结构 —— `memory/__init__.py:430-512`**

```python
        self.storage[thread_id][checkpoint_ns].update(
            {
                checkpoint["id"]: (
                    self.serde.dumps_typed(c),                              # 快照本体
                    self.serde.dumps_typed(get_checkpoint_metadata(config, metadata)),
                    config["configurable"].get("checkpoint_id"),            # 父指针
                )
            }
        )
```

```python
        outer_key = (thread_id, checkpoint_ns, checkpoint_id)
        for idx, (c, v) in enumerate(writes):
            inner_key = (task_id, WRITES_IDX_MAP.get(c, idx))
            if inner_key[1] >= 0 and outer_writes_ and inner_key in outer_writes_:
                continue
            self.writes[outer_key][inner_key] = (task_id, c, self.serde.dumps_typed(v), task_path)
```

三层结构，一眼能看明白：
```
storage[thread_id][checkpoint_ns][checkpoint_id] = (blob, metadata_blob, parent_id)
writes [(thread_id, checkpoint_ns, checkpoint_id)][(task_id, idx)] = (task_id, channel, blob, task_path)
blobs  [(thread_id, checkpoint_ns, channel, version)] = blob      # channel 值的按版本去重池
```
**注意 `blobs` 那一层**：channel 值按 `(channel, version)` 存，checkpoint 只引版本号 —— 这是他们在"全量快照"之外唯一的重删手段。

**（f）读：不带 `checkpoint_id` 就取最新 —— `memory/__init__.py:253-288`**

```python
        if checkpoint_id := get_checkpoint_id(config):
            if saved := self.storage[thread_id][checkpoint_ns].get(checkpoint_id):
                ...
        else:
            if checkpoints := self.storage[thread_id][checkpoint_ns]:
                checkpoint_id = max(checkpoints.keys())      # uuid6，字典序 = 时间序
```

`checkpoint_id` 用 `uuid6`（`_checkpoint.py:334`：`str(uuid6(clock_seq=step))`），**所以 max() 拿最新是成立的**，且天然可排序 —— 这点跟我方 `events.jsonl` 的"事件 id ≡ 行号"是同一个思路，可以互相印证。

**（g）序列化格式 —— `serde/jsonplus.py:280-313`**

```python
    def dumps_typed(self, obj: Any) -> tuple[str, bytes]:
        if obj is None:
            return "null", EMPTY_BYTES
        elif isinstance(obj, bytes):
            return "bytes", obj
        elif isinstance(obj, bytearray):
            return "bytearray", obj
        else:
            try:
                return "msgpack", _msgpack_enc(obj)
            except ormsgpack.MsgpackEncodeError as exc:
                if self.pickle_fallback:
                    return "pickle", pickle.dumps(obj)
                raise exc
```

**是 `(类型标签, 字节)` 二元组**，不是裸 JSON。默认 `msgpack`，可退 `pickle`。（`jsonplus.py:104-117` 明确写了安全警告：如果攻击者能直接写你的 checkpoint 库，反序列化时可能 RCE。）

---

### 2.3 节点级重试与退避

**（a）`RetryPolicy` 的默认值 —— `libs/langgraph/langgraph/types.py:427-446`**

```python
class RetryPolicy(NamedTuple):
    initial_interval: float = 0.5     # 首次重试前等多久
    backoff_factor: float = 2.0       # 每次乘几
    max_interval: float = 128.0       # 上界
    max_attempts: int = 3             # 含首次
    jitter: bool = True
    retry_on: (type[Exception] | Sequence[type[Exception]] | Callable[[Exception], bool]) = default_retry_on
```

**（b）哪些异常重试 —— `_internal/_retry.py:1-29`（全文件 29 行）**

```python
def default_retry_on(exc: Exception) -> bool:
    import httpx, requests
    if isinstance(exc, ConnectionError):
        return True
    if isinstance(exc, httpx.HTTPStatusError):
        return 500 <= exc.response.status_code < 600
    if isinstance(exc, requests.HTTPError):
        return 500 <= exc.response.status_code < 600 if exc.response else True
    if isinstance(exc, (ValueError, TypeError, ArithmeticError, ImportError, LookupError,
                        NameError, SyntaxError, RuntimeError, ReferenceError,
                        StopIteration, StopAsyncIteration, OSError)):
        return False
    return True          # ← 默认重试！
```

**注意这个设计取向：白名单是"不重试"的那边，默认 `return True`。** 它的语义是"只有明确是程序写错了才不重试"。

**（c）退避怎么算 —— `pregel/_retry.py:657-682`（sync）/ `:805-831`（async，逐字相同）**

```python
            # attempts tracks failed tries only; it increments after a failure.
            attempts += 1
            if attempts >= matching_policy.max_attempts:
                raise
            interval = matching_policy.initial_interval
            interval = min(
                matching_policy.max_interval,
                interval * (matching_policy.backoff_factor ** (attempts - 1)),
            )
            sleep_time = interval + random.uniform(0, 1) if matching_policy.jitter else interval
            time.sleep(sleep_time)
            config = patch_configurable(config, {CONFIG_KEY_RESUMING: True})
```

即 `sleep = min(128, 0.5 * 2^(n-1)) + U(0,1)`。**jitter 是 `+U(0,1)` 加法抖动，不是乘性**。

**（d）重试的边界 —— `_retry.py:614-617`**

```python
        try:
            # clear any writes from previous attempts
            task.writes.clear()
            # run the task
            return task.proc.invoke(task.input, config)
```

**重试是"清空本 task 的写入 + 把节点函数整个重跑一遍"。** 加上 `_retry.py:632-634` 的 `except GraphBubbleUp: raise`（中断不重试）和 `:644-645` 的 `if not retry_policy: raise`（没配策略就直接抛）—— 粒度就是**整个节点**。

**它解决的问题**：把"LLM 调用 429 / 网络抖动"的容错下沉到图的运行时，节点作者不用写 try/catch。代价是**节点体内的副作用会被重做**，除非节点自己幂等。

---

### 2.4 superstep / recursion_limit / 流式事件

**（a）主循环 —— `pregel/main.py:3050-3079`**

```python
                # Similarly to Bulk Synchronous Parallel / Pregel model
                # computation proceeds in steps, while there are channel updates.
                # Channel updates from step N are only visible in step N+1
                # channels are guaranteed to be immutable for the duration of the step,
                # with channel updates applied only at the transition between steps.
                while loop.tick():
                    for task in loop.match_cached_writes():
                        loop.put_writes(task.id, task.writes, cached=True)
                    for _ in runner.tick([t for t in loop.tasks.values() if not t.writes], ...):
                        yield from _output(...)
                    loop.after_tick()
                    emit_graph_lifecycle_events(loop)
                    # wait for checkpoint
                    if durability_ == "sync":
                        loop._put_checkpoint_fut.result()
```

**一个 superstep = 一次 `tick()`（调度）+ 一轮 task 执行 + 一次 `after_tick()`（commit）+ 一次 checkpoint。** 就这四拍。

**（b）`recursion_limit` 不是"递归深度"，是"superstep 预算" —— `_loop.py:1881`**

```python
        self.step = self.checkpoint_metadata["step"] + 1
        self.stop = self.step + self.config["recursion_limit"] + 1
```

`tick()` 的第一件事（`_loop.py:656-660`）：

```python
        # check if iteration limit is reached
        if self.step > self.stop:
            self.status = "out_of_steps"
            return False
```

到顶之后 `main.py:3093-3102`：

```python
            if loop.status == "out_of_steps":
                msg = create_error_message(
                    message=(f"Recursion limit of {config['recursion_limit']} reached "
                             "without hitting a stop condition. ..."),
                    error_code=ErrorCode.GRAPH_RECURSION_LIMIT,
                )
                raise GraphRecursionError(msg)
```

**是抛异常，不是优雅停机。** 且 `recursion_limit < 1` 直接 `ValueError`（`main.py:2665-2666`）。

**（c）`durability`：checkpoint 什么时候真的落盘 —— `main.py:2798-2804`**

```
- `"sync"`:  Changes are persisted synchronously before the next step starts.
- `"async"`: Changes are persisted asynchronously while the next step executes.  # 默认
- `"exit"`:  Changes are persisted only when the graph exits.
```

`_loop.py:1283-1285` + `:1500-1511` 是它的实现：`do_checkpoint = ... and (exiting or self.durability != "exit")`。**也就是说它提供了"不每步落盘、只在退出时落一次"的档位** —— 这正是"运行级 checkpoint"和"每步 checkpoint"的开关。

**（d）`stream_mode` 七种 —— `types.py:131-145`**

```python
StreamMode = Literal["values", "updates", "checkpoints", "tasks", "debug", "messages", "custom"]
- `"values"`:    每步后发全部状态
- `"updates"`:   每步后只发节点名 + 增量
- `"custom"`:    节点内部用 StreamWriter 自己发
- `"messages"`:  LLM token 级
- `"checkpoints"`: 每次 checkpoint 时发一条，格式同 get_state()
- `"tasks"`:     task 起止
- `"debug"`:     "checkpoints" + "tasks"
```

发事件的地方就一处 `_emit`（`_loop.py:1557-1591`），`stream` 是一个 `(namespace, mode, value)` 三元组的队列。**注意 `"checkpoints"` 这一档**：它把"checkpoint 落盘"本身变成了一条可订阅的事件 —— 这跟我方 `events.jsonl` 追加一行 `{type:"checkpoint"}` 是同构的。

⚠️ 但这一整套（namespace 元组 + 7 种 mode + `debug` 重映射 + v1/v2 chunk 双格式，见 `main.py:4409-4433`）是它最臃肿的部分之一。

---

### 2.5 上下文压缩（trim/summarize/RemainingSteps）

**第一结论（重要）：LangGraph 本体里没有"上下文压缩"这个功能。** 我在 `libs/langgraph`、`libs/prebuilt`、`libs/checkpoint` 三个包的所有非测试 `.py` 里搜 `trim_messages|SummarizationNode|summarize_messages|token_count`，**零命中**。

它给的是**一个接缝**，不是一个实现。

**（a）`RemainingSteps` / `IsLastStep`：步数预算，不是 token 预算 —— `managed/is_last_step.py`（全文件 24 行）**

```python
class IsLastStepManager(ManagedValue[bool]):
    @staticmethod
    def get(scratchpad: PregelScratchpad) -> bool:
        return scratchpad.step == scratchpad.stop - 1

IsLastStep = Annotated[bool, IsLastStepManager]

class RemainingStepsManager(ManagedValue[int]):
    @staticmethod
    def get(scratchpad: PregelScratchpad) -> int:
        return scratchpad.stop - scratchpad.step

RemainingSteps = Annotated[int, RemainingStepsManager]
```

**就两行减法。** 但它是"managed value"：不占 state、不进 checkpoint、每次调用按 scratchpad 现算。这是一个极其便宜但很有用的设计 —— **把"剩余预算"作为只读视图注入节点，而不是当作状态去维护。**

**（b）`pre_model_hook` 的唯一作用：让你在"模型看到的消息"和"账本里的消息"之间插入一刀 —— `prebuilt/chat_agent_executor.py:396-397`**

```python
        pre_model_hook: An optional node to add before the `agent` node (i.e., the node that calls the LLM).
            Useful for managing long message histories (e.g., message trimming, summarization, etc.).
```

**实现机制全部在于一个新 state key `llm_input_messages`（`:636-658`）**：

```python
    def _get_model_input_state(state: StateSchema) -> StateSchema:
        if pre_model_hook is not None:
            messages = (
                _get_state_value(state, "llm_input_messages")
            ) or _get_state_value(state, "messages")
            ...
        _validate_chat_history(messages)
        state["messages"] = messages     # 只在喂给模型的那份 state 里覆盖
        return state
```

配合 `:723-742` 动态造一个 `CallModelInputSchema`：

```python
    if pre_model_hook is not None:
        class CallModelInputSchema(state_schema):  # type: ignore
            llm_input_messages: list[AnyMessage]
        input_schema = CallModelInputSchema
```

于是：**`messages` 是持久化的全量账本（进 checkpoint），`llm_input_messages` 是本轮喂给模型的压缩视图（不进 state 的历史）。** hook 节点可以随便裁/摘要，写进 `llm_input_messages`，模型就用压缩版，而 `messages` 一条不少。

**这就是它关于上下文压缩的全部答案，而且它是答案里最值钱的部分。**

**（c）ReAct 轮次控制 —— `:620-634` + `:684-692`**

```python
    def _are_more_steps_needed(state: StateSchema, response: BaseMessage) -> bool:
        has_tool_calls = isinstance(response, AIMessage) and response.tool_calls
        all_tools_return_direct = (...)
        remaining_steps = _get_state_value(state, "remaining_steps", None)
        if remaining_steps is not None:
            if remaining_steps < 1 and all_tools_return_direct:
                return True
            elif remaining_steps < 2 and has_tool_calls:
                return True
        return False
```

```python
        if _are_more_steps_needed(state, response):
            return {"messages": [AIMessage(id=response.id,
                    content="Sorry, need more steps to process this request.")]}
```

**预算快用完时，它不报错，而是伪造一条 assistant 消息说"我步数不够了"，让循环自然退出。** —— 这比 `GraphRecursionError` 友好得多，是个可以直接抄的小技巧。

默认预算 25（`:74`：`remaining_steps: RemainingSteps = 25`，Pydantic 版本）。

---

## 3. 值不值得借鉴

**我方约束（判定尺子）**：Java 17 + Spring Boot、**无数据库**、单机单用户、**要人能打开看**、代码不能变臃肿、事件流是 `events.jsonl`（事件 id ≡ 行号）。

| # | 机制 | 收益 | 在我们的代价 | 结论 |
|---|---|---|---|---|
| 3.1 | **`interrupt()` = 抛异常结束 run，`Command(resume=v)` = 把答案当一条 pending write 落盘** | 审批不再占线程，进程可以退出；重启后从持久化状态继续。这是唯一能真正解决"审批阻塞线程"的形状 | 小：一个自定义异常 + 一个 `pendingInput` 字段 + 一次 JSON 落盘。**不需要**它的 channel/versions_seen 机制 | **值得** |
| 3.2 | **resume 值按"节点内第 N 个 interrupt"位置匹配，不引入全局 id** | 省掉一张 interrupt 表、省掉 id 分配、省掉 id 冲突处理 | 极小 | **值得** |
| 3.3 | **"从节点第一行重放"这个取舍** | 换来实现极简：不需要"从中间续跑"的机制 | **风险高**：我方 ④开发 / ⑤测试 站的节点如果先干了活（改文件、跑容器）再审批，重放会把副作用做两遍 | **值得但必须配纪律**：见 §4 的硬约束 |
| 3.4 | **`versions_seen`（每个节点消费到哪一版）** | 这是"下一步该跑谁"的完整答案，也是可恢复性的根 | **大**：要引入 channel + 版本号体系，与我方"五站流水线 + 闸"的显式图重复；我方下一步是确定的，不需要推导 | **不值得** |
| 3.5 | **每 superstep 全量 `channel_values` 快照 + blobs 按版本去重** | 换来任意步 time-travel | **大**：无数据库，全量快照 × 步数会膨胀；而且我们要的是"运行级 checkpoint"不是"每步 checkpoint" | **不值得** |
| 3.6 | **运行级 checkpoint = `durability="exit"` 那一档：只在退出时落一次** | 正好是我们③要的东西，且成本 = 1 次落盘 | 小 | **值得** |
| 3.7 | **`checkpoint_id` 用 uuid6（字典序 = 时间序），`max()` 取最新** | 跟"事件 id ≡ 行号"同构，可以直接融进 events.jsonl | 小 | **值得（概念）** |
| 3.8 | **节点级重试 + `RetryPolicy` 默认值（0.5 / ×2 / ≤128 / 3 次 / +U(0,1) 抖动）** | 一处配置解决 LLM 429 / 网络抖动 | 小（~40 行） | **值得（参数），不值得（粒度）** |
| 3.9 | **`default_retry_on` 的"白名单写的是不重试"取向** | 简单可靠：只有明确的编程错误才不重试 | 极小 | **值得** |
| 3.10 | **"重试 = 清空本 task 的 writes + 重跑整个节点"** | 实现简单 | **危险**：节点有副作用时不幂等。我方一个节点里可能已经 `docker run` 过了 | **不值得**（重试下沉到"单次工具调用"层） |
| 3.11 | **`recursion_limit` = superstep 预算 + `GraphRecursionError`** | 有硬上限，不会死循环烧钱 | 小 | **值得（换个收尾方式）** |
| 3.12 | **`remaining_steps` / `is_last_step` 作为只读 managed value 注入** | 两行减法，让模型知道"还剩几步"；配 `_are_more_steps_needed` 的"伪造一条消息自然收尾" | 极小 | **值得** |
| 3.13 | **`pre_model_hook` + `llm_input_messages`：账本 ≠ 给模型的视图** | **这是压缩的正解**：全量历史照存（可读、可审计），每轮按需压缩成"喂给模型的视图"。两者分离，压缩策略可以随便改而不用动账本 | 小：state 里多一个字段 + 组装 prompt 时优先读它 | **值得** |
| 3.14 | **LangGraph 本体里没有任何 trim/summarize 实现** | 说明"压缩"不该是框架的事，是调用方的事 | —— | **佐证 3.13** |
| 3.15 | **`stream_mode` 7 档 + namespace 元组 + debug 重映射 + v1/v2 双格式** | 生态兼容 | **大**：我方 `events.jsonl` 已经是单一真相，再套一层流式事件模型就是纯臃肿 | **不值得** |
| 3.16 | **`DeltaChannel` / `_DeltaSnapshot` / fork / `checkpoint_ns` / `Send` / `Command.PARENT`** | 多图、子图、并行、bleeding-edge 存储优化 | **大**：单机五站流水线没有子图和并行 | **不值得** |
| 3.17 | **msgpack + pickle 双序列化 + RCE 警告** | 表达力强 | 大：我方要"人能打开看"，必须纯 JSON | **不值得** |
| 3.18 | **重试里 `except GraphBubbleUp: raise`（中断不重试）** | 一行保证"审批中断不会被重试逻辑吞掉" | 极小 | **值得** |

**一句话总账**：LangGraph 值得抄的是**四个"结构判断"**——(1) 中断=异常+落盘而不是挂线程；(2) resume 按位置匹配；(3) 只在边界落一次快照，存"下一步该干什么"而不是状态历史；(4) 账本和模型视图分离。**不值得抄的是它为此付出的全部机器**——channel 版本体系、superstep 全量快照、子图 namespace、7 种流模式。

---

## 4. 如果值得，给 Java 形态的最小实现草图

### 4.1 三条硬纪律（比代码更重要）

1. **`interrupt` 必须放在节点/轮次的最前面**：先审批，后干活。否则重放会把副作用做两遍（LangGraph 自己也这样，`types.py:918`）。
2. **一条 run 只允许一个未决 input**（单机单用户，不需要 interrupt id map）。
3. **checkpoint 只存"最新状态 + 下一步"，不存历史链**；历史在 `events.jsonl` 里。

### 4.2 类与签名

```java
// 中断：异常携带"人类要看的东西"，不携带挂起的调用栈
public final class WaitForInput extends RuntimeException {
    public final String prompt; public final JsonNode schema;         // 给前端渲染
    public WaitForInput(String prompt, JsonNode schema) { ... }
}

// 一条 checkpoint：只存"最新 + 下一步"，一行 JSON
public record Checkpoint(String runId, String station,               // ENV|PROBE|CASE|DEV|TEST
                         int reactTurn, long eventId,                // eventId = 与之同步的 events.jsonl 行号
                         JsonNode state,                             // 全量事实（可读）
                         PendingInput pending,                       // 非空 = 等人
                         String nextStep, Instant ts) {}

public interface CheckpointStore {                                   // 实现: run/<id>/checkpoint.json
    Optional<Checkpoint> load(String runId);
    void save(String runId, Checkpoint cp);                          // 原子写 + 追加一条 checkpoint 事件
}

public record PendingInput(String prompt, JsonNode schema, Instant askedAt) {}

public interface InputBus {                                          // 审批回填
    void ask(String runId, PendingInput p);                          // 写 run/<id>/input.request.json
    Optional<JsonNode> takeAnswer(String runId);                     // 有就取走并删除（一次性，对齐 get_null_resume(consume=True)）
}

// 节点拿到的上下文：既能读全量账本，也能拿压缩视图与剩余预算
public final class Ctx {
    public JsonNode ledger();                    // 全量（进 checkpoint）
    public List<Msg> modelView();                // 压缩视图（对齐 llm_input_messages）
    public int remainingSteps();                 // 对齐 RemainingSteps
    public JsonNode resume();                    // 本次的 resume 值，无则 null
}

public final class Runner {
    public RunOutcome run(String runId) {
        Checkpoint cp = store.load(runId).orElse(Checkpoint.fresh(runId));
        while (cp.nextStep() != null) {
            JsonNode answer = null;
            if (cp.pending() != null) {
                answer = bus.takeAnswer(runId).orElse(null);
                if (answer == null) return RunOutcome.waiting(cp.pending());   // 线程到此释放
            }
            try {
                Step s = registry.get(cp.nextStep());
                s.exec(new Ctx(runId, cp, answer));                            // 重放整个 step
            } catch (WaitForInput w) {
                bus.ask(runId, new PendingInput(w.prompt, w.schema, Instant.now()));
                store.save(runId, cp.awaiting(w));                             // 落盘后再返回
                return RunOutcome.waiting(cp.pending());
            }
            cp = cp.advance(/*nextStep=*/ s.next(cp));                        // 只前进一格
            store.save(runId, cp);                                            // 边界落一次
        }
        return RunOutcome.done(cp);
    }
}
```

### 4.3 伪代码（≤25 行，核心那一段）

```
run(runId):
  cp = store.load(runId) ?? Checkpoint.fresh(runId)

  while (cp.nextStep != null):                       # 每次循环 = 一格
    if (cp.pending != null):                         # 上一格在等人
      answer = bus.takeAnswer(runId)                 # 一次性取走
      if (answer == null): return WAITING            # ← 线程/请求到此结束，不阻塞
    else:
      answer = null

    try:
      step = registry.get(cp.nextStep)
      step.exec(Ctx(cp, answer))                     # 从这一格的第一行重放
    catch WaitForInput w:
      bus.ask(runId, w)                              # 1. 先落"我在等什么"
      store.save(cp.withPending(w))                  # 2. 再落 checkpoint
      return WAITING                                 # 3. 最后才返回 → 顺序不可换

    cp = cp.advance(step.nextResult(cp))             # 只前进一格
    store.save(cp)                                   # 边界落一次（对齐 durability="exit" 的"少落"精神）
    appendEvent(runId, "step_done", step, cp.eventId)

  return DONE
```

**压缩怎么接进去（3.13 的落地）**：`Ctx.modelView()` 默认返回 `ledger()` 的投影；某一站的 `Step` 可以返回一个 `compact()` 结果写进 `cp.state.modelView`，下一格 `modelView()` 就读它。**账本不动，只换视图。** 触发条件、摘要 prompt 全在业务侧，框架不认识它。

---

## 5. 不借鉴的部分与理由

1. **`versions_seen` / channel 版本体系** —— 它是 LangGraph 可恢复性的发动机，但我方的"下一步"是**显式图 + 闸**决定的，不需要从版本差推导。引入它等于把一张 5 节点的图换成一台调度器，纯增复杂度。
2. **每 superstep 全量 `channel_values` 快照** —— 它为此付出的代价已经大到要开 beta 的 `DeltaChannel` 去救（`base/__init__.py:64-87`）。我方无数据库，学它必膨胀。我们要的是"运行级 checkpoint"，取 `durability="exit"` 那一档就够。
3. **"重试整个节点"** —— `task.writes.clear()` 之后整节点重跑（`_retry.py:614-617`）。我方节点里有 `docker run` / 文件写入，重跑 = 副作用重做。**重试必须下沉到单次工具调用/单次 LLM 调用**。
4. **7 种 `stream_mode` + namespace 元组 + v1/v2 chunk 双格式**（`types.py:131-145`、`main.py:4409-4433`）—— `events.jsonl` 已经是唯一真相，再叠一层流式模型是自我重复。
5. **msgpack / pickle 序列化**（`jsonplus.py:280-313`）—— 我方硬约束是"人要能打开看"。JSON + `jq` 能读，msgpack 不能。
6. **子图 `checkpoint_ns` / `Send` / `Command.PARENT` / fork / time-travel** —— 单机单用户五站流水线，零需求。
7. **`GraphRecursionError` 这种"到顶就抛异常"的收尾** —— 学 `_are_more_steps_needed`（`chat_agent_executor.py:620-634`）的"伪造一条消息自然收尾"更好：跑完的现场留下的是可读的结果，不是一颗异常。
8. **多 interrupt id map（`resume: dict[str, Any]`）** —— 我方单用户，一个未决审批足矣。`_loop.py:1021-1031` 那套"多个 pending 时必须带 id"的分支是纯负担。

---

## 6. 未核实的点（诚实写）

1. **star 数**：本会话 `web_search` 未返回权威数字，不写猜测值。
2. **没跑过任何代码**：全部结论来自静态阅读。`libs/checkpoint-conformance` 这个测试包我没读，不知道它对 saver 实现有哪些硬约束。
3. **`durability="async"` 的真实时序**：`_checkpoint_put_after_previous` 这个 future 机制我只看到调用点（`_loop.py:1283`、`main.py:3079`），没读它的完整实现（`_checkpoint.py` 后段 348-546 行只扫过）。
4. **`DeltaChannel` 的完整语义**：`base/__init__.py:64-87`、`:150-183`、`:374-440` 读了，但 `delta_channels_to_snapshot` / `delta_channels_with_pending_writes` 的实现没读，§3.5/§5.2 的"成本很大"是基于它自己文档里的 beta 警告和 `_DeltaSnapshot` 机制推断的。
5. **`trim_messages` 归属**：我确认了**LangGraph 三个包内没有它**（非测试文件里零命中），但它大概率在 `langchain-core`（`langchain_core.messages.utils`）—— 该包不在本仓库，我没读到源码，不引用它的签名。
6. **`Interrupt.id` 在嵌套子图下的行为**：`id = xxh3_128_hexdigest(checkpoint_ns)`（`types.py:644`）我只按字面理解；多子图重名时是否会碰撞，没验证。
7. **`RemoteGraph`（`pregel/remote.py`，48KB）完全没读** —— 即"通过 HTTP 恢复中断"的那条路径。如果 specflow 未来要做"审批走 HTTP 回调"，这一块可能还有料，本轮未覆盖。
8. **`libs/sdk-py` / `libs/cli` 没读**。
9. **`interrupt_after` / `interrupt_before`（静态断点）** 只读了 `should_interrupt`（`_algo.py:156-186`），没追它在 loop 里的完整分支。
10. **`1.2.15` 是否对应某个 tag** 未确认（tarball 无 git 元数据，`git describe` 拿不到）。

---

## 7. 证据清单（文件:行）

> 根目录前缀：`E:\sf-research\langgraph\langgraph-main\`

### interrupt / Command(resume)
- `libs/langgraph/langgraph/types.py:903-1046` — `interrupt()` 全文（含 `interrupt_counter` 位置匹配、`get_null_resume(True)` 消费、`raise GraphInterrupt`）
- `libs/langgraph/langgraph/types.py:906-925` — 文档：抛异常、"resumes from the start of the node, re-executing all logic"、"must enable a checkpointer"
- `libs/langgraph/langgraph/types.py:920-922` — 多个 interrupt 按节点内顺序匹配，scope 是 task
- `libs/langgraph/langgraph/types.py:584-656` — `Interrupt` dataclass；`:644` `id=xxh3_128_hexdigest(ns)`
- `libs/langgraph/langgraph/types.py:833-882` — `Command`，`:857` `resume` 字段，`:845-846` 两种形态
- `libs/langgraph/langgraph/types.py:885-892` — `_validate_resume`（`response_schema` 校验，失败不落盘）
- `libs/langgraph/langgraph/_internal/_scratchpad.py:1-19` — `PregelScratchpad` 全字段
- `libs/langgraph/langgraph/pregel/_algo.py:1279-1344` — `_scratchpad()`：从 `pending_writes` 里捞 RESUME；`:1319-1330` `get_null_resume(consume)` 一次性消费；`:1295-1297` "None 不能当 resume 值"
- `libs/langgraph/langgraph/pregel/_algo.py:1332` — `# using itertools.count as an atomic counter`
- `libs/langgraph/langgraph/pregel/_io.py:56-78` — `map_command()`：`resume` → `(NULL_TASK_ID, RESUME, cmd.resume)`
- `libs/langgraph/langgraph/pregel/_loop.py:1013-1056` — `Command` → 按 task 分组 → `put_writes`；`:1016-1019` 无 checkpointer 直接 `RuntimeError`
- `libs/langgraph/langgraph/pregel/_loop.py:1021-1031` — resume 是 map（按 interrupt id）时的分支与"多个 pending 必须带 id"
- `libs/langgraph/langgraph/pregel/_loop.py:935-946` — `is_resuming` 的推断（input 为 None / Command / 同 run_id）
- `libs/langgraph/langgraph/pregel/_loop.py:948-978` — time-travel 与 resume 的分野；`:976-978` 重放时丢掉 RESUME 写
- `libs/langgraph/langgraph/pregel/_loop.py:1073-1078` — resume 时把 `versions_seen[INTERRUPT]` 补齐
- `libs/langgraph/langgraph/pregel/_loop.py:1500-1552` — 退出时落盘 + 吃掉 `GraphInterrupt`（`:1513`、`:1551-1552 return True`）
- `libs/langgraph/langgraph/pregel/_loop.py:907-913` — `_pending_interrupts()`
- `libs/langgraph/langgraph/pregel/_retry.py:632-634` — `except GraphBubbleUp: raise`（中断不重试）
- `libs/langgraph/langgraph/errors.py:51` / `:103` — `class GraphInterrupt(GraphBubbleUp)`（确认中断是 `GraphBubbleUp` 子类，所以逃过重试）
- `libs/langgraph/langgraph/errors.py:68` — `class GraphRecursionError(RecursionError)`
- `libs/langgraph/langgraph/pregel/_algo.py:156-186` — `should_interrupt()`（静态断点用 `versions_seen[INTERRUPT]` 判定）

### checkpointer
- `libs/checkpoint/langgraph/checkpoint/base/__init__.py:93-125` — `Checkpoint` 字段全表（`v/id/ts/channel_values/channel_versions/versions_seen/updated_channels`）
- `libs/checkpoint/langgraph/checkpoint/base/__init__.py:39-87` — `CheckpointMetadata`（`source` 四值、`step` 起点 -1/0、`parents`、`run_id`、`counters_since_delta_snapshot`）
- `libs/checkpoint/langgraph/checkpoint/base/__init__.py:140-147` — `CheckpointTuple`（含 `parent_config`、`pending_writes`）
- `libs/checkpoint/langgraph/checkpoint/base/__init__.py:199-230` — `thread_id` 是主键；两种选法（每 run 一个 uuid / 会话复用）
- `libs/checkpoint/langgraph/checkpoint/base/__init__.py:250-342` — `get / get_tuple / list / put / put_writes` 抽象签名
- `libs/checkpoint/langgraph/checkpoint/base/__init__.py:185-196` — `writes_sort_key(task_path, task_id, idx)`
- `libs/checkpoint/langgraph/checkpoint/base/__init__.py:150-183` — `DeltaChannelHistory`（beta 成本优化的证据）
- `libs/checkpoint/langgraph/checkpoint/memory/__init__.py:430-474` — `put()`：`blobs[(th,ns,ch,ver)]` + `storage[th][ns][cp_id] = (blob, meta, parent)`
- `libs/checkpoint/langgraph/checkpoint/memory/__init__.py:476-512` — `put_writes()`：`writes[(th,ns,cp_id)][(task_id, idx)]`
- `libs/checkpoint/langgraph/checkpoint/memory/__init__.py:253-288` — 不带 `checkpoint_id` 时 `max(checkpoints.keys())` 取最新
- `libs/checkpoint/langgraph/checkpoint/serde/jsonplus.py:280-313` — `dumps_typed/loads_typed`：`(type, bytes)`，`null/bytes/bytearray/json/msgpack/pickle`
- `libs/checkpoint/langgraph/checkpoint/serde/jsonplus.py:104-117` — 序列化 RCE 警告
- `libs/langgraph/langgraph/pregel/_checkpoint.py:268-341` — `create_checkpoint()`：逐 channel 取 `ch.checkpoint()` 全量快照；`:334` `uuid6(clock_seq=step)`
- `libs/langgraph/langgraph/pregel/_loop.py:1231-1350` — `_put_checkpoint()`；`:1283-1285` `do_checkpoint` 受 durability 门控
- `libs/langgraph/langgraph/pregel/_loop.py:803` — `after_tick` 里 `_put_checkpoint({"source": "loop"})`（每 superstep 一次）
- `libs/langgraph/langgraph/pregel/main.py:2682-2686` — 挂了 checkpointer 必须有 `thread_id`/`checkpoint_ns`/`checkpoint_id`
- `libs/langgraph/langgraph/pregel/main.py:1464-1532` — `get_state` / `get_state_history`（time-travel 入口）
- `libs/langgraph/langgraph/pregel/main.py:2617` — `update_state`

### 重试
- `libs/langgraph/langgraph/types.py:427-446` — `RetryPolicy` 默认值（0.5 / 2.0 / 128.0 / 3 / jitter / `default_retry_on`）
- `libs/langgraph/langgraph/_internal/_retry.py:1-29` — `default_retry_on()` 全文件
- `libs/langgraph/langgraph/pregel/_retry.py:600-682` — sync 重试循环（`:614-617` `writes.clear()` + 整节点重跑；`:660-674` 退避与抖动；`:682` 置 `RESUMING`）
- `libs/langgraph/langgraph/pregel/_retry.py:685-831` — async 版（逐字同构）
- `libs/langgraph/langgraph/pregel/_retry.py:834-847` — `_should_retry_on()`：Sequence / type / callable 三种
- `libs/langgraph/langgraph/pregel/_retry.py:59-153` — `TimeoutPolicy` / `_TimedAttemptScope`（超时与重试的关系，只扫读）

### superstep / recursion_limit / 流
- `libs/langgraph/langgraph/types.py:131-145` — `StreamMode` 7 值全表
- `libs/langgraph/langgraph/pregel/main.py:3050-3079` — 主 superstep 循环（BSP 注释 + `tick` → `runner.tick` → `after_tick` → checkpoint）
- `libs/langgraph/langgraph/pregel/main.py:3093-3106` — `out_of_steps` → `GraphRecursionError`；`draining` → `GraphDrained`
- `libs/langgraph/langgraph/pregel/main.py:2665-2666` — `recursion_limit < 1` → `ValueError`
- `libs/langgraph/langgraph/pregel/main.py:2798-2804` — `durability` 三档语义（`sync`/`async`/`exit`，默认 `async`）
- `libs/langgraph/langgraph/pregel/main.py:2828` — `durability = "async" if checkpoint_during else "exit"`（旧参映射）
- `libs/langgraph/langgraph/pregel/_loop.py:650-735` — `tick()`：`:658-660` 步数上限
- `libs/langgraph/langgraph/pregel/_loop.py:737-764` — `after_tick()`：汇总 writes、`apply_writes`
- `libs/langgraph/langgraph/pregel/_loop.py:1880-1882` — `step` / `stop = step + recursion_limit + 1`
- `libs/langgraph/langgraph/pregel/_loop.py:1557-1591` — `_emit()`：`(namespace, mode, value)` 单点发射 + debug 重映射
- `libs/langgraph/langgraph/pregel/main.py:4409-4433` — `_output()` 的 v1/v2 分支（`:4410-4425` typed chunk / `:4426-4433` 裸三元组），臃肿证据
- `libs/langgraph/langgraph/pregel/_algo.py:1259-1276` — `_triggers()`（版本差决定调度）

### 上下文压缩 / ReAct 轮次
- `libs/langgraph/langgraph/managed/is_last_step.py:1-24` — `RemainingSteps` / `IsLastStep` 全文（`stop - step`）
- `libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py:396-397` — `pre_model_hook` 文档："message trimming, summarization"
- `libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py:636-658` — `_get_model_input_state()`：`llm_input_messages` 优先于 `messages`
- `libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py:723-742` — 动态 `CallModelInputSchema`（加 `llm_input_messages`）
- `libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py:620-634` — `_are_more_steps_needed()`
- `libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py:684-692` — 预算不足时伪造 "Sorry, need more steps..." 消息
- `libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py:74` — `remaining_steps: RemainingSteps = 25`
- `libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py:787-824` / `:876-961` — `pre_model_hook`/`post_model_hook` 如何改接线
- 全仓搜索（`libs/**/*.py` 排除 tests）：`trim_messages|SummarizationNode|summarize_messages|token_count` → **零命中**
