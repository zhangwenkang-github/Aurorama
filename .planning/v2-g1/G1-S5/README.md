# Aurorama 2.0 · G1-S5 视觉方向证据包

状态：`READY_FOR_V2_Q_G1_04_USER_DECISION / Q04_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED`

本目录只是 `V2-Q-G1-04` 的用户决策输入，不是 Q04 已确认、G1 PASS、实现、独立 QA 或发布证据。
权威基线：分支 `codex/v2-integration`，HEAD `397ff4f0a3d8378c91ee283da317c5263cc80285`；目录级 `.gitattributes`
固定 `text` / `eol=lf`，保证 checkout 与 manifest 按字节复现。

## 建议读序

1. `decision-brief.md`：先看推荐整包、备选、取舍和待确认问题。
2. `index.html`：按主题 → 九交互 → RWD → 状态 → 无障碍浏览独立 SVG 帧。
3. `manifest.json`：机械核对证据 ID、来源、路径、MIME、尺寸与 SHA-256。
4. `data/`：读取可复算的 token、RWD、状态、对比度、名称映射和源文档快照。

## 覆盖

- 五主题：Prism、Lumen、Nocturne、Monolith、系统动态色，5/5 独立风格帧。
- 九交互：首页、媒体库 / 视频详情、音乐 / 队列、书架 / 阅读器、下载、搜索、设置 / 主题、播放控制、SyncPlay 候选，9/9 四段复核。
- RWD：Compact 360、Medium 600、Expanded 840、Large 1200、ExtraLarge 1600，另有 700dp 折叠 / 窗口帧，共 6 帧。
- 状态：10 个页面组 × 6 类状态 = 60 条映射；另有组件与权限状态板。
- 无障碍：130 个对比度 pair、18 个动作名称映射、12 项适用检查。静态失败：全部通过。

## 复现

终端必须使用 PowerShell 7：

```powershell
pwsh -NoProfile -Command "python .planning/v2-g1/G1-S5/scripts/build_evidence.py"
pwsh -NoProfile -Command "python .planning/v2-g1/G1-S5/scripts/verify_evidence.py --expected-dirty docs/DECISIONS.md --expected-dirty docs/TASKS.md --expected-dirty docs/V2_PLAN.md"
```

生成器只写本目录；不使用外部包、远程素材、设备、服务器、认证或生产资源。

## 未覆盖

- Compose / Kotlin 实际 token 落地、字体真渲染、Android 设置与设备真实布局。
- TalkBack、Switch Access、Voice Access、键盘、指针、权限弹窗和动态色运行时。
- S5 之后的每主题 3 界面明暗稿、独立视觉 QA、S6、G2、Release。
