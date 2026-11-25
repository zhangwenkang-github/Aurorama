# 2.0.1 任务卡台账

<!-- V201_STATUS_BEGIN -->
> **当前状态（2026-11-25）**：W0 已收官；**W1 修复快线已收官**——T01–T08 全 DONE（T01 `a4b6ed4f`、T02 `56202e2d`、T02b `9bc5693c`、T03 `52d57030`、T04 `c7e6f2ff`+`84e7b800`、T05 `71a15850`、T06 `0ed6cf41`、T07 `c014f948`、T08 `93b2697b`）。**W2 开工**：T09 批① 角色收口 DONE（`0d3aa49a`+`b17eaa24`，验收裁定落 `V201_DESIGN_FINAL.md` §2）。分支 `codex/v2.0.1`；master 已对齐 v2.0.0（`312209a6`）；设计定稿 `docs/V201_DESIGN_FINAL.md`（域色=身份、极光=行动）。
> **下一卡**：W2-T09 批② 组件换肤（Aurora tone/进度/选中/开关滑杆/SectionHeader，依赖批① token 已就位）+ T11/T12（手势误触，独立于 token）可并发；T10/T13 随后。批③ 开工前需 Kimi 先出浅色档对比度专项裁定。
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
| T09 | #9 | DOING | 主题还原：按 `docs/V201_DESIGN_FINAL.md` §2 三批执行。**批① 角色收口 DONE**（`0d3aa49a`+`b17eaa24`：Neutral→MediaAurora 新色板、M3 解耦域色+dynamic 映射、warning/success/info 语义 token、评分徽标极光淡色 pill、黄铜退出 phone）；验收裁定已落定稿文档（派生值转正、浅色档对比度列批③专项、inversePrimary 无消费点不处理、dynamic 下 Neutral 固定极光）。余批② 组件换肤、批③ 细节 | 批①：core 516/616 单测绿、grep 无 `Neutral->MediaFilm`/`C9A227` 残留、K60 首页+详情页无黄橙违和 ✅；批②③待派 |
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
- T01 移交项：真机 2.0.0→2.0.1-rc 真实升级 E2E（进度可见/安装成功/更新说明渲染取证——AVAILABLE 态只有 rc 才能构造）；注意 `StoredReleaseState` 新增 `releaseNotes` 键，旧版本 App 降级读取 release.json 会失败（仅降级场景，W5 裁定是否升 schemaVersion）。

## 遗留 LATER 池（2.0.0 裁定，W5 再裁）

A4 徽标 / A5 FAILED 同步 / A6 计数 / 对账口径 /「全部继续」/ Pad 库页离线降级。

## 过程日志（只留最近 ~5 条）

- 2026-11-25：W1 收官 + W2 开工——T02b/T05/T07 三卡并发 DONE（`9bc5693c`/`71a15850`/`c014f948`；T07 根因=服务器按 ParentIndexNumber 归季返回跨季 dto；T05 留档：已看勾选无语义不可文本取证；T02b 留档：补查未节流）。T09 批① 角色收口 DONE（`0d3aa49a`+`b17eaa24`），验收裁定落定稿 §2（浅色档对比度列批③专项）。
- 2026-11-25：W1-T06/T08 并发派卡均 DONE——T06 季页多选下移（`0ed6cf41`，hero 补稳定 key，K60 两场景坐标零偏移）；T08 搜索音乐结果导航（`93b2697b`，根因=导航白名单漏登 MusicItemRoute+id 硬塞 initialSongId，三类结果各进对应详情层；留档：服务器音乐搜索只产歌曲、详情层左上返回键语义列 W3 再议）。
- 2026-11-25：W1-T01/T02 并发派卡均 DONE——T01 更新链路（`a4b6ed4f`）；T02 管理员入口（`56202e2d`；遗留首次查询失败无缓存场景落 T02b，已收官）。
- 2026-11-24：W1 开工——T03 logo 换 B 版 DONE（`52d57030`）；T04 缓存默认无限制 DONE（`c7e6f2ff`+`84e7b800`）。
- 2026-11-24：W0 全波次收官——D2 设计定稿落 `docs/V201_DESIGN_FINAL.md`（域色=身份、极光=行动），D3 master 对齐 v2.0.0。
