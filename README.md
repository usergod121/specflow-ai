# specflow

一句话：**给它一个项目 + 一句需求，它自己把这条链跑完**——

```
① 环境  →  ② 探针  →  ③ 用例  →  ④ 开发  →  ⑤ 测试
搭起来      摸清家底     定验收线     改代码      沙箱里验收
```

每一格都有**闸**：闸不过就退回上一格，不许往下走。⑤ 跑的是项目副本 + 真容器，
验收阶段源码冻结；跑完你自己按「接受」才算闭环。

这是 **v2，从 0 重写**（旧版在 `E:\specflow-ai`，两份互不影响）。

## 跑起来（双击就行）

| 双击 | 它干什么 |
| --- | --- |
| **`start.bat`** | 起引擎（前端已经打进 jar，一个进程就是全部）→ 等它健康 → 开浏览器 |
| **`stop.bat`** | 停引擎 + 把这个项目的容器 / 卷 / 网络收干净；`stop.bat -Purge` 连流程数据一起删 |
| `dev.bat` | 只有改前端想要「保存即刷新」时才用（引擎 + Vite 热更新） |

第一次双击会自己把这些办了：找 Java 17+ → 缺 jar 就用 Maven 编一份 → 看 Docker 在不在
→ 挑一个空端口（默认 8780）→ 密钥从项目 `local.env` / 环境变量 / 这台机器上别的 specflow
里找一份写进项目 → 起引擎 → 打开 `http://127.0.0.1:8780`。

参数都可选：

```powershell
start.bat -Project D:\my\project    # 打开哪个项目（默认：上次那个，其次 fixture）
start.bat -Port 8790                # 换端口（默认 8780；被占就往后找）
start.bat -ApiKey sk-xxxx           # 密钥
start.bat -Rebuild                  # 改了 Java 代码之后重建
start.bat -Foreground               # 日志打在这个窗口里（调试用）
stop.bat -Purge                     # 连流程数据（.specflow\flow）一起删，会先问一遍
```

> 端口随你换。**别用 8770**——那是你原来那份实例正在占的端口，脚本也会拒绝动它。
> 想手工起也行：`java -jar target\specflow.jar web --port 8780 --no-open -p <项目>`

不开界面的四条：

```powershell
java -jar target\specflow.jar env prepare -p <项目>   # 只铺配置（不动 docker）
java -jar target\specflow.jar env init    -p <项目>   # 起环境：拉镜像 + init（失败就 down -v 收干净）
java -jar target\specflow.jar env status  -p <项目>   # 现在什么状态（不动任何东西）
java -jar target\specflow.jar env down    -p <项目>   # 收干净：主环境 + 测试沙箱，连卷；--purge 再删流程数据
```

**前置**：JDK 17、Docker Desktop 在跑、项目里 `.specflow/local.env` 有 `SPECFLOW_API_KEY=sk-…`
（`start.bat` 会替你把最后这条办了）。缺 Key 界面照样能开，只是点「跑」会告诉你缺什么。

想先拿现成的练手：`fixture\` 里有一个零依赖的 Java 小项目，故意埋了一个 bug，README 写了
「要求是什么、跑出来应该是什么」。

## 几条不变式（这一版为什么这么写）

- **一个写者**：事件只有一条流 `events.jsonl`，`id` **恒等于行号**，进程重启接着数。
- **一次工具调用两条事件**（`running` + `ok/error`，共用 `callId`）→ 界面才有「一边跑一边冒」的 chip。
- **闸只有一处**（`flow.Gates`）：一格自己的动作只需满足**前驱**的闸（`requireReachable`），
  否则第一次用就死锁。
- **路径只有一个出口**（`core.Layout`），**长任务只有一个进度口**（`core.Progress`）。
- **写前快照 + 原子落盘**（`flow.Snapshot` / `core.Atomic`），崩了能接着走（`flow.Journal`）。
- **没有数据库**：状态全在 `.specflow/flow/<id>/…` 的文本文件里，人能打开看。

## 出 bug 时怎么查

- 每次请求、每段运行都带 id：`[req run]`，`grep run=r-…` 能把整段拉出来。
- 日志文件：`<用户目录>/.specflow/logs/engine.log`（10MB × 5 滚动）。
- 运行时调级别，不用重启：
  `POST /api/actuator/loggers/com.specflow {"configuredLevel":"debug"}`
- 计数器 `GET /api/actuator/metrics`：`specflow.runs`、`model.calls`、`commands`、`gate.blocked` …
  ——先看「是个例还是每次都这样」。

## 目录

```
src/main/java/com/specflow/
  core/   地基：Json / 原子写 / Layout / 事件流 / 进程 / 模型 / 日志 / 计数
  flow/   五节点 + 闸 + 状态 + 快照 + 日志（业务流程的地基）
  env/    ① 环境：compose 渲染、起停、命令守卫、编译
  agent/  ReAct 循环 + 工具集（读/写/改/跑/交卷）+ 协议
  test/   ⑤ 沙箱、用例结算、审批、冻结
  web/    HTTP 接口 + 静态资源 + 链路过滤器 + 错误口径
src/main/frontend/        Vue 3 + Vite（无组件库、无 Tailwind）
fixture/                  练手用的小项目（含一个故意的 bug）
docs/                     架构 / API / UI 三份契约 + 本文件的交付说明
```

## 文档

| 文件 | 里面是什么 |
| --- | --- |
| `docs/SYSTEM.html` | **系统说明书**（八个部分：需求 / 架构 / 契约与 API / 逐包逐类 / 关键机制 / 参考与借鉴 / 质量 / 运维）——用浏览器打开 |
| `docs/DELIVERY.md` | **交付说明**：怎么跑、验过什么（原始输出）、没做什么 |
| `docs/ARCHITECTURE.md` | 分层、不变式、事件协议、崩溃恢复 |
| `docs/API.md` | 每个接口的入参出参、状态码、闸拒绝长什么样 |
| `docs/UI.md` | 页面结构、组件、状态词表 |
| `PRODUCT.md` / `DESIGN.md` | 产品口径 + 设计系统（色板、间距、动效） |
| `TASKS.md` | 任务账（做完了什么、砍了什么） |
