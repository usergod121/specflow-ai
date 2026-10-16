# specflow v2 · 任务账

> 这是给「下一个不记得发生什么的人」看的：**做了什么、砍了什么、每条拿什么验的**。
> 五节点的接口契约在 `docs/ARCHITECTURE.md`，接口表在 `docs/API.md`，页面口径在 `docs/UI.md`。

## 一、九件事，全是 DONE

| # | 做了什么 | 落在哪 | 拿什么验的 |
|---|---|---|---|
| T0 | 地基：Json / Atomic / Layout / Event / EventLog / Progress / Cmd / Llm / Project / Logs / Metrics | `core/` | 被下面全部测试与两次端到端间接覆盖；`EventLog` 的「id = 行号」由端到端事件的连续性钉住 |
| T1 | 环境：env.yaml 模型与校验、compose 渲染、up/down/init/reset/cancel、命令守卫、编译 | `env/` | `EnvConfigLoaderTest` 8、`ComposeTest` 5、`EnvironmentTest` 9 |
| T2 | 流程：五节点状态机、闸、flow.json、回退作废、快照、崩溃恢复 | `flow/` | `GatesTest` 11 + 两次端到端实跑的「冻结 / 回退」 |
| T3 | 前端：七个页面 + 组件 + token + 纯函数库 + 单测 | `src/main/frontend/` | `npx vitest run` 95 条 / 6 个文件；`tests/smoke.mjs` 七路由零控制台错误 |
| T4 | ReAct 循环 + 工具层：Tool/ToolRegistry/Loop + 六个工具 | `agent/` | `LoopTest` 13、`ProtocolTest` 8、`ToolRegistryTest` 9、`ToolsTest` 12、`ReadFileTest` 7、`ApplyPatchTest` 8 |
| T5 | 三个 Agent：环境、探针（只读）、开发（白名单内改 + 编译回喂） | `agent/` | 两次端到端真跑（12.4s / 10.4s / 6.3s，产物与声明都对得上） |
| T6 | 测试 Agent：用例清单、容器沙箱、逐条验收、审批、冻结 | `test/` | `CaseListTest` 18、`SandboxTest` 7、`ApprovalsTest` 5 + 端到端 ⑤ 109s / 11 条全到终态 |
| T7 | 路由：`/api/*` 全表 + 错误翻译（409 讲人话）+ Spring 装配 + 链路过滤器 | `web/`、`Main.java` | 端到端每一步都走 HTTP，且对状态码与事件流有断言；错误口径由 `web/Errors` 一处翻 |
| T8 | 接线与真跑：真模型 + 真容器，五节点走通 | — | `E:\sf-e2e\run2.mjs`，**连着两次全绿**（157s / 150s） |
| T9 | 验收：单测 + 浏览器冒烟 + 交付文档 | `src/test/`、`docs/` | 后端 `mvn -o clean package` **120 条全绿**；前端 95 条；交付说明在 `docs/DELIVERY.md` |

## 二、每条「做完」的判据（Definition of Done）

- **T0**：`mvn -o clean package` 过（编译 + 打包 + 前端产物一起）。
- **T1**：三种声明写法都能读（只给命令 / 给命令+服务 / 声明不需要环境）；非法声明当场拒；
  渲染出的 compose 里只有 `app` 挂项目目录，`init` 不进容器。
- **T2**：五档闸各一条；**① 自己这一格的闸只需前驱满足**（`requireReachable`）——
  否则第一次用就死锁，这一条是端到端撞出来才补的。
- **T3**：`npx vitest run` 全绿、`npx vite build` 过、**七个路由在真浏览器里零 JS 报错**。
- **T4**：轮次上限、工具结果回喂、`submit` 收场、observer 推两条共用 `callId` 的事件。
- **T5**：真模型下产物能用（`env.yaml` 12 行、编译命令对）。
- **T6**：用例终态校验（5 类问题）；容器沙箱跑副本；无 `PENDING` 才算完。
- **T7**：闸拒绝是 **409 + 讲人话的理由**，不是 500；静态资源缺失是 404 且不刷 ERROR 日志。
- **T8**：`① 起容器 → ② 产物 → ③ 用例 → ④ 编译过 + 独立核对行为 → ⑤ 全终态 → 闭环`，
  其中 ④ 的核对**不采信 Agent 自述**：脚本自己 `javac` + `java` 跑一遍看行为。
- **T9**：`mvn -o clean package` 全绿 + 冒烟七页干净 + `docs/DELIVERY.md` 写清「怎么跑 / 验过什么 / 没做什么」。

## 三、砍掉的（说好了不做，别再找）

- 老 `.specflow` 数据迁移；旧接口兼容层（`/api/drafts`、`/api/tasks`、`/api/chain`）。
- 按阶段配不同厂商 / Key（现在一份 `project.yaml` 的 `llm` 段全链共用）。
- 模板库 / 上下文库面板；`TaskDraft` 那层中间概念。
- 跨平台打磨（这套东西当前只在 Windows + Docker Desktop 上验过）。

## 四、已知的坑（详见 `docs/DELIVERY.md` 第四节）

1. 审批（y/n）**真链路上没被触发过**——有五条单测钉住逻辑，但「真停在界面上等人点」没实测。
2. `TestSandbox` 被硬杀时容器会留着（下一次同名 `up` / `down` 会收拾掉）——**没做实时验证**。
3. 界面只做过浏览器冒烟（七路由零报错），**没有逐页视觉走查**。
