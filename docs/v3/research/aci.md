# SWE-agent / Aider · 深读（ACI）

> 全部结论基于**真源码**（`main` 分支 tarball 解包，2026-10 快照）。证据按 `文件:行` 给在第 9 节。
> 目标读者：specflow（Java 17 / Spring Boot / ReAct + `read_list`·`read_file`·`write_file`·`apply_patch`·`bash`·`case_update`·`submit`）。
>
> 一句话结论：**SWE-agent 最值钱的东西不是工具定义，是「历史处理器」和「状态行」；行号视图值得抄但只抄一半；后台任务是上游的空白，抄不到，必须自己写。**

---

## 1. 工具用 YAML 声明：形状、好处、代价（贴一段真实 yaml）

### 1.1 先纠正一个预期

`main` 分支里 **`sweagent/tools/` 下没有任何 yaml**（`sweagent/tools/` 只有 `bundle.py`/`commands.py`/`parsing.py`/`tools.py`/`utils.py`/`__init__.py`）。老的 `registry.py` + `toolconfig` 那套已经被重构掉了：现在的 `sweagent/tools/registry.py` 不存在了，`registry.py` 变成了**容器侧的 `EnvRegistry`**（一个 JSON 状态文件），而不是工具注册表。

工具声明挪到了仓库根目录的 **`tools/` 目录**，每个工具是一个 **bundle 目录**：

```
tools/windowed/
├── bin/            ← 真正的实现，是「可执行文件」（Python 脚本 + shebang）
│   ├── _state      ← 特殊：状态命令
│   ├── create
│   ├── goto
│   ├── open
│   ├── scroll_up
│   └── scroll_down
├── config.yaml     ← 声明：签名 / docstring / 参数
├── install.sh
└── lib/windowed_file.py   ← 共享库
```

（`docs/config/tools.md:17-30` 明确给出这个结构。）

### 1.2 声明长什么样（`tools/windowed/config.yaml`，全文 38 行，实贴核心）

```yaml
tools:
  open:
    signature: 'open "<path>" [<line_number>]'
    docstring: "opens the file at the given path in the editor. If line_number is provided, the window will be move to include that line"
    arguments:
      - name: path
        type: string
        description: "the path to the file to open"
        required: true
      - name: line_number
        type: integer
        description: "the line number to move the window to (if not provided, the window will start at the top of the file)"
        required: false
  scroll_down:
    signature: "scroll_down"
    docstring: "moves the window down {WINDOW} lines"
    arguments: []
state_command: "_state"
```

更复杂的那个（`tools/edit_anthropic/config.yaml`）展示了另外两个字段：`enum` 和 `argument_format`：

```yaml
  str_replace_editor:
    signature: |
      str_replace_editor <command> <path> [<file_text>] [<view_range>] [<old_str>] [<new_str>] [<insert_line>]
    docstring: >
      Custom editing tool for viewing, creating and editing files
      * If `path` is a file, `view` displays the result of applying `cat -n`. ...
    arguments:
      - name: command
        type: string
        description: "The commands to run. Allowed options are: `view`, `create`, `str_replace`, `insert`, `undo_edit`."
        required: true
        enum: ["view", "create", "str_replace", "insert", "undo_edit"]
      - name: old_str
        type: string
        description: "Required parameter of `str_replace` command containing the string in `path` to replace."
        required: false
        argument_format: "--old_str {{value}}"
      - name: view_range
        type: array
        items:
          type: integer
        required: false
        argument_format: "--view_range {{value|join(' ')}}"
state_command: "_state_anthropic"
```

字段全集（`sweagent/tools/commands.py:52-100`）：

| 字段 | 作用 |
|---|---|
| `name` | 工具名（yaml 的 key） |
| `signature` | 给模型看的调用形状，`<x>` 必填、`[<x>]` 可选。**省略则自动生成** `name <a1> [<a2>]`（`utils.py:46-72`） |
| `docstring` | 给模型看的说明；可用 `{WINDOW}` 之类占位符被 `env_variables` 填充（`utils.py:96`） |
| `arguments[]` | `name`/`type`/`description`/`required`/`enum`/`items`/`argument_format` |
| `argument_format` | jinja，把结构化参数渲染成 **CLI flag**，如 `--old_str {{value}}` |
| `end_name` | 有则视为**多行命令**，用 heredoc 收尾（`tools.py:411-421`） |
| `state_command` | bundle 级：每次动作后执行，输出 JSON 合并进模板变量 |

### 1.3 一个 yaml 如何变成「模型看得到的东西」

三层，全自动，**单一事实来源**：

1. `Bundle`（`bundle.py:34-35`）读 `config.yaml` → `BundleConfig` → `list[Command]`（`bundle.py:52-57`）。
2. `ToolConfig.model_post_init` 调 `generate_command_docs(...)`（`tools.py:214-218`）生成 **给模型看的纯文本文档**（`utils.py:75-108`，格式是 `name:` / `  docstring:` / `  signature:` / `  arguments:` 的缩进块）。
3. 同一批 `Command` 也喂给 `get_function_calling_tool()`（`commands.py:133-165`）生成 **OpenAI function schema**。

所以同一个 yaml 同时驱动：提示词里的工具清单、function-calling 的 JSON schema、以及参数校验。**三者不可能漂移。**

顺带一个很硬的设计：`Command` 的 pydantic validator 强制 **`signature` 里出现的占位符集合 == `arguments` 名字集合**，否则直接启动失败（`commands.py:202-204`）；并且**必填参数不能排在可选参数之后**（`commands.py:186-191`）。

### 1.4 好处 / 代价

**好处**

- **单一事实来源**：文档、schema、校验同源。这是最有价值的一点。
- 工具实现是**容器里的普通可执行文件**，与 Python 主进程零耦合；模型就是「在 shell 里敲命令」。所以 `open` 在 agent 侧和执行侧完全是同一个东西，调试时可以直接手敲。
- 换 ACI 只换 bundle 列表（`default.yaml` 里 `bundles: [registry, edit_anthropic, review_on_submit_m]`）——论文里的 ACI 消融实验就是这么做的。
- `hidden_tools` 可以在不删文件的情况下对模型隐藏某个工具（`bundle.py:19,56`）。

**代价（很重要，别忽略）**

- **状态必须外置**。每个工具调用是独立进程，进程间无共享内存，所以搞了个 `EnvRegistry`：一个 `/root/.swe-agent-env` JSON 文件，键值读写（`tools/registry/lib/registry.py:7-53`）。"当前打开的文件 / 窗口第一行"就存在这里。文件里自述动机：*"so we can persist state between tool calls without using environment variables (which are problematic because you cannot set them in a subprocess)"*。
- **每次调用 = 一次进程 spawn**（`open`、`goto`、`scroll_down` 都是独立脚本）。在我们 Java 单体里这层开销纯属自找。
- 三层间接：yaml → Command → 渲染文本。改一个工具的提示词要跨文件看。
- 工具失败只能用 **exit code + stdout 文本**表达，结构化错误要自己约定（他们靠 `print` 和 `exit(1)`）。

### 1.5 对我们的判定

**不抄 bundle 架构**（我们是 Java 手写工具，一个类一个工具，本来就是单一来源）。**只抄两条微观约定**：

1. 工具描述从**注解/反射**生成，别在手写 prompt 里再抄一遍签名——我们完全可以用同一个 `@Tool` 注解同时产出「给模型的文档」和「给 JSON schema 的字段」，等价于他们的 `generate_command_docs` + `get_function_calling_tool` 双出。**收益：永远不会出现"提示词说 3 个参数、实现要 4 个"的漂移。代价：约 60 行 Java 反射代码。**
2. 抄 `argument_format` 这个"把结构化参数压成一行 CLI"的思路：我们的 `apply_patch` 现在如果是多行字符串参数，模型很容易写坏引号；SWE-agent 用 `end_name` + heredoc 解决（见 §2.4）。

---

## 2. 查看/编辑原语的设计（行号视图为什么省 token，贴实现）

SWE-agent 有**两套**查看/编辑原语，都在 `tools/` 下，别搞混：

| bundle | 视图 | 编辑 | 状态键 |
|---|---|---|---|
| `tools/windowed/` | 固定 **100 行窗口**，`open/goto/scroll_up/scroll_down` | 窗口内 search/replace、`insert`、按**行号区间重写** | 有 `_state`，吐 `open_file` |
| `tools/edit_anthropic/` | `cat -n` 全文（截断 16000 字符），可选 `view_range` | `str_replace` / `insert` / `create` / `undo_edit` | 有 `_state_anthropic`，只吐 `working_dir` |

`config/default.yaml:41-44` 用的是 **`edit_anthropic` + `review_on_submit_m`**，也就是说**今天 SWE-agent 的默认 ACI 是「行号全文视图 + search/replace」，不是 100 行窗口**。100 行窗口是 0.7 时代的主线（`config/exotic/windowed_replace.yaml`）。

### 2.1 行号视图的实现（`edit_anthropic/bin/str_replace_editor`）

编号格式就一行：

```python
# str_replace_editor:674-686
def _make_output(self, file_content, file_descriptor, init_line=1, expand_tabs=True):
    file_content = maybe_truncate(file_content)          # ← 先截断，再编号
    if expand_tabs:
        file_content = file_content.expandtabs()
    file_content = "\n".join(["{:6}\t{}".format(i + init_line, line)
                              for i, line in enumerate(file_content.split("\n"))])
    return "Here's the result of running `cat -n` on {}:\n".format(file_descriptor) + file_content + "\n"
```

截断常量与截断提示（**注意提示语本身就在教模型下一步怎么做**）：

```python
# str_replace_editor:27-28
TRUNCATED_MESSAGE = "<response clipped><NOTE>To save on context only part of this file has been shown to you. You should retry this tool after you have searched inside the file with `grep -n` in order to find the line numbers of what you are looking for.</NOTE>"
MAX_RESPONSE_LEN = 16000

# str_replace_editor:57-63
def maybe_truncate(content, truncate_after=MAX_RESPONSE_LEN):
    return content if not truncate_after or len(content) <= truncate_after \
        else content[:truncate_after] + TRUNCATED_MESSAGE
```

### 2.2 「行号省 token」——这条要拆开说，否则会抄错

**行号本身不省 token，行号是花 token 的。** `"{:6}\t"` 每行约 7 字符，100 行 = 700 字符 ≈ 175 token。真省 token 的是另外三件事：

1. **只显示一个窗口**：100 行 vs 整个文件。`windowed_file.py` 的窗口语义写得很清楚：`line_range[1] - line_range[0] == window-1`（`windowed_file.py:141-148`）。论文的 ACI 文档原话：*"this file viewer works best when displaying just 100 lines in each turn"*（`docs/background/aci.md:12`）。
2. **200 行以上的文件整篇不给你看**：`MAX_RESPONSE_LEN = 16000` 硬截断 + 一句"去用 grep -n 定位"。
3. **编辑后只回显 ±4 行**，不是整个文件（`SNIPPET_LINES = 4`，`str_replace_editor:35,581-590`）。

**行号真正买到的是「编辑定位精度」**，不是省 token。因为有了行号，编辑可以：
- 用 `view_range` 精确取一段（不用读全文）；
- 用 `edit <start>:<end>` 按**区间重写**（`windowed_edit_replace/config.yaml:3-4`）；
- 报错时能说"第 47 行"。

反过来，`str_replace` 路线（不用行号编辑）买到的是**抗漂移**：`old_str` 必须唯一匹配，不唯一就拒绝（`edit_anthropic/config.yaml:17`）。两条路线的取舍是：

| | 行号区间重写 (windowed) | search/replace + `cat -n` 视图 (anthropic) |
|---|---|---|
| 视图成本 | 低（100 行窗口） | 高（全文，16000 字符封顶） |
| 编辑安全 | 模型给错区号就毁一段 | 文本不唯一就拒绝，天然幂等 |
| 模型负担 | 要自己数行 | 要复制原文（容易漏空格） |
| 上游现状 | 非默认 | **默认** |

### 2.3 状态行：这是针对「猜路径」的正面设计 ★

`windowed_file.py` 把「当前文件 / 窗口首行」写进 registry 文件：

```python
# windowed_file.py:100-111
registry["CURRENT_FILE"] = str(self.path.resolve())
...
registry["FIRST_LINE"] = self.first_line
```

然后 `_state` 把状态吐成 JSON，`_state_anthropic` 同理（`_state_anthropic:15` `state["working_dir"] = os.getcwd()`），**每次动作后都会执行**（`tools.py:337-348` `get_state()`）。

这个 JSON 直接进模板变量，于是 `config/sweagent_0_7/07.yaml:68-72` 写成了：

```yaml
    next_step_template: |-
      {{observation}}
      (Open file: {{open_file}})
      (Current directory: {{working_dir}})
      bash-$
    next_step_no_output_template: |-
      Your command ran successfully and did not produce any output.
      (Open file: {{open_file}})
      (Current directory: {{working_dir}})
```

**这就是 specflow 问题 #1（模型猜路径浪费 5 轮）的标准答案**：不靠提示词解释「宿主路径 vs 容器路径」，而是**每一条 observation 后面钉一行"你现在在哪个目录、开着哪个文件"**。模型不需要推理，读就行。成本约 40 token/轮。**这条必须抄，而且是我们所有收益里性价比最高的。**

注意 `_state` 在 `windowed` 里是 `cwd + open_file`，在 `edit_anthropic` 里只有 `cwd`（`_state_anthropic:15-17`）——说明连上游自己也认为 `open_file` 是可选增强。

### 2.4 编辑的「事务性」：lint gate ★

`tools/windowed_edit_linting/bin/edit` 是整个 ACI 里最优雅的一段：**先测、再改、再测、坏了就回滚**。

```python
# windowed_edit_linting/bin/edit:94-119
    # Get pre-edit linting errors
    pre_edit_lint = flake8(wf.path)

    # Perform the edit
    wf.set_window_text(replacement_text, line_range=(start_line, end_line))

    # Check for new linting errors
    post_edit_lint = flake8(wf.path)
    new_flake8_output = format_flake8_output(
        post_edit_lint,
        previous_errors_string=pre_edit_lint,          # ← 只报「新引入的」错误
        replacement_window=(start_line, end_line),
        replacement_n_lines=len(replacement_text.splitlines()),
    )

    if new_flake8_output:
        with_edits = wf.get_window_text(line_numbers=True, status_line=True, pre_post_line=True)
        wf.undo_edit()                                  # ← 回滚
        without_edits = wf.get_window_text(...)
        print(_LINT_ERROR_TEMPLATE.format(errors=new_flake8_output,
                                          window_applied=with_edits,
                                          window_original=without_edits))
        exit(1)
```

配套的报错模板（`windowed_edit_linting/bin/edit:27-43`）明确写：

> `Your proposed edit has introduced new syntax error(s). ...`
> `Your changes have NOT been applied. Please fix your edit command and try again.`
> `DO NOT re-run the same failed edit command. Running it again will lead to the same error.`

三个设计要点：
- **diff-based lint**：只报这次编辑**新引入**的错误（`pre_edit_lint` 做基线），原先就有的噪声不报。这对我们（老的 Java 项目一堆历史 warning）是刚需。
- **失败即回滚**，并且给模型看「应用后会变什么样」和「原来的样子」的对照。
- 显式禁止重试同一条命令——正面治疗「7 轮白转」那类死循环。

`apply_patch` 在我们的场景等价物是「改完编译一下」。这个 lint gate 值得抄，但要注意代价：每次编辑多一次 `javac`/`mvn -q compile`（秒级）。可以退化成"只对 `.java` 文件做、且只在 `apply_patch` 后做"。

---

## 3. history_processors 清单（逐个：名字/触发条件/压掉什么/保留什么）★ 最重要

### 3.0 先讲架构：这是最该抄的一点

`history_processors` 是**在每次向模型发请求时，对一份拷出来的历史跑一遍纯函数链**，原始历史一个字都不动：

```python
# sweagent/agent/agents.py:539-551
@property
def messages(self) -> list[dict[str, Any]]:
    """Return the history of the agent for this attempt since the last reset,
    processed through all history processors.
    """
    filtered_history = [entry for entry in self.history if entry["agent"] == self.name]
    # Chain the history processors
    messages = filtered_history
    for processor in self.history_processors:
        messages = processor(messages)
    return messages
```

三个可直接照搬的性质：

1. **压缩是可逆的**。`self.history` 永远是全量，落盘的 `.traj` 是全量，replay/评测看到的是全量。只有"喂给模型的那一份"被压。**我们在 specflow 里可以照抄这个分层：`case_update` 存的 history 不动，`buildMessages()` 出口处压。**
2. **可组合**，按 yaml 列表顺序执行，每个都是 `History -> History`。
3. **无状态、无阈值**——见下表，SWE-agent 的处理器**没有一个是按 token 数触发的**。

### 3.1 逐个（共 7 个，`sweagent/agent/history_processors.py`）

#### ① `DefaultHistoryProcessor` — `type: default`（`history_processors.py:74-82`）
- **触发**：永远是它（默认值，`agents.py:155`）。
- **压掉**：无，恒等函数。
- **保留**：全部。
- 用途：占位，让 config 不写 `history_processors` 时也能有非空列表。

#### ② `LastNObservations` — `type: last_n_observations` ★ 最经典（`history_processors.py:85-176`）
- **触发**：**每次查询，无条件**。不是"超 N token 才触发"，而是**恒久只保留最后 n 条 observation**。
- **压掉**：除最后 `n` 条之外的所有 `message_type == "observation"` 的条目。
- **保留**：
  - 最后 `n` 条 observation；
  - **第一条 observation 永不删**（`history_processors.py:154-155`，注释：`We never remove the first observation, as it is the instance template`）；
  - 所有 action（assistant 侧）不删，**只删 observation**；
  - 打了 `keep_output` tag 的 observation 强制保留；
  - 打了 `remove_output` tag 的即使在前 n 条里也删掉。
- **压成什么**：一行占位符 `Old environment output: (N lines omitted)`（`history_processors.py:171-175`）。

三个精妙细节：

```python
# history_processors.py:147-155
def _get_omit_indices(self, history: History) -> list[int]:
    observation_indices = [idx for idx, entry in enumerate(history)
                           if entry.get("message_type") == "observation" and not entry.get("is_demo", False)]
    last_removed_idx = max(0, (len(observation_indices) // self.polling) * self.polling - self.n)
    return observation_indices[1:last_removed_idx]
```
- `[1:]` = 跳过第一条（任务模板）。
- `polling`（默认 1）：**为了让前缀稳定**。如果每轮都往前推一条，prompt 前缀就每轮都变，Anthropic 的 prompt cache 全废。`polling: 5` 表示"每 5 步才把窗口往前跳一次"，此时实际保留 `n ~ n+polling` 条。`history_processors.py:106-108` 明说：*"Note that using this history processor will break prompt caching ... There are some workarounds possible with the `polling` parameter."*

```python
# history_processors.py:124-132
always_remove_output_for_tags: set[str] = {"remove_output"}
always_keep_output_for_tags: set[str] = {"keep_output"}
```
- **tag 机制是逃生舱**：`last_n` 是粗暴的"按时间截断"，tag 让它变成"按语义截断"。谁打 tag？见 ③。

**上游实际怎么用（我扫了所有 config）：**

| 配置 | 用的处理器 |
|---|---|
| `config/sweagent_0_7/07*.yaml`、`config/coding_challenge.yaml`、`config/human/human_demo.yaml`、`config/exotic/windowed_replace*.yaml` | `last_n_observations, n: 5` |
| `config/default.yaml`、所有 `config/benchmarks/*.yaml`（2025 年的） | **只有 `cache_control`** |

**这是个很强的信号**：`last_n_observations` 是**小上下文时代**的救命稻草。上下文涨到 200k 之后，上游主力配置**不再压缩历史了**，只做缓存。而 `history_processors.py:110-111` 的注释也承认：*"However, most SotA models can now fit a lot of context, so generally this history processor is not always needed anymore."*

对我们的启示：**`n=5` 这个数字别照抄**（太激进，会把 mid-task 的关键上下文切掉），但**"只压 observation、不压 action"和"第一条永不删"这两条规则必须抄**。

#### ③ `TagToolCallObservations` — `type: tag_tool_call_observations`（`history_processors.py:179-212`）
- **触发**：每次查询，无条件。但要**排在 ② 之前**。
- **压掉**：什么也不压，**只打 tag**。
- **保留**：全部。
- **干什么**：给「由指定工具产生的观察」打上 `keep_output` tag，让 ② 绕过它们。

```python
# history_processors.py:199-206
def _should_add_tags(self, entry: HistoryItem) -> bool:
    if entry.get("message_type") != "action":
        return False
    function_calls = entry.get("tool_calls", [])
    ...
    function_names = {call["function"]["name"] for call in function_calls}
    return bool(self.function_names & function_names)
```

- 这是**唯一一个把"语义重要性"注入压缩逻辑的机制**，而且实现只有 20 行。**这是我们最该抄的一个**：`case_update` 的返回、`submit` 的校验结果，都应该被钉住。

#### ④ `ClosedWindowHistoryProcessor` — `type: closed_window`（`history_processors.py:215-258`）
- **触发**：每次查询，无条件。
- **压掉**：**同一个文件的所有旧窗口**，只保留最近一次看到的那个窗口。
- **保留**：每个文件最新的一次 `open` 输出（原样），以及所有非 user 角色、所有 demo。
- **压成什么**：`Outdated window with N lines omitted...\n`（`history_processors.py:251-255`）。

工作原理：正则认出「带行号的行」和状态行 `[File: ... (N lines total)]`，倒着扫历史，第一次遇到某文件 = 最新窗口 → 留着；再遇到同文件 = 旧窗口 → 换成一行摘要。

```python
# history_processors.py:224-225
_pattern = re.compile(r"^(\d+)\:.*?(\n|$)", re.MULTILINE)
_file_pattern = re.compile(r"\[File:\s+(.*)\s+\(\d+\s+lines\ total\)\]")
```

**注意：这个处理器虽然被完整实现，但没有任何一个上游 config 用它**（我全仓 grep 过，只有定义处一处命中）。它是"给 100 行窗口视图配套的、写好但没启用"的东西。

对我们：**思想值钱，实现别抄**（我们不做"窗口"概念）。思想是「同一个文件的多次 `read_file` 只留最后一次」——这正好命中我们问题 #2 的一个变种：模型反复 `read_file` 同一个文件，历史里堆了 5 份。

#### ⑤ `CacheControlHistoryProcessor` — `type: cache_control`（`history_processors.py:261-302`）
- **触发**：每次查询，无条件。**必须排在所有压缩处理器之后**。
- **压掉**：不压内容，只在最后 `last_n_messages`（默认 2）条 user/tool 消息上打 Anthropic `cache_control: {"type": "ephemeral"}` 标记，并清掉更早的标记。
- **保留**：全部。
- **为什么重要**：它和 ② 是**互相打架**的。② 每轮改历史 → 缓存全失效；⑤ 是止损。这也解释了 `polling` 参数存在的唯一理由。

对我们：我们用不用 Anthropic 缓存另说，但**"压缩动作会摧毁前缀缓存"这个成本认知必须有**——这直接影响 §7 里 compact 的触发频率设计（**别每轮都压，攒着一次性压**）。

#### ⑥ `RemoveRegex` — `type: remove_regex`（`history_processors.py:305-337`）
- **触发**：每次查询，无条件。
- **压掉**：匹配 `remove` 里任意正则的内容，**但最后 `keep_last` 条不动**。
- **保留**：最后 `keep_last` 条原样；其余条目里不匹配的部分原样。
- 默认值 `remove: ["<diff>.*</diff>"]`，`keep_last: 0`。

这是**"滚动锚点"模式**：上游在 `config/benchmarks/250212_sweagent_heavy_sbl.yaml` 里把「累计 diff」塞进**每一条** observation，然后用 `remove_regex` 把所有旧的 diff 删掉，只留最后 2 条：

```yaml
      history_processors: &diff_history_processors
        - type: remove_regex
          keep_last: 2
          remove:
            - "<diff>.*</diff>"
        - type: cache_control
          last_n_messages: 2
          last_n_messages_offset: 2
```

**这个模式对我们极有价值**：它等于「**每轮都让模型看到最新的全局状态（diff），但历史上只保留一份**」。比 `LastNObservations` 更精准，因为它保留的是"状态"而不是"最近 N 条"。

#### ⑦ `ImageParsingHistoryProcessor` — `type: image_parsing`（`history_processors.py:340-387`）
- 把 markdown 里的 base64 图片转成多模态 `image_url` 段。与我们的问题无关，跳过。

### 3.2 汇总表

| # | 名字 | 触发条件 | 压掉什么 | 保留什么 | 上游用吗 |
|---|---|---|---|---|---|
| ① | `default` | 恒真 | 无 | 全部 | 默认值 |
| ② | `last_n_observations` | **每轮无条件**（非 token 阈值） | 前 n 条之外的 **observation** | 最后 n 条 / 首条 / 所有 action / `keep_output` tag | 0.7 时代全用 `n:5` |
| ③ | `tag_tool_call_observations` | 每轮，须在 ② 前 | 无（只打 tag） | 全部 | 配合 ② 用 |
| ④ | `closed_window` | 每轮无条件 | 同文件的旧窗口 | 每文件最新窗口 | **定义了但没人用** |
| ⑤ | `cache_control` | 每轮，须在压缩后 | 无（只打缓存标记） | 全部 | 现代默认 |
| ⑥ | `remove_regex` | 每轮无条件 | 匹配正则的片段（旧 diff） | 最后 `keep_last` 条 | heavy benchmark |
| ⑦ | `image_parsing` | 每轮无条件 | 无（格式转换） | 全部 | 多模态场景 |

**触发条件的总结论（务必记住）**：**上游 7 个处理器，0 个是按「上下文快满了」触发的。** 全部是「每轮无条件跑一个确定性变换」。对比 Aider 是「超阈值 → 后台线程摘要」（§5）。两条路线的取舍：

- SWE-agent 路线：确定性、可预测、可 replay、零额外 LLM 调用成本。→ **适合我们。**
- Aider 路线：信息密度高，但引入一次 LLM 调用（贵、慢、可能失败）、且摘要不可复现。→ **不适合我们（小项目，一轮几十个文件）。**

---

## 4. 命令执行与超时

### 4.1 四个超时参数（`sweagent/tools/tools.py:139-152`）

```python
execution_timeout: int = 30
"""Timeout for executing commands in the environment"""

install_timeout: int = 300
"""Timeout used for each of the installation commands"""

total_execution_timeout: int = 1800
"""Timeout for executing all commands in the environment.
Note: Does not interrupt running commands, but will stop the agent for the next step."""

max_consecutive_execution_timeouts: int = 3
"""Maximum number of consecutive execution timeouts before the agent exits."""
```

四个层级，各有分工：单命令 30s / 安装 300s / 整场 1800s（墙钟，只在**下一步开始前**检查，不打断正在跑的命令）/ 连续超时 3 次就**判死**。

### 4.2 超时后的完整路径（`agents.py:962-989`）

```python
try:
    step.observation = self._env.communicate(
        input=run_action,
        timeout=self.tools.config.execution_timeout,
        check="raise" if self._always_require_zero_exit_code else "ignore",
    )
except CommandTimeoutError:
    self._n_consecutive_timeouts += 1
    if self._n_consecutive_timeouts >= self.tools.config.max_consecutive_execution_timeouts:
        msg = "Exiting agent due to too many consecutive execution timeouts"
        self.logger.critical(msg)
        ...
        raise                                    # ← 直接把 agent 干掉
    try:
        self._env.interrupt_session()            # ← 打断容器里的进程
    except Exception as f:
        ...
        raise
    step.observation = Template(self.templates.command_cancelled_timeout_template).render(
        **self._get_format_dict(),
        timeout=self.tools.config.execution_timeout,
        command=run_action,
    )
else:
    self._n_consecutive_timeouts = 0
```

配套给模型看的话术（`agents.py:112-117`）：

```python
command_cancelled_timeout_template: str = (
    "The command '{{command}}' was cancelled because it took more than {{timeout}} seconds. "
    "Please try a different command that completes more quickly. "
    "Note: A common source of this error is if the command is interactive or requires user input "
    "(it is impossible to receive user input in the current environment, so the command will never complete)."
)
```

**话术的关键点**：不只说"超时了"，还**直接指控"命令大概率是交互式的、永远不会结束"**，并给出下一步（换更快的命令）。这比裸 `timeout` 强得多。

### 4.3 还有一道 `bash -n` 语法预检（`agents.py:102-107`）

```python
shell_check_error_template: str = (
    "Your bash command contained syntax errors and was NOT executed. "
    "Please fix the syntax errors and try again. This can be the result "
    "of not adhering to the syntax for multi-line commands. Here is the output of `bash -n`:\n"
    "{{bash_stdout}}\n{{bash_stderr}}"
)
```

**语法错的命令压根不执行**，省一次容器往返，也避免"半执行"。

### 4.4 输出体积控制

不在执行层，在**模板层**（`agents.py:729-746`）：

```python
elided_chars = 0
if step.observation.strip() == "":
    templates = [self.templates.next_step_no_output_template]
elif len(step.observation) > self.templates.max_observation_length:
    templates = [self.templates.next_step_truncated_observation_template]
    elided_chars = len(step.observation) - self.templates.max_observation_length
else:
    templates = [self.templates.next_step_template]
```

```python
# agents.py:69-82
next_step_truncated_observation_template: str = (
    "Observation: {{observation[:max_observation_length]}}<response clipped>"
    "<NOTE>Observations should not exceeded {{max_observation_length}} characters. "
    "{{elided_chars}} characters were elided. Please try a different command that produces less output "
    "or use head/tail/grep/redirect the output to a file. Do not use interactive pagers.</NOTE>"
)
max_observation_length: int = 100_000
```

三个可抄的点：
1. **空输出有话术**（`Your command ran successfully and did not produce any output.`）——否则模型会以为命令失败而重试。
2. **截断时告诉模型被砍了多少字符 + 该怎么办**（head/tail/grep/redirect）。
3. **截断发生在「进历史之前」**，所以历史里存的就是截断后的。→ 注意这跟我们问题 #2 的「只留尾部 200 行」是同一类做法，但**上游是按字符数、且保留头部**（`observation[:max]`），我们是**保留尾部**。保留尾部对编译错误/测试结论更合适（结论在尾部），这点我们做得对，不用改。

### 4.5 ★ 关键结论：后台任务 / 跟日志 —— 上游是空白，且被主动封杀

我全仓 grep（`sweagent/` 下 407 个文件）确认：**SWE-agent 没有任何后台任务、任务句柄、日志跟随的设施。**

反过来，它把这条路**明确堵死**了（`tools.py:37-65`）：

```python
blocklist: list[str] = [
    "vim", "vi", "emacs", "nano",
    "nohup",           # ← 阻止后台化
    "gdb", "less",
    "tail -f",         # ← 阻止跟日志
    "python -m venv", "make",
]
"""Block any command that starts with one of these"""

blocklist_standalone: list[str] = [
    "python", "python3", "ipython", "bash", "sh",
    "/bin/bash", "/bin/sh", "nohup", ...
]
```

被 `should_block_action` 命中后的效果（`tools.py:353-367`）→ 抛 `_BlockedActionError`（`agents.py:946-947`）。

**所以 specflow 问题 #4（超时后没有后台任务+跟日志，模型 `sleep` 轮询烧 7 轮）在上游是「无解 + 主动禁止」的。** 上游的替代方案只有两条：
1. `execution_timeout=30` + 换更快的命令 + 连续 3 次就判死；
2. 长任务请自己重定向到文件，然后**一次性** `tail -n`（不是 `tail -f`）——注意 `tail -f` 被封但 `tail` 没封，`redirect the output to a file` 就写在截断提示里（`agents.py:73`）。

**判定：这一条抄不到，必须自己设计。** 这反而是我们的机会——一个 `bash` + `bg=true` + `task_tail(task_id)` 能力，在 ACI 层面是显著超越上游的（详见 §6、§8）。

---

## 5. Aider：repo map / 历史裁剪 / lint 回馈

### 5.1 repo map：用 PageRank + 二分搜预算（`aider/repomap.py`）

目标：给模型一份"仓库骨架"，**严格控制在 `map_tokens`（默认 1024）以内**。

**第一步：排序（`repomap.py:365-574`）**

- tree-sitter 抽 tags（definitions / references）。
- 建图：`G.add_edge(referencer, definer, weight=use_mul * num_refs, ident=ident)`（`repomap.py:514`）。
- **带 personalization 的 PageRank**：

```python
# repomap.py:519-531
if personalization:
    pers_args = dict(personalization=personalization, dangling=personalization)
else:
    pers_args = dict()
try:
    ranked = nx.pagerank(G, weight="weight", **pers_args)
```

`personalization`（`repomap.py:374-445`）由**当前 chat 里的文件**和**chat 里提到过的标识符**加权。所以排序是「当前对话相关性」驱动的，不是静态重要性。
- 排除已在 chat 里的文件（`repomap.py:556-557`），这样预算全花在**没看过**的文件上。

**第二步：二分搜 token 预算（`repomap.py:666-706`）** ← 最巧的一段

```python
num_tags = len(ranked_tags)
lower_bound = 0
upper_bound = num_tags
best_tree = None
best_tree_tokens = 0
...
middle = min(int(max_map_tokens // 25), num_tags)     # ← 初始猜测：每 tag ≈ 25 token
while lower_bound <= upper_bound:
    tree = self.to_tree(ranked_tags[:middle], chat_rel_fnames)
    num_tokens = self.token_count(tree)
    pct_err = abs(num_tokens - max_map_tokens) / max_map_tokens
    ok_err = 0.15                                      # ← 误差 15% 内就收工
    if (num_tokens <= max_map_tokens and num_tokens > best_tree_tokens) or pct_err < ok_err:
        best_tree = tree
        best_tree_tokens = num_tokens
        if pct_err < ok_err:
            break
    if num_tokens < max_map_tokens:
        lower_bound = middle + 1
    else:
        upper_bound = middle - 1
    middle = int((lower_bound + upper_bound) // 2)
```

**这是"按 token 精确控量"的通用范式**：把"取前 K 个"的 K 当变量，二分搜 K 使渲染结果命中预算。任何"排序列表 + 渲染"的东西都能套。

**第三个细节：chat 为空时放大预算（`repomap.py:120-132`）**

```python
padding = 4096
if max_map_tokens and self.max_context_window:
    target = min(int(max_map_tokens * self.map_mul_no_files),
                 self.max_context_window - padding)
if not chat_files and self.max_context_window and target > 0:
    max_map_tokens = target
```
还没读过任何文件时，模型最需要全局视野，所以把预算放大（`map_mul_no_files` 倍），但仍留 4096 的余量。**"预算随对话阶段自适应"**这个思路对我们有用。

**还有：超大文件先用 filemap 骨架代替全文**（`str_replace_editor:493-509`）——`path.suffix == ".py" and len(file_content) > MAX_RESPONSE_LEN and USE_FILEMAP` 时，打印骨架 + 明确提示"用 `view_range` 看具体行"。这就是"骨架视图 vs 全文"的分级策略，与我们 `read_file` 该不该全文返回直接相关。

### 5.2 历史裁剪：阈值触发 + 后台线程摘要（`aider/history.py` + `base_coder.py:1002-1034`）

触发条件是**明确的 token 阈值**，与 SWE-agent 完全相反：

```python
# aider/history.py:15-18
def too_big(self, messages):
    sized = self.tokenize(messages)
    total = sum(tokens for tokens, _msg in sized)
    return total > self.max_tokens
```
`max_tokens` 传的是 `self.main_model.max_chat_history_tokens`（`base_coder.py:510-513`）。

```python
# aider/coders/base_coder.py:1002-1034
def summarize_start(self):
    if not self.summarizer.too_big(self.done_messages):
        return
    self.summarize_end()
    if self.verbose:
        self.io.tool_output("Starting to summarize chat history.")
    self.summarizer_thread = threading.Thread(target=self.summarize_worker)
    self.summarizer_thread.start()                    # ← 后台线程，主循环继续

def summarize_worker(self):
    self.summarizing_messages = list(self.done_messages)
    try:
        self.summarized_done_messages = self.summarizer.summarize(self.summerizing_messages)
    except ValueError as err:
        self.io.tool_warning(err.args[0])

def summarize_end(self):
    if self.summarizer_thread is None:
        return
    self.summarizer_thread.join()
    self.summarizer_thread = None
    if self.summarizing_messages == self.done_messages:   # ← 竞态保护
        self.done_messages = self.summarized_done_messages
    self.summarizing_messages = None
    self.summarized_done_messages = []
```

三个工程细节值得抄：

1. **摘要跑在后台线程**，用户/主循环不被阻塞。就是我们要的"后台任务"思路的雏形（虽然这里是内部摘要，不是用户命令）。
2. **竞态保护**：`if self.summarizing_messages == self.done_messages` —— 如果摘要期间历史又长了，**丢弃这次摘要结果**，不覆盖。
3. **摘要是分层的，保留近端原文**（`history.py:33-96`）：从尾部倒着累积，攒到 `half_max_tokens` 就切；**尾部原样保留，头部递归摘要**（`depth > 3` 时退化成一次性 `summarize_all`）。

```python
# aider/history.py:46-57
tail_tokens = 0
split_index = len(messages)
half_max_tokens = self.max_tokens // 2
for i in range(len(sized) - 1, -1, -1):
    tokens, _msg = sized[i]
    if tail_tokens + tokens < half_max_tokens:
        tail_tokens += tokens
        split_index = i
    else:
        break
```

而 `summarize_all`（`history.py:98-123`）：**丢掉所有非 USER/ASSISTANT 的角色**（即丢掉 tool 输出！），拼成 markdown，用 `prompts.summarize` + `prompts.summary_prefix` 打一次 LLM。

**成本判据**：这是**每超一次阈值就多一次 LLM 调用**。对于"一轮几十个文件"的小项目，这个开销和不可复现性都不划算。**判定：不抄摘要，只抄"保留近端原文"的分割思想。**

### 5.3 lint / test 回馈：静默即成功

**核心原则：只在失败时才把输出塞进上下文。**

```python
# aider/linter.py:62-63
if returncode == 0:
    return  # zero exit status
```

```python
# aider/coders/base_coder.py:1599-1623
if edited and self.auto_lint:
    lint_errors = self.lint_edited(edited)
    self.auto_commit(edited, context="Ran the linter")
    self.lint_outcome = not lint_errors
    if lint_errors:
        ok = self.io.confirm_ask("Attempt to fix lint errors?")
        if ok:
            self.reflected_message = lint_errors
            return                                   # ← 立即重问模型

...
if edited and self.auto_test:
    test_errors = self.commands.cmd_test(self.test_cmd)
    self.test_outcome = not test_errors
    if test_errors:
        ok = self.io.confirm_ask("Attempt to fix test errors?")
        if ok:
            self.reflected_message = test_errors
            return
```

`reflected_message` 的语义（`base_coder.py:933-944`）：**非空就直接带着这条错误再问一遍模型，不等用户输入**。这是"自动纠错回合"。

`cmd_test` 的回馈有一个非常值钱的裁剪规则 —— **只有非零退出码才把输出加进 chat**（`commands.py:1026-1027`）：

```python
if add_on_nonzero_exit:
    add = exit_status != 0
```

**代价**：`reflected_message` 是"错误全文"，没有长度上限，长 stacktrace 会直接进上下文。Aider 因此依赖用户手动确认（`confirm_ask`）。

**判定**：抄「静默即成功」+「失败才回灌」，不抄「无限长的错误全文直接进上下文」（我们应该对错误栈也做裁剪，只留最重要的 N 行 + 首尾）。

---

## 6. 值不值得借鉴（逐条：机制 / 收益 / 我们的代价 / 结论）

约束回顾：**项目小（一轮几十个文件），Java 手写工具，代码不能臃肿。** 所以判据是「每 10 行 Java 换来多少轮次的节省」。

| # | 机制 | 解决我们哪个问题 | 收益 | 我们的代价 | 结论 |
|---|---|---|---|---|---|
| 1 | **状态行**（`(Current directory: {{working_dir}}) (Open file: {{open_file}})` 钉在每条 observation 后） | **#1 猜路径** | ★★★ 直接消除。模型不需推理 cwd，读一行即可。约省 3-5 轮/任务 | **~15 行**：`bash` 执行后拼一行 `[cwd=/work \| open=... \| changed=3]`；把提示词里所有宿主路径换成 `/work` | ✅ **必抄，性价比第一** |
| 2 | `LastNObservations` 的**压制规则**：只压 observation、不压 action；首条永不压；tag 逃生舱 | **#3 上下文爆** | ★★★ 长对话不再爆，且轨迹可 replay | **~40 行**（见 §7） | ✅ **必抄**（但 `n=5` 太激进，我们取 `n≈8`；且改成**按字符预算触发**，见 §7） |
| 3 | `TagToolCallObservations` → 「关键输出打 `keep` 标签，压缩时绕过」 | #3 | ★★★ 让粗暴截断变成**语义截断**。这是把"压缩"从危险操作救回来的唯一机制 | **~10 行**：工具层在「失败(stderr 非空)/`apply_patch` 成功/`case_update`」时给消息打 `keep` | ✅ **必抄**，和 #2 是一对 |
| 4 | `RemoveRegex` 的**滚动锚点**：每轮注入最新全局状态，历史里只留最后一份 | #3 | ★★ 比"最近 N 条"更精准地保住"状态"而非"时序" | **~15 行**（我们已有 `case_update`，只需在 buildMessages 时把最新 case 状态拼到末尾，历史里的旧状态行删掉） | ✅ 抄（用 #1 的状态行天然实现） |
| 5 | **行号视图** `"{:6}\t{}"` + `MAX_RESPONSE_LEN` 截断 | #2 输出灌满 | ★★ 行号本身不省 token；省 token 的是「窗口化 + 截断 + 编辑后只回显 ±4 行」 | **~20 行**：`read_file(path, from, to)` 返回带行号；截断时附"用 grep/read_file(from,to) 定位" | ⚠️ **抄一半**：抄「带行号 + 分段读取 + 编辑后回显局部」，**不抄**「100 行固定窗口 + open/goto/scroll 那套是有状态的编辑器」（对我们太笨重，模型的 `read_file(from,to)` 已经更好）。详见 §8 |
| 6 | **编辑事务性（lint gate）**：改前测、改后测、只报新增、坏了回滚 | 间接治 #1/#3（少走弯路） | ★★ Java 编译秒级，值得；`diff-based` 只报新错误，避免老 warning 噪声 | **~30 行**：`apply_patch` 后跑 `javac`/`mvn -q compile`，只报 baseline 之外的新错误，失败则回滚并回显「应用后/原来」对照 | ✅ **抄**，但**降频**：只对 `.java` 且只对 `apply_patch` 做，别对 `write_file` 做 |
| 7 | **超时话术**「命令大概率是交互式的，永远不会结束，换更快的」+ 连续 N 次判死 | #4 的一半 | ★★ 比裸 `timeout` 强，能让模型不再傻重试 | **~10 行** | ✅ 抄 |
| 8 | **空输出话术**「命令成功但无输出」 | 防误判重试 | ★ | 3 行 | ✅ 抄 |
| 9 | **后台任务 + 跟日志**（`bash bg=true` + `task_tail(id)`） | **#4 核心** | ★★★ | **上游没有，抄不到**。自研约 80-120 行（进程表 + 环形缓冲 + 增量读） | ❗**自研**。见 §8 |
| 10 | 工具用 YAML bundle 声明 + 自动生成文档 | 无 | ★ 单一事实来源 | 重；我们是 Java 手写工具 | ❌ **架构不抄**，只抄「文档与 schema 同源」的想法（注解反射） |
| 11 | Aider 的 repo map（PageRank + 二分预算） | 无（我们一轮几十个文件，全读也不贵） | ✗ | 需要 tree-sitter + 图算法，几百行 + 依赖 | ❌ **不抄**。但可抄「骨架视图 vs 全文」的分级（见 §8 第 3 条） |
| 12 | Aider 的 LLM 摘要压缩 | #3 | ✗ 引入一次额外 LLM 调用 | 贵、慢、不可复现 | ❌ **不抄** |
| 13 | `pollling` 参数（压窗推进节流以保缓存） | 缓存成本 | ★ | 5 行（见 §7 的"攒着压"） | ✅ 抄思想：**别每轮都压** |

---

## 7. 我们要做的 "compact" 最小方案（≤30 行 Java 草图）

### 7.1 设计决策（先定 4 件事）

1. **在哪压**：只压「喂给模型的那一份」。`case_update` 落盘的 history 不动 —— 照抄 `agents.py:539-551` 的 `messages` property 分层。**压缩可逆，轨迹可 replay。**
2. **何时触发**：**按字符预算**（≈ token×4），不按轮数、不按 token 精确计数（Java 里精确分词太贵）。超了才压，且**一次压到底**，然后攒到下次超预算再压（等价于 SWE-agent 的 `polling`：避免每轮都动前缀、把缓存和 diff 都搞乱）。
3. **压什么**：只压 `TOOL` 角色的旧输出，替换成一行摘要。`USER`/`ASSISTANT` 不压（对话意图和推理线索留着）。
4. **保留哪 3 类锚点**：
   - **锚 1 任务锚**：system 消息 + 首条 user（原始 spec/需求）—— **永不压，也不参与预算计算**。
   - **锚 2 语义锚**：打了 `keep` 标签的 tool 输出 —— 最近一次的失败栈、`apply_patch` 的成功回执、`case_update` 的进度。**永不压。**
   - **锚 3 状态锚**：末尾动态拼上的「当前状态行」（`cwd` / 已改文件 / 当前 case 阶段）—— **每轮重建一份，历史里的旧状态行删掉**（`RemoveRegex` 模式）。

### 7.2 Java 草图（28 行）

```java
// HistoryCompactor：在 buildMessages() 出口调用。case 里存的 history 永不被修改。
static final int BUDGET = 120_000;   // ≈30k tokens；超了才压，压完攒到再超才压第二次
static final int KEEP_TAIL = 8;      // 最近 8 条 tool 输出原样保留（近端原文）

List<Msg> compact(List<Msg> raw, String stateLine) {
    if (chars(raw) <= BUDGET) return raw;                    // 触发：只按长度，不按轮数
    List<Msg> h = deepCopy(raw);                             // 可逆：不碰原始历史
    int tailFrom = Math.max(1, h.size() - KEEP_TAIL);        // 锚1：index 0 是 system/任务，永不压
    for (int i = 1; i < tailFrom; i++) {
        Msg m = h.get(i);
        if (m.role != TOOL) continue;                        // 只压工具输出，不压 USER/ASSISTANT
        if (m.tags.contains("keep")) continue;                // 锚2：失败栈 / patch 回执 / case 进度
        m.content = "旧输出已省略（" + lineCount(m.content) + " 行）: " + firstLine(m.cmdLine);
    }
    h.removeIf(m -> m.tags.contains("state"));                // 锚3：先把所有旧状态行删掉
    h.add(Msg.system(stateLine).tag("state"));                 //      再在末尾拼唯一一份最新的
    return h;
}

// 谁打 keep：工具层，写 history 的那一刻。对应 SWE-agent 的 TagToolCallObservations。
void onToolResult(Msg m, ToolResult r) {
    if (r.exitCode() != 0 || r.tool().equals("apply_patch")
        || r.tool().equals("case_update") || r.tool().equals("submit")) m.tag("keep");
}
```

**为什么这样切：**

- `KEEP_TAIL = 8` 对应 SWE-agent 的 `last_n_observations`，但单位是「最近 8 条 tool 输出」而不是「8 条 observation」，且**由预算而非轮数决定要不要压**——比上游的"恒久只留 5 条"温和，对小项目足够。
- `tags.contains("keep")` 是 `TagToolCallObservations` + `always_keep_output_for_tags` 的直接移植（`history_processors.py:129-132,179-212`）。
- 末尾拼 `stateLine` 是 `_state` + `next_step_template` 的移植（§2.3），顺手把 `RemoveRegex` 的"只留最新一份"也实现了。
- 全量 28 行，无依赖，无额外 LLM 调用，可单测（输入 history + 预算，断言输出长度和锚点存在）。
- **不做摘要**：省一次 LLM 调用，也省掉"摘要丢了关键信息"这类不可调试的 bug。

### 7.3 配套（建议一起做的 3 件小事，各 <10 行）

1. **`bash` 输出截断保持尾部**（我们已做对，200 行），但**加一句提示**：`<clipped: N chars elided; use grep/head, or bash(bg=true)+task_tail>`。
2. **`read_file` 带行号 + 支持 `from/to`**：`String.format("%6d\t%s", i, line)`（照抄 `str_replace_editor:685`）。
3. **`apply_patch` 后局部回显**：只回显改动点前后各 4 行（`SNIPPET_LINES = 4`），不要回显整个文件。

---

## 8. 不借鉴的部分

1. **YAML bundle 架构（`tools/<name>/config.yaml` + `bin/` 可执行文件）—— 不抄。**
   我们的工具是 Java 方法，天然单一来源；引入 yaml 只是多一层间接。bundle 架构存在的理由是"工具跑在容器里、和 agent 进程解耦"，我们的 `bash` 已经在容器里了，`read_file`/`write_file` 是宿主侧的 Java 方法，没必要假装成 CLI。
   *唯一移植的想法*：文档/schema 从注解生成，避免手写漂移。

2. **有状态的行编辑器（`open`/`goto`/`scroll_up`/`scroll_down` + registry 文件 + 100 行固定窗口）—— 不抄。**
   这套的代价是：必须维护 `CURRENT_FILE`/`FIRST_LINE` 跨进程状态（`registry.py` 整个文件就是为了它）、模型要学会"先 open 再 scroll"的两段式、窗口大小写死会让"看 300 行的配置文件"变成 3 轮。`read_file(path, from, to)` 一个无状态参数化调用能覆盖同样的能力，且对模型更简单。**我们已经有 `read_file`，只要补 `from/to` + 行号即可。**
   注意：上游自己也在往这个方向走——默认配置已经换成无状态的 `str_replace_editor`，`windowed` 退到 `config/exotic/`，而给 windowed 配套的 `ClosedWindowHistoryProcessor` **一次都没在正式配置里启用过**。

3. **Aider 的 repo map —— 不抄。**
   tree-sitter + PageRank + 二分搜预算，为的是"几万文件里挑 1024 token 的骨架"。我们一轮就几十个文件，`read_list` 一次全列出来更省事、更准。
   *可移植的碎屑*：`MAX_RESPONSE_LEN` 那种「超大文件先给骨架、再让模型要具体行」的分级，用一个 `read_file(topLevelOnly=true)` 就够了。

4. **Aider 的 LLM 摘要压缩（`ChatSummary`）—— 不抄。**
   多一次 LLM 调用、不可复现、失败还要 try/except 兜；小项目根本到不了需要摘要的规模。用确定性的 `LastNObservations` 式截断 + `keep` 标签就够。

5. **`max_observation_length = 100_000` 字符不截断细节 —— 不抄这个数值。**
   10 万字符 ≈ 25k token 一次性灌进去，对我们这种小上下文预算太奢侈。我们保持"尾部 200 行"更合适（编译/测试结论在尾部）。

6. **`LastNObservations(n=5)` 的具体数值 —— 不抄。**
   `n=5` 是 GPT-4 8k 时代的产物，会把 mid-task 的关键上下文切掉。我们取 8，且用预算触发。

7. **`last_n_observations` 那种「每轮无条件压」—— 不抄节奏。**
   每轮都改历史 = prompt cache 全失效（上游自己在 `history_processors.py:106-108` 承认了）。我们改成"超预算才压、一次压到底"。

---

## 9. 未核实点 + 证据清单（文件:行）

### 9.1 证据清单

**SWE-agent · 工具声明**
- `docs/config/tools.md:17-30` — bundle 目录结构（`bin/` + `config.yaml` + `install.sh`）
- `docs/config/tools.md:34-44` — yaml 工具声明样例（`filemap`）
- `docs/config/tools.md:46-78` — `state_command` 的作用与 `_state` 实现
- `tools/windowed/config.yaml:1-38` — `open`/`goto`/`create`/`scroll_up`/`scroll_down` 声明 + `state_command: "_state"`
- `tools/edit_anthropic/config.yaml:1-56` — `enum`、`items`、`argument_format: "--old_str {{value}}"`、`argument_format: "--view_range {{value|join(' ')}}"`
- `tools/search/config.yaml:1-37` — 三个搜索工具的声明（`find_file`/`search_dir`/`search_file`）
- `sweagent/tools/bundle.py:12-57` — `BundleConfig`（`tools` + `state_command`）、`Bundle.commands`
- `sweagent/tools/commands.py:52-77` — `Argument` 全字段
- `sweagent/tools/commands.py:79-131` — `Command`、`invoke_format`、signature→format 的 `<x>`→`{x}` 替换
- `sweagent/tools/commands.py:133-165` — `get_function_calling_tool()`（yaml → OpenAI schema）
- `sweagent/tools/commands.py:186-204` — validator：必填不能后置、名字去重、**signature 占位符集合 == 参数名集合**
- `sweagent/tools/commands.py:208-223` — `BASH_COMMAND` 内置 bash 工具
- `sweagent/tools/tools.py:168-191` — 收集 bundle 命令、重名报错
- `sweagent/tools/tools.py:193-195` — `tools` 属性 = function schema 列表
- `sweagent/tools/tools.py:198-224` — `model_post_init` 里生成 `command_docs`
- `sweagent/tools/utils.py:46-72` — `get_signature()` 自动签名
- `sweagent/tools/utils.py:75-108` — `generate_command_docs()` 文档格式
- `sweagent/tools/tools.py:292-312` — 上传 bundle、`chmod +x`、`which` 校验可用性

**SWE-agent · 查看/编辑原语**
- `docs/background/aci.md:9-15` — ACI 四条原则（linter gate / 100 行查看器 / 搜索只列文件 / 空输出话术）
- `tools/edit_anthropic/bin/str_replace_editor:27-28` — `TRUNCATED_MESSAGE` + `MAX_RESPONSE_LEN = 16000`
- `tools/edit_anthropic/bin/str_replace_editor:35` — `SNIPPET_LINES = 4`
- `tools/edit_anthropic/bin/str_replace_editor:57-63` — `maybe_truncate()`
- `tools/edit_anthropic/bin/str_replace_editor:438-514` — `view()`，含 `view_range` 校验、`WindowExpander` 扩窗、filemap 骨架降级
- `tools/edit_anthropic/bin/str_replace_editor:674-686` — `_make_output()`，行号格式 `"{:6}\t{}"`
- `tools/edit_anthropic/bin/str_replace_editor:516-522` — `str_replace` 前 `expandtabs()`
- `tools/edit_anthropic/bin/_state_anthropic:15-17` — 只吐 `working_dir`
- `tools/windowed/lib/windowed_file.py:100-114` — `CURRENT_FILE` / `FIRST_LINE` 写入 registry
- `tools/windowed/lib/windowed_file.py:116-148` — `first_line` 边界钳制、`line_range`
- `tools/windowed/lib/windowed_file.py:150-175` — `get_window_text()`（`line_numbers` / `status_line` / `pre_post_line` 三开关）
- `tools/windowed/lib/windowed_file.py:264-274` — `goto()` / `scroll()`（含 `overlap`）
- `tools/windowed/bin/open:8-42` — 无参 open 重显当前窗口；行号越界告警
- `tools/windowed/bin/_state:17-22` — 吐 `open_file` + `working_dir`
- `tools/windowed_edit_replace/config.yaml:1-60` — `edit <search> <replace> [<replace-all>]` + `insert`
- `tools/windowed_edit_linting/bin/edit:94-124` — **pre-lint / 应用 / post-lint / `undo_edit()` 回滚 / 回显 applied+original**
- `tools/windowed_edit_linting/bin/edit:27-43` — `_LINT_ERROR_TEMPLATE`（含"不要重试同一条命令"）

**SWE-agent · 状态行**
- `config/sweagent_0_7/07.yaml:65-76` — `(Open file: {{open_file}}) (Current directory: {{working_dir}}) bash-$`
- `config/default.yaml:9-12,20` — `{{working_dir}}` 在 instance_template 里（**容器侧路径**，不是宿主路径）
- `sweagent/agent/agents.py:739-746` — `**step.state` 灌进模板变量
- `sweagent/tools/tools.py:337-348` — `get_state()`：执行所有 bundle 的 state_command 后读 `/root/state.json`

**SWE-agent · history_processors**
- `sweagent/agent/history_processors.py:13-16` — `AbstractHistoryProcessor` 协议（`History -> History`）
- `sweagent/agent/agents.py:539-551` — **`messages` property：每次查询跑链、不动原始 history**
- `sweagent/agent/history_processors.py:74-82` — `DefaultHistoryProcessor`
- `sweagent/agent/history_processors.py:85-176` — `LastNObservations`（`n`/`polling`/两个 tag 集合/`_get_omit_indices` 的 `[1:]`/`Old environment output: (N lines omitted)`）
- `sweagent/agent/history_processors.py:106-111` — 注释承认"会破坏 prompt cache"、"SotA 模型上下文够大所以不一定需要"
- `sweagent/agent/history_processors.py:179-212` — `TagToolCallObservations`
- `sweagent/agent/history_processors.py:215-258` — `ClosedWindowHistoryProcessor`（`_pattern`/`_file_pattern`/`Outdated window with N lines omitted`）
- `sweagent/agent/history_processors.py:261-302` — `CacheControlHistoryProcessor`（`last_n_messages`/`last_n_messages_offset`）
- `sweagent/agent/history_processors.py:305-337` — `RemoveRegex`（`keep_last`）
- `sweagent/agent/history_processors.py:340-387` — `ImageParsingHistoryProcessor`
- `sweagent/agent/history_processors.py:390-399` — 判别联合 `HistoryProcessor`
- `docs/reference/history_processor_config.md:1-23` — 官方文档
- `config/default.yaml:67-69` — 默认只有 `cache_control`
- `config/sweagent_0_7/07.yaml` — `last_n_observations, n: 5`
- `config/benchmarks/250212_sweagent_heavy_sbl.yaml` — `remove_regex` + `keep_last: 2` + 累计 diff 注入
- 全 config 扫描结果：`last_n_observations` 见于 `07*.yaml`/`coding_challenge.yaml`/`human_demo.yaml`/`windowed_replace*.yaml`；`cache_control` 见于全部 2025 benchmark 配置；**`closed_window` 全仓 0 处使用**

**SWE-agent · 命令执行与超时**
- `sweagent/tools/tools.py:139-152` — `execution_timeout=30` / `install_timeout=300` / `total_execution_timeout=1800` / `max_consecutive_execution_timeouts=3`
- `sweagent/agent/agents.py:962-989` — `handle_action` 的 timeout 分支、`interrupt_session()`、连续超时判死
- `sweagent/agent/agents.py:1018-1019` — `total_execution_timeout` 在 `forward()` 开头检查
- `sweagent/agent/agents.py:112-117` — `command_cancelled_timeout_template`
- `sweagent/agent/agents.py:102-107` — `shell_check_error_template`（`bash -n` 预检）
- `sweagent/agent/agents.py:69-82` — `next_step_truncated_observation_template` + `max_observation_length = 100_000`
- `sweagent/agent/agents.py:729-746` — 按长度选模板、计算 `elided_chars`
- `sweagent/tools/tools.py:37-72` — blocklist（含 **`nohup`** 与 **`tail -f`**）
- `sweagent/tools/tools.py:353-367` — `should_block_action()`
- `sweagent/environment/swe_env.py:197-221` — `communicate()`，默认 `timeout=25`
- `sweagent/environment/swe_env.py:192` — `interrupt_session()` 定义处
- 全仓 grep（`sweagent/` 407 文件）确认：**无 background/task/log-follow 设施**，`tail -f`/`nohup` 仅出现在 blocklist 内

**SWE-agent · submit 门**
- `tools/review_on_submit_m/bin/submit:21-46` — `git diff --cached` → 多段 `SUBMIT_REVIEW_MESSAGES` 逐段回灌 diff → 最后才输出 `<<SWE_AGENT_SUBMISSION>>`
- `config/default.yaml:45-63` — `SUBMIT_REVIEW_MESSAGES` 内容（要求重跑复现脚本、清理脚本、revert 测试文件、再看 diff）
- `sweagent/tools/tools.py:372-376` — `check_for_submission_cmd()`

**Aider · repo map**
- `aider/repomap.py:365-574` — `get_ranked_tags()`（建图 `:514`、personalization 的 PageRank `:519-531`、排除 chat 文件 `:556-557`、rank 分发 `:533-545`）
- `aider/repomap.py:629-706` — `get_ranked_tags_map_uncached()`，**二分搜 K 命中 `max_map_tokens`，`ok_err = 0.15`，初始 `max_map_tokens // 25`**
- `aider/repomap.py:120-132` — chat 为空时放大预算（`map_mul_no_files`，留 4096 padding）
- `aider/repomap.py:576-613` — `map_cache` / `refresh` 策略（`manual`/`always`/`files`/`auto`）
- `aider/repomap.py:710-779` — `render_tree()` / `to_tree()`（只渲染 lines-of-interest）

**Aider · 历史裁剪**
- `aider/history.py:15-18` — `too_big()` 阈值判定
- `aider/history.py:33-96` — `summarize_real()`：尾部保 `half_max_tokens` 原文、头部递归摘要、`depth > 3` 退化
- `aider/history.py:98-123` — `summarize_all()`：丢弃非 USER/ASSISTANT 角色
- `aider/coders/base_coder.py:510-513` — `ChatSummary(..., max_chat_history_tokens)`
- `aider/coders/base_coder.py:1002-1034` — `summarize_start/worker/end`，后台线程 + 竞态保护
- `aider/coders/base_coder.py:1036-1038` — `move_back_cur_messages()` 里触发 `summarize_start()`

**Aider · lint / test 回馈**
- `aider/coders/base_coder.py:1681-1696` — `lint_edited()`（只在有错误时拼输出）
- `aider/linter.py:62-63` — `if returncode == 0: return`（**静默即成功**）
- `aider/coders/base_coder.py:1596-1623` — `reflected_message` 触发"自动再问一遍"（lint / test）
- `aider/coders/base_coder.py:933-944` — `reflected_message` 消费处
- `aider/commands.py:993-1011` — `cmd_test()`，`add_on_nonzero_exit=True`
- `aider/commands.py:1013-1053` — `cmd_run()`：计算输出 token 数、`add = exit_status != 0`、拼 `prompts.run_output`
- `aider/run_cmd.py:11-86` — **全文件无任何 timeout 参数**

### 9.2 未核实点（老实交代）

1. **Aider 的 tarball 未完整解包**（下载完成但未展开），Aider 部分全部基于 `raw.githubusercontent.com` 单文件取回：`repomap.py`(27 KB)、`history.py`(5 KB)、`coders/base_coder.py`(86 KB)、`coders/base_prompts.py`、`commands.py`(62 KB)、`run_cmd.py`(4 KB)、`linter.py`(8 KB)。**行号可信**（取的是 `main` 同名文件），但**没看到 Aider 全仓**，所以「Aider 里有没有别处做输出截断」这一条我无法断言 —— 我只核到 `cmd_run` 里没有截断逻辑，`base_coder.py` 里 grep `truncat|max_lines|250|output_lines` 无命中。
2. **SWE-agent `sweagent/tools/` 下无 yaml** 是 `main` 分支（2026-10 快照）的事实。任务描述里说的 `registry.py` / `toolconfig` / `sweagent/tools/` 下的 yaml 属于**已被重构掉的旧版（≤1.x）**。我没有去 fetch 旧 tag 核对那个确切形状，所以 §1 描述的是**当前**的 bundle 形状；如果我们要参考旧版，需要另外拉 tag。
3. **`windowed_edit_linting` 未在默认配置中启用**：`config/default.yaml` 用的是 `edit_anthropic`（无 lint gate）。`_state` / lint gate 这套只在 `config/exotic/` 和 `config/sweagent_0_7/` 里。我没有逐一核对每个 benchmark 配置的 `USE_LINTER` registry 变量，所以「lint gate 在评测里是否开启」未确认。
4. **`tools/diff_state/bin/_state_diff_state` 取回 0 字节**（curl write error 三次）。所以 §3 里没有 diff_state bundle 的细节，只有 `config/benchmarks/250212_sweagent_heavy_sbl.yaml` 里 `remove_regex` + `{{diff}}` 的用法证据。若要用「每轮注入 diff」这个机制，这条实现需要补读。
5. **`tools/diff_state/config.yaml`（44 字节）取回成功但极短**，未展开分析。
6. **未测**：以上全部是静态读码结论。SWE-agent 的 `last_n_observations(n=5)` 相对 `cache_control only` 的实测收益差异，只有上游论文/benchmark 数字，我没核对具体分数。
7. **"100 行窗口" 的实际 `WINDOW` 默认值**：`windowed_file.py:101` 是 `int(registry.get_if_none(window, "WINDOW"))`，`WINDOW` 来自 registry 环境变量；`docs/background/aci.md:12` 说 100 行，但我没有在代码里找到硬编码的 100 —— 它由 `install.sh` 或 registry 变量注入。**§2.2 里"100 行"引用的是 ACI 文档而非代码常量**，这点请注意。

---

## 附：一页速查（给实现者）

```
必抄（按优先级）
 1. 状态行钉在每条 observation 后        → 治「猜路径」，~15 行
 2. only-compact-observations + keep tag → 治「上下文爆」，~40 行
 3. 超时话术「大概率是交互式命令」        → 治「white-spin」，~10 行
 4. 空输出话术                            → ~3 行
 5. apply_patch 后 diff-based lint gate   → ~30 行（降频到只 .java）
 6. read_file 带行号 + from/to            → ~20 行

抄一半
 7. 行号视图：抄「行号 + 分段 + 局部回显」，不抄「100 行固定窗口 + scroll 状态机」

不抄
 8. YAML bundle 架构
 9. 有状态行编辑器（open/goto/scroll + registry 文件）
10. repo map（tree-sitter + PageRank + 二分）
11. LLM 摘要压缩
12. last_n = 5 / 每轮无条件压

必须自研（上游空白）
13. bash(bg=true) + task_tail(id) 后台任务与增量日志
```
