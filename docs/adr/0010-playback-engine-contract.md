# 播放引擎抽象接口契约

## Status

Accepted

## Context

`player/core` 一直只有 domain 模型，没有引擎接口；`PlayerViewModel`（4500+ 行）面向 media3 `Player` 编程，mpv 的能力靠 3 处 `as? MPVPlayer` 强转泄漏。结果是内核差异没有落点：「mpv prepare 先载 0 号条」「mpv 不支持随机播放」这类事实只能散落在 ViewModel 的 `playerBackend == "mpv"` 分支与注释里，后续修 1.4.1 遗留（F2 重载首集、跨集重试落回上一集）时无处安放。

2.0 的 W2 走 strangler 路线：T4 只落接口、适配器、工厂与契约测试，**不改 ViewModel 的调用点**（26 处 mpv 专属触点迁移归 T5），也不改任何错误 / 状态流转。

## Decision

1. `player/core`（`com.zhangwenkang.cinefin.player.core.playback`，不依赖 media3）定义：
   - `PlaybackEngine`：`capabilities: EngineCapabilities`、`backendName: String`、`prepare()` / `play()` / `pause()` / `seekTo(positionMs)` / `seekTo(itemIndex, positionMs)` / `setRate(rate)` / `release()`；伴生常量 `BACKEND_EXOPLAYER="exoplayer"` / `BACKEND_MPV="mpv"` 作为内核标识的唯一来源（`PlayerHolder.BACKEND_*` 改为引用它们，取值不变）。
   - `EngineCapabilities`：`canPlayHls`、`supportsSoftwareDecode`、`supportsSubtitleStyling`、`supportsAudioDelay`、`supportsSideloadedSubtitles`、`supportsShuffleMode` 六个必填布尔位。
   - `EngineError(kind: EngineErrorKind, message: String, cause: Throwable?)`，种类枚举 `SOURCE_UNRESOLVED / SOURCE_START_FAILED / IO_FAILED / DECODING_FAILED / UNKNOWN`。
2. `player/local` 定义 `internal` 适配器：`engine/exo/ExoPlaybackEngine`（包 `ExoPlayer`）、`engine/mpv/MpvPlaybackEngine`（包 `MPVPlayer`）。接口方法**逐一直接委托**，并各自暴露 `media3Player: androidx.media3.common.Player` 供 MediaSession / UI / 既有代码取用。
3. `PlayerHolder` 不再直接持有 `Player`：改为持有 `engine: PlaybackEngine`，经 `PlaybackEngineFactory` 创建；`currentEngine` 只读暴露，`existingPlayer` 返回 `engine.media3Player`。既有调用方签名与语义零改动。
4. **映射入口**：两个内核最终都经 `Player.Listener.onPlayerError(PlaybackException)` 上报，因此只保留一个入口 `engine.EngineErrorMapper.fromPlaybackException()`（含纯函数 `kindForErrorCode(Int)`）。本步不接入 `ErrorPolicy`，接入归 T5。
5. **能力位是现状快照，不是目标能力**：任何一位翻转都需要真机或契约证据，并同步本文的取值表。

### 能力位取值表（2026-11-18）

| 能力位 | ExoPlayer | mpv | 依据 |
|---|---|---|---|
| `canPlayHls` | true | true | 依赖含 `media3-exoplayer-hls`；mpv 原生支持 m3u8 |
| `supportsSoftwareDecode` | true | true | `CinefinRenderersFactory` 扩展渲染器 + 解码器回退；mpv `hwdec=no` |
| `supportsSubtitleStyling` | true | true | Exo 走自绘层（`PlayerEvents.SubtitleStyleChanged` → `PlayerActivity.configureSubtitleStyle`）；mpv 走 libass `applySubtitleStyle` |
| `supportsAudioDelay` | true | true | Exo `AudioDelayProcessor` 挂在音频链；mpv 写 `audio-delay` 属性 |
| `supportsSideloadedSubtitles` | true | true | Exo 字幕管线 + `SideloadedSubtitleStore.INDEX_BASE`；mpv `addSubtitleFile` |
| `supportsShuffleMode` | true | **false** | `MPVPlayer.setShuffleModeEnabled` 仍是 `TODO`，`getAvailableCommands()` 不含 `COMMAND_SET_SHUFFLE_MODE`；ViewModel 据此禁用随机，`PLAYER_PLAN.md` 已记该限制 |

唯一差异是 `supportsShuffleMode`：其余五位在当前实现里两边都成立，测试 `EngineCapabilitiesTest` 以「exo.copy(supportsShuffleMode=false) == mpv」钉住这一点，出现新差异必须显式改测试与本文。

## mpv `prepare()` 怪癖的归属

`MPVPlayer.prepare()`（`MPVPlayer.kt:1894`）在非保源路径下固定先用 `loadFileCommand(first, "replace")` 载 0 号条，再用 `playlist-play-index` 跳回目标条目，因此重试 / 引擎重建时首集会被真实重载。本步 `MpvPlaybackEngine.prepare()` **原样委托**，怪癖在 KDoc 里标为已知问题并指向 T5 锚点；根治（以 `currentMediaItemIndex` 为准、不再先载 0 号条）在实现内部完成，接口面不出现任何「首条 / 重载」语义。

同理，`seekTo(itemIndex, positionMs)` 的重载存在就是为了让重试路径携带条目上下文；接口不提供「prepare + 裸 seekTo(position)」这类会丢条目上下文的组合语义。

## Considered Options

- 让 `PlaybackEngine` 直接继承 media3 `Player` 接口：拒绝。会把 media3 具体实现拖进 `player/core`（当前零 media3 依赖），也保不住「内核怪癖在实现内部收口」。
- 一次把 ViewModel 的 26 处 mpv 触点改为面向接口：拒绝。属 T5 体量，无法在单步证明行为零变化。
- 把能力位做成运行时探测（例如查 `availableCommands`）：拒绝。能力位是内核的静态事实，运行时探测会把「当前实例状态」混进「内核能力」，也让 JVM 单测无法断言。
- 错误映射放在 `player/core`：拒绝。映射需要 media3 的 `PlaybackException` 与错误码常量，core 应保持零 media3 依赖。

## Consequences

- 新增一层间接但行为零变化：`PlayerHolder` 返回的仍是同一个 media3 实例，事件、监听与生命周期未动。
- `PlayerHolder` 的实例事实从「`Player` + 后端字符串」收敛为「引擎 + 后端字符串」，两者仍由 `install()` / `release()` 成对写入。
- 引擎目录（`engine/exo`、`engine/mpv`）成为内核差异的落点：T5 的 F2 修复、错误细分、能力差异收敛都只改这里，不再回到 ViewModel。
- `EngineError` 目前无生产消费者，只有映射入口与单测；T5 接入 `ErrorPolicy` 后才有真实流转，因此本步不派「错误行为」真机验证，只按红线跑 K60 起播/暂停/续播轻回归。
