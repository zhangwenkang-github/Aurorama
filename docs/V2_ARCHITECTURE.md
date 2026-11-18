# Aurorama 2.0 目标架构（方向文档）

> 状态：W0 定稿（2026-11-18）　分支：`codex/v2-integration`
> 本文档是 2.0 架构的唯一方向来源；具体任务卡在实施波次文档中展开。
> 事实依据：`player-arch-anatomy` 与 `module-boundary-audit` 两份调查报告（2026-11-18，下文行号对应 HEAD `04ed3d94`）。

## 1. 诊断：为什么会"修一个 bug 出另一个"

以 1.4.1 三个真机缺陷（起播悬挂、假定位超时、切集静默回退）为样本，根因是同一结构病：

**同一个事实有 2–3 个独立 owner，冲突全靠"事后比对快照"撤销。**

| 事实 | 现有 owner 数 | 后果实例 |
|---|---|---|
| 播放会话代次（generation） | 4 条路径可推进（起播/attach/换集/切轨） | 在解析中的起播被判过期，静默丢弃 |
| 进度看门狗 terminal | fallback / recovery / UI 三方各持一份 | 切集等待期被 20s 兜底劫持，回退上一集 |
| `fileLoaded` 布尔 | 兼作 UI 首帧、错误分档、恢复耗尽判定 | 错误卡刚发布就被 READY 清掉 |
| 播完决策 | `END_OF_MEDIA_ITEM` 与 `ENDED` 两条回调 | 停帧/关页行为不一致 |
| 错误状态 `playerError` | 7 处写 / 5 处读 | 错误入口在竞争中丢失 |

次要结构问题：

- **无双内核抽象**：`player/core` 只有模型没有 `PlaybackEngine` 接口；ViewModel 面向 media3 `Player` 编程，mpv 能力靠 3 处强转泄漏（VM:848/937/3464）。`mpv prepare() 重载队列首集`（F2）这类隐患无处安放。
- **PlayerViewModel 4544 行、20 项职责**：字幕面板 850 行、音轨、队列展开、片段跳过、进度上报全部内联。
- **`core` 是合并模块**（设计系统+下载器+Worker+DI）且下依赖 `data`，modes 经 core 传递可见 data 实现。
- 影视 UI 75 个文件滞留 `app/phone` 未迁入 `modes/film`。
- SDK 枚举泄漏 32 文件、DAO 直连 ViewModel 3 例（收敛成本低，列入 W9 清扫）。

## 2. 目标原则（按优先级）

1. **单一所有权**：每个可变事实（会话代次、seek 状态、错误状态、恢复状态、播放位置权威源）有且只有一个 owner 类可写；其他方只读投影或提交意图（intent）。
2. **显式状态机**：起播、seek、恢复、错误各为显式状态机，状态迁移只在 owner 内发生；禁止"先改了再说、事后比对撤销"。
3. **意图单向流**：UI/MediaSession/通知只提交 intent（已有 `SeekIntent` 词表是先例），不直调引擎。
4. **引擎抽象**：`player/core` 定义 `PlaybackEngine` 接口（prepare/play/pause/seekTo/setRate/tracks/subtitle/audio delay/style/release），ExoPlayer 与 mpv 各自实现并**收口各自怪癖**（如 mpv prepare 语义）在实现内部。
5. **跨层只走接口**：UI → 域服务接口 → data/player；Jellyfin SDK 类型不出 `data`。
6. **可测试性**：owner/状态机为纯 Kotlin 类（无 Android 依赖），JVM 单测直接覆盖；与引擎的边界用契约测试（现有 `MpvSeekEventRelayTest` 式 harness）。

## 3. 播放器目标结构（W2 实施）

```
player/core
  PlaybackEngine（接口）+ EngineCapabilities + 统一错误模型
player/local
  engine/exo/   ExoPlaybackEngine : PlaybackEngine
  engine/mpv/   MpvPlaybackEngine : PlaybackEngine（含 prepare 不再重载首集：以当前 mediaItemIndex 为准）
  session/
    PlaybackSessionOwner   ← media identity + generation + sourceIntent 唯一可写者
    StartupStateMachine    ← Idle→Resolving→Preparing→Ready|Failed（显式终态）
    ErrorPolicy            ← 唯一 playerError 写者；重试预算/错误码分档集中在此
    ProgressWatchdog       ← 唯一 terminal 产生者；fallback 与 recovery 改为它的订阅者而非平行消费者
  （既有已外置资产保留：PlaybackSeekSink/SeekRequestCoordinator、PlaybackSessionRules、
    PlayerSubtitleController、PlayerAccessInvariant/ExitFinalizationGate）
app/phone
  PlayerViewModel ← 只剩 UI 投影聚合 + intent 转发，目标 ≤1500 行
```

**迁移策略（ strangler 式，禁止大爆炸重写）**：
1. 先抽 `PlaybackSessionOwner` 包住代次与 sourceIntent（1.4.1 三修复的行为保持不变，纯搬移+委托）；
2. 再抽 `ErrorPolicy`（统一 7 处 `playerError` 写点）与 `ProgressWatchdog`（fallback/recovery 改订阅制）；
3. 引 `PlaybackEngine` 接口，先让 Exo/mpv 各自适配现状，再根治 F2；
4. 每一步门禁：`player/local` + `app:phone` 单测全绿 + K60 核心链路真机验证（起播/选集/大拖/错误卡/换内核）。

## 4. 全局模块方向（W9 前逐步）

- `core` 拆分：`core/designsystem`（主题/tokens/组件，**不得依赖 data**）、`core/download`（Worker/下载引擎）、`core/common`。W1 先拆 designsystem（主题引擎落点），其余随波次。
- 影视 UI 迁完：`app/phone/presentation/film` 75 文件 → `modes/film`（W3 随新 UI 骨架一起做，搬移即重设计）。
- SDK 泄漏收敛：W9 机械替换枚举/常量引用为 domain 类型；DAO 直连 3 例改为走仓库接口。
- 新增 `docs/adr/` 记录：引擎抽象接口契约、PlaybackSessionOwner 所有权边界（W2 开工时写）。

## 5. 主题引擎（W1，详见 `docs/V2_DESIGN.md`）

- `CinefinTheme` 增加 `themeId` 参数；palette 从编译期 val 改为数据类注册表 `Map<ThemeId, ThemeTokens>`。
- 打通 `darkTheme`（现状恒 true）与既有 `pref_theme` 设置项；浅色主题从"不可达"变为一等公民。
- Lumen 并行皮肤（52 处包裹）并入主题引擎，取消独立皮肤机制。
- `Color.kt` 旧色板（71 处字面量）评估后删除或并入 tokens，恢复"字面量仅两处"纪律。

## 6. 不变量（任何波次不得破坏）

- 升级不丢用户数据（登录/下载/书签/进度/歌单/偏好/本地库）。
- 生产服务器只读；写测试只用独立可写测试服。
- 门禁：相关模块单测 + ktfmt + 核心链路真机证据。
- 架构红线由代码与契约测试保证，不靠文档呼吁。
