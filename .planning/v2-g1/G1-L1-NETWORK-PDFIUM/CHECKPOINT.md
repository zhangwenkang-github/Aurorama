# G1-L1 PDFium artifact 审计检查点（2026-11-04）

状态：`STATIC_AUDIT_COMPLETE / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED`

## ACTIVE

- 固定分支 `codex/v2-integration`、HEAD `506fe75fddd40188ec116ca19496a09778868725`；开工 clean。
- 仅按用户批准的 `repo1.maven.org` / `io.legere` 三个 2.0.3 坐标完成 AAR、
  sources.jar、POM、`.sha256` GET；无 redirect、设备、服务器、runtime、QA、commit 或 push。
- AAR SHA-256 与给定值、文件 size 与初始 HEAD length 均已核对；本地哈希与可用远端
  `.sha256` 一致，404 项保留为缺失。
- ELF 静态结果：arm64-v8a=PASS；armeabi-v7a=FAIL；x86=FAIL；x86_64=PASS。
- source：`SOURCE_INCOMPLETE`；notice：
  `MISSING`；exact PDFium source revision `UNPROVEN`。

## 未完成 / 不相容主张

- ELF 静态对齐不是 16 KB runtime PASS；未启动 Android 或加载 native。
- 未验证 PDF 渲染、Range、页面 seek、搜索、批注、内存、性能或设备。
- 未取得 PDFium native corresponding source、DEPS、GN、complete third-party notice
  bundle 或 written source offer。
- Q03 仍未关闭，G1 仍未通过，L2/L3 仍锁定。

## 下一步

角色：fresh scoped reviewer（全新只读会话）。

第一条只读动作：读取 `manifest.json`、`manifest.sha256`、`artifact-index.json`、
`elf-inventory.json`、`license-source-matrix.json`，再运行 verify。

停止条件：manifest/hash/LF/计数不可复算，AAR hash/size 漂移，出现 redirect、binary
入证据、source/notice 过度主张，或把静态 ELF PASS 写成 runtime/G1/Q03 PASS。
