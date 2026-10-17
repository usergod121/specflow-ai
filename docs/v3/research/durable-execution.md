# 持久执行（Durable Execution）范式研究 — Temporal / Cadence / Restate / DBOS

> 面向 specflow v2 架构研究。目标：从成熟持久执行引擎中提取可借鉴的**恢复、重试、心跳、审批、幂等、补偿**机制。
> 每条结论尽量带源码证据（`文件路径` + 关键片段）。源码来自 `temporalio/sdk-java@main` 与 `temporalio/temporal@main` 的 tarball。
>
> 状态：撰写中（本节为初稿骨架，正在补证据）。

（正文见下）
