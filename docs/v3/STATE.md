# specflow v3 · 状态（**上下文压缩之后，第一个读这个文件**）

> 更新时间：2026-10-17 12:50　|　交付时间：**今天 18:00**　|　状态：**功能与文档已完成**
> 用户在睡觉，已授权一口气做到交付。

## 一句话：这一轮做了什么

把 `E:\specflow-v2` 从 v2 升级到 **v3**：先写架构文档（`docs/v3/ARCH-V3.md`）→ 让两个 subagent 锐评
（`REVIEW-arch.md` / `REVIEW-scope.md`）→ 按评审收敛成 4 件小事（M1 超时单旋钮、M2 SSE、M3 卡死检测、
M4 闸拆表）→ 再把用户实测出来的 8 条缺陷修掉（C1）→ 全测试 + 端到端 + 交付文档。

**五节点的语义与顺序一个字都没改。**

## 现在到哪一步（全部完成，逐条见 `PLAN.md` 的勾）

- [x] A 研究（13 个 subagent 因会话中断只留下半页 → 改为我直接写架构，标注【实测】/【常识】）
- [x] B 架构文档 + 两份评审 + 取舍记录（`ARCH-V3.md` / `DECISIONS.md`）
- [x] C1 缺陷修复 11 条（容器能力校验、每用例预算/跳过/重试、Preflight、守卫三档、依赖缓存卷…）
- [x] M1–M4 + 事件流行快照（后端测试 162→163 条）
- [x] C3 SSE（后端 + 前端 + 真浏览器验证）
- [x] D 端到端（真模型 + 真 Docker）：**98 秒通过**，`reports-v2\v3-e2e-3.log`
- [x] E 交付：`docs/BOOK.html`（类书）、`docs/DELIVERY.md`、三个 bat

## 端到端里又抓到的两条（都修了，值得记住）

1. **模型的命令不该占满「我愿意等」的额度**：⑤ 为验一条并发用例写了个死循环程序，在容器里
   150% CPU 转了 15 分钟。修法：`BashTool.MAX_TIMEOUT_SECONDS = 600`（env/build 那类仍用旋钮）。
2. **准备阶段不该记在第一条用例头上**：`read_list` 七八次把 C1 的预算吃光 → C1 一秒没验就被跳过。
   修法：`CaseBudget.PREP_GRACE = 8`。

## 硬约束（这一轮守住了）

1. 五节点语义与顺序不变 ✓（`GatesTest` 一个字节都没改就是机器证明）
2. 前端只加 SSE / 新按钮 ✓（`store.js` 一处 + 两个视图各一行）
3. 一个 bat 入口 ✓（`start.bat` / `stop.bat` / `dev.bat`，本轮复验过）
4. 交付 HTML 讲解文档 ✓（`docs/BOOK.html`）
5. 没碰 8770 ✓、密钥没进版本库 ✓

## 文件地图

- 架构与账：`docs/v3/{ARCH-V3,DECISIONS,PLAN,STATE}.md`、`docs/v3/{REVIEW-arch,REVIEW-scope}.md`
- 交付：`docs/BOOK.html`（讲解）、`docs/DELIVERY.md`（怎么跑 / 验过什么 / 没做什么）
- 代码：`src/main/java/com/specflow/{core,flow,env,agent,test,web}`；前端 `src/main/frontend`
- 测试证据：`E:\sf-e2e\reports-v2\`（`v3-e2e-3.log` 是最后那次端到端）
- 用户真项目（回归样本）：`E:\library-management\library-management-backend`
- 回滚点：tag `v2-working`（v2 可用基线）、`v3-c1`、`v3-m3m4`
