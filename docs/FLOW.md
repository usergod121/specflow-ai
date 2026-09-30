# 一次运行的链路与拦截点

## 一句话

从填表到落盘是一条单向的链，链上的每一处拦截只有三种性质：
**代码里真的会拦**、**只是说给模型听的**、**停下来等用户点一下**。
分清这三类，才能回答「这一步到底谁说了算」。

## 一条链长什么样

填表 → 检查（可选）→ 运行 → **按施工单一步一步**：每步先给施工指令 → 模型交补丁 → 引擎验补丁 → 落盘 → 跑编译 → 过了进下一步，没过就回滚到**这一步的进入点**重来。

```
① 填表            点「运行」先过一遍必填项                    index.html 的 validateForm()
② 检查（可选）     点「先检查」，一次模型调用，同步返回          /api/review → RunService.review
③ 运行            受理后立刻返回，实际跑在后台单线程上          /api/run → RunService.start
④ 定施工单        检查给过就用；没检查就花一次调用现生成
                 （STEPS 块 → PlanParser.parseSteps → StepAudit 核一遍；
                   拿不到可用的就退化成只有一步 = 老的单步行为）
                 续跑时先用留档里那一份，但复用前要对着**当前**清单再核一遍
                 （清单变了就不开工，见下表）                       DevelopmentAgent.stepsFor
⑤ 拍快照          整个 run 一个快照，覆盖 spec.targets() 全部文件  WorkspaceSnapshot.capture
⑥ 对施工单上的每一步（DevelopmentAgent.execute 的 for）：
   记进入点        该步开始时目标文件的原文，只放内存、不落盘       DevelopmentAgent.StepCheckpoint
   给施工指令      「第 N 步做什么 / 涉及哪些文件 / 只改这些」       DevelopmentAgent.stepInstruction
   模型交补丁      LlmClient.complete
   引擎验补丁      PatchParser.parse → SearchReplaceStrategy.plan（只看内容，一个字节都不写）
   落盘            PatchApplier.apply → 跑编译 CompileVerifier
   过了 → 下一步；没过 → 回滚到本步进入点 → 回喂错误 → 本步重试（预算 max-retry）
   本步重试耗尽 / 总轮次用尽 → 整个 run 回滚到起点，失败收工
⑦ 全部走完        快照改名 .pending，等人接受或回滚           WorkspaceSnapshot.markPending
⑧ 测试阶段        只在「检查阶段给过用例清单」时才跑：一次调用生成
 （Test Agent）    tools/<时间戳>/ 下的测试代码 + 入口脚本 → 引擎只落盘
                 → 在项目根执行它 → 看退出码 → 读它打印的失败清单
                 DevelopmentAgent.testPhase → TestAgent / TestArtifacts /
                 TestScriptVerifier / TestReport
```

**每一步的起点都是「需求 + 该步开始前的代码」**：先回滚再重试，所以模型永远按那份原文写锚点。
**没有施工单时就是上面这条路的特例**——只有一步，没有步级事件和步级指令，也就是老的单步行为。
**没有用例清单时 ⑧ 一步都不走**（一个字节没变的老行为），它花的是一次模型调用而不是轮次，单独记在留档里。

## 逐项分类

| 环节 | 性质 | 真会拦吗 | 在哪实现 |
| --- | --- | --- | --- |
| 表单必填 | 硬拦 | 会。需求为空、或一个目标文件都没选，`run()` 直接返回，连请求都不发 | `index.html` 的 `validateForm()` |
| 服务端 spec 校验 | 硬拦 | 会。清单为空、清单里有空路径或重复、路径越出项目根，一律抛 `SpecValidationException`；界面和命令行共用这一套 | `SpecValidator` + `SafePathResolver.resolve`（`RunService.toValidSpec`、`RunCommand`） |
| 补丁路径必须在目标清单内 | 硬拦 | 会。清单外是 `TARGET_NOT_ALLOWED`，块里没写路径是 `MISSING_TARGET_PATH` | `SearchReplaceStrategy.resolveTarget` |
| 锚点必须逐字存在且唯一 | 硬拦 | 会。不存在是 `ANCHOR_NOT_FOUND`，匹配到多处是 `ANCHOR_AMBIGUOUS`（新建文件时 SEARCH 留空，不走这条） | `SearchReplaceStrategy.planInPlaceEdit`、`TextNormalizer` |
| 同一文件的改动不许重叠 | 硬拦 | 会。`EDIT_OVERLAP` | `SearchReplaceStrategy.assertNoOverlap` |
| 不许整文件覆盖已存在的文件 | 硬拦 | 会。`TARGET_EXISTS`；反过来，给了锚点而文件不存在是 `TARGET_MISSING` | `SearchReplaceStrategy.planNewFile` |
| 解析不出补丁块 | 硬拦 | 会。`NO_BLOCK_PARSED`，按一次补丁冲突计（这个预算是**每一步** 3 次） | `PatchParser.parse`、`DevelopmentAgent.MAX_CONFLICT_RETRIES` |
| 落盘前先快照 | 硬拦 | 会。整个 run 拍**一个**快照，覆盖目标清单全部文件——单步也拍，单步失败时回滚到运行起点靠的就是它（前提是快照没在 `project.yaml` 里关掉，关了就只剩一句警告）；**每一步内部的重试**改用内存进入点回滚，与快照开关无关 | `DevelopmentAgent.execute`、`StepCheckpoint`、`WorkspaceSnapshot.capture` |
| 编译校验 | 硬拦 | 会。退出码非 0 → 回滚到本步进入点 → 回喂 → 本步重试，预算 `max-retry`（默认 6，**每步各自算**）；没配编译命令是 `SKIPPED`，不算通过 | `CompileVerifier.run`、`VerifySpec` |
| 总轮次上限 | 硬拦 | 会。整次运行调用模型的次数上限，默认 `3 × 步数`（`verify.max-rounds` 可手写）。用尽即整轮回滚失败——没有它，一份 7 步的施工单最坏能烧掉 7 × (6 + 冲突重试) 次 | `VerifySpec.roundBudget`、`DevelopmentAgent.execute` |
| 施工单本身能不能执行 | 硬拦 | 会。步数超过 7、最后一步标了「中间态」、某一步要动清单外的文件——三条都拦；只有 1 步（这件事不用拆，按单步跑）和中间态超过总步数三分之一只提示 | `StepAudit.check`、`RunService.review` |
| 中间态那一步编译没过 | 硬拦（反向） | 不拦：施工单上写明这一步做完可能编不过，就记一笔继续下一步。但**最后一步**是中间态且编没过时整轮算失败——施工单跑完本该是一个能编译的项目 | `DevelopmentAgent`（`step.intermediate()` 分支） |
| 环境问题早停 | 硬拦 | 会。输出命中缺依赖 / JDK 级别 / 权限这类字样就判成 `ENVIRONMENT`，立刻停、回滚，不再喂给模型 | `CompileFailure.classify`、`VerificationResult.environmental()`、`AgentResult.needsEnvironment` |
| 同一个错误连着两轮 | 硬拦 | 会。**同一步内**两轮的失败指纹一样就停，不再烧轮次 | `DevelopmentAgent.signatureOf` |
| 人工中断 | 硬拦 | 会。界面上的「停止」→ `POST /api/cancel` → `RunService.cancel()`；引擎**每轮开头**和**步与步之间**各问一次 `listener.cancelled()`，为真就回滚收工（`AgentResult.CANCELLED`，CLI 退出码 3）。**不会打断正在飞的那次调用**：点完到真停还隔着一次调用——界面上的提示就是照这个写的 | `RunService.cancel` / `cancelled`、`AgentListener.cancelled()`、`DevelopmentAgent.execute` |
| 「不要改清单之外的文件」 | 纯 prompt | 字面上不拦。这一条恰好另有硬拦（`TARGET_NOT_ALLOWED`），提示词里那句只是为了让模型少白跑一轮 | `PatchProtocol.INSTRUCTIONS` 第 1、7 条 |
| 改动处标 `@requirement` 编号 | 纯 prompt | 不拦也不查。引擎不在事后改写文件，也不会因为缺这行注释拒绝落盘 | `PatchProtocol.INSTRUCTIONS` 第 9 条、`TraceSpec` |
| 检查阶段的缺失清单与三档严重度 | 纯 prompt | 引擎会解析（认不出的词算 `UNKNOWN`）、会按严重度排序，但不拿它做任何判断，界面上只显示成「模型自己觉得这 N 条要紧」 | `ReviewProtocol.INSTRUCTIONS`、`PlanReview.MissingItem.Severity`、`index.html` 的缺失块 |
| 模型声明「信息不足」就停 | 纯 prompt（说了才算） | 说不说是它的事，引擎不自己判断信息够不够；它一旦说了，引擎会认——只认响应不超过 3 行、且以 `NEED_CONTEXT:` 开头的，立刻停手（分步时连前面几步的改动一并撤回：挂起期间磁盘必须是干净的）。**但只在「还可能拿到新材料」这一档才认**：方案已确认、或用户按了「直接放行」时，这句话被当成普通回答——引擎回喂补丁协议，它要么改口给出补丁，要么在重试上限上用失败收场，而不是逼人再点一次「直接继续」 | `DevelopmentAgent.detectNeedContext` 及其调用处、`PatchProtocol.NEED_CONTEXT_PREFIX` |
| 检查回来后机器查出「方案执行不了」 | 人工确认 | 只此一处。方案（或施工单）要动的文件不在目标清单里、步数超过 7、最后一步标了中间态时，点「运行」被挡一次，点「我知道，仍然继续」才继续（一次性放行，换一份方案就失效）。施工单那三条由 `StepAudit` 判，在界面上单独占一块（`#plan .steps-audit`），同样是「我知道，仍然继续」才放行 | `RunService.review` → `PlanAudit.check` + `StepAudit.check`；`index.html` 的 `auditBlock()`、`stepsAuditBlock()` 与 `run()` |
| 续跑复用留档里那份施工单 | 硬拦（没有放行） | 会。留档里那份当初是照**当时**的清单核的，而续跑的前提就是用户改了清单。复用前拿 `StepAudit` 对着当前 `targets` 再核一遍（越界的步物理上做不了），核不过就一个字节都不动地停下（`AgentResult.PLAN_OUTDATED`，CLI 退出码 6），并说清哪一步要动哪个文件。这次拒绝**不留档**——留档会把「挂着等人补料」的那条挤下去，用户就再也接不上了。留档里没有施工单（老记录）时不核，照旧现生成一份 | `DevelopmentAgent.staleSchedule`、`RunRecorder.finished` |
| 测试产物只能写在 `tools/<时间戳>/` 里 | 硬拦 | 会。路径必须落在本次那个产物目录下：写产品代码、写别处、绝对路径、`..` 穿越一律整批拒绝（`TARGET_NOT_ALLOWED` 那种级别的错，但走的是另一份白名单——目标清单管不着它，它也不在 `spec.targets` 里，所以开发 Agent 改不动测试代码，结构上防作弊） | `TestArtifacts.write` / `shown` |
| 生成出来的脚本里的高危命令 | 硬拦 | 会。`sudo` / `rm -rf` / `dd if=` / `mkfs` / `--privileged` / 挂宿主根 / `$HOME` / `docker.sock`，以及 Windows 那一套删除命令（`del /s /q`、`rmdir /s /q`、`Remove-Item -Recurse`）命中就整批拒绝落盘、也不执行。判据**大小写无关、空白折叠**，还会把被换行拆开的命令（`^` / `\` 续行）接回去再判；「删除类命令 + 沾上产品目录、盘符、通配符、环境变量」一律拒，宁可误拒也不放过。它仍然是字面匹配，**挡不住真正的变体**（`python -c`、`mvn exec`），真正的兜底是容器隔离（还没做） | `TestArtifacts.forbidden` |
| 脚本本身起不来 | 硬拦 | 会。入口脚本不存在、进程起不来、输出读不出来 → 判 `ENVIRONMENT`：立刻停、回滚、把原始错误交给人（CLI 退出码 4）。**超时单独一档**（`TIMEOUT`）：连整棵进程树一起收掉（Windows 走 `taskkill /T`，只杀直接子进程会留下孙进程），改动**不回滚**，走「改动留着等人看」那条路 | `TestScriptVerifier.run` / `killTree`、`TestReport.classify`、`DevelopmentAgent.testPhase` |
| 测试失败的四档 | 硬拦（分档） | 会。①**断言失败**（输出里有 `FAIL \| …` 行——**先看它**，用例自己打印「连不上 / 命令不存在」不算环境问题）→ 改动留着、不自动回喂，交给人（`AgentResult.TESTS_FAILED`，CLI 退出码 7）；②**环境问题**（脚本打印 `BLOCKED`，或输出里有带报错形状的「命令不存在 / 连不上」）→ 停 + 回滚（CLI 退出码 4）；③**测试超时** → 单独一档，不回滚，同样留着改动等人看；④**测试代码问题**（非 0 退出却一行失败都没打印：编译不过、引用了不存在的 API）→ 同样留着改动，但**与「产品代码错」分开标**。判断都由引擎按输出做，不看脚本自己的说法 | `TestReport.classify`、`TestOutcome.Failure.Kind`、`DevelopmentAgent.testPhase` |
| 用例的优先级（必须过/建议过/可选） | 纯 prompt（只做显示） | 不拦。分级只影响排序与颜色，任何一档为空都照常跑完；认不出的词记「未标」 | `ReviewProtocol.CASES_RULES`、`PlanReview.TestCase.Level` |
| 用例块与失败清单 | 留档 | 引擎解析（`PlanParser.parseCases`）并原样进运行记录：`testCases` 是用例清单（通过率的分母），`tests.failures` 是哪条没过、期望什么、实际什么、哪一类失败，`tests.cases` 是**逐条用例的账**（哪几条跑了、过了没有）。脚本要逐条打 `PASS \| 编号`（过了）或 `FAIL \| 编号 \| …`（没过）；**清单上有、脚本没报的算没过**，否则「声明 3 条、一条都没跑、退出 0」会显示成全绿 | `RunRecord.testCases` / `RunRecord.tests`、`TestReport.coverage`、`RunRecorder.testsFinished` |

模型自己标的「阻断」**不拦人**。它标歪过（真模型试跑里 6 条阻断全都自己写了默认值），
所以 `severity` 只用来显示、排序和拼进提示词——`PlanReview.blocking()` 除了渲染方案文本，没有任何调用方。

## 软肋与正在补的地方

**真正硬的两道在「补丁」和「编译」上**，合起来原本只有两个传感器：补丁合不合规、编译过没过。
发生在计划和口径层面的问题，引擎原本完全看不见——比如方案要求新建一个清单外的类，
只要模型顺手把这段省掉，补丁那道不响、编译照样是绿的，最后交出来一个「少了东西但编译过」的结果。

**第三个传感器是测试脚本的退出码**（`TestScriptVerifier`）：编译过只说明语法没错，
用例过没过是另一回事。但它的结论**不是自动门槛**——机器只摆事实（哪条、期望、实际、哪一类失败），
「代码错了还是用例写错了」由人看失败清单来定。这一批还没做的是：用户确认并冻结用例、
把「确实是代码错了」的那几条一次性回喂给开发、Docker 隔离与清理。**测试代码本身对不对，工具不保证。**

**已经补了一道机器检查：`PlanAudit`。** 它把方案文本里像本项目文件的东西挑出来
（带路径的、或命名空间对得上项目已有目录的全限定类名），跟目标清单比一遍，
报出「清单外的文件改不了 / 建不了」。刻意保守：`java.util.List` 这类外部类名不报，
后缀差异不算两个文件，最多报 5 条，拿不准就不报。

**「拦人」的开关现在接在它上面。** `index.html` 的 `run()` 只看机器那一份（`auditFindings()` 取 `state.audit`），
模型自评的严重度只负责显示；`ReviewOutcome` 把这几块分开装，就是不让「模型说的事」有机会挡住用户。
`StepAudit` 的结论装在 `ReviewOutcome.stepAudit` 里（`findings` 拦人、`hints` 只提示），
界面按 `findings` 挡「运行」、把 `hints` 当提示画出来（`stepsAuditBlock()`）。

**还软的地方**：`PlanAudit` 只查文件路径这一件事，`StepAudit` 只查步数上限/最后一步/文件这三件事，
方案本身想错了（改错方法、理解偏了）依旧只有人看得出来；施工单**不解决「做得对不对」**，
终局判据仍是编译通过（`acceptance` 至今没有人校验）；没有 dry-run——界面上的「运行」是直接写盘的；
「停」也只在轮与步的边界生效，正在飞的那次调用拦不住。
另外两条明说的边界：生成出来的测试代码与入口脚本**没经过人确认就被执行**（「用户确认并冻结」还没做），
以及脚本正文里的非 ASCII 字符在 Windows 控制台可能被拆坏（提示词里叮嘱了，但引擎管不住）。

**顺带一条，不属于拦截、但决定「事后能不能复盘」**：每次运行结束写的记录里现在也存了当时的上下文
（`RunRecord.context`，超长文本留前 200 字 + 总字数），运行历史里能看到「它当时参考的是什么」。
上下文本身可以导出成一份 YAML（`.specflow/context/`）拿去给别人用，`ContextLibrary` 管读和写。
