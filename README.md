# specflow

一句话：**给它一个项目 + 一句需求，它自己把这条链跑完**——

```
① 环境  →  ② 探针  →  ③ 用例  →  ④ 开发  →  ⑤ 测试
搭起来      摸清家底     定验收线     改代码      沙箱里验收
```

每一格都有**闸**：闸不过就退回上一格，不许往下走。⑤ 跑的是项目副本 + 真容器，
验收阶段源码冻结；跑完你自己按「接受」才算闭环。

这是 **v2，从 0 重写**（旧版在 `E:\specflow-ai`，两份互不影响）。

## 跑起来

```powershell
cd E:\specflow-v2
mvn -o clean package        # 编 Java + 编前端（Vite），产物 target\specflow.jar

java -jar target\specflow.jar web --port 8780 --no-open -p <你的项目目录>
# 浏览器开 http://127.0.0.1:8780
```

> 端口随你换。**别用 8770**——那是你原来那份实例正在占的端口。

不开界面的三条：

```powershell
java -jar target\specflow.jar env init   -p <项目>   # 起环境：拉镜像 + init + reset
java -jar target\specflow.jar env status -p <项目>   # 现在什么状态
java -jar target\specflow.jar env down   -p <项目>   # 收环境（连卷一起）
```

**前置**：JDK 17、Docker Desktop 在跑、项目里 `.specflow/local.env` 有 `SPECFLOW_API_KEY=sk-…`。
缺 Key 界面照样能开，只是点「跑」会告诉你缺什么。

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
| `docs/DELIVERY.md` | **交付说明**：怎么跑、验过什么（原始输出）、没做什么 |
| `docs/ARCHITECTURE.md` | 分层、不变式、事件协议、崩溃恢复 |
| `docs/API.md` | 每个接口的入参出参、状态码、闸拒绝长什么样 |
| `docs/UI.md` | 页面结构、组件、状态词表 |
| `PRODUCT.md` / `DESIGN.md` | 产品口径 + 设计系统（色板、间距、动效） |
| `TASKS.md` | 任务账（做完了什么、砍了什么） |
