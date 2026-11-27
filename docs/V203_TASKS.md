# 2.0.3 任务卡台账

<!-- V203_STATUS_BEGIN -->
> **当前状态（2026-11-28）**：**2.0.3 已发布（用户授权）**——发布点 `0735849c`（分支 `codex/v2.0.3`）；发布纪律执行（本地 master 先对齐发布点 → push master `d7ff30a7..0735849c` + 分支 + tag `v2.0.3`）；GitHub Release「极光幕 / Aurorama v2.0.3」双 APK；**发布后自检 ✅**（重新下载两资产：SHA-256 复核一致 arm64 `ebf3d987…09aa` / universal `09bd602c…650c`，字节数与 API 一致；GitHub 链路慢两次断流，gh 重试成功）；**Pad 砖态终验 ✅**（2.0.2(9) 死锁态覆盖安装 2.0.3(10) → 起播成功 mpv 出帧 PLAYING 持续）；K60 升级 E2E ✅（数据保留、首页渲染、crash 空）；11 项用户实测 bug 全修 + 找茬复审 F1–F6 返修全合入。
> **修复卡**（基线 = 2.0.2 发布点 `d7ff30a7` 的后续 `12f7e68d`）：
> - A `7e322512` 播放内核（bug 11/8）：切内核失败不再销毁重建（原地恢复+熔断器+`BACKEND_SWITCH_FAILED` 错误卡）；持久化内核建不出来时自愈回落 ExoPlayer 并复位偏好（Pad 整机死锁根因）；单集解析失败回落单片（"该条目不是可播放的剧集"触发链切断）；首页返回改静默刷新（不再整页 loading）。
> - B `b91cf96c` 播放器界面（bug 5/9/10）：播放顺序（循环模式）移底栏；字幕轨道行四段式重排；面板打开控制层收起、垫层唯一化（scrim 28% token）。
> - C `00249726` 影视域（bug 2/3/4）：详情页按钮条纯图标 24dp+1dp 细环；删除/编辑元数据入口归位海报 ⋮+详情页（电影/剧集/季/合集/收藏/库）；首页视频卡不显示评分；新增 `ItemOverflowActions(Host)`+`ItemWriteViewModel`。
> - D `5a574ae8` ⋮+音乐（bug 6/7）：全局 ⋮ 统一共享组件 `CinefinMoreVertIcon`（24dp 矢量，原 17sp 文本字形）；音乐页双 ⋮ 合并为顶栏单入口。
> - E `ea5e8876` 更新（bug 1）：根因=损坏 transfer 状态记录致 download() 永久 STATE_ERROR 且点击被静默吞；自愈丢弃+点击即反馈（STARTING_DOWNLOAD/失败码可见）。
> - 集成 `19d60125`：bump 2.0.3/10 + film 域 ⋮ 归一共享组件。
> **找茬复审**（QA 子代理全量 diff 复审 + 横向排查，报告无 P0、5 P1、7 P2、8 P3）→ **返修卡**：
> - F1 `b1641c66` 首页签名只比 id 的回归（进度/已看/改名被丢弃）→ 按条目全字段合并刷新（引用稳定+数据更新双满足）；内核失败专用文案；自愈提示条（BackendSwitchOverlay 通道）。
> - F2 `0bc0ff7f` 更新排队态可取消+「等待网络」明示+下载中禁检查+重试不闪旧失败码（代际过滤）。
> - F3 `434e522b` 详情动作条 BoxWithConstraints 键宽自适应（7 键不折行、⋮ 不掉行）；海报 ⋮ 垫层归 scrim 28%+glassBorder 并入 token 守卫；宽屏动作排玻璃底盘+极光 CTA；编辑对话框快照 id 匹配治时序竞态；⋮ 32dp 视觉+44dp 触控；VM 懒加载。
> - F4 `0716c8c2` 暂停降亮不受面板开关牵连（只收控件垫层）；底栏键集分档各按本档行宽判定。
> - F5 `f665ff8b` 音乐菜单字符串三语种资源化（music 模块新建 values×3）。
> - F6 `29933d68` 真机取证揪出：VideoScreen（视频 tab 聚合/临时库）、SearchBar、PersonScreen 海报网格漏接 ⋮ host，全补；新增 DEBUG 缺 host 告警防线（`WarnMissingOverflowHost`）。
> **K60 真机取证（2026-11-28，admin@jellyfin-test）**：bug2 首页无评分✅；bug3 详情页⋮+海报⋮（菜单=编辑元数据/删除，与官方同序）✅；bug4 纯图标按钮带无障碍名✅；bug5 循环模式在底栏（与字幕/音轨/倍速同排）✅；bug6 ⋮ 24dp 统一✅；bug7 音乐页单 ⋮✅；bug8 播放返回首页滚动位置保持+无整页重载✅；bug9 字幕面板分区正常（本集无字幕轨，行布局结构性通过）✅；bug10 面板打开控制层收起✅；bug1 检查更新有反馈（"已是最新版本"口径正确）✅——下载路径无可用更新无法真机触发，由 37+83 条单测覆盖。
> **字幕主/次技术解答（bug 9 提问）**：主字幕走内核渲染管线（mpv sid/ExoPlayer 轨道，支持图形轨 PGS）；次字幕是 App 层下载源文件 Compose 自绘文本。图形轨只能主字幕；内核原生轨道表没有的纯外挂文本轨只能次字幕——两条完全不同的渲染路径，是能力差异不是 bug。
> **观察项**：字幕轨道行新布局未在有字幕的片上实轨目视；P3-2（循环档位徽标）/P3-5（音乐子菜单返回语义）未做=留档；core `DownloaderImplRecoveryTest` 2 条并行偶发（单跑绿，与本波无关）。
> **红线**：生产服只读；push/tag/Release 需用户授权；发布前先并 master。
<!-- V203_STATUS_END -->

## 卡状态约定

`TODO / DOING / REVIEW / DONE / BLOCKED / DEFERRED`；实现一律派子代理，设计以 `docs/V202_DESIGN.md` + `docs/design/COLOR_ICON_INVENTORY.md` 为准。

## 执行卡

| 卡 | 状态 | 内容 | 验收 |
|---|---|---|---|
| A | DONE | 播放内核：切内核死锁自愈 + 首页返回静默刷新 | `7e322512`，单测 12 条新增，K60 返回不回顶 ✅ |
| B | DONE | 播放器界面：播放顺序移底栏 / 字幕行重排 / 垫层唯一化 | `b91cf96c`，PlayerControlLayoutTest 29 条 ✅ |
| C | DONE | 影视域：详情纯图标按钮条 / 删除编辑入口归位 / 首页去评分 | `00249726`，641 单测 ✅，K60 三处取证 ✅ |
| D | DONE | 全局 ⋮ 24dp 共享组件 + 音乐双菜单合一 | `5a574ae8`，music 500 单测 ✅ |
| E | DONE | 更新下载无反应根因修复 + 反馈可见 | `ea5e8876`，37+83 单测 ✅ |
| QA | DONE | 找茬复审（无 P0 / 5 P1 / 7 P2 / 8 P3） | 报告见会话存档，P1 全修 |
| F1–F6 | DONE | P1 返修×5 + VideoScreen 漏网补修 | `b1641c66/0bc0ff7f/434e522b/0716c8c2/f665ff8b/29933d68`，门禁全绿 |
| R1 | DONE | Pad 装机+bug11 终验 ✅（砖态恢复 PLAYING）；发布门绿；master 对齐/tag `v2.0.3`/GitHub Release 双 APK（2026-11-28 授权） | 发布记录 `RELEASE_PLAN.md` §7 |
