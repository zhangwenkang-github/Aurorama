# G1-L1 Sherpa-ONNX 2.0.1 Network Artifact 检查点

**身份**：Sherpa-ONNX 2.0.1 取证与许可审计工程师。
**状态**：`STATIC_AUDIT_COMPLETE / DOWNLOADS_MATCH / ELF_16K_STATIC_PASS /
LICENSE_SBOM_PARTIAL / RUNTIME_UNVERIFIED / Q03_OPEN / G1_NOT_PASSED`。
**事实目录**：`F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama`；
HEAD `506fe75fddd40188ec116ca19496a09778868725`，branch `codex/v2-integration`。

## 下载与重定向

- 同一 owner 受控重试总耗时 58.7 秒；全部请求使用 HTTP/1.1，最多一次已验证官方 redirect，
  未访问第四个 URL、其他 host 或并行代理。
- 三个原始 URL 的 HEAD 均为 302；两个 AAR 只到
  `release-assets.githubusercontent.com`，source 只到 `codeload.github.com`。
- 签名 query、JWT 与临时下载 URL 不进入证据；只保留 host/path/filename/tag 校验。

| artifact | GET | bytes | SHA-256 |
|---|---:|---:|---|
| `sherpa-onnx-2.0.1.aar` | 200 | 49,441,341 | `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` |
| `sherpa-onnx-static-link-onnxruntime-2.0.1.aar` | 200 | 38,577,050 | `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575` |
| `sherpa-onnx-2.0.1.tar.gz` | 200 | 11,933,876 | `24ecc0fccf6344c2556e7c229c849e9f61bf9456b915615197d85f35e3f177e7` |

源 tar 不嵌入 `.git`；任务给定 commit `af2d8d81dbfa61c4cea4c568da78f3982edca005`
是外部输入，不能仅凭 tar 字节独立映射到该 commit。

## AAR / ABI

- standard AAR：26 entries；static AAR：15 entries；两者 `classes.jar` 均 245,045 B，
  SHA-256 `0ee4991c56c19ef5a8a573b01891193d7298ffe7ebcc68f42b944361d8869d93`。
- 两者 `AndroidManifest.xml` 均 203 B，
  SHA-256 `4e4afc406f4551293d45ef95eb63516774d0fa48bb0db415f6761b672ac9445a`。
- standard ABI native 数：arm64-v8a 4、armeabi-v7a 4、x86 4、x86_64 4。
- static ABI native 数：arm64-v8a 1、armeabi-v7a 1、x86 2、x86_64 1。
- standard 每个 ABI 都含独立 `libonnxruntime.so`；static 的 ARM/ARM64/x86_64 通过
  `libsherpa-onnx-jni.so` 静态承载 ORT，不在 `DT_NEEDED` 中列 ORT；static x86 仍包含
  独立 `libonnxruntime.so`，不是四个 ABI 完全同构的 static 包。
- source CMake 声明 Android ONNX Runtime 预编译包版本 `1.28.2`，并给出各 ABI
  package URL 与 SHA-256；prebuilt package 本体及上游 commit/notices 不在三件 artifact 中。

## ELF 16 KiB 静态 alignment

本机未找到 `llvm-readelf`，使用可复现 Python ELF32/ELF64 program-header parser，并以 GNU
`readelf -lW` 交叉核对。每条 PT_LOAD 要求 `p_align >= 0x4000` 且 offset/vaddr 同余。

- `arm64-v8a`：PASS（全部 PT_LOAD `0x4000`）。
- `armeabi-v7a`：PASS（全部 PT_LOAD `0x4000`）。
- `x86`：PASS（全部 PT_LOAD `0x4000`）。
- `x86_64`：PASS（全部 PT_LOAD `0x4000`）。

这是 **static ELF alignment PASS**；不证明 16 KiB 页设备可加载、不证明模型推理或 runtime。

## Source / License / SBOM

- source tar：9,016 members、6,626 files、78 个 CMakeLists、799 个对应源候选；
  `third_party/` 为空，说明精确 transitive source closure 依赖构建时下载项，未随 tar 冻结。
- source 根 `LICENSE` 为 Apache-2.0；无根 `NOTICE`。
- 两个 AAR 的 LICENSE/NOTICE entry 数均为 0，不得从 AAR 获得最终通知材料。
- `license-source-matrix.json` 记录 Apache-2.0 code、ONNX Runtime 1.28.2 prebuilt inputs、
  third-party notice 与 Aurorama GPL-3.0 corresponding source 义务。
- `finalPackagedNoticeComplete=false`、`thirdPartyClosure=UNRESOLVED`、
  `readyForFinalPackagedSbom=false`。Q03 仍 Open，G1 仍 NOT_PASSED。

## P0-P3

- P0：无。
- P1：`P1-ONNXRUNTIME-NOTICE-PROVENANCE`，AAR 无许可/notice，ORT upstream commit、
  完整构建配置与 transitive notices 未冻结，阻断最终 packaged notice。
- P2：`P2-SOURCE-TAG-COMMIT-UNBOUND`，source tar 不嵌入 Git 元数据，给定 commit 只能作为
  外部声明，不能由本轮字节独立证明。
- P3：无。
- BLOCKED：无；本静态审计已完成，但许可/SBOM 与 runtime 仍有明确开放门。

## 验证与下一步

- verifier 8/8 负例 GREEN；LF 属性、manifest 覆盖、无二进制与最终 verifier 均须在收口
  命令现场读取结果，不在本文件自引用 hash。
- 未运行 Gradle、设备、ADB、账号、服务器、runtime、独立 QA、commit、push 或 Release。
- 下一步由 fresh scoped reviewer 只读复核 artifact hash、redirect 证据、AAR/ELF 清单、
  license/source 缺口与 verifier；不得把静态 16 KiB PASS 或本证据包升级成 runtime/Q03/G1 PASS。
