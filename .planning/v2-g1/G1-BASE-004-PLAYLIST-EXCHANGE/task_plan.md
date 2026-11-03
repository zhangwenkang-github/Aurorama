# G1-BASE-004 执行计划

## 目标

冻结本地歌单 JSON / M3U8 交换合同证据，覆盖版本、未知字段、稳定媒体身份、来源边界、
M3U8 表达限制、异常导入与往返损失；不实现生产 parser、SAF 写入或服务器歌单逻辑。

## 阶段

1. `complete` 基线核验：HEAD、branch、clean、AGENTS、V2_PLAN / TASKS / DECISIONS、
   R020/R025/R027/R028 必要短段和 S3 身份摘要。
2. `complete` 代码边界定位：仅用 graphify query 或 rg 找 1–3 个相关生产文件做局部核对。
3. `complete` 合同建模：versioned JSON Schema、身份/冲突规则、M3U8 mapping matrix、
   导入与 roundtrip 状态表。
4. `complete` fixture / test：正测及 missing-track、duplicate-identity、
   version-unsupported、M3U8-lossy-roundtrip 边界案例。
5. `complete` 验证与交付：schema 可解析、fixtures/测试、LF、哈希、敏感值、manifest、
   report.json、短 README；复核 write domain 与 repo status。

## 边界

- 唯一写域：`.planning/v2-g1/G1-BASE-004-PLAYLIST-EXCHANGE/`。
- 不修改生产代码、资源、schema、文档、设备、SAF 或服务器；不 commit / push。
- 服务器侧只读；不调用生产写接口。
- B：需要用户选择导入/导出合并策略。
- C：需要 SAF、设备或真机行为验证。
- D：需要生产 parser / 服务器歌单实现或协议实测。

## 验收

- 所有合同规则均可由 fixtures 或测试表达，不被 M3U8 的不可表达字段伪造。
- 缺曲、重复身份、未知主版本、损坏/部分导入均为显式状态，不静默删曲或覆盖。
- JSON schema 可解析；测试脚本 exit 0；manifest、SHA-256、bytes、LF 与敏感值扫描可复核。
