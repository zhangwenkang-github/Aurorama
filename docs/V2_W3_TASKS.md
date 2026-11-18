# 2.0 W3 · 导航与新 UI 骨架（任务卡 · 待备）

> 状态：`READY`（2026-11-18 W2 收口后建卡；任务未派，开工先做现状调查再定稿任务）
> 依据：`docs/V2_REQUIREMENTS.md` §10（V2-NAV）与 §15 波次（W3 ← W1）；设计唯一权威 `docs/V2_DESIGN.md`。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`，HEAD `edcf7a68`（已推送）。
- W2 已交付：架构四件套（`PlaybackSessionOwner` / `ErrorPolicy` / `StartupStateMachine` / `ProgressWatchdog`，player/local/session/）+ `PlaybackEngine` 引擎抽象（player/core，ADR-0010）+ 1.4.1 三遗留根治 + app/tv 恢复编译。
- **W2 遗留（开工第一件事核查）**：
  1. ~~master Build CI 仍红~~ **已解决（2026-11-18 晚）**：T6 `ce2febca` 已 cherry-pick 回 master（`edfb0e8c`），Build/Format 转绿；Unit Tests 暴露的第三个时序型 flaky（`:core` `DownloaderImplRecoveryTest` sidecar 用例）已根治并 cherry-pick（分支 `b89653ac` / master `a1e2951c`）——根因是意图处理器 fire-and-forget 未被 await，修法 = `awaitIntentProcessingIdle()` + 测试用 PausingDispatcher 确定性同步，删除裸 `delay(200)`。开工时 `gh run list --branch master --limit 3` 复核三条 CI 全绿即可翻篇。
  2. **mpv 真机补验 PENDING（性质已变）**：TLS 层已修通（mbedtls 3.6.6 默认构建最高 TLS1.2；`-0x7280`=服务端致命 alert；用户 2026-11-18 晚的服务器端修复生效后 TLS1.2+ECDHE-ECDSA 握手正常、设备有 ESTABLISHED 连接）。剩余卡点是 Jellyfin 媒体管线（PlaybackInfo 源解析失败/缓冲不出帧，服务器配置变动期），等服务器稳定后跑 mpv 起播/重试/跨集复验。
  3. VM 层 `retryPlaybackAt` 的判别性单测缺 harness（T5-β 交接已注明）。
  4. 服务器瞬时态观察：2026-11-18 晚 jellyfins 出现过"API 200 但剧集 PlaybackInfo 解析失败"的窗口（用户正在改服务器配置），与代码无关，复验时注意区分。
- **环境备注**：全量构建带 `--max-workers=2`（16G 机器 OOM 前科）；构建前 `export JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; export GRADLE_USER_HOME='F:\Develop\gradle-home'`；CI（Build/Unit Tests/Format）仅 master push 与 PR 触发；K60=`8e875894`；jellyfins 只读。
- **W1 遗留随 W3 处理**：图标第二批（约 66 个）、形状 token（K1 §3.2 圆角）随页面换肤切换、旧 Prism 中性常量清理、light aurora3 对比度调值。详见 `docs/V2_W1_TASKS.md` 交接节。

## 新会话开工提示词（可直接粘贴）

```
继续 Aurorama 2.0.0 开发。你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W3_TASKS.md 状态块（W2 遗留核查清单）
→ docs/V2_REQUIREMENTS.md §10 + docs/V2_DESIGN.md → 记忆库 MEMORY.md。
本波任务：W3 导航与新 UI 骨架。第一步你亲自做导航现状调查（app/phone 导航骨架、抽屉/侧栏现状、
modes/* 路由），对照 V2_REQUIREMENTS §10 与 V2_DESIGN 定稿 W3 任务卡，再派 deepseek 执行。
设计决策（视觉/布局/动效）由你亲自定稿，deepseek 零设计权。
纪律：每任务一个子代理一个 commit；门禁=相关模块单测+ktfmtCheck+构建（--max-workers=2）；
核心链路 K60 真机验证。另：先核查 docs/V2_W3_TASKS.md 状态块里的 W2 遗留（第三个 flaky 是否已 cherry-pick、
mpv 媒体管线复验、retryPlaybackAt 单测 harness）。
```

## W3 任务草案（待开工调查后定稿）

| 草案 | 内容 | 依据 |
|---|---|---|
| T0 | 导航现状调查 + 任务卡定稿 | 本卡状态块 |
| T1 | 导航骨架按 §10 重构（页面级 K1 换肤载体） | §10 + V2_DESIGN |
| T2 | 图标第二批（约 66 个旧图标） | W1 交接 #5/#6/#8 |
| T3 | 形状 token 切换 + 页面级换肤首批 | W1 交接 #9 |
| T4 | 旧 Prism 中性常量清理（reader 引用收口后） | W1 交接 #2 |
