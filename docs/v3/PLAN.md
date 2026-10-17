# specflow v3 · 任务账（真账）

> 规矩：一条任务 = 一个可独立验收的改动。**做完就打勾**，判据写在右边。
> ◻ 未完成　☑ 完成　⊘ 明确不做（原因写在 `DECISIONS.md`）
> 每一轮结束都要让这份账与代码对得上——它是给"下一个不记得发生什么的人"看的。

## 一、研究与架构

| # | 任务 | 状态 | 判据 / 产物 |
|---|---|---|---|
| A1 | 13 个方向的并行研究（dify / LangGraph / Temporal / 编排器 / YAML 引擎 / Claude Code 生态 / 原则清单 / Agent SDK / OpenHands / ACI / 多智能体 / 验证优先 / toonflow） | ⊘ 部分 | 会话中断只留下 `research/durable-execution.md` 半页；改为我直接写架构，并在文中标注【实测】/【常识】 |
| B1 | 新架构文档（核心认知 / 顶层抽象 / 架构图 / 执行图 / 5 节点映射 / 借鉴取舍 / 落地映射） | ☑ | `docs/v3/ARCH-V3.md` |
| B2 | 锐评与投票：两份独立评审（架构向 + 范围向） | ☑ | `docs/v3/REVIEW-arch.md`、`docs/v3/REVIEW-scope.md`、`docs/v3/DECISIONS.md` |

## 二、C1 缺陷修复（先把闭环打通）

| # | 任务 | 状态 | 判据 |
|---|---|---|---|
| C1.1 | ① 的闸新增「容器里真能构建」（`Capabilities`） | ☑ | `GatesTest` 3 条 + 真项目会红并给出改法 |
| C1.2 | 编译命令过 `CommandGuard.forbidden` | ☑ | `Compiler` 里 `REJECTED` 分支 |
| C1.3 | ⑤ 开工前 `Preflight`：缺工具立刻失败并指名 ① | ☑ | `Preflight` + 端到端 |
| C1.4 | 守卫第三档：装工具链直接拒 | ☑ | `CommandGuardTest` |
| C1.5 | 每用例轮次预算 + 超限跳过 + 「重试这一条」 | ☑ | `CaseBudgetTest` 5 条 + `POST /api/node/test/case/retry` |
| C1.6 | `$HOME` 放行、`sudo` 直接拒 | ☑ | `CommandGuardTest` |
| C1.7 | `bash` 超时可配（不再写死 300 秒） | ☑ | 后被 M1 改成「旋钮 + 600 秒上限」，见 D 节 |
| C1.8 | 路径两个世界在提示词里讲清 | ☑ | `TestAgent.userMessage` + 端到端 |
| C1.9 | ⑤ 收场如实报已写结论数 | ☑ | `TestAgent.decided()` |
| C1.10 | ① 与 ④ 补「冻结」按钮 | ☑ | `wiring.spec.js` 10 条 |
| C1.11 | 依赖缓存卷（external，跨轮保留） | ☑ | `ComposeTest` |

## 三、C2 顶层抽象（按评审意见收敛）

| # | 任务 | 状态 | 判据 |
|---|---|---|---|
| M1 | **超时单旋钮**（`env.Budgets`）：修掉「界面改了没反应」那个谎 | ☑ | `BudgetsTest` 4 条 + 硬编码清零 |
| M2 | **SSE**：后端 `/api/node/{k}/stream` + 前端 EventSource + 轮询兜底 | ☑ | curl 看到 `event:events`→`event:done`；真浏览器七页各订一条 |
| M3 | **`StuckCheck`**：连续 3 次相同调用就打断 | ☑ | `StuckCheckTest` 3 条 |
| M4 | **`Gate` ×5 + 一张表**（替掉 switch） | ☑ | `GatesTest` 一个字节都没改 |
| O4 | **`EventLog` 行快照**（校验和失效）+ 补 6 条测试 | ☑ | `EventLogTest` 6 条 |
| C2.1 | `.specflow/flow.yaml` 流程 DSL | ⊘ | 评审：会是"第四份真相"，且与"前端尽量少改"冲突 |
| C2.2 | `NodeSpec` / `NodeRegistry` | ⊘ | 同上（DSL 的载体，DSL 不做它就没有意义） |
| C2.3 | `flow.Action` + 统一 `POST /api/flow/action` | ⊘ | 评审：10 条带请求体的路由装不进"一个动作一个 Step" |
| C2.4 | `ToolHook` 中间件链（替掉装饰器） | ⊘ | 评审 R3：动它会让 22 条测试变红，收益只是"好读一点" |
| C2.5 | 上下文压缩 `Compact` | ⊘ | 时间不够；`Loop` 有轮次上限兜底。已写进 `BOOK.html` 第 8 节 |
| C2.6 | 可恢复中断 `InterruptGate` + `/api/flow/resume` | ⊘ | 评审：187 秒的真因是守卫判据错，不是线程阻塞 |
| C2.8 | 运行级 checkpoint / 崩溃后接着跑 | ⊘ | `Journal` 的「没写 END = 中断」+ 快照回滚已够用 |

## 四、端到端里发现并修掉的新问题

| # | 发现 | 状态 | 判据 |
|---|---|---|---|
| D1 | ① 起环境后在**宿主机**编译，容器里没 Maven | ☑ | C1.1 |
| D2 | 60 轮用尽 12 条用例 0 结论 | ☑ | C1.5 |
| D3 | **单条 bash 命令能吃掉整个「我愿意等」的额度**：⑤ 为验并发用例写了个死循环程序，在容器里 150% CPU 转了 15 分钟 | ☑ | `BashTool.MAX_TIMEOUT_SECONDS = 600`（模型写的命令另设上限；env/build 仍用旋钮） |
| D4 | 并发类用例（C10）本身就是"验起来很贵"的那一类，容易把预算吃光 | ◻ | 待办：③ 生成时就标成本，或允许"性能/并发类另起一轮"（见 `BOOK.html` 第 8 节、`DECISIONS.md`） |

## 五、交付

| # | 任务 | 状态 | 判据 |
|---|---|---|---|
| E1 | 后端全测试 | ☑ | 162 条全绿 |
| E2 | 前端单测 + 构建 | ☑ | 105 条 + `vite build` 过 |
| E3 | 浏览器冒烟七路由 | ☑ | 零控制台错误；且确认走 SSE |
| E4 | 五节点端到端（真模型 + 真 Docker） | ☑ | `E:\sf-e2e\reports-v2\v3-e2e-2.log` |
| E5 | bat 入口复验（开 / 关 / 销毁） | ☑ | 健康 UP；容器与卷真没了；8770 未被碰 |
| E6 | **类书 HTML 文档** | ☑ | `docs/BOOK.html` |
| E7 | 交付说明 | ☑ | `docs/DELIVERY.md` |
| E8 | 提交与收尾报告 | ☑ | git 干净 + 报告含"没做的" |
