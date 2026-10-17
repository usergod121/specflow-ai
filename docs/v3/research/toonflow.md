# toonflow · 深读

> 结论前置：**toonflow 存在，而且不小（17,992 star）。但它不是工作流引擎，没有"编排层"。**
> 它把"编排"交给了两样东西：**一张可变的画布（数据结构）+ 一份 1406 行的 Markdown 提示词（流程定义）**。
> 我们最该抄的不是它的"顶层抽象"，而是它**节点能力的单一真相机制**——同时也要看清楚它为了做到这点付出了什么代价。

---

## 0. 它到底是什么

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/HBAI-Ltd/Toonflow-app |
| Star | 17,992（2026-10 读取时；同名前缀最高星，第二名 `HBAI-Ltd/Toonflow-web` 只有 409） |
| 语言 / 许可 | TypeScript，MIT |
| 创建 / 最近推送 | 2026-01-29 / 2026-10-16 |
| 我读的提交 | `master` @ `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`（2026-10-13，"更新README"） |
| 规模 | Git 树 1,093 个 blob；monorepo：`apps/{server,web,desktop,mobile,updateServer}` + `packages/{nodeScaffold,nodeShared,nodes/*,ext/*,toolScaffold,tools/*,skills/*,ffmpeg,file,i18n,mcp}` |
| 运行时 | Bun + Express（server）、Vue 3 + Vue Flow（web）、Electro-bun 桌面壳 |

**一句话定位**：开源 AI 短剧 / 漫剧创作工具。无限画布上拖节点（文本、图片生成、视频生成、3D 导演台、ffmpeg 合成……），节点是可安装的插件（插件市场），一个 AI Agent 在画布上帮你干活。

**它不是**：不是 DAG 执行引擎，不是 BPMN/DSL 工作流，不是"五站流水线"那种有闸门有顺序的东西。仓库里唯一叫 `workflow` 的东西是 `packages/skills/workflow/`，**那是一个 Markdown 技能包（92 KB）**，不是代码。

**关键事实：没有执行器。** 全仓库搜不到 `executor` / `pipeline` / `dag` / `runner` 这类模块。画布的"执行"就是——用户在某个节点上点了生成按钮，那个节点自己读上游值、自己调模型。没有调度，没有拓扑排序，没有自动触发。

---

## 1. 顶层抽象：节点 / 流程 / 执行器 / 状态

它有四个抽象，但**没有"执行器"这一层**。

### 1.1 节点（Node）= 一个 UMD 前端组件包

节点不是数据，是一个**可独立安装的 Vue 组件**。定义全部写在组件自己的源码里：

```vue
<!-- packages/nodeScaffold/readme.md:90-98 的官方示例 -->
<script setup lang="ts">
defineOptions({
  inheritAttrs: false,
  handles: [{ id: "text", type: "source", dataType: "STRING", label: "文本输出" }] satisfies NodeHandle[],
});
const { nodeProps, outputs } = useNode({
  label: "文本",
  outputs: { text: { dataType: "STRING", value: "" } },
});
</script>
```

三个核心类型（`packages/nodeScaffold/src/connection.ts:7-24`）：

```ts
export type NodeDataType = "STRING" | "INT" | "FLOAT" | "BOOLEAN" | "IMAGE" | "MASK" | "LATENT"
  | "AUDIO" | "VIDEO" | "FILE" | "MODEL" | "CLIP" | "VAE" | "CONDITIONING" | "*" | (string & {});

export type NodeHandle = { id: string; type: "source" | "target"; dataType: NodeDataType | NodeDataType[]; label?: string };
export type NodeData = { label?: string; handles?: NodeHandle[]; outputs?: NodeOutputs };
```

`dataType` 是**照抄 ComfyUI 端口类型**的（readme:261 明说"仿照 ComfyUI 类型"）。端口方向 `source`(右,输出) / `target`(左,输入)，兼容规则是"两端类型有交集即可连"，`*` 通配。

`useNode()`（`src/useNode.ts:20-68`）是节点的运行时句柄，返回 `id / node / config / nodeProps / handles / outputs / nodeEvent / nodeTools / ai / ffmpeg / files / updateNodeInternals`。注意 `config` 是**按节点类型**存的（同类所有实例共用），不写进画布 JSON（readme:478）。

### 1.2 节点的"动作" = 运行期注册的 `node:<name>` 工具

这是最值得看的一层。节点自己往共享注册表里注册能被 Agent 调用的函数（`src/nodeTools.ts:79-106`）：

```ts
nodeTools.register({
  name: "setText",
  description: "修改此节点的文本输出",
  parameters: z.strictObject({ text: z.string().min(1) }),
  execute({ text }) { outputs.value.text.value = text; return { text }; },
});
// → Agent 侧看到的工具名是 "node:setText"
```

- 参数用 **Zod** 定义，注册时 `z.toJSONSchema(parameters, { io: "input", target: "draft-07" })` 转成 JSON Schema（nodeTools.ts:85）。
- 注册表挂在**共享的 Vue Flow 实例**上（`Symbol.for("toonflow.nodeTools")`），**不写进画布 JSON**（nodeTools.ts:19-21, 223）。
- 前端执行前先 `parameters.parseAsync(args)`，所以 default/transform/异步 refine 全部生效（nodeTools.ts:96）。
- 组件卸载自动注销（`onScopeDispose`）。

于是节点的完整能力 = 组件 UI + 端口定义 + `node:xxx` 工具集 + `configRules` 配置表单，**四样东西都在同一个包里、同一份源码里**。

### 1.3 流程（"Workflow"）= 画布 JSON + Markdown 技能

- **结构**：画布是一个工作区 JSON 文件，形如 `{ toonflowCanvas: true, nodes, edges, viewport }`（`apps/web/src/pages/workspace/panels/canvas/canvasPanel.vue:565`）。它是**数据依赖图，不是控制流图**——边只表示"这个输出接到那个输入"，没有顺序、分支、循环、条件语义。
- **过程**：真正定义"先做什么后做什么"的是 `packages/skills/workflow/SKILL.md`——**1,406 行 Markdown**，加 20+ 个 references 文件。Agent 读它就照做。

### 1.4 执行器：没有。只有"拉取式数据流"

节点之间没有调度。下游节点在下游需要时**主动去读**上游的输出（`src/nodeInputs.ts:42-52`）：

```ts
export function getTargetValues(targetId, targetHandleId, nodes, edges): NodeInputValue[] {
  // 返回 [{ source, sourceHandle, dataType, value }]
}
```

多来源是允许的（一个输入口可以接多条边，返回数组，按连线顺序）。放进 Vue `computed` 就自动响应——**"执行"= 响应式重算**，不是引擎推。

---

## 2. 流程定义方式：不是代码写死，也不是 DSL，是"提示词 + 画布状态"

**答：两者都不是。** 这是这份报告最反直觉的一点，也是它不可照抄的一点。

- 没有 `workflow.json` / `pipeline.yaml` / BPMN / 任何机器可读的流程描述。
- 没有把流程写死在代码里（没有 `stage1(); stage2(); ...` 这种东西）。
- 流程是有序的自然语言，写在 `SKILL.md` 里，由 LLM 解释执行。

真实例子（`packages/skills/workflow/SKILL.md:19-28`，节选他的"从哪开始"判定表）：

```markdown
| 已有输入 | 处理方式 |
|---|---|
| 创意或一句话 | 补齐人物目标、阻力和结果，形成可审阅的故事与本次完整剧本 |
| 小说、故事或原著片段 | 确认改编范围，保留原文，追踪事件、人物、对白与未知信息，转成可视听剧本 |
| 已定稿剧本 / 时间轴 + 当前目标是制作、生成、继续或修订视频 | **无论用户是否提到"Seedance"…都立即进入…「PART A」执行。** |
| 续集或已有制作 | 读取指定的历史、当前节点和采用记录，确认本次范围，从实际进度继续 |
| 指定一个片段、资产或修改 | 只处理该范围和确实受影响的依赖，不重跑整条链 |
```

再看它怎么写"工具契约"（`references/canvasExecution.md:17-32`）：

```markdown
| 工具 | 当前参数形状 |
|---|---|
| `getCanvas` | `{include?:["canvases","nodeTypes"],cursor?,limit?,canvasId?}` |
| `addNode` | `{type, position:{x,y}, label?}` |
| `connectNodes` | `{connections:[{source,sourceHandle,target,targetHandle}]}` |
| `nodeTools` | `{nodeId, name:"node:函数名", args:{...}}` |
```

**所以"DSL"存在于工具参数层，不存在于流程层。** 工具调用是强 schema 的（Zod，`packages/tools/canvas/src/runtime.ts:10-68`）；流程顺序是软约束（提示词）。

代价在文件里看得见：`SKILL.md` 用了整整 60+ 行反复声明"Seedance 路由锁存""硬隔离""优先级声明不能替代硬隔离"（SKILL.md:32-64），因为一旦顺序靠提示词保证，模型就会跑偏，于是只能不断加禁止条款。**这是一份 92 KB 补丁叠补丁的流程规约。**

---

## 3. 数据在节点之间怎么流

### 3.1 三类通道

1. **结构化值（拉取）**：`outputs[handleId] = { dataType, value }`，下游 `getTargetValues()` 读。协议表（readme:325-334）：

   | dataType | value |
   |---|---|
   | STRING / INT / FLOAT / BOOLEAN | 原生标量 |
   | IMAGE / MASK | `{ url, mimeType }` |
   | VIDEO | `{ url, mimeType }` |
   | AUDIO | `{ url, mimeType }` |

2. **事件推送**：`nodeEvent.on("input:<handleId>", values => ...)` / `on("output:<handleId>", ...)`，深度 watch，注册即回调当前值（readme:381-404）。
3. **引用编号**：`{{ref N}}` 按**真实入边顺序**编号。顺序存在 `data.referenceOrder[targetHandle]`，键是 `encodeURIComponent(JSON.stringify([source, sourceHandle]))`（canvasExecution.md:54-58）。这一段 Agent 得自己分页读边、自己排序——**因为不存在 `getReferences` 工具**。

### 3.2 "下一步是谁"

**没有这个概念。** 边不带方向语义（除了 source→target 的端口方向）。没有 `next`、没有 `onSuccess`、没有条件分支。Agent 决定下一步做什么，或者人拖一根线。

### 3.3 媒体走文件，不走内存

所有媒体是**工作区相对路径 + MIME**，`assets/<nodeId>/…`。画布 JSON 只存相对路径；预览靠 `files.useFileUrl()` 申请 Blob URL，最后一个使用者释放时回收（readme:354）。**这一条和我们"文件 + JSONL，无数据库"的路线天然一致。**

---

## 4. 失败 / 重试 / 人工介入

**没有引擎级重试、没有断点续跑、没有事务回滚。** 全部落在三个地方：

**(a) 超时与取消**
- 节点工具调用：`AbortSignal.timeout(120000)`，超时/卸载/切画布都会 reject（`nodeTools.ts:129-153`）。
- 服务器 → 浏览器回传：120 s 超时 + 取消 + 对话结束三级兜底（`apps/server/src/agent/bridge/canvas.ts:26-42`）。同一对话的画布变更**串行排队**（`queue`，canvas.ts:19, 44）。
- `ai.generate` 带工具：最多 40 次模型请求、10 分钟上限发取消信号（readme:179）。
- **客户端不自动重试**（readme:174 明说："客户端不自动重试"）。

**(b) 失败暴露给谁**
工具的 `execute` 抛错 → 作为工具错误返回 Agent → Agent 决定下一步。节点 UI 侧有 `showNodeError` / `nodeError.vue`（`packages/nodeShared`）。失败保留上次输出（readme:188："失败或停止时保留上次图片"）。

**(c) 人工介入 = 设计出来的一等公民**，但没有审批机件，全靠提示词纪律：
- **临执行复核**（canvasExecution.md:73-82）：生成前必须 `node:getConfig` 核实能力 → 接线 → 写 prompt → 写配置 → 回读核对 → 固定方案 → **展示算力消耗并取得授权** → 用户回复后**再读一次画布和配置**比对是否被改过。
- **一次性提问工具**：`packages/tools/askUser`，`context.question.ask`，支持 `options` 单选与最多 12 字段动态表单，可"跳过"（`packages/toolScaffold/readme.md:68`）。
- **版本快照**：`stateAndDelivery.md:39-47`——"准备提交审阅时，保存该版正文的独立历史快照…用户确认绑定这个快照…原确认仍对应提交的版本，不能顺便批准后来的内容"。

一句话：**它把人工闸门写成了提示词里的强制流程，而不是代码里的状态机。** 所以它是"应该那样做"，不是"必须那样做"。

---

## 5. 状态存哪

**没有数据库。全是文件 + 进程内注册表。** 和我们一样。

| 状态 | 位置 | 证据 |
|---|---|---|
| 画布（节点+边+视口） | 工作区 `画布N.json`，`{ toonflowCanvas: true, ... }` | canvasPanel.vue:565, 397-399 |
| 撤销 | 内存 `useCanvasHistory` | canvasPanel.vue:157, 259 |
| 节点脚本 | `data/nodes/<name>.umd.js` | `plugins/nodes.ts:8,14` |
| 节点元数据 | **UMD 第一行注释** `/*! toonflowNode:{JSON} */`，server 只解析不执行 | `plugins/nodes.ts:21-29` |
| 节点启用/禁用 | 标记文件 `data/nodes/<name>.disabled` | readme:429 |
| 节点配置 | `settings.json` 的 `nodeConfigs[<nodeType>]`，0o600 | `plugins/nodes.ts:68`；readme:478 |
| 节点菜单（顺序/别名/隐藏） | 设置里，后端归一化 | `apps/server/src/lib/nodeMenu.ts:54-97` |
| **`node:xxx` 工具注册表** | **纯内存**，挂在 Vue Flow 实例的 Symbol 上，**不落盘** | `nodeTools.ts:19-21` |
| 作品/生产台账 | `productionState.md`（Markdown 表），Agent 自己维护 | `stateAndDelivery.md:5,11-35` |
| 素材 | `assets/<nodeId>/` | readme:339 |

注意最后两行：**"流程运行状态"根本不是系统状态，是一份 AI 自己写的 Markdown 表格。** 这就是它没有执行器的直接后果——没有 checkpoint，没有 replay，没有"第 3 站失败了从第 3 站重跑"。

代码里还有一句自认的边界（`bridge/canvas.ts:8`）：

```ts
// ACT: 回传仅在当前 Server 进程存活期间有效；多进程部署需共享请求通道。
```

**这意味着：这套架构只在单用户桌面/单进程下成立。** 我们是 Spring Boot 多客户端，直接照搬会炸。

---

## 6. 前端编排与后端定义的对应关系（**单一真相，且是本项目最值钱的部分**）

**结论：节点动作只有一份定义，就在前端组件包里。后端不持有第二份。** 这正好治我们的病（"节点动作硬编码在前端 + 后端各一份"）。

它做到这点靠三步：

### 第一步：后端根本不"认识"节点，只搬运字节

`apps/server/src/utils/plugins/nodes.ts:21-29`：

```ts
const source = await readFile(path, "utf8");
const header = source.match(/^\/\*! toonflowNode:([^\r\n]*) \*\/(?:\r?\n|$)/)?.[1];
const parsed = header ? JSON.parse(header) : null;
// 旧节点或损坏的元数据继续使用文件名，不执行节点脚本。
```

后端能拿到的全部信息 = 首行注释里那点 JSON（`displayName / version / author / readme / github / configRules`）。**没有端口定义，没有工具列表，没有执行逻辑。** 它连"这个节点有哪些输入口"都不知道。

### 第二步：前端动态加载 + 注册

`apps/web/.../canvas/loadNodeComponent.ts:14-56`：`fetch` 脚本 → 插 `<script>` → 读 `window.toonflowNodes[name]` → 当成 Vue Flow 组件类型 `remote-<name>`。节点列表来自 `/api/nodes/get`（canvasPanel.vue:1120-1136），**图标和标题直接取已加载组件自己的 `defineOptions({icon})` 和 `label`**（`nodeMenuItems.vue:13`；readme:73"图标无需写入节点的 data"）。

### 第三步：Agent 在运行期"发现"节点能干什么

后端**没有**静态的工具清单。Agent 只能用三个通用入口现查：

```
getCanvas({include:["nodeTypes"]})   → 有哪些节点类型
getNodeTools({nodeIds:[...]})        → 这些节点注册了哪些 node:xxx，完整 schema
nodeTools({nodeId, name, args})      → 调用
```

（`packages/tools/canvas/src/runtime.ts:76-93`；readme:213-217 给了真实 JSON）

**于是加一个新节点 = 加一个包，不改后端一行代码。** 工具名、参数、schema、UI 全在包里。

### 那"共享"的那份在哪？

**在通用画布操作层，而且只有一份 Zod 定义被两侧共用**：

- 定义：`packages/tools/canvas/src/runtime.ts:10-68`（`canvasSchemas`，17 个操作的 Zod schema）
- 后端用它**校验入参**：`bridge/canvas.ts:22-24`
  ```ts
  const operation = canvasOperations.find(item => item.name === request.name);
  const args = operation.parameters.parse(request.args);
  ```
- 前端用它**执行 + 构造投影**：`canvasQueries.ts:1` `import { canvasSchemas, type CanvasToolCall } from "@toonflow/tool-canvas/runtime";`
- 官方明说（`packages/toolScaffold/readme.md:69`）："参数规则由 `@toonflow/tool-canvas/runtime` 的 Zod schema 共享。"

**这就是"单一真相"的完整答案：**
> 业务动作定义在它该在的地方（节点包内），通用协议定义在一份跨端共享的 schema 模块里。
> 两端各自引用同一个模块，而不是各自抄一份。

### 顺带一个巧思：读工具的"有界投影"

五个读工具全部受 **64 KiB** 上限，带 `hasMore/nextCursor`、`path` 深度 64 层、`valueLimit ≤ 100`、`textLimit ≤ 4000`、`pathBytesLimit ≤ 2048`，超了要给 `truncated: {path, reason, nextOffset}`（`runtime.ts:25-52`；`canvasQueries.ts:8-13`）。**面向 LLM 的读接口被显式设计成有界的**，避免把整张画布灌进上下文。这个细节我们基本没做。

---

## 7. 值不值得借鉴（逐条）

前提：我们是 Java 17 + Spring Boot，五站流水线（环境→探针→用例→开发→测试），每站有闸，Agent 是 ReAct + 工具，无数据库（文件 + JSONL），前端 Vue 3。

| # | 机制 | 收益 | 我们的代价 | 结论 |
|---|---|---|---|---|
| 1 | **动作定义只存在于能力所有者处**；编排后端只持有通用 schema，靠"发现"拿能力清单（`getNodeTools`） | 彻底消灭前后端各写一份动作定义；加站/加动作不动后端 | 中：要给每站加"自描述"能力（`station:<action>` + JSON Schema），编排层改成"查了再调"而不是"预先知道" | **值得，最高优先级。** 但注意 Java 侧没有 UMD 动态加载，我们的等价物是：**每站自带一份 `actions.json`（JSON Schema）+ 一个 REST 自描述端点**，前端 TypeScript 类型从这份 schema 生成。 |
| 2 | **通用协议用一份共享 schema 模块，前后端都 import 它** | 契约漂移不可能发生；改一处两端同时编译失败 | 低：Java 有 JSON Schema / OpenAPI 生成的成熟方案（如 `jsonschema2pojo` / `openapi-generator`），前端再生成 TS 类型。半天到一天的事 | **值得，立刻做。** 这是我们"缺顶层抽象"的**真实病根**，不是缺 DAG。 |
| 3 | **产物只能通过 typed op 改，禁止直接写 JSON**（"不直接写画布 JSON"，runtime.ts:93） | 每次变更都有校验、有回执、有幂等边界；审计清楚 | 低—中：要把五站的产物读写包成 ops；我们已有 JSONL 事件流，正好当审计层 | **值得。** 这条直接升级我们的"闸"：闸 = 拒绝不符合 schema 的 op，而不是靠约定。 |
| 4 | **面向 LLM 的读接口有界**（64 KiB / 分页 / `truncated` 带续读指针 / 字段投影 `fields`+`dataKeys`） | 上下文不爆；大产物也能读 | 低：加个 wrapper | **值得，抄细节。** 我们现在的工具大概率会一次性返回整站产物。 |
| 5 | **人工闸门做成"回读-比对-授权"三步**（`canvasExecution.md:73-82`：执行前再读一次配置，比对是否被改，算力消耗单独授权） | 防止"确认的是旧状态、执行的是新状态"这个经典竞态 | 低：我们已有闸，只需补"临执行复核"这一步和"授权快照" | **值得。** 它对竞态很诚实（canvasExecution.md:82："读回与执行不是宿主提供的原子操作…不能承诺完全消除并发竞态"）。 |
| 6 | **通用画布操作只有 17 个、且全部批量、全部有上限**（`connectNodes` 一次 ≤64 组，`addNode` 不接受 `data`） | Agent 的能力面极窄，几乎无法做出格的事；也没法绕过节点函数 | 低 | **值得。** 窄接口比宽接口好维护得多。 |
| 7 | **流程写成 1406 行 Markdown 提示词** | 极快迭代业务规则，不用改代码 | **高**：不可测、不可回放、不可审计、无法保证执行；文件里 60+ 行"硬隔离/禁止/必须"就是它在跟模型打补丁的证据 | **不值得，明确反对。** 我们每个站有闸，恰好可以把它做的事用状态机做到"必然"，而不是"应该"。 |
| 8 | **无执行器 / 无 checkpoint / 运行状态 = AI 自己写的 Markdown 台账** | 实现极简 | **高**：没有 replay、没有断点续跑、"第 3 站挂了从第 3 站重跑"做不到 | **不值得。** 我们已有的 JSONL 事件流严格优于它，别为了"像它"而丢掉。 |
| 9 | **回传通道依赖单进程内存**（`bridge/canvas.ts:8` 的 ACT 注释自认） | 桌面端够用 | 高：Spring Boot 多客户端会直接崩 | **不可借鉴。** 记录在案，引以为戒。 |
| 10 | **节点插件即 UMD + 首行注释元数据** | 安装/卸载/版本/市场全免费 | 高：Java 服务端跑不了 UMD，且"节点逻辑跑在浏览器"我们无法接受（多客户端、要审计） | **不可借鉴**（形态层面）。但它背后的想法——**"元数据可读、代码不执行"**——值得学：我们的站自描述清单也应该是纯 JSON，编排层读它但绝不加载站内业务代码。 |

---

## 8. 那"编排层顶层抽象"到底值不值得做？

值得。但**不是 toonflow 那种抽象**。

toonflow 教给我们的最重要一件事，恰恰是**反面的**：一个 92 KB 的提示词 + 一堆 Markdown 台账，**不是顶层抽象，是顶层债**。它的"抽象"在工具层（17 个 typed op + 每节点自描述函数 + 一份共享 Zod），而流程层是空的。

对我们五站流水线，正确的顶层抽象是这三件（按价值排序）：

1. **一个统一的"产物操作"协议**（等价于它的 `canvasOperations`）：每站产物的读写只能通过 `<station>.<op>` 调用，参数有 schema，批量有上限，读有界且分页。→ 这就是"顶层抽象"的本体。
2. **每站自描述能力清单**（等价于 `getNodeTools` / `node:xxx`）：编排层启动时（或调用时）向站要 `actions.json`，不预置任何站的动作常量。前后端都从这份 schema 生成。
3. **闸 = 状态机里的显式前置条件**（这是 toonflow 完全没有的部分，是我们的优势别丢）：前置未冻结 → op 直接 reject，而不是提示词里写"不得"。

**放弃**：把流程写成提示词、把运行状态写成 Markdown、把调度交给 LLM 自由发挥。

### 如果一定要向外找一个"老师傅"

（以下为**我对其他工作流引擎的常识判断，不是 toonflow 源码结论**，明确标注）

1. **Dagster**（Python）— **模型最贴我们**。它的核心概念是 *software-defined asset*（每个资产有明确产出与依赖）+ *asset check / freshness policy*（**校验闸门是资产的一等属性**），图由资产依赖**自动推导**而不是手画。对应到我们：五站 = 五个 asset，每站的"闸"= 一个 `AssetCheck`，`前置没冻结` = 上游 asset 未通过 check，下游根本不会被调度。这正是"顶层抽象"该有的样子：**声明产物和校验，拓扑自动出**。
2. **Temporal**（有 Java SDK）— 学它的**durability 与 human-in-the-loop**：workflow 是一个确定性函数，状态由事件历史 replay 出来（**和我们的 JSONL 事件流同构**），人工介入用 **signal** 而不是靠提示词。它的"等用户确认三天后回来接着跑"是我们五站必然会遇到的场景，而 toonflow 和我们现在都做不到。

补充一提：Java 圈内 **Camunda/Zeebe（BPMN）** 也能表达"站 + 闸 + 人工任务"，但 BPMN 图与代码分离、图例繁重，做五个站属于杀鸡用牛刀；**Spring Statemachine** 反而轻，但缺持久化与 replay。所以：**模型学 Dagster，持久化/人工介入学 Temporal**，落地用 Spring 自己写（我们已有 JSONL，不需要引进新中间件）。

---

## 9. 未核实点 + 证据清单

### 未核实点（诚实标注）

1. `canvasPanel.vue`（57 KB）我只读了 `grep` 到的关键行（保存、节点加载、节点列表），**保存队列的合并/防抖细节、撤销栈深度、并发编辑冲突处理没有逐行核**。
2. `packages/skills/canvas/SKILL.md` 我抓取失败（未落到本地），只知道它存在；它与 `skills/workflow/SKILL.md` 的关系（是被合并还是并存）未确认。
3. `packages/tools/canvas/readme.md`（1.2 KB）未读；`packages/tools/canvas/src/index.ts` 未读，**17 个 op 中"前端实际执行体"与"后端 schema"的接线我只核了 `canvasSchemas` 的引用关系（`canvasQueries.ts:1` + `bridge/canvas.ts:22`），没有看到 op → 具体 Vue Flow 调用 的完整映射**。
4. `packages/skills/v118workflow/`（另一套 workflow，24 个 references）我只看到文件列表，没读内容；它和 `skills/workflow/` 的版本关系不明（可能是新旧两代并存，这本身是"提示词编排会腐化"的旁证，但我未证实）。
5. **我没有运行该项目**，所有结论来自静态阅读。没有验证"Agent 真的按 SKILL.md 顺序执行"——而这恰恰是我在报告里质疑的点（不可测），所以无法用运行结果反驳自己。
6. star / 日期是 2026-10-16 前后读取的 API 快照，会变。

### 证据清单（文件:行 / 链接）

**仓库元数据**
- https://github.com/HBAI-Ltd/Toonflow-app —— README、star、MIT、语言
- https://api.github.com/repos/HBAI-Ltd/Toonflow-app —— `default_branch: master`，`stargazers_count: 17992`，`created_at: 2026-01-29T10:25:45Z`，`pushed_at: 2026-10-16T08:31:49Z`
- `master` @ `d2e94aca7b59659ff6b4ca15f03cf134ec7f0e1d`（2026-10-13，commit "更新README"）
- 仓库树 1,093 blob：`GET /repos/HBAI-Ltd/Toonflow-app/git/trees/master?recursive=1`，`truncated: false`

**节点抽象**
- `packages/nodeScaffold/src/connection.ts:4-12` —— `NodeDataType` 全集
- `packages/nodeScaffold/src/connection.ts:7-12` —— `NodeHandle` 三字段
- `packages/nodeScaffold/src/connection.ts:20-24` —— `NodeData` = `{label, handles, outputs}`
- `packages/nodeScaffold/src/connection.ts:33-38` —— `isTypeCompatible`：有交集即通，`*` 通配
- `packages/nodeScaffold/src/connection.ts:40-56` —— `validateConnection`，只调 target 的回调
- `packages/nodeScaffold/src/useNode.ts:20-68` —— `useNode()` 返回值全集
- `packages/nodeScaffold/readme.md:90-101` —— 最小节点示例 + `defineOptions({handles})`
- `packages/nodeScaffold/readme.md:261` —— "dataType 仿照 ComfyUI 类型"
- `packages/nodeScaffold/readme.md:325-334` —— 输出值协议表
- `packages/nodeScaffold/readme.md:439-455` —— `createNodeConfig({name, displayName, author, github, configRules})` + 首行元数据注释
- `packages/nodeScaffold/readme.md:477-478` —— 配置按节点类型存、不写 `node.data`

**动作注册（单一真相的核心）**
- `packages/nodeScaffold/src/nodeTools.ts:79-106` —— `nodeTools.register`，Zod → draft-07，`parseAsync`
- `packages/nodeScaffold/src/nodeTools.ts:19-21` —— 注册表挂 Vue Flow 实例 Symbol，**不落画布 JSON**
- `packages/nodeScaffold/src/nodeTools.ts:108-156` —— `useNodeToolsContext().call`，120 s 超时，卸载即失效
- `packages/nodeScaffold/readme.md:206-221` —— `register` 示例 + Agent 调用 JSON `{"nodeId":…,"name":"node:setText","args":{…}}`
- `packages/nodeScaffold/readme.md:223` —— "注册表共用宿主 Vue Flow 实例，不写入画布 JSON"

**数据流**
- `packages/nodeScaffold/src/nodeInputs.ts:13-22` —— `getTargetSources`（只查连接）
- `packages/nodeScaffold/src/nodeInputs.ts:42-52` —— `getTargetValues`（带值，多源、类型校验）
- `packages/nodeScaffold/src/nodeInputs.ts:56-67` —— `useNodeInputs()` 响应式包装
- `packages/nodeScaffold/readme.md:381-404` —— `input:<handle>` / `output:<handle>` 事件
- `packages/nodeScaffold/readme.md:373` —— "一个输入可连接多个 source，返回数组"；"工具只读取当前值，**不执行上游节点**"

**通用工具协议（跨端共享 schema）**
- `packages/tools/canvas/src/runtime.ts:10-68` —— `canvasSchemas`，17 个 op 的 Zod 定义
- `packages/tools/canvas/src/runtime.ts:75-94` —— `canvasOperations`，每个 op 的 `label` + 长 `description`（给 LLM 的说明书）
- `packages/toolScaffold/readme.md:69` —— **"参数规则由 `@toonflow/tool-canvas/runtime` 的 Zod schema 共享"**
- `apps/server/src/agent/bridge/canvas.ts:22-24` —— 后端用同一份 schema 校验入参
- `apps/web/src/pages/workspace/panels/canvas/canvasQueries.ts:1` —— 前端 import 同一份 `canvasSchemas`
- `apps/web/src/pages/workspace/panels/canvas/canvasQueries.ts:8-13` —— 64 KiB / 52 KiB / scanLimit 2000

**前端编排与节点定义的对应**
- `apps/web/src/pages/workspace/panels/canvas/loadNodeComponent.ts:14-56` —— 动态 `<script>` 加载 → `window.toonflowNodes[name]` → Vue Flow 组件
- `apps/web/src/pages/workspace/panels/canvas/canvasPanel.vue:1120-1136` —— `/api/nodes/get` → `type: remote-${name}`, `label: displayName`
- `apps/web/.../canvas/components/nodeMenuItems.vue:13` —— 菜单图标取自已加载组件的 `defineOptions({icon})`
- `apps/server/src/utils/plugins/nodes.ts:21-29` —— 后端只解析首行注释，**不执行节点脚本**
- `apps/server/src/lib/nodeMenu.ts:54-97` —— 后端只归一化顺序/别名/隐藏，并追加新装类型

**流程定义（提示词）**
- `packages/skills/workflow/SKILL.md:1-9` —— skill 元数据 `version: "2.7.0"`、`displayName: 视频制作工作流`
- `packages/skills/workflow/SKILL.md:19-28` —— 输入分派表（流程判定的真实形态）
- `packages/skills/workflow/SKILL.md:32-64` —— "Seedance 路由锁存 / 硬隔离"，60 行反复加固（提示词编排的债）
- 共 1,406 行（read 工具报 total）
- `packages/skills/workflow/references/canvasExecution.md:17-32` —— 工具参数表
- `packages/skills/workflow/references/canvasExecution.md:54-58` —— `{{ref N}}` 顺序还原三步
- `packages/skills/workflow/references/canvasExecution.md:73-82` —— 生成前准备 + 临执行复核 + 竞态免责

**状态与人工介入**
- `packages/skills/workflow/references/stateAndDelivery.md:5` —— `productionState.md` 台账
- `packages/skills/workflow/references/stateAndDelivery.md:11-35` —— 台账模板（成果/尝试/采用/交付四张表）
- `packages/skills/workflow/references/stateAndDelivery.md:39-47` —— 确认绑定快照，不顺便批准后续改动
- `packages/toolScaffold/readme.md:68` —— `askUser` 工具契约（含"跳过"语义）
- `packages/nodeScaffold/readme.md:174` —— "客户端不自动重试"
- `packages/nodeScaffold/readme.md:179` —— 工具循环最多 40 次请求 / 10 分钟
- `apps/server/src/agent/bridge/canvas.ts:8-9` —— ACT 注释：回传仅进程存活期内有效
- `apps/server/src/agent/bridge/canvas.ts:19,44` —— 同对话画布变更串行 `queue`
- `apps/web/src/pages/workspace/panels/canvas/canvasPanel.vue:565` —— 画布落盘 `{toonflowCanvas:true,nodes,edges,viewport}`
- `apps/web/src/pages/workspace/panels/canvas/useCanvasHistory.ts` —— 内存撤销
- `packages/nodeScaffold/readme.md:429` —— 禁用标记 `data/nodes/<name>.disabled`

**它自己承认的边界**
- `apps/server/src/utils/plugins/nodes.ts:20` —— "当前整包读取，内存开销随节点总体积增长"（ACT）
- `packages/toolScaffold/readme.md:74` —— 工具插件是可信服务端代码，**不是沙箱**
- `packages/nodeScaffold/readme.md:143` —— FFmpeg 间接 I/O "**不是文件系统沙箱**"

---

*本报告为静态源码阅读，未运行项目。涉及其他工作流引擎（Dagster / Temporal / Camunda）的判断为作者常识，已在第 8 节明确标注，不来自 toonflow 源码。*
