# specflow v3 · 交付说明

> 2026-10-17。这一版是**在 v2 上就地升级**（没有新开目录）：`E:\specflow-v2`。
> 三份契约在 `docs/`（架构 / API / UI），**讲解这一本在 `docs/SYSTEM.html`**（用浏览器打开），
> 设计在 `PRODUCT.md` / `DESIGN.md`，任务账在 `TASKS.md` 与 `docs/v3/PLAN.md`，
> 这一轮的取舍与评审记录在 `docs/v3/DECISIONS.md`。（`PRODUCT.md` 与 `TASKS.md` 是过程台账，
> 只留在本机，没入库。）
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
| 后端 | **211 条全绿**（30 个测试类） | `mvn -o test -Dfrontend.skip=true` |
| 前端 | **102 条全绿**（7 个文件） | `cd src/main/frontend; npx vitest run` |
| 前端构建 | 过（143.9 kB js / 14.4 kB css） | `npx vite build`（`mvn package` 里自动跑） |

这一轮**新加**的钉子（都是"某一处没接上、跑起来不报错"那一类）：

| 测试 | 条数 | 钉的是什么 |
|---|---|---|
| `DevMessageTest` | 4 | ② 的 `prompt`/`files`/`context` 三样真的进了 ④ 的提示词、顺序固定、空的不炸、空 prompt 当场拒 |
| `ApiTest`（新增 1 条） | 1 | `context` 以**字符串**提交时不会被悄悄清空 |
| `ProjectTest` | 3 | 改盘之后再读必须是新值 / 文件没了保留上一次 / 从没读到过给默认值 |
| `LinuxSafeTest` | 4 | 编译命令里的反斜杠换成正斜杠的判据，以及"带正则的命令一律不动" |
| `LoopAnchorsTest` | 5 | 压缩之后模型**真的**看得见「读过/改过的路径」「最近的失败」；调用方补的排在其后；补锚点抛异常不掀链子 |
| `SnapshotTest` | 6 | 原文逐字节回来 / 新建的文件被删掉 / 清单不在就不按半份清单回滚 / 同一份不许处置两次 / 项目外的文件拍不进去但要说出来 / `latest()` 找最近一份 |
| `CompactTest`（加强 1 条） | — | 压**第二次**时任务书不丢、锚点不叠成两份 |
| `StuckCheckTest`（新增 1 条） | 1 | ⑤ 判死说「这条用例」，①~④ 说「这一轮」 |

更早那批：`EventLogTest` 6 条（id≡行号 / 重启接着数 / 外部改动可见 / 坏行变 warn / 回退先留副本 /
文件不在不抛）、`CaseBudgetTest` 6 条、`CommandGuardTest` 6 条、`BudgetsTest` 4 条、`StuckCheckTest` 3 条、
`CheckpointTest` 4 条、`FlowIndexTest` 4 条、`ActionsTest` 6 条、`ApiTest` 6 条、`ApprovalsTest` 7 条、
`CompactTest` 4 条，外加 `GatesTest` 里 3 条（容器能力校验）与前端 `wiring.spec.js` 7 条
（跨端对齐：`Actions.java` 里声明的每个 id，对应视图里都必须有处理函数）。

### 2. 浏览器冒烟（七个路由，真浏览器）

```
node src\main\frontend\tests\smoke.mjs target\specflow.jar 4121
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

### 4. ② 的产物存不存得住（真 HTTP + 真盘上）

13 项全过，明细见第五节第 4 小节。

### 5. 五节点端到端（真模型 deepseek-chat + 真 Docker）

见第五节第 5 小节。

### 6. 计数器（`/api/actuator/metrics` 里我们自己的那五个）

见第五节第 6 小节。

## 三、这一版改了什么（对着事故写）

| 改动 | 事故 | 判据 |
|---|---|---|
| ① 的闸新增 `containerBuild`：起完环境**在容器里**认工具 + 真构建一次 | 容器里没有 Maven，① 却亮绿灯；⑤ 自己装 Maven 烧掉 28 轮 / 15 分钟 | `GatesTest` + 真项目会红并给出改法 |
| `Preflight`：⑤ 开工前 `command -v`，缺工具立刻失败并指名 ① | 同上（最划算的一条：几百毫秒换掉 28 轮） | 端到端 + 单测 |
| `CaseBudget`：每条用例 12 轮，超了标 `SKIPPED`；`case_update` 换账；新增「重试这一条」 | 60 轮用尽、12 条用例 0 条结论；摘要还谎报 0 | `CaseBudgetTest` 6 条 |
| `CommandGuard` 三档：`$HOME` 放行、`sudo` 直接拒、装工具链直接拒 | 2 轮无理由硬拒 + 187 秒无效审批 + 20 轮装 Maven | `CommandGuardTest` 6 条 |
| `Budgets`：超时收成一个来源 | 界面「改超时」只回显不生效（Javadoc 说共用、方法体却写死 30 分钟） | `BudgetsTest` 4 条 |
| 依赖缓存卷（external，跨轮保留） | 每轮重下依赖：7 轮 `sleep`、17 分钟白等 | `ComposeTest` 断言 `external: true` 与 `HOME` |
| 提示词同时给出宿主与容器两个路径 | 模型在 bash 里猜路径，5 轮白烧 | 提示词 + 端到端 |
| `StuckCheck`：连续 3 次相同调用就打断 | 连着 7 轮 `sleep 240`：每轮成功、每轮没进展 | `StuckCheckTest` 3 条 |
| `Gate` ×5 + 一张表（替掉 switch） | 加一格要改三处，漏改只会"这一格永远不动" | **`GatesTest` 一个字节都没改** |
| `EventLog` 行快照（校验和失效） | SSE 每 250ms 读一次全文件；这个类此前零测试 | `EventLogTest` 6 条 |
| ① 与 ④ 补「冻结」按钮 + `wiring.spec.js` | 页面没有冻结入口，下游永远解锁不了 | 前端接线钉子（7 条） |
| **② 的 `files`/`context` 真的进了 ④ 的提示词**（`Dev.userMessage` 四段） | 在 ② 里改「上下文」对 ④ 毫无影响，想让它生效只能自己去改 prompt | `DevMessageTest` 4 条钉提示词原文 |
| **`Api.lines` 两种形状都认**（字符串按行拆） | 前端把 `context` 当一段多行文本提交，后端只认数组 → 保存像成功、刷新后是空的 | `ApiTest` 用 String 存一次再读回来断言 |
| **`Project.raw()` 按 (size, mtime) 每次问盘** | 填了编译命令 → 点冻结 → 闸说"编译命令还是空的"（界面上没有"重开项目"按钮） | `ProjectTest` 3 条 |
| **编译命令要"两处都能跑"**：提示词要求正斜杠 + `Capabilities` 兜一次自动改写 | ① 的 Agent 交出 `javac -d out\main …`，宿主机过、容器里退出码 2，① 冻结被闸拦下 | `LinuxSafeTest` 4 条 + 端到端 |
| **压缩的锚点表搬进 `Loop` 自己攒** | ②③④ 一压缩就只剩"system + 任务书 + 尾巴"，它开始重读同一个文件——原来只有 ⑤ 拼了锚点表 | `LoopAnchorsTest` 5 条 |
| **`Compact` 压第二次不再丢任务书**（幂等） | 长运行跑到中段"跑偏"：需求没了，只剩"刚才在干什么"；锚点表还叠成两份 | `CompactTest` 里那条"压两次" |
| **`Snapshot` 补 6 条测试 + ④ 冻结时 `accept()`** | 回滚是唯一会**反向写用户真项目**的动作，此前零测试；且每跑一次开发就在盘上留一份没人处置的原文副本 | `SnapshotTest` 6 条 |
| **`Metrics` 接上 registry（5 处调用）** | `Metrics` 写完一个调用点都没有，而文档一直写着"actuator 能看到我们自己的计数器" | 真引擎上 `specflow.runs` 等 5 个指标出现（见 §五.6） |
| **`StuckCheck` 五格都包** | 只有 ⑤ 装了打转检测；④ 反复 `read_file` 同一个文件照样能烧光轮次 | `StuckCheckTest` 加 1 条（话术按"用例/这一轮"分） |
| **删掉 `Loop.onToolResult`（只有一个测试在用的钩子）** | 生产代码零调用点——按"只有测试在用的口子 = 生产没接"这条判据，它该走；`INJECT` 一起删 | `LoopTest` 13 → 11（删掉那两条） |
| **`Api.flowView()` 补上 `actions`**（用户报的 bug） | `NodeView` 有 actions、`Actions.TABLE` 也写了，可路由往外拼响应时漏了那一栏 → **五个页面全都只有状态、没有按钮**，零报错；用户点「新开流程」后连第一步都点不了 | `ApiTest` 断言每格都带 actions（① 含 `up`/`freeze`）+ `smoke.mjs` 拿声明和真实渲染的页头按钮对账 |
| **`READY` 的界面词：已就绪 → 待完成** | 「已就绪」在 ① 上会被读成"环境已经起好了"，可用户还得去点「起环境」（用户就是这么误会的） | `facts.spec.js` / `copy.spec.js` 两处词表 + `UI.md` |
| **禁用条件不许写成字面量**（用户报的"探测环境点不动"） | `{ disabled: { agent: Boolean(store.flow) === false } }` 是**挂载那一刻**求的值：直接打开 `/env` 时 `/api/flow` 还没回来 → 按钮**永久 disabled**，点了不发请求、也不报错。③ 的「保存」、② 的「存需求」同一个坑 | `lib/actions.js` 允许 `options` 传函数；三个视图改成 `() => ({…})`；`actions.spec.js` 4 条 |
| **① 增加「宿主机过了 · 容器里没过」一行** | "编译过了"与"容器里没有 mvn"读着像自相矛盾（两条编译本来就不同），用户会以为闸坏了 | `EnvNode.vue`：`compilePassed && !containerBuild` 时提示一句（判据没动） |

## 四、没做的 / 已知的（**不许当成做了**）

1. **流程 DSL 没做，但"节点定义散在 6 处"这个真问题已经收敛**：动作表只有 `flow/Actions.java` 一份，随 `GET /api/flow` 下发，视图只按 id 绑处理函数（且视图里不许再出现按钮文案，由 `wiring.spec.js` 机器检查）。把"流程"本身做成 YAML 仍然判断为不值：
   这一轮的"统一封装"落在既有的 `Facts` / `Gates` / `CaseList` 上。理由见 `docs/v3/DECISIONS.md` 第三节。
2. ~~上下文压缩没做~~ ——**已做**（`agent.Compact`，确定性锚点 + 任务书 + 尾巴，**不调模型**；
   触发线 6 万字符；`CompactTest` 4 条）。剩余的真缺口只有一条：压缩点由"字符数"判，
   没有按"这一轮是不是刚失败"来调。
3. **审批仍是"阻塞线程 + 2 分钟超时按拒"**：三态（`ALLOW` / `DENY` / `TIMEOUT`）与两种措辞
   都有单测（1 秒时限验 TIMEOUT、DENY 与 ALLOW 分开），**"人不在、等满 2 分钟"这条分支没在真跑里等过**。
4. ~~运行级 checkpoint / 崩溃后接着跑没做~~ ——**已做**：`flow.Checkpoint`（只存"哪几条硬了、
   下一步干什么、花了几轮"，**不存对话历史**）+ `POST /api/node/test/resume`；`Journal.sweep()` 接进了
   `Session.open()`（此前这个方法**没有任何调用点**）。
5. **工具中间件链（`ToolHook`）没做**：仍然是装饰器（`Frozen` / `CaseBudget` / `StuckCheck` 各包一层）。
   评审结论：三个装饰器已等价覆盖，只差"返回值升级成四值枚举拿 `updatedInput`"。
6. **`Api` 这一层有测试了**（7 条，包内构造、不启 Spring），但覆盖的是**响应形状与增量语义**，
   不是每条路由都走一遍；真链路由浏览器冒烟 + 端到端补。
7. **审批（y/n）在真链路上触发过**（某一轮端到端 `替你点了 1 条审批`；最近这一轮是 `0 条`——
   模型没撞高危命令）。三态语义已验证：问出来 → 人回答 → 命令执行 → 结论写回。
8. **`TestSandbox` 被硬杀时容器会留着**（下一次同名 `up`/`down` 会收掉）——没做实时验证。
9. **界面只做过浏览器冒烟**（七路由零控制台错误），**没有逐页视觉走查**：我看不到图（模型不接图像输入）。
10. **宿主机命令的守卫是"文本级"**：`project.yaml` 的 `build.compile` 现在过 `CommandGuard.forbidden`，
    但它终究是在你机器上以你的身份执行的（这是它的语义），只是危险写法会被拒。
11. **"两处都能跑"这条只治了斜杠**：编译命令如果在宿主机用 `cmd.exe` 语法（如 `gradlew.bat`），
    进了 Linux 容器照样跑不通——那种情况的正确答案是改 `env.yaml` 的 image / init，
    闸会如实说"容器里构建不起来"，不会假装通过。

## 三点五、前端整段重写（第三版）

第二版的前端不是"不好看"，是**会告诉用户错的事**（按钮不渲染、按钮永远灰着、换项目还显示上一条流程、
叫停藏着）。按你的要求：整段重写、旧文件搬出项目、bat 加校验。

| 项 | 现在 |
|---|---|
| 旧前端备份（**在项目外面**） | `E:\specflow-frontend-old-20261017-234129`（43 个文件） |
| 新前端 | `src\main\frontend`：`api.js` / `store.js` / `router.js` / `styles/{tokens,app}.css` / `lib/{status,format,useNode}.js` / `components/{PageHead,EventStream}.vue` / `views/*`（7 页） |
| 状态纪律（这一版的地基） | 所有动作走 `store.act()`：成功之后、409 之后都 `await refresh()`；换项目全量重来；有活在跑 1.5 秒轮询兜底 |
| 动作按钮 | 只按 `GET /api/flow` 的 `actions` 渲染；`disabled` 每次渲染重算；`onlyWhileRunning`（叫停）提到页头最前 |
| 测试 | `tests/unit/logic.spec.js` 11 条（状态词 / 格式化 / store 刷新纪律）；`tests/smoke.mjs` 真浏览器七页，对账"声明↔渲染""点了→发请求""换项目→状态跟着换" |
| bat 校验 | `start.ps1`：缺 `node_modules` 先 `npm install`；`clean package` 重打；**检查 jar 里 `static/index.html` 与 `static/assets/*.js`**，没有就报"包里的界面是空的" |

验证（都在 00:04 那个 jar 上）：

- 前端单元 **11 条全绿**；`vite build` 过（131 kB js / 12.9 kB css）
- 冒烟 **七页全过**：`/env` 状态「待完成」、页头 `探测环境 起环境 ⋯`；点「探测环境」真的发出 `POST /api/node/env/agent`；换项目 flowId 从 `20261018-001647` → `20261018-001700` 且五格跟着换
- 真引擎 8780 上七页复验：零控制台报错、零失败条

## 三点六、清掉的东西（别让我的痕迹留在你机器上）

| 清掉的 | 说明 |
|---|---|
| `E:\library-management\library-management-backend\.specflow` | 我的运行在里面留下的流程数据。**已先备份**到 `E:\specflow-data-backup-20261018-001749`（91 个文件），再删除；项目目录现在只剩 `.idea / sql / src / target / pom.xml / unknown.bak` |
| `E:\sf-e2e\` | 我这几轮写的验证脚本（e2e 驱动、冒烟、探针回归、各种 check）整目录删除 |
| 临时项目目录 | `%TEMP%` 下 `sfv2-*` / `sf-smoke-*` / `sf-v2-*` / `junit-*` / `sf-accept` / `sf-headtree`，共 41 个 |
| 容器 / 网络 / 卷 | 我起的 `sf-*` 容器 2 个、网络 1 个、卷 230 个（含 `sf-library-management-backend-*` 与一堆 `sf-junit-*`）。你的 `kust-*` / `docker-*` 一个都没动 |
| `recent.json` | 只剩你自己的那个项目（之前被我的单测塞过 9 个 `junit-` 死条目，已清） |
| `specflow-v2\.run\` | 我的 pid/端口记录 |
## 五、实测记录

### 1. 后端测试

```
cd E:\specflow-v2
mvn -o test -Dfrontend.skip=true
→ [INFO] Tests run: 211, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
```

分布（全绿共 30 个测试类）：`agent` 72（`LoopTest` 11 · `ToolsTest` 12 · `ToolRegistryTest` 9 ·
`ProtocolTest` 8 · `ApplyPatchTest` 8 · `ReadFileTest` 7 · `LoopAnchorsTest` 5 · `CompactTest` 4 ·
`DevMessageTest` 4 · `StuckCheckTest` 4）、`env` 46（`EnvironmentTest` 9 · `EnvCliTest` 9 ·
`EnvConfigLoaderTest` 8 · `CommandGuardTest` 6 · `ComposeTest` 6 · `BudgetsTest` 4 · `LinuxSafeTest` 4）、
`test` 38（`CaseListTest` 18 · `SandboxTest` 7 · `ApprovalsTest` 7 · `CaseBudgetTest` 6）、
`flow` 34（`GatesTest` 14 · `ActionsTest` 6 · `SnapshotTest` 6 · `CheckpointTest` 4 · `FlowIndexTest` 4）、
`core` 14（`EventLogTest` 6 · `JsonTest` 5 · `ProjectTest` 3）、`web` 7（`ApiTest`）。

### 2. 前端

```
cd E:\specflow-v2\src\main\frontend
npx vitest run  → Test Files 7 passed (7) / Tests 102 passed (102)
npx vite build  → dist/index.html 0.93 kB · assets 143 kB js / 14.4 kB css
```

### 3. 浏览器冒烟（七个路由）

```
node src\main\frontend\tests\smoke.mjs E:/specflow-v2/target/specflow.jar 4121
→ / /workbench /env /probe /cases /dev /test 各页 ✓ 无 JS 报错 ✓ 无失败面板
  ✓ 7 页全过：没有 JS 报错、没有失败面板、外壳都渲染了
```

### 4. ② 的产物存不存得住（真 HTTP + 真盘上）

这一条是专门为"改了没反应"那个 bug 写的，13 项全过：

```
node E:\sf-e2e\probe-context.mjs 4122 E:/specflow-v2/fixture
→ 打开项目 ✓ ① 保存编译命令 ✓ ① 声明不需要环境 ✓ ① 冻结 → ② 解锁 ✓
  ② 保存探针产物 ✓ context 落盘成两条 ✓ files 落盘成两条 ✓ changes 也认字符串 ✓
  prompt 原样保留 ✓ 数组形状也走得通 ✓ 数组形状存下来的还是两条 ✓
  ② 的闸看见了「有文件」与「有 prompt」✓ ② 能冻结 → ③ 解锁 ✓
```

### 5. 五节点端到端（真模型 + 真 Docker）

```
cd E:\sf-e2e
node run2.mjs --jar E:/specflow-v2/target/specflow.jar --port 4004

  ✓ ① 跑环境 Agent（10.3s）编译命令「javac -d out/main src/main/java/com/demo/*.java」
  ✓ ① 保存声明（0.1s）  ✓ ① 起环境（真 docker）（3.7s）容器 sf-sfv2-4004-1-de494f-app-1
  ✓ ① 编译（0.6s）状态 PASSED  ✓ ① 冻结（状态 DONE）
  ✓ ② 需求 + 跑探针（9.2s）要改 1 个、可读 2 个   ✓ ② 保存 + 冻结
  ✓ ③ 生成 + 保存 + 冻结（2.6s）11 条            ✓ ④ 跑开发（8.2s）编译通过
  ✓ ④ 自己核一遍（javac + java 看行为）打印 reserve(5)=false、onHand(A)=3   ✓ ④ 冻结
  ✓ ⑤ 跑测试（容器沙箱）（161.1s）11 条全到终态（PASS 10）；替你点了 0 条审批
  ✓ ⑤ 提交 + 接受（0.1s）已闭环（done=true）
  → 通过（200s）
```

原始输出在 `E:\sf-e2e\reports-v2\`（`e2e-2026-10-17T12-13-45.json`）。
**同一个 jar 上跑过三次全通过**：200s / 11 条 / PASS 10、153s / 10 条 / PASS 10、196s / 10 条 / PASS 9。
差别全在 ⑤ 那一轮的容器沙箱（77s ~ 161s，模型每轮走的路不一样）。

**这一轮最值钱的两行**：

1. **「④ 自己核一遍」**：脚本**不采信 Agent 的自述**，自己 `javac` + `java` 跑一遍目标项目，
   要求打印出 `reserve(5)=false` 与 `onHand(A)=3`。
2. **① 那行的编译命令变成了 `javac -d out/main …`**：同一次修好**之前**它是 `out\main`，
   宿主机过、容器里退出码 2，① 冻结被闸拦下（那次端到端就是在这里断的）。提示词改成
   "路径一律正斜杠"之后，两处都能跑通——**这条修复是被一次真失败逼出来的，不是想出来的**。

### 6. 计数器实测（`Metrics` 从"零调用点"变成真的能用）

```
GET /api/actuator/metrics        → specflow.gate.blocked        ← 点一个会被闸拦下的动作之后
POST /api/node/env/agent         → specflow.runs / specflow.runs.finished
                                   specflow.run.seconds / specflow.running
```

顺序：开项目 → 点一个会被闸拦下的动作（`specflow.gate.blocked` 出现）→ 起一段 Agent 活
（其余四个出现）。**这一条以前是零调用点**——类写完了，一个地方都没用，而 `App` 的注释
一直写着"actuator 能看到我们自己的计数器"。

### 7. 这一轮的五次事故（记下来，别犯第二次）

1. 用 `pwsh` 的 `.Replace()` 往 Java 文件里塞**新中文**会因编码边界把文件弄成乱码；
   靠 `git checkout` 回滚 + 改用编辑工具重做救回来。教训：**带中文的文件改动一律用编辑工具**。
2. `git commit -m` 的消息里带 `"` 会把 shell 引号搞坏，提交静默失败而 tag 已打上。
   教训：提交信息里只用 `「」`。
3. **`start.bat` 里 `& start.ps1` 会让 `exit` 杀掉整个会话**：改成 `Start-Engine` 函数、
   `.bat` 只留 ASCII、`.ps1` 存成 UTF-8 with BOM。教训：批处理里的中文是雷区。
4. **一次编译命令毁掉一次端到端**：`out\main` 在容器里是错的写法（见上）。教训：
   「跑在哪几处」是写命令时的一部分，不是事后排查时才想的事。
5. **`.ps1` 少写 BOM，PowerShell 5.1 按 ANSI 读 → 一整行被注释吃掉**（`$root` 变成空字符串，
   于是所有验证命令都在 `E:\target\...` 这种路径上跑，报错还看不出是这个原因）。
   教训：**验证脚本一律 ASCII-only，或存 UTF-8 with BOM**。

### 8. 一次"验了个寂寞"（这条最该记）

`mvn package` 报 `Unable to rename target/specflow.jar`（还有一个 java 进程占着它），
**jar 没更新**，而随后一轮冒烟 + 探针回归 + 端到端**全部通过**——验的是旧包。
靠 `Get-Item target\specflow.jar` 的时间戳才发现（17:55 vs 19:59）。
教训：**每次验证前先看 jar 时间戳**；`mvn package` 的输出要盯 `Building jar` 那一行。
