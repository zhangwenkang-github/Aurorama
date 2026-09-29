# Cinefin 开发规范（DEV_STANDARDS）

| 项 | 值 |
|----|----|
| 文档版本 | v1.0（2026-09-29） |
| 状态 | 已定稿，待项目负责人审查 |
| 作者会话 | S2 · 架构与规范 |
| 上游依据 | `docs/REQUIREMENTS.md` §12（验收标准）、`.github/workflows/*`、根 `build.gradle.kts`、现有代码范式 |
| 适用对象 | 全部开发会话（R1–R4）、播放器线与后续任务线 |
| 维护规则 | 规范变更先改本文；实现细节与进度写各任务线文档 |

> 本文是**可执行的最低标准**：PR 审查按本文与 `docs/GIT_WORKFLOW.md` 逐条对照。文中"待实施"项指规范已定、但基础设施尚未落地，由对应任务线按其排期补齐。

---

## 1. 总则

1. **可读性优先**：代码是给下一个会话读的。宁可多一行显式命名，不用"聪明"的缩写。
2. **改动最小化**：一个 PR 只做一件事；不顺手重构无关文件（未提交的他人改动不要动、不要回滚）。
3. **中文注释、英文标识符**：注释与文档用简体中文；类名 / 函数名 / 变量名 / 资源 key 一律英文。
4. **不新建零散文档**：新需求、新决策、新进度写进对应任务线文档（见 §8）。
5. **真机为准**：任何影响播放 / 布局 / 阅读渲染 / 音频的改动，必须真机验证后才能勾选完成（Pad 5 主，K60 辅）。

---

## 2. 代码风格

### 2.1 格式化：ktfmt（kotlinLangStyle）

| 项 | 现状 / 要求 |
|----|------------|
| 工具 | `com.ncorti.ktfmt.gradle`（版本目录 `ktfmt = "0.27.0"`），在根 `build.gradle.kts` 对所有子项目启用 |
| 风格 | `kotlinLangStyle()`（对齐 [Kotlin 官方代码风格](https://kotlinlang.org/docs/coding-conventions.html)） |
| 本地格式化 | `.\gradlew.bat ktfmtFormat`（提交前**必须**跑） |
| CI 校验 | `.github/workflows/format.yaml` 跑 `./gradlew ktfmtCheck`，触发路径 `**.kt` / `**.kts` |
| 手工排版 | 禁止手工对齐空格、禁止为了美观手动断行（交给 ktfmt） |

> 仓库当前没有 `.editorconfig`（**待实施**：如需统一 IDE 缩进，可在后续 PR 增加；本文件不强制）。
> IDE 建议：Android Studio 安装 ktfmt 插件并开启 "format on save"，避免 PR 里出现格式噪音。

### 2.2 Kotlin 约定（ktfmt 不覆盖的部分）

- **显式类型**：公共 API 显式写返回类型；局部变量用类型推断。
- **不可变优先**：默认 `val`，需要变更才 `var`；集合默认 `List`，避免对外暴露 `MutableList`。
- **空安全**：不用 `!!`；确需断言用 `requireNotNull(...) { "中文说明" }` 或 `checkNotNull`。
- **作用域函数**：`let` / `run` / `apply` 只在能提升可读性时使用；链式超过 3 层拆成局部变量。
- **协程**：`suspend` 函数不吞异常；UI 侧统一 `viewModelScope.launch`，IO 用 `Dispatchers.IO`，CPU 密集（解析）用 `Dispatchers.Default`。
- **Flow**：状态用 `MutableStateFlow` + `asStateFlow()`（现有范式）；"一次性事件"用 `SharedFlow` / `Channel`，不塞进 State。
- **Parcelable 模型**：跨页面传递的模型用 `@Parcelize`（现有 `PlayerItem` 即此模式）。

### 2.3 文件与包组织（MVI 范式）

沿用 `modes/film` 与 `settings` 的既有结构，每个业务特性一个包，包内四件套：

```text
<module>/src/main/java/com/zhangwenkang/cinefin/<mode>/<feature>/
 ├─ XxxScreen.kt        // Compose 页面
 ├─ XxxState.kt         // data class，页面全部状态
 ├─ XxxAction.kt        // sealed interface/class，页面意图
 ├─ XxxEvent.kt         // （可选）一次性事件
 └─ XxxViewModel.kt     // @HiltViewModel，State 用 MutableStateFlow
```

- 新模块包名：`com.zhangwenkang.cinefin.reader.*`（`modes/reader`）、`com.zhangwenkang.cinefin.music.*`（`modes/music`）；
- 领域纯逻辑放 `domain/`，UI 模型放 `presentation/models/`，DI 放 `di/`（参考 `modes/film/presentation/di/FilmModule.kt`）；
- Compose 页面若需放在 `app/phone`（与现有 film 页面一致），则页面文件放 `app/phone/.../presentation/<mode>/`，业务逻辑仍留在对应 `modes/*` 模块。

### 2.4 Compose 约定

- 可组合函数用 **PascalCase**（`ReaderToolbar`）；非 Composable 的辅助函数用 camelCase；
- 参数顺序：必填参数 → `modifier: Modifier = Modifier` → 回调 `onXxx`；
- **状态提升**：Composable 不持有业务状态；跨页状态进 ViewModel；
- 不在 Composable 体内做 IO / 网络 / 数据库调用（用 `LaunchedEffect` 触发 ViewModel 的 Action）；
- 列表用 `key = { it.id }`，禁止无 key 的 `items()`；
- 长列表（书架 / 歌词 / 队列）用 `Lazy*`，禁止 `Column` + `forEach` 渲染大列表。

### 2.5 View 互操作（阅读器）

- Readium Fragment 只能通过 `AndroidView` + `FragmentContainerView` 承载（见 `docs/ARCHITECTURE.md` §3.2）；
- Fragment 生命周期收敛在容器内，`onRelease` 必须释放 NavigatorFactory 与文档对象；
- 与 Compose 通信只用回调 / 状态流，禁止把 Fragment 实例暴露给上层页面。

---

## 3. 命名规范

| 类别 | 规则 | 示例 |
|------|------|------|
| 包名 | 全小写，无下划线 | `com.zhangwenkang.cinefin.music.presentation.player` |
| 类 / 接口 | PascalCase，名词 | `MusicPlaybackController`、`LyricsPairer` |
| 函数 | camelCase，动词开头 | `getLyrics()`、`updateUserItemData()` |
| 布尔 | `is` / `has` / `can` / `should` 前缀 | `isOfflineMode`、`canSeek` |
| 常量 | `UPPER_SNAKE_CASE` | `MAX_DELAY_MS`、`DEFAULT_REPORT_INTERVAL_MS` |
| State 字段 | 名词 / 形容词，避免缩写 | `isLoading`、`lyricsBlocks`、`queueIndex` |
| Action | 事件式命名 | `OnRetryClick`、`OnLyricLanguageSelect` |
| Compose 可组合 | PascalCase | `NowPlayingScreen` |
| 测试类 | 被测类名 + `Test` | `LrcParserTest`、`MusicQueueTest` |

**资源命名（`res/values/strings.xml`）**

- 统一 snake_case，前缀表达归属：`player_controls_exit`、`reader_toc_title`、`music_queue_shuffle`；
- 新增文案必须同步三套：英文基准 `values`、`values-zh-rCN`、`values-zh-rTW`（REQUIREMENTS §10）；
- drawable：图标 `ic_*`，背景 / 形状 `bg_*`，图片 `img_*`。

**`AppPreferences` key**

- 既有键保留 `pref_*`；
- 新键按任务线加前缀：阅读 `reader_*`、音乐 `music_*`、界面改造 `ui_*`；
- 一次 PR 只新增自己前缀的键，避免咽喉文件冲突（`docs/ARCHITECTURE.md` §2.4）。

**Jellyfin API 相关**

- 服务器字段名保持原样（`PlaybackPositionTicks`、`PlayedPercentage`），本地映射字段用语义化命名；
- 时间统一用毫秒（`Long`）；与服务器 ticks 的换算集中在一处（`TICKS_PER_MILLISECOND = 10_000L`），禁止散落魔法数字。

---

## 4. 错误处理

### 4.1 现有范式（必须沿用）

```kotlin
// ViewModel：仓库抛异常 → 收敛到 State.error，UI 渲染错误态
viewModelScope.launch {
    _state.emit(_state.value.copy(isLoading = true, error = null))
    try {
        val data = repository.getXxx()
        _state.emit(_state.value.copy(items = data))
    } catch (e: Exception) {
        _state.emit(_state.value.copy(error = e))
    }
    _state.emit(_state.value.copy(isLoading = false))
}
```

### 4.2 规则

| 规则 | 说明 |
|------|------|
| 不在 UI 层抛异常 | 异常在 ViewModel / Repository 边界收敛为状态 |
| 不静默吞异常 | `runCatching` 必须配 `Timber.w/e` 或回落到 State；禁止空 `catch {}` |
| 用户可见错误 | 用 `UiText` / `ExceptionUiText`（`core/models`）承载，保证多语言 |
| 降级优先于崩溃 | 参照播放器多内核降级与阅读器单页渲染失败（占位 + 重试） |
| 幂等重试 | 网络 / 下载重试不得重复写入用户数据（进度上报用"最新覆盖"语义） |
| 取消不吞 | `CancellationException` 不得被 `catch (e: Exception)` 吞掉后当成错误上报 |

### 4.3 服务器写操作纪律（硬红线）

- 测试服务器 `jellyfins.zhangwenkang.com` **只读 + 用户数据写白名单**（进度 / 收藏 / 播放上报 / 播放列表）；
- 禁止任何媒体库修改、扫描、删除、用户管理调用；开发调试脚本不得对生产库做写操作；
- 新增写接口必须在 PR 描述中标注"写入的接口 + 是否在白名单内"。

---

## 5. 日志

1. 统一用 **Timber**（仓库已依赖）；禁止 `println` / `android.util.Log`。
2. 级别约定：

| 级别 | 用途 | 示例 |
|------|------|------|
| `Timber.d` | 高频调试（状态流转、队列变更） | `Timber.d("queue %d -> %d", from, to)` |
| `Timber.i` | 关键业务节点（起播、打开书、下载完成） | `Timber.i("打开 EPUB：%s", itemId)` |
| `Timber.w` | 可恢复异常（降级、跳过、重试） | `Timber.w(it, "构建队列第 %d 项失败，跳过")` |
| `Timber.e` | 不可恢复或用户可感知失败 | `Timber.e(it, "进度上报失败")` |

3. 必须打日志的关键路径：播放起播 / 失败与内核降级、阅读器打开与进度上报失败、歌词解析失败与配对统计、下载完成 / 失败、音视频互斥切换。
4. **禁止打印**：凭据与令牌（API Key、密码、`X-Emby-Token`）、带 token 的完整 URL、用户隐私（完整文件路径含用户名时截断）。
5. 日志中带条目 ID（UUID）用于排查；不要打印整段 HTML / 整本书内容。

---

## 6. 测试要求

### 6.1 现状与目标

| 项 | 现状 | 目标（本期） |
|----|------|-------------|
| 单元测试 | **仓库内尚无任何测试** | 新增纯逻辑必须有单测；先建立测试基础设施 |
| 测试依赖 | 模块 `build.gradle.kts` 未声明 `testImplementation` | 首次落地模块自行声明 JUnit（**待实施**） |
| CI 测试门禁 | CI 仅 Build（assemble）+ Format（ktfmtCheck） | 增加 `testLibreDebugUnitTest`（**待实施**） |
| 真机验证 | 已是硬要求（Pad 5 / K60） | 保持；验证记录写任务线文档 |

### 6.2 必须写单测的逻辑（新增即覆盖）

- **LRC 解析**：时间戳变体（`[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]`）、一行多时间戳、`[offset:]` 正负、ID 标签、空行 / 乱序 / 重复行；
- **双语配对**：完全同时间戳、±250 ms 极近、未配对单行、元数据行不参与配对；
- **逐行语言检测**：纯日文（含假名）、纯中文（简 / 繁）、纯英文、中英混排、中日混排、纯符号行；
- **队列逻辑**：`insertNext` 边界（队首 / 队尾 / 当前项）、`move` 前后索引、随机模式下的原始顺序保持、循环模式；
- **进度换算**：progression ↔ ticks ↔ percentage 往返一致、越界（>1.0、负数）夹取；
- **其他纯逻辑**：新增的匹配 / 排序 / 去抖 / 状态机。

### 6.3 约定

- 目录：`<module>/src/test/java/...`；类名 `<被测类>Test`；
- 断言用 JUnit 原生 `assertEquals` 等（不引入额外断言库，除非先过许可审查）；
- 每个测试至少覆盖 **1 个正常路径 + 1 个边界 / 异常路径**；
- 运行：`.\gradlew.bat :modes:music:testLibreDebugUnitTest --console=plain`（模块名按实际替换）；
- 依赖网络 / 文件 / Android Framework 的逻辑**不要**塞进单测；把它们隔离到 Repository / Player 层，用接口替换。

---

## 7. 依赖与许可

1. **许可审查**（硬性）：新增任何第三方库前，确认许可与 **GPL-3.0 兼容**；**禁止** AGPL、GPL-2.0-only 代码并入（REQUIREMENTS §1）。
2. **版本集中管理**：库与版本一律登记在 `gradle/libs.versions.toml`，模块里用 `libs.*` 引用；禁止模块内硬编码版本号。
3. **仓库来源**：当前为 `google()` + `mavenCentral()`；若需新增仓库（如 Readium PDF 适配器要求的 `https://jitpack.io`），必须在根 `build.gradle.kts` 集中声明，并在 PR 说明中标注原因。
4. **体积与性能**：新增依赖必须在 PR 中说明对 `assembleLibreDebug` 体积的影响；阅读器相关依赖只允许出现在 `:modes:reader`。
5. **原生库（.so）**：涉及 ABI 支持范围变化时必须在 PR 中列明（当前拆分 `armeabi-v7a` / `arm64-v8a` / `x86` / `x86_64`）。

---

## 8. 文档纪律

| 要写的内容 | 写到哪里 |
|-----------|---------|
| 项目级状态 / 任务线增删 / 里程碑 | `docs/PROJECT_PLAN.md` |
| 某条线的需求、进度、勾选、日志、踩坑 | 该线文档（如 `docs/PLAYER_PLAN.md`；新线开工时先建线文档） |
| 需求变更 | `docs/REQUIREMENTS.md`（递增版本号 + 影响分析） |
| 架构 / 接口 / 并行边界变更 | `docs/ARCHITECTURE.md` |
| 开发规范 / PR 审查标准 | 本文 |
| Git / 分支 / CI / 凭据纪律 | `docs/GIT_WORKFLOW.md` |

**禁止**：新建零散 `.md` 说明文件；把临时笔记、调查过程散落在仓库根目录。

**上下文纪律（会话内）**：

- 工具输出裁剪（`-Last N`、`Select-String` 命中行），禁止整文件 / 整页回显；
- 截图只作文件交付，不在对话里贴图；看完即删；
- 单次请求纯文本 ≤ 880 KB、含内联图片 ≤ 48 MiB（硬约束）；接近上限时主动交接。

---

## 9. PR 审查清单（参考 Google eng-practices）

评审标准参考 [Google Engineering Practices · Code Review](https://google.github.io/eng-practices/review/)：审查关注"这次改动是否让代码库更好"，而不是"是否完美"；评审意见区分**必须修改**与**建议**。

| # | 检查项 | 通过标准 |
|---|--------|---------|
| 1 | 范围 | PR 只做一件事；无关改动（全仓格式化、顺手重构）已拆出 |
| 2 | 正确性 | 边界条件、错误路径、协程取消、离线场景已考虑 |
| 3 | 测试 | 新增纯逻辑有单测；覆盖正常 + 边界；`ktfmtCheck` 通过 |
| 4 | 编译 | 本地 `:app:phone:compileLibreDebugKotlin` 通过；必要时 `assembleLibreDebug` |
| 5 | 风格 | 命名、结构、注释符合本文；无 `!!`、无空 catch、无 `println` |
| 6 | 架构 | 依赖方向符合 `docs/ARCHITECTURE.md` §2.3；未越层调用 SDK / 文件 IO |
| 7 | 性能 | 大列表 / 图片 / PDF / 位图有内存上限；无主线程 IO |
| 8 | 兼容 | 手机 + 平板布局无回归；`app/tv` 仍可编译（冻结但不可破坏） |
| 9 | 无障碍 / 本地化 | 新文案三套语言齐全；可点击元素有内容描述 |
| 10 | 安全 / 凭据 | 无凭据入库；无破坏性服务器调用；写接口在白名单内 |
| 11 | 许可 | 新依赖许可已核（GPL-3.0 兼容），已登记版本目录 |
| 12 | 文档 | 进度 / 决策已回写对应任务线文档；架构级变更已更新 `ARCHITECTURE.md` |
| 13 | 真机 | 涉及播放 / 布局 / 渲染 / 音频的改动有真机验证记录 |

---

## 10. 来源

- ktfmt（Kotlin 格式化）：<https://github.com/ncorti/ktfmt-gradle>、<https://facebook.github.io/ktfmt/>
- Kotlin 官方代码风格：<https://kotlinlang.org/docs/coding-conventions.html>
- Google 工程实践 · 代码审查：<https://google.github.io/eng-practices/review/>
- Android 架构建议：<https://developer.android.com/topic/architecture/recommendations>
- 项目内依据：`docs/REQUIREMENTS.md` §10 / §12、根 `build.gradle.kts`（ktfmt 配置）、`.github/workflows/build.yaml`、`.github/workflows/format.yaml`
