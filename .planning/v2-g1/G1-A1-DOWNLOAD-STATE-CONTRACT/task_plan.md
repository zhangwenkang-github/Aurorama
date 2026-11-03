# G1-A1 下载状态等式合同计划

## 目标

产出只读静态证据，冻结下载 identity/key、跨 DB/sidecar/Worker/通知/UI/删除回执的状态等式与允许/禁止转移，并用可执行 fixture 证明关键负例。

## 边界

- 唯一工作目录：`F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama`
- 唯一写域：`.planning/v2-g1/G1-A1-DOWNLOAD-STATE-CONTRACT/`
- 不修改生产 Kotlin / Gradle / resources / schema；不接触设备、服务器、播放；不 commit / push。
- 静态 fixture 只能证明合同、归一化和转移逻辑，不能替真实 Room、WorkManager、通知或文件系统映射作证。

## 阶段

1. `complete` — 锁定 HEAD、来源章节与现有下载状态模型，记录预存脏工作树。
2. `complete` — 定义 canonical identity/key、因果字段、允许/禁止转移与 capability gap。
3. `complete` — 建立用户承诺矩阵与 runtime 未覆盖清单。
4. `complete` — 编写静态 fixture、property/负例测试，并证明好 fixture 退出 0、故意破坏退出 1。
5. `complete` — 生成 JSON 报告、manifest、短 README、commands.log，校验 JSON/hash/LF/敏感值。

## 验收命令

```powershell
pwsh -NoProfile -File .planning/v2-g1/G1-A1-DOWNLOAD-STATE-CONTRACT/test_contract.ps1
pwsh -NoProfile -File .planning/v2-g1/G1-A1-DOWNLOAD-STATE-CONTRACT/test_contract.ps1 -Mutation broken-invariant
```

第二条命令预期退出 1；任何成功声明都必须基于实际退出码与 `commands.log`。
