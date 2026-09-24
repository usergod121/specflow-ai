# 一次运行的链路与拦截点

## 一句话

从填表到落盘是一条单向的链，链上的每一处拦截只有三种性质：
**代码里真的会拦**、**只是说给模型听的**、**停下来等用户点一下**。
分清这三类，才能回答「这一步到底谁说了算」。

## 一条链长什么样

填表 → 检查（可选）→ 运行 → 每一轮：模型交补丁 → 引擎验补丁 → 落盘（先快照）→ 跑编译 → 过了就完，没过就回滚重来。

```
① 填表            点「运行」先过一遍必填项                    index.html 的 validateForm()
② 检查（可选）     点「先检查」，一次模型调用，同步返回          /api/review → RunService.review
③ 运行            受理后立刻返回，实际跑在后台单线程上          /api/run → RunService.start
④ 每一轮（DevelopmentAgent.execute 的 while）：
   模型交补丁      LlmClient.complete
   引擎验补丁      PatchParser.parse → SearchReplaceStrategy.plan（只看内容，一个字节都不写）
   落盘（先快照）   WorkspaceSnapshot.capture → PatchApplier.apply
   跑编译         CompileVerifier
   过了 → 收工；没过 → snapshot.restore() 回滚 → 把错误回喂 → 下一轮
```

每一轮的起点都是「需求 + 原始代码」：先回滚再重试，所以模型永远按原文件内容写锚点。

## 逐项分类

| 环节 | 性质 | 真会拦吗 | 在哪实现 |
| --- | --- | --- | --- |
| 表单必填 | 硬拦 | 会。需求为空、或一个目标文件都没选，`run()` 直接返回，连请求都不发 | `index.html` 的 `validateForm()` |
| 服务端 spec 校验 | 硬拦 | 会。清单为空、清单里有空路径或重复、路径越出项目根，一律抛 `SpecValidationException`；界面和命令行共用这一套 | `SpecValidator` + `SafePathResolver.resolve`（`RunService.toValidSpec`、`RunCommand`） |
| 补丁路径必须在目标清单内 | 硬拦 | 会。清单外是 `TARGET_NOT_ALLOWED`，块里没写路径是 `MISSING_TARGET_PATH` | `SearchReplaceStrategy.resolveTarget` |
| 锚点必须逐字存在且唯一 | 硬拦 | 会。不存在是 `ANCHOR_NOT_FOUND`，匹配到多处是 `ANCHOR_AMBIGUOUS`（新建文件时 SEARCH 留空，不走这条） | `SearchReplaceStrategy.planInPlaceEdit`、`TextNormalizer` |
| 同一文件的改动不许重叠 | 硬拦 | 会。`EDIT_OVERLAP` | `SearchReplaceStrategy.assertNoOverlap` |
| 不许整文件覆盖已存在的文件 | 硬拦 | 会。`TARGET_EXISTS`；反过来，给了锚点而文件不存在是 `TARGET_MISSING` | `SearchReplaceStrategy.planNewFile` |
| 解析不出补丁块 | 硬拦 | 会。`NO_BLOCK_PARSED`，按一次补丁冲突计（这个预算是 2 次） | `PatchParser.parse`、`DevelopmentAgent.MAX_CONFLICT_RETRIES` |
| 落盘前先快照 | 硬拦 | 会，前提是快照没在 `project.yaml` 里关掉；关了就没有回滚，只剩一句警告 | `DevelopmentAgent.applyAndVerify`、`WorkspaceSnapshot.capture` |
| 编译校验 | 硬拦 | 会。退出码非 0 → 回滚 → 回喂 → 下一轮，预算 `max-retry`（默认 6）；没配编译命令是 `SKIPPED`，不算通过 | `CompileVerifier.run`、`VerifySpec` |
| 环境问题早停 | 硬拦 | 会。输出命中缺依赖 / JDK 级别 / 权限这类字样就判成 `ENVIRONMENT`，立刻停、回滚，不再喂给模型 | `CompileFailure.classify`、`VerificationResult.environmental()`、`AgentResult.needsEnvironment` |
| 同一个错误连着两轮 | 硬拦 | 会。两轮的失败指纹一样就停，不再烧轮次 | `DevelopmentAgent.signatureOf` |
| 人工中断 | 硬拦（只是个钩子） | 引擎每轮开头问一次 `listener.cancelled()`，为真就回滚收工（`AgentResult.CANCELLED`，CLI 退出码 3）。但**当前没有任何实现把它置真**：界面没有停止按钮，命令行是自己 Ctrl-C 杀进程，靠磁盘上的快照兜底 | `AgentListener.cancelled()`、`DevelopmentAgent.execute` 开头 |
| 「不要改清单之外的文件」 | 纯 prompt | 字面上不拦。这一条恰好另有硬拦（`TARGET_NOT_ALLOWED`），提示词里那句只是为了让模型少白跑一轮 | `PatchProtocol.INSTRUCTIONS` 第 1、7 条 |
| 改动处标 `@requirement` 编号 | 纯 prompt | 不拦也不查。引擎不在事后改写文件，也不会因为缺这行注释拒绝落盘 | `PatchProtocol.INSTRUCTIONS` 第 9 条、`TraceSpec` |
| 检查阶段的缺失清单与三档严重度 | 纯 prompt | 引擎会解析（认不出的词算 `UNKNOWN`）、会按严重度排序，但不拿它做任何判断，界面上只显示成「模型自己觉得这 N 条要紧」 | `ReviewProtocol.INSTRUCTIONS`、`PlanReview.MissingItem.Severity`、`index.html` 的缺失块 |
| 模型声明「信息不足」就停 | 纯 prompt（说了才算） | 说不说是它的事，引擎不自己判断信息够不够；但它一旦说了，引擎会认——只认响应不超过 3 行、且以 `NEED_CONTEXT:` 开头的，立刻停手、不动磁盘 | `DevelopmentAgent.detectNeedContext`、`PatchProtocol.NEED_CONTEXT_PREFIX` |
| 检查回来后机器查出「方案执行不了」 | 人工确认 | 只此一处。方案要动的文件不在目标清单里时，点「运行」被挡一次，点「我知道，仍然继续」才继续（一次性放行，换一份方案就失效） | `RunService.review` → `PlanAudit.check`；`index.html` 的 `auditBlock()` 与 `run()` |

模型自己标的「阻断」**不拦人**。它标歪过（真模型试跑里 6 条阻断全都自己写了默认值），
所以 `severity` 只用来显示、排序和拼进提示词——`PlanReview.blocking()` 除了渲染方案文本，没有任何调用方。

## 软肋与正在补的地方

**真正硬的两道在「补丁」和「编译」上**，合起来只有两个传感器：补丁合不合规、编译过没过。
发生在计划和口径层面的问题，引擎原本完全看不见——比如方案要求新建一个清单外的类，
只要模型顺手把这段省掉，补丁那道不响、编译照样是绿的，最后交出来一个「少了东西但编译过」的结果。

**已经补了一道机器检查：`PlanAudit`。** 它把方案文本里像本项目文件的东西挑出来
（带路径的、或命名空间对得上项目已有目录的全限定类名），跟目标清单比一遍，
报出「清单外的文件改不了 / 建不了」。刻意保守：`java.util.List` 这类外部类名不报，
后缀差异不算两个文件，最多报 5 条，拿不准就不报。

**「拦人」的开关现在接在它上面。** `index.html` 的 `run()` 只看机器那一份（`auditFindings()` 取 `state.audit`），
模型自评的严重度只负责显示；`ReviewOutcome` 把这两块分开装，就是不让「模型说的事」有机会挡住用户。

**还软的地方**：`PlanAudit` 只查文件路径这一件事，方案本身想错了（改错方法、理解偏了）依旧只有人看得出来；
没有 dry-run——界面上的「运行」是直接写盘的。
