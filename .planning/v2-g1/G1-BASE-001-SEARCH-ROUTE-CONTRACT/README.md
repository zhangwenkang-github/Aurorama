# G1-BASE-001 搜索路由状态合同

状态：`STATIC_CONTRACT_COMPLETE_RUNTIME_UNVERIFIED / G1_NOT_PASSED`。

本包只冻结静态合同，不实现生产搜索、不访问设备/服务器、不做独立 QA、不提交或发布。
S3 的端点与协议事实继续以 `.planning/v2-g1/G1-S3/` 为唯一台账；本包只消费其 Search
摘要，并补齐 scope/query identity、路由状态、结果身份、返回恢复与 runtime 门。

## 入口

- `scope-query-matrix.json`：4 scope、完整 query shape、规范化与 hash 去碰撞规则。
- `route-state-machine.json`：11 states、20 transitions、代际/取消/deadline/错误/offline。
- `stable-identity-matrix.json`：稳定结果身份、跨域同标题、去重与排序。
- `user-commitment-matrix.json`：13 条用户承诺及 A/B/C/D 门。
- `fixtures/search-contract-cases.json`：4 规范化、16 状态、7 身份、4 恢复样本。
- `report.json`：结论、计数、P0–P3、未覆盖与来源去重。
- `manifest.json`：除自身外的全量文件、字节数与 SHA-256。

## 验证

```powershell
pwsh -NoProfile -File .\.planning\v2-g1\G1-BASE-001-SEARCH-ROUTE-CONTRACT\verify-contract.ps1
pwsh -NoProfile -File .\.planning\v2-g1\G1-BASE-001-SEARCH-ROUTE-CONTRACT\verify-contract.ps1 -Mutation stale-response-overwrite
pwsh -NoProfile -File .\.planning\v2-g1\G1-BASE-001-SEARCH-ROUTE-CONTRACT\verify-contract.ps1 -Mutation cross-scope-hit
pwsh -NoProfile -File .\.planning\v2-g1\G1-BASE-001-SEARCH-ROUTE-CONTRACT\verify-contract.ps1 -Mutation empty-as-error
```

正测 exit 0；三类 mutation 必须 exit 1。manifest 由
`verify-contract.ps1 -GenerateManifest` 在内容冻结后生成，manifest 不包含自身。

## 门

`B` 需要后续授权的生产搜索改动；`C` 需要用户选择排序/分组/呈现；`D` 需要真实设备或其他
runtime 证据。历史搜索 ANR `L14-01` 仍为 `ACCEPTED_RESIDUAL`，本静态包不关闭它。
