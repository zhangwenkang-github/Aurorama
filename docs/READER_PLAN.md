# Cinefin 阅读器任务线（READER_PLAN）

> 本文件是阅读器线（R1）唯一权威文档：需求、决策、进度、验收标准与踩坑库都写在这里。
> 项目级状态仍写 `docs/PROJECT_PLAN.md`，本文件不复制项目级内容。

| 项 | 值 |
|----|----|
| 任务线 | 阅读器（EPUB / PDF / CBZ） |
| 会话 | W1-R1 · 阅读器骨架（R1-SKELETON）→ W2-R1 · 阅读器主体（R1-CORE） |
| 分支 | `feature/r1-reader-core`（W2） |
| 基线 | `master` `674ad8b`（2026-09-30） |
| 状态 | W2 阅读模式 / 排版设置 / 阅读主题完成，真机通过；待负责人合并 |

## 1. 需求与 W1 范围

需求基线为 `docs/REQUIREMENTS.md` §4（EB-1–EB-12）。

| 编号 | 需求 | W1 状态 |
|------|------|---------|
| EB-1 | 书源仅 Jellyfin Books 库 | 🟡 已用 Books 库真实 EPUB 验证；书籍详情入口归 W2 |
| EB-2 | 首期 EPUB | ✅ Readium 3.4.0 已集成并真机打开 |
| EB-3 | PDF 分页懒加载 | ⏳ 路线已评估（见 D5），实现归 W4 |
| EB-4 | CBZ 基础阅读 | ⏳ 策略已定（见 D6），实现归 W4 |
| EB-5 | 滚动 / 横向分页 / 双栏 | ✅ 三档模式（滚动 / 分页 / 双栏）真机切换通过，Pad 5 横屏双栏渲染正确 |
| EB-6 | 排版设置 | ✅ 字号 / 行距 / 边距 / 字体 / 对齐，即时生效并持久化 |
| EB-7 | 独立阅读主题 | ✅ 纸色 / 护眼 / 深色 / OLED / 跟随，独立于主 App 主题 |
| EB-8 | 批注 | ⏳ 归 W3 |
| EB-9 | 进度写回 UserData | ✅ 读 / 写 / 反序列化均真机验证 |
| EB-10 | 入口改造 | ⏳ 归 W2（本波用 adb 显式启动 PoC） |
| EB-11 | 离线阅读 | 🟡 已先落盘再打开；离线进度队列归 W3 |
| EB-12 | 辅助阅读 | ⛔ 本期不做 |

## 2. 决策记录

### D1 · 引擎：Readium Kotlin Toolkit 3.4.0

- 依赖：`readium-shared` / `readium-streamer` / `readium-navigator`，版本 `3.4.0`。
- 传递依赖与项目对齐（Kotlin 2.4.20 / compileSdk 37 / minSdk 28 / Media3 1.11.x）。
- 许可 BSD-3-Clause，与 GPL-3.0 兼容。
- 版本目录条目由 R1 单独提交（`f30f0fb`），是 W1 波次中唯一一次修改
  `gradle/libs.versions.toml`。

### D2 · 集成：Compose `AndroidView` + `FragmentContainerView`

- Readium 3.4.0 的 Visual Navigator 仍是 Fragment 体系，PoC Activity 由
  `AppCompatActivity.supportFragmentManager` 承载单个 `EpubNavigatorFragment`。
- `AndroidView.factory` 创建 `FragmentContainerView`，**必须在 `post {}` 后**添加
  Fragment；否则容器尚未挂到 Activity 视图树，`commitNow` 会抛
  `No view found for id ...`（见 §8 踩坑 3）。
- Fragment 用 `EpubNavigatorFactory.createFragmentFactory(...)` 生成，传入
  `initialLocator`、`EpubPreferences(scroll, theme)` 与 Listener；进度监听走
  `EpubNavigatorFragment.PaginationListener.onPageChanged`。
- W1 PoC 直接使用 Activity 的 FragmentManager；W2 接入导航后改回
  `childFragmentManager`。

### D3 · 取书：先落盘再打开（沿用 ARCHITECTURE §3.3）

- `ReaderRepository.ensureLocalFile(itemId)`：命中
  `filesDir/books/{itemId}.book` 直接返回；否则
  `GET /Items/{itemId}/Download`（`X-Emby-Token`）流式落盘。
- 落盘后 `AssetRetriever.retrieve(File)` → `DefaultPublicationParser` →
  `PublicationOpener.open(asset)`；打开失败会关闭 `Asset`。

### D4 · 本地精确定位：W1 用应用私有 JSON，W3 再迁 Room

- `ReaderProgressStore` 落 `filesDir/reader/progress.json`，保存 Readium `Locator`
  的 JSON、progression、ticks、`updatedAt` 与 `pendingSync`。
- 暂不使用 Room 的理由：W1 波次 R1 / R2 可能并行改 Room schema；数据库版本号
  属于咽喉资源（PARALLEL_PLAN §1.3）。W3-R1 再做带迁移的 `reader_annotations` /
  `reader_progress` 表。
- 冲突策略：最近 `updatedAt` 优先；本地 locator 永远保留用于精确恢复。

### D5 · PDF 路线建议（W4 执行前定稿）

评估结论（详见 §5 skill 2–4）：

1. **Readium pdfium 适配器**：与 EPUB 共用导航器 API、开发量最小；但依赖 JitPack
   的 `com.github.marain87:PdfiumAndroid` / `AndroidPdfViewer`，需验证 ABI 体积、
   中文渲染与 CI 可达性，且 pdfium 自身不保证「页窗口」内存上限。
2. **androidx.pdf 1.0.0-beta01**：`pdf-viewer-fragment` 已到 beta，Fragment 体系可
   直接复用；但仍为 beta，且 API 偏「整文档查看器」，懒加载窗口是否可控需 spike。
3. **PdfRenderer 自研**：`PdfRenderer.openPage(index)` + `Page.close()` 能严格实现
   EB-3 的「当前 ±1 页、位图 ≤3 张、降采样到屏幕宽」；工作量最大，但不引入
   JitPack 与 beta 依赖。

**建议**：W4 先用一本书 ≥200 页的 PDF 各做 1 天 spike；若 Readium pdfium 无法把
内存钉在常数区间，则 PDF 走 PdfRenderer 自研、EPUB 继续 Readium（ARCHITECTURE
§3.4 的备选方案）。

### D6 · CBZ 策略

- Readium 对 CBZ 标注「部分实现」，判定标准：单页 + 预取 ≤3 页、双页 / RTL、
  连续 20 页内存稳定；不满足则自研。
- 自研路径：`ZipFile` 随机读条目（`ZipInputStream` 顺序流仅作兜底）+
  `BitmapFactory.inSampleSize` 降采样 + Compose `HorizontalPager` + 下一页预取
  + 位图 LRU。
- 禁止整包解压；复用 §3.4 的页窗口预算。CBR 本期不做。

### D7 · W1 PoC 入口

- `ReaderActivity` 声明 `exported=true`，仅用于本波 adb 显式启动验证。
- W2 接入 `NavigationRoot` 后必须改回 `exported=false`，并把启动参数改为
  `itemId: UUID` 路由参数。

### D8 · 阅读设置模型与提交方式（W2）

- `ReaderSettings`（`modes/book/.../reader/ReaderSettings.kt`）是 UI / 持久化使用的纯数据：
  `mode` / `fontSize` / `lineHeight` / `pageMargins` / `font` / `textAlign` / `theme`。
- 每次设置变更构造**一整套** `EpubPreferences` 并 `navigator.submitPreferences(...)`，
  保证即时生效且无旧偏好残留；不需要重建 Navigator Fragment。
- 模式三档映射：滚动 = `scroll=true`；分页 = `scroll=false` + `columnCount=ONE`；
  双栏 = `scroll=false` + `columnCount=TWO`（官方建议把 `columnCount` / `spread`
  收敛为用户可见的单一"双页"开关）。
- **字号单位是百分比倍数**（`fontSize=1.0` → CSS 100%），不是 px；官方 supportedRange
  为字号 0.1–5.0、行距 1.0–2.0、边距 0.0–4.0，UI 取实用子区间 0.7–2.5 / 1.0–2.0 / 0.0–2.0，
  越界 / 非有限值在 `ReaderSettings.sanitized()` 中裁剪。
- 主题映射：纸色 / 护眼 = `Theme.LIGHT` + 自定义背景 / 文字色；深色 / OLED = `Theme.DARK`
  + 自定义背景 / 文字色；`跟随` 按系统深色解析为深色 / 纸色。
- 字体：默认不覆盖出版方字体；衬线 / 无衬线 / 等宽分别映射 Readium 内置
  `FontFamily.SERIF / SANS_SERIF / MONOSPACE`，不打包字体文件。
- 持久化：`AppPreferences` 只追加 `pref_reader_*` 键（`pref_reader_mode` /
  `pref_reader_font_size` / `pref_reader_line_height` / `pref_reader_page_margins` /
  `pref_reader_font_family` / `pref_reader_theme` / `pref_reader_text_align`），
  由 `ReaderViewModel` 读写，未重排文件、未改其他任务线的键。

### D9 · 阅读主题 / 面板 token（W2）

- 复用 core 已落地的 `PaperSurface` / `PaperAccent`；在 `CinefinTokens` 追加 §8.14 阅读器
  区块：`ReaderPanelDark`（`#191F28`，设计系统指定）、`ReaderEyeCareSurface`（`#E7EFE1`，
  按纸色向绿色相偏移的派生值）、`ReaderOledSurface`（`#000000`）。
- 强调色：纸色 / 护眼 = 纸页棕 `PaperAccent`；深色 / OLED = 阅读域天青 `MediaBook.base`；
  强调填充上的文字用 `OnSurfaceLight` / `MediaBook.onBase`。
- 这三项是 core token 的唯一追加（本波 core 主题文件无其他会话并行修改）；业务层不出现
  自造色值，全部经 token 引用。若后续 S1/R3 修订设计系统，以设计系统为准回调。
- 排版面板按 §8.14 实现：宽 512dp、圆角 22dp（`CinefinShapes.Lg`）、内边距 30/28dp、
  78dp 主题缩略图（选中 2dp 描边）、滑块 / chip 行随阅读主题换色。

### D10 · 面板滚动（真机发现）

- 真机（Pad 5 横屏）发现 `ModalBottomSheet` 内固定高度的面板内容超过可视区时不会自动滚动，
  主题行不可达。修复：`ReaderSettingsPanel` 的 Column 自带 `verticalScroll`，真机复测
  5 个主题缩略图全部可见、可点。

## 3. 接口契约（已落地）

```kotlin
interface ReaderRepository {
    suspend fun ensureLocalFile(itemId: UUID): File
    suspend fun deleteLocalFile(itemId: UUID)
    suspend fun getReadingProgress(itemId: UUID): ReadingProgress?
    suspend fun saveReadingProgress(itemId: UUID, progress: ReadingProgress)
    suspend fun flushPendingProgress()
}

data class ReadingProgress(
    val itemId: UUID,
    val locatorJson: String = "",
    val progression: Double,
    val positionTicks: Long,
    val updatedAt: Instant,
    val pendingSync: Boolean = false,
)
```

- 接口位于 `data/repository/ReaderRepository.kt`，实现
  `ReaderRepositoryImpl` 位于 data 模块；Hilt 绑定在
  `modes/book/.../BookModule.kt`（W0 预留的 DI 扩展点）。
- 写路径：本地 JSON 先落盘（`pendingSync=true`）→
  `POST /UserItems/{itemId}/UserData` 成功则标记同步；失败保留待同步标记。
- 读路径：`GET /Users/{userId}/Items/{itemId}/UserData` + 本地 Locator
  → `resolveReadingProgress(...)` 合并。

## 4. 进度同步与服务端行为

### 4.1 实测接口

| 操作 | 接口 | 结果 |
|------|------|------|
| 读 | `GET /Users/{userId}/Items/{itemId}/UserData` | 200，返回 ticks / percentage / played |
| 写 | `POST /UserItems/{itemId}/UserData` | 200，写入后再次 GET 可见 |
| 取书 | `GET /Items/{itemId}/Download` | 206 / `application/epub+zip`（支持 Range） |

### 4.2 ticks 换算（重要）

Jellyfin 服务端用 `PlaybackPositionTicks / RunTimeTicks × 100` 计算
`PlayedPercentage`。本服务器书籍 `RunTimeTicks = 10_000_000`（1 秒占位值），
因此 **不能**使用固定合成时间轴：

```text
positionTicks = progression × item.RunTimeTicks
```

W1 实现：`saveReadingProgress` 先读取该条目的 `RunTimeTicks`，再按上式换算。
实测写入 progression `0.003568879` 后，服务端返回
`PlayedPercentage = 0.35689`、`PlaybackPositionTicks = 35689`，一致。

### 4.3 上报时机（W1 实现 / W3 完善）

- W1：`onPageChanged` 去抖 2 秒后异步写本地 + 回传；失败保留 pending。
- W3：补齐打开恢复、30 秒定时、退后台、关闭页、WorkManager 离线队列。

## 5. 学习笔记（ROLE_SKILLS §5.1 全表）

### 5.1 Readium Kotlin Toolkit 官网 / README

- 格式：EPUB 2/3 ✅、PDF ✅（需适配器）、CBZ 🚧、CBR ❓。
- 能力：EPUB 分页 / 滚动 / RTL / Decoration 高亮 / TTS ✅；PDF 搜索与高亮 👀。
- 最低要求：Readium 3.4.0 = minSdk 24 / compileSdk 37 / Kotlin 2.4.20 / Gradle
  9.7.0；本项目完全对齐。
- **必须启用 core library desugaring**；本项目 `app:phone` 已启用。
  Gradle 8.4+ / AGP 8.4+ 默认支持。

### 5.2 Readium PDF 指南

- PDF 支持由 `document` + `navigator` 两个适配器组成；官方提供 pdfium 与商业
  pspdfkit。pdfium 适配器通过 JitPack 引入第三方 `PdfiumAndroid` /
  `AndroidPdfViewer`。
- `PdfNavigatorFragment` 负责导航与 Locator 事件；引擎侧负责单页渲染。

### 5.3 androidx.pdf 发布说明

- 当前 beta：`androidx.pdf:pdf-viewer-fragment:1.0.0-beta01`（2026-08-26）。
- 模块：`pdf-viewer` / `pdf-viewer-fragment` / `pdf-document-service`；较新的
  alpha 还提供 Compose API 与 @ExperimentalPdfApi。
- 仍是 beta，暂不作为 W1 主路径。

### 5.4 PdfRenderer API

- `PdfRenderer(ParcelFileDescriptor)` 需要可 seek 的描述符；`openPage(index)` 从 0
  开始；`Page.close()` 回收；`close()` 时若还有页面未关闭会抛异常。
- 严格按页渲染是 EB-3 自研路线的基础。

### 5.5 W3C EPUB 3.3

- OCF 容器：`mimetype` + `META-INF/container.xml` 指向 package document。
- package document：metadata / manifest / **spine**；spine 定义默认阅读顺序。
- navigation document 是 XHTML 特例，提供 toc / page-list / landmarks。
- 固定版式与 reflowable 由 rendition 属性控制；阅读进度定位依赖 spine + CFI /
  Locator。

### 5.6 java.util.zip / ZipInputStream

- `getNextEntry()` 顺序读条目，`closeEntry()` 跳到下一个；适合流式读 CBZ，但
  随机访问不如 `ZipFile`。
- Android U+ 禁止含 `..` 或绝对路径的条目名；CBZ 自研需做路径校验。

### 5.7 Mihon（Apache-2.0）

- 成熟漫画阅读交互参考：多种查看器、阅读方向、RTL / 双页 / 裁边、本地阅读、
  主题与离线缓存。
- 仅借鉴交互与架构思路；若复制代码需保留 Apache-2.0 声明。

## 6. W1 任务清单

- [x] 版本目录独立提交（Readium 3.4.0 + JUnit，`f30f0fb`），并报告负责人。
- [x] `:modes:book` 集成 Readium，`AndroidView` 承载 `EpubNavigatorFragment`。
- [x] 真机打开 Books 库真实 EPUB《雷普利全集》，分页与滚动模式可切换。
- [x] `ReaderRepository` / `ReadingProgress` 数据模型落地。
- [x] UserData 进度读写实现（GET + POST，仅用户数据白名单）。
- [x] 本地 Locator 精确定位存储（W1 文件版，W3 迁 Room）。
- [x] data 模块 JVM 单测：progression 夹取 / ticks 换算 / 最近时间戳合并。
- [x] `:app:phone:compileLibreDebugKotlin` + `ktfmtCheck` 通过。
- [x] 真机验证记录（见 §7）写入本文件。

### W2 任务清单（R1-CORE，2026-09-30）

- [x] 阅读模式三档：滚动 / 分页 / 双栏（Readium `scroll` + `columnCount`）。
- [x] 平板双栏：Pad 5 横屏真机确认左右两栏渲染、翻页与进度正常。
- [x] 排版设置：字号 / 行距 / 边距 / 内置字体 / 对齐，即时生效。
- [x] 阅读主题：纸色 / 护眼 / 深色 / OLED / 跟随，独立于主 App 主题。
- [x] `AppPreferences` 追加 `pref_reader_*`，退出重进保持（真机 force-stop 复测）。
- [x] `modes/book` JVM 单测 9 项（模式 / 主题 / 颜色 / 字体 / 对齐 / 裁剪 / 回退 / 强调色 / 循环）。
- [x] `:modes:book:testDebugUnitTest`、`:app:phone:assembleDebug`、`ktfmtCheck` 通过。
- [x] W2 真机验证记录写入 §7.4，踩坑写入 §8。

## 7. 真机验证记录（2026-09-30）

设备：Xiaomi Pad 5（`nabu`，型号 21051182C），Android 13，2560×1600。

### 7.1 打开与渲染

```text
adb shell am start -W -n com.zhangwenkang.cinefin.debug/\
  com.zhangwenkang.cinefin.book.presentation.reader.ReaderActivity \
  -e itemId 8109915342fc71c405da49d632fc6978
```

- `Status: ok`、`LaunchState: COLD`、ReaderActivity 获得焦点。
- `uiautomator dump` 显示 `EpubNavigatorFragment` 的
  `resourcePager` / `webView` 节点，WebView 内出现 EPUB 正文文本。
- logcat 出现 Readium 内容页的 Chromium 控制台日志（远程字体被 CORS 拦截），
  证明真实 EPUB 已渲染；正文使用内置字体不受影响。

### 7.2 翻页与滚动

- 连续左滑后，本地 locator 从 `5o6jvk_z_split_002.html` 变为
  `5o6jvk_z_split_004.html`，`totalProgression` 从 `0.0021413` 增至
  `0.0035689` —— 翻页有效。
- 点击右上角模式按钮，`uiautomator` 标签由「翻页模式」变为「滚动模式」——
  `EpubPreferences(scroll=true)` 生效，滚动模式可用。

### 7.3 进度读写

- 写入后服务端 `UserData` 返回：
  `PlayedPercentage=0.35689`、`PlaybackPositionTicks=35689`、
  `LastPlayedDate=2026-09-30 12:42:54`。
- 本地 `files/reader/progress.json` 记录同一 progression 与 locator，
  `pendingSync` 在回传成功后变为 `false`。

### 7.4 W2 阅读模式 / 排版 / 主题（2026-09-30 21:24–21:38，Pad 5）

设备：Xiaomi Pad 5（`nabu`，Android 13，2560×1600，横屏）；adb 文本命令优先，
截图仅用于双栏 / 底色判断（本地查看后即删，未贴回对话）。

| 项 | 操作 | 结果 |
|----|------|------|
| 面板完整性 | 顶栏点「Aa」 | 「阅读设置」面板显示模式 / 字号 / 行距 / 边距 / 字体 / 对齐 / 主题全部字段 |
| 面板滚动 | 面板内上滑 | 修复后 5 个主题缩略图（纸色 / 护眼 / 深色 / OLED / 跟随）全部可见 |
| 双栏 | 点「双栏」 | 顶栏显示「双栏」；截图确认左右两栏分页渲染，中间栏距正常 |
| 字号即时生效 | 字号滑块 100% → 160% | 面板值即时变 160%，正文即时放大且保持双栏 |
| 纸色主题 | 点「纸色」 | 正文底采样 `#FBF6EC`，顶栏同底，深墨正文 |
| 滚动 + OLED | 点「滚动」+「OLED」 | 顶栏显示「滚动」，连续滚动单栏；正文底 `#000000`、顶栏底 `#191F28` |
| 分页 + 护眼 | 点「分页」+「护眼」 | 顶栏显示「分页」，单栏分页；正文底 `#E7EFE1` |
| 深色主题（默认） | 点「深色」 | 正文底 `#151A21` |
| 持久化 | 双栏 + 纸色 + 160% 后 `force-stop` 重启 | 顶栏仍「双栏」、字号仍 160%、正文底仍 `#FBF6EC`；退出重进保持成立 |
| 进度回归 | `run-as ... cat files/reader/progress.json` | `progression=0.003568879`、`pendingSync=false`，W1 进度链路无回归 |
| 崩溃 | `adb logcat -d` 过滤 | 无 FATAL / `E cinefin` |

> 验证后已把设置恢复为默认（滚动 / 深色 / 100% / 行距 1.2 / 边距 1.0 / 默认字体 / 两端对齐）
> 并释放 device lock。截图临时文件位于 `%TEMP%\cinefin-r1-*.png`（未入库、未贴回对话）。

## 8. 踩坑库

1. **Readium 包名是 `org.readium.r2.*`**，不是 `org.readium.navigator.*`；
   3.4.0 的入口为 `org.readium.r2.navigator.epub.EpubNavigatorFactory`。
2. **Fragment 添加时机**：`FragmentContainerView` 尚未 `onAttachedToWindow` 时
   `commitNow` 会抛 `No view found for id ...`；必须在 `container.post {}` 中
   添加 Fragment（本会话已修）。
3. **Jellyfin UUID 两种格式**：REST 返回 32 位无连字符 UUID，
   `UUID.fromString` 只接受 36 位标准格式；PoC 已兼容两种写法。
4. **PlayedPercentage 由服务端反算**：不能使用固定合成时间轴；
   必须按条目 `RunTimeTicks` 换算，否则会出现 214% 的假进度。
5. **Readium 3.4.0 部分 API 标注 `@ExperimentalReadiumApi`**（Fragment Listener、
   `submitPreferences` 等），调用处需 `@OptIn`；升级时优先复查这些 API。
6. **共享测试机的并发安装**：其他会话可能重装同一 debug 包，导致
   ReaderActivity 临时“消失”；验证前重装本分支 APK 并立即执行。
7. **凭据纪律**：`.env.local` 只在命令变量中使用；不得写入仓库、文档、提交
   信息或会话输出。测试日志只取过滤片段。
8. **`EpubPreferences.fontSize` 是百分比倍数**（1.0 = CSS 100%），不是 px；
   官方 supportedRange 0.1–5.0，超范围会静默失效或异常显示。行距 1.0–2.0、
   边距 0.0–4.0，UI 需自行收窄到实用区间。
9. **Readium `Theme` 枚举在 JVM 单测初始化时会调用 `android.graphics.Color.parseColor`**
   （unit test 报 `not mocked`）。映射拆成纯 Kotlin 的 `ReaderPreferenceSpec` 中间层，
   单测只断言该层；Readium 适配层（`toEpubPreferences`）留给真机 / instrumentation。
10. **`ModalBottomSheet` 内超屏内容不会自动滚动**：面板必须自带 `verticalScroll`，
    否则横屏下主题行不可达（本会话真机已修）。
11. **adb 点击 Material3 Slider 轨道可以跳值**（真机验证用），但按像素换算的落点
    有 ±2% 误差；需要精确默认值时多点一次或用 `value` 文本确认。

## 9. 未决问题与下一波

### W2 遗留（交接 W3）

- `ReaderActivity` 仍为 `exported=true`；Books 库 → 书籍详情 → 阅读器路由由 R3 统一注册
  （本会话按约束未改 `NavigationRoot.kt`），接完导航后改回 `exported=false` 并换路由参数。
- 「跟随」主题有单测覆盖（系统深色 → 深色、浅色 → 纸色）；真机系统当前为浅色，
  未单独点选走查，W3 可在系统深色下补一次回归。
- 字体只提供默认 + Readium 内置字体族（衬线 / 无衬线 / 等宽）；设计 §3.1 的
  Noto Serif SC / 思源宋体（OFL 1.1，打包子集）待字体资源到位后接入。
- 字号 / 行距 / 边距滑块为实用子区间（0.7–2.5 / 1.0–2.0 / 0.0–2.0），
  如需放宽改 `ReaderSettings.kt` 顶部常量。
- 本地进度仍是文件版 `progress.json`；W3 迁 Room 时补迁移与并发测试。

### W3 / W4

- W3：Room 本地 Locator、批注、离线进度队列（WorkManager）、30 秒定时上报。
- W4：PDF 路线 spike 后定稿、CBZ 判定与自研窗口化、内存红线验证。

## 10. 变更日志

| 日期 | 变更 |
|------|------|
| 2026-09-30 | W1-R1 创建本文档；完成 Readium PoC、进度接口、单测与真机验证 |
| 2026-09-30 | W2-R1：阅读模式（滚动 / 分页 / 双栏）、排版设置（字号 / 行距 / 边距 / 字体 / 对齐）、阅读主题（纸色 / 护眼 / 深色 / OLED / 跟随）；`pref_reader_*` 持久化；9 项单测 + Pad 5 真机验证 |
