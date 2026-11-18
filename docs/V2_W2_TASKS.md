# 2.0 W2 · 播放器架构重构 + 1.4.1 遗留根治（任务卡）

> 状态：`IN PROGRESS`（2026-11-18 开工）　分支基座：`codex/v2-integration`
> 架构唯一权威：`docs/V2_ARCHITECTURE.md` §2/§3。**执行者不做任何设计/架构决策**——任务边界、类名、归属以本卡为准；遇到本卡未覆盖的结构问题，停下来报告，不自行发挥。

## 状态块

- 当前波次：W2 **进行中**　进度：T0 ✅（2/2）· T1 ✅ · T2 ✅ · T3a ✅；T3b、T4–T6 未派
- 基座 HEAD：`196404dc`（T0a `55df7572` · T0b `73f44f0d` · T1 `6d07f3ea` · T2 `c6d5e22d` · T3a `196404dc`，已推送）
- T1 验收（2026-11-18）：`PlaybackSessionOwner`（140 行纯 Kotlin）+ 9 单测；`SourceResolutionIntent` 随迁 session 包（ADR-0009 记录）；VM 净减 62 行；player/local 486 + app/phone 528 用例全绿。
- T2 验收（2026-11-18）：`ErrorPolicy`（158 行纯 Kotlin）统一 7 处 playerError 写点 + 起播预算 + 去重窗口；`PlayerErrorInfo` 提为 session 包 public 顶层类（typealias 不可嵌套，改在 `PlayerControlOverlay.kt` 换 import，app/phone 仅此 1 处）；8 新单测；494 + 528 用例全绿。
- T3a 验收（2026-11-18）：`StartupStateMachine`（110 行纯 Kotlin，5 相位迁移表 + 代次归属）+ 7 单测；`startupInProgress` 布尔删除、8 处读取全改状态机。**已验收的语义偏差**：起播保护窗口右边界从 `play()` 返回后移到 STATE_READY 出画面（更保守，commit body 已录）。K60 真机：起播 PLAYING→暂停 PAUSED→续播 PLAYING、FAILED 分支错误卡、退出复位重进全过。
- **环境阻塞（待用户决策）**：K60 上 mpv 内核 TLS 握手失败（`mbedtls -0x7280`，服务器 ECDSA 证书兼容问题，既有、与 W2 改动无关）→ T4/T5 的 mpv 路径真机验证暂不可用，T3a 回归是改 pref 到 exoplayer 跑的（已还原）。
- 观察项：K60 上 `e22f44af`（齐木楠雄 S00E01）报"暂时没有可播放的媒体源"、重进同集停 BUFFERING 且 recovery attempt=2——待 T3b/T5 真机复核是否既有现象。
- 红线：不改 data 层协议映射、不动 UI 视觉、TV 端除 T6 外冻结；门禁 = `player/local`（+涉及模块）单测 + `ktfmtCheck` + 构建；**播放链路行为改动必须 K60（`8e875894`）真机验证**（起播/暂停/续播/跨集/错误卡重试），Worker SUCCESS / 日志无报错不算数。
- T0a 验收（2026-11-18 本地）：`player/local/build.gradle.kts` 加 `packaging.jniLibs.pickFirsts += "**/libc++_shared.so"`（与 app/phone、app/tv 同款）；clean 后全量 `:app:phone:assembleDebug` + `:data:testDebugUnitTest` + `ktfmtCheck` 全绿（308 任务 / 4m18s）。
- T0b 验收（2026-11-18）：同代码两次 master CI 挂不同用例（run 44163993936 / 44256582821，`data/` 零 diff）→ 纯时序型；两挂例共享 `assertSame(failure, caught?.cause)`，钉死了 coroutines `-ea` 下 stacktrace recovery 的异常拷贝行为；已放宽为 cause 链可达（`assertSameFailureDelivered`）。本地单独类 6 轮 + 全量 3 轮不复现，最终待 CI 复核。
- **CI 触发口径（2026-11-18 查实）**：Build / Unit Tests 仅 `push: master` 与 PR 触发；分支推送不跑 CI。分支已推 `73f44f0d`；master 立即转绿需 cherry-pick（待用户授权）或开 PR。

## 现状调查摘要（2026-11-18，Kimi 亲查）

- `PlayerViewModel` 4544 行 / ~254 成员，god object。`playerError` 写点 7 处：`PlayerViewModel.kt:771, 981, 1088, 1999, 2045, 3403, 3553`。
- 已外置资产（保留复用）：`PlaybackSessionRules`（纯 reducer，同文件 4180+）、`ProgressRecoveryJobOwner`（3981+）、`PlayerAccessInvariant`/`PlayerExitFinalizationGate`、`SeekRequestCoordinator`/`PlaybackSeekSink`、`PlayerSubtitleController`、`PlayerStartupSourceRetryBudget`。
- **F2 现场**：`MPVPlayer.prepare()`（`mpv/MPVPlayer.kt:1894`）非保源路径固定 `loadFileCommand(first, "replace")` 先载 0 号条再 `playlist-play-index` 跳回 → 重试/引擎重建时首集被真实重载。
- **跨集重试落回上一集**：`PlayerViewModel.retryPlaybackAt`（:3396）= `prepare()` + 裸 `seekTo(position)`（不带 mediaItemIndex）；mpv 下 prepare 重置条目上下文，位置落到错误的集。`retryPlayerError`（:3368）同路径。
- **onExhausted 静默取消**：`ProgressRecoveryJobOwner.start` 循环（:4003-4043）中 `requestIsCurrent` 失败即 `cancel()` 吞掉预算；`publishExhaustedStartupSourceFailure`（:767）又有 `fileLoaded`/`playerError` 双重短路——取消与耗尽窗口互相覆盖。
- 引擎创建：`PlayerHolder`（340 行 @Singleton）内 `ExoPlayer.Builder`（:288）与 `MPVPlayer`；`player/core` 现只有 domain 模型，无引擎接口。
- 测试面：`player/local` 30 个 JVM 测试类（纯 Kotlin 惯例成熟），`app/phone` 有 `PlayerSourceFailureRecoveryTest` 等跨层测试。

## T0 开工批 · master CI 两条既有失败（已完成，见状态块）

## T1 PlaybackSessionOwner 抽取（纯搬移，行为不变）

**目标**：media identity + generation + sourceIntent 的唯一可写者收进 `player/local/session/PlaybackSessionOwner`（纯 Kotlin，无 Android 依赖）；`PlayerViewModel` 改为持有+委托。

**改点**：
1. 新建 `player/local/session/PlaybackSessionOwner.kt`：收编 `playbackSnapshots`、`activePlaybackToken`、`playbackSessionState`、`lastSourceIntent`/`failedSourceIntent`、`playbackSessionKeyFor`、`resetPlaybackSnapshots`/`beginMediaSnapshot`/`ensureMediaSnapshot`/`advanceCurrentStreamGeneration` 等快照/代次原语（`PlayerViewModel.kt:245-253, 843-955` 一带）。
2. `PlayerViewModel` 内同名读写点全部改为委托；公开签名与 `_uiState` 投影不变。
3. 新写 `docs/adr/` 一条：PlaybackSessionOwner 所有权边界（哪个可变事实归它，谁只读投影）。

**验收**：`PlaybackSessionOwnerTest`（JVM 单测：代次递增、token 失效、stale 请求拒绝）；`player/local` + `app:phone` 单测全绿；ktfmtCheck + 构建绿。**行为零变化**，不派真机。

## T2 ErrorPolicy（统一 playerError 写点 + 重试预算）

**目标**：`session/ErrorPolicy` 成为唯一 `playerError` 写者与重试预算持有者。

**改点**：
1. 收编 7 处写点（现状调查摘要列行号）与 `PlayerStartupSourceRetryBudget`、`publishExhaustedStartupSourceFailure` 的短路规则。
2. 错误码分档（source resolution / startup source / 播放中 / codec fallback）集中在 ErrorPolicy，UI 只读投影。
3. 依赖 T1 的 SessionOwner 做"错误归属哪个会话代次"判定。

**验收**：`ErrorPolicyTest` 覆盖分档与耗尽路径；既有 `PlayerSourceFailureRecoveryTest` 等不回归；门禁同上。

## T3 StartupStateMachine + ProgressWatchdog

**2026-11-18 主负责人决定：拆为 T3a / T3b 两个子任务**（单个"一屏能验证完"放不下，串行执行）。

### T3a StartupStateMachine

**目标**：起播显式状态机 `IDLE→RESOLVING→PREPARING→READY|FAILED`（收编 `initializePlayer` :955-1090 的超时/取消/失败分支与 `startupInProgress` 布尔）；行为零变化。

**改点**：
1. 新建 `player/local/session/StartupStateMachine.kt`（纯 Kotlin）：枚举相位 + 合法迁移表 + 归属代次（initializationGeneration）；非法迁移拒绝（返回 false 或保持原态，不抛）。
2. VM 在现有决策点驱动迁移：进入起播协程 → RESOLVING；拿到 startItem 准备 `setMediaItems` → PREPARING；出画面（fileLoaded 置位点）→ READY；三个 catch/失败分支 → FAILED；显式退出/新起播 → IDLE。`startupInProgress` 的读取点全部改读状态机（`startupInProgress = true/false` 两处赋值 :1004/:1086 一带随之删除）。
3. `isCurrentPlaybackStartup` 的 generation + exit 判定留在 VM（依赖 explicitExitController），状态机只管相位与代次归属。

**验收**：状态机迁移表 JVM 单测（合法迁移、非法迁移拒绝、新起播复位、取消归 IDLE）；`player/local` + `app:phone` 单测全绿；ktfmtCheck + 构建绿；K60 快速回归（起播 + 暂停 + 续播一条链）。

### T3b ProgressWatchdog 订阅制

**目标**：`refreshPlaybackSession`（:619-722）的"判定 + 分发"收进 `session/ProgressWatchdog.kt`：terminal 仍由 `PlaybackSessionRules.advance` 单点产生，fallback（`progressFallbackController.evaluate`）与 recovery（`progressRecoveryController.observe/begin` + `ProgressRecoveryJobOwner`）改为 Watchdog 固定顺序驱动的订阅者；VM 只采集输入（player 读取）与执行副作用（eventsChannel、`_uiState.update`、launchProgressRecovery）。onExhausted 语义并入 Watchdog 的调用顺序，**行为不变**（T5-c 才修静默取消）。

**验收**：Watchdog 决策顺序的单测（terminal→retain→fallback→recovery→project 的次序与短路）；既有 `PlayerSourceFailureRecoveryTest` 等全绿；K60 真机：起播/暂停/续播/跨集/错误卡重试全链路。

## T4 PlaybackEngine 接口 + 双引擎适配（可与 T1 并行）

**目标**：`player/core` 定义 `PlaybackEngine` 接口 + `EngineCapabilities` + 统一错误模型；`player/local/engine/exo`、`engine/mpv` 各出适配器，先收口现状（行为不变）。

**改点**：
1. 接口面：prepare/play/pause/seekTo（含 mediaItemIndex 语义）/setRate/tracks/subtitle/audio delay/style/release。
2. `PlayerHolder` 的 ExoPlayer.Builder（:288）与 MPVPlayer 创建点改走引擎工厂；`SeekPlayerEventListener`（1021 行）按需跟着适配。
3. 新写 `docs/adr/` 一条：引擎抽象接口契约（含 mpv prepare 语义归实现内部收口）。

**验收**：引擎适配层契约测试（参照既有 harness 惯例）；双内核 K60 起播回归。

## T5 三条 1.4.1 遗留根治（依赖 T3/T4）

1. **F2 mpv prepare() 重载首集**：`MpvPlaybackEngine` 内 prepare 以 `currentMediaItemIndex` 为准，不再先载 0 号条（`MPVPlayer.kt:1894-1926` 重写，保源路径语义并入）。
2. **跨集重试落回上一集**：重试路径携带 mediaItemIndex（`seekTo(mediaItemIndex, position)` 语义），禁止 `prepare()` + 裸 `seekTo(position)`（`PlayerViewModel.kt:3396-3414`）。
3. **onExhausted 静默取消**：T3 单一 owner 后，耗尽与取消窗口收口到 Watchdog 一处；补发错误卡片只在确认 EXHAUSTED 终态发生。

**验收**：每项一条针对性单测/契约测试 + **K60 真机四链路**（起播/暂停/续播/跨集），外加：mpv 内核下中途错误 → 重试 → 落在同一集同一位置；Exo 播放到错误 → 恢复耗尽 → 错误卡片出现且可重试。

## T6 恢复 app/tv 编译（独立，可随时插入）

**评估结论（2026-11-18）**：不是一行改名。TV `MainScreen.kt` 期待旧契约 `MainViewModel.uiState` / `UiState.Normal(server, user)` / `loadServerAndUser()`，core `MainViewModel`（W1 改造后）只剩 `state: StateFlow<MainState>`（布尔位 + themeId），server/user 对象无处取。**且 Build CI 跑根 `assembleDebug` 含 TV——libc++ 修好后 TV 编译失败会成为下一个 Build 红灯**，必须修。

**改法**：只动 `app/tv`——建 TV 本地 `TvMainViewModel`（或同名包装类）实现旧契约，`MainScreen`/`MainActivity` 换引用；server/user 数据源复用 data 层既有接口。不改 core、不恢复 TV 功能交付（冻结口径不变）。

**验收**：`:app:tv:assembleDebug` 绿 + 根 `assembleDebug` 绿；不派真机。

## 交接与顺序

T1→T2→T3 串行（同文件域）；T4 可与 T1 并行（player/core 新文件 + engine/ 新目录）；T5 依赖 T3+T4；T6 独立。每任务一个 deepseek 子代理、一个 commit，主负责人验收后合入。会话容量不够时按 T1→T2→T3→T4→T5→T6 顺序截断交接。
