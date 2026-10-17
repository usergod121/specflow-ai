# specflow v3 · 交付说明

> 2026-10-17。这一版是**在 v2 上就地升级**（没有新开目录）：`E:\specflow-v2`。
> 三份契约在 `docs/`（架构 / API / UI），**讲解这一本在 `docs/BOOK.html`**（用浏览器打开），
> 设计在 `PRODUCT.md` / `DESIGN.md`，任务账在 `TASKS.md` 与 `docs/v3/PLAN.md`，
> 这一轮的取舍与评审记录在 `docs/v3/DECISIONS.md`。
>
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
java -jar target\specflow.jar env init    -p <项目>  # 起环境（拉镜像 + init + 在容器里构建一次）
java -jar target\specflow.jar env status  -p <项目>  # 现在什么状态
java -jar target\specflow.jar env down    -p <项目>  # 收干净（主环境 + 测试沙箱，连卷）；--purge 再删流程数据
```

**前置**：JDK 17、Docker Desktop 在跑、项目里 `.specflow/local.env` 有 `SPECFLOW_API_KEY=sk-…`
（`start.bat` 会替你把最后这条办了）。

## 二、验过什么（都是真跑出来的）

### 1. 单元与模块级测试

| 套件 | 数量 | 命令 |
|---|---|---|
| 后端 | **162 条全绿** | `mvn -o test -Dfrontend.skip=true` |
| 前端 | **105 条全绿**（7 个文件） | `cd src/main/frontend; npx vitest run` |
| 前端构建 | 过（143 kB js / 14 kB css） | `npx vite build` |

这一轮**新加**的钉子：`EventLogTest` 6 条（id≡行号 / 重启接着数 / 外部改动可见 / 坏行变 warn /
回退先留副本 / 文件不在不抛）、`CaseBudgetTest` 5 条（超预算跳过 / 写结论换账 / 无待验不拦 /
SKIPPED 是终态不是结论 / reopen 可重跑）、`CommandGuardTest` 6 条（`$HOME` 放行 / `sudo` 直接拒 /
装工具链拒 / 脚手架放行 / 老两档还在 / 认出构建工具）、`BudgetsTest` 4 条、
`StuckCheckTest` 3 条，外加 `GatesTest` 里 3 条（容器能力校验）与前端 `wiring.spec.js` 10 条。

### 2. 浏览器冒烟（七个路由，真浏览器）

```
node src\main\frontend\tests\smoke.mjs target\specflow.jar 3973
→ / /workbench /env /probe /cases /dev /test 各页：✓ 无 JS 报错 ✓ 无失败面板
  ✓ 7 页全过
```

并且确认了**前端真的走 SSE**：引擎日志里七个页面各订一条
`GET /api/node/<key>/stream?from=0`（不再靠 1.2 秒一次的轮询）。

### 3. SSE 本身（curl 直接看帧）

```
curl.exe -s -N --max-time 6 "http://127.0.0.1:3971/api/node/env/stream?from=0"
→ event:events   data:{"running":false,"stage":"","events":[…],"next":1}
  event:done     data:{…}     ← 没有在跑的活就主动收场（不关的话浏览器会自动重连）
```

### 4. 五节点端到端（真模型 deepseek-chat + 真 Docker）

见第五节。

## 三、这一版改了什么（对着事故写）

| 改动 | 事故 | 判据 |
|---|---|---|
| ① 的闸新增 `containerBuild`：起完环境**在容器里**认工具 + 真构建一次 | 容器里没有 Maven，① 却亮绿灯；⑤ 自己装 Maven 烧掉 28 轮 / 15 分钟 | `GatesTest` + 真项目会红并给出改法 |
| `Preflight`：⑤ 开工前 `command -v`，缺工具立刻失败并指名 ① | 同上（最划算的一条：几百毫秒换掉 28 轮） | 端到端 + 单测 |
| `CaseBudget`：每条用例 12 轮，超了标 `SKIPPED`；`case_update` 换账；新增「重试这一条」 | 60 轮用尽、12 条用例 0 条结论；摘要还谎报 0 | `CaseBudgetTest` 5 条 |
| `CommandGuard` 三档：`$HOME` 放行、`sudo` 直接拒、装工具链直接拒 | 2 轮无理由硬拒 + 187 秒无效审批 + 20 轮装 Maven | `CommandGuardTest` 6 条 |
| `Budgets`：超时收成一个来源 | 界面「改超时」只回显不生效（Javadoc 说共用、方法体却写死 30 分钟） | `BudgetsTest` 4 条 |
| 依赖缓存卷（external，跨轮保留） | 每轮重下依赖：7 轮 `sleep`、17 分钟白等 | `ComposeTest` 断言 `external: true` 与 `HOME` |
| 提示词同时给出宿主与容器两个路径 | 模型在 bash 里猜路径，5 轮白烧 | 提示词 + 端到端 |
| `StuckCheck`：连续 3 次相同调用就打断 | 连着 7 轮 `sleep 240`：每轮成功、每轮没进展 | `StuckCheckTest` 3 条 |
| `Gate` ×5 + 一张表（替掉 switch） | 加一格要改三处，漏改只会"这一格永远不动" | **`GatesTest` 一个字节都没改** |
| `EventLog` 行快照（校验和失效） | SSE 每 250ms 读一次全文件；这个类此前零测试 | `EventLogTest` 6 条 |
| ① 与 ④ 补「冻结」按钮 + `wiring.spec.js` | 页面没有冻结入口，下游永远解锁不了 | 前端 10 条接线钉子 |

## 四、没做的 / 已知的（**不许当成做了**）

1. **`.specflow/flow.yaml`（流程 DSL）没做**。评审判断它会是"第四份真相"，且与"前端尽量少改"冲突；
   这一轮的"统一封装"落在既有的 `Facts` / `Gates` / `CaseList` 上。理由见 `docs/v3/DECISIONS.md` 第三节。
2. **上下文压缩（compact）没做**：长对话仍可能爆。`Loop` 有轮次上限兜底，但没有摘要与锚点。
3. **可恢复中断没做**：审批仍是"阻塞线程 + 10 分钟超时按拒"。这一轮只把 `sudo` 从审批里拿掉了
   （那是 187 秒的真正来源），没有改中断模型。
4. **运行级 checkpoint / 崩溃后接着跑没做**：仍然只有「没写 END = 中断」+ 快照回滚。
5. **工具中间件链（`ToolHook`）没做**：仍然是装饰器（`Frozen` / `CaseBudget` / `StuckCheck` 各包一层）。
6. **`Api` 这一层仍没有单元测试**：项目没引 `spring-boot-starter-test`，MockMvc 要动 pom。
   现在的覆盖是「浏览器冒烟 + SSE curl + 端到端 HTTP 全链路」——**更弱但够用**，如实写在这里。
7. **审批（y/n）在真链路上仍没被触发过**：逻辑有五条单测（`ApprovalsTest`），但"真停下来等人点"没实测；
   这一轮端到端同样没触发（`替你点了 0 条`）。
8. **`TestSandbox` 被硬杀时容器会留着**（下一次同名 `up`/`down` 会收掉）——没做实时验证。
9. **界面只做过浏览器冒烟**（七路由零控制台错误），**没有逐页视觉走查**：我看不到图（模型不接图像输入）。
10. **宿主机命令的守卫是"文本级"**：`project.yaml` 的 `build.compile` 现在过 `CommandGuard.forbidden`，
    但它终究是在你机器上以你的身份执行的（这是它的语义），只是危险写法会被拒。

## 五、实测记录

### 1. 后端测试

```
cd E:\specflow-v2
mvn -o test -Dfrontend.skip=true
→ [INFO] Tests run: 162, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
```

逐类（节选，全绿共 21 个测试类）：`agent.LoopTest` 13、`test.CaseListTest` 18、`flow.GatesTest` 14、
`test.SandboxTest` 7、`core.EventLogTest` 6、`env.CommandGuardTest` 6、`test.CaseBudgetTest` 5、
`env.BudgetsTest` 4、`agent.StuckCheckTest` 3、`core.JsonTest` 5、`test.ApprovalsTest` 5。

### 2. 前端

```
cd E:\specflow-v2\src\main\frontend
npx vitest run  → Test Files 7 passed (7) / Tests 105 passed (105)
npx vite build  → dist/index.html 0.93 kB · assets 143 kB js / 14 kB css
```

### 3. 浏览器冒烟（七个路由）

```
node src\main\frontend\tests\smoke.mjs E:/specflow-v2/target/specflow.jar 3973
→ ✓ 7 页全过：没有 JS 报错、没有失败面板、外壳都渲染了
```

### 4. 五节点端到端（真模型 + 真 Docker）

```
cd E:\sf-e2e
node run2.mjs --jar E:/specflow-v2/target/specflow.jar --port 3977
→ 原始输出：E:\sf-e2e\reports-v2\v3-e2e.log
```

**端到端里最值钱的一行**仍然是 ④ 的「自己核一遍」：脚本**不采信 Agent 的自述**，
自己 `javac` + `java` 跑一遍目标项目，要求打印出 `reserve(5)=false` 与 `onHand(A)=3`。

### 5. 这一轮的两次事故（记下来，别犯第二次）

1. 用 `pwsh` 的 `.Replace()` 往 Java 文件里塞**新中文**会因编码边界把文件弄成乱码；
   靠 `git checkout` 回滚 + 改用编辑工具重做救回来。教训：**带中文的文件改动一律用编辑工具**。
2. `git commit -m` 的消息里带 `"` 会把 shell 引号搞坏，提交静默失败而 tag 已打上。
   教训：提交信息里只用 `「」`。
