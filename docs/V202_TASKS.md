# 2.0.2 任务卡台账

<!-- V202_STATUS_BEGIN -->
> **当前状态（2026-11-26）**：**W0 开工准备完成**——分支 `codex/v2.0.2` 自 `codex/v2.0.1` tip（`91912da8`）建立；设计定稿落 `docs/V202_DESIGN.md`（用户批准 + 当日补充裁定：黑底清理含播放器控件）；本台账建立；AGENTS.md 台账指针切到本文件；`PROJECT_PLAN.md` 焦点切 2.0.2。
> **方案依据**：用户五条诉点（音乐/书籍头部重排、最近阅读割裂、图标黑底、黄橙残留、状态栏/挖孔避让）+ 全局盘点（诊断见 `V202_DESIGN.md` §0）+ 2.0.1 裁定遗留 U1 热修。
> **下一卡**：C1 = U1 更新下载死锁热修（卡内容见下表）。
> **红线**：生产服 `jellyfins.zhangwenkang.com` 永久只读；服务器写只在测试服验证；push/tag/Release 需用户授权。
<!-- V202_STATUS_END -->

## 卡状态约定

`TODO / DOING / REVIEW / DONE / BLOCKED / DEFERRED`；每卡含验收标准与证据要求；实现一律派子代理，设计以 `docs/V202_DESIGN.md` 为准（deepseek 零设计权）。

## W0 开工准备

| 卡 | 状态 | 内容 | 验收 |
|---|---|---|---|
| W0 | DONE | 建分支 `codex/v2.0.2`；设计定稿 `V202_DESIGN.md`（含 2026-11-26 用户补充裁定：播放器控件纳入 D4）；本台账；AGENTS.md / PROJECT_PLAN 焦点切换 | 文档落库、分支在建 ✅ |

## 执行卡（按依赖排序；共享层 C2–C4 → 核心页面 C6–C7 → 台账回归 C8–C9）

| 卡 | 状态 | 内容 | 验收 |
|---|---|---|---|
| C1 | TODO | **U1 更新下载死锁热修**：`UpdateRepositoryImpl.download()` 在 transfer 缺失/不匹配且已存 release 匹配 updateId 时经 `store.beginDownload(release)` 重建后继续（含 READY_FOR_VERIFICATION 短路径）；补「仅 release.json 无 transfer」单测（现有 `UpdateRepositoryTest` 手动预置 transfer 掩盖缺口）；注意 W5 教训：测试改 `Dispatchers.Main` 必须 `@AfterClass` 还原。文件边界：`data/.../update/`（UpdateRepository.kt / UpdateStateStore.kt / UpdateHttpDownloader.kt 只读参考）+ 对应测试 | 新增单测绿 + `:data:testDebugUnitTest` 全绿 + ktfmt + assemble；K60 全链（检查→AVAILABLE→下载进度→系统安装器弹窗）uiautomator/logcat 取证（禁降级/卸载，装机路径先 dumpsys 核实，受阻则上报） |
| C2 | TODO | **D5 批① XML 主题层清零**：`colors.xml` 橙→极光、neutral 阶对齐 bg 阶、删死 token、`themes.xml`/night 同步、window 底色对齐、`activity_player.xml` 死资源核查清理、`player_background`/`player_hud_background` 对齐玻璃色系 | assemble + ktfmt 绿；K60：系统对话框/开关/涟漪无橙、冷启动无闪色、播放器 HUD 正常 |
| C3 | TODO | **D1 安全区统一**：`rememberSafePadding` 下沉 core + `Modifier.cinefinSafeTop()`；`CinefinPageTopBar` 换 safeDrawing 口径；搜索页/冷启动品牌页补顶部；阅读器控制层挖孔核查 | 单测+assemble 绿；K60 竖屏+横屏各页顶栏不压状态栏/挖孔（uiautomator 坐标取证） |
| C4 | TODO | **D4 黑底清理**：BaseBadge / ItemTopBar / CinefinSelectIndicator / 播放器图标键背板+缓冲背板+跳过 chip+字幕提示 chip / 桌面歌词写死色 → 全走 glass/glassBorder token；`DesignTokenConsistencyTest` 加 `Color.Black.copy` 守卫 | core+phone 单测绿（含新守卫）；K60：海报徽标/详情返回键/多选圆点/播放器控件无黑块、浅封面可读 |
| C5 | TODO | **D5 批② Compose 黄橙清零**：加载圈/进度条/选中态归极光，`DownloadRows` 等逐点裁定，`LumenSurface` 光晕改极光系；`UI_DESIGN_SYSTEM.md` 域色表勘误 | 单测+assemble 绿；K60 首页/详情/下载/搜索无黄橙违和（文本+抽查像素取证） |
| C6 | TODO | **D2 音乐头部重排**：core 新建 `CinefinLibraryTabs`；音乐页两行收敛（顶栏图标收 ⋮、标签行 + 库 chip + ⋮、播放全部降 36dp 紧凑工具行）；`MusicPrimaryToolbarLayout` 及其单测更新/退役 | core+music 单测绿；K60：5 tab 横滑/选中指示条/⋮菜单全项/播放全部起播回归 |
| C7 | TODO | **D2 书籍/库头部 + D3 最近阅读入流**：书籍/库页两行收敛（删独立工具行）；最近阅读/本地书籍改 grid header item 随滚动；`LibraryScreen` headerContent 插槽机制改 grid item；视频库页连带统一；W6 基线文档更新 | app/phone 单测绿；K60：书架滚动时最近阅读随动、空态零占位、tab/排序/视图切换回归 |
| C8 | TODO | **D6 台账**：`docs/design/COLOR_ICON_INVENTORY.md`（token 表/图标表/残留登记表）+ 守卫单测补齐 + 文档指针勘误 | 台账生成且与代码抽查一致；单测绿 |
| C9 | TODO | **全量回归 + 变更对账表 + 发布门**：四主题切换无残留色、7 态抽查、D1–D5 逐点对账表回写本台账；发布门（assembleRelease + verifyMpvJniReleaseContract + 全模块单测） | 对账表全绿；版本 bump 与发布另待用户授权 |

## LATER 池（本期不做，裁定见 V202_DESIGN.md「明确不做」）

字阶全站迁移、玻璃实时 blur、新主题（Prism/Nocturne/Monolith）、TV 端、播放器横屏布局。
