# 发现

## 基线

- HEAD：`287c37e1d1287e2a90c047e628f7272a13cc5970`
- 分支：`codex/v2-integration`
- 工作树非 clean：预存修改仅 `docs/DECISIONS.md`、`docs/TASKS.md`、`docs/V2_PLAN.md`；本任务不改动这些文件。
- graphify 查询回退原因：当前 worktree 缺少 `graphify-out/graph.json`。

## 待补

- `V2_PLAN` §5.7、§5.11、§9 的精确短段。
- `TASKS` / `DECISIONS` 中 DOWNLOAD、Q03、Q04 命中。
- R020 / R025 / R027 / R028 命中。
- 现有 1.4 下载状态模型的 1–3 个局部实现文件。

## 已核实来源

- `V2_PLAN` §5.7.2 固定 D129/D130/D133–D136/D140/D143–D145 的产品裁决；§5.7.3 记录 Worker SUCCESS、通知身份、删除残留与 finalizer 分层风险；§5.7.4–§5.7.5 固定 DOWNLOAD-037/038/061/065 与 D-N/D-R 证据边界。
- `V2_PLAN` §5.11.1/§5.11.3 把下载控制列为独立 seam；§9 的 L14-04/05/19/27/28 明确通知身份、删除残留、统计、书籍 source 与可选资产 finalizer 缺口。
- `DECISIONS` 的 D129/D130/D133/D134/D136 与 Q03/Q04 Open 口径已命中；TASKS 仍将 `V2-SOLUTION-BASELINE` 置于 In Progress。
- 现有实现只读了 `DownloadTask.kt`、`DownloadLifecycle.kt`、`DownloadNotifications.kt` 三份局部：存在 generation、删除过渡与持久 watch 方向，但取消仍落 FAILED、通知以 sourceId hash 标识且没有 purge API、通用 accepted/done/deadline 字段未进主模型。

## 2026-11-04 删除 predicate 审查修复

- 根因：`Get-DeletionState()` 只用 `FAILED` / 缺失设置错误，再把“未出现错误”当作全部 confirmed；因此 `PENDING` 等未知值被错误折叠进 `DELETED`。
- RED：新增 `DEL-PENDING-FILES` 后，修复前得到 `expected DELETING, actual DELETED`，exit 1。
- 修复：五类 child 逐项要求字面 `CONFIRMED`；`FAILED` / 缺失归 `DELETING_FAILED`，其他非 confirmed 值归 `DELETING`。
