# G1-S4-A-Q03-SBOM

本目录冻结 Q03 的派生 release-runtime SBOM 子证据。状态为 `PARTIAL`：已经用 S4-A
冻结输入生成真实的 CycloneDX 1.6 组件清单，并从冻结的 1.4.0(5) arm64 APK 复算 14 个
native ZIP entry；但没有 2.0 候选 APK/AAB、完整父子边、native 精确许可 / notice 或
runtime 兼容证据，因此不是 canonical 2.0 release SBOM，也不关闭 Q03。

## 产物

- `sbom.cdx.json`：305 个组件 = 1 个应用根组件之外，296 个 external Maven 组件 +
  9 个 Gradle 项目组件；35 direct external / 261 transitive external。
- `native-inventory.json`：冻结 arm64 APK 的 14 个 `lib/**` 文件、尺寸、SHA-256 与
  parent Maven 归属；Gradle dependency graph 自动表达其中 0 个嵌套 native 文件。
- `license-evidence-contract.json`：raw annotation state 到互斥 primary grade 的唯一映射真源。
- `option-matrix.json`：现有能力、派生清单、CycloneDX / SPDX 插件和 APK 后处理路径。
- `report.json`：覆盖、阻塞、P0–P3、Q03 子项和下一步。
- `build-sbom.ps1` / `validate-sbom.ps1`：只写本目录的确定性重建与验证入口。
- `commands.log`：原始命令日志；`commands.txt` 是应对仓库 `*.log` ignore 的逐字节可跟踪副本。
- `manifest.json`：输入 / 证据文件尺寸和 SHA-256；manifest 自身不列入自身条目。

## 重建与验证

```powershell
pwsh -NoProfile -File .\.planning\v2-g1\G1-S4-A-Q03-SBOM\build-sbom.ps1
pwsh -NoProfile -File .\.planning\v2-g1\G1-S4-A-Q03-SBOM\validate-sbom.ps1
```

生成器先校验三份 S4-A JSON、JSpecify POM、冻结 APK 与分类合同的固定 SHA-256。SBOM 不写
时间戳或随机 UUID，因此输入不变时可重复生成相同字节。外部 schema 校验使用
`https://cyclonedx.org/schema/bom-1.6.schema.json`，本轮实测 `valid=true`；验证器本身不联网。

## 真实性边界

- 296/296 external 组件都有许可标识或明确名称。互斥主分类为 46 `EXACT_POM`、246
  `FAMILY_ONLY`、4 `MIXED_EXACT_FAMILY`、0 `UNKNOWN_BLOCKED`；另保留原始 annotation
  状态计数，两者不能混成一张互斥状态表。Family 与 mixed 均不冒充 exact。
- S4-A 冻结文件没有父子边拓扑，所以本 SBOM 只声明 direct/transitive 分类，不伪造
  root/transitive parent 关系；CycloneDX `compositions.aggregate=incomplete` 明示覆盖不完整。
- `libav*`、`libc++_shared.so` 等嵌套 native 许可仍为 `BLOCKED`；Media3 1.11.1 与
  Jellyfin FFmpeg decoder 1.9.0+1 的 JNI/API/native ABI 兼容也不是 SBOM 能证明的。
- PDFium、Sherpa-ONNX / Piper / TTS voice 不在冻结 runtime；未来引入后仍需 Maven 图之外的
  native / model asset 清单。
- 当前 `AboutLibraries` 只能作许可 UI / 元数据辅助，不能替代 CycloneDX / SPDX；正式 candidate
  SBOM 需要 G2 构建集成或等价的后处理闭环。

## Q03

可关闭的仅是“S4-A 快照存在可复现组件清单、PURL 和 APK native 文件台账”子项。canonical
2.0 candidate SBOM、native notice/source、runtime 兼容、TTS/PDF runtime 与用户决策仍 Open。
