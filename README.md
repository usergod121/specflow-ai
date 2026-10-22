<h1 align="center">specflow</h1>

<p align="center">
  <b>给 AI 一个项目，让它按五步把活干完——每一步都交出能复核的产物</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-007396" alt="Java 17" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F" alt="Spring Boot 3.5" />
  <img src="https://img.shields.io/badge/LangChain4j-1.22-0b7261" alt="LangChain4j" />
  <img src="https://img.shields.io/badge/Vue-3-42b883&logo=vuedotjs" alt="Vue 3" />
  <img src="https://img.shields.io/badge/Docker-required-2496ED&logo=docker" alt="Docker" />
  <img src="https://img.shields.io/badge/后端单测-231-4c1" alt="后端单测" />
  <img src="https://img.shields.io/badge/前端单测-11-4c1" alt="前端单测" />
</p>

---

specflow 把「给一个陌生项目改一处需求」拆成五个有闸的节点：环境、上下文、用例、开发、测试。每一步交出的产物是下一步的输入，走不过闸就退回上一步；Agent 在容器副本里改代码、按人定义的用例自验，结论与证据回写清单。做给要接手陌生项目、又想看清「AI 到底改了什么、凭什么说验过了」的人。

现状：在 Windows 11 + Docker Desktop + DeepSeek API 上完整跑通过一条链路；没有 CI、没有对外发布，代码在 `master` 分支。

## 特性

- **五节点状态机**：环境、上下文、用例、开发、测试，顺序由枚举推导，准入判据是表驱动的纯函数，服务端校验与界面提示取同一结论。
- **可复核的产物**：环境声明、可读文件与写白名单、验收清单、代码改动、结论与证据，全部落成能直接打开的文件。
- **容器沙箱隔离**：测试在项目副本 + 非特权容器里执行，不映射宿主端口，用例之间重置数据，结束回收容器与卷，宿主项目不被挂载。
- **事件流审计**：append-only 日志，事件 id 等于行号，增量读取、断点续跑与审计定位共用同一个编号。
- **可回退**：改码前对白名单文件留存快照，回滚逐字节还原并清理新建文件；崩溃后按开收场记账识别并补偿未收场的执行。
- **预算与中断**：时间、轮次、工具调用次数三重上限，到点各有明确原因；叫停按进程树整棵终止。
- **模型无关**：工具调用走文本围栏协议，不依赖 function-calling，换模型只改配置。

## 目录

[架构](#架构) · [技术栈](#技术栈) · [快速开始](#快速开始) · [环境配置](#环境配置) · [命令行](#命令行) · [数据落点](#数据落点) · [测试](#测试) · [已知限制](#已知限制) · [文档](#文档)

## 架构

<p align="center">
  <img src="docs/diagrams/architecture.svg" alt="specflow 架构图" width="96%" />
</p>

一次点按钮之后：

```mermaid
sequenceDiagram
    participant U as 用户
    participant W as Web 路由
    participant F as 流程（准入 / 运行登记）
    participant A as Agent（循环）
    participant D as 磁盘
    U->>W: POST /api/node/test/run
    W->>F: 准入判定
    alt 未满足条件
        F-->>W: 409 + 一句话
    else 放行
        F->>F: 登记一段执行，记开工
        W-->>U: 202 + 事件游标
        A->>A: 压缩 → 问模型 → 跑工具
        A->>D: 事件流 / 用例清单 / 快照
        A-->>W: 收场写结论
        W-->>U: 界面按游标增量刷新
    end
```

- 闸的判据只有一份，在 `src/main/java/com/specflow/flow/Gates.java`，界面只显示它给出的那句话，不自己判断。
- ② 的 `changes` 是 ④ 的写白名单：要改的和要新建的文件都列在这里，漏列的文件 ④ 动不了。
- ⑤ 跑在项目副本 + 容器里，验收阶段源码冻结，跑完要你点「接受」才算闭环。

## 技术栈

| 层 | 选型 | 为什么 |
| --- | --- | --- |
| 语言 | Java 17 | records 与文本块让数据类写起来很薄 |
| Web | Spring Boot 3.5，只引 starter-web | 内嵌 Tomcat 一行起；不引 JPA / Security，暴露面越小越好 |
| 模型 | LangChain4j（OpenAI 兼容） | 只用一个 `chat()` 调用，协议解析自己写更可控 |
| 前端 | Vue 3 + Vite，无组件库 | 七页不需要框架，手写 CSS 才控得住 |
| 存储 | 文件（JSONL + JSON） | 产物要人能直接打开改；事件 id 就是行号 |
| 容器 | Docker CLI | 行为可读、排错话术可控，离线仓库可用 |

## 快速开始

**前置**：JDK 17、Maven 3.9、Node 18+（编前端才需要）、Docker Desktop（能跑 Linux 容器）、一个 OpenAI 兼容的模型 key。

```bash
mvn clean package                              # 产物 target/specflow.jar，前端一起打进去
java -jar target/specflow.jar web -p fixture   # 打开 fixture 练手项目
```

浏览器打开 `http://127.0.0.1:8780`，按页面顺序点：① 探测环境 → 起环境 → 冻结 → ② 存需求 → 跑探针 → 冻结 → ③ 生成用例 → 冻结 → ④ 跑开发 → 冻结 → ⑤ 跑测试 → 提交 → 接受。

`fixture/` 是一个零依赖的 Java 小项目，故意留了一个 bug，期望输出写在 `fixture/README.md`。没有 key 也能开界面，点「跑」的时候会告诉你缺什么。

## 环境配置

项目里两份配置是给人写的：`project.yaml`（构建与模型）、`env.yaml`（环境声明）；密钥放 `local.env`。

### `.specflow/project.yaml`

```yaml
build:
  compile: "mvn -q -DskipTests compile"   # ① 的闸第一条，也是 ④ 每次交差跑的命令

llm:
  base-url: "https://api.deepseek.com"    # 任意 OpenAI 兼容端点
  model: "deepseek-chat"
  api-key-env: "SPECFLOW_API_KEY"         # 密钥从环境变量或 local.env 取
  temperature: 0.2
  timeout-seconds: 180
```

`compile` 这同一句要在宿主机和 Linux 容器里都跑，路径一律正斜杠；`llm` 只用到 chat completions 一个接口，换模型改 `base-url` 和 `model` 即可。

### `.specflow/env.yaml`

「环境长什么样」的声明，引擎按它渲染 compose。① 的探测环境会生成一份，也可以手写。

```yaml
image: "maven:3.9-eclipse-temurin-17"   # app 容器镜像，项目目录挂进去
workdir: "/work"                        # 容器内挂载点，必须以 / 开头

dependencies:                           # 要连的中间件，名字就是容器互连的服务名
  db:
    image: "mysql:8.0"
    env:
      MYSQL_ROOT_PASSWORD: "123456"     # 这些注入 db 容器自己
      MYSQL_DATABASE: "library"
    healthcheck: "mysqladmin ping -h 127.0.0.1 -uroot -p123456"   # 只写一条命令

env:                                    # 注入 app 容器，测试代码照它读
  DB_HOST: "db"
  DB_PORT: "3306"

init:                                   # 起完环境跑一次，逐条执行，任一失败整体回滚
  - "mvn -q -DskipTests compile"
  - service: "db"                       # 在 db 容器里跑（非 root 的 app 装不了 mysql 客户端）
    command: "mysql -uroot -p123456 library"
    stdin: "sql/schema.sql"             # 项目里的文件，由引擎搬进容器喂给标准输入

reset: "mvn -q flyway:clean flyway:migrate"   # ⑤ 每条用例开始前跑一次
```

| 字段 | 必填 | 作用 | 注意 |
| --- | --- | --- | --- |
| `image` | 是 | app 容器镜像 | 必须自带 `compile` 要用的构建工具，版本与项目声明一致 |
| `workdir` | 否 | 项目目录挂进容器的位置 | 默认 `/work`；别用 `/app`，那是镜像自己的目录 |
| `dependencies.<名>` | 否 | 一个中间件 | 名字同时是容器互连的主机名 |
| `dependencies.<名>.env` | 否 | 注入这个依赖容器 | 密码、初始库名写这里，不是顶层 `env` |
| `dependencies.<名>.healthcheck` | 否 | 健康检查命令 | 一条命令字符串，不写 `interval` / `timeout` / `retries` |
| `env` | 否 | 注入 app 容器的连接信息 | 值写成带引号的字符串 |
| `init` | 否 | 起环境后跑一次 | 可在依赖容器里跑（`service`），可喂项目文件（`stdin`） |
| `reset` | 否 | 每条用例前跑一次 | 把数据恢复到已知状态 |

三个容易踩的点：

- `healthcheck` 仅支持一条命令字符串（或 `{test: "..."}`）；compose 的 `interval`、`timeout`、`retries` 等字段会被忽略。
- 密码须写入 `dependencies.<名>.env`：顶层 `env` 仅注入应用容器，依赖容器无法读取。
- 不建议在 `init` 中安装系统依赖：应用容器以非特权用户运行且已去除能力，构建工具应通过镜像提供。

### 密钥

`.specflow/local.env` 写一行 `SPECFLOW_API_KEY=sk-...`，环境变量同名时环境变量优先。密钥不回显、不进日志、不进容器副本，这个文件不要提交进版本库。

## 命令行

```bash
java -jar target/specflow.jar web [--port 8780] [--no-open] [-p 项目目录]
java -jar target/specflow.jar env prepare [-p 项目目录]               # 只铺配置，不动 docker
java -jar target/specflow.jar env init    [-p 项目目录] [--timeout 分钟]
java -jar target/specflow.jar env reset   [-p 项目目录] [--timeout 分钟]
java -jar target/specflow.jar env status  [-p 项目目录]
java -jar target/specflow.jar env down    [-p 项目目录] [--purge]
java -jar target/specflow.jar version
```

`env down` 按项目名扫容器、网络、卷，⑤ 的测试沙箱也会一并收掉。服务只监听 `127.0.0.1`，别改成 `0.0.0.0`。

## 数据落点

```text
<项目>/.specflow/
  project.yaml  local.env  env.yaml
  flow/current                              当前流程 id
  flow/<id>/flow.json                       五格状态 + facts
  flow/<id>/events.jsonl                    事件流，行号就是事件 id
  flow/<id>/probe.json  cases.json  summary.md
  flow/<id>/snapshot/                       写之前的原文
  flow/<id>/runs/<runId>.jsonl              每次运行的开工/收场
  flow/<id>/env/<时间戳>/compose.yaml       渲染出来的 compose
  flow/<id>/checkpoint.json                 断点，可以删

~/.specflow/logs/engine.log                 引擎日志
~/.specflow/recent.json                     最近打开的项目
```

没有数据库。想查「刚才到底发生了什么」，直接看 `events.jsonl`。

## 测试

```bash
mvn -o test "-Dfrontend.skip=true"                              # 后端 231 例
cd src/main/frontend && npm ci && npx vitest run                # 前端 11 例
cd src/main/frontend && SPECFLOW_PLAYWRIGHT=file:///D:/tools/node_modules/playwright/index.mjs node tests/smoke.mjs   # 七页冒烟
```

冒烟测试自己起一个 jar，用真 Chrome 走七个页面，对账：后端声明的按钮有没有画出来、能点的按钮点下去有没有发请求、换项目后五格状态有没有跟着换、五格都声明了叫停且闲时不显示。端到端（真模型 + 真 Docker）没有写成脚本，手工点一遍约两百秒。

## 已知限制

- 仅在 Windows 11 环境验证，未覆盖 macOS / Linux。
- HTTP 接口无鉴权，仅绑定回环地址；不支持多用户与远程访问。
- 同一时刻仅允许一段执行，并发提交以冲突状态返回。
- 终止操作会结束已派生的进程，但不中断进行中的模型请求。
- 已验证场景为 Java / Maven 项目，其他语言需自定义编译命令。
- 模型仅验证 DeepSeek 与一个 OpenAI 兼容端点；对不规范的模型输出，引擎在同一轮内最多补问两次，仍不合规则终止。
- 无 CI 流程与覆盖率门槛，文档中的数字为手工执行所得。
- Maven 版本号仍为 2.0.0，与分支命名历史不一致。
- 暂未提供 License。

## 文档

先看 `docs/README.md`（文档地图与阅读顺序）。

| 文件 | 内容 |
| --- | --- |
| `docs/SYSTEM.html` | 系统说明书，浏览器打开，分层讲 |
| `docs/ARCHITECTURE.md` | 十二条不变量、目录落点、事件协议 |
| `docs/API.md` | 每个接口的形状、状态码 |
| `docs/UI.md` | 页面结构、状态词表 |
| `docs/DELIVERY.md` | 交付记录：验过什么、没做什么 |
| `CONTRIBUTING.md` | 构建、测试、加动作/工具/闸要动哪几处 |

## License

还没有。要商用或再分发，先开 issue 确认。
