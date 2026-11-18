# 播放会话事实收进 PlaybackSessionOwner

## Status

Accepted

## Context

`PlayerViewModel` 已膨胀到 4500+ 行，同时承担：播放会话事实、UI 投影、播放器实例访问、协程调度、错误恢复与队列编排。其中「播放会话事实」——快照代次、异步结果令牌、会话状态、起播源意图——散落在 ViewModel 的多个 `var` 里，任何读取方都能写，晚到的异步结果是否过期只能靠人肉比对字段。

2.0 的 W2 波次按 strangler 方式拆解这个 god object：每一步只做纯搬移 + 委托，不改变任何可观察行为，为后续收编会话规则与 UI 投影留出边界。

## Decision

1. 新增 `player/local/.../session/PlaybackSessionOwner.kt`，作为下列可变事实的唯一所有者，外部只读：
   - `playbackSnapshots: PlaybackSnapshotState`
   - `activePlaybackToken: PlaybackRequestToken?`
   - `playbackSessionState: PlaybackSessionState`
   - `lastSourceIntent` / `failedSourceIntent: SourceResolutionIntent?`（`SourceResolutionIntent` 随之从 ViewModel 的 `private` 嵌套类改为 `session` 包的 `internal` 顶层类型，引用点全部经 import，行为不变）
2. 快照原语整体迁入 owner：`resetPlaybackSnapshots`、`beginMediaSnapshot`、`ensureMediaSnapshot`、`advanceCurrentStreamGeneration`、`applySourceSnapshot`、`applyCurrentStreamSnapshot`；代次单调递增与 stale token 拒绝语义原样保留。
3. UI 发布副作用不进 owner：owner 通过构造注入的 `onSnapshotsChanged: () -> Unit` 通知，ViewModel 注入 `::publishPlaybackSnapshots`；通知次数与旧实现的发布点逐点一致（包括 stale 提交也照旧发布一次、无 identity 的 `advanceCurrentStreamGeneration` 不发布）。
4. 以下仍留在 ViewModel，只读投影：`publishPlaybackSnapshots` / `refreshPlaybackSession` 的 UI 投影、`refreshCurrentStreamSnapshot` 的 `PlayerAccess` / `MPVPlayer` 读取与协程、依赖 media3 `Player` 的 `currentPlaybackSessionKey`。owner 保持纯 Kotlin，不 import `android.*` / `androidx.*`。
5. 后续扩展点：
   - T2：把 `PlaybackSessionRules.advance` 的写入与会话键计算下沉到 owner，消除 ViewModel 对 `updatePlaybackSessionState` 的直接调用；
   - T3：把 `publishPlaybackSnapshots` 的 UI 投影改成 owner 暴露的只读流，ViewModel 只做收集；
   - 更远：owner 依赖的 `presentation.PlaybackSessionState`（其字段又引用 `PlayerViewModel.PlaybackRecovery`）随后续任务迁到 `session` 包，消除 session → presentation 的反向依赖。

## Considered Options

- 一次性重写 ViewModel：拒绝。无法证明行为零变化，回归面覆盖播放、seek、字幕、队列所有链路。
- 只在 ViewModel 外包一层 facade、状态仍留两份：拒绝。双写会引入新的不一致来源，也不满足「唯一所有者」目标。
- 把 UI 发布也放进 owner：拒绝。owner 会开始依赖 `StateFlow` 与 Android 侧投影模型，无法保持纯 Kotlin，也无法作为后续跨模块下沉的边界。

## Consequences

- ViewModel 净减约 70 行，会话事实只有一个写入点，晚到结果的有效性判定收敛到 owner 内部。
- 本步可观察行为零变化：UI 投影内容、发布时序、公开签名、playerError 写点均未改动；因此不需要真机验证，只用既有单测 + 新增 owner 单测守住语义。
- 临时保留一处反向依赖（owner → `presentation.PlaybackSessionState`）。这是任务卡「类型原地复用、不搬家」的直接结果，已列为 T2/T3 的清理项，不视为稳定架构。
