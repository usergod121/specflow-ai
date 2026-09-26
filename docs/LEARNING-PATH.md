# 从哪开始读：一条学习路径

这份文档只回答一件事：**这个项目该按什么顺序读，每一步读完应该能回答什么问题。**

顺序不是按目录字母排的，而是按**数据流**排的：从"一次需求长什么样"开始，顺着它被拼成提示词、交给模型、
变成补丁、落盘、校验、留档，最后才走到界面和命令行。这样每读一个模块，你都已经知道它的输入从哪来。

每步都给了：**为什么先读它** / **读哪几个文件** / **读完要能回答的问题**（答不出来就回去重读那一段）。

---

## 模块依赖图（箭头 = "读完这个才好看懂那个"）

```
                ① spec ──────────┐   一次需求的数据结构（引擎的输入长什么样）
                                  │
                ② context ───────┤   提示词是怎么拼出来的（引擎↔模型的接口）
                                  │
                ③ llm ───────────┤   模型是怎么被调用的
                                  │
                ④ agent ◄────────┘   ★ 主循环：按施工单一步怎么走、什么时候重试、什么时候停
                 │  │  │
       ┌─────────┘  │  └──────────────┐
       ▼            ▼                 ▼
   ⑤ patch      ⑥ verify          ⑦ snapshot
   补丁这一关    编译校验           失败时怎么退回去
   （最硬）      （两个传感器之一）
       │            │                 │
       └────────────┴────────┬────────┘
                             ▼
                        ⑧ history      每次运行留下什么（事后复盘的唯一依据）
                             │
                             ▼
                        ⑨ review       检查阶段：先出方案 + 机器对照清单
                             │
                             ▼
                     ⑩ project/util     配置、密钥、路径安全、编码
                             │
                             ▼
                        ⑪ web          界面与服务端（最大的一块，放最后）
                             │
                             ▼
                        ⑫ cli          命令行入口（与 web 同级，可以跳过）
```

---

## 第 1 步：`spec` —— 一次需求长什么样

**为什么先读**：整个引擎的输入、界面表单的字段、留档的内容，全是这个包定义的。它不认识这个包，后面都白读。

**读这些**：
- `spec/Spec.java`（十个字段，注意注释里哪些是"引擎消费"、哪些是"给模型看的"）
- `spec/SpecLoader.java` + `spec/SpecValidator.java`（YAML 怎么读进来、什么算不合法）
- `spec/VerifySpec.java`（`compile` / `compile-command` / `max-retry` 三个开关，外加管整次运行总调用的 `max-rounds`）
- 项目根下的 `spec.example.yaml`（一份真实的样子）

**读完要能回答**：
1. 目标文件清单是"待办"还是"白名单"？（白名单，这是后面所有"越界被拒"的由来）
2. "新建文件"和"修改文件"用什么区分？（不用开关，看文件在不在）
3. 验收标准（`acceptance`）现在是"被使用"还是"只被存下来"？（只进提示词和留档，没人校验——这是已知缺口）

---

## 第 2 步：`context` —— 提示词是怎么拼出来的

**为什么第二步**：引擎与模型之间的**唯一接口**就是这个包。系统消息 = 协议 + 模板；用户消息 = 需求、验收、约束、上下文、目标文件内容。

**读这些**：
- `context/PatchProtocol.java`（补丁格式契约：SEARCH/REPLACE 怎么写、路径必须来自清单、新建文件时 SEARCH 留空）
- `context/ContextAssembler.java`（`systemMessage` / `userMessage` 两个方法，看每段是从 spec 的哪个字段来的）

**读完要能回答**：
1. 模型知道"自己能改哪些文件"吗？（知道：用户消息里有 `## 目标文件` 段且写了"清单外的你动不了"）
2. 模型看得到项目里**别的**文件吗？（看不到，这是刻意的——不给它工具）
3. `@requirement 编号` 这类要求写在哪、谁校验？（写在协议里，**没有**任何校验）

---

## 第 3 步：`llm` —— 模型是怎么被调用的

**读这些**：`llm/LlmClient.java`（一个方法的接口）、`llm/OpenAiCompatibleClient.java`（兼容 OpenAI 协议的实现，base-url/model/temperature 从哪来、密钥怎么找）、`project/Secrets.java`（密钥查找顺序：环境变量 → `.specflow/local.env`）。

**读完要能回答**：为什么换一家模型服务只要改 `project.yaml`？（协议兼容，客户端只有一份）

---

## 第 4 步：`agent` —— ★ 主循环（整个项目的心脏）

**为什么是重点**：所有"重试/回滚/停下"的判断都在这里。这一份读懂，项目就懂了七成。

**读这些**（按这个顺序）：
- `agent/AgentResult.java`（七种终态：成功 / 未校验 / 失败 / 待处理环境 / 被中断 / 信息不足 / 等人处置上一次的改动）——**先看它，你就知道这个循环可能怎么结束**
- `agent/AgentListener.java`（两级粒度：`round` 是"调了一次模型"，`step` 是施工单上的一步）——**先看它，你就知道循环会报出哪些事**
- `agent/DevelopmentAgent.java`：
  - `stepsFor` / `generateSteps`（施工单从哪来：检查阶段给的就用；没检查就花一次调用现生成；拿不到就退化成只有一步 = 老的单步行为）
  - `execute()` 那个 `for` + 内层 `while`：一行一行读。**只有一条路径**，单步执行是它的特例
  - `stepInstruction`（每一步开工前给模型的"只做这一步、只改这些文件"）
  - `StepCheckpoint`（每一步的进入点，只放内存：为什么它不落盘）
  - `restoreStep` / `closeRun` / `fail`（**三种回滚落点**：本步进入点、运行起点、只删快照）
  - `detectNeedContext`（模型说"信息不够"就停，不动盘）
  - 五个停止条件：本步校验重试上限、同一错误连着两轮、环境问题立刻停、某步重试耗尽（整轮回滚）、总轮次用尽
- `agent/RepairFeedback.java`（失败以后回喂给模型什么）
- `agent/ProgressMessages.java`（一次运行怎么把进度说给界面/命令行听）

**读完要能回答**：
1. "一轮"和"一步"差在哪？没有施工单时循环长什么样？（一步；就是老行为，不需要另写一段代码）
2. 编译通过会不会自动接着做下一半任务？（施工单上还有下一步就接着走；**没有施工单时成功就收工**——"写完 DTO/VO/Entity 就停下"的根因）
3. 环境问题（缺依赖）为什么不能继续重试？（模型会删掉用到那个包的地方，换来一个假绿灯）
4. 回滚发生在哪几处？分别落到哪个点？（本步进入点 / 运行起点 / 只删快照——见 `restoreStep`、`closeRun`）
5. 施工单上标了"中间态"的那一步编译没过，会发生什么？（记一笔继续下一步；但如果它是最后一步，整轮算失败）

---

## 第 5 步：`patch` —— 补丁这一关（最硬的一道）

**读这些**：
- `patch/PatchParser.java`（把 `<<<<<<< SEARCH` 文本拆成 `PatchBlock`）
- `patch/SearchReplaceStrategy.java`（**四道不变量**：路径必须在清单内、锚点必须逐字存在、必须唯一、同文件改动不许重叠）
- `exception/PatchConflictException.java`（8 种 `Kind`：越界/锚点找不到/锚点歧义/区间重叠/整文件覆盖已有文件/锚点落在不存在的文件上……）
- `patch/PatchPlan.java` + `patch/PatchApplier.java`（先算计划再落盘，顺便算 diff）
- `patch/TextDiff.java` / `patch/TextNormalizer.java`（diff 与换行/空白归一）

**读完要能回答**：
1. 模型写了一个清单外的文件会怎样？（解析期就拒，一个字节不落盘，原因回喂给它重试）
2. 为什么"越界被拒"时会提示"最接近的是 X"？（人的手误往往只差一个后缀）
3. 落盘失败（磁盘满）会不会留下半成品？（不会，快照回滚）

---

## 第 6 步：`verify` + `snapshot` —— 两个传感器与一次退路

**读这些**：
- `verify/Verifier.java`（**可插拔的缝**：将来 Test Agent 就挂这儿）
- `verify/CompileVerifier.java`（跑 `build.compile`，退出码判定，失败时把日志搬进 `.specflow/logs/`）
- `verify/CompileFailure.java`（**判"环境问题还是代码问题"**的正则表；为什么不收"找不到符号"）
- `verify/VerificationResult.java`（`Status` + `Kind` 两个维度）
- `snapshot/WorkspaceSnapshot.java`（capture / restore / discard）

**读完要能回答**：
1. 这个引擎"判断改对了没有"靠什么？（只有编译退出码——所以那三个没后缀的文件骗过去过）
2. 加一个新校验器要改哪几行？（实现接口 + 挂进 `List<Verifier>`，主循环不用动）

---

## 第 7 步：`history` —— 事后凭据

**读这些**：`history/RunRecorder.java`（把 Agent 的每个回调变成留档的一行；**按步攒那本小账**）、`history/RunRecord.java`（字段：需求、验收、目标文件、上下文、缺失项、改动 diff、**按步的 `steps`**、时间线）、`history/RunStore.java`（一文件一次运行 + `missingStats` 统计）。

**读完要能回答**：
1. 代码已经回滚了，事后靠什么复盘"它当时到底做了什么、参考了什么"？（只有这份记录）
2. 分步运行之后，"第 3 步当时为什么重试了两次"从哪儿看？（`RunRecord.Step` 里的 `rounds`/`state`/`changes`，加上时间线里带步号的那几行）
3. 老记录里没有 `steps` 字段，读出来是什么？（`null`——和 `context` 一样，缺字段不是读取失败）

---

## 第 8 步：`review` —— 检查阶段（人机确认的那一道）

**读这些**：`review/ReviewProtocol.java`（模型要输出的四块：SUMMARY / FLOW / MISSING / **STEPS**）、`review/PlanParser.java`（宽容解析，但流程图必须有；施工单那一块**一行都不许悄悄丢**）、`review/PlanReview.java`（缺失项五个字段 + 严重度 + 施工单）、`review/PlanStep.java`（一步长什么样）、**`review/PlanAudit.java`**（机器对照：方案里提到的文件 vs 目标清单）、**`review/StepAudit.java`**（机器对照：施工单能不能照着做）、`review/StepsProtocol.java`（没跑检查时那份"只产施工单"的轻协议）、`review/ReviewOutcome.java`（模型那份与机器那两份分开放）。

**读完要能回答**：
1. "拦人"的开关接在哪？（只接在机器那两份上：`PlanAudit` + `StepAudit`；模型自评的"阻断"只显示）
2. 为什么严重度不拦人？（真模型试跑里 6 条"阻断"全都自己写了默认值）
3. 施工单的三条硬拦是哪三条？为什么"中间态超过三分之一"只提示不拦？
4. `StepsProtocol` 和 `ReviewProtocol` 为什么共用同一段 `STEPS_RULES`？

---

## 第 9 步：`project` + `util` —— 配置、安全、编码

**读这些**：`project/ProjectConfigLoader.java`、`project/SnapshotConfig.java`、`project/ProjectScanner.java` / `ProjectInitializer.java`（`init` 生成了什么）、`project/RecentProjects.java`、`project/ContextLibrary.java`（上下文导出/导入），`util/SafePathResolver.java`（所有路径的必经之路）、`util/ProcessOutput.java`（**按字节认编码**：GBK/UTF-16/UTF-8）、`util/ProjectFiles.java`、`util/UserPath.java`。

**读完要能回答**：
1. 密钥放在哪、查找顺序是什么？
2. 为什么读子进程输出不能假定 UTF-8？（中文 Windows 上 javac 的报错是 GBK）

---

## 第 10 步：`web` —— 界面与服务端（最大的一块，1990 行）

**读这些（按依赖顺序）**：
1. `web/WebServer.java`：路由表 → 静态白名单 → `LocalOnly` 来源检查 → 事件轮询 `/api/events`
2. `web/OpenProject.java`：同一时刻只开一个项目、`requireOpen()`
3. `web/RunService.java`：起运行（单线程池）、推事件给 `RunHub`、检查阶段、`cancel()`
4. `web/WorkspaceApi.java` + `web/Payloads.java` + `web/RunRequest.java`：模板/草稿/上下文库的接口与请求体形状
5. `web/ProjectIndex.java` / `ProjectBrowser.java` / `FolderPicker.java` / `FolderPicking.java`：文件树、目录浏览、系统选目录（含"弹不出来"的降级）
6. 前端：`web/index.html` 分区读——欢迎页 → 左栏（搜索/文件树/新建/上下文）→ 主面板（模板、需求、验收、约束、运行、方案面板、日志、结果）→ 四个弹层；再看 `web/flowchart.js`（Mermaid 子集 + 自绘 SVG）；最后两个自检页（`flowchart-demo.html`、`style-demo.html`）。

**读完要能回答**：
1. 界面上的"执行不了"那一条是谁算出来的？（服务端 `RunService.review` 调 `PlanAudit` 与 `StepAudit`，`/api/review` 返回 `{plan, audit, stepAudit}`）
2. 事件是怎么到界面的？（400ms 轮询，不是 WebSocket）
3. 为什么静态文件是白名单？（不扫目录，避免路径拼出界）
4. 分步运行时，界面靠什么把一段乱序的日志按步分组？（事件的 `step` 字段；施工单本身由 `plan` 事件一次给全）

---

## 第 11 步：`cli` —— 另一个入口（可以跳过）

`cli/SpecflowCli.java` + `RunCommand`（退出码：0 成功 / 1 失败已回滚 / 2 模型要补信息 / 3 被中断 / 4 待处理环境）+ `InitCommand` / `ValidateCommand` / `TemplatesCommand` / `WebCommand` + `Console.java`（彩色与符号的出口，样式与 Web 共用一套语义色）。

**与 web 的关系**：两者是**并列**入口，共用同一个引擎（`DevelopmentAgent` + 同一条 `SpecValidator` 校验）。删掉 CLI 不会影响 Web。

---

## 第 12 步：测试怎么读（挑五个就够）

- `agent/DevelopmentAgentTest.java` —— 循环的每条边界：锚点失配重试、校验失败回滚、重试耗尽、被中断、环境问题早停、同错两轮停，**外加按步循环那一批**（每步施工指令、回到本步进入点重试、某步耗尽整轮回滚、中间态不阻塞、步之间能停、总轮次用尽、施工单三种来源）
- `patch/SearchReplaceStrategyTest.java` —— 四道不变量各自的反例
- `verify/CompileVerifierTest.java` —— 用 `exit 0/1` 模拟构建，验"退出码怎么解释、命令从哪来"
- `web/WebServerTest.java` —— 接口契约（状态码 + 响应体形状）+ `LocalOnly`
- `src/test/js/browser.test.js` —— 19 条链，交互与几何；`src/test/js/web.test.js` —— 前端纯逻辑（直接从真源码 eval）

**跑法**：
```powershell
& "E:\javaweb\01\apache-maven-3.9.11-bin\apache-maven-3.9.11\bin\mvn.cmd" -o test        # Java 全量
& "D:\node.exe" src\test\js\web.test.js                                                  # 前端纯逻辑
# 浏览器链需要一个"带项目"起的服务：
java -jar target\specflow.jar web --port 3081 --no-open -p .
& "D:\node.exe" src\test\js\browser.test.js http://127.0.0.1:3081/ .
```

---

## 读的时候记住这几条"护栏"（不然会误判代码在瞎写）

1. **不引依赖**是硬约束：所以流程图自己画、前端没有框架。
2. **回滚有三个落点，都在 `DevelopmentAgent` 里**（`restoreStep` 回本步进入点、`closeRun` 回运行起点、只删快照）：别在别处再补回滚，那就是回滚两遍。
3. **施工单只由检查阶段产出，或缺检查时开工前现生成一次**：运行期不许临时加步（发现要加步就是报告偏差并停下）。
4. **清单是白名单**：所有"路径越界被拒"都是这一条在起作用，不是 bug。
5. **模型看不到项目里的其他文件**：所以"它不知道表结构"是设计，不是缺陷。
6. **事件是轮询不是推送**：看到 400ms 的定时器别以为是临时方案。
7. **改前端必须重新打包 + 重启服务**：浏览器测的是服务端那份 `index.html`。
