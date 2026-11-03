# 发现与决策

## 需求

- 冻结多 scope 搜索路由状态合同静态证据包；解决结果串域、返回查询丢失、状态不可区分、
  旧历史 ANR P1 风险的设计缺口。

## 研究发现

- `docs/V2_PLAN.md` 的 `V2-REQ-BASE-001` 要求 scope、规范化、正/空/失败/加载/返回恢复，
  并明确 G1 固定 scope 映射、超时与 fixture；真实 ANR 仍是 G2/runtime 门。
- S3 的 Search 只冻结端点可表达性，当前客户端只使用
  `userId/searchTerm/includeItemTypes/recursive`；分页、排序、字段、图像、过滤和
  401/403/503 映射仍是缺口。
- 历史 `L14-01` 为 `ACCEPTED_RESIDUAL`：9.56 秒 Main continuation 与 5 秒 Input timeout
  只能由 runtime/device 门重开和验证。
- 局部代码：scope-to-type 映射已存在；状态对象在 scoped evidence 中只有 loading 与
  server/local failure；ViewModel 的 SavedState 只明确保存 query/scope，已完成请求身份为
  query+scope。
- 本 worktree 无 graphify graph，按项目规则使用 `rg` 定位后只读 3 个搜索源文件。

## 技术决策

| 决策 | 理由 |
|------|------|
| query identity 覆盖全部行为参数 | 只 hash `searchTerm` 会混淆合法 scope 请求 |
| 代际 + scope + normalized query + deadline 共同判 stale | 单一时间或 scope 不足以归因迟到响应 |
| stable identity 不以标题为键 | 同标题跨域是合法共存，不得误合并 |
| 历史 ANR 标 runtime/implementation gate | 静态合同不能证明设备卡顿已消失 |

## 遇到的问题

| 问题 | 解决方案 |
|------|---------|
| graphify graph 缺失 | 使用 scoped `rg` 定位，避免全仓阅读 |
| PowerShell 条件命令解析错误 | 括号包住 `Test-Path` 后再与 `-and` 组合 |
| ordered dictionary 的 `bytes` 无法由 `Measure-Object -Property` 读取 | 按 manifest 条目显式累加字节 |

## 资源

- 目标 worktree：`F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama`
- 唯一写域：`.planning/v2-g1/G1-BASE-001-SEARCH-ROUTE-CONTRACT/`
- S3 已有事实：`.planning/v2-g1/G1-S3/report.json`、`contracts.json`、`commands.log`

## 合同结论

- query identity 必须对完整 behavior shape 做 canonical SHA-256；四 scope 的样本 hash
  必须两两不同。
- late/mismatched response 不改变状态；deadline、cancel、offline、stale、empty、error
  是不同分类。
- 稳定身份包含 `itemType`，因此同标题 Movie/Audio/Book 共存；仅证据充分的 download
  overlay 折叠。
- back stack 最小锚点为 query、scope、queryShape binding、first-visible stable identity +
  offset、selection identity。
- 收口时初始 clean 的 `.planning/v2-g1/.gitattributes` 出现并发外部修改
  `*.m3u8 text eol=lf`；本会话未编辑该文件，已保留并记录，未回退他人改动。
