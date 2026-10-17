# specflow v3 · 任务账

> 规矩：一条任务 = 一个可独立验收的改动。**做完就打勾**，并在 `STATE.md` 里改「现在到哪一步」。
> 每条都写清「拿什么验」。◻ 未完成　◐ 进行中　☑ 完成　⊘ 明确不做（写原因）

## A 阶段：研究（约 3h，子代理并行）

目标：把 12+ 个高星项目的**工程做法**对比清楚，产出可落地的设计候选。产物落在 `docs/v3/research/<项目>.md`，
每份按同一套大纲（顶层抽象 / DSL / 执行引擎 / 单元预算与失败 / 数据流动 / 流式与可观测 /
agent 与 workflow 的关系 / 上下文管理 / 工具层 / 可借鉴 5-8 条 / 反例 / 源码证据）。

| # | 研究对象 | 关注点 | 产物 | 状态 |
|---|---|---|---|---|
| A1 | dify | DSL、节点基类、图引擎、变量池、SSE、运行状态持久化 | `research/dify.md` | ◻ |
| A2 | LangGraph | StateGraph、checkpointer、interrupt/resume、streaming | `research/langgraph.md` | ◻ |
| A3 | Temporal / Cadence | 持久执行、Activity 重试/心跳、确定性、continue-as-new | `research/temporal.md` | ◻ |
| A4 | Airflow / Prefect / Dagster | DAG、任务级重试退避、幂等、超时、资产 | `research/data-orchestrators.md` | ◻ |
| A5 | Argo Workflows / Windmill / n8n | YAML DSL、steps/dag 模板、retryStrategy、artifacts、节点注册 | `research/yaml-engines.md` | ◻ |
| A6 | OpenHands | 事件流架构、runtime 隔离、action/observation、卡死检测 | `research/openhands.md` | ◻ |
| A7 | SWE-agent / Aider | Agent-Computer Interface、repo map、lint/验证回路、上下文裁剪 | `research/aci.md` | ◻ |
| A8 | Claude Code 生态（learn-claude-code 等） | 工具设计、hooks、子代理、上下文压缩（compact）、权限与审批 | `research/claude-code.md` | ◻ |
| A9 | OpenAI Agents SDK / Vercel AI SDK / Mastra | agent 原语、工具生命周期 hook、流式协议、handoff/guardrail | `research/agent-sdks.md` | ◻ |
| A10 | CrewAI / AutoGen / Semantic Kernel | 任务与代理抽象、编排范式、事件驱动 | `research/multi-agent.md` | ◻ |
| A11 | 12-factor agents / Anthropic context engineering | 工程原则：自有 prompt/上下文/控制流、pause/resume、compact | `research/principles.md` | ◻ |
| A12 | toonflow（用户点名） | 它是怎么编排的、节点抽象与约束 | `research/toonflow.md` | ◻ |
| A13 | Skyvern / browser-use | 确定性块 + 智能块混合、"验"这一步怎么做 | `research/hybrid-agent.md` | ◻ |

验收：每份研究都有**源码证据**（文件路径/关键代码），且都落到「可借鉴 5-8 条」+「反例」。

## B 阶段：架构综合（约 0.5h）

| # | 任务 | 产物 | 验收 | 状态 |
|---|---|---|---|---|
| B1 | 汇总研究，写新架构 | `docs/v3/ARCH-V3.md` | 有：核心认知、顶层抽象、架构图、执行图、5 节点如何落在新抽象上 | ◻ |
| B2 | 争议取舍投票（≥3 个 subagent 独立评审，过半才采纳） | `docs/v3/DECISIONS.md` | 每个争议点有采纳/否决 + 理由 | ◻ |

## C 阶段：开发（约 5h）

### C1 缺陷修复（先把闭环打通，这部分不依赖架构结论）

| # | 任务 | 验收 | 状态 |
|---|---|---|---|
| C1.1 | ① 增加「容器内能力校验」：`build.compile` 在 app 容器里跑一次，进闸（facts） | 用户项目上 ① 会因缺 mvn 而红，并给出两条修法 | ◻ |
| C1.2 | `build.compile` 执行前过 `CommandGuard.forbidden()`；UI 标明"这条在本机执行" | 单测：含 `--privileged` 的编译命令被拒 | ◻ |
| C1.3 | ⑤ 预检：容器里缺工具链则**立即失败并指名 ①**，不许自己装 | 单测 + 真跑 | ◻ |
| C1.4 | ⑤ **禁止环境自举**：装工具链类命令直接拒（`CommandGuard.bootstrap`） | 单测 | ◻ |
| C1.5 | **每用例轮次预算**：每个用例单独计数，超限跳过并记 SKIPPED；支持单条重试续跑 | 单测：超限跳过；重试只跑那一条 | ◻ |
| C1.6 | 修 `$HOME` 误拒、`sudo` 改为直接拒（容器内无 sudo） | 单测 | ◻ |
| C1.7 | `bash` 超时跟配置走（不再写死 300） | 单测 | ◻ |
| C1.8 | 路径一个世界：提示词与工具统一用容器路径 `/work/...`，引擎翻译 | 单测 + 真跑 ⑤ 不再猜路径 | ◻ |
| C1.9 | ⑤ 收场如实报告：撞上限也保留并报出已写结论数 | 单测 | ◻ |

### C2 顶层抽象（架构升级主体）

| # | 任务 | 验收 | 状态 |
|---|---|---|---|
| C2.1 | 工作流 DSL：把 5 节点/闸/动作做成可声明、可校验、可版本化的定义（yaml + 校验器） | DSL 单测：非法定义被拒并带行号 | ◻ |
| C2.2 | 节点统一封装：`NodeSpec`/`Action`/`Gate`/`Artifact` 接口 + 注册表 | 5 节点都通过同一条路径注册；无 if-else 特判 | ◻ |
| C2.3 | 执行单元抽象：`Step`/`Executor`（超时、重试、退避、取消、进度、事件） | 单测：超时、重试、取消各一条 | ◻ |
| C2.4 | 工具层：`Tool` 生命周期 hook（before/after/onError）、参数校验、超时、幂等 | 单测 + 5 个工具都走同一入口 | ◻ |
| C2.5 | Agent 运行时：轮次/工具预算、上下文压缩（摘要+裁剪）、卡死检测、暂停/恢复 | 单测：压缩触发、卡死检测 | ◻ |
| C2.6 | 验收裁决类：每用例的 PASS/FAIL/ERROR/SKIPPED + 证据 + 重试；整体接受/拒绝 | 单测 | ◻ |
| C2.7 | 统一事件模型 + 落盘（沿用 id≡行号），为 SSE 提供增量订阅 | 单测 + 端到端事件连续 | ◻ |
| C2.8 | 崩溃恢复：运行级 checkpoint，重启后能接着跑（含"未收场=中断"） | 单测 + 真杀进程验一次 | ◻ |

### C3 前端（尽量少改）

| # | 任务 | 验收 | 状态 |
|---|---|---|---|
| C3.1 | 事件流改 **SSE**（后端 `/api/node/<key>/stream`，前端 EventSource） | 浏览器实测：chip 实时冒、断线重连 | ◻ |
| C3.2 | 其余前端改动**仅限**：新增按钮/字段以对接新 API（不重做页面） | 冒烟七页仍零报错 | ◻ |

## D 阶段：验证与交付（约 2h）

| # | 任务 | 验收 | 状态 |
|---|---|---|---|
| D1 | 后端全测试绿 | `mvn -o clean package` 全绿（数量写在 DELIVERY 里） | ◻ |
| D2 | 前端单测 + 构建 | `npx vitest run` + `npx vite build` 全绿 | ◻ |
| D3 | 浏览器冒烟（七路由零控制台错误） | `smoke.mjs` 全过 | ◻ |
| D4 | 端到端（真模型 + 真 Docker），**用用户真项目当回归样本** | `run2.mjs` 通过；且验证"每用例预算/跳过"生效 | ◻ |
| D5 | bat 入口复验（开/关/销毁） | `start.bat` → 健康 UP；`stop.bat` → 容器/卷真没了 | ◻ |
| D6 | **HTML 类书文档** | `docs/BOOK.html`：核心认知、顶层抽象、架构图、执行图、逐包逐类（一段代码一段解说） | ◻ |
| D7 | 交付说明更新 | `docs/DELIVERY.md`：怎么跑 / 验过什么 / 没做什么 | ◻ |
| D8 | 提交并给用户收尾报告 | git 干净、报告含"没做的" | ◻ |

## 明确不做（⊘）

| 事项 | 原因 |
|---|---|
| 换数据库 / 引入 SQLite | 用户没要求；事件流真身 + 人能打开看仍是这一版的价值。只做 O(n) 的定点修复 |
| 引擎自身也塞进容器 | 与"引擎要调 docker 造沙箱"冲突（docker.sock = 宿主 root），成本过高 |
| 五个节点的语义/顺序调整 | 用户明确要求不变 |
| 前端重做视觉 | 用户明确要求尽量少改前端 |
