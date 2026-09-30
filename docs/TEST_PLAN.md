# Cinefin · 测试与验收任务线（TEST_PLAN）

> **本线唯一权威文档**（R4 维护，已在 `docs/PROJECT_PLAN.md` §3 登记）。真机矩阵 / 性能基线 /
> 测试数据 / 每波回归清单 / 踩坑库都在本文件；新需求与新决策写进本文件对应章节，不新建零散 `.md`。

| 项 | 值 |
|----|----|
| 文档版本 | v1.0（2026-09-30） |
| 负责人 | W2-R4 会话 · 分支 `feature/r4-test-base`（兼本波「设备与环境」协调） |
| 需求基线 | `docs/REQUIREMENTS.md` v1.0（§9 设备矩阵 / §11 权限边界 / §12 验收标准） |
| 技能基线 | `docs/ROLE_SKILLS.md` §5.4（adb / UI Automator / Espresso / Compose 测试 / Macrobenchmark / Logcat / Perfetto） |
| 自动化脚本 | `tools/test/`（本文件 §2.6 说明用途与用法，原始输出不入库） |

---

## 1. 真机矩阵与设备纪律

### 1.1 设备矩阵

| 设备 | adb 序列 | 型号 / 系统 | 形态 | 角色 | 责任 |
|------|---------|------------|------|------|------|
| Xiaomi Pad 5 | `43af8627` | `21051182C`（nabu）· Android 13 / SDK 33 · 1600×2560 @360dpi · RAM 5.4 GiB | 平板（横屏为主，双栏） | **主验收设备**：全部功能的最终判决口径 | R4 |
| Redmi K60 | 待接入 | Android 15 · 手机 | 竖屏 + 横屏 | 手机形态回归、小屏布局与触控 | R4 |

- 所有性能数值**必须标注设备**，Pad 5（nabu）是基线设备；K60 接入后单独建一行基线，不与 Pad 5 混比。
- 模拟器**不作为验收口径**（`PROJECT_PLAN.md` §5.5）。

### 1.2 设备占用纪律（device-lock，硬约束）

1. `adb` 真机**同一时刻只允许一个会话**占用；占用前在
   `E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\device-lock.md` 登记
   （会话名 / 设备 / 开始时间 / 预计时长 / 用途），完成即清空。
2. 超过 **45 分钟**未释放视为过期，可被接管；接管前确认对方无正在进行中的 adb 安装 / 测量进程。
3. **同一波次的真机回归优先由 R4 统一执行**（本线负责），开发会话只做必要的最小验证，避免抢占。
4. 未登记就装机 / 占用属于违规；发现设备被占用时**排队等待**，不要 kill 别人的进程。

### 1.3 建议验证窗口（每波）

| 窗口 | 时段建议 | 内容 | 设备 |
|------|---------|------|------|
| W 波内 · 日常 | 开发会话按需登记，单次 ≤45 分钟 | 开发自测最小验证（装机 + 目标流程走查） | Pad 5 |
| W 波内 · 收口 | 每波倒数第 1–2 天，R4 统一登记 45–90 分钟 | 本波全量回归 + 性能对比（§2.5 基线） | Pad 5（+ K60 若在线） |
| W6 · 验收 | 由负责人与用户约定 | 最终验收（功能 + 性能 + 真机矩阵） | Pad 5 + K60 |

> 说明：K60 目前未接入 adb；在用户接入前，所有结论以 Pad 5 为准并在报告中显式标注「K60 未验证」。

---

## 2. 性能基线与方法

### 2.1 指标口径与红线

| 指标 | 口径（同设备 / 同构建类型 / 同 ABI 才可比较） | W2 基线 | 劣化阈值 | 超阈值处置 |
|------|--------------------------------------------|--------|---------|-----------|
| 冷启动时间 | `am start -W` 的 **TotalTime**，force-stop 后冷启动，**跑 3 次取中位数** | **1085 ms** | +15%（≥1248 ms） | 负责人评审并写入回归报告 |
| 内存峰值 | 冷启动后前台静置 60 s，每 2 s 采样 `dumpsys meminfo` 的 **TOTAL PSS**，取峰值 | **247,790 kB**（≈242 MiB） | +15% | 同上 |
| debug APK 体积 | `:app:phone:assembleDebug` 产物，按 ABI 拆分，**取 arm64-v8a** | **96.95 MiB**（101,663,749 B） | +5% | 同上 |

- 红线来自 `REQUIREMENTS.md` §12.2「性能红线：启动时间、内存占用、APK 体积不发生明显劣化」；阈值是 R4 给出的可执行口径，如需调整由负责人确认。
- 单次波动不作为结论；冷启动 <3 次、内存采样 <20 次视为无效测量。
- 当前基线是 **debug** 口径（带调试开销）；发布口径（release/staging）在 W6 前补测一次，两套数值不混用。

### 2.2 冷启动（可复现命令）

```powershell
# 前置：设备已登记占用；应用已安装并处于已登录状态
adb -s 43af8627 shell am force-stop com.zhangwenkang.cinefin.debug
Start-Sleep -Seconds 1
adb -s 43af8627 shell am start -W -n com.zhangwenkang.cinefin.debug/com.zhangwenkang.cinefin.MainActivity
# 输出按 run 保存，取 TotalTime 中位数（默认 3 次）
.\tools\test\Measure-ColdStart.ps1 -Serial 43af8627 -Runs 3
```

- 关注字段：`TotalTime`（主指标，含进程冷启动）、`WaitTime`（参考）、`LaunchState` 必须为 `COLD`。
- 测量前必须确认「已登录 + 首页有内容」；登录页 / 引导页的启动时间不计入基线。

### 2.3 内存峰值（可复现命令）

```powershell
adb -s 43af8627 shell am force-stop com.zhangwenkang.cinefin.debug
adb -s 43af8627 shell am start -W -n com.zhangwenkang.cinefin.debug/com.zhangwenkang.cinefin.MainActivity
# 每 2 s 采样一次，共 60 s；取 TOTAL PSS 峰值
.\tools\test\Measure-MemoryPeak.ps1 -Serial 43af8627 -Seconds 60 -IntervalSeconds 2
```

- 解析字段：`dumpsys meminfo <pkg>` 的 App Summary **TOTAL**（单位 kB）。
- 场景化峰值（播放 4K / 打开大 PDF / 滚动长列表）在 W4+ 按需追加 `-KeepRunning` 采样，并在报告中标注场景。

### 2.4 APK 体积（可复现命令）

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug --console=plain
.\tools\test\Measure-ApkSize.ps1          # 统计 apk 目录下全部 ABI 的 bytes/MiB/SHA1
```

- 必须**同 ABI、同构建类型**比较；debug 包含 FFmpeg / libmpv 等 `.so`，绝对值偏大属正常。

### 2.5 W2 基线数值（2026-09-30 首次测量）

| 项 | 值 |
|----|----|
| 代码版本 | `674ad8b`（master，即 W2 开工基线） |
| 设备 | Xiaomi Pad 5 `43af8627` / Android 13 / 竖屏 |
| 构建 | `:app:phone:assembleDebug`（flavor `libre`，debug） |
| 应用状态 | 已登录测试服务器，首页「继续观看」有内容 |
| 冷启动 TotalTime | 1094 / 1085 / 1075 ms → **中位 1085 ms**（WaitTime 中位 1089 ms） |
| 内存峰值 TOTAL PSS | **247,790 kB**（27 个采样，min 152,618 kB，稳态 ≈246,000 kB） |
| APK（arm64-v8a） | 101,663,749 B = **96.95 MiB**，SHA1 见 `apksize.json` |
| 其他 ABI | armeabi-v7a 94.65 MiB / x86 99.70 MiB / x86_64 101.74 MiB（仅供参考，非验收口径） |

**原始输出目录（本地，不入库）**：
`E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\baseline\20260930-211820-43af8627\`
（`summary.json` / `coldstart.json` / `coldstart-raw.txt` / `mempeak.json` / `mempeak-raw.txt` / `apksize.json` / `info.json`）

### 2.6 脚本清单与用法（`tools/test/`）

| 脚本 | 用途 | 关键参数 | 输出落点 |
|------|------|---------|---------|
| `Invoke-PerfBaseline.ps1` | **一键跑完整基线**：设备信息 + 冷启动 ×3 + 内存峰值 + APK 体积 | `-Serial` `-ColdStartRuns 3` `-MemorySeconds 60` `-Rebuild` | `…\.planning\cinefin-expansion\baseline\<时间戳>-<序列号>\` + `summary.json` |
| `Get-DeviceInfo.ps1` | 采集设备 / 系统 / RAM / 存储 / App 版本 | `-Serial` `-Package` | `info.json` / `info.txt` |
| `Measure-ColdStart.ps1` | 冷启动 ×N 取中位 | `-Runs` `-SettleSeconds` | `coldstart.json` / `coldstart-raw.txt` |
| `Measure-MemoryPeak.ps1` | 内存峰值采样 | `-Seconds` `-IntervalSeconds` `-KeepRunning` | `mempeak.json` / `mempeak-raw.txt` |
| `Measure-ApkSize.ps1` | APK 体积 / SHA1 | `-Build` `-ApkDir` | `apksize.json` |
| `Invoke-WaveRegression.ps1` | 每波回归只读检查（崩溃 / ANR、MediaSession、前台窗口、包信息、UI 文本、内存快照） | `-Wave W2..W6` `-LogcatTailLines` `-SkipUiDump` | `…\.planning\cinefin-expansion\regression\<W>-<时间戳>\` |

约定：**原始大输出一律写到仓库外**（`.planning\cinefin-expansion\…`），仓库只保留脚本与本文件的数值表。
脚本均为 PowerShell + `adb` 只读命令（`force-stop` / `am start` 仅用于构造验证场景）。

### 2.7 局限与升级路径

- 当前口径（`am start -W` / `dumpsys meminfo`）够做「不劣化」对比，但不是帧级启动分解。
- 后续可引入 **Macrobenchmark**（独立 `benchmark` 模块 + `StartupTimingMetric`，官方文档
  `developer.android.com/topic/performance/benchmarking/macrobenchmark-overview`；本机代理 30001 首次抓取返回 000，需重试）
  做帧级与滚动性能基线；新增模块属于工具链改动，需负责人批准后再做。
- 卡顿 / IO 深挖用 **Perfetto / 系统跟踪**（`developer.android.com/topic/performance/tracing`），只在定位具体问题时开启，trace 文件不入库。

---

## 3. 测试数据清单（🟡 待用户确认）

### 3.1 本地测试文件（`E:\codex_work\Android_Studio_Work_Space\test_files`）

| 文件 | 大小 | 格式用途 | 服务器对应条目 |
|------|------|---------|---------------|
| `attention_is_all_you_need.pdf` | 2.2 MiB | PDF 基础渲染 / 分页懒加载 | ✅ 已入 Books 库 `/medi/书籍/attention_is_all_you_need.pdf` |
| `Andas_Game_2007.cbz` | 16.5 MiB | CBZ 单页 / 双页 / 缩放 / RTL | ✅ 已入 Books 库 `/medi/书籍/Andas_Game_2007.cbz` |
| `futuristic_tales.cbz` | 0.7 MiB | CBZ 小文件对照 | ✅ 已入 Books 库 `/medi/书籍/futuristic_tales.cbz` |

### 3.2 服务器书籍（Books 库，共 4 条）

| 条目 | itemId | 用途 |
|------|--------|------|
| 雷普利全集（……）.epub | `8109915342fc71c405da49d632fc6978` | **EPUB 主验证样例**（打开 / 翻页 / 排版 / 主题 / 进度写回） |
| attention_is_all_you_need.pdf | `0a1f4dc1b7052d9d0d1c13d97333b9d8` | PDF（EB-3） |
| Anda's Game（.cbz） | `a147672978b46d08c05a5f7053887d8b` | CBZ（EB-4） |
| futuristic_tales（.cbz） | `6bbbb0ce7d2d3500ac6e9b12054be028` | CBZ（EB-4） |

### 3.3 服务器音乐与歌词（只读查询结果 2026-09-30）

| 项 | 数量 / itemId | 备注 |
|----|--------------|------|
| Audio 条目 | **100** | 样例 FLAC 23.5 MiB（`Container=flac`） |
| MusicArtist | 86 | **MusicAlbum 实体为 0** → 专辑必须按 Album 名客户端分组（与 `MUSIC_PLAN` W2 一致） |
| Playlist | **0** | ⚠️ 服务器无歌单：歌单浏览 / 队列保存验证需先由用户（或白名单内客户端调用）建立测试歌单 |
| 歌词样例 aLIEz | `3b31291fb0b41e86aae694d9f1a60799` | 83 行，日文原文 + 中文翻译共享时间戳 |
| 歌词样例 Brave Shine | `4e610c492dfe2a8ec2b9ef66892fcad5` | 27 行，同模式 |
| 歌词样例 爱的回归线 | `73303346e719875d87d509a5c6dfa0b3` | 57 行，纯中文 |

### 3.4 账号与权限边界（回归必查）

- 服务器 `https://jellyfins.zhangwenkang.com`（Jellyfin 10.11.8）**只读**；
- **写白名单**（仅此四类，且只操作测试账号自身数据）：`/UserItems/{itemId}/UserData`（进度 / 收藏）、
  `Sessions/Playing*`（播放上报）、播放列表读写；
- **禁止**：媒体库修改 / 删除 / 扫描、用户管理、图片与元数据改写；
- 回归时用 `adb logcat` 过滤 `/Items` 写请求与 `Delete` / `Sessions/Capabilities` 类调用，发现越界写即 FAIL。

### 3.5 需要用户补充 / 确认的缺口

1. **大 PDF 样例**：当前 PDF 仅 2.2 MiB，无法验证 EB-3「内存峰值不随文档大小线性增长」——建议补 1 个 ≥50 MiB 或 ≥500 页的 PDF。
2. **CBR 样例**（可选）：EB-4 的 CBR 分支视测试内容决定；不补则 CBR 按「不支持」记录。
3. **多字幕 / 音轨视频**：用于 W4–W5 播放器 UI 与 libass 回归（含 ASS 特效字幕、双语轨道）。
4. **10-bit H.264 片源**：验证硬解降级 mpv 路径（`PLAYER_PLAN` §9 已记录该坑）。
5. **K60 接入 adb**：手机形态回归依赖该设备上线。
6. **测试歌单**：见 §3.3（Playlist=0）。

> 以上 1–6 项**提交用户确认**；确认结果回填本节，未确认项在回归报告中标注「未覆盖」。

---

## 4. 每波回归清单

### 4.1 通用门禁（每个 PR 必过，`REQUIREMENTS.md` §12.1）

- [ ] `:app:phone:compileLibreDebugKotlin` 通过（必要时 `assembleLibreDebug`）
- [ ] `ktfmtCheck` 通过（含 Kotlin 改动时）
- [ ] 新增逻辑有单元测试（解析器 / 匹配算法 / 队列 / 语言识别 / 冲突策略）
- [ ] 真机最小验证记录（设备 + 版本 + 步骤 + 结果；截图或日志留档）
- [ ] 写白名单核对（§3.4）：无越界写调用
- [ ] `adb logcat` 无新增 `FATAL EXCEPTION` / `ANR`

### 4.2 W2 · 主体波 1（阅读器核心 / 音乐核心 / 测试基建）

| 域 | 检查项 |
|----|--------|
| 阅读器 | EPUB 打开正确；滚动 / 分页 / 双栏切换；排版设置（字号 / 行距 / 边距 / 对齐 / 字体）即时生效并持久化；四种主题（纸色 / 护眼 / 深色 / OLED）即时切换；平板横屏双栏正确 |
| 音乐 | 专辑（按 Album 名分组）/ 艺术家 / 歌曲浏览正确；队列增删 / 排序 / 「下一首播放」；连播 gapless 听觉无缝隙；播放上报写回成功（白名单内）；通知 / 锁屏可控 |
| 测试基建 | 本文件 §2.5 基线可复现；`tools/test/` 脚本在干净设备上可跑通 |
| 性能 | 冷启动中位 / 内存峰值 / APK 体积对比 §2.1 阈值 |

### 4.3 W3 · 主体波 2（进度与离线 / 歌词 / 页面设计）

- 阅读进度：退出重进恢复；离线暂存 + 联网回传；`/UserItems/{id}/UserData` 写入正确（白名单）。
- 离线阅读：飞行模式可打开并记录进度。
- 歌词：aLIEz / Brave Shine / 爱的回归线 三样例双语配对 / 逐行语言识别 / 默认简体中文 / 双语对照 / 滚动同步。
- UI：音乐 / 阅读页面接入新设计系统，**旧配色清零**；平板双栏体验。

### 4.4 W4 · 收口波 1（PDF / CBZ / 播放器 UI / 全页面换新）

- PDF：分页懒加载；内存峰值不随文档大小线性增长（用 §3.5 大 PDF 验证）。
- CBZ：单页 / 双页 / 缩放 / RTL 开关正确。
- 播放器 §11：控件精简、面板右侧化不压缩画面、命中区无回归、自动选字幕与打开即播。
- 全页面换新：UI-3 清单全覆盖，旧配色清零。

### 4.5 W5 · 收口波 2（libass / 音乐 P1 / 全量回归）

- libass：特效 ASS 与电脑端基本一致；ExoPlayer / mpv 双内核切换不受影响。
- 音乐 P1：睡眠定时、离线容量管理。
- **全量回归**：§4.2–4.4 全部重跑一遍，性能对比报告（vs §2.5 基线）+ 服务器只读核对。

### 4.6 W6 · 验收波

- `REQUIREMENTS.md` §12.1 通用门禁 + §12.2 功能验收要点逐条通过；
- 真机矩阵（Pad 5 全项 + K60 手机形态）完成，未覆盖项显式列出；
- 发布口径性能基线与 debug 口径并列归档；GPL / NOTICE / 图标 / 启动图检查。

### 4.7 自动化辅助

```powershell
# 每波收口时由 R4 执行（需先登记 device-lock）
.\tools\test\Invoke-WaveRegression.ps1 -Wave W2 -LogcatTailLines 3000
.\tools\test\Invoke-PerfBaseline.ps1 -Serial 43af8627 -ColdStartRuns 3 -MemorySeconds 60
```

`Invoke-WaveRegression.ps1` 覆盖「崩溃 / ANR 快照、MediaSession、前台窗口、包版本、UI 文本、内存快照」，
其余为人工项（本清单的复选项）。

---

## 5. 踩坑库（R4）

| # | 现象 | 原因 / 对策 |
|---|------|------------|
| 1 | PowerShell 脚本里 `adb shell am start -W …` 报「参数名 'W' 歧义」 | 给 adb 包装函数加了 `[Parameter()]` 会变成高级函数，`-W` 被抢去匹配 `-WarningAction`。**用 `$args` 透传，别加参数属性**（`tools/test/` 已注释） |
| 2 | `uiautomator dump` 在小米设备上打印 `theme_compatibility.xml ENOENT` 堆栈 | MIUI 系统噪声，dump 仍成功写入 XML；脚本需只取 XML 内容、丢弃 stderr |
| 3 | `adb logcat -t '09-30 21:00:00.000'` 时间戳带空格易被拆参 | 改用 `logcat -d -t <行数>` 尾部快照，或整段导出后再本地过滤 |
| 4 | 服务器偶发 `SocketTimeoutException` | 服务器侧偶发（`PROJECT_PLAN` §6），非客户端 bug；重试即可，但会拉长首屏时间从而影响内存峰值，测量前确认首屏已加载完成 |
| 5 | `dumpsys meminfo` 的 TOTAL PSS 在不同 Android 版本排版不同 | 脚本先匹配 `^\s*TOTAL:\s+(\d+)`（App Summary），回退 `^\s*TOTAL\s+(\d+)`（明细表） |
| 6 | APK 体积跨 ABI 比较失真 | 只比 arm64-v8a；且 debug 含 FFmpeg/libmpv，绝不与 release 直接比 |
| 7 | 首次安装 / 清数据后测启动，数值偏高且不可比 | 基线测量前确认「已登录 + 首页有内容」；清数据后需重新登录再测 |
| 8 | 服务器无 `MusicAlbum` 实体（0 条） | 专辑按 Album 名客户端分组（与 `MUSIC_PLAN` 一致）；别按官方实体模型写死 |
| 9 | 服务器 Playlist = 0 | 歌单 / 队列保存验证前需先有测试歌单（见 §3.5-6） |
| 10 | 真机被多会话抢占 | 一律走 device-lock（§1.2），45 分钟过期；未登记不得装机 |
| 11 | Macrobenchmark 文档经代理首次抓取失败（HTTP 000） | 代理偶发；重试或换 `benchmarking` 目录页；引入模块需负责人批准 |

---

## 6. 进度与决策日志

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-09-30 | v1.0 | W2-R4 开工：真机矩阵（Pad 5 已接入 / K60 待接入）、device-lock 窗口建议、性能基线方法与首次数值（冷启动中位 1085 ms / 内存峰值 247,790 kB / arm64 debug APK 96.95 MiB @674ad8b）、测试数据清单（含待确认缺口）、W2–W6 回归清单、踩坑库 11 条、`tools/test/` 6 个脚本 |

### 6.1 决策记录

- **D1（2026-09-30）**：性能基线先用 `adb` 口径（`am start -W` + `dumpsys meminfo` + APK 体积），
  Macrobenchmark 作为后续升级；理由是当天可复现、无新增模块、不阻塞 W2 回归。
- **D2（2026-09-30）**：阈值定为冷启动 / 内存 +15%、APK +5%（同口径），超阈值需负责人评审；
  红线来自 `REQUIREMENTS.md` §12.2，量化口径由本线给出。
- **D3（2026-09-30）**：原始输出一律落在
  `E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\`（baseline / regression），仓库只留脚本与数值表。
- **D4（2026-09-30）**：K60 未接入前，所有验收结论以 Pad 5 为准并在报告中标注「K60 未验证」。

### 6.2 取证与验证记录

| 日期 | 内容 | 结果 / 落点 |
|------|------|------------|
| 2026-09-30 | `.\gradlew.bat :app:phone:assembleDebug --console=plain` | BUILD SUCCESSFUL（241 tasks，1m25s） |
| 2026-09-30 | `.\gradlew.bat ktfmtCheck --console=plain` | BUILD SUCCESSFUL（1m54s；本波未新增 Kotlin 代码） |
| 2026-09-30 | `Invoke-PerfBaseline.ps1` 首跑（Pad 5） | 基线数值见 §2.5；原始输出 `baseline\20260930-211820-43af8627\` |
| 2026-09-30 | `Invoke-WaveRegression.ps1 -Wave W2` 冒烟 | `crashOrAnr=PASS`（logcat 尾部 2000 行无 FATAL/ANR）、前台 = `MainActivity`、TOTAL PSS = 253,777 kB；输出 `regression\W2-20260930-212247\` |
| 2026-09-30 | 服务器只读核对（Items / Lyrics） | Books 4 条、Audio 100 条、MusicArtist 86、MusicAlbum 0、Playlist 0；三首歌词样例行数 83 / 27 / 57，与 `REQUIREMENTS.md` §5.1 一致 |
