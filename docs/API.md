# specflow v2 · API

> 后端只暴露**这些**路由。界面能调到的每一条都在这里；这里没有的，后端也没有。
> 契约测试会拿这份表与代码对账（`ApiSurfaceTest`）。

## 0. 通用形状

- 只监听 `127.0.0.1`（这个服务能读写你的项目文件，绑 `0.0.0.0` 等于让同网段的人改你的代码）。
- 请求与响应都是 JSON，UTF-8。
- **错误**:非 2xx 时正文恒为 `{"error": "<一句人话>"}`。
  409 = 现在不能这么做（闸没过 / 还有东西在跑），400 = 你发的东西不合法，500 = 引擎自己炸了。
  界面**原样显示这句话**，不许换成"操作失败"。

## 1. 外壳

```
GET    /api/state                 → {open, root, project:{name,path}, hasModel}
POST   /api/open    {path}        → {root, project:{name}, hasModel}
GET    /api/browse?path=          → {dirs:[], files:[]}      欢迎页挑目录
GET    /api/recent                → {recent:[{path,name,exists}]}
DELETE /api/recent?path=          → {recent:[]}
```

## 2. 流程（五节点）

```
GET  /api/flow                    → {flowId, cursor, nodes:[Node]}
POST /api/flow/new                → 新开一条
POST /api/flow/goto   {node}      → {invalidated:[...]}      回退：下游作废
POST /api/flow/freeze {node}      → 冻结（闸不过 → 409）
GET  /api/node/{key}/events?from= → {running, stage, events:[Event], next}
POST /api/node/cancel             → {stopped:[...]}          叫停
```

`Node`：

```json
{ "key": "env", "title": "环境搭建", "state": "READY|LOCKED|DONE",
  "blockedReason": "一句人话，空串=能做", "facts": { "envReady": "true" } }
```

`facts` 是**扁平字符串表**：界面读它画徽章（`envReady` / `compilePassed` / `caseCount`…），
值只有 `"true"` / `"false"` / 数字 / 短文本。

## 3. ① 环境

```
POST /api/node/env/agent                     → 202 {runId,cursor}   跑环境 Agent（只读）
POST /api/node/env/save  {envYaml,compileCommand} → {written, compileWritten}
GET  /api/node/env/view                      → {envYaml, compileCommand, needed}
POST /api/node/env/up                        → {ok, seconds, containers:[]}   长任务（阻塞）
POST /api/node/env/compile                   → {ok,status,command,output,seconds}  秒级
POST /api/node/env/skip                      → 声明「这个项目不需要环境」
POST /api/node/env/settings {pullTimeoutMinutes} → 改超时
GET  /api/node/env/settings                  → {pullTimeoutMinutes}
```

- `env/up` 成功时把 `envReady=true` 落进 facts；`env/compile` 把 `compilePassed` 落进去。
  **界面上的绿/红徽章读的就是这两个**，不读"历史里有没有报错"。
- `env.yaml` 的 `init`/`reset` 每一条可以是字符串（跑在 app 容器里），
  也可以是 `{command, service, stdin}`：`service` 指到某个依赖容器（`mysql` 客户端只在 `mysql` 镜像里），
  `stdin` 是**项目里**的文件（引擎读出来搬进容器），因为依赖容器没挂你的项目目录。

## 4. ② 上下文探针

```
POST /api/node/probe/brief {requirement, design}   → 写需求与设计
POST /api/node/probe/agent                          → 202 {runId,cursor}   跑探针
GET  /api/node/probe/view                           → {files,changes,context,prompt,requirement,design}
POST /api/node/probe/save {files,changes,context,prompt} → 人改产物
```

## 5. ③ 用例

```
GET  /api/node/cases            → {cases:[{id,what,tags,expect,status,evidence,note}], pending, missingTags}
POST /api/node/cases/save {cases} → 整份存回（人的增删改都走它）
POST /api/node/cases/agent      → AI 生成一版（**不落盘**，交回界面给人改）
```

## 6. ④ 开发

```
POST /api/node/dev/run          → 202 {runId,cursor}
GET  /api/node/dev/view         → {whitelist, readable, prompt, feedback, compileCommand, compilePassed}
POST /api/node/dev/rollback     → 按快照把真项目退回原文
```

## 7. ⑤ 测试

```
POST /api/node/test/run         → 202 {runId,cursor}
GET  /api/node/test/approvals   → {approvals:[{id,command,reason,askedAt}]}   高危命令等人回答
POST /api/node/test/approve {id,allow} → {answered:true}                     没有这条 → 409
POST /api/node/test/submit {summary}   → 落 summary.md；清单有 PENDING 或总结提「用例」→ 409
POST /api/node/test/accept      → 接受并完成（闭环）
POST /api/node/test/optimize    → 再优化一轮（回喂 ④）
```

## 8. 事件（`events[]` 的每一项）

```json
{ "id": 42, "type": "log|tool|result|gate", "at": "2026-10-16T15:38:44",
  "node": "env", "level": "info|warn|error", "text": "一句人话",
  "tool": "read_file", "args": "{\"path\":\"pom.xml\"}", "result": "一行摘要",
  "callId": "c7", "status": "running|ok|error", "millis": 12, "resultFull": "全文",
  "round": 2, "payload": { } }
```

- `id` **恒等于归档日志的行号**（重启后接着数）；
- `payload` 只在 `type=result` 时带整份收场载荷（界面画那一页就靠它）；
- 工具调用推两条：`status=running`（执行前）+ `status=ok|error`（执行后，带 `millis` 与 `resultFull`），
  两条共用 `callId`——界面把两条合成**一枚** chip。

## 9. 收场载荷（`type=result` 的 `payload`）

一段活跑完，**只说这几样**（界面不猜、不补算）：

```json
{ "status": "DONE", "statusLabel": "已完成", "reason": "", "nextStep": "下一步点什么",
  "rounds": 3, "modelCalls": 5, "seconds": 41,
  "calls": [{ "round":1, "tool":"read_file", "arguments":"…", "ok":true, "result":"…" }],
  "roundList": [{ "round":1, "compilePassed":true, "submits":1 }],
  "problems": [] }
```

## 10. 不做的事

- 不做旧接口兼容（`/api/drafts` `/api/tasks` `/api/chain` 一律没有）；
- 不做按阶段配模型（一份 `project.yaml` 的 `llm` 段，全链共用）；
- 不做模板库 / 上下文库（要存就存文件）；
- 不做 WebSocket 推送（界面按 `?from=` 轮询足够，且断线可重放）。
