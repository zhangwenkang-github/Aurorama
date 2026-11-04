# Aurorama G1-L1 离线打包与许可证据

状态：`L1_PARTIAL / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED`

本目录只覆盖 `V2-G1-L1-EVIDENCE` 的本地、可逆静态证据。事实基线为
`codex/v2-integration` / `89f09ca5da57c252e3a20779cde01fa7a80cc7d2`，开工时
worktree clean。没有联网、设备、ADB、账号、服务器、生产代码或 runtime 操作。

## 构建结果

- 命令：`.\gradlew.bat :app:phone:assembleDebug --offline --no-daemon --console=plain`
- 提交前复跑 exit：`0`；Gradle `9.7.1`；JDK `25.0.3`
- Gradle 报告：`BUILD SUCCESSFUL in 50s`；`242` actionable tasks，全部 up-to-date；
  wrapper wall time `50.368s`
- 输出：4 个当前 App 的 1.4.0(5) libre debug split APK，覆盖
  `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`
- 代表 APK：`app/phone/build/outputs/apk/libre/debug/phone-libre-arm64-v8a-debug.apk`
- 代表 APK：`150,290,331 B` / SHA-256
  `20ECD05FA6964A1F4B4C8D2DAB56D0275DC9BC7E951B695AC271D9BE37C62DB1`
- 四个 APK 均为当前 App，不是 2.0 candidate；均使用 Android Debug v2 签名。

完整退出、四 APK 路径/大小/SHA-256 与执行计数见 `build-attempt.json`；完整
四个 APK 的构建事实记录在 `build-attempt.json`。完整 ZIP entry ledger、native
文件哈希、license/notice-like 路径与签名事实记录在 `packaged-inventory.json`。
离线命令与实际 stdout/stderr 合并在 `build-console.txt`：`321` 行，
SHA-256 `64334CAB4D0F53D4B3F25ACAE8C168DF2B346EB8E272C8E689ED03B41079723B`。

## 覆盖与缺口

- 依赖：296 external Maven components + 9 project modules，35 direct / 261 transitive。
- 许可 provenance：46 `EXACT`、250 `FAMILY`（其中 4 个 mixed 未提升）、0 `UNKNOWN`。
- corresponding source：0/296；当前包没有 packaged SBOM。
- notice：62/296 可映射到当前包或既有 Q03 证据；234/296 未在本次范围定位。
- license gap matrix：2 `PARTIAL` + 4 `BLOCKED`，缺口合计 6。
- native：4 个 APK 共 56 个 native ZIP entries；既有 Q03 native matrix 仍为
  13 `PROVEN` / 6 `PARTIAL` / 12 `BLOCKED`。
- Q03 未关闭，任何 `PARTIAL` / `UNKNOWN` 都未被提升为 `PASS`。

## A1 / B1 / C1 / D1

- `A1 PARTIAL`：Media3 `1.11.1`、Jellyfin decoder `1.9.0+1`、libmpv `1.0.0`
  与 ass-kt `0.5.1` 已进入当前离线构建；四 ABI / 静态 16 KB 事实沿用
  `M3-07`、`MPV-06`，runtime load-order 与同进程 FFmpeg 冲突仍 `BLOCKED`。
- `B1 BLOCKED`：Sherpa-ONNX `v2.0.1`（commit
  `af2d8d81dbfa61c4cea4c568da78f3982edca005`）本地缓存缺失，且没有官方 Maven
  坐标。后续若获准，所需域为 `github.com`，需要固定 release AAR/source SHA。
- `C1 BLOCKED`：worktree 内没有可审计普通话 voice、ONNX model、lexicon、rule
  data 或 model card；exact asset 未选择，许可 / 体积 / 质量门见矩阵。
- `D1 BLOCKED`：`io.legere:pdfiumandroid*:2.0.3` 本地 AAR/source 缺失；
  native revision、third-party notice 与本地 16 KB 复算缺失，既有上游静态
  16 KB 状态为 `FAIL_REPORTED_UPSTREAM`；PdfRenderer 回滚路径保留。

## 文件与复算

- `source-index.json`：输入、版本目录与 APK 哈希。
- `dependency-inventory.json`：exact coordinates、版本、许可 provenance、
  source/notice 状态。
- `packaged-inventory.json`：四个当前 APK 的完整 ZIP entry ledger。
- `license-gap-matrix.json` / `engine-asset-gap-matrix.json`：缺口与 A–D 状态。
- `manifest.json` + `manifest.sha256`：payload 文件哈希与 manifest 哈希。
- `commands.jsonl`：明确可跟踪的机器记录；不使用被根 `.gitignore` 匹配的 `*.log`。
- `build-console.txt`：合并 stdout/stderr，含离线命令、`BUILD SUCCESSFUL`、task 与 exit 标记。
- `.gitattributes`：固定全部 L1 文本扩展为 `eol=lf`，JSONL / SHA-256 显式覆盖；
  仅对 `build-console.txt` 禁用 `blank-at-eol` whitespace 检查，不修改原始输出。
- `build_l1_evidence.py`：仅从本地 worktree / build / 既有 Q03 文件重建派生 JSON。
- `verify_l1_evidence.py`：默认全量校验；`--self-test` 运行 24 项正负契约。
- verifier 递归枚举 L1 根下全部普通文件；Git 状态只接受“全部由当前 `HEAD` 跟踪”
  或“全部是普通 `git add -n` 候选且未被 ignored”两种完整形态。嵌套 ignored、
  混合、遗漏、manifest 未覆盖及 tracked 模式的 non-HEAD 文件均失败，不使用
  `git add -f`。

## 边界与下一步

当前结论仅为 `L1_PARTIAL`。所产出的许可文件不在 APK 内，source body 不含绝对个人
路径，APK 未被复制进证据目录。下一步应由负责人指定 fresh scoped reviewer 只读检查
`manifest.json`、本 README、三份 inventory、两张 gap matrix、verifier 与 build attempt；
在 Q03 用户决策及 L2/L3 授权前，不得联网补 B1/D1、选择 voice/model、设备 runtime、
生产实现、G2、独立 QA 或 Release。
