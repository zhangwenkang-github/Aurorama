# 2.0.2 任务卡台账

<!-- V202_STATUS_BEGIN -->
> **当前状态（2026-11-26）**：**W0 DONE + C1 DONE**——分支 `codex/v2.0.2`（基线 2.0.1 tip `91912da8`）；设计定稿 `docs/V202_DESIGN.md`（用户批准 + 当日补充裁定：黑底清理含播放器控件）；C1 U1 热修 `a3ed8f5d`：`UpdateRepositoryImpl.download()` 新增 `persistedTransfer()`——transfer 缺失/不匹配时读 `loadDiscoveredRelease()`、`stableUpdateId` 匹配则 `beginDownload` 重建；新增 3 单测（仅 release 无 transfer / 皆无仍 STATE_ERROR / 陈旧 transfer 按新 release 重建）；门禁 :data 533 测 0 败 + ktfmt + assembleLibreDebug 全绿；K60 真机：点击前仅 release.json 无 transfer.json（死锁前置实锤）→ 点击后 transfer.json 重建、83MB 全量落盘、进入校验——**死锁链真机走通**；安装器弹窗未验（debug 包名后缀触发 PACKAGE_MISMATCH，固有限制非缺陷；release 无 AVAILABLE、降版本属红线，以 2.0.1 E2E 证据链佐证）。
> **下一卡**：C2 = D5 批① XML 主题层清零（卡内容见下表）。
> **LATER 登记**：`beginDownload` 重建分支不回收旧 release 的 `.part`/`.apk`（既有行为，C1 实测陈旧 1.3.0 part 残留），列 C8 台账残留登记表或后续小卡。
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
| C1 | DONE（`a3ed8f5d`） | **U1 更新下载死锁热修**：`persistedTransfer()`——transfer 缺失/不匹配时读 `loadDiscoveredRelease()`，`stableUpdateId` 匹配则 `beginDownload` 重建后续原流程；READY_FOR_VERIFICATION 短路径与 cancel/check 语义未动；新增 3 单测。留档：安装器弹窗真机未验（debug 包名 PACKAGE_MISMATCH 固有限制）；旧 release `.part` 残留=既有行为，登记 LATER | :data 533 测 0 败 + ktfmt + assemble 绿；K60：死锁前置实锤（仅 release.json）→ 重建 → 83MB 全量落盘 → 进校验 ✅ |
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
