# 2.0 W6 · 阅读域（任务卡）

> 状态：`ACTIVE`（2026-11-21 **W6 会话3**：T2–T6/T8/T9/T10 全部合入并 K60 取证，修复卡 T4r/T6r/T9r/T9r2 已合入；剩 T6r2 未修、T9r2 未复验、T11 收官）
> 依据：`docs/V2_REQUIREMENTS.md` §5（V2-READER 全 48 条）与 §15 波次（W6 ← W3）；设计唯一权威 `docs/V2_DESIGN.md` + 本文档「设计定稿」；token 与动效沿用 W1/W3/W4 落地件（`core/presentation/theme/`）。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W6 开工基线 = W5 收官 HEAD（`4219fe7e`，以 git log 为准）。
- **会话3（2026-11-21）**：T2–T6/T8/T9/T10 全部合入（`b84c14be`/`baa306a2`/`6c80cd9d`/`773fe87c`/`9aecc478`/`22abb8a6`/`e27070ac`/`dab1ae32`），K60 大矩阵取证完成；真机抓出 5 个缺陷，4 张修复卡合入（T4r `ac2b2d2e`、T6r `bc331698`、T9r `feae0fd3`、T9r2 `99f460ac`）。**取证判定矩阵**（✅=真机实证）：
  - T1 ✅ 全项（沉浸/唤出/关闭/下滑/返回两级/三分区/反转/关点按/双击占位）；T2 ✅（进度条拖跳 2→27 页、目录跳转+自动关闭、双击书签+列表）；T3 ✅ 冒烟（CBZ 覆盖/仿真翻页无崩、EPUB 面板只给滑动/无动画；动画曲线本身未逐帧验）；T4 ✅ 音量键+方向锁定，❌→✅ DPAD/Enter（T4r=dispatchKeyEvent 拦截，复验过）；T5 ✅ 面板新结构眼见（三组/字重/自定义色/亮度滑块，自定义色实际上屏未单独验）；T6 ✅ 分页自动翻页（跨章 30s 实跳）+暂停规则，❌→✅ EPUB 滚动档（T6r=JS scrollTop 通路+可见区选帧，日志实证滚动+跨章续接）；T7 复验 ✅（修复后 UTF-8/GBK 导入打开渲染全通）；T8 ✅ 全链（工具栏/四色/cssSelector 落盘/杀进程恢复/跳转复位渲染/标注列表）；T9 ✅ 主链（T9r 引擎回退 mibrain.speech 出声、控制条/通知/MediaSession；句高亮 T9r2 已修**未复验**）；T10 ✅（划词→搜索联动、章节名结果、跳转、导出 md 结构正确、本地书架登记、作者·进度/未读）。
  - **新发现问题**：① T6r2（未修）：末章到底后 goForward 空转不停（书末判定失效）；实测滚速 ~150px/s vs 设定 60px/s（疑 CSS px/设备 px 换算 2.5×）；② T9r2（已修 `99f460ac`，**待 K60 复验**：句高亮无失败日志、跟随跳章、停止清空）；③ T9 句高亮根因=淡入 tint 每 50ms 重启 effect 取消在途 apply + liveLocator 滚动档陈旧（T9r2 已根治）；④ 观察项：T2 loadNavigation 主线程 2.5s 卡顿（MIUIScout，大书首开）；T9 定时/上下句/后台细项未逐条验；`pageIndicatorText` 死代码（T11 清）；T4 平板方向锁定可能被 Android 16 忽略（观察）。
  - **设备环境记录**：K60/Pad 均无默认 TTS 引擎（HyperOS），mibrain.speech 可被显式绑定；mibrain 首次朗读弹录音权限（需手动允许一次）。
- **会话2（2026-11-21）**：首批三卡并行派出并收口——
  - **T1 沉浸控制层与手势重排 ✅ 已合入** `436aaf07`（沉浸全屏、顶片/底片控制层覆盖叠加、单击三分区（反转/关闭两设置项）、双击书签回调占位、返回键先关控制层；204 单测绿）。上报偏差：glass 的 `blur 24` 未实现（Compose 无 backdrop blur，沿用工程既有「半透明无实时模糊」口径，待 Kimi 裁定）；双击中区=PDF 适宽快捷入口随手势重排移除（如需保留待设计补入口）。
  - **T7 TXT 支持与导入 ✅ 已合入** `d238397d`（嗅探 UTF-8/GBK + 二进制防护、纯 JVM TXT→最小 EPUB3 转换器、可配置分章正则 `pref_reader_txt_chapter_regex`（仅模型无 UI）、书架导入 FAB→SAF 持久授权→直接进阅读器；16+8 单测绿）。遗留：导入书不进书架列表（data 层白名单/单文件登记，归 T10）；真机验证（Readium 打开缓存 EPUB、GBK 渲染、SAF 授权跨重启）待 K60。
  - **T8-spike ✅ 完成**（结论见下「T8-spike 结论」）。
  - T1/T7 真机取证（沉浸/控制层/三分区/TXT 导入链路）**未做**，归会话3 Kimi 在 K60 统一执行；T2–T6 待派。
- **T8-spike 结论（2026-11-21，反编译 AAR + 官方 3.4.0 源码交叉核实）**：**方案 A 成立，走官方 Decoration API，不启用降级**。要点：① 选择回调无 `SelectionListener`，用 `Configuration.selectionActionModeCallback`（替换系统选择菜单）+ `currentSelection(): Selection(locator, rect)`（rect 为导航器 view 坐标，可直接锚浮动工具栏）；② Decoration 完整：`applyDecorations` 整组替换、`Style.Highlight(tint)` 支持 alpha（模板级 0.28）与**圆角**（`cornerRadius`，定稿五圆角观察项撤销）、`addDecorationListener` 点击回调带 rect、分页/滚动均支持、换页自动重放；③ Readium 3.x **无 CFI**：定位=整条 `Locator.toJSON()` 落盘（字段名 `locatorJson`），渲染靠 `Text` 文本引用 + `cssSelector`（后者走 `otherLocations` 往返无损）；④ 精确性风险：`currentSelection()` 的 locator 无选区级 `cssSelector`，短句重复文本可能错位 → T8 实现走「路径 2」：官方 `registerJavascriptInterface` + 注入 JS 在 selectionchange 时补算 cssSelector/progression，选区稳定时先捕获快照（`onActionItemClicked` 会先 clearFocus，不可点击时再取）；⑤ 待真机验证一项：`onCreateActionMode` 返回 false 抑制系统菜单后选区是否保留（保底：返回 true + 单条不可见项）。R1 风险降级为「已核实，剩 1 项真机待验」。定稿五两处口径修正：「cfi」→「Locator JSON」；圆角观察项删除。
- **会话1（2026-11-21）**：T0 现状核对完成（Kimi 亲自，对照 §5 全 48 条，逐条归证见下文「T0 现状核对」）；设计定稿八节定稿（Kimi，deepseek 零设计权）；任务卡 T1–T11 定稿待派。
- **现状基线（T0 要点）**：现有阅读器 = EPUB（Readium 3.4.0 Fragment 内核）+ PDF/CBZ（自研页源 `SimpleBookView`）双引擎；已具备：流式远程首开（EPUB/CBZ）、locator 进度持久化+30s 定时上报+离线暂存回传、书签（仅 EPUB）、PDF 全书搜索、PDF 矩形批注、亮度手势+HUD、滚动/分页/双栏三模式、RTL、五套阅读主题（纸色/护眼/深色/OLED/跟随）。缺口集中在：沉浸控制层、底部进度条+章节刻度、目录、翻页动画、音量键/键盘翻页、EPUB 划词高亮+笔记、TTS 全套、TXT 导入、Markdown 导出、书架语义（最近阅读/进度/移出）。
- **两条已定稿的 ⚠️ 预案**（与 W5 三 ⚠️ 同流程，收官判定时按此口径）：
  1. **EPUB 仿真/覆盖翻页**：Readium 内核只支持滑动，容器快照方案伪影风险高。定稿 = 覆盖/仿真只在自研页引擎（PDF/CBZ）实现；EPUB 提供「滑动（默认）/ 无动画」两档。EPUB 仿真收官记 ⚠️ → backlog。
  2. **EPUB 划词选择**：Readium 3.4.0 `EpubNavigatorFragment` 的选择/Decoration 能力未经验证。T8 首步为时限 spike（≤半天）：可用则走官方 Decoration API；不可用则降级 = JS 注入 selectionchange + Range→locator 自算（持久化仍走 Readium locator，不引入像素坐标）。
- **取证纪律**（沿用 W5）：① K60 行为日志一律流式 `adb logcat --pid=<pid>` 落盘（dump 会被 MediaCodec 刷屏冲掉；Timber tag=调用类名；PID 随重装变更需重开流）；② 装机前核对 APK mtime 晚于目标合并；③ 设备用后停播放（media_session state=NONE + force-stop 兜底）。
- **环境**：`export JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; export GRADLE_USER_HOME='F:\Develop\gradle-home'`；构建 `./gradlew.bat :app:phone:assembleDebug --console=plain --max-workers=2`；CI 仅 master push/PR 触发。
- **本次质量门**：相关模块单测 + `ktfmtCheck` + `:app:phone:assembleDebug` 绿；核心链路（打开/翻页/控制层/目录跳转/高亮笔记/TTS/导入）K60（`8e875894`）真机取证；生产 jellyfins 永久只读（App 播放/阅读链路自身请求除外）。
- **设计分权**：一切视觉/动效/布局决策以本文档「设计定稿」八节为准（Kimi 亲自定稿），deepseek 零设计权。
- **W6 开工提示词存档**（本会话原始指令，供追溯）：

```
继续 Aurorama 2.0.0 W6 阅读域开工会话。你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_REQUIREMENTS.md §5（V2-READER
全 48 条）与 §15 波次表（W6 阅读域 ← W3）→ docs/V2_DESIGN.md → 记忆库 MEMORY.md。
W5 参考口径（只读状态块，不通读）：docs/V2_W5_TASKS.md。
现状：W5 音乐域已正式收官（2026-11-21，HEAD 以 git log 为准），全波判定 50 条 = ✅47/⚠️3/❌0；
三条 ⚠️ 已有去向（18 渐进加载→W7 观察、35 内嵌歌词/RG 入库→backlog、49 本地缺失标记观感→观察项）。
本会话任务（沿用波次制，一天做不完的回写状态后交接）：
① W6-T0 现状核对：§5 全 48 条逐条核对 modes/book 现状（✅/⚠️/❌+代码位置归证），
   阅读器模块在 modes/book，设计 token 与动效沿用 W3/W4 落地件；
② 设计定稿：视觉/动效/布局/交互规格由 Kimi 亲自定稿（可借助设计类 skill），deepseek 零设计权；
③ 任务卡定稿：每卡自包含（目标/文件边界/验收要点/门禁/红线），新建 docs/V2_W6_TASKS.md
   （状态块在顶部），本提示词移入其状态块存档。
纪律：每任务一子代理一 commit；子代理禁 adb，只模块单测+ktfmtCheck+构建（--max-workers=2）；
核心链路 K60（8e875894）真机取证由 Kimi 做——行为日志一律流式 adb logcat --pid=<pid> 落盘
（dump 会被 MediaCodec 刷屏冲掉；Timber tag=调用类名；PID 随重装变更需重开流）；
装机前核对 APK mtime 晚于目标合并；设备用后停播放（state=NONE + force-stop）。
红线：生产 jellyfins.zhangwenkang.com 永久只读；只删自己创建的文件。
交接：收口按 AGENTS.md 五件事，含为下一任务写开工提示词（放 PROJECT_PLAN 焦点节）。
```

## T0 现状核对（2026-11-21，Kimi 亲自，对照 §5 全 48 条）

图例：✅ 已具备 / ⚠️ 部分具备（差距见注）/ ❌ 缺失。归证路径相对仓库根，`modes/book/.../reader/` 简写为 `reader/`。

### §5.1 沉浸模式与控制层（01–06）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 01 | ❌ | 顶栏常驻：`reader/ReaderScreen.kt:175` `ReaderTopBar` 始终渲染，无沉浸态概念。 |
| 02 | ❌ | 单击无控制层；现有手势 = 双击三分区翻页 + 纵向亮度拖（`reader/ReaderGestures.kt:147`）；无「下滑关闭」。 |
| 03 | ❌ | 现顶栏 = 返回 + 书名 + 设置（`ReaderScreen.kt:530-575`），无控制层、无「更多」。 |
| 04 | ❌ | 无底部进度条/功能区；功能全部收进设置面板工具区（`reader/ReaderSettingsPanel.kt:169-216`）；PDF/CBZ 仅有极简页指示。 |
| 05 | ❌ | 无目录（Readium `tableOfContents` 未消费，全模块 grep 零命中）；抽屉无从谈起。 |
| 06 | ⚠️ | 双页已有：EPUB TwoColumn（`ReadiumEpubView.kt:59` 压 `--RS__colCount`）+ PDF/CBZ 双页拼版（`reader/SpreadLayout.kt`）；**缺方向锁定**（全模块无 `requestedOrientation`）。 |

### §5.2 翻页引擎与阅读模式（07–14）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 07 | ✅ | 三模式 Scroll/Paged/TwoColumn（`reader/ReaderSettings.kt:34`），Readium locator 锚点切换保位（`ReadiumEpubView.kt:71` liveLocator 接回）。 |
| 08 | ❌ | EPUB = Readium 内置滑动；PDF/CBZ = `HorizontalPager` 默认滑动（`reader/SimpleBookView.kt:494`）；无覆盖/仿真，无可选动画设置。 |
| 09 | ⚠️ | 三分区判定存在（`ReaderGestures.kt:54` `readerTapTarget`），但绑定在**双击**、中区动作 = 单双栏切换而非控制层；分区不可反转/关闭。 |
| 10 | ❌ | 无音量键翻页（全模块无 VOLUME 引用）。 |
| 11 | ✅ | Readium 滚动模式连续纵向无翻页动画；进度按 `totalProgression`（`ReaderViewModel.kt:659`）。 |
| 12 | ❌ | 无自动翻页/自动滚动（SHOULD）。 |
| 13 | ✅ | Readium 内核即「CSS 多栏 + JS 控制容器位置」架构（`EpubNavigatorFragment`），章节按 spine 顺序；由内核承担而非自研。 |
| 14 | ⚠️ | 设置变更即时重排并以 locator 保位 ✅（`toEpubPreferences` 整套提交）；**无落盘分页缓存**（key=书+字号+行距+字体+视口 的缓存不存在）。 |

### §5.3 排版、主题与亮度（15–19）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 15 | ✅ | 设置 = `ModalBottomSheet`（`ReaderScreen.kt:330`），修改不关闭、`submitPreferences` 即时预览（`ReadiumEpubView.kt:95`）；分组为显示/字体/对齐/主题/页面/工具，与「字体/布局/主题」三组口径略异（实现时按定稿重组）。 |
| 16 | ⚠️ | 有：字体族（出版方/衬线/无衬线/等宽）、字号、行距、边距、对齐（`ReaderSettings.kt`）；**缺**：字重、段间距、首行缩进、连字符；无内置字体文件（仅系统族）。 |
| 17 | ⚠️ | 纸色/护眼/深色/OLED/跟随 五套齐全（`ReaderTheme`，`ReaderSettings.kt:49`）；但每套**不可单独改**背景/正文/链接/高亮色（固定 token）。 |
| 18 | ✅ | `ReaderTheme.System.resolve`（`ReaderSettings.kt:57`）。 |
| 19 | ✅ | 窗口级亮度手势 + HUD（`ReaderScreen.kt:118-153`、`ReaderGestures.kt:74-83`），独立于系统亮度、离开即恢复；滚动档仅左 25% 窄带、分页档全屏；设置面板内无亮度滑块（T5 补）。 |

### §5.4 目录、进度与书签（20–22）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 20 | ❌ | 无目录 UI（`tableOfContents` 未消费）。 |
| 21 | ❌ | 无可拖进度条、无章节刻度、无拖动浮层。 |
| 22 | ⚠️ | 书签增删跳齐全（仅 EPUB：`ReaderViewModel.kt:904-941` + `BookmarkPanel`）；**无**双击加书签、**无**进度条标记；PDF/CBZ 无书签（`bookmarksAvailable` 仅 Rich）。 |

### §5.5 划词、高亮与笔记（23–31）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 23 | ❌ | EPUB 未启用文本选择（`ReadiumEpubView` 无 selection 接线）；PDF 批注为矩形框选非文本选区。 |
| 24 | ❌ | 无浮动工具栏。 |
| 25 | ❌ | 批注模型无颜色字段（`reader/ReaderAnnotation.kt:32`）。 |
| 26 | ⚠️ | PDF 矩形批注可点按改备注/删除（`ReaderAnnotationSheet.kt`）✅ 半；EPUB 高亮渲染不存在。 |
| 27 | ⚠️ | 现有字段：itemId/pageIndex/rect/note/createdAt/updatedAt；**缺** chapterHref、range/CFI、highlightColor。 |
| 28 | ⚠️ | PDF 归一化矩形（0–1）与排版无关 ✅；EPUB 侧标注不存在，「字号变更后还原」整体不满足。 |
| 29 | ❌ | 模型无章节树/元数据预留字段。 |
| 30 | ❌ | 无 Markdown 导出（SHOULD）。 |
| 31 | ⚠️ | PDF 全书搜索 + 上下文 + 跳页齐全（`reader/PdfSearch.kt`、`ReaderSearchSheet.kt`）；EPUB 无书内搜索（本条 SHOULD）。 |

### §5.6 听书 / TTS（32–36）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 32–36 | ❌ | 全套缺失：全仓无 `TextToSpeech` 引用；无控制条、无句级高亮、无定时关闭、无 `onRangeStart` 接线。 |

### §5.7 导入与书架（37–39）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 37 | ⚠️ | EPUB 经 Jellyfin 库或本地 SAF 媒体库打开（`ReaderViewModel.kt:584` `openLocal`），Readium 提取元数据 ✅；**TXT 无支持**（`BookFormat` 无 TXT 分支）；无「文件选择器导入进书架」流程。 |
| 38 | ✅ | 进度/书签/批注均按 itemId（本地书 `localItemIdFor`，`ReaderViewModel.kt:585`）。 |
| 39 | ⚠️ | 书架 Tab = 服务器书籍库复用 `LibraryScreen`（`app/phone/.../film/BookshelfScreen.kt:80`）：有封面/书名；**无**作者、进度百分比、「最近阅读」排序、移出/加入书架语义；本地书架不存在。 |

### §5.8 数据对象、非功能与范围（40–48）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 40 | ⚠️ | `Publication`（Readium）✅、`ReadingProgress`（locator+progression+ticks，`data/.../ReaderRepository.kt:121`）✅、`ReaderSettings` ✅；`Annotation` 字段不齐（见 27/29）。 |
| 41 | ✅ | locatorJson+progression 同一链路存/同步（`ReaderViewModel.kt:1066` `persistProgression`）；页码仅派生展示。 |
| 42 | ⚠️ | 解析全在 IO 线程 + 远程流式首开 ✅（`openFromBestSource`）；**无分页落盘缓存**，二次打开靠 Readium 重排。 |
| 43 | ⚠️ | 本地 PDF `PdfRenderer` 按页懒加载 ✅（`reader/PageSource.kt`）；**远程 PDF 不流式**，回退整本下载（`ReaderViewModel.kt:366-369`）。 |
| 44 | ⚠️ | 对比度有 W1 token 门禁（`CinefinThemesTest`）✅；系统动态字体缩放未显式支持；键盘/方向键翻页无（D-pad 工具在 `core/.../ComposeUtils.kt`，仅 TV 用）。 |
| 45 | ✅ | 阅读/翻页/批注全本地；进度离线暂存 + 联网回传（`flushPendingProgress`）；TTS 缺失另计 32–36。 |
| 46 | ❌ | P0 闭环三环节缺失：TXT 导入、EPUB 划词高亮+笔记、TTS 句级高亮。 |
| 47 | ❌ | P1 全未做：仿真翻页/自动滚动/导出/多色高亮/词典翻译/EPUB 书内搜索（SHOULD）。 |
| 48 | ✅ | 未做 HTML/UMD/FB2/CHM，符合非目标；CBZ 为 1.4.x 既有能力保留（不计超范围）。 |

**合计：✅9 / ⚠️17 / ❌22**（MUST 40 条中 ❌18、⚠️14、✅8；SHOULD 7 条中 ❌4、⚠️2、✅1；LATER 1 条 ✅）。

## 设计定稿（Kimi 亲自定稿，2026-11-21；deepseek 零设计权）

> 总原则：阅读器正文层归阅读域（5 套阅读主题自持），外壳/控制层沿用 W1/W3/W4 落地 token（`CinefinTheme(domain=Book)`、`MediaBook`、玻璃/弹簧）；动效全部落在 150–300ms，弹簧可中断，尊重系统「减少动态效果」（降级为淡入淡出/短 tween）。所有尺寸走 4/8 网格，圆角只用现有档位（按钮 16 / 卡片 20 / 玻璃容器 32）。

### 定稿一 · 沉浸与控制层（READER-01/02/03/04/05，T1/T2 依据）

1. **默认沉浸态**：进入阅读页即隐藏顶栏与底部功能区；`ReaderActivity` 进沉浸（`WindowInsetsControllerCompat` 隐藏状态栏+导航栏，手势滑动可短暂唤出系统栏）。正文占满全屏，无任何固定文字元素。
2. **控制层结构**（唤出时，上下两片）：
   - 顶片（高 56dp + 状态栏 inset）：`← 返回`（左，图标按钮 48dp）｜书名（中，TitleMedium，单行省略）｜`更多 ⋯`（右，48dp）。**只有这两个动作**；「更多」开 bottom sheet 收：目录、书签列表、书内搜索、标注列表、下载/流式状态、听书入口（T9 就位后）。
   - 底片：上排 = 全书进度条（见定稿二）；下排 = 四个功能入口（图标 + 12sp 标签，触控区 ≥48dp，均分宽度）：`目录` `书签` `进度`（聚焦进度条）`设置`。下载/流式状态徽标放进度条左端（8dp 圆点 + LabelSmall 文案，不占入口位）。
   - 容器：深色/OLED 家族 = `glass`（`rgba(10,16,28,.58)` + blur 24）+ `glassBorder` 发丝线；纸色/护眼 = `chromeColor` 96% 不透明（纸面观感不打毛玻璃）。圆角 20（顶片只下圆角、底片只上圆角）。
3. **唤出/关闭手势**（与翻页手势的仲裁见定稿三）：
   - 单击屏幕中 1/3 → 唤出；控制层可见时单击正文任意处 → 关闭；控制层上沿下滑（位移 >48dp 或速度 >800dp/s）→ 关闭。
   - 打开动画 200ms（`fadeIn` + 上下各 24dp 位移），关闭 160ms，无回弹；减少动态 = 纯淡入淡出 120ms。
   - 系统返回键：控制层可见先关控制层，不可见才退出阅读页（BackHandler 拦截）。
4. **单手可达**：四个入口集中在底片下半部；目录用**底部半屏抽屉**（`ModalBottomSheet` skipPartiallyExpanded=false，默认半屏可拖全屏），不做左侧抽屉。
5. 顶片/底片唤出期间正文**不位移、不缩放**（覆盖式叠加），避免每次唤出引发 EPUB 重排。

### 定稿二 · 全书进度条、目录与书签标记（READER-20/21/22，T2/T3 依据）

1. **进度条**（底片上排，高 44dp 触控区，轨道 3dp，thumb 12dp 圆点）：
   - 值 = `totalProgression`（EPUB）/ 页索引换算（PDF/CBZ，与现有进度语义一致）。
   - 拖动中浮层（进度条上方 8dp，胶囊：圆角 16、容器同控制层）：章节名（单行省略）+ 第二行 `xx.x%`；分页引擎显示 `第 X 页 / 共 Y 页`（EPUB 页数来自 Readium `positions`，不可用时只显百分比）。
   - 章节分界刻度：2dp 高、1dp 宽的竖 tick，按各章起始 progression 标在轨道上（EPUB 用 `positions` 按资源分组聚合；PDF/CBZ 无章节概念则不画刻度）。
   - 书签标记：4dp 圆点叠在轨道上对应 progression 处，颜色 = 阅读域 accent；点击进度条书签点 = 跳转（与列表跳转同链路）。
   - 拖动松手 → 跳转到目标 locator/页码；拖动过程不反复 go（只在松手执行一次）。
2. **目录抽屉**（底部半屏）：多级嵌套（每级缩进 16dp），当前章行高亮（accent 12% 底 + 左侧 3dp 竖条）；点击 = 立即跳转并关闭抽屉；行尾「已读」标记 = 该章 progression 区间整体小于当前位置时显 accent ✓（可选开关放设置「目录显示已读」，默认开）。加载/空态/错误三态按 V2-UX-10 补齐。
3. **书签**：控制层底片 `书签` 入口开列表（沿用现有 BookmarkPanel 能力：添加当前位置/跳转/删除）；**双击正文 = 快速添加/取消书签**（当前位置已有书签则取消，弹 900ms 轻提示胶囊「已加书签 / 已取消」；双击翻页手势因此移除，见定稿三手势重排）。

### 定稿三 · 手势与翻页动画（READER-08/09/10/44，T1/T4 依据）

1. **手势重排（全格式统一）**：
   | 手势 | 动作 |
   |---|---|
   | 单击左 1/3 | 上一页（分区可反转/可关，设置项） |
   | 单击中 1/3 | 唤出/关闭控制层 |
   | 单击右 1/3 | 下一页（同上） |
   | 双击正文 | 添加/取消书签（EPUB 先行；PDF/CBZ 书签随 T2 扩展） |
   | 左缘纵向拖动 | 亮度（滚动档左 25% 窄带，分页档全屏——沿用现状） |
   | 长按 | 划词选择（EPUB，T8）/ 矩形批注模式（PDF 沿用） |
   | 横向拖动 | 分页档翻页（Pager/Readium 原生），滚动档不拦截 |
   - 三分区设置项：`反转左右` `关闭点按翻页`（关闭后单击任意处 = 控制层）。
   - 音量键翻页：设置开关（默认关），分页档上下键 = 上/下页，滚动档 = 按行滚动；仅在阅读页 Activity 内生效。
   - 键盘/模拟方向键：←→/↑↓/PgUp/PgDn = 翻页/滚动，Enter = 唤出控制层（走 `onKeyEvent`，复用 core 键值表思路，不依赖 TV 模块）。
2. **翻页动画三档**（设置「翻页动画」：滑动(默认)/覆盖/仿真）：
   - **自研页引擎（PDF/CBZ）**：滑动 = 现有 Pager；覆盖 = 新页从右（RTL 从左）以 240ms `tween(FastOutSlowIn)` 平移盖入，旧页静止 + 8% 压暗；仿真 = 卷页角拖动跟随（拖动距离驱动卷角半径），松手过 40% 阈值自动完成 260ms、否则 180ms 弹回；**仿真可关且不作默认**。
   - **EPUB（Readium）**：仅「滑动(默认)/无动画」两档（Readium `animated` 参数）；覆盖/仿真 EPUB 记 ⚠️ → backlog（预案见状态块）。
   - 滚动模式无翻页动画（READER-11 不变）。
3. **方向锁定**（READER-06）：设置项三档 `跟随系统(默认)/锁定竖屏/锁定横屏`，写 `pref_reader_orientation`，`ReaderActivity` 启动与变更时设 `requestedOrientation`；横屏 + 分页档 EPUB 默认双栏（沿用 TwoColumn），PDF/CBZ 沿用双页拼版。

### 定稿四 · 排版设置扩展（READER-15/16/17/19，T5 依据）

1. **面板重组为三组**（底部弹出层不变，改后三组 + 工具区）：`字体`（字族/字号/字重）｜`布局`（行距/段间距/页边距/首行缩进/两端对齐/连字符/翻页方向）｜`主题`（五套缩略图 + 自定义色 + 跟随系统 + 亮度滑块）。工具区（下载/书签/搜索/批注/目录）保持现状位置。
2. **新增排版字段**（全部映射 Readium `EpubPreferences` 支持的子集；Readium 不支持的字段先做模型+持久化并标注 ⚠️，不在面板暴露）：
   - 字重：400/500/700 三档（`fontWeight`）；
   - 段间距：0–2em 滑块（`paragraphMargins`）；
   - 首行缩进：开关（`paragraphIndent`，开 = 2em）；
   - 连字符：开关（`hyphens`，默认关）。
   - 内置阅读字体：沿用系统族四档（出版方默认/衬线/无衬线/等宽），**2.0.0 不打包字体文件**（包体红线），「2–3 款内置字体」记 ⚠️ → backlog。
3. **主题自定义色**：每套阅读主题下展开「自定义」区：背景色/正文色各 6 个预设色板（从阅读域 token 派生的协调色）+ 「默认」还原；链接色与高亮色跟随 accent（不单独开色板，README 口径：链接=accent、高亮=accent 低透明）。自定义值持久化在 `pref_reader_theme_custom_<theme>`（JSON），不参与 App 主题。
4. **亮度**：主题组内加亮度滑块（0–100%，与手势同一窗口亮度源）；左缘手势保留。

### 定稿五 · EPUB 划词高亮与笔记（READER-23–29，T8 依据）

1. **数据模型**（新 `BookAnnotation`，与 PDF 矩形批注并存不合并）：
   `id / bookId / chapterHref / locatorJson(range 定位，整条 Readium Locator JSON 序列化——3.x 无 CFI 概念) / highlightColor(具名枚举 yellow|blue|pink|green) / noteText / selectedText(≤200 字，列表展示与导出用) / createdAt / updatedAt`。
   持久化：`filesDir/reader/epub_annotations/{itemId}.json`（沿用 PDF 批注的文件制+版本号模式，version=1）。定位只存 CFI/locator，**禁止像素坐标**；章节树与元数据字段（`chapterTitle`、`spineIndex`）一并写入，供导出。
2. **交互**：长按正文 → 系统选择手柄 + 自定义浮动工具栏（选区上方，贴边翻转下方）：`高亮(展开四色) | 想法 | 复制 | 搜索 | 词典`（词典=调系统 `ACTION_DEFINE`/网页搜索 Intent，失败降级复制链接，不内置在线服务）。点击已有高亮 → 底部小面板：改色（四色）/编辑笔记/删除/跳转。
3. **渲染**：Readium `Decoration.Style.Highlight`（模板 alpha 0.28、`cornerRadius` 2dp——spike 已证实圆角原生支持），tint = 高亮枚举色；不遮字（底色混合，观感偏暗则开 `experimentalPositioning` 压到文字下）。
4. **spike 前置**：✅ 已完成（2026-11-21 会话2），结论 = 官方 Decoration API 可用（方案 A），选择与精度细节见状态块「T8-spike 结论」；降级方案（JS 注入 selectionchange 自算）仅保留为历史备选，不启用。

### 定稿六 · TTS 听书（READER-32–36，T9 依据）

1. **引擎**：系统 `TextToSpeech`（离线可用，符合 READER-45）；语速 0.5–2.0×、音色 = 系统可用 voice 列表。句切分：按资源提取纯文本后按句读标点切分（中/英双语规则），句 → Readium locator 映射（`publication` 文本定位；映射失败降级 = 只读不高亮，记观察项）。
2. **控制条**（底部迷你条，高 56dp，容器同控制层底片）：`⏮ 上一句 | ▶/⏸ | ⏭ 下一句 | 语速 | 定时`；从「更多 → 听书」或控制层底片入口启动，起点 = 当前位置（locator 最近句）或章首（二选一弹窗）。
3. **句级高亮**：当前句以阅读域 accent 18% 底色（Decoration），边界对齐 `onRangeStart` 回调；跳章自动衔接下一资源。「回到原文」按钮 = 跳转当前朗读句 locator；点正文任意处 = 定位到该处继续读（设置项「点按正文跳转朗读位置」默认开，暂停/保持播放 = 设置项默认保持）。
4. **定时关闭**：15/30/60 分钟 + 「本章结束后停止」；到时淡出不停字（系统 TTS 无音量包络则直接 stop，记观察项）。
5. 后台播放走前台 Service + MediaSession 通知（复用音乐域通知骨架，不含队列）。

### 定稿七 · TXT 导入、导出与书架（READER-30/31/37/39，T7/T10 依据）

1. **TXT 支持**（T7）：`BookFormat` 加 `Txt` 嗅探分支（非 ZIP/非 PDF 且 UTF-8/GBK 可解码 → TXT）；导入时一次性转为**内存/缓存 EPUB**（按可配置分章正则切 spine，默认 `第[0-9一二三四五六七八九十百千]+[章节回卷]` 等常见中式章头，正则持久化可在设置改），之后走 Readium 全链路（翻页/设置/高亮/TTS 自动继承）；进度按生成书籍的稳定 ID（`localItemIdFor(uri)`）绑定。
2. **导入入口**：书架页 FAB「导入书籍」→ 系统文件选择器（EPUB/TXT）→ SAF 持久授权 → 进本地书架（复用 W37 本地媒体库索引，不拷贝源文件）。
3. **书架增强**（T10）：书架页顶部加「最近阅读」行（按 `ReadingProgress.updatedAt` 倒序，横滑卡片：封面/书名/进度百分比）；书籍条目补作者与进度（有进度显 `xx%`，无显「未读」）；长按条目 = 移出书架（本地书）/ 详情。服务器书籍库的「加入书架」语义 = 本地收藏标记（`pref_reader_shelf_<serverId>` 集合），移出 = 从书架页隐藏（不删服务器数据，红线）。
4. **书内搜索（EPUB）**（T10，SHOULD）：按资源顺序扫描纯文本，返回 章节名 + 上下文片段（前后各 30 字）；点击跳转并临时高亮命中（Decoration 一次性，3s 淡出）。
5. **Markdown 导出**（T10，SHOULD）：整书标注按章节树导出 `.md`（头部：书名/作者/导出时阅读进度百分比；章节分组、每条 = 引文 + 笔记 + 高亮色），分享 Intent 发出。

### 定稿八 · 动效清单与风险台账

| 动效 | 参数 | 解释的状态变化 |
|---|---|---|
| 控制层开/关 | 200/160ms fade+24dp 位移 | 沉浸 ↔ 控制态 |
| 抽屉/面板开合 | 200/160ms（M3 sheet 默认弹簧） | 工具层出现/消失 |
| 覆盖翻页 | 240ms FastOutSlowIn + 旧页 8% 压暗 | 页码推进 |
| 仿真翻页 | 跟随拖动；完成 260ms / 弹回 180ms | 页码推进/取消 |
| 双击书签提示 | 900ms 胶囊淡入淡出 | 书签集合变更 |
| 搜索命中高亮 | 3s 淡出 | 临时定位标记 |
| TTS 句高亮 | 200ms 底色淡入 | 朗读位置推进 |

**风险台账**：
- R1（高）：EPUB 划词选择能力未验证 → T8 spike 前置，降级方案已定稿（定稿五 §4）。
- R2（中）：EPUB 覆盖/仿真不支持 → 已定稿 ⚠️ 口径（状态块预案 1）。
- R3（中）：TXT 分章正则对非标书失效 → 整书单章兜底 + 正则用户可改。
- R4（低）：Readium `positions` 部分书不可用 → 进度条退化为纯百分比（不定页数）。
- R5（低）：TTS 句 → locator 映射失败书 → 只读不高亮降级（观察项）。

## 任务卡（T1–T11）

> 通用纪律（每卡适用，不再重复）：一个子代理一个 commit；子代理**禁 adb**，门禁 = 相关模块单测 + `ktfmtCheck` + `./gradlew.bat :app:phone:assembleDebug --console=plain --max-workers=2` 绿；实现一律按「设计定稿」对应节执行，不得自由发挥样式；不改设计就不得在任务卡里留自由发挥口子；红线 = 生产 jellyfins 永久只读、只删自己创建的文件。真机取证（K60 `8e875894`）由 Kimi 在合并后统一做。
> 依赖：T1 →（T2、T3、T4、T5、T6 可并行）；T7 独立可首批并行；T8 spike 可首批启动（结论影响 T8 实现与 T9）；T9 依赖 T8 的 locator 映射件；T10 依赖 T2/T7；T11 最后。

### T1 · 沉浸控制层与手势重排（READER-01/02/03/05/09）

- **目标**：默认沉浸全屏；单击中区唤出控制层（顶片=返回+书名+更多、底片占位+四入口：目录/书签/进度/设置），单击正文或下滑关闭；双击三分区翻页改为单击三分区 + 双击书签占位（书签动作 T2 接线，本卡先留回调）；返回键先关控制层。
- **文件边界**：`modes/book/.../reader/ReaderScreen.kt`、`ReaderGestures.kt`、`ReaderActivity.kt`、`ReadiumEpubView.kt`、`SimpleBookView.kt`（手势回调接线）、`reader/ReaderSettings.kt`（三分区反转/关闭两个设置项+持久化）；`app/phone` 不动。
- **验收要点**：进入阅读页系统栏隐藏；中 1/3 单击唤出、再击关闭、下滑关闭（阈值按定稿一 §3）；左/右 1/3 单击翻页；分区反转/关闭设置即时生效；控制层开合动画 200/160ms；`ReaderGesturesTest` 覆盖新仲裁（含双击/单击窗口、分区反转）。
- **门禁**：modes/book 单测 + ktfmt + assembleDebug。

### T2 · 底部功能区 + 全书进度条 + 目录抽屉 + 书签标记（READER-04/20/21/22）

- **目标**：底片进度条（拖动跳转 + 拖动浮层章节名/页码/百分比 + 章节刻度 + 书签标记点）；目录底部半屏抽屉（多级缩进、当前章高亮、点击跳转关闭、已读标记开关）；PDF/CBZ 书签支持（页索引锚点）；双击正文添加/取消书签（T1 留的回调接线）+ 提示胶囊。
- **文件边界**：`ReaderScreen.kt`（底片）、新增 `reader/ReaderProgressBar.kt`、`reader/ReaderTocSheet.kt`；`ReaderViewModel.kt`（positions/toc 暴露、PDF/CBZ 书签存取）；`data/.../ReaderBookmark.kt`（模型如需扩 simple 页锚点——优先复用 locatorJson 空 + progression 语义，避免 data 层迁移）。
- **验收要点**：EPUB 拖动浮层显章节名 + 百分比（positions 可用时页码）；刻度与 `positions` 一致；目录跳转后 locator 与进度条同步；PDF 书签增删跳与 EPUB 同面板；双击书签 900ms 提示；进度条/TOC 纯逻辑（刻度计算、已读判定、页码换算）单测覆盖。
- **门禁**：modes/book（+data 若动）单测 + ktfmt + assembleDebug。

### T3 · 翻页动画三档（自研页引擎）+ EPUB 动画档（READER-08）

- **目标**：设置「翻页动画」：滑动(默认)/覆盖/仿真——PDF/CBZ 三档全实现（覆盖=240ms 盖入+旧页 8% 压暗；仿真=拖跟随+阈值完成/弹回）；EPUB 提供 滑动/无动画 两档（`animated` 参数），面板对 EPUB 隐藏仿真/覆盖并给一句说明。
- **文件边界**：`SimpleBookView.kt`（新翻页渲染）、新增 `reader/PageTurnAnimation.kt`（动画状态机，纯逻辑可测）、`ReaderSettings.kt`（设置项）、`ReadiumEpubView.kt`（animated 档）。
- **验收要点**：三档切换即时生效；仿真松手阈值 40%、完成 260ms/弹回 180ms；RTL 下方向镜像；减少动态 = 全部降级淡入淡出；动画状态机单测（阈值/方向/中断接管）。
- **门禁**：modes/book 单测 + ktfmt + assembleDebug。

### T4 · 音量键 / 键盘翻页 + 方向锁定（READER-06/10/44 键盘半）

- **目标**：音量键翻页开关（默认关，分页翻页/滚动按行）；键盘 ←→↑↓/PgUp/PgDn/Enter 支持；方向锁定三档（跟随系统/竖/横）。
- **文件边界**：`ReaderActivity.kt`（`onKeyDown`/`requestedOrientation`）、`ReaderSettings.kt`（两个设置项+持久化）、`ReaderSettingsPanel.kt`（入口）。
- **验收要点**：开关默认关且仅在阅读页生效；横屏锁定后 EPUB 双栏/PDF 双页拼版正常；键位映射纯函数单测。
- **门禁**：modes/book 单测 + ktfmt + assembleDebug。

### T5 · 排版设置扩展与主题自定义（READER-15/16/17/19）

- **目标**：面板重组 字体/布局/主题 三组 + 工具区；新增字重/段间距/首行缩进/连字符（映射 Readium 支持子集，不支持项模型持久化但不上面板）；每套主题自定义背景/正文色（6 预设 + 还原，按主题独立持久化）；主题组亮度滑块（与手势同源）。
- **文件边界**：`ReaderSettings.kt`、`ReaderSettingsPanel.kt`、`ReaderScreen.kt`、`ReadiumEpubView.kt`（`toEpubPreferences` 扩展）；新增字段进 `pref_reader_*`（`settings` 模块 `AppPreferences`）。
- **验收要点**：每组修改面板不关闭且正文实时预览；自定义色按主题独立保存、切主题不串；字重三档=400/500/700；`ReaderSettingsTest` 覆盖新字段 sanitize/映射/spec 快照。
- **门禁**：modes/book + settings 单测 + ktfmt + assembleDebug。

### T6 · 自动翻页与自动滚动（READER-12，SHOULD）

- **目标**：分页档自动翻页（间隔 5–120s 可设）；滚动档像素级平滑自动滚动（速度实时可调 px/s，非固定跳页）；控制层唤出或手动干预即暂停。
- **文件边界**：`ReaderViewModel.kt`、新增 `reader/AutoAdvance.kt`（纯逻辑节拍器可测）、`ReadiumEpubView.kt`/`SimpleBookView.kt`（执行层）、`ReaderSettings.kt`。
- **验收要点**：间隔/速度实时可调立即生效；手动翻页/唤出控制层自动暂停；节拍器单测。
- **门禁**：modes/book 单测 + ktfmt + assembleDebug。

### T7 · TXT 支持与导入入口（READER-37 TXT 半/46 环节）

- **目标**：`BookFormat` 加 TXT 嗅探（UTF-8/GBK 解码探测）；TXT→缓存 EPUB 转换器（可配置分章正则，默认中式章头集合；无命中整书单章）；书架页「导入书籍」FAB → SAF 选择器（EPUB/TXT）→ 持久授权 → 本地书架打开。
- **文件边界**：`reader/BookFormat.kt`、新增 `reader/TxtToEpub.kt`（纯 JVM 可测）；`app/phone/.../film/BookshelfScreen.kt`（FAB 与跳转）、`NavigationRoot.kt`（接线如需）；`settings`（分章正则偏好）。
- **验收要点**：fixture TXT（含 GBK）转换后 Readium 打开、分章目录正确、进度/设置/高亮链路继承；导入失败有用户可见错误；转换器与嗅探单测（含编码误判防护）。
- **门禁**：modes/book + app/phone 相关单测 + ktfmt + assembleDebug。

### T8 · EPUB 划词高亮与笔记（READER-23–29）

> **spike 已完成（2026-11-21）**：官方 Decoration API 可用，不降级。实现要点（详据见状态块「T8-spike 结论」）：`selectionActionModeCallback` 替换系统选择菜单（`onCreateActionMode` 返回 false 抑制菜单是否保留选区需真机首验，保底返回 true+单条不可见项）；选区快照在选区稳定时**先捕获**（点击回调里系统已 clearFocus）；locator 持久化 = 整条 `Locator.toJSON()`（无 CFI 概念），精确性用 `registerJavascriptInterface` 注入 JS 补算 `cssSelector`/`progression`（放 `otherLocations`）；渲染 `applyDecorations` 整组替换 + `Style.Highlight(tint)`（模板 `cornerRadius=2`、`alpha=0.28`，观感偏暗再开 `experimentalPositioning`）；点击高亮走 `addDecorationListener` 的 `onDecorationActivated`（`extras` 带 annotationId）。FXL 不支持（`supportsDecorationStyle` 判定）。

- **目标**：按定稿五 + 上方 spike 结论实现：长按选择 + 浮动工具栏（高亮四色/想法/复制/搜索/词典 Intent）、`BookAnnotation` 模型与文件持久化、Decoration 渲染、点高亮改色/编辑/删除。
- **文件边界**：新增 `reader/EpubAnnotation.kt`（模型+codec，纯 JVM）、`reader/EpubSelectionToolbar.kt`；`ReadiumEpubView.kt`、`ReaderViewModel.kt`、`ReaderScreen.kt`（标注列表面板并入「更多」）；spike 临时代码不入库。
- **验收要点**：locator JSON 持久化（禁像素坐标）；字号/布局变更后高亮还原（重复文本场景靠注入补算的 cssSelector 保证）；颜色具名枚举落盘；模型 codec/容错单测。
- **门禁**：modes/book 单测 + ktfmt + assembleDebug。

### T9 · TTS 听书（READER-32–36）

- **目标**：系统 TTS 从当前位置/章首朗读；控制条（播放/暂停/上下句/语速/定时）；句级 Decoration 高亮对齐 `onRangeStart`；定时 15/30/60 分 + 本章结束；前台 Service + MediaSession 通知；句→locator 映射（失败降级只读不高亮）。
- **文件边界**：新增 `reader/tts/`（`ReaderTtsController` 纯状态机可测、`ReaderTtsService`、句切分器）；`ReaderViewModel.kt`、`ReaderScreen.kt`（控制条）、`ReadiumEpubView.kt`（高亮与跳转）；通知骨架参考 modes/music 通知实现，不跨模块依赖。
- **验收要点**：句切分中英双语 fixture 单测；高亮边界与回调对齐（K60 取证）；定时各档生效；后台/锁屏控制可用；降级路径有日志。
- **门禁**：modes/book 单测 + ktfmt + assembleDebug。

### T10 · 书内搜索（EPUB）+ Markdown 导出 + 书架增强（READER-30/31/39，SHOULD 打包）

- **目标**：EPUB 全书搜索（章节+上下文片段、跳转+3s 临时高亮）；整书标注按章节树导出 Markdown（头部书名/作者/进度）；书架「最近阅读」行（按进度 updatedAt 倒序）+ 条目作者/进度/未读 + 本地书移出书架 + 服务器书加入/移出书架（本地标记，不碰服务器）。
- **文件边界**：新增 `reader/EpubSearch.kt`、`reader/AnnotationExport.kt`（纯 JVM）；`app/phone/.../film/BookshelfScreen.kt` 与其 ViewModel；`data`（书架标记偏好走 settings 模块，不进 data）。
- **验收要点**：搜索结果含章节名与上下文、跳转后 3s 高亮淡出；导出文件结构符合定稿七 §5；书架排序/进度显示/移出语义正确；搜索与导出纯逻辑单测。
- **门禁**：modes/book + app/phone 相关单测 + ktfmt + assembleDebug。

### T11 · 全量验收（48 条判定）+ 波次收官

- **目标**：对照 §5 逐条销号（✅/⚠️/❌ + 证据），K60 核心链路取证（打开/翻页/控制层/目录跳转/高亮笔记/TTS/TXT 导入）；⚠️ 项裁定去向（观察/backlog）；回写状态块与 `V2_REQUIREMENTS.md` 关联判定、PROJECT_PLAN 焦点移交 W7。
- **负责人**：Kimi（不派子代理）。

### T6r2 · 自动滚动残留两缺陷（K60 取证发现，T11 前必修）

- **目标**：① 末章到底后 goForward 空转不停——书末判定不能只看 `totalProgression ≥0.995`（程序化滚动不刷 locator，book 停在章首 progression），改用「连续 N 次 goForward 后位置 key 无变化」判书末并 stop；② 实测滚速 ~150px/s vs 设定 60px/s（疑 JS `scrollTop += d` 的 CSS px 与引擎 px/s 设备 px 换算差一个 density 或 dt 口径），校准到 ±20%。
- **文件边界**：`ReadiumEpubView.kt`、`AutoAdvance.kt`（+测试）。
- **验收**：日志证实在末章到底后 ≤2 次空转内 stop 并提示；设定 60px/s 实测 48–72px/s。
- **门禁**：modes/book 单测 + ktfmt + assembleDebug。
