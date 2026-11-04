# G1-L1 离线证据检查点（2026-11-04）

状态：`L1_PARTIAL / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED`

## 已完成

- 核验 `codex/v2-integration`、HEAD `89f09ca5da57c252e3a20779cde01fa7a80cc7d2`
  与 clean 基线。
- 使用本地 Gradle wrapper/JDK 执行一次 `--offline --no-daemon` phone debug 构建，
  exit `0`，生成四 ABI APK。
- 提交前以同一命令复跑：exit `0`，`BUILD SUCCESSFUL in 50s`，242 tasks 全部
  up-to-date，wrapper wall time `50.368s`；合并 stdout/stderr 已保存为
  `build-console.txt`。
- L1 根 `.gitattributes` 固定 JSON/JSONL/Markdown/Python/SHA-256/TXT 为 LF；
  `build-console.txt` 保留原始行尾空格，仅禁用该类 transcript 的
  `blank-at-eol` whitespace 检查。console 内容与 SHA-256 未改变。
- 生成四 APK 的 ZIP entry、native、license/notice-like path、版本与签名 inventory。
- 从既有 Q03 SBOM 汇聚 296 external components，保留 46 `EXACT`、250 `FAMILY`、
  0 `UNKNOWN`，并将 4 个 mixed 保持为 `FAMILY`。
- 明确 source 0/296、packaged SBOM 0、license gap 6、A–D 状态与 exact 下载门。
- verifier 先完成 5 个基础负例、4 个提交前 P2 负例、3 个属性负例与独立
  `--no-daemon` 变异 RED，再实现 13/13 校验。
- 提交后复验回归另行 RED→GREEN：computed bundle 现接受“全部由 `HEAD` 跟踪”或
  “全部为普通 `git add -n` 候选且未 ignored”两种完整形态，拒绝混合、遗漏与
  ignored；selftest 扩为 24 项，保留 untracked / staged candidate 正向覆盖。
- 独立复审 P1 已 RED→GREEN：verifier 递归遍历证据根全部普通文件；嵌套
  `subdir/hidden.log`、nested untracked/ignored 混合及 manifest 未覆盖 nested
  文件均有负例，tracked 模式出现 non-HEAD 文件必须失败。

## 未完成 / BLOCKED

- `B1`：Sherpa-ONNX v2.0.1 本地 artifact 缺失；未联网补齐。
- `C1`：普通话 voice/model/material 与模型卡缺失；按要求不选 exact asset。
- `D1`：stable PDFium 2.0.3 AAR/source 本地缺失；revision、notice、16 KB
  本地复算仍缺。
- `commands.log` 已移除并改为可跟踪的 `commands.jsonl`；`build-console.txt` 保存
  实际构建输出，不依赖 JSON 自述。
- 当前包不是 2.0 candidate；无 canonical packaged SBOM、complete notice、
  corresponding source、written offer、attestation 或 relink 证据。
- runtime / device / Range / TTS 零网络与播放能力全部未执行。

## 实际权限与红线

本检查点没有网络、设备/ADB、账号、服务器、数据库、runtime、生产代码、G2、
独立 QA 或 Release 操作；没有 commit / push，也没有把 APK 复制进证据目录。

## 下一步

角色：fresh scoped reviewer / 交付负责人。

第一条只读动作：读取 `manifest.json`、`manifest.sha256`、本检查点、
`build-attempt.json`、`source-index.json`、两类 inventory 与两张 gap matrix。

停止条件：manifest / LF / hash / 计数不一致，出现绝对个人路径，current app 被写成
2.0 candidate，或任何缺口被写成 Q03 / G1 / runtime PASS。通过 scoped review 后，
若需 B1/D1 本地缺失 artifact，须由用户另行明确网络授权；若需 runtime/device，
须另行授权 L2/L3。
