# specflow v2 · 交付说明

> 2026-10-16 夜。这一版是**从 0 重写**的：`E:\specflow-v2`。
> 三份契约在 `docs/`（架构/API/UI），设计在 `PRODUCT.md` / `DESIGN.md`，任务账在 `TASKS.md`。
> 这一份只回答三件事：**怎么跑、验过什么、没做什么**。

## 一、怎么跑

```powershell
cd E:\specflow-v2
mvn -o clean package                 # 编 Java + 编前端（Vite），产物 target\specflow.jar
java -jar target\specflow.jar web --port 8770 --no-open -p <你的项目目录>
# 浏览器打开 http://127.0.0.1:8770
```

不开界面的两条：

```powershell
java -jar target\specflow.jar env init  -p <项目>   # 起环境（拉镜像 + init + reset）
java -jar target\specflow.jar env down  -p <项目>   # 收环境（连卷一起）
java -jar target\specflow.jar env status -p <项目>  # 现在什么状态
```

**前置**：JDK 17、Docker Desktop 在跑、项目里 `.specflow/local.env` 有 `SPECFLOW_API_KEY=sk-…`
（没有的话界面能开，只是点「跑」会告诉你缺密钥）。

## 二、验过什么（都是真跑出来的）

### 1. 五节点全链路端到端：**连着两次都通过**（157 秒 / 150 秒）

`node E:\sf-e2e\run2.mjs --jar E:/specflow-v2/target/specflow.jar`（真模型 deepseek-chat + 真 docker + 真容器沙箱）：

```
① 跑环境 Agent   33.2s  env.yaml 13 行、编译命令「javac -d out/main src/main/java/com/demo/*.java」
① 保存声明        0.1s  env.yaml → .specflow/env.yaml；编译命令 → .specflow/project.yaml
① 起环境（真容器） 2.4s  容器 sf-sfv2-…-app-1
① 编译            0.6s  状态 PASSED
① 冻结            0.1s  DONE
② 跑探针         15.7s  要改 1 个、可读 2 个
② 保存 + 冻结     0.1s  DONE
③ 生成用例        3.6s  11 条、冻结 DONE
④ 跑开发         10.4s  编译通过
④ 自己核一遍      0.7s  打印 reserve(5)=false、onHand(A)=3     ← 脚本自己 javac + java 跑出来的
④ 冻结            0.1s  DONE
⑤ 跑测试（沙箱）  87.0s  11 条全到终态（PASS 10）
⑤ 提交 + 接受     0.1s  已闭环
```

**「④ 自己核一遍」那一行是这一段最值钱的证据**：它不看 Agent 的自述，而是脚本自己
`javac` 编译目标项目、`java` 跑一遍，要求打印出 `reserve(5) = false` 与 `onHand(A) = 3`。
也就是说「它说改好了」这句话是**被独立核对过**的。

### 2. 链路与日志（用户点名要的那一层）

- **每一次请求**：`← 200 POST /api/node/env/up（2575ms）`，带一个 `req` id；
- **每一段运行**：后台线程带着 `run` id，于是 `grep run=r-...` 能把整段拉出来；
- **日志文件**：`<用户目录>/.specflow/logs/engine.log`（10MB × 5 滚动，UTF-8，格式带 `[req run]`）；
- **监控端点**（Actuator，只挂回环地址）：
  - `GET  /api/actuator/health` —— 活着吗（带详情）
  - `GET  /api/actuator/metrics` —— JVM / HTTP / 我们自己的计数器
  - `GET  /api/actuator/loggers` —— 现在各级别是什么
  - `POST /api/actuator/loggers/com.specflow {"configuredLevel":"debug"}` —— **运行时调级别**，出 bug 不用重启
- **业务计数器**（`core/Metrics`）：`specflow.runs`、`runs.finished`、`model.calls`、`commands`、
  `approvals`、`gate.blocked`、`run.seconds` —— 出问题时先看「是个例还是每次都这样」。
- **脱敏**：`core/Logs.redact(...)` 把 `sk-…` / `api_key=` / `password=` 一律换成 `***`。

### 3. 单元与模块级测试

见本文件末尾的「实测记录」一节（跑 `mvn -o test -Dfrontend.skip=true` 与
`cd src/main/frontend; npx vitest run` 得到）。

## 三、这一版长什么样（与原版的差别）

- **没有数据库**：所有状态都在项目目录下的文件里（`.specflow/flow/<id>/…`），人能打开看。
- **一个写者**：事件只有一条流（`events.jsonl`），`id` **恒等于行号**，重启接着数。
- **一次调用两条事件**：工具调用推 `running` + `ok/error` 两条（共用 `callId`），
  界面据此画「一边跑一边冒」的 chip。
- **闸只有一处**（`flow.Gates`），而「这一格自己的门槛」由这一格的动作去满足
  （`requireReachable`）——不然第一次用就死锁（端到端实测撞到过）。
- **写前快照 + 原子写 + 崩溃恢复**：`flow.Snapshot` / `core.Atomic` / `flow.Journal`。
- **⑤ 跑在副本里**：`test/TestSandbox` 把项目复制一份，容器挂副本；验收阶段 `src/` 冻结。
- **砍掉的**：老 `.specflow` 数据迁移、旧接口兼容层（`/api/drafts`、`/api/tasks`、`/api/chain`）、
  按阶段配模型、模板库 / 上下文库面板、`TaskDraft` 那层中间概念。

## 四、没做的 / 已知的

1. **按阶段配不同厂商/Key** 没做（一份 `project.yaml` 的 `llm` 段，全链共用）。
2. **老项目的数据不迁移**：v2 只认自己的落点（`flow/<id>/`），旧 `.specflow` 里的草稿/任务不读。
3. **`test/TestSandbox` 的收尾**：容器与副本会在 `close()` 里收掉；但如果进程被硬杀，
   容器会留着（下一次 `up` 用的是同名 project，`down` 会收掉它）。**这一条没做实时验证。**
4. **审批（y/n）在真链路上没被触发过**：这一次端到端里测试 Agent 没跑高危命令（`替你点了 0 条`）。
   逻辑有单测（`ApprovalsTest`）与设计（阻塞 + 超时按拒），但"真跑起来停住等人"这条**没实测**。
5. **界面只做过浏览器冒烟**（七个路由零 JS 报错），**没有逐页视觉走查**——
   我看不到图（模型不接图像输入），观感得你自己看。
6. **`mvn -o test` 与前端单测的数字见下**；其中若有红，红在哪一条会如实写出来。

## 五、实测记录

> 下面都是刚跑出来的原始输出，没修过数。

### 1. 后端测试

```
cd E:\specflow-v2
mvn -o test -Dfrontend.skip=true
→ [INFO] Tests run: 115, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
```

按类节选：`CaseListTest` 18 条、`SandboxTest` 7 条……逐类清单在
`E:\sf-e2e\reports-v2\backend-tests.log`。

### 2. 前端测试与构建

```
cd E:\specflow-v2\src\main\frontend
npx vitest run
→ Test Files  6 passed (6)
  Tests       95 passed (95)
  Duration    271ms

npx vite build
→ dist/index.html                  0.93 kB │ gzip:  0.60 kB
  dist/assets/index-BgV49IZ5.css  14.36 kB │ gzip:  3.55 kB
  dist/assets/index-CLzKrCKA.js  143.36 kB │ gzip: 52.30 kB
  ✓ built in 166ms
```

### 3. 浏览器冒烟（七个路由）

```
node src\main\frontend\tests\smoke.mjs E:/specflow-v2/target/specflow.jar 3955
→ /          h1「流程」…
  /workbench /env /probe /cases /dev /test  各页均：✓ 无 JS 报错 ✓ 无失败面板
  ✓ 7 页全过：没有 JS 报错、没有失败面板、外壳都渲染了
```

每页都断言了三件事：`h1` 出得来、六个侧栏格子 / 折叠块 / chip 区都在、控制台零错误。
（早先每页有一条 `favicon.ico → 500` 的噪音：兜底处理器把「静态资源找不到」翻成了 500。
已修两处——`web/Errors` 对 `NoResourceFoundException` 返 404，界面改用内联 SVG 图标，
于是连请求都不发。现在七页控制台一条不剩。）

### 4. 五节点端到端（真模型 + 真 Docker）

**连着跑通两次**：第一次 157 秒、第二次 150 秒（就是下面第 5 条）。
两次都是真调 `deepseek-chat`、真起容器、真编译，不是桩。

### 5. 第二次端到端（改完 favicon/404 之后，原始输出）

```
cd E:\sf-e2e
node run2.mjs --jar E:/specflow-v2/target/specflow.jar --port 3957

  ✓ ① 跑环境 Agent（12.4s） env.yaml 12 行、编译命令「javac -d out/main src/main/java/com/demo/*.java」
  ✓ ① 保存声明（0.1s） env.yaml → .specflow/env.yaml；编译命令 → （没变）
  ✓ ① 起环境（真 docker）（2.8s） 容器 ["sf-sfv2-3957-1-3d94fd-app-1"]
  ✓ ① 编译（0.7s） 状态 PASSED、事件 40 条
  ✓ ① 冻结（0.1s） 状态 DONE
  ✓ ② 需求 + 跑探针（10.4s） 要改 1 个、可读 2 个
  ✓ ② 保存 + 冻结（0.1s） 状态 DONE
  ✓ ③ 生成 + 保存 + 冻结（3.4s） 11 条、状态 DONE
  ✓ ④ 跑开发（6.3s） 编译通过
  ✓ ④ 自己核一遍（javac + java 看行为）（0.7s） 打印 reserve(5)=false、onHand(A)=3
  ✓ ④ 冻结（0.1s） 状态 DONE
  ✓ ⑤ 跑测试（容器沙箱）（109.0s） 11 条全到终态（PASS 10）
  ✓ ⑤ 提交 + 接受（0.2s） 已闭环（done=true）
  → 通过（150s）
```

报告原文：`E:\sf-e2e\reports-v2\e2e-2026-10-16T13-17-10.json`，
控制台日志：`E:\sf-e2e\reports-v2\final-e2e.log`。

（「④ 自己核一遍」这一行不采信 Agent 的自述：脚本自己 `javac` + `java` 跑一遍，
要求打出 `reserve(5)=false`、`onHand(A)=3`。这两次都打出来了。）
