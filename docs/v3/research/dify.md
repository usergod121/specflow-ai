# Dify · 深读

**仓库** `langgenius/dify` · **语言** Python · **star** 158678 · **默认分支** `main` · **pushed_at** 2026-10-17T05:07:16Z
引擎本体：`langgenius/graphon` · 84 star · **pushed_at** 2026-10-15T10:42:38Z
（`star / pushed_at` 均为实测 `GET https://api.github.com/repos/...`，非记忆值）

---

## 0. 先说一个必须纠正的前提（否则后面全是错的）

**任务里给的路径 `api/core/workflow/graph_engine/graph_engine.py`、`api/core/workflow/entities/node.py`、`.../event_management.py`、`.../entities/variable_pool.py`，在当前 `main` 上全部不存在。**

实测 `GET /repos/langgenius/dify/contents/api/core/workflow?ref=main` 返回的目录里没有 `graph_engine/`、没有 `entities/`：

```
graph_topology.py  llm_node.py  node_factory.py  node_runtime.py
node_execution_process_data.py  variable_pool_initializer.py  workflow_entry.py ...
nodes/{agent,agent_v2,datasource,human_input,knowledge_index,knowledge_retrieval,trigger_*}/
```

全树正则 `"path": "graph_engine` 只命中 `api/tests/unit_tests/core/workflow/graph_engine/`（只剩测试）。

原因在 `api/core/workflow/node_runtime.py:49-76` 的 import 里写得很直白：

```python
from graphon.file import File, FileTransferMethod, FileType
from graphon.nodes.runtime import ToolNodeRuntimeProtocol
from graphon.nodes.tool.exc import ToolNodeError
```

`api/core/workflow/nodes/agent/events.py:6-7` 同样：

```python
from graphon.graph_events import GraphNodeEventBase
from graphon.node_events import NodeEventBase
```

**结论：Dify 把工作流引擎整体抽成了独立包 `graphon`（PyPI 上有，`pypi.org/pypi/graphon/json` 实测可查），Dify 仓库里只剩"业务适配层"。** 所以本次深读的引擎源码来自 `langgenius/graphon@main`（`src/graphon/...`，全树 548 个文件、未截断），Dify 侧的 SSE 契约来自 `langgenius/dify@main`（`api/core/app/entities/task_entities.py`）。

> 这条本身就是给 specflow 的第一个结论：**引擎和执行宿主是可以切开的两层，切点就在"事件"上。**

---

## 1. 宏观：顶层抽象与约束（≤10 行）

1. **图 = 节点 + 有向边**（`nodes[].id` / `edges[].source,target`，见 `api/core/workflow/graph_topology.py:22-34`）。
2. **节点是生成器**：`_run()` 要么直接返回 `NodeRunResult`，要么 `yield` 一串事件（`nodes/base/node.py:621-633`）。
3. **节点永不向引擎抛异常**：`run()` 里 `try/except Exception` 兜底，任何异常都变成一条 `NodeRunFailedEvent`（`node.py:663-667`）。
4. **失败不是控制流中断，是一次"可配置的续走"**：`NodeFailureHandler.handle()` 把 `NodeRunFailedEvent` 翻译成 retry / fail-branch / default-value 事件，返回 `None` 才代表本作用域真失败（`engine/event/node_failure.py:48-97`）。
5. **执行是"就绪队列 + 固定 worker 池"**：默认 5 个 worker 抢 `ready_queue`，天然并行（`engine/engine.py:67,147-154`）。
6. **分支靠边的 taken/skipped 标记，不靠 if**：`NodeState.TAKEN/SKIPPED`，未选中的分支整条路径递归 skip，并各发一条事件（`engine/scheduler.py:296-366`）。
7. **事件是双层的**：节点只 yield "无 id 的 payload"，引擎补上 `id/node_id/node_type` 再发出去（`node_events/base.py:10` vs `engine_events/base.py`）。
8. **事件流是 `Generator`，pull 驱动**：`Engine.run()` 是个生成器，谁消费谁推动；SSE 只要把它翻成 `event:` 行（`engine/engine.py:211-236`）。
9. **嵌套容器用"帧"（Frame）隔离**：iteration/loop 各开一个 frame，有自己的 scheduler 和变量池作用域（`engine.py:116-123`）。
10. **状态落库分两层**：`workflow_runs` 存一次运行，`workflow_node_executions` 存每一次节点执行（含 `index` 序号、`predecessor_node_id`）。

---

## 2. 逐点证据

### 2.1 BaseNode 的契约

文件：`graphon/src/graphon/nodes/base/node.py`（1109 行）

**（a）唯一必须实现的钩子 —— `_run`（:621-633）**

```python
@abstractmethod
def _run(
    self,
) -> (
    NodeRunResult
    | Generator[
        NodeEventPayload | NodeEvent | ContainerAwaitRequest,
        None,
        None,
    ]
):
    """Run the node and return either a result object or an event stream."""
    raise NotImplementedError
```

无参数、无 `inputs` 形参。**输入不在签名里**——节点在 `__init__` 时已拿到 `runtime_state`（含变量池），`_run` 自己去取。这是刻意的：让"取输入"和"跑逻辑"解耦，节点可以在中途再取变量。

**（b）构造函数（:592-619）**

```python
def __init__(self, node_id: str, data: NodeDataT, *,
             init_params: InitParams, runtime_state: RuntimeState) -> None:
    if not node_id:
        raise ValueError("node_id is required")
    self._run_context = MappingProxyType(dict(init_params.run_context))
    self.id = node_id
    self.runtime_state = runtime_state
    self.state: NodeState = NodeState.UNKNOWN   # node execution state
    self._node_data = self.validate_node_data(data)
```

注意 `MappingProxyType`——run context 对节点是**只读**的。

**（c）模板方法 `run()`——整个契约的核心（:635-667）**

```python
def run(self) -> Generator[NodeEvent | ContainerAwaitRequest, None, None]:
    execution_id = self.execution_id
    self._start_at = datetime.now(UTC).replace(tzinfo=None)

    start_event = NodeRunStartedEvent(
        id=execution_id, node_id=self._node_id, node_type=self.node_type,
        node_title=self.title, start_at=self._start_at,
    )
    try:
        self.populate_start_event(start_event)
    except Exception:
        logger.warning("Failed to populate start event for node %s", self._node_id, exc_info=True)
    yield start_event

    try:
        yield from self._run_events()
    except Exception as e:
        logger.exception("Node %s failed to run", self._node_id)
        yield self._build_run_failed_event(e)
```

**它解决的问题**：① `started` 事件保证一定发得出（连 `populate_start_event` 挂了都只 warn 不炸）；② 节点作者写 `raise` 也不会让引擎崩，异常被规范化成 `FAILED` + `error_type="WorkflowNodeError"`（:723-738）。**引擎侧因此可以完全信任"节点永远只 yield 事件"这个不变量。**

**（d）结果 → 事件的统一转换（:669-682）**

```python
def _run_events(self) -> Generator[...]:
    result = self._run()
    if isinstance(result, NodeRunResult):
        yield self._convert_node_run_result_to_node_event(result)   # 一步到位的节点
        return
    for event in result:
        yield self._normalize_run_event(event)                       # 流式节点
```

**两种节点形态共用一个契约**：简单节点 `return NodeRunResult(...)`，LLM/Agent 节点 `yield` 一串 chunk。引擎不需要区分。

---

### 2.2 节点定义的字段（含 error strategy / retry）

**（a）节点数据的公共基类 `BaseNodeData`（`entities/base_node_data.py:144-168`）**

```python
class BaseNodeData(ABC, BaseModel):
    model_config = ConfigDict(extra="allow")      # 见下

    type: NodeType
    title: str = ""
    desc: str | None = None
    version: str = "1"
    error_strategy: ErrorStrategy | None = None
    default_value: list[DefaultValue] | None = None
    retry_config: RetryConfig = Field(default_factory=RetryConfig)

    @property
    def default_value_dict(self) -> dict[str, Any]:
        if self.default_value:
            return {item.key: item.value for item in self.default_value}
        return {}
```

**注意 `id` 不在这里。** 节点 id 在图 JSON 的外层（`node["id"]`），`data` 是内层。证据 `api/core/workflow/node_factory.py:192-197`：

```python
node_id = node.get("id")
data = node.get("data")
...
node_type = data.get("type")
```
以及 `NodeConfigDictAdapter.validate_python(...)` 后 `node_id = typed_node_config["id"]`（:418-420）。

**`extra="allow"` 是刻意的技术债**，源码注释（:146-153）写明：未知节点类型的专有字段、以及 `selected`/`params`/`paramSchemas`/`datasource_label` 这些历史键，在"共享 DTO"阶段不能拒；等 `node_factory` 解析出具体类后，再用具体 schema **重新校验一遍**（`node_factory.py:426-429`）。

> 对 specflow：这是一条**明确不要抄**的设计——它为了兼容存量 JSON 付出的代价。

**（b）错误策略枚举（`enums.py`）**

```python
class ErrorStrategy(StrEnum):
    FAIL_BRANCH = "fail-branch"
    DEFAULT_VALUE = "default-value"
```

只有两个值，**没有 "abort"**——因为 `None` 就是 abort。

**（c）重试配置（`base_node_data.py:17-26`）**

```python
class RetryConfig(BaseModel):
    """node retry config"""
    max_retries: int = 0        # max retry times
    retry_interval: int = 0     # retry interval in milliseconds
    retry_enabled: bool = False # whether retry is enabled

    @property
    def retry_interval_seconds(self) -> float:
        return self.retry_interval / 1000
```

**（d）默认值（`base_node_data.py:29-44`）**

```python
class DefaultValueType(StrEnum):
    STRING = "string"; NUMBER = "number"; BOOLEAN = "boolean"; OBJECT = "object"
    ARRAY_NUMBER = "array[number]"; ARRAY_STRING = "array[string]"
    ARRAY_BOOLEAN = "array[boolean]"; ARRAY_OBJECT = "array[object]"
    ARRAY_FILES = "array[file]"

class DefaultValue(BaseModel):
    value: Any = None
    type: DefaultValueType
    key: str
```

带一个 `model_validator` 做类型校验与字符串→数字/JSON 的转换（:71-141）。`key` 是**输出变量名**，所以 `default_value` 是一组"当节点挂了，用这些值冒充输出"。

**（e）节点属性的只读投影（`nodes/base/node.py:276-330`）**

```python
@property
def retry(self) -> bool: ...                        # = retry_config.retry_enabled
def _get_error_strategy(self) -> ErrorStrategy | None: ...
def _get_retry_config(self) -> RetryConfig: ...
def _get_default_value_dict(self) -> dict[str, Any]: ...

@property
def error_strategy(self) -> ErrorStrategy | None: ...
@property
def retry_config(self) -> RetryConfig: ...
@property
def default_value_dict(self) -> dict[str, Any]: ...
```

引擎只读这些 property，不碰 `node_data`。

**（f）节点执行状态的枚举（`enums.py`）**

```python
class WorkflowNodeExecutionStatus(StrEnum):
    PENDING = "pending"    # scheduled but not yet executing
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    EXCEPTION = "exception"
    STOPPED = "stopped"
    PAUSED = "paused"
    RETRY = "retry"        # Legacy: replaced by retry mechanism in error handling
```

**`FAILED` 和 `EXCEPTION` 是两个不同状态**，这是整个失败策略的关键：`FAILED` = 节点真错了；`EXCEPTION` = 错了但已被策略接管（走了 fail-branch 或填了默认值）。下游只看 `EXCEPTION` 就当作"有输出"继续跑。

**（g）节点运行结果 `NodeRunResult`（`node_events/base.py:19-40`）**

```python
class NodeRunResult(BaseModel):
    status: WorkflowNodeExecutionStatus = WorkflowNodeExecutionStatus.PENDING
    inputs: Mapping[str, Any] = Field(default_factory=dict)
    process_data: Mapping[str, Any] = Field(default_factory=dict)
    outputs: Mapping[str, Any] = Field(default_factory=dict)
    metadata: Mapping[WorkflowNodeExecutionMetadataKey, Any] = Field(default_factory=dict)
    llm_usage: LLMUsage = Field(default_factory=LLMUsage.empty_usage)

    edge_source_handle: str = "source"   # source handle id of node with multiple branches

    error: str = ""
    error_type: str = ""

    retry_index: int = 0
```

`edge_source_handle` 是**分支节点的出口选择器**——if-else 节点不改图，只改这个字段，调度器据此决定走哪条边。

---

### 2.3 图引擎怎么跑（分支、并行、失败传播）

**（a）装配（`engine/engine.py:62-190`）**

```python
def __init__(self, graph, runtime_state, command_channel=None, workers: int = 5, ...):
    dispatch_queue: queue.Queue[DispatchTask] = queue.Queue()
    self._frame_registry = FrameRegistry()
    root_frame = self._frame_registry.create(
        frame_id=ROOT_FRAME_ID, container_id="", graph=self._graph, state=self._runtime_state)
    self._scheduler = root_frame.scheduler
    self._event_stream = EventStream(self._layers)
    ...
    self._worker_pool = WorkerPool(ready_queue=..., dispatch_queue=..., workers=workers)
    event_processor = NodeEventProcessor(graph_execution=..., event_stream=..., frame_registry=...,
                                         container_handlers=...)
    self._dispatcher = Dispatcher(dispatch_queue=..., event_processor=..., scheduler=...)
```

**五个角色，职责单一**：`ready_queue`（待跑节点）→ `WorkerPool`（真跑，固定线程数）→ `dispatch_queue`（状态变更）→ `Dispatcher`（拿状态变更去推图）→ `Scheduler`（标记边/node 的 taken/skipped，算出新的 ready）。**并行 = 5 个 worker 同时抢 ready_queue，没有任何"并行节点"语法**。

**（b）并行**

`workers=5` 是构造参数，校验非正数直接抛（`engine.py:94-96`）。`_start_execution` 里先 `self._worker_pool.start()` 再 `enqueue_node(root)`，注释（:373）说明了顺序原因：

```python
# ReadyQueue.put() may block, so consumers must exist before scheduling.
self._worker_pool.start()
...
self._scheduler.enqueue_node(root_node.id)
```

**（c）分支：边的 takeover**

`Scheduler`（`engine/scheduler.py`）的核心状态是 node 的 `NodeState.TAKEN/SKIPPED` 和 edge 的 taken/skipped。

```python
def enqueue_node(self, node_id: str) -> None:
    """Mark a node as TAKEN and add its task to the ready queue."""
def mark_node_skipped(self, node_id: str) -> None:
    """Mark a node as SKIPPED."""
def mark_edge_taken(self, edge_id: str) -> None: ...
def mark_edge_skipped(self, edge_id: str) -> None: ...

def handle_branch_completion(self, ...) -> ...:
    """Advance a branch node along its selected path."""
def _skip_branch_paths(self, ...) -> ...:
    """Skip every path beginning with an unselected branch edge."""
def _skip_edge_path(self, edge: Edge) -> list[GraphEdgeSkippedEvent]:
    """Skip one edge and propagate its effect through the target path."""
def _propagate_skip_to_node(self, node_id: str) -> list[GraphEdgeSkippedEvent]:
    """Skip a node and recursively skip each of its outgoing paths."""
```

**关键收益**：图是静态的，分支不靠改图、不靠 if 语句，只靠"边标记 + 递归 skip"。而且**被跳过的边也发事件**（`GraphEdgeTakenEvent` / `GraphEdgeSkippedEvent`，`engine_events/traversal.py`），前端能把"没走的那条路"灰掉——这正好是"人能打开看"要的东西。

**（d）失败传播 —— 最值得读的一段**

`engine/event/node_failure.py:26-97`：

```python
@final
class NodeFailureHandler:
    """Select the configured continuation for a failed node.

    A retry or configured error strategy becomes another node event. Returning
    ``None`` tells the processor that the failure aborts its execution scope.
    """

    def handle(self, *, frame_id: str, event: NodeRunFailedEvent) -> NodeEvent | None:
        node = self._graph.nodes[event.node_id]
        node_execution = self._graph_execution.get_or_create_node_execution(
            frame_id=frame_id, node_id=event.node_id)
        retry_count = node_execution.retry_count

        if node.retry and retry_count < node.retry_config.max_retries:
            # Retry count is incremented when NodeRunRetryEvent is processed.
            return self._handle_retry(event, retry_count)

        strategy = node.error_strategy
        match strategy:
            case None:
                logger.error("Node %s failed without a continuation strategy: %s",
                             event.node_id, event.error)
                return None
            case ErrorStrategy.FAIL_BRANCH:
                return self._handle_fail_branch(event)
            case ErrorStrategy.DEFAULT_VALUE:
                return self._handle_default_value(event)
            case _:
                assert_never(strategy)
```

三条分支各自的产物：

```python
def _handle_retry(self, event, retry_count) -> NodeRunRetryEvent:
    time.sleep(node.retry_config.retry_interval_seconds)
    return NodeRunRetryEvent(id=event.id, ..., error=event.error,
                             retry_index=retry_count + 1)

def _handle_fail_branch(self, event) -> NodeRunExceptionEvent:
    outputs = {"error_message": event.node_run_result.error,
               "error_type": event.node_run_result.error_type}
    return NodeRunExceptionEvent(
        ...,
        node_run_result=NodeRunResult(
            status=WorkflowNodeExecutionStatus.EXCEPTION,
            inputs=event.node_run_result.inputs,
            process_data=event.node_run_result.process_data,
            outputs=outputs,
            edge_source_handle="fail-branch",          # ← 走失败分支的边
            metadata={WorkflowNodeExecutionMetadataKey.ERROR_STRATEGY:
                      ErrorStrategy.FAIL_BRANCH}))

def _handle_default_value(self, event) -> NodeRunExceptionEvent:
    outputs = {**node.default_value_dict,                # ← 配置的默认值
               "error_message": event.node_run_result.error,
               "error_type": event.node_run_result.error_type}
    ...  # status=EXCEPTION, edge_source_handle 保持默认 "source"
```

**四个要点，全部值得抄**：
1. **retry 优先级最高**，先于 error_strategy 判定（`handle` 里先 `if node.retry and ...`）。
2. **失败 → 事件，不是异常**。返回值要么是一个新事件（喂回 processor），要么 `None`（本作用域真失败）。整个续走逻辑没有一处 `raise`。
3. **`FAIL_BRANCH` 复用 `edge_source_handle`**——和 if-else 走同一个分支机制，不新增概念。
4. **`DEFAULT_VALUE` 和 `FAIL_BRANCH` 都强制注入 `error_message` / `error_type` 两个输出**，所以下游一定拿得到失败原因，可以自己决定要不要当失败。

**（e）全局终态：partial 也是一种成功**

`engine.py:279-324`：

```python
if self._graph_execution.error is not None:
    raise self._graph_execution.error
...
exceptions_count = self._graph_execution.exceptions_count
if exceptions_count > 0:
    partial_event = GraphRunPartialSucceededEvent(exceptions_count=exceptions_count, outputs=outputs)
    ...
    return
succeeded_event = GraphRunSucceededEvent(outputs=outputs)
```

**有 exception 但没 error → `GraphRunPartialSucceededEvent`**。`WorkflowExecutionStatus.PARTIAL_SUCCEEDED` 就是为此存在的。对应 specflow 的五站：某站走了 default-value 兜底，整体应该是"部分成功"而不是"失败"。

**（f）事件出口是生成器（`engine.py:211-236, 256-277`）**

```python
def run(self) -> Generator[EngineEvent, None, None]:
    events = self._run()
    error: BaseException | None = None
    try:
        while True:
            with use_workflow_file_runtime(self._file_runtime):
                try:
                    event = next(events) if error is None else events.throw(error)
                except StopIteration:
                    return
            try:
                yield event
            except BaseException as exc:
                error = exc     # 消费者（HTTP/SSE）出错要回灌，触发 finally 清理
    finally:
        events.close()
```

这段的用意：**消费端断开（浏览器关页面）不是靠轮询标志位，而是靠把异常 throw 回生成器**，从而 `finally` 里能确定性地 `close()` / `_stop_execution()`。骨架里非 `Object<Events.Node>` 的返回值（`NodeRunResult | Generator`）在这个 node 上未做判断。

---

### 2.4 SSE 事件类型清单（这是本次最想抄的部分）

**先分清三层，Dify 的三层命名容易看晕：**

| 层 | 位置 | 是什么 |
|---|---|---|
| ① `NodeEventPayload` | `graphon/node_events/*.py` | 节点 `yield` 的"裸 payload"，**没有 id** |
| ② `NodeEvent` / `EngineEvent` | `graphon/engine_events/*.py` | 引擎补齐 `id`(node execution id) / `node_id` / `node_type` 后的内部事件 |
| ③ `StreamEvent` | `dify/api/core/app/entities/task_entities.py:62-95` | 真正写到 SSE 的 `event:` 名 |

**③ 是线上契约，共 29 个值（`task_entities.py:67-95`，逐行照抄）：**

```python
class StreamEvent(StrEnum):
    PING = "ping"
    ERROR = "error"
    MESSAGE = "message"
    MESSAGE_END = "message_end"
    TTS_MESSAGE = "tts_message"
    TTS_MESSAGE_END = "tts_message_end"
    MESSAGE_FILE = "message_file"
    MESSAGE_REPLACE = "message_replace"
    AGENT_THOUGHT = "agent_thought"
    AGENT_MESSAGE = "agent_message"
    WORKFLOW_STARTED = "workflow_started"
    WORKFLOW_PAUSED = "workflow_paused"
    WORKFLOW_FINISHED = "workflow_finished"
    NODE_STARTED = "node_started"
    NODE_FINISHED = "node_finished"
    NODE_RETRY = "node_retry"
    ITERATION_STARTED = "iteration_started"
    ITERATION_NEXT = "iteration_next"
    ITERATION_COMPLETED = "iteration_completed"
    LOOP_STARTED = "loop_started"
    LOOP_NEXT = "loop_next"
    LOOP_COMPLETED = "loop_completed"
    TEXT_CHUNK = "text_chunk"
    TEXT_REPLACE = "text_replace"
    REASONING_CHUNK = "reasoning_chunk"
    AGENT_LOG = "agent_log"
    HUMAN_INPUT_REQUIRED = "human_input_required"
    HUMAN_INPUT_FORM_FILLED = "human_input_form_filled"
    HUMAN_INPUT_FORM_TIMEOUT = "human_input_form_timeout"
```

**逐个：名字 + 载荷 + 什么时候发**（载荷字段均摘自 `task_entities.py` 对应 `class Data`）

| 事件 | 载荷（`data` 内字段） | 什么时候发 |
|---|---|---|
| `ping` | 无（`:773-778`） | 长连接保活，无业务含义 |
| `error` | `status, code, message`（`:107-112`） | 任务级致命错误，流的终止 |
| `workflow_started` | `id, workflow_id, inputs, created_at, reason`（`:211-225`） | 一次运行开始；`reason` ∈ INITIAL/RESUMPTION（`:220-221` 注释：恒存在，镜像 `QueueWorkflowStartedEvent.reason`） |
| `workflow_finished` | `id, workflow_id, status, outputs, error, elapsed_time, total_tokens, total_steps, created_by, created_at, finished_at, exceptions_count, files`（`:233-254`） | 运行终态。**注意 `exceptions_count` 独立成字段** |
| `workflow_paused` | `workflow_run_id, paused_nodes, outputs, reasons, status, elapsed_time, total_tokens, total_steps`（`:262-279`） | 暂停（HITL 等待、人工审批） |
| `node_started` | `id, node_id, node_type, title, index, predecessor_node_id, inputs, inputs_truncated, created_at, extras, iteration_id, loop_id, agent_strategy`（`:380-401`） | 单节点开始。**`index` 是全局递增序号，`predecessor_node_id` 记上游** |
| `node_finished` | `id, node_id, node_type, title, index, predecessor_node_id, inputs, process_data, outputs, status, error, elapsed_time, execution_metadata, created_at, finished_at, files, iteration_id, loop_id`（`:429-458`） | 单节点结束。`status` 直接复用 `WorkflowNodeExecutionStatus`（含 `EXCEPTION`） |
| `node_retry` | 同 `node_started` 形状 + 重试信息（`:493-...`） | 失败且被判为可重试时，**重试前发一次** |
| `iteration_started` | 同 node 形状（`:559-574`） | 批处理容器开始 |
| `iteration_next` | `+ index, pre_iteration_output`（`:584-597`） | 每轮开始前 |
| `iteration_completed` | `... steps`（`:607-630`） | 批处理结束 |
| `loop_started/next/completed` | 同上，`pre_loop_output`（`:640-712`） | 循环容器 |
| `text_chunk` | 文本增量（`:722-730`） | 流式 token |
| `reasoning_chunk` | 推理增量（`:742-753`） | 思考链增量，**单独一种事件，不混进 text_chunk** |
| `text_replace` | 全文替换（`:762-769`） | 流式内容需整体纠正时 |
| `agent_thought` | 思考/工具调用（`:180-185`） | ReAct 的一个 step |
| `agent_message` | Agent 中间消息（`:196-201`） | Agent 阶段输出 |
| `agent_log` | `message_id, label, node_execution_id, parent_id, error, status, data, metadata`（`graphon/engine_events/agent.py`） | Agent 内部日志（工具调用明细） |
| `human_input_required` | `...`（`:283-300`） | 需要人填表，流挂起 |
| `human_input_form_filled` | `node_id, node_title, rendered_content, action_id, action_text, submitted_data`（`:341-355`） | 人提交后回放 |
| `human_input_form_timeout` | `node_id, node_title, expiration_time`（`:360-370`） | 表单超时 |
| `message/message_end/message_file/message_replace/tts_*` | — | 对话型应用专用，工作流引擎不关心 |

**每个事件都带 `to_ignore_detail_dict()`**（如 `:403-421`、`:460-485`）：把 `inputs`/`outputs`/`process_data` 置 `None`、`files` 置 `[]`，**同一事件有两种载荷精度**——详情视图 vs 概览视图。这是"给人看的前端"很实用的一招：列表页只收轻量版。

**关键观察（对 specflow 直接可用）**：
- 事件**全部**是 `event` + `task_id` + `workflow_run_id` + `data` 四段式（`StreamResponse`，`:98-105`）。
- **节点级只有 3 个事件**：`node_started` / `node_finished` / `node_retry`。**没有 `node_succeeded` / `node_failed` / `node_exception` 三个 SSE 事件**——它们全被压进 `node_finished` 的 `status` 字段。内部有 `NodeRunSucceededEvent`/`Failed`/`Exception`（`engine_events/node.py:79-101`），但对外收敛。
- `truncated` 是**显式布尔字段**（`inputs_truncated`/`outputs_truncated`/`process_data_truncated`），不是靠长度猜。

---

### 2.5 变量池

文件：`graphon/src/graphon/runtime/variable_pool.py`（357 行）

```python
type VariableValue = str | int | float | dict[str, object] | list[object] | File

class VariablePool(BaseModel):
    _SYSTEM_VARIABLE_NODE_ID = "sys"
    _ENVIRONMENT_VARIABLE_NODE_ID = "env"
    _CONVERSATION_VARIABLE_NODE_ID = "conversation"
    _RAG_PIPELINE_VARIABLE_NODE_ID = "rag"
    model_config = ConfigDict(extra="forbid")

    # Variable dictionary is a dictionary for looking up variables by their selector.
    # The first element of the selector is the node id.
    # It's the first-level key in the dictionary.
    # Other elements of the selector are keys in the second-level dictionary.
    # To get the key, we hash the elements of the selector except the first one.
    #
    # The `variable_dictionary` is the source of truth for the runtime
    # value of variables.
    variable_dictionary: defaultdict[
        str, Annotated[dict[str, Variable], Field(default_factory=dict)]
    ] = Field(description="Variables mapping", default_factory=_default_variable_dictionary)
```

**形状就是 `Map<nodeId, Map<restOfSelector, Variable>>`。** 一个 selector 如 `["1747829548239", "result"]` → `variable_dictionary["1747829548239"]["result"]`。

**"系统变量"不是特殊类型，只是保留 nodeId**（`sys`/`env`/`conversation`/`rag`）。这个技巧非常便宜：不需要任何 if 分支就能让系统变量走同一条取值路径。

**selector → mapping 的静态分析**（`nodes/base/node.py:740-781`，docstring 极清晰）：

```
{'1747829548239.#1747829667553.result#': ['1747829667553', 'result']}
```

key 是 `"<当前节点id>.<被 # 包住的 selector>"`，value 是 selector 数组。**这份映射是编译期产物，用来做"这个节点依赖谁"的拓扑分析**——不用运行时试错。

**变量写入走事件**（`node_events/node.py:93-96` → `engine_events/node.py:84-89`）：

```python
class VariableUpdatedEvent(NodeEventPayload):
    """Notify the engine that a single variable should be applied to the shared pool."""
    variable: Variable = Field(..., description="Updated variable payload to apply.")
```

节点**不直接写池**，是发事件；引擎 `_dispatch` 收到后统一应用（`node.py:936-944`）。这样变量变更也进了事件流，可回放。

---

### 2.6 运行状态存了哪些字段（为什么是这些）

文件：`api/models/workflow.py`

**（a）`workflow_runs`（一次运行，`:764-841`）**

```python
id, tenant_id, app_id,
workflow_id, type, triggered_from,
version, graph,          # graph = 本次运行的图快照（JSON）
inputs,
status,                  # WorkflowExecutionStatus
outputs, error,
elapsed_time, total_tokens, total_steps,
created_by_role, created_by,
created_at, finished_at,
exceptions_count,        # :832
```

**为什么是这些**：`graph` 存快照而不是版本号引用——图改了以后旧运行仍可**精确回放**。`exceptions_count` 单独存，因为 `status` 只有 `PARTIAL_SUCCEEDED` 一个值表示"部分成功"，**数量**必须另存。

**（b）`workflow_node_executions`（每一次节点执行，`:925-1031`）**

```python
id, tenant_id, app_id, workflow_id, triggered_from,
workflow_run_id,
index,                        # :1014  本次运行内的执行序号
predecessor_node_id,          # :1015  上游节点（单值，因为并行时"上一个"不重要，链路才重要）
node_execution_id,            # :1016  与事件里的 id 对齐
node_id, node_type, title,
agent_workspace_binding_id,
inputs, process_data, outputs,
status, error, elapsed_time,
execution_metadata,           # :1027  JSON：total_tokens/total_price/iteration_id/error_strategy/...
created_by_role, created_by,
created_at, finished_at,
```

**`process_data` vs `outputs` vs `execution_metadata` 三分**：
- `outputs` = 给下游用的；
- `process_data` = 给人看的中间态（LLM prompt/响应原文）；
- `execution_metadata` = 给系统用的（token/价格/iteration_id/`error_strategy`）。

`WorkflowNodeExecutionMetadataKey`（`enums.py`）就是这份 metadata 的键表，其中：

```python
ERROR_STRATEGY = "error_strategy"   # node in continue on error mode return the field
```

**节点挂了但被兜底时，这条元数据会写进库**——事后能查出"这个结果其实是默认值冒充的"。这是"人能打开看"的关键诚实性。

**（c）重试历史落进 `process_data`**（`api/core/app/workflow/retry_history.py`）

```python
RETRY_HISTORY_PROCESS_DATA_KEY = "__dify_retry_history"

class WorkflowNodeRetryAttempt(BaseModel):
    """Complete data captured for one failed node attempt before a retry."""
    retry_index: int = Field(gt=0)
    inputs: Mapping[str, Any]
    process_data: Mapping[str, Any]
    outputs: Mapping[str, Any]
    error: str
    elapsed_time: float = Field(ge=0)
    execution_metadata: Mapping[str, Any]
    created_at: int
    finished_at: int
    model_config = ConfigDict(extra="forbid")
```

**每次失败的尝试都完整留档**，不覆盖。所以"重试了3次最后成功"在库里有 4 条记录的证据。`__dify_retry_history` 用双下划线前缀表明"这是保留键"。

**（d）另外还专门建了 `workflow_pauses` / `workflow_pause_reasons` 两张表**（`:2088-2157`）——暂停原因是**一等公民**，不是挤在 JSON 里。

---

## 3. 值不值得借鉴

| 机制 | 收益 | 代价 | 结论 |
|---|---|---|---|
| **失败→事件（`NodeFailureHandler` 返回 `NodeEvent \| None`）** | 续走逻辑集中一处、可单测；节点作者不用知道重试策略；异常不外泄 | 需要"节点只 yield 不抛"的强约定 + 一个兜底 `try/except` | **强推**。这是全文最值钱的一条 |
| **`ErrorStrategy` 只有 `fail-branch` / `default-value`，`None` 即 abort** | 2 个值覆盖 95% 需求；abort 是缺省因而不占枚举 | 无 | **强推** |
| **`FAIL_BRANCH` 复用 `edge_source_handle`** | 不给分支引入新概念；失败路径在图上可见 | 要求节点/边模型已有 handle | **强推** |
| **`FAILED` vs `EXCEPTION` 双状态** | 下游能区分"真错"和"兜底过" | 枚举多一个值 | **强推** |
| **`node_finished` 单事件 + `status` 字段（对外收敛）** | SSE 事件种类少一半，前端一个 handler 搞定 | 前端要 switch status | **强推** |
| **`node_retry` 独立事件** | 前端能显示"第 2 次尝试"，而不只是最后结果 | 多一个事件类型 | **强推** |
| **`workflow_started` 带 `reason`（INITIAL/RESUMPTION）** | 重放/续跑时前端不用猜 | 一个字段 | **抄** |
| **`index` + `predecessor_node_id` 落到节点执行表** | 时间线可精确重建，顺序确定 | 两列 | **抄** |
| **`PARTIAL_SUCCEEDED` + `exceptions_count`** | "有几站兜底了"可查询、可告警 | 一个状态 + 一列 | **抄** |
| **`to_ignore_detail_dict()` 双精度载荷** | 列表页轻、详情页全 | 每事件写两遍字段名 | **抄**（specflow 用 `?detail=false` 就够） |
| **重试历史 `__dify_retry_history` 全量留档** | "重试3次成功"有证据 | 存储增长 | **抄**，但可以只留最后一次失败的 |
| **系统变量用保留 nodeId（`sys`/`env`）** | 零特殊分支 | 需要 id 命名空间隔离 | **抄**（specflow 的①环境站正好是 `env`） |
| **`VariableUpdatedEvent` 而非直接写池** | 变量变更也进事件流 | 一次间接 | 看情况；specflow 单用户可直写 |
| **5 worker 池 + ready_queue 的图调度** | 真并行、概念少 | 线程/锁、事件顺序不确定 | **不抄**（见 §5） |
| **Frame 嵌套容器（iteration/loop）** | 任意深度嵌套 | 极重，`frame.py`+`scheduler.py`+`container_handler/*` 约 60KB | **不抄**（见 §5） |
| **`extra="allow"` 的宽松 DTO + 二次校验** | 兼容历史 JSON | 校验跑两遍、类型不安全窗口 | **不抄**（见 §5） |
| **`time.sleep()` 做重试间隔** | 简单 | 阻塞 worker 线程，5 个 worker 全睡就停摆 | **不抄**（见 §5） |

---

## 4. Java 形态的最小实现草图（≤25 行）

```java
// 1) 节点只产事件，不抛给引擎
public interface Node { String id(); String title(); NodeConfig config(); Iterator<NodeEvent> run(Ctx c); }

// 2) 事件：SEALED，种类刻意少
public sealed interface NodeEvent permits Started, Finished, Retry, EdgeTaken, EdgeSkipped {}
public record Started(String execId, String nodeId, String title, int idx, Instant at) implements NodeEvent {}
public record Finished(String execId, String nodeId, Status st, Map<String,Object> outputs,
                       String error, long ms, Map<String,Object> meta) implements NodeEvent {}
public record Retry(String execId, String nodeId, String error, int retryIndex, Instant at) implements NodeEvent {}

// 3) 失败策略：一次匹配，返回下一种事件；null = 本作用域失败
static NodeEvent onFailure(Node n, Failed e, NodeState s) {
    var rc = n.config().retry();
    if (rc.enabled() && s.retryCount() < rc.maxRetries())
        return new Retry(e.execId(), e.nodeId(), e.error(), s.retryCount() + 1, now());
    return switch (n.config().errorStrategy()) {
        case FAIL_BRANCH   -> exc(e, withErr(Map.of(), e),                "fail-branch");
        case DEFAULT_VALUE -> exc(e, withErr(n.config().defaults(), e),  "source");
        case null          -> null;                       // abort 本作用域
    };
}
static Finished exc(Failed e, Map<String,Object> out, String handle) {   // STATUS=EXCEPTION
    return new Finished(e.execId(), e.nodeId(), Status.EXCEPTION, out, e.error(), e.ms(),
                        Map.of("error_strategy", handle), handle);
}
```

配一条 `events.jsonl` 铁律：`id ≡ 行号`，且**只允许这 5 种 type**；`EXCEPTION` 与 `FAILED` 分开，`finished` 一条带 `status`。

---

## 5. 不借鉴的部分与理由

**（1）`Engine` + `WorkerPool` + `ready_queue` + `Dispatcher` 全套图调度 —— 不抄。**
理由：`engine/engine.py` 416 行、`dispatcher.py` 7859B、`scheduler.py` 13788B、`worker/*` 约 19KB、`frame.py` 10027B，加起来 50KB+ 只为实现"多节点并行 + 分支 + 嵌套容器"。specflow 是**五站流水线、单用户、单机**——五站本身是**强顺序**的（①→②→③→④→⑤ 带闸），并行需求为零。引入就为了零收益付巨大的复杂度。**specflow 需要的是 `for (int i=0;i<5;i++) { gate(i); runStation(i); }` 外加站内的 ReAct 循环。**

**（2）Frame / iteration / loop 嵌套容器 —— 不抄。**
理由：`container_handler/builtin/iteration.py`(18588B) + `loop.py`(19396B) + `runtime_state/v1,v2,v3.py`(约 19KB 的手写迁移代码) 是为"用户能在画布上任意嵌套"服务的。specflow 的迭代是**站内 ReAct 的 while 循环**，不是图里的容器节点。而且 `runtime_state/v1|v2|v3` 这种**版本化快照迁移链**正是"代码臃肿"的典型来源。

**（3）`BaseNodeData.model_config = ConfigDict(extra="allow")` —— 不抄。**
理由：源码自己承认（`base_node_data.py:146-153`）这是为兼容 `selected`/`params`/`paramSchemas` 等历史键，代价是**同一份 JSON 校验两遍**（`node_factory.py:418-429`：先宽松 DTO，解析出具体类后"re-validate using the resolved node class"）。specflow 无存量数据，用 Java `record` + Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true` 直接严格校验即可。

**（4）`time.sleep()` 实现重试间隔 —— 不抄。**
理由：`node_failure.py:119` 在 `_handle_retry` 里直接 `time.sleep(node.retry_config.retry_interval_seconds)`。这在 worker 池里会**阻塞整个 worker 线程**；`workers=5` 时若有 5 个节点同时重试，引擎直接停摆。specflow 单线程顺序执行，用 `ScheduledExecutorService` 或干脆 `Thread.sleep` 都可接受，但要**显式知道这是个阻塞点**，不能当成无代价。

**（5）`WorkflowNodeExecutionStatus.RETRY` —— 不抄（enum 自己说了是 legacy）。**
原注释：`RETRY = "retry"  # Legacy: replaced by retry mechanism in error handling`。抄了会引入一个永远不该被写进库的状态。

**（6）29 个 SSE 事件里对话型应用的那些（`message*` / `tts_*` / `agent_thought` / `agent_message`）—— 不抄。**
理由：这些是 Dify "应用"层的产物，不是引擎产物。specflow 没有对话型应用，Agent 的 ReAct 步骤用 `agent_log` 一个事件（或直接复用 `node_*` 的嵌套）就够。

**（7）三层事件模型（Payload → NodeEvent → StreamEvent）—— 只抄两层。**
理由：`NodeEventPayload`（无 id）→ `NodeEvent`（补 id）这层拆分是有价值的（让节点不用自己拼 id）；但第三层 `StreamEvent` 的 `.Data` 嵌套 + `to_dict()`/`to_ignore_detail_dict()` 三套序列化，是为了同时服务 SSE、DB、日志三种消费者。specflow 只有 `events.jsonl` 一种消费者，**两层足够**。

---

## 6. 未核实点

1. **`graphon/src/graphon/graph/graph.py`（33963B）未读到**：两次 `curl` 分别返回 87 字节（疑似 404 文本），本次未重试成功。因此**图结构（`Graph` 的 `nodes` 字典、`root_node`、`Edge` 的 `source_handle` 字段定义）的精确形状未经直接阅读**，§2.3 中关于边的描述依据的是 `engine/scheduler.py` 的方法签名与 docstring、`api/core/workflow/graph_topology.py:22-34`、以及 `engine_events/traversal.py` 的字段，而非 `graph.py` 本身。
2. **`graphon/src/graphon/entities/workflow_node_execution.py` 抓到 0 字节**（树里标注 5682B），未重试成功。§2.6 的节点执行字段因此全部取自 **Dify 侧 ORM** `api/models/workflow.py:925-1031`（这是落库形状，更贴近需求），而非 graphon 的运行时实体。
3. **`engine/event/processor.py`（16081B）与 `engine/dispatcher.py`（7859B）只读了类/方法清单，未逐行读实现**。因此"失败事件如何喂回 processor、retry 计数在哪一步 +1"只核实到 `node_failure.py:78` 的注释（`Retry count is incremented when NodeRunRetryEvent is processed.`），**未看到递增那一行代码**。
4. **codeload 全量 tarball 下载超时**（`curl -r 0-100 https://codeload.github.com/.../main` 在 300s 上限内未返回），因此**没有做全仓库级别的 grep**，可能漏掉其他相关实现。本次结论基于定向抓取的具体文件。
5. **`langgenius/graphon` 的 star 数 84** 与 Dify 的 15.8 万差距巨大；本报告未核实 graphon 是否就是 Dify 官方维护的同一份引擎（只核实了 Dify `main` 的 import 引用了同名包、且 PyPI 上 `graphon` 自述为 "Python graph execution engine for agentic AI workflows"）。若二者实为不同项目，§2 引擎侧证据需重新定位。
6. **本地 `E:\sf-research\dify-1.17.1.tgz` 解包后不含 `api/core/` 目录**（只有 `api/{clients,commands,configs}` 等），因此**无法用 1.17.1 反证"老版本是否就是任务里描述的结构"**。

---

## 7. 证据清单

**引擎（`graphon@main`，本地 `E:\sf-research\graphon\`，远程 `https://raw.githubusercontent.com/langgenius/graphon/main/<path>`）**

| 文件 | 行 | 内容 |
|---|---|---|
| `src/graphon/nodes/base/node.py` | 621-633 | `_run` 签名（`NodeRunResult \| Generator[NodeEventPayload\|NodeEvent\|ContainerAwaitRequest]`） |
| `src/graphon/nodes/base/node.py` | 592-619 | `__init__`：`node_id`/`data`/`init_params`/`runtime_state`，`MappingProxyType` 只读 run context |
| `src/graphon/nodes/base/node.py` | 635-667 | `run()`：started 事件 + catch-all → failed 事件 |
| `src/graphon/nodes/base/node.py` | 669-682 | `_run_events()`：结果 or 流，两形态共用契约 |
| `src/graphon/nodes/base/node.py` | 276-330 | `error_strategy` / `retry_config` / `default_value_dict` 只读投影 |
| `src/graphon/nodes/base/node.py` | 740-781 | selector→mapping 静态分析 docstring |
| `src/graphon/nodes/base/node.py` | 858-1101 | `_dispatch`：11 个 `NodeEventPayload` → `NodeEvent` 的 `singledispatch` 映射 |
| `src/graphon/entities/base_node_data.py` | 17-26 | `RetryConfig` |
| `src/graphon/entities/base_node_data.py` | 29-44 | `DefaultValueType` / `DefaultValue` |
| `src/graphon/entities/base_node_data.py` | 144-168 | `BaseNodeData`（`extra="allow"`、`error_strategy`、`default_value`、`retry_config`） |
| `src/graphon/entities/base_node_data.py` | 146-153 | 宽松 DTO 的自述技术债注释 |
| `src/graphon/enums.py` | — | `ErrorStrategy{FAIL_BRANCH,DEFAULT_VALUE}`、`NodeState{UNKNOWN,TAKEN,SKIPPED}`、`NodeExecutionType`、`FailBranchSourceHandle`、`WorkflowExecutionStatus`(含 `PARTIAL_SUCCEEDED`)、`WorkflowNodeExecutionStatus`(含 `FAILED`/`EXCEPTION`/`RETRY`)、`WorkflowNodeExecutionMetadataKey` |
| `src/graphon/node_events/base.py` | 19-40 | `NodeRunResult`（`outputs`/`process_data`/`metadata`/`edge_source_handle`/`error_type`/`retry_index`） |
| `src/graphon/node_events/node.py` | 43-121 | `RunRetryEvent`/`StreamChunkEvent`/`StreamReasoningEvent`/`VariableUpdatedEvent`/`PauseRequestedEvent`/`HumanInput*` |
| `src/graphon/engine_events/base.py` | — | `EngineEvent{container_id}` / `NodeEvent{id,node_id,node_type,node_version,node_run_result}` |
| `src/graphon/engine_events/node.py` | 13-143 | `NodeRunStartedEvent`…`NodeRunRetryEvent`(继承 Started)、`NodeRunExceptionEvent`、`is_node_result_event()` |
| `src/graphon/engine_events/graph.py` | — | `GraphRunStarted/Succeeded/Failed/PartialSucceeded/Aborted/PausedEvent` |
| `src/graphon/engine_events/traversal.py` | — | `GraphEdgeTakenEvent` / `GraphEdgeSkippedEvent`（`frame_id,edge_id,source_node_id,target_node_id,source_handle`） |
| `src/graphon/engine_events/iteration.py` / `loop.py` | — | 6 个容器事件 |
| `src/graphon/engine_events/agent.py` | — | `NodeRunAgentLogEvent` |
| `src/graphon/engine/engine.py` | 62-190 | `Engine` 装配（worker_pool/dispatcher/scheduler/event_stream/frame_registry） |
| `src/graphon/engine/engine.py` | 211-236 | `run()` 生成器，消费者异常回灌 |
| `src/graphon/engine/engine.py` | 256-277 | `_run_graph()`：started → emit_events → terminal |
| `src/graphon/engine/engine.py` | 279-324 | terminal：aborted / error raise / paused / **partial_succeeded** / succeeded |
| `src/graphon/engine/engine.py` | 373-383 | `worker_pool.start()` 必须先于 `enqueue_node(root)` |
| `src/graphon/engine/event/node_failure.py` | 26-31 | `NodeFailureHandler` 类 docstring（"becomes another node event"） |
| `src/graphon/engine/event/node_failure.py` | 48-97 | `handle()`：retry 优先 → `FAIL_BRANCH`/`DEFAULT_VALUE`/`None` |
| `src/graphon/engine/event/node_failure.py` | 99-129 | `_handle_retry`（`time.sleep` + `retry_index=retry_count+1`） |
| `src/graphon/engine/event/node_failure.py` | 131-168 | `_handle_fail_branch`（`status=EXCEPTION`、`edge_source_handle="fail-branch"`、注入 `error_message`/`error_type`） |
| `src/graphon/engine/event/node_failure.py` | 170-209 | `_handle_default_value`（`{**node.default_value_dict, error_message, error_type}`） |
| `src/graphon/engine/scheduler.py` | 47-110 | `enqueue_node`/`mark_node_skipped`/`mark_edge_taken`/`mark_edge_skipped` |
| `src/graphon/engine/scheduler.py` | 262-366 | `handle_branch_completion` / `_skip_branch_paths` / `_propagate_skip_to_node` |
| `src/graphon/runtime/variable_pool.py` | 21-49 | `VariableValue` + `VariablePool.variable_dictionary` 及 selector 语义注释 |
| `src/graphon/runtime/variable_pool.py` | 29-32 | 保留 nodeId：`sys`/`env`/`conversation`/`rag` |
| `src/graphon/runtime/variable_pool.py` | 76-112 | `from_bootstrap()` |

**宿主/契约（`dify@main`，本地 `E:\sf-research\main\`）**

| 文件 | 行 | 内容 |
|---|---|---|
| `api/core/app/entities/task_entities.py` | 62-95 | **`StreamEvent` 29 个 SSE 事件名** |
| `api/core/app/entities/task_entities.py` | 98-105 | `StreamResponse` 基类（`event`/`task_id`/`workflow_run_id`/`data`） |
| `api/core/app/entities/task_entities.py` | 206-279 | `workflow_started`/`workflow_finished`/`workflow_paused` 载荷 |
| `api/core/app/entities/task_entities.py` | 375-521 | `node_started`/`node_finished`/`node_retry` 载荷 + `to_ignore_detail_dict` |
| `api/core/app/entities/task_entities.py` | 554-712 | iteration/loop 六事件载荷 |
| `api/core/app/apps/common/workflow_response_converter.py` | 全 | `workflow_*_to_stream_response` 映射函数（21 个 def，实测清单） |
| `api/core/app/workflow/retry_history.py` | 全 | `RETRY_HISTORY_PROCESS_DATA_KEY="__dify_retry_history"` + `WorkflowNodeRetryAttempt` |
| `api/core/app/workflow/layers/persistence.py` | 类清单 | `WorkflowPersistenceLayer`：`on_graph_start/on_event/on_graph_end` + 每事件一个 `_handle_*` |
| `api/models/workflow.py` | 764-841 | `WorkflowRun` 全字段（含 `exceptions_count:832`） |
| `api/models/workflow.py` | 925-1031 | `WorkflowNodeExecutionModel` 全字段（`index:1014`、`predecessor_node_id:1015`、`process_data:1022`、`execution_metadata:1027`） |
| `api/models/workflow.py` | 2088-2157 | `workflow_pauses` / `workflow_pause_reasons` |
| `api/core/workflow/node_runtime.py` | 49-76 | `from graphon...` —— 引擎已外置的直接证据 |
| `api/core/workflow/nodes/agent/events.py` | 6-7 | `from graphon.graph_events / graphon.node_events` |
| `api/core/workflow/graph_topology.py` | 22-34 | `nodes[].id` / `edges[].source,target` 图 JSON 形状 |
| `api/core/workflow/node_factory.py` | 181-201 | `node["id"]` 与 `node["data"]["type"]` 分离 |
| `api/core/workflow/node_factory.py` | 405-432 | `create_node`：宽松校验 → 解析具体类 → 重新严格校验 |

**元数据**

- `GET https://api.github.com/repos/langgenius/dify` → `stargazers_count=158678`, `language=Python`, `default_branch=main`, `pushed_at=2026-10-17T05:07:16Z`
- `GET https://api.github.com/repos/langgenius/graphon` → `stargazers_count=84`, `pushed_at=2026-10-15T10:42:38Z`, `size=2323`
- `GET https://api.github.com/repos/langgenius/graphon/git/trees/main?recursive=1` → 548 项，`truncated=false`
- `GET https://pypi.org/pypi/graphon/json` → `"Graphon is a Python graph execution engine for agentic AI workflows."`
- `GET https://api.github.com/repos/langgenius/dify/contents/api/core/workflow?ref=main` → 无 `graph_engine/`、无 `entities/`
