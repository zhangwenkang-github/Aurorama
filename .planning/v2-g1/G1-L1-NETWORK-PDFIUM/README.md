# Aurorama G1-L1 PDFium 2.0.3 artifact 静态审计

状态：`STATIC_AUDIT_COMPLETE / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED`

本目录只记录用户批准的 `repo1.maven.org` 上 `io.legere` 三个 2.0.3 artifact 的静态证据。
二进制 AAR、sources 与 POM 只落在系统临时目录，审计后删除；证据树不含 AAR/JAR/POM/SO。

## 基线、边界与网络

- 事实 worktree 分支 `codex/v2-integration`，HEAD `506fe75fddd40188ec116ca19496a09778868725`，开工 clean。
- 仅 HTTPS、`--max-redirs 0`、`--noproxy "*"`、精确 host `repo1.maven.org`；没有跟随 redirect。
- 仅 GET 以下坐标：`io.legere:pdfiumandroid:2.0.3`、`io.legere:pdfiumandroid-core:2.0.3`、
  `io.legere:pdfiumandroid-api:2.0.3` 的 AAR、sources.jar、POM 及对应 `.sha256`。
- 未运行 Gradle、ADB、设备、账号、服务器、PDF runtime、Range、独立 QA 或 Release。

## Artifact 与 AAR 内容

| Module | AAR SHA-256 | Bytes | Native 文件 | META-INF license/notice |
|---|---|---:|---:|---:|
| `pdfiumandroid` | `1F07A9160C9A5975D6EFFF72404C5E49217D46F2651BFE02C67C8AFB133E02D7` | 377,258 | 0 | 0 |
| `pdfiumandroid-core` | `F80B8D2166F226E0ED0A50B7014CEA91E297952C5E0AC158F10FF62C74D7E318` | 9,787,161 | 8 | 0 |
| `pdfiumandroid-api` | `A47A0CC8210D3D435996CCB47A3C666361F8C7CB774EFBE58AB02AC478731F3F` | 44,512 | 0 | 0 |

- 9 个主 artifact 均 HTTP 200；9 个远端 `.sha256` 中 9 个可用，
  0 个为 HTTP 404 / `MISSING_REMOTE_404`，未猜值。
- 每个 AAR 均检查 `AndroidManifest.xml`、`classes.jar`、JNI `.so` 与直接
  `META-INF` license/notice 路径；文件哈希只记录在 JSON inventory。
- aggregate 与 core/api 的 POM 依赖、classes.jar 重叠和 native SHA 跨模块关系记录在
  `artifact-index.json` 的 `module_relations`。

## ELF 静态 16 KB 对齐

| ABI | 静态 LOAD 对齐 | ELF 数 |
|---|---|---|
| `arm64-v8a` | `PASS` | 2 |
| `armeabi-v7a` | `FAIL` | 2 |
| `x86` | `FAIL` | 2 |
| `x86_64` | `PASS` | 2 |

规则：每个 `PT_LOAD` 必须 `p_align >= 16384` 且
`p_offset % p_align == p_vaddr % p_align`。`PASS` 仅为静态 ELF program-header
结论；不是 Android 设备上 16 KB page-size runtime PASS，也不检查
`GNU_RELRO` 边界。

## Revision、notice 与 source

- Maven artifact version `2.0.3`：`PROVEN`。
- exact PDFium upstream source revision：`UNPROVEN`；native SHA 可固定文件，但不能替代 commit。
- source audit：`SOURCE_INCOMPLETE`；缺失项：
  `PDFium C/C++ native source, DEPS, GN build files`。
- notice obligation：`MISSING`。
- source jar 若只含绑定层 Java/Kotlin，不含 PDFium native source、DEPS、GN，
  不得臆测 corresponding source 已交付。

## 复算

```powershell
python .planning/v2-g1/G1-L1-NETWORK-PDFIUM/verify_pdfium_artifact_audit.py --self-test
python .planning/v2-g1/G1-L1-NETWORK-PDFIUM/verify_pdfium_artifact_audit.py
```

verify 的 9 个负例覆盖 hash、size、redirect、缺失 AAR/POM/source、
ELF UNPROVEN、静态冒充 runtime 与 binary 入证据。

## 非结论

本审计不关闭 Q03、不通过 G1、不证明 runtime load、16 KB 设备启动、Range 首开、
检索/批注、内存、性能或设备兼容。fresh reviewer 必须独立复核；任何 runtime/device
继续受 `L2/L3 LOCKED` 约束。
