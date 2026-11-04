# G1-L1 Sherpa-ONNX 2.0.1 Artifact 静态取证

1. 锁定唯一事实 worktree、branch、HEAD 与 clean；只写本目录，二进制仅落系统临时目录。
2. 仅按用户 allowlist 对 3 个精确 GitHub URL 做 HEAD/GET，逐跳验证 host/path、长度与 SHA-256。
3. 以 ZIP/TAR 清单、短许可文本、ELF program header 解析与哈希生成 AAR/source/16 KB/许可来源证据。
4. 先运行 verifier 负例并取得 RED，再生成证据、修复 verifier 直至 GREEN；不声称 runtime / packaged notice 完成。
5. 收口核对 LF、manifest、无二进制与写域，记录 P0-P3、未覆盖项及 fresh reviewer 下一步。

状态：`STATIC_AUDIT_COMPLETE / DOWNLOADS_MATCH / ELF_16K_STATIC_PASS /
LICENSE_SBOM_PARTIAL / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED`。

受控重试在 58.7 秒内完成三个 exact artifact 下载；两个 AAR 的 size/SHA-256 与用户固定值完全
一致。静态审计覆盖 AAR/JAR、Manifest、ABI/native、source archive、ELF PT_LOAD 16 KiB
alignment、license/source/SBOM 输入。AAR 不含 LICENSE/NOTICE entry，ONNX Runtime 的
完整 upstream commit、third-party notice 闭包与最终 packaged notice 仍未证明；16 KiB PASS
仅为 ELF 静态口径，不是设备 runtime PASS。详见 `CHECKPOINT.md`。
