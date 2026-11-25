# 2.0.1 任务卡台账

<!-- V201_STATUS_BEGIN -->
> **当前状态（2026-11-26）**：**W4 播放器收尾全波次收官**——T21 `8d5f8213`（竖屏顶栏 safeDrawing 下移出挖孔区）、T22 `4b7392c4`（竖屏锁定可解锁：根因=命中区按容器 height/2 取中点，竖屏下半截是选集区，改实测矩形回传；锁/解锁键统一 spec 档位；旋转 FULL_SENSOR→USER+manifest 同步）、T23 `29f3efdc`（串流码率迁设置→播放器子页+删播放器内码率面板，底栏 6 键→5 键）。分支 `codex/v2.0.1` HEAD=`29f3efdc`；master=`312209a6`（远端推送待授权）；设计定稿 `docs/V201_DESIGN_FINAL.md`。
> **下一卡**：W5 收口与发布——LATER 池裁定 → 全量回归（T9 R1–R12 + 新增用例 + W4 留档验证项）→ 版本 bump 2.0.1 + RELEASE_NOTES + 签名双 APK + `verifyMpvJniReleaseContract` → push/tag/Release（用户授权）。
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
| T01 | #1 | DONE | 更新链路（`a4b6ed4f`）：手动检查/下载 KEEP→REPLACE（自动保留 KEEP，策略随命令下传可断言）；digest 缺失改警告跳过哈希、**不删包**（签名/包名/版本等校验照常，malformed 仍硬失败）；manifest 加 `<queries>`（APK VIEW intent）；`GitHubReleaseDto.body`→`StoredReleaseState.releaseNotes`→UpdateScreen 纯文本渲染（无 markdown 依赖）。ENQUEUED→QUEUED 既有映射核查无需改 | K60 取证：更新页可开、连点两次检查产生两个 Work id+SUCCESS（REPLACE 直接证据）、「已是最新版本」文案 ✅；真实升级 E2E 与 AVAILABLE 渲染真机验证移至 W5（需 rc） |
| T02 | #10 | DONE | 管理员入口（`56202e2d`）：取证排除服务端 policy 与偏好门控（K60 admin 两包入口本就在）；实修缺口=「查询失败/身份未发布被折算 false 并覆盖同账号已确认值」，`DrawerViewModel.load()` 改 `drawerAdministratorFlag`=联网∨同账号缓存（安全语义未松：查询成功先写缓存） | 单测 7/7；K60 修复包抽屉控制台/资料管理节点存在、两页均可进（uiautomator）✅ |
| T02b | #10 | DONE | T02 遗留缺口（`9bc5693c`）：`refreshAccount()` 改判据 `drawerAccountNeedsServerConfirmation`——缓存**无当前账号记录**（区别"有记录且 false"）视同换账号补一次完整 `load()`，全新登录管理员首次查询失败后下一次导航即恢复；有记录保持零请求。留档：确认成功前每次导航补查一次 policy（未节流，W5 再裁）；真机场景需 pm clear/生产建号，红线内不可构造，以单测验收 | 单测 12/12（新增 5 条：无记录重查/失败→补查恢复/有记录零请求/false 不误清/换账号）；ktfmt+assemble 绿 ✅ |
| T03 | #3 | DONE | `core/.../drawable/ic_logo.xml` 已换 B「极幕之眼」（`52d57030`，与启动图标同源 ×0.2 构图，10 处引用全核对）；已知留档：渐变方向随启动图标渲染（与设计稿 objectBoundingBox 旋转差异，连启动图标一起改才算对齐，列 LATER）；Light 主题关于页 120dp 对比度偏低（列 LATER） | K60 装机+uiautomator 取证顶栏/抽屉 logo 节点正常，截图 2 张已删 ✅ |
| T04 | #4 | DONE | 图片缓存默认 50MB→0 无限制（`c7e6f2ff` + 跟进 `84e7b800` 解除 Coil 250MB 默认硬顶：0=可用空间×2%、10MB 保底、无固定上限；存量值不迁移；反编译确认显式 maxSizeBytes 不受钳制） | 3 条单测绿；phone assemble + tv compile + ktfmt 全绿 ✅ |
| T05 | #16 | DONE | 静默刷新（`71a15850`）：`loadShow()` 以 `state.value.show?.id == showId` 判同剧（`lateinit showId` 开头即覆写不可用作判据）——同剧刷新保留 nextUp/seasons/actors/director/writers，明细请求失败时 `settled()` 沿用旧值；换剧才一次性清空。`modes:film` 补 kotlinx-coroutines-test 依赖。留档：已看勾选为无语义纯图标，文本取证不可行（刷新链路本身已取证），如需闭环另开小卡给勾选加 contentDescription | 单测 228/228（新增 2 条含反向验证）；K60 播放返回后季/集节点 4/4 连续存在、滚动偏移仅 31px ✅ |
| T06 | #17 | DONE | 多选下移（`0ed6cf41`）：`SeasonScreen.kt` hero `item{}` 补稳定 key（`hero_${season.id}`）——无 key 时隐式 index key 被多选顶栏插入顶替导致错位 | K60 两场景（hero 在屏/出屏）长按进多选 uiautomator 纵向坐标逐一相同 ✅ |
| T07 | #21 | DONE | 跨季误下载（`c014f948`）：只读探针确认根因=**服务器按 ParentIndexNumber 归季**——S01 查询返回 13 条含 1 条 SeasonId=S02 的「Lv.2 09」（同集也在 S02 返回中），客户端只缺季回落不做一致性判断。修复=`episodeMatchesRequestedSeason` 纯函数（双方有季 ID 且不一致才丢弃，W73 缺季回落保留）+ `materializeOnlineEpisodes` 过滤并记 Timber.d。排除缓存/Room 路径 | 单测 528/528（新增 5 条）；K60：S01 季页「季 · 12」、下载框「将加入 12 集」、logcat 一条跳过跨季集记录；S02 仍 11 集不过滤 ✅ |
| T08 | P1 | DONE | 搜索音乐结果导航（`93b2697b`）：根因=`showNavigation` 白名单漏登 `MusicItemRoute`+路由把任意音乐 id 硬塞 `initialSongId`；修复=白名单+底栏高亮补登、`MusicItemRoute` 带 kind/title、`openItemFromRoute()` 按类型进专辑/艺术家/歌单详情层（歌曲起播后开所属专辑详情）、搜索打开的详情返回直回搜索页。留档：服务器音乐搜索只请求 AUDIO，专辑/艺术家/歌单链路暂只能单测覆盖；详情层左上角返回键仍是「只关详情」（与系统返回直回搜索页不同步，列 T15/W3 再议） | 单测 431/226/611 三模块 0 失败（新增 7 条）；K60 歌曲结果→所属专辑详情、底栏 4 节点在、返回保留搜索结果 ✅ |

## W2 设计基础（依赖 W0-D2 定稿）

| 卡 | 需求# | 状态 | 内容 | 验收 |
|---|---|---|---|---|
| T09 | #9 | DONE | 主题还原三批全收官：**批① 角色收口 DONE**（`0d3aa49a`+`b17eaa24`）。**批② 组件换肤 DONE**（`c9cb41c9`：CinefinBrushes 100° 公共渐变+单测、CinefinButton Aurora tone（渐变实底+onAurora+6dp/28% 投影、非 Filled 回退 Media）、ProgressBar/HomeHero/CinefinWideCard/PlayerControlOverlay 已播段改极光渐变（旧 playerProgressBrush 与 <10% 断言退役）、chip/segmented/card 选中=navSelectedFill+aurora1 描边+navSelectedContent、Switch/Slider 读 M3 primary（thumb=onPrimary，dynamic 下保对比度）、SectionHeader 动作回灰；规格外一处：ItemButtonsBar 竖屏 hero 自绘 CTA 按一级 CTA 口径换渐变）。**批③ 细节 DONE**（`cd9d6ad0`：`auroraInk` token 转正（浅 #0A6B5A/深 aurora1/lumen #5CE1D2，navSelectedContent 改读 token、LightNavSelectedContent 删除、评分徽标文字换 auroraInk）、PageMetrics Compact 20→16/gutter 16→12、玻璃注释正式决定口径、字幕预览面板写死色 token 化（渐变→surfaceContainer 高低档、提示 chip→inverse 反色对，理由=scrim 浅色档仅 1.9:1）、HomeHero:124/134 media.* 归零）。留档：进度条批③未做新像素取证（以共用 helper+AuroraGradientTest+批②留档作证） | 批②③：core 529/phone 615 单测绿、ktfmt+assemble 绿、K60 浅色徽标 #0A6B5A+深色 #3DE8C2 像素对照、批③独立 worktree 冷构建自证 ✅ |
| T10 | #18 | DONE | 图标规范（`78df3ed7`）：去方框磁贴底实改 8 处（定稿 7 处+同模式补漏 2 处：LibraryEntryCard 无封面占位、LocalLibraryScreens 头部封面）；图标着色默认 onSurfaceVariant/强调=navSelectedContent（含 auroraInk）；新增 `scrimBehindControl`（黑 32%）与 `mediaContentShadow`（0 1px 4px 60%）token + `CinefinMediaSurface.kt` 三 helper（HomeHero 圆点示范接入）；vector 清理 4 个 UI 图标 fillColor→`?attr/colorControlNormal`，通知/启动图标 7 个保持纯白（RemoteViews 剪影资产，刻意保留），18 处 `?attr/colorOnBackground` 复核=全在 TV 专用 ic_banner 无需改。留档：图片无封面时占位无灰底（如需底色改 surfaceContainerLow 且仅加载中显示）；`FavoriteBadge.kt:28 tint=Color.White` 范围外写死色 | core 530/phone 615 单测绿、ktfmt+assemble 绿；K60 媒体库/下载页截图目视：磁贴底消失、亮暗封面图标可辨 ✅ |
| T11 | #12 | DONE | 抽屉误触（`e3bafa2e`）：整屏 anchoredDraggable → 左缘 28dp 热区（`DrawerEdgeSwipeWidth`）且整体内移到系统返回手势保留区（≥24dp，`DrawerSystemEdgeReserve`）之后——MIUI 实测 x<62px 应用收不到 down 事件，纯 x≈8 物理不可行（裁定：接受内移，不改系统设置）；Initial pass 方向仲裁（横向过 slop 且 \|dx\|>\|dy\| 接管，纵向让出），热区内纵向滑动照常滚动页面；打开后仍全屏可拖关闭；收尾动画自算目标+animateTo（尊重 Reduce Motion，少 decay 续滚） | 单测 528/528（新增 7 条手势门控）；K60 六组滑动取证：中部纵向/中部横向/右侧横向/热区内纵向均不开抽屉，左缘热区右滑开出（日志 claim+抽屉节点），面板左滑/遮罩点击可关 ✅ |
| T12 | #13 | DONE | 滑杆防误触（`ddf91d75`）：`cinefinSliderShouldClaimDrag` 纯函数（\|dx\|>touchSlop 且 \|dx\|>\|dy\| 才接管；45° 斜向放行父级滚动）；行为变化=按下不再即时落位（水平接管时落位/点按抬手落位），已写 KDoc；三个调用点排查全是水平滑杆无需方向分支 | 单测 528/528（新增 8 断言）；K60 字幕外观面板从滑杆上垂直滑→面板滚 197px 且字号不变（滚动正证）、水平拖改值、点按定位可用 ✅ |
| T13 | #2 | DONE | 设置页重布局（`22988cf2`）：设置页 5 组骨架/条目/渲染零改动（核对组头样式与间距已一致）；播放设置面板 12→6 项（留：时间格式/提示条时长 chips/自动下一集/停在结束帧/循环/解码与能力+手势 tab 全留；移除：后台音频/片段跳过按钮/自动跳过/章节标记），同步收敛解码面板（去内核选择+hwdec 行）、字幕面板（去记住轨道/首选字幕语言）、音轨面板（去首选音轨语言）；面板顶部「全部播放设置 →」经 MainActivity extra 直达播放组子页；三 locale 字符串补齐。**勘误落 §7**：提示条时长=片段时长同键按保留执行；串流码率设置页无宿主暂留面板（后续卡：设置→播放器补码率行再删面板）。留档：PlayerSettingsSnapshot 冗余字段瘦身另开卡 | 单测 615/615+settings 绿、ktfmt（快照隔离格式化未碰他卡文件）、assemble 绿；K60：5 组头齐、面板移除项节点不存在、入口直达播放组子页、手势开关/倍速档双端同键联动 ✅ |

## W3 音乐模块重塑（依赖 W2 token；可拆 a=T14–T17 / b=T18–T20）

| 卡 | 需求# | 状态 | 内容 | 验收 |
|---|---|---|---|---|
| T14 | #7 | DONE | 工具行重构（`8aa7d705`+`2cd7f0cd`）：Tab 序列=建议/歌曲/专辑/艺术家/歌单（建议=最近添加/最常播放/收藏三段各 ≤10、空段隐藏，复用 MusicRecentStore）；工具行左 ⋮ 收纳菜单（来源筛选+徽标开关+排序 6 键升降序持久化+收藏筛选+每页数量 50/100/200 默认 100 持久化），右 Aurora「播放全部」+随机键；歌曲 tab 客户端分页+底部页码栏；根因修复 `querySongs` 补 `DATE_CREATED`；排序/分页/建议段纯函数+17 条新单测。留档：最常播放=最近播放序近似（无计数表，LATER）；排序仅歌曲 tab（其余 tab LATER）；默认 tab 仍「专辑」（是否改「建议」W5 裁）；筛选（收藏）不持久化 | 单测 7/5/5 新+全模块绿、ktfmt+assemble 绿；K60：5 tab/⋮菜单全项/排序改序/每页 50→1/7 页/翻页/force-stop 持久化/建议段可起播 ✅（`F:/Develop/code_data/qa/w3-t14/`） |
| T15 | #8 | DONE | 歌曲行（`00eb1cdf`+`748d76c0`）：去序号 leading 只留 40dp 封面；右侧 compact=收藏+⋮、≥600dp=加歌单/收藏/⋮（Pad 取证）；⋮ 与长按共用同一份 bottom sheet 菜单=播放/下一首播放/加入队列/加入歌单/收藏/下载/查看专辑/查看艺术家/刷新元数据（stub snackbar，T20 接）+既有「音频信息」保留；歌单上下文上移/下移保留；当前曲目标题 aurora1（core `CinefinListRow` 加可选 `titleColor`）；VM 加 `enqueueSongForAction`/`openAlbumOfSong`（playSong 语义未动，归 T17）。留档：收藏 toggle 真机未验（生产服只读，测试服补，W5）；下载项文案沿用既有三态；整行高亮未改（仅标题着色，如需再裁） | 单测 11 新+全模块绿、ktfmt+assemble 绿；K60：无序号/菜单 10 项/播放/查看专辑/下载前置框/队列插入位置/歌单上移下移；Pad：三图标 ✅（`qa/w3-t15/`） |
| T16 | #6 | DONE | 去多选按钮（`5efbf784`）：删工具行+详情顶栏两处「多选」入口与死代码 `enterBatchSelection()`；所有歌曲行（歌曲/建议/详情层）统一长按进多选并选中该曲，⋮ 成上下文菜单唯一入口；批量态顶栏/批量条/W59 专辑卡长按保留。全局排查：VideoScreen/LibraryScreen/SeasonScreen/FavoritesScreen/DownloadRows 均已是长按进多选无入口按钮，模块外零残留。留档：建议 tab「全选」作用域=全部歌曲而非建议 20 条（W5 裁） | 单测 454 全绿、ktfmt+assemble 绿；K60：工具行/详情无多选、长按进多选已选 1→2 项、批量下载前置框、× 退出、⋮ 菜单回归、建议 tab 长按 ✅（`qa/w3-t16/`） |
| T17 | #14 | DONE | 队列语义（`4e391eb5`+`8c283888`）：`playSong` 改纯函数 `musicPlaySongPlan` 分派——点单曲=追加队尾并起播，已在队列只跳位不重复追加；详情层（专辑/艺术家/歌单/收藏/最近播放）点歌=追加该曲并起播+后台把「该曲→列表尾」追加队尾（不重建）；搜索/通知路由单曲同追加语义；整列加载只留「全部播放/随机/多选播放/下载页多选播放」；队列快照=逐项剔除确认不可用项+保存的当前曲目被剔除时就近迁移（原 clamp 可能续播无关曲目，隐性修复）；`docs/RELEASE_NOTES_v2.0.1.md` 首条行为变化。留档：详情层尾段不与已有条目去重（保连续播放）；快照恢复偏好 `pref_music_resume_queue` 默认 false（验证时已复原）；14 首服务端解析失败跳过为既有容错 | 单测 6+5 新+全模块绿、ktfmt+assemble 绿；K60：点 A→B 队尾追加起播、再点 A 计数不变跳位、全部播放后台补齐至 335、快照恢复 65 条续上、详情层尾段 65→69 ✅（`qa/w3-t17/`） |
| T18 | #11 | DONE | 播放页布局（`6e71a8b8`）：非 compact 艺术家·专辑 Row 修重叠（weight+Ellipsis）；Regular/Wide 左栏=顶栏固定+内容区 verticalScroll+底部辅助行固定（短屏降级）；单入口=底部 音效/歌词/队列（顶部 ⋮ 收敛播放模式/收藏/桌面歌词/睡眠定时，Wide 右栏队列 Tab 移除、封面点击/歌词预览点击/左滑手势入口全删，Compact 补 ⋮）；全屏播放页隐藏底栏（NavigationRoot host 槽位重构，防 NavHost 重建）；safeDrawing 安全区；封面取色整体撤销——`MusicCoverPalette`+`androidx.palette` 依赖删除，波形/传输键/歌词高亮/迷你条进度线全回极光。留档：主播放键（70dp）保持 onSurface 中性（原不随封面色，是否改极光 W5 裁）；K60 横屏 Compact 档未复测；环境光晕未实现（§1 允许未要求） | 单测 4 新+全模块绿、ktfmt+assemble 绿；K60：三键单入口/底栏隐藏恢复/安全区坐标/长文本不重叠/极光像素取样；Pad：侧轨隐藏/右栏仅歌词/三键安全区 ✅（`qa/w3-t18/`） |
| T19 | #5 | DONE | 服务器歌单写操作（`d29bd6b0`）：SDK 复用封装——改名 `PlaylistsApi.updatePlaylist`（只动 Name）、删除 `LibraryApi.deleteItem`（复用视频域写边界）、换位 `PlaylistsApi.moveItem`；服务器歌单 ⋮ 开放重命名/删除（删除确认框明示「同时删除服务器上的歌单且无法撤销」）、详情内上移/下移解封；**顺带修真 bug**：移曲/换位原误用媒体 itemId（Jellyfin 要求 PlaylistItemId，实测 204 但顺序不变），改映射 `playlistEntryId` 后生效；`PlaylistServerExchangePort` 陈旧注释更新（整份导入仍 writeEnabled=false）；本地歌单零变化 | 契约 6+菜单 2 新单测、data+music 单测绿、ktfmt+assemble 绿；K60 测试服 E2E：建→改名→加曲×2→换位（顺序实变）→删除全通，本地歌单回归，测试服无残留 ✅（`qa/w3-t19/`） |
| T20 | #15 | DONE | 元数据（`f7c1e8b2`）：封装 `itemRefreshApi`（POST Refresh，Default/Default+双 replace=false，契约测试钉死防改覆盖式）+ `userLibraryApi` 单条 GET；歌曲 ⋮「刷新元数据」接真调用（刷新→800ms→回读→`mergedWithFresh` 合并→snackbar），T15 stub 退役；无封面补取=保守只读口径（列表加载后对无封面曲目发 GET 回读，延迟 2s/每轮≤20/并发 4/会话内只试一次，**永不自动 POST**，生产服红线安全）。留档：自动补取只 GET（服务端列表缺图单条有图场景自愈）；`MusicRecentStore` 无更新 API（最近播放旧封面待再播放刷新）；800ms 固定延迟未做轮询 | 契约 4+合并 4+补取 6+详情更新 4 新单测全绿、ktfmt+assemble 绿；K60 测试服：手动刷新 snackbar「已刷新元数据」+logcat POST/GET 回读已更新；补取节流计数（候选 20 已试 20→40 不重复）✅（`qa/w3-t20/`） |

## W4 播放器收尾

| 卡 | 需求# | 状态 | 内容 | 验收 |
|---|---|---|---|---|
| T21 | #19 | DONE | 竖屏控件下移（`8d5f8213`）：Compose 方案——`PlayerTopBar` 加 `protectTopInset`（`maxWidth<maxHeight` 才取 `safeDrawing.only(Top)`），竖屏顶栏整体下移 39.6dp 出挖孔区，scrim 仍铺到顶缘、命中带随实测高度自动跟随；横屏沉浸式 inset=0 零副作用；`BasePlayerActivity.configureInsets` 死代码保留未删（列清理） | phone 单测+ktfmt+assemble 绿；K60 竖屏 7 个顶栏控件 bounds 全部落挖孔安全区之下（y 11..137→115..241，位移=safeInsetTop 103.5px），中央/底栏/锁定键坐标零变化；横屏顶栏 8dp 无叠加 inset ✅ |
| T22 | #20 | DONE | 竖屏锁定无法解锁+尺寸统一+旋转口径（`4b7392c4`）：**根因=锁定命中区按容器 `height/2` 取中点，竖屏容器下半截是常驻选集区，解锁键（画面区居中）与命中区错位 ~700px 点不到**；改 `onGloballyPositioned` 实测矩形回传（`lockKeyRectPx`/`unlockKeyRectPx` + `HitRect` 纯类+3 单测，兜底按画面区中点），删 140×160dp 与 76×36dp 两组硬编码；锁/解锁键统一 `playerControlSpec` 档位（窄屏 38dp/宽屏 44dp）；旋转 `FULL_SENSOR→SCREEN_ORIENTATION_USER`（`PlayerActivity.orientationForFormFactor` + manifest `fullSensor→user` 同步，车机/TV SENSOR_LANDSCAPE 不动） | phone 单测（新增 3 例）+ktfmt+assemble 绿；K60 竖屏点解锁键 `locked=false` 控制层恢复（修复前实点复现无反应）、锁/解锁键 bounds 完全同值、横屏锁定/解锁回归、dumpsys 取证 USER→全屏 SENSOR_LANDSCAPE→退出回 USER ✅；留档：「关自动旋转横置不转」因红线未改系统设置未真机验证（平台契约兜底）、Pad 锁定几何未实测（W5 回归带上） |
| T23 | T13 遗留 | DONE | 串流码率宿主补偿（`29f3efdc`）：设置→播放器子页新增「串流码率」行（`PreferenceStreamingBitrate` 模型，落「播放内核」后，通用 OptionsDialog，档位= `PlayerStreamingQuality.qualityPresets()` 单源），写回仍只有 `setPlayerStreamingBitrate`；删播放器内 `PlayerPanel.Bitrate` 面板+底栏/小窗码率键+`onSelectBitrate` 通路（底栏 6 键→5 键，`PlayerControlLayoutTest` 25 断言同步）；三 locale 字符串补齐。踩坑：settings state 重建映射漏 `is PreferenceStreamingBitrate -> copy(...)` 会致选中后行值不刷新（真机发现已修） | settings+phone 单测绿、ktfmt+assemble 绿；K60：行存在/档位逐条一致/改档持久化（shared_prefs 单键落地）/底栏无「码率」节点/「全部播放设置 →」直达且回显联动 ✅；留档：jellyfin-test 拒连（ping 通 443 拒连），码率→实际转码流验证 W5 补；`player_controls_bitrate_*` 字符串与 `ic_player_bitrate` 无引用待清理 |

## W5 收口与发布

| 卡 | 状态 | 内容 | 验收 |
|---|---|---|---|
| T24 | DONE | 2.0.1 清理卡（本卡单 commit）：① 删 `BasePlayerActivity.configureInsets` 死代码（连同仅它使用的 `View` / `updatePadding` 导入；全仓零调用、零 override）；② 删 `player_controls_bitrate_*` 6 条（`values` / `values-zh-rCN`，仓内无 `values-en`）+ `ic_player_bitrate.xml`，全仓零引用；③ `bottomRowWidthDp` 核对结论=T23 已按 5 键/7 间距改好（`29f3efdc` 内 `6f→5f`、`8f→7f`），估算式 ≥ 布局实际（4 个键内距 + 2×Space2），无需再改；④ 音乐 `UiState.tab` 默认 `ALBUMS→SUGGESTIONS`（tab 无持久化，不动已存选择），同步首屏曲库失败降级口径（建议/专辑→歌单）；⑤ `StoredReleaseState` 降级兜底核对=v2.0.0 `readState` try/catch→`Invalid`，UI `as? Present` 按无更新处理不崩；降级后更新检查返回 STORAGE_ERROR（功能降级，非崩溃），schemaVersion 不升；补 2 条契约单测（旧 json 缺 `releaseNotes`→Present/notes=null；未知字段→Invalid 不抛）。顺带仅修 T23 遗留 1 行 ktfmt（`SettingsViewModel.kt:1476` 纯换行） | 六模块单测 core 530 / phone 618 / data 530 / film 228 / music 496 / settings 9 全绿、ktfmtCheck + assembleLibreDebug 绿；K60：音乐页默认「共 20 条推荐」+「最近添加」段；竖屏底栏 5 键+1×+全屏键 bounds x 19..1060（1080 内）；横屏带字 5 键（音轨/字幕/倍速/解码/详细信息）+退出全屏 x≤2351（2400 内）无溢出，无「码率」节点 |

- LATER 池裁定（2026-11-26 已裁，见下节）→ 全量回归（T9 R1–R12 + 新增用例 + 留档验证项）→ 版本 bump 2.0.1 + RELEASE_NOTES + 签名双 APK + `verifyMpvJniReleaseContract` → push/tag/Release（用户授权）。
- T01 移交项：真机 2.0.0→2.0.1-rc 真实升级 E2E（进度可见/安装成功/更新说明渲染取证——AVAILABLE 态只有 rc 才能构造）。
- 「关自动旋转横置不转」：回归阶段请用户手动关自动旋转验一次（勿用 adb 改系统设置）。

## 遗留 LATER 池（2026-11-26 W5 已逐条裁定）

**2.0.1 修（→ T24）**：`configureInsets` 死代码 / `player_controls_bitrate_*`+`ic_player_bitrate` 清理 / `bottomRowWidthDp` 复算 / 默认 tab 改「建议」 / `StoredReleaseState` 降级兜底核对（兜底在则 schemaVersion 不升）。

**不修（终裁，理由）**：
- `FavoriteBadge.kt:28` 写死白色 tint——`BaseBadge` 即 §8.9 中性徽标规范（黑 62% 底+白 12% 描边，明示不用媒体色），白图标是该规范的一部分，属媒体面例外，非偏差。
- 图片占位无灰底——定稿未要求，占位图标可辨，加底色需新视觉验证收益低。
- 建议 tab「全选」作用域=全部歌曲——批量条语义=当前库全量，与下载前置框一致；建议段 20 条可长按多选代替。
- 主播放键（70dp）保持 onSurface——定稿 §4.5 未要求；播放页极光已覆盖进度/传输键/歌词高亮/当前曲目，主键保持中性作视觉锚点，避免满屏极光。
- `MusicRecentStore` 无更新 API——最近播放旧封面/名称待再播放自愈，既有架构约束。
- 元数据回读 800ms 固定延迟——保守只读口径已定，轮询增复杂度；慢提供器场景下一次手动刷新可纠正。
- T02b policy 补查未节流——低频导航触发，安全语义优先。

**DEFERRED 2.0.2**：A4 旧记录徽标判定（identity 命名空间并案）/ A5 FAILED 态同步（P2）/ A6 下载计数跳变（P2 未复现）/ 对账口径 /「全部继续」/ Pad 库页离线降级 / `PlayerSettingsSnapshot` 冗余字段瘦身 / 建议 tab 真实播放频次（需计数表）/ 专辑·艺术家·歌单 tab 排序 / music 模块文案三 locale / 播放页环境光晕。

**W5 回归项（不留档，本次做掉）**：串流码率→实际转码流联动（jellyfin-test 2026-11-26 已恢复 302）/ 收藏 toggle 测试服验证 / 服务器歌单「移出曲目」E2E / Pad 锁定几何+横屏挖孔区控件 / K60 横屏 Compact 播放页复测 /「关自动旋转横置不转」用户手动验。

## 过程日志（只留最近 ~5 条）

- 2026-11-26：**W5-T24 清理卡 DONE**（本卡单 commit）——删 `configureInsets` 死代码、码率残留资源（6 串+1 图标）、`bottomRowWidthDp` 核对已 5 键/7 间距、音乐默认 tab 改「建议」（含首屏降级分支）、`StoredReleaseState` 降级兜底核对+2 契约单测；顺带修 T23 遗留 1 行 ktfmt。六模块单测全绿，K60 取证绿。
- 2026-11-26：**W4 全波次收官**——T21 `8d5f8213`（竖屏顶栏 safeDrawing 下移）、T22 `4b7392c4`（锁定解锁根因=容器高≠画面区中点，改实测矩形回传；旋转口径 USER）、T23 `29f3efdc`（码率迁设置页+删播放器内面板，底栏 6→5 键）。HEAD=`29f3efdc`，三卡 K60 取证绿；留档 jellyfin-test 拒连（码率联动 W5 补）等 6 项入 LATER 池。
- 2026-11-25：**W3 全波次收官**——T17 `4e391eb5`+`8c283888`（队列追加语义+快照就近迁移+RELEASE_NOTES 首条）、T18 `6e71a8b8`（播放页单入口/隐藏底栏/封面取色撤销回极光）、T20 `f7c1e8b2`（元数据手动刷新+只读保守补取）、T19 `d29bd6b0`（服务器歌单改名/删除/换位；修 PlaylistItemId 误用 bug）。W3 七卡全 DONE，全程 K60/Pad 取证绿、测试服无残留。
- 2026-11-25：**W3 a 组收官**——T14/T15/T16 串行 resume 派同一子代理全 DONE（`8aa7d705`+`2cd7f0cd` / `00eb1cdf`+`748d76c0` / `5efbf784`）。
- 2026-11-25：**W2 收官**——T13 `22988cf2` + T10 `78df3ed7`；T09 批② `c9cb41c9` + 批③ `cd9d6ad0` + T11 `e3bafa2e` + T12 `ddf91d75`。W2 五卡全绿。
- 2026-11-24：W0 全波次收官——D2 设计定稿 `docs/V201_DESIGN_FINAL.md`，D3 master 对齐 v2.0.0。
