# 贡献指南

先看 `docs/README.md` 了解文档结构，这篇只讲怎么改代码。

## 本地跑起来

```bash
mvn clean package
java -jar target/specflow.jar web -p fixture
```

打开 `http://127.0.0.1:8780`。fixture 是个零依赖的 Java 小项目，跑一遍就能看到五个节点各自产出什么。

改前端想要保存即刷新，两个终端：

```bash
# 终端 1：引擎
java -jar target/specflow.jar web --no-open -p fixture

# 终端 2：Vite，只代理 /api 到 8780
cd src/main/frontend && npm run dev
```

Vite 起在 5173，`/api` 转发到 `127.0.0.1:8780`。后端端口不是 8780 的话，起 Vite 前设 `SPECFLOW_PORT`。

## 测试

```bash
mvn -o test "-Dfrontend.skip=true"                    # 后端 231 条，大约 40 秒
cd src/main/frontend && npx vitest run                # 前端 11 条
```

浏览器冒烟要 Chrome 和 playwright，playwright 不是项目依赖：

```bash
cd src/main/frontend
SPECFLOW_PLAYWRIGHT=file:///D:/tools/node_modules/playwright/index.mjs node tests/smoke.mjs
```

没有 CI，测试得自己跑。改完不跑就别提。

## 改动的判断顺序

改之前先问三句，答案会决定你要动几个文件：

1. 这个动作的**输入**谁给？没人给的话它永远走默认值。
2. 这个动作的**输出**谁用？没人用的话就是白算。
3. 它的**调用点**有几个？只有测试在调 = 生产没接上。

这三句对应 `docs/ARCHITECTURE.md` 的第 12 条不变量，是踩坑之后补的。

## 常见改动

### 加一个按钮（动作）

按钮的声明只有一处，在 `src/main/java/com/specflow/flow/Actions.java`。加一个动作要动四处：

1. `Actions.TABLE` 里对应那格加一条 `ActionSpec`，想清楚进页头还是进「⋯」、是不是危险色。
2. `web/Api.java` 加路由，路由里只做三件事：取参数、调下层、拼响应。判据不许写在这一层。
3. 前端对应视图（`src/main/frontend/src/views/`）的 `handlers` 里按 id 绑处理函数。没绑的会渲染成灰的并说明原因，不会静默消失。
4. 测试：`flow/ActionsTest` 看声明，`web/ApiTest` 看响应里带没带，`tests/smoke.mjs` 看浏览器里画没画出来。

页头最多两枚（一主一次），其余进「⋯」。`onlyWhileRunning` 的动作用在「叫停」这类只在有活时该出现的按钮上，界面会在跑起来的时候把它插队到页头最前面。

### 加一个工具

工具是 `agent/Tool` 的实现，放在 `agent/tools/`。写完后要有人把它装进 `ToolRegistry`，位置在 `agent/Dev.java`、`agent/Probe.java`、`test/TestAgent.java` 各自的 `ToolRegistry.of(...)`。

两个注意点。一是写盘类工具必须包一层 `Whitelist`，白名单就是 ② 交出来的 `changes`，不然 Agent 能改任何文件。二是想清楚「这个工具坏了会怎样」：注册表把异常翻成 `ok=false` 喂回模型，不抛、不崩循环，所以错误文案要能让模型自己纠正。

给工具加壳的顺序是 `CaseBudget` → `StuckCheck` → `Whitelist`，别调换。

### 改一条闸

闸在 `flow/Gates.java`，判据是纯函数，输入是 `Facts`。改之前先看 `docs/ARCHITECTURE.md` 第 5 条不变量：闸只有一处判据，界面一行判断都不许有。

要点：

- 事实名写成 `flow/Facts` 里的常量，写的人和读的人用同一个。写过一次 `envConfirmed` 读 `envReady`，闸永远看不见，① 卡死。
- 拒绝的说法要能让人知道下一步干什么。`GatesTest` 里有话术断言，改文案会红，这是故意的。
- 加完跑 `flow/GatesTest` 和 `ApiTest`，后者看 409 的正文有没有跟着变。

### 加一页或改前端

页面在 `src/main/frontend/src/views/`，共用页头在 `components/PageHead.vue`。状态只有一个口：

- `store.refresh()` 读 `/api/flow`，五格状态、闸那句话、动作表、`running` 都在里面；
- 所有动作走 `store.act()`，成功和 409 之后都会再 refresh 一次；
- 换项目是 `store.openProject()`，它会把上一套状态全清掉。

界面上不许出现后端已经知道的东西：按钮文案、禁用条件、闸的判据都从后端来。视图里再写一遍就是第四份真相。

### 改接口

`web/Api.java` 加或改方法，同时要改：`docs/API.md`（形状）、`src/main/frontend/src/api.js`（调用口）、`web/ApiTest`（断言）。错误一律 `throw ApiException` 或 `FlowRejected`，由 `web/Errors.java` 翻成 `{"error":"一句话"}`，前端照原话显示。

## 代码约定

- 注释写「为什么这么写」，不写「这行做了什么」。每条反直觉的写法上面都该有一句原因，原因是当时的故障。
- 落盘走 `core/Atomic`（临时文件 + rename）和 `flow/Snapshot`，不要直接 `Files.write`。
- 路径一律问 `core/Layout`，别自己拼字符串。
- 长任务的进度只从 `core/Progress` 出去，写事件日志，别另开一条日志文件。
- 数据类用 record。新增第三方依赖前先说明为什么 JDK 自带的东西不够用。
- 界面上解释性文字不超过 6 个汉字，长的进 `title`。状态词只有 `未开始 / 待完成 / 已完成 / 失败` 四档。

## 提交前

- 后端和前端测试都跑过。
- 改了交互或按钮，跑一遍 `tests/smoke.mjs`。
- 改了接口、闸、动作表、测试数量，回去看 `README.md` 和 `docs/` 里对应的数字改没改。文档里的数字对不上代码，就说明文档过期了。
- 提交信息用中文，`fix:` / `feat:` / `docs:` 开头，正文写清「用户看到什么、根因是什么、修法是什么」。

## 不要提交

`*.bat`、`tools/`、`TASKS.md`、`PRODUCT.md`、任何 `.specflow/local.env`。前四样是本机的东西，最后一样里有密钥。
`.gitignore` 已经挡了，别用 `git add -f` 绕过去。
