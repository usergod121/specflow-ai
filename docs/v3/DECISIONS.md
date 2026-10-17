# specflow v3 · 取舍与评审记录

> 这一份记的是「为什么这么做、为什么不那么做」。每条都写明**来源**（谁提的、我看到什么证据），
> 以及**最后做了什么**。被否掉的方案也留着——避免下一轮又把它提一遍。

## 一、评审怎么做的

按用户要求：先写架构文档（`ARCH-V3.md`），再让 subagent **锐评**，有争议就砍。

- 评审 A（架构向）：`docs/v3/REVIEW-arch.md` —— 从「能不能落地、会不会砸现状」审。
- 评审 B（范围向）：`docs/v3/REVIEW-scope.md` —— 从「6 小时够不够、哪些必须砍」审。
- 两位的结论一致度很高：**部分做**，先锁基线，再把三件小事做扎实，其余全给文档。

> 研究阶段的那 13 个 subagent 因为会话中断没落盘（只在 `research/durable-execution.md` 留了半页）。
> 所以 `ARCH-V3.md` 的对比部分以我的知识为主，并在文中逐处标注【实测】/【常识】——
> **取舍理由是这一轮真正靠得住的东西**，而理由是可验证的（每条都对应一次真实事故）。

## 二、被采纳的（都做了，都有机器判据）

| # | 建议 | 来源 | 落到哪 | 判据 |
|---|---|---|---|---|
| 1 | 先 commit + 打 tag 锁基线（否则搞砸会连带丢掉 C1） | A(M1) / B(0:00-0:10) | tag `v3-c1` / `v3-m3m4` | `git tag -l` |
| 2 | **超时单旋钮**：`Compiler` 的 Javadoc 说共用 `pullTimeoutMinutes`，方法体却是 `return 30*60`；界面「改超时」只回显不生效 | B(M1) | 新 `env/Budgets` + 5 处接线 | `BudgetsTest` 4 条；`Select-String '30 \* 60|\b600\b'` 只剩注释与 SSE 的无关常量 |
| 3 | **SSE 收尾**（后端已在、前端还是 `setInterval`） | B(M2) | `store.js` 改 EventSource + 轮询兜底 | 真浏览器：`/api/node/*/stream` 被七个页面各订一次；curl 看到 `event:events` → `event:done` |
| 4 | **StuckCheck** 只做「连续 3 次同一条命令」一条判据 | B(M3) | `agent/StuckCheck`（Tool 装饰器） | `StuckCheckTest` 3 条 |
| 5 | **`Gate` ×5 + 一张表**，证明方式 = `GatesTest` 一个字节都不改 | B(M4) | `flow/Gate` + `Gates.TABLE` | `git diff --stat GatesTest.java` 为空 |
| 6 | 事件流 `lines()` 的 O(n)（B 列为可选 O4；我判断必须做——SSE 每 250ms 读一次全文件） | B(O4) | `EventLog` 加 (大小,时间) 校验和快照 | `EventLogTest` 6 条（含"外部改动可见"） |
| 7 | 补 `Api`/`Store`/`Journal`/`EventLog` 零覆盖的窟窿 | A(M1) | 先补 `EventLogTest`；Api 层用浏览器冒烟 + SSE curl 兜 | 162 条后端 + 7 页冒烟 |

## 三、被否掉的（明确不做，别再来一遍）

| 方案 | 谁提的 / 为什么诱人 | 否掉的**证据** |
|---|---|---|
| `.specflow/flow.yaml` 驱动执行（流程 DSL） | 用户原话想要"顶层抽象与约束" | 评审 B：它会是**第四份真相**（`FlowState`/`Node`/前端页面之外多一份），而硬约束"前端尽量少改"让 yaml↔Vue 永远对不上，恰恰防不住它想防的那次事故（页面没冻结按钮）。**改法**：那一轮把统一封装落在 `Facts`/`Gates`/`CaseList` 三处既有抽象上，不新造一层。 |
| `flow.Action` + 统一 `POST /api/flow/action` | 让路由变薄 | 评审 A(M3)：10 条带请求体的路由（`envSave`/`testApprove`/`testSubmit`…）根本装不进"一个动作一个 `Step`"，硬套就要为它们各开例外。收益抵不过复杂度。 |
| `AgentRuntime` 替换 `Loop` | 预算/压缩/卡死集中一处 | 评审 A(M4)：`Loop` 的时序被 13 条测试钉着（每次调用推两条事件、`callId` 成对）。**改成装饰器**（`CaseBudget`、`StuckCheck`）效果一样、风险为零。 |
| `InterruptGate` + `POST /api/flow/resume`（可恢复中断） | 审批别阻塞线程 | 评审 A：187 秒的真正原因是**守卫判据错**（容器里没有 sudo 却问用户），不是线程阻塞。这个前提修掉之后，收益就从"必要"降为"锦上添花"。 |
| `Compact`（上下文压缩） | 长对话会爆 | 时间不够，且 `Loop` 有轮次上限兜底。**风险写进 `BOOK.html` 第 8 节**，不许当成做了。 |
| `ToolHook` 中间件链 | 装饰器剥四层难读 | 评审 A(R3)：动它会让 22 条测试变红，而收益是"好读一点"。留到下一轮。 |
| `NodeRegistry` / `Checkpoint` 崩溃恢复 | 更"工程化" | 同上：时间与风险都不划算；`Journal` 的「没写 END = 中断」已经够用。 |

## 四、评审提的、我没照做但改了说法的

- **评审 A 要求「补 `ApiSmokeTest` 3 条 GET」**：这个项目没有 `spring-boot-starter-test`（只有 JUnit + AssertJ），
  搭 MockMvc 要动 pom、要造一个真项目上下文，代价比收益大。**改成**：SSE 用 `curl` 验帧、页面用真浏览器
  冒烟（`tests/smoke.mjs` 七个路由零控制台错误）、端到端覆盖 HTTP 全链路。**这不是等价替代，是更弱但够用的覆盖**——
  所以写在 `DELIVERY.md` 的「没做的」里。

## 五、这一轮的时间账（诚实版）

| 阶段 | 计划 | 实际 |
|---|---|---|
| 研究（13 个 subagent） | 3h | 会话中断，只有 1 个留下半页；改为我自己写架构文档 |
| 架构文档 + 两份评审 | 0.5h | ≈1h（含两次评审等待） |
| C1 缺陷修复（9 条） | 3h | ≈3h（含两次自己测试抓到的实现错误） |
| M1 超时单旋钮 | 0.7h | ≈0.7h |
| M2 SSE（前后端 + 验证） | 1h | ≈1h（含一次文件编码事故与回滚） |
| M3 卡死检测 | 0.7h | ≈0.4h |
| M4 闸拆表 | 0.7h | ≈0.3h |
| 事件流快照 + 六条测试 | — | ≈0.5h |
| 文档（BOOK/DELIVERY/这份） | 1h | 进行中 |
| 端到端与收尾 | 1.5h | 进行中 |

**两次事故记一笔**（下次别再犯）：
1. 用 `pwsh` 的 `.Replace()` 往 Java 文件里塞**新中文**会把文件弄成乱码（编码边界）。
   教训：**带中文的文件改动一律用编辑工具，不用 shell**。那次靠 `git checkout` + 重做救回来。
2. `git commit -m` 里带 `"` 会把 shell 引号搞坏，提交静默失败而 tag 已经打上。
   教训：提交信息里只用 `「」`。
