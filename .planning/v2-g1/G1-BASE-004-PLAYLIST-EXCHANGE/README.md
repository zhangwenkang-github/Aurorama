# G1-BASE-004 本地歌单交换合同

状态：`STATIC_CONTRACT_COMPLETE_WITH_B_C_D_OPEN / G1_NOT_PASSED / NO_IMPLEMENTATION`。

本目录只冻结本地歌单 JSON / M3U8 交换合同，不实现生产 parser、SAF 写入、设备流程或服务器歌单。
本地歌单身份域是 `LOCAL_DRAFT`；服务器歌单与离线 saved user 不因名称、标题或当前会话而合并。

## 入口

- [report.json](report.json)：结论、计数、B/C/D、P0–P3、未覆盖与下一步。
- [local-playlist-exchange.v1.schema.json](schemas/local-playlist-exchange.v1.schema.json)：v1
  Draft 2020-12 schema。
- [M3U8_MAPPING.md](M3U8_MAPPING.md)：34 行 EXTINF / URI / 注释 / `EXT-X-*` 行为矩阵。
- [roundtrip-matrix.json](roundtrip-matrix.json)：JSON→JSON、带 marker / generic
  JSON→M3U8→JSON 的保留、降级、丢失状态。
- [test-results.json](test-results.json)：99/99 静态检查结果。
- [manifest.json](manifest.json)：相对路径、bytes、SHA-256 与 LF 清单；排除自身。

测试命令：

```powershell
python .\.planning\v2-g1\G1-BASE-004-PLAYLIST-EXCHANGE\test_contract.py
```

关键不变量：只有完整 SOURCE 身份可呈现为同一已解析媒体；缺曲保留；重复保留；身份冲突停止写；
跨来源同 itemId 与同标题不合并；损坏、未知 required feature、高版本和高阶 HLS 指令均显式拒绝。
基础 M3U8 无 AURORAMA marker 时，恢复结果是 `UNKNOWN / NO_MERGE`，不能宣称无损。

需要用户选重复 / 部分导入合并策略时为 B；需要 SAF、设备、重启或真机证据时为 C；需要生产
parser、持久化迁移、服务器歌单实现或真实协议时为 D。本轮三者均未越权执行。
