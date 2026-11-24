# 2.0.1 任务卡台账

<!-- V201_STATUS_BEGIN -->
> **当前状态（2026-11-24）**：W0 已收官（D0/D1/D2/D3 全完成）。分支 `codex/v2.0.1`；master 已对齐 v2.0.0（`312209a6`）；W0-D2 设计定稿落 `docs/V201_DESIGN_FINAL.md`（9 条裁定：域色=身份、极光=行动；琥珀退出交互强调；封面取色只留背景光晕；阅读外框服从 App 主题；玻璃无 blur 为正式决定；主题选择器加「跟随系统」）。
> **下一卡**：W1 修复快线 T01–T08 可并发派子代理（T03/T04 已派出）；W2 T09 按定稿三批执行（依赖 `V201_DESIGN_FINAL.md` §2）。
> **工作流（2026-11-24 用户拍板）**：① 下载类任务分散到功能波次，不设独立下载波次；② adb 取证与简单修复下放子代理，负责人只审证据；③ 负责人任务量按上下文消耗弹性排，不固定卡数；④ 负责人只做规划/复杂排错/设计定稿/证据终审；⑤ 波次按依赖与风险排，不照搬需求编号；⑥ 所有主题必须落卡，收口逐卡核销；⑦ 新需求按内容归属插入最合适波次（可插队任意未开始波次），每波次预留 ~20% 缓冲。
> **红线**：生产服 `jellyfins.zhangwenkang.com` 永久只读；服务器写只在测试服验证；push/tag/Release 需用户授权。
<!-- V201_STATUS_END -->

## 卡状态约定

`TODO / DOING / REVIEW / DONE / BLOCKED / DEFERRED(2.0.2)`；每卡含验收标准与证据要求。

## W0 开工准备

| 卡 | 状态 | 内容 | 验收 |
|---|---|---|---|
| W0-D0 | DONE | 建分支 `codex/v2.0.1`、建本台账、AGENTS.md 工作流制度化（`87d6470c`）、PROJECT_PLAN 焦点切换+交接提示词 | — |
| W0-D1 | DONE | 设计审计完成：22 条偏差（P0 5/P1 8/P2 8/P3 1）落 `docs/V201_DESIGN_AUDIT.md`；根因=琥珀角色越界（Neutral→MediaFilm 总开关 + M3 primary=域色 + K1 极光渐变未落地）+ 评分徽标黄铜 + 封面取色/纸页棕。9 条不确定项已由 D2 裁定 | 偏差清单已产出；D2 已逐条裁定并分派到卡 |
| W0-D2 | DONE | 设计定稿落 `docs/V201_DESIGN_FINAL.md`：9 条裁定（域色=身份/极光=行动、琥珀退出交互强调、封面取色只留背景光晕、阅读外框服从 App 主题、玻璃无 blur 正式决定、选择器加跟随系统、评分徽标改极光淡色 pill）+ T09 token 三批清单 + T10 图标规范 + W3 音乐工具行/歌曲行/多选/队列/播放页交互稿；R1 已加状态块 | token 修改清单 + 交互稿 ✅ |
| W0-D3 | DONE | git 治理：master 独有资产已移植（`f541b249`，无内容丢失）；用户确认后 master 已对齐 `v2.0.0`（`312209a6`，reset --hard）；此后每次发布合并一次；远端 master 推送待用户授权 | master=v2.0.0 ✅ |

## W1 修复快线（根因已定位，子代理为主）

| 卡 | 需求# | 状态 | 内容 | 验收 |
|---|---|---|---|---|
| T01 | #1 | TODO | 更新失效：`UpdateWorkScheduler` KEEP→REPLACE + 排队态回显；`UpdatePackageVerifier` digest 缺失不删包（改警告/或发布流程补 digest）；manifest 加 `<queries>`；`GitHubReleaseDto` 加 `body` + UpdateScreen 渲染更新说明（Markdown） | 真机 2.0.0→2.0.1-rc 真实升级：进度可见、安装成功、说明显示 |
| T02 | #10 | TODO | 管理员入口不可见：adb 取证 `policy.isAdministrator` 实际值与缓存（`JellyfinRepositoryImpl.kt:2396-2420`、`DrawerViewModel`），按证据修 | admin 真机可见并进入控制台/资料管理 |
| T03 | #3 | DONE | `core/.../drawable/ic_logo.xml` 已换 B「极幕之眼」（`52d57030`，与启动图标同源 ×0.2 构图，10 处引用全核对）；已知留档：渐变方向随启动图标渲染（与设计稿 objectBoundingBox 旋转差异，连启动图标一起改才算对齐，列 LATER）；Light 主题关于页 120dp 对比度偏低（列 LATER） | K60 装机+uiautomator 取证顶栏/抽屉 logo 节点正常，截图 2 张已删 ✅ |
| T04 | #4 | TODO | 图片缓存默认 50MB（`AppPreferences.kt:262`）→ 默认无限制（0=不设 maxSizeBytes）；存量值不迁移，设置项注明 | 全新安装默认无限制 |
| T05 | #16 | TODO | 节目页静默刷新：`ShowViewModel.loadShow` 不再先清空 seasons/actors（`:48-58`），新数据到位再替换；UI 不闪、位置不变 | 真机播放返回：不黑不闪、位置保持、已看勾更新 |
| T06 | #17 | TODO | 多选下移：`SeasonScreen.kt:344` hero `item{}` 加稳定 key | 真机长按照片首屏位置不变 |
| T07 | #21 | TODO | 跨季误下载：先只读取证《学生会的一己之见》成因（卡面 S01/S02 判别）；`JellyfinRepositoryImpl.kt:1774-1786` 加 resolvedSeasonId 一致性过滤 | 第一季下载清单不含第二季集；单测覆盖 |
| T08 | P1 | TODO | 搜索音乐结果卡片点击回库根页+左侧导航消失（2.0.0 遗留首修） | 真机：进正确详情、导航保留 |

## W2 设计基础（依赖 W0-D2 定稿）

| 卡 | 需求# | 状态 | 内容 | 验收 |
|---|---|---|---|---|
| T09 | #9 | TODO | 主题还原：按 `docs/V201_DESIGN_FINAL.md` §2 三批执行——批① 角色收口（Neutral→MediaAurora、M3 解耦域色、语义 token、评分徽标）、批② 组件换肤（Aurora tone/进度/选中/开关滑杆/SectionHeader）、批③ 细节（页边距/玻璃注释/字幕面板色） | 真机各域页面无黄橙违和色；偏差清单核销；对比度测试绿 |
| T10 | #18 | TODO | 图标规范：去页面自绘方框磁贴底，抽公共扁平图标组件；图标/文字窄描边（或阴影）token | 复杂背景下图标文字可读；无方框底 |
| T11 | #12 | TODO | 抽屉误触：`CinefinDrawer.kt` 整屏 anchoredDraggable → 加边缘触发区（左缘 ~24–32dp）或提高阈值 | 真机上下滑动不误触，左缘仍可呼出 |
| T12 | #13 | TODO | 滑杆防误触：`CinefinSlider` 加垂直手势优先判定 | 真机垂直滚动不改滑杆值 |
| T13 | #2 | TODO | 设置页重布局：页内已有的播放设置去重，重排 5 组；按 W0-D2 稿 | 无重复设置项；结构按定稿 |

## W3 音乐模块重塑（依赖 W2 token；可拆 a=T14–T17 / b=T18–T20）

| 卡 | 需求# | 状态 | 内容 | 验收 |
|---|---|---|---|---|
| T14 | #7 | TODO | 工具行重构（`MusicModeScreen.kt:1564`）：收纳下拉菜单+图标化；排序/筛选（对标官方 app）；tab 与官方一致（加「建议」）；翻页+每页数量可设置 | 真机各项功能可用；官方 app 对照 |
| T15 | #8 | TODO | 歌曲行（`:2382`）：去序号；左侧=加歌单/收藏/⋮竖三点；菜单对标官方全量 | 真机对照官方 app 功能齐 |
| T16 | #6 | TODO | 去显式「多选」按钮（`:1599`、`:1376-1383`），统一长按进多选；清理死代码 `onBatchLongPress`；全局排查 | 全 app 无多选按钮，长按可进 |
| T17 | #14 | TODO | 队列语义：`playSong`（VM `:2480`）点单曲=追加队尾并起播；仅「全部播放」整列加载；队列快照失效策略=仅剔确认过时项 | 真机行为符合；RELEASE_NOTES 明示变化 |
| T18 | #11 | TODO | 播放页布局：修文字重叠（`MusicNowPlayingScreen.kt:1220-1265` 无权重 Row；`:550/:566` 不可滚动）；队列/音效入口去重；隐藏底栏；底部按钮不被遮 | 真机三布局无重叠、单入口、无遮挡 |
| T19 | #5 | TODO | 服务器歌单补改名/删除：封装 updatePlaylist/deletePlaylist，PlaylistList ⋮ 对服务器歌单开放；清理 `PlaylistServerExchangePort` 陈旧注释 | 测试服真机：建/改名/删/加曲/移曲全通 |
| T20 | #15 | TODO | 元数据：封装 `/Items/{id}/Refresh`；无封面曲目主动补取机制 + 单曲 ⋮「刷新元数据」（接 T15 菜单） | 真机：无图曲目刷新后出现封面 |

## W4 播放器收尾

| 卡 | 需求# | 状态 | 内容 | 验收 |
|---|---|---|---|---|
| T21 | #19 | TODO | 竖屏控件下移：接通 `BasePlayerActivity.configureInsets()`（死代码 `:136-147`）或 Compose safeDrawing | 真机竖屏控件不被状态栏/摄像头干扰 |
| T22 | #20 | TODO | 竖屏锁定无法解锁（排查右缘 140×160dp 触摸区 `PlayerOverlayContainer.kt:99-107` 与手势拦截链）；统一锁/解锁键位尺寸；旋转 FULL_SENSOR→SCREEN_ORIENTATION_USER（manifest 同步） | 真机：竖屏可解锁；系统关自动旋转时手机横置不转 |

## W5 收口与发布

- LATER 池裁定 → 全量回归（T9 R1–R12 + 新增用例）→ 版本 bump 2.0.1 + RELEASE_NOTES + 签名双 APK + `verifyMpvJniReleaseContract` → push/tag/Release（用户授权）。

## 遗留 LATER 池（2.0.0 裁定，W5 再裁）

A4 徽标 / A5 FAILED 同步 / A6 计数 / 对账口径 /「全部继续」/ Pad 库页离线降级。

## 过程日志（只留最近 ~5 条）

- 2026-11-24：台账初建；分支 `codex/v2.0.1` @ `17848eff`；计划经用户两轮审批通过（含工作流七条、新需求按归属插波、主题还原 2.0.0 定稿、master 跟随发布）。
- 2026-11-24：W0-D0 完成——master 独有资产移植（`f541b249`）、台账（`699bf34b`）、AGENTS.md 2.0.1 版（`87d6470c`）；W0-D3 待用户确认后执行 master 对齐。
- 2026-11-24：W0-D1 完成（子代理）——《设计偏差清单》22 条落 `docs/V201_DESIGN_AUDIT.md`，「满屏黄橙」根因三条结构性（Neutral→MediaFilm、M3 primary=域媒体色、K1 极光渐变未落地），修复方向=收回琥珀适用范围而非换色；9 条不确定项待 W0-D2 裁定。
- 2026-11-24：W0-D3 完成——用户确认后 master `reset --hard v2.0.0`（`312209a6`），独有资产零丢失（已移植 `f541b249`）；远端推送待授权。
- 2026-11-24：W0-D2 完成（负责人定稿）——9 条不确定项全部裁定落 `docs/V201_DESIGN_FINAL.md`（总原则：域色=身份、极光=行动），含 T09 token 三批清单、T10 图标规范、W3 音乐交互稿；R1 文档已加指向状态块；设置页布局稿登记为 T13 前置；W0 全波次收官。
