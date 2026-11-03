# G1-BASE-003 持久 SWR 静态合同

本目录冻结 `V2-REQ-BASE-003` 的缓存身份、新鲜度、失效、容量与多进程边界。它只冻结静态合同，不实现缓存、不改 schema、不运行设备或服务器。

## 文件

- `report.json`：结论、现状常量、合同缺口、P0–P3 与未执行项。
- `cache-identity-matrix.json`：19 维 lookup identity、canonical key 与身份失配负例。
- `freshness-state-machine.json`：10 个投影状态、TTL/时钟/304/stale-if-error、generation fence 与容量规则。
- `user-commitment-matrix.json`：12 条承诺到可观察结果和 fixture 的映射。
- `fixture-test.ps1`：正例与三个合同破坏负例。
- `manifest.json`：除自身外全部交付文件的字节数与 SHA-256。
- `commands.log`：实际执行、验证和未执行边界。

## 运行

```pwsh
pwsh -NoProfile -File .planning/v2-g1/G1-BASE-003-PERSISTENT-SWR/fixture-test.ps1 -Case positive
pwsh -NoProfile -File .planning/v2-g1/G1-BASE-003-PERSISTENT-SWR/fixture-test.ps1 -Case mutation-older-generation-used
pwsh -NoProfile -File .planning/v2-g1/G1-BASE-003-PERSISTENT-SWR/fixture-test.ps1 -Case cross-account-hit
pwsh -NoProfile -File .planning/v2-g1/G1-BASE-003-PERSISTENT-SWR/fixture-test.ps1 -Case write-after-still-fresh
```

`positive` 必须退出 0。三个负例故意让错误候选触发合同破坏，必须退出 1；非零代表负例按预期抓到了风险，不是 harness 故障。

## 边界

- 本合同不替代 G1-S3：S3 管端点、OpenAPI 状态码与 ETag/304 能力账；本目录只规定“若运行时确实得到合法 304，缓存如何接受或拒绝”。
- fixture 中的人为小 TTL 只用于状态测试，不是产品默认值。
- 实际 TTL、容量、淘汰、pin、预取、多进程存储与生产代码修复尚需负责人/用户决策和后续授权。
