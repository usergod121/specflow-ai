# specflow v2 · 交付说明

> 2026-10-16 夜。这一版是**从 0 重写**的：`E:\specflow-v2`。
> 三份契约在 `docs/`（架构/API/UI），设计在 `PRODUCT.md` / `DESIGN.md`，任务账在 `TASKS.md`。
> 这一份只回答三件事：**怎么跑、验过什么、没做什么**。

## 一、怎么跑

**双击就行**（不用记任何命令）：

| 双击 | 它干什么 |
| --- | --- |
| **`start.bat`** | 起引擎（前端已经打进 jar，一个进程就是全部）→ 等它健康 → 开浏览器 |
| **`stop.bat`** | 停引擎 + 把这个项目的容器 / 卷 / 网络收干净；`stop.bat -Purge` 连流程数据一起删 |
| `dev.bat` | 只有改前端想要「保存即刷新」时才用（引擎 + Vite 热更新） |

第一次双击会自己办这些：找 Java 17+ → 缺 jar 就用 Maven 编一份 → 看 Docker 在不在 →
挑一个空端口（默认 8780）→ 密钥从项目 `local.env` / 环境变量 / 这台机器上别的 specflow 里
找一份写进项目 → 起引擎 → 打开 `http://127.0.0.1:8780`。

参数都可选：`-Project <目录>`、`-Port <端口>`、`-ApiKey sk-xxx`、`-Rebuild`（改了 Java 后重建）、
`-Foreground`（日志留在窗口里）。定位与状态记在 `.run\engine.json`，`stop.bat` 只按这份记录
去停——**它不会去动 8770 上你原来那份实例**。

手工起也行：

```powershell
cd E:\specflow-v2
mvn -o clean package                 # 编 Java + 编前端（Vite），产物 target\specflow.jar
java -jar target\specflow.jar web --port 8780 --no-open -p <你的项目目录>
# 浏览器打开 http://127.0.0.1:8780
```

不开界面的四条：

```powershell
java -jar target\specflow.jar env prepare -p <项目>  # 只铺配置（不动 docker）
java -jar target\specflow.jar env init    -p <项目>  # 起环境（拉镜像 + init，失败就 down -v 收干净）
java -jar target\specflow.jar env status  -p <项目>  # 现在什么状态
java -jar target\specflow.jar env down    -p <项目>  # 收干净（主环境 + 测试沙箱，连卷）；--purge 再删流程数据
```

**前置**：JDK 17、Docker Desktop 在跑、项目里 `.specflow/local.env` 有 `SPECFLOW_API_KEY=sk-…`
（`start.bat` 会替你把最后这条办了；没有的话界面能开，只是点「跑」会告诉你缺密钥）。

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
   逻辑本身有五条单测（`test/ApprovalsTest`：真的卡住线程 / 点 y 放行 / 点 n 按拒 /
   收尾时按拒唤醒不挂死 / 前后脚两条各等各的），但**"真跑起来停在界面上等人点"这一条没实测**——
   要实测得让 Agent 恰好去跑一条高危命令，那是模型说了算的，我没法可靠地摆出来。
   写这个测试时顺手发现并修掉一个真问题：待审批表原来用 `ConcurrentHashMap`，
   可面板的合同是"按问的先后"，而它的迭代顺序不保证（`seq` 自增也没锁）——已换成
   "一把锁 + 有序表"，测试里那条顺序断言就是钉它的。
5. **界面只做过浏览器冒烟**（七个路由零 JS 报错），**没有逐页视觉走查**——
   我看不到图（模型不接图像输入），观感得你自己看。
6. **审批收场有个毫秒级窗口**：`Approvals.clear()`（换项目 / 关服务时调）只答掉"此刻正在等着的"，
   抢在这次收场之后才登记进来的那一条，会一直等到它自己的时限（10 分钟）。
   换项目时那条链路已经在收尾，窗口是毫秒级；要彻底消掉得让 `ask` 带一个"这一轮已作废"的记号，
   那是 `Runs` 那一层的事，**这一版没做**（写在 `Approvals.clear` 的注释里）。
7. **启动脚本只在 Windows PowerShell 5.1 上验过**：这台机器没装 PowerShell 7，
   所以 `start.bat` 里那句"有 pwsh 就用 pwsh"的分支**没实测**（走的是 `powershell` 那条）。
8. **后端与前端单测的数字见第五节**：这一版是 **134 + 95 全绿**（交付前又补了 14 条：
   审批 5 条、env 子命令 9 条、JSON/YAML 入口 5 条里的一部分，所以数字从 115 一路上来）。

## 五、实测记录

> 下面都是刚跑出来的原始输出，没修过数。

### 1. 后端测试

```
cd E:\specflow-v2
mvn -o test -Dfrontend.skip=true
→ [INFO] Tests run: 134, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
```

逐类（全绿，共 15 个测试类）：

```
agent.LoopTest              13    env.ComposeTest           5
agent.ProtocolTest           8    env.EnvCliTest            9
agent.ToolRegistryTest       9    env.EnvConfigLoaderTest   8
agent.tools.ApplyPatchTest   8    env.EnvironmentTest       9
agent.tools.ReadFileTest     7    flow.GatesTest           11
agent.tools.ToolsTest       12    test.ApprovalsTest        5
core.JsonTest                5    test.CaseListTest        18
                                  test.SandboxTest          7
```

（原始输出在 `E:\sf-e2e\reports-v2\final2-backend.log`。
其中 `ApprovalsTest` 与 `EnvCliTest` 是交付前补的——见第四节第 4、6 条。）

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

### 6. 双击那一套（`start.bat` / `stop.bat`）：真的开、真的收

**开**（`start.bat -NoBrowser`，原始输出）：

```
  specflow  五节点规格驱动引擎
-> 找一个 Java 17+
   OK  C:\Users\Lenovo\.jdks\ms-21.0.10\bin\java.exe
   ..  用现成的 target\specflow.jar（改了 Java 就加 -Rebuild）
-> 看一眼 Docker
   OK  Docker 在跑（29.2.1）
-> 挑一个项目
   OK  E:\specflow-v2\fixture
-> 看密钥
   OK  用项目 .specflow\local.env 里的
   ..  端口 8780
-> 起引擎
   OK  起来了（8780）
  地址   http://127.0.0.1:8780
```

起完的核对：`GET /api/actuator/health` → `UP`；`GET /workbench` → **200**。

**收**（先起一次真环境，再收）：

```
docker ps  →  sf-fixture-660e62-app-1  Up
stop.bat   →  • 环境已关闭（down -v）：Container sf-fixture-660e62-app-1 Stopping
收完核对   →  容器没了、网络也没了
```

**销毁**（`stop.bat -Purge -Yes`）：

```
• 流程数据已删：.specflow/flow（4 个文件）；project.yaml / env.yaml / local.env 都留着
```

**它没碰你原来那份实例**：全程 `8770` 一直是 `pid 6872` 在听，
`stop.bat` 只按 `.run\engine.json` 记的 pid 和「本仓库 jar 的全路径」找进程
（故意不按文件名匹配：这台机器上还跑着 `E:\specflow-ai` 那一份）。

顺手清掉了自己早先测试留下的 19 个容器 + 19 个网络（`sf-sfv2-*` / `sf-sf-e2e-*` / `sf-junit-*`），
**你那两个 `sf-library-management-backend-*` 一个没动**。
