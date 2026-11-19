# 2.0 W4 · 视频域（任务卡 · 待备）

> 状态：`READY`（2026-11-19 W3 收口后建卡；任务未派，开工先做现状调查再定稿）
> 依据：`docs/V2_REQUIREMENTS.md` §9（V2-VIDEO）与 §15 波次（W4 ← W2）；架构 `docs/V2_ARCHITECTURE.md` §3；设计唯一权威 `docs/V2_DESIGN.md`。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W4 开工基线 `651cd427`（W3 全部合入，工作树干净）。
- **前置波次结论**：W2 已交付播放器架构四件套（`PlaybackSessionOwner` / `ErrorPolicy` / `StartupStateMachine` / `ProgressWatchdog`）+ `PlaybackEngine` 引擎抽象（Exo/mpv 双适配器，ADR-0010）——§9.1 的 VIDEO-01/02/03 基础已在；W3 已交付导航 IA 决策、K1 主题换肤与形状/图标 token。
- **W3 遗留并入 W4**：9 处非 token 硬编码圆角（含 `PlayerContentTabItem` 10、`PlayerSubtitleOverlay` 6、`ErrorDialog` 28、`LyricsSheet` 8、`BaseBadge` 8、歌词覆盖层 22 等）随 W4 播放器 UI 换肤一并清理。
- **独立播放器遗留（开工先认领，不混入功能任务）**：① 生产 `jellyfins` HTTPS 间歇 `mbedtls -0x7280`（E1 可播、E2 握手失败，TLS 层已定性为 libmpv AAR 内 mbedtls ≤TLS1.2，服务器已修、客户端现象未复验收口）；② `seekSink.retrySeek()` 会从回退后的引擎条目重建目标（与已修的 `retryPlaybackAt` 不同路径，待最小修复）。
- **本次质量门**：相关模块单测 + `ktfmtCheck` + `:app:phone:assembleDebug --max-workers=2` 绿；核心链路 K60（`8e875894`）真机验证，播放链路验证用 jellyfin-test（生产 jellyfins 永久只读，App 播放链路自身请求除外）。
- **环境备注**：`export JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; export GRADLE_USER_HOME='F:\Develop\gradle-home'`；K60 density 420 = 2.625 px/dp、Pad `43af8627` 360dpi = 2.25 px/dp（圆角像素量测按此换算）；CI 仅 master push/PR 触发。
- **SyncPlay（§9.5，SHOULD）**：裁决 5 定为 W4 评估——T0 产出 go/no-go 与技术评估输入，不做预承诺；若 go，独立任务卡。

## 新会话开工提示词（可直接粘贴）

```
继续 Aurorama 2.0.0 开发。你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W4_TASKS.md 状态块
→ docs/V2_REQUIREMENTS.md §9（V2-VIDEO）+ docs/V2_ARCHITECTURE.md §3 + docs/V2_DESIGN.md
→ 记忆库 MEMORY.md（含 playback-troubleshooting：排障先确认设备构建版本）。
本波任务：W4 视频域。第一步你亲自做视频域现状调查（对照 §9 全部 50 条：
W2 已交付的 PlaybackEngine 抽象/双适配器覆盖哪些、1.4.x 手势/字幕/控制层现状、
trickplay/HDR/SAF 本地媒体现状、SyncPlay 评估输入），然后定稿 W4 任务卡再派 deepseek 执行。
设计决策（视觉/布局/动效）由你亲自定稿，deepseek 零设计权。
纪律：每任务一个子代理一个 commit；门禁=相关模块单测+ktfmtCheck+构建（--max-workers=2）；
核心链路 K60 真机验证（jellyfin-test，生产只读）。
另：先认领两个独立遗留——生产 TLS `-0x7280` 复验、`seekSink.retrySeek()` 最小修复。
```

## W4 任务草案（待 T0 调查后定稿）

| 草案 | 内容 | 依据 |
|---|---|---|
| T0 | 视频域现状调查（§9 全 50 条逐条核对）+ SyncPlay go/no-go 评估输入 + 任务卡定稿 | 本卡状态块 |
| T1 | 播放器 UI 与动效：玻璃控制层 K1 换肤、弹簧动效、控制层 4s 自动隐藏、主题跟随 | §9.4（VIDEO-29~35） |
| T2 | 字幕域：语言分组标注内封/外挂/图形、偏移 50ms 步长、seek 重算不残留、样式可调 mpv 实时预览 | §9.2（VIDEO-11~15） |
| T3 | 引擎切换体验：自动切换非打断提示、状态全保留、淡入遮罩、手动入口、tone-mapping 选项、能力探测 | §9.1（VIDEO-04~09、34） |
| T4 | trickplay 缩略图 + HDR 路径（Exo 直通 / mpv tone-mapping 补偿） | VIDEO-17/18 |
| T5 | 手势体系复核：七类手势无冲突、方向锁 8–10vp 仲裁、控制层让位规则 | §9.3（VIDEO-19~28） |
| T6 | 本地媒体 SAF 统一 + 域验收（双内核状态保留、图形字幕、字幕不漂移、非阻断提示） | VIDEO-47~50 |
