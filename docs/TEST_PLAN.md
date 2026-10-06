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
adb -s 43af8627 shell am force-stop io.github.zhangwenkang.aurorama.debug
Start-Sleep -Seconds 1
adb -s 43af8627 shell am start -W -n io.github.zhangwenkang.aurorama.debug/com.zhangwenkang.cinefin.MainActivity
# 输出按 run 保存，取 TotalTime 中位数（默认 3 次）
.\tools\test\Measure-ColdStart.ps1 -Serial 43af8627 -Runs 3
```

- 关注字段：`TotalTime`（主指标，含进程冷启动）、`WaitTime`（参考）、`LaunchState` 必须为 `COLD`。
- 测量前必须确认「已登录 + 首页有内容」；登录页 / 引导页的启动时间不计入基线。

### 2.3 内存峰值（可复现命令）

```powershell
adb -s 43af8627 shell am force-stop io.github.zhangwenkang.aurorama.debug
adb -s 43af8627 shell am start -W -n io.github.zhangwenkang.aurorama.debug/com.zhangwenkang.cinefin.MainActivity
# 每 2 s 采样一次，共 60 s；取 TOTAL PSS 峰值
.\tools\test\Measure-MemoryPeak.ps1 -Serial 43af8627 -Seconds 60 -IntervalSeconds 2
```

- 解析字段：`dumpsys meminfo <pkg>` 的 App Summary **TOTAL**（单位 kB）。
- 场景化峰值（播放 4K / 打开大 PDF / 滚动长列表）在 W4+ 按需追加 `-KeepRunning` 采样，并在报告中标注场景。

### 2.4 APK 体积（可复现命令）

```powershell
$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'
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

---

## 7. W47 全量回归报告（2026-10-03 · R4 回归会话）

会话：分支 `feature/w47-full-regression`（worktree 3865，起点 master `046d99f`）；设备 Pad 5 `43af8627` 主 + K60 `8e875894` 抽验（device-lock 14:12 登记 / 16:xx 释放）；安装包 = 本 worktree `:app:phone:assembleDebug`（arm64-v8a，双机 `install -r`）；服务器全程只读（仅 UserData / Sessions 白名单路径）。
所有 `adb` 命令带 `-s <serial>`；分辨率 / 密度 / 旋转 / 网络 / freeform 设置均按登记还原。

### 7.1 A 组（无需用户配合）

| # | 项目 | 结果 | 证据关键词 |
|---|------|------|-----------|
| A1 | 全链路冒烟（Pad 5 主 + K60 抽验） | ✅ 0 App FATAL / ANR | 逐页 dump：首页（继续观看 / 接下来 / 本地媒体）、媒体库（8 库 + 本地 2 库）、音乐（105 专辑）、书架（8 本）、下载（0/0/0 + 25.01 kB + 53.68 GB 可用）、客户端设置（账号 / 网络 / 媒体库 / 下载与缓存 / 播放与音乐 / 离线模式）、播放器、阅读器、搜索、离线；K60 首页 985 ms + 音乐 `state=PLAYING(3)` + 媒体库 8 库 |
| A2 | 本地视频真实播放 | ✅ 通过 | `dumpsys media_session` `state=3` + `run-as` fd `187 -> /mnt/user/0/emulated/0/Download/W47Media/w47_big.mp4`（45.7 MB）+ `PlayerActivity$onCreate` UiState `path=content://…primary%3ADownload%2FW47Media%2Fw47_local.mp4`、`container=mp4`、`fileLoaded=true` |
| A3 | 阅读大文档：金田一 2.36 GB（本地 SAF）+ 自造旋转页 PDF | 🟡 打开 ✅ / 双栏扫描触发 **D-W47-1**；旋转页 ✅ | 打开 ≈ **13.4 s**（滚动模式，tap → 内容区像素均值 >100）；双栏日志 `reader spread layout pages=5006 slots=4973 landscape=4938 at=1,2,3,4,5,6,7,8`；**扫描后 Native Heap 21 MB → 1,586 MB、PSS 1.72–2.45 GB → MIUI killinfo + SIGKILL（pid 9617 / 13072 两次被杀、`wm_finish_activity … proc died without state saved`）**；旋转页 PDF（`/Rotate` 90 / 270 各 1 页 + 原生横版 1 页 + 竖版 1 页）`pages=4 slots=4 landscape=3 at=0,2,3`，第 1 页白底区域宽高比 **1.43 ≈ 842/595**（旋转后按横版渲染） |
| A4 | 播放器复验 | ✅ 2 项通过 + 1 项单测覆盖（1 项未触发见 7.3） | ①SRT「背景 + 描边」互斥：`AssSubtitleScriptTest.backgroundWinsWhenBackgroundAndOutlineBothSelected` 断言 `BorderStyle=3` + `OutlineColour=&H4C000000`（背景优先，不再画黑描边）；②end-帧 OFF 档（默认 `pref_player_stay_at_end_frame=false`）：本地单条目播完 → `PlayerViewModel: queue end: close player（队列播完）` + 返回 MainActivity + `state=1`、`queue size=0`；③Compact 自由窗口：`settings global enable_freeform_support=1` + `am start --windowingMode 5` + `am task resize … 0 0 1000 1600`（w444dp h711dp，`mode=freeform`）→ 工具行为纯图标（`content-desc` = 选择音轨 / 选择字幕轨 / 倍速 / 码率 / 解码 / 信息 / 睡眠 / 播放队列 / 画面比例 / 设置，无文字标签），本地视频 `state=3` 播放正常 |
| A5 | 性能抽样 | 🟡 冷启动超阈值 → **已评审接受新基线**（D-W47-2，用户 2026-10-03） | 冷启动 `am start -W` TotalTime **1537 / 1268 / 1322 ms → 中位 1322 ms**（基线 1085 ms，**+21.8%，超 +15% 阈值**，`LaunchState=COLD`，首页已登录有内容）；音乐长列表（105 专辑，4 次上滑）`gfxinfo` **610 帧 / janky 17（2.79%）/ p50 7 ms / p90 12 ms**；双栏阅读 PSS：4 页 PDF 293 MB / Native 39 MB，金田一（本地 5006 页）见 D-W47-1 |
| A6 | 缩略图清理遗留（W45） | ✅ 修复 + 单测 | `LocalThumbnailRules.purgeThumbnails`（删 `<itemId>.jpg|.fail`）＋ `deleteLibrary` / `removeFolder` 调用＋ DAO `getLocalMediaItemsByFolder`；data 单测净增 1 项（purge 只删目标条目、不动其他文件） |

### 7.2 W47 新增缺陷

| ID | 级别 | 现象 | 根因 / 定位 | 建议 |
|----|------|------|------------|------|
| D-W47-1 | **P1（内存 / 进程被杀）** | 本地媒体库（SAF `content://`）PDF 进双栏：金田一 5006 页时 Native Heap 1.59 GB、PSS 1.72–2.45 GB，随后被 MIUI 杀进程（2 次复现，无 Java / native crash，`killinfo` + `libprocessgroup` SIGKILL） | `PdfPageSource(descriptor)` 的 `layout = null` → `collectPageAspectRatios()` 回退**逐页 `PdfRenderer.openPage`**（W33 只给 `File` 路径接了 PdfBox `PdfLayoutSource`，本地 SAF 打开未覆盖） | ①把 SAF fd 接 `PdfLayoutSource`（`/proc/self/fd/N` 随机读或 PdfBox 流式 + 8 MB 溢出）；②兜底：大书（如 >1500 页）跳过逐页回退、直接固定两页划分；修复后按 W33 口径真机复验（Native ≤60 MB / PSS ≤310 MB） |
| D-W47-2 | P2（性能阈值） | 冷启动中位 1322 ms，超基线 +15% 阈值（1248 ms） | 与 W2 基线相比应用状态更大（本地库 / 缩略图 / 更完整首屏）。debug 口径 | **用户 2026-10-03 拍板：接受 debug 新基线 1322 ms**；发布包（release / AAB）实测若仍超 +15% 阈值，再开性能定位波并做启动 profile 抽样 |

**D-W47-1 修复（W48，2026-10-03，分支 `fix/w48-saf-pdf-scan-memory`，起点 master `1ebdbcc`）**：SAF fd 经
`dup` + `Os.pread`（4 KB 页缓存）接 `PdfLayoutSource`（`PDFParser` + `ScratchFile` 8 MB 混合缓冲），批量路径
不可用时大书（>1500 页）跳过逐页 `openPage`、用安全默认版式；渲染路径不变。K60 `8e875894` 复验（同一
2.53 GB / 5006 页本地 SAF 样本）：扫描日志 `pages=5006 slots=4973 landscape=4938`（与修复前一致）、Native
49.8–53.9 MB、PSS 358–368 MB（同机滚动基线 316.3 MB）、+60 s 不增长、双栏→滚动→分页可回落；W22 测试书
`landscape=2 at=14,15`、RTL 相位对图 8/8、0 误拼；设备副作用全部还原。详见 `READER_PLAN` §2 D24 / §7.16 与
`DOWNLOAD_PLAN` §16。

### 7.3 未触发项（B 组 / 故障窗口）

- 下载 **FAILED 自动重试**（2026-10-03 B 组补验）：**纯设备侧断网不会产生 FAILED** —— 系统停在 `PAUSED_WAITING_FOR_NETWORK`（App 映射 `PAUSED` + `NETWORK_UNAVAILABLE`，下载页仍计「进行中」），恢复网络后由 **DownloadManager 自行续传**（116.8 → 186.7 MB / 40 s，无应用层日志）。应用层 `isAutoRetryEligible` 只接受 `FAILED`，故该路径在纯断网下无法触发 → 标「**部分覆盖**：系统自愈续传 ✅ / 应用层 FAILED 重试未触发」；补验需服务器错误注入或传输层故障窗口。
- **空间不足失败列表**：**跳过（用户确认 2026-10-03）** —— 用户明确「不方便，可不测或用其他方法」，本轮不以任何方式（含等效方案）制造小空间，不算失败。
- **reboot 续传**：✅ **通过（2026-10-03 16:03–16:10，用户配合窗口；分支 `feature/w47c-reboot-resume`）** —— 重启前 201,328,281 B / 进行中（未暂停未删除）→ 用户重启 + 解锁后**系统自行续传**（无任何操作）：16:06 494,962,329 B → 每 30 s 稳定增长（543 → 599 → 680 → 757 → 824 → 891 → 980 → 1,067 MB）→ 16:10 完成，`.download` 残片重命名为最终文件 **1,145,104,598 B（1.15 GB）**；下载页 `0 进行中 · 2 已完成 · 0 失败`、占用与文件一致；**播放校验**：详情页「播放」→ `PlayerActivity` UiState `path=/storage/emulated/0/Android/data/…/files/downloads/beec8170-…`、`fileSizeBytes=1,145,104,598`、fd `123 -> …/downloads/beec8170-…`、`state=PLAYING(3)`（**本地文件本地优先播放**，非服务器流）；测试下载已用 App 删除流程清理（`files/downloads` 空、已占用回 2.24 MB）。
- **M4A 设备样本**：✅ 已完成（2026-10-03，见 §7.5），不再是未覆盖项。
- **end-帧 OFF 档的服务器缓流片尾**：本轮用本地单条目验证了 OFF 档目标动作（关闭播放页）；服务器直连流片尾 `state=6 buffering` 场景未复现（W24.5 原样挂账）。
- libass 初始化失败注入、`sub-add` 网络失败注入（W19 / W18 既有遗留）：未触发。

### 7.4 设备还原（2026-10-03 收工）

- Pad 5 `43af8627`：删除测试库 **W47Media**（App 内删除 + `/sdcard/Download/W47Media` 源文件删除）、清 `/sdcard/w47*.xml`；阅读器偏好还原（`pref_reader_mode` 回「滚动」，离线模式 false）；Wi-Fi / 飞行模式 / freeform 全局开关还原；App `am force-stop`。
- K60 `8e875894`：清 `/sdcard/w47k_ui.xml`、App `am force-stop`。
- 服务器：未做任何写操作（白名单外零调用）。

### 7.5 W47-B 补验（2026-10-03，Pad 5 `43af8627`，分支 `feature/w47b-b-verification`）

M4A 样本 = 服务器 `m4a_60s_sample_file_574KB`（itemId `09805eb4-5342-457b-383f-46e4cb9b5ee9`；容器 `mov,mp4,m4a,3gp,3g2,mj2`；574 KB / 60 s）：

| 步骤 | 结果 | 证据 |
|------|------|------|
| 标签读取 | ✅ 未检测到标签（真·无标签，非读取失败） | 面板「未检测到 ReplayGain 标签」；logcat `ReplayGainTagReader$read: …来源=NONE track=null album=null`；独立复核 = 服务器拉全量 587,509 B，`REPLAYGAIN` 0 次、freeform `----` atom 0 个 |
| 本机覆盖 -6.0 dB | ✅ | 滑杆拖动 → 面板「曲目标签 -6.0 dB（本机设置）」；文件 `files/replaygain/09805eb4….txt` = `track=-6.0` |
| 清除覆盖 + 回读 | ✅ 无负缓存 | 清除后面板回「未检测到 ReplayGain 标签」；logcat 出现**新的** `ReplayGain 读取：item=09805eb4 来源=NONE`（重新读取，不沿用缓存失败） |
| 重启持久化 | ✅ | 覆盖 +4.0 dB（`track=4.0`）→ `am force-stop` + 重启 → 音乐页「0:05 / 1:00 · 上次播放」→ 恢复播放 → 面板仍「曲目标签 +4.0 dB（本机设置）」 |
| 增益应用 | 🟡 代码链 + 数学可测 | `MusicAudioEffectsController.applyReplayGainToProcessor()`：`processor.replayGainFactor = 10^(dB/20)`（-6 dB → 0.501、+4 dB → 1.585，钳制 `MIN/MAX_GAIN_FACTOR`）；**未做声学 / 电平测量**（无采集条件），听感需人工复验 |
| 稳定性 / 副作用 | ✅ | 全程 0 FATAL / ANR；覆盖已清除、`files/replaygain/` 空、`pref_music_replaygain_mode` 回 `off` |

下载链路（超能力女儿 第 1 集，1.15 GB，Pad 5）：开始下载（7%）→ 关 Wi-Fi + 飞行模式 → 2 分钟任务保持「进行中」（系统 `PAUSED_WAITING_FOR_NETWORK`，文件停 115,672,729 B）→ 恢复网络 12 s 内 `Active default network` 恢复 → **系统自行续传** 116.8 → 120.8 → 157.3 → 186.7 MB（40 s，无应用层日志）→ App 删除流程清理（`0 进行中 / 1 已完成（既有书籍 2.22 MB）/ 0 失败`，`files/downloads` 空）。**结论：设备侧断网 = 系统自愈续传，不进入 FAILED。**

设备还原：网络 / 飞行模式还原（ping 通）、下载测试数据经 App 删除、ReplayGain 覆盖清除、`pref_music_replaygain_mode=off`、App force-stop（device-lock 见登记文件）。

**reboot 续传（W47-C，2026-10-03 16:03–16:10，用户配合窗口；分支 `feature/w47c-reboot-resume`）**：重启前 201,328,281 B / 进行中（不暂停、不删除）→ 用户重启 + 解锁后**系统 DownloadManager 自行续传**：16:06 首帧 494,962,329 B（+293.6 MB），此后 30 s 采样稳定增长（543,196,825 → 599,164,569 → 679,708,313 → 757,171,865 → 824,082,432 → 891,324,057 → 979,601,049 → 1,067,419,287）→ 16:10 `.download` 残片消失 = 重命名完成，最终文件 **1,145,104,598 B**；下载页 `0 进行中 · 2 已完成 · 0 失败`、已占用 1.15 GB；播放校验 = 本地文件本地优先（见 §7.3 证据）；清理后 `files/downloads` 空、已占用 2.24 MB（既有书籍）。

### 7.6 W61 发布前全量回归（2026-10-04 · 分支 `test/w61-full-regression` · R4 回归会话）

会话 = 分支 `test/w61-full-regression`（worktree bb79，起点 master `a984fbc` 含 W60b；**纯验证无代码改动**）；设备 Pad 5 `43af8627` 主 + K60 `8e875894` 抽验（device-lock 09:14 登记 / 11:0x 释放，命令全部 `-s <serial>`）；测试包 = 本 worktree `:app:phone:assembleDebug`（arm64-v8a，双机 `install -r`，09:16 装机）；服务器全程只读（仅 UserData / Sessions 白名单动作，收藏与下载测试已复原）。

#### 7.6.1 已完成回归项（Pad 5 主 + K60 抽验）

| 组 | 项目 | 结果 | 证据关键词 |
|---|------|------|-----------|
| 全链路冒烟 | 冷启动 → 首页（继续观看 / 继续阅读 / 接下来 / 最近添加 / 最新·动漫·其他2·书籍·书籍3·音乐 + 「全部」入口） | ✅ | 「继续收听」无数据走廊隐藏（与 W60 一致）；阅读进度更新后「继续阅读」顺序实时变化 |
| | 顶层图标回主页（W56 D64） | ✅（K60 抽验） | 音乐专辑详情（二级）点顶栏图标 → 回音乐主页「共 105 张专辑」，未开抽屉 |
| | 视频页（库卡 / 「全部库」选库下拉 / 选库库视图 / 库网格 / 长按多选 五键 / 全选 / 退出多选） | ✅ | 全选 17 项 = 电影库 17 部 Movie 已加载全集（与服务器递归一致） |
| | 音乐（105 专辑 + 来源筛选 + 专辑长按多选「下载整张」+ 迷你条状态） | ✅ | 底栏「已选 1 项 + 下载整张」 |
| | 书架（8 本 + 网格·列表·排序·筛选 + 长按三键 下载 / 标记已读 / 收藏） | ✅ | 三键无播放 / 无删除 |
| | 媒体库（两段式：本地媒体库 / 服务器媒体库 + 库卡计数） | ✅ | Pad 5 本地「书籍 · 1 个文件夹 · 10 项」；K60 本地空态「0 个文件夹 · 尚未扫描到媒体」 |
| | 下载页（钻取式 IA + 页签 进行中·失败·已完成 + 类型筛选 + 专辑钻取 全部暂停·全部继续·删除 + 曲目平铺） | ✅ | 0 进行中 · 6 已完成 · 0 失败；已占用 664 MB（含书籍 661 MB）· 可用 49.5 GB |
| | 搜索（服务器 / 本地双分组 + 计数 + 空态） | ✅ | 「AURA」→ 服务器 1 条；「2026」→ 本地 1 条；「attention」→ 未找到（服务器书籍不在搜索范围，与现状一致） |
| | 我的收藏（空态 → 收藏 1 项 → 类型筛选（剧集 0 / 电影 1）→ 名称排序 → 多选批量取消 → 归零） | ✅ | 卡片「已收藏」角标；详情收藏 → 收藏页「共 1 个项目」单一数据源；Snackbar「已更新 1 项」 |
| | 设置（五组 + 账号卡组内首行 + 下载三项 + 缓存 50 + 离线模式 + 关于） | ✅ | 同时下载数 2（1–8 手动输入弹窗）/ 下载限速 0 MB/s / 仅 Wi-Fi / 缓存大小 50 MB |
| 播放 | 服务器直连播放（HLS 转码） | ✅ | 起播约 40 s（DTS→转码慢）→ `state=3`；`/videos/47de9056…/master.m3u8`；1920×1080 SDR H.264 |
| | 字幕（2 条外挂 ASS：Chs&Jap / Jap） | ✅ | 面板 = 字幕模式 自动·始终显示·关闭 + 语言优先 + 侧载导入 + 延迟 ±0.1 s + 主·次字幕 + 外观；**实机渲染双语「我、我还什么都没说啊 / ま、まだまだ何も言ってないじゃん」** |
| | 音轨面板 | ✅ | 语言优先 + 延迟 −0.05s·0.00s·+0.05s + 音轨列表（AAC） |
| | 倍速 | ✅ | 1.5× 实测 pos/wall = 1.5；面板 0.25–3×；回落 1× 实测 ratio≈1.0 |
| | 手势 | ✅ | 双击右 +15 s / 双击左 −5 s / 横向滑动 seek +117.9 s |
| | PiP | ✅ | `mode=pinned`（w290dp h163dp）；双击退出回 App |
| | 睡眠定时（播放器面板 + 音乐面板 + 倒计时 + 取消） | ✅ | 音乐设 10 分钟 → 底栏「睡眠 09:56」递减 →「取消定时」清零 |
| | 信息面板 | ✅ | 字幕数量 2 / 音轨 1 / HLS / 14.11 GB / 总时长 1:46:31 |
| 阅读 | EPUB（雷普利全集）打开 + 分页翻页 | ✅ | 「离线可读 · 2.1 MB」；左滑翻页像素 diff 72.3 |
| | CBZ（futuristic_tales）三档模式 分页·双栏·滚动 + 翻页 | ✅ | 4/4 → 3/4；双栏「3-4/4」；滚动 diff 68.0 |
| | PDF（attention）三档模式 + 搜索 + 批注 | ✅ | 分页 1/15 / 双栏 1-2/15 / 滚动；搜索「attention」65 条命中 + 跳转回 1/15；批注面板 + 框选模式进入·退出 |
| 音乐 | 队列 / 歌词（服务端双语 + 语言切换 + 空态）/ EQ（摇滚预设 +5·+3·−1·+3·+5 → 原声复原）/ ReplayGain（关闭·曲目·专辑 + 本机覆盖 + 清除）/ 桌面歌词（授权 → 桌面悬浮 → 关闭 → appops 还原）/ 手势（左滑歌词·右滑返回·下滑关闭）/ 睡眠 | ✅ | 桌面歌词在其他应用上层（Home 后悬浮可见）；歌词面板「来源：服务端 简体中文 / 日文 / 英文 / 混合行」 |
| 离线 | 离线模式开（书架「离线模式 · 已下载 4 本」+ 点击直读 attention）+ 关（还原 false）；离线「我的收藏」空态（预期空缺） | ✅ | `pref_offline_mode` true → false |
| 下载 | 专辑入队（Snackbar「已加入下载队列 · 1 首」+ 查看）→ 完成（664 → 692 MB）→ 多选删除（确认框 → 7 → 6 已完成，692 → 664 MB） | ✅ | 前台通知「1 个活动下载」；书籍封面缓存 `files/book_covers` 3 张 JPEG + 2 个 `.fail` |
| 性能 | 冷启动 / 内存 / 滚动 jank | 见 7.6.4 | |
| 稳定性 | 双机 App 0 FATAL / 0 ANR | ✅ | Pad 5 crash buffer 仅 1 条 `UiAutomation`（uiautomator 工具进程 `Bad file descriptor`，非 App）；K60 clean |

#### 7.6.2 缺陷与观察项（本波无代码改动，全部交负责人排波）

| ID | 级别 | 现象 | 定位 / 证据 | 建议 |
|----|------|------|------------|------|
| F1 | 非 App（服务器侧数据） | 媒体库 / 视频页「共 N 个项目」数字随机波动（实测 8→7→6→1→3…） | 同一请求连续 3 次采样 ChildCount 全不同（电影 6/8/4、动漫 2/1/8、书籍 7/6/3…）；递归 `TotalRecordCount` 稳定（电影 20 = 17 Movie + 3 Folder；动漫 2364） | 服务器 `ChildCount` 返回不可靠；App 可改用 `TotalRecordCount` 口径或在 UI 注明近似；非本波引入 |
| F2 | P3 观察 | 离线书架「已下载 4 本」与下载页书籍清单不一致（离线清单含「虚构推理 (2026) 639.6 MB」、未含 futuristic_tales 703 KB） | 离线书架 dump vs 下载页书籍 4 本逐项对比 | 待复核 allowOffline / 离线书目口径（低优先） |
| F3 | P3 观察 | EPUB「雷普利全集」滚动模式下上下滑 / PageDown 内容区无位移（分页模式左滑正常） | 4 次取证 diff=0；同机 CBZ 滚动 diff=68 正常 | 疑该书当前章节不足一屏或 EPUB 滚动专属问题，待复核 |
| F4 | P3 观察 | 长按倍速手势本次未复现 2× | adb 长按 2.6 s 采样 speed=0.0（释放后 1.0） | 疑 adb 长按与真实手指差异；W15 已验收，建议人工复核 |

#### 7.6.3 未覆盖项

- 跳片头 / 片尾提示条动态触发（片头等待窗口成本高）、Trickplay 缩略图（服务器 trickplay 数据未确认）、片尾 end-帧服务器缓流复验；
- 下载 FAILED 终态 / 失败徽标（红叹号）故障注入（网络类失败按设计进入重试）；「重试中 · 第 N 次」本波未构造；下载「进行中」进度环徽标（下载过快未抓到帧）；
- 剧集库整剧批量下载真机（数据量大，W58b 遗留）；本地媒体库条目删除置灰样本；平板两列大数据量视觉节奏；书籍封面观感（人工）；
- 搜索「已下载视频」下载角标样本（无英文名已下载视频 + `input text` 不支持中文）；角标淡入逐帧；
- mpv stall 报错复现 / libass 失败注入（既有遗留，未触发窗口）；音乐批量服务器压力上限；
- 登录页离线入口（需登出，未破坏登录态）。

#### 7.6.4 性能数字（Pad 5，同构建 debug arm64；与既有基线对比）

| 项 | 本次 | 对比 |
|----|------|------|
| 冷启动 TotalTime ×3 | 1957 / 1394 / 1384 → **中位 1394 ms** | W2 基线 1085 ms（+28.5%）；**W47 接受基线 1322 ms（+5.4%，未超 +15% 阈值）** |
| 内存峰值（60 s 静置 / 27 样本） | **268,511 kB**（min 217,007） | W2 基线 247,790 kB（**+8.4%，未超 +15%**） |
| 音乐列表滚动（105 专辑 / 4 次上滑） | **562 帧 / janky 6（1.07%）/ p50 8 ms / p90 10 ms / p95 11 ms / p99 15 ms** | W47: 610 帧 / 2.79% / p50 7 ms / p90 12 ms（**改善**） |
| 首页快速翻滚（副样本 / 5 次） | 576 帧 / janky 36（6.25%）/ p90 17 ms | 参考值（页面不同，不与 W47 音乐口径混比） |
| K60 冷启动（参考） | 1246 ms COLD | 首测，供后续基线 |

#### 7.6.5 设备还原（2026-10-04 11:0x 收工）

- Pad 5 / K60：`am force-stop`；`/sdcard/w61*.xml` 清理；未改分辨率 / 密度 / 旋转 / 网络；
- Pad 5 偏好核对：`pref_reader_mode=scroll`、`pref_offline_mode=false`；倍速回落 1×；桌面歌词已关 + `SYSTEM_ALERT_WINDOW` appops 还原 `deny`；
- 测试下载（A/Z|aLIEz 专辑 27.4 MB）经 App 批量删除清除（7 → 6 已完成、692 → 664 MB）；收藏测试条目经批量取消复原（「我的收藏」回 0）；
- 服务器仅白名单动作（收藏 / 播放上报 / 阅读进度，属正常用户数据）；无越界写。

#### 7.6.6 W62 修复记录（2026-10-04 · 分支 `fix/w62-regression-defects` · 开发会话）

会话 = 分支 `fix/w62-regression-defects`（worktree afa9，起点 master `2cdaff8`）；设备 Pad 5 `43af8627` 主 + K60 `8e875894` 抽验（device-lock 10:32 登记 / 11:21 释放，命令全 `-s <serial>`）；测试包 = 本 worktree `:app:phone:assembleDebug`（arm64-v8a，11:12 构建，双机 `install -r`）；服务器只读（另做只读 API 探针 `/Items` 采样，零写）。

| ID | 级别 | 结论（根因 / 修法 / 或「不改 + 原因」） | 证据 |
|----|------|--------------------------------------------------------------------|------|
| F1 | 中 · 服务器侧数据 + 客户端口径 | **修**。①只读探针复核：服务器 10.11.8 对 UserView 的 `ChildCount` 是**随机值**（同一请求连续采样：电影 8/4/6、动漫 4/5/2、书籍 9/5/3…），且 `RecursiveItemCount` 字段**根本不返回**（显式请求仍为空）→ 不能直接上屏。②稳定口径 = 按库直接子项查询的 `TotalRecordCount`（`parentId` + `recursive=false` + `limit=1`，两次采样完全一致：电影 **17** / 动漫 **94** / 其他2 **2** / 书籍 **8** / 书籍3 **8** / 音乐 **124** / 音乐测试 **0** / Playlists **0**），并发取数 + 60 s 进程内缓存（库列表在侧栏 / 视频页 / 媒体库页 / 书架各读一次），失败回退 `ChildCount`，两者皆无则不占位。③**未采用**「递归 TotalRecordCount」（动漫 2364 = 94 系列 + 2099 单集 + 171 季，与库内容页「1-94 / 94」不一致；音乐 / 书籍同理含容器层级）→ 新口径 = **进入该库后内容页看到的条目数**（电影 17 = 库页 `1-17 / 17`）。 | 只读探针 ×3 + 直接/递归对比；真机 Pad 媒体库 8 库全项、视频页 电影 17、K60 视频页 17 / 94，重进一致。代码 `data` `LibraryItemCount.kt`（纯函数 + TTL 缓存）+ `JellyfinRepositoryImpl.getLibraries()`；+5 单测 |
| F2 | P3 | **修**（两处都改到位）。①**书单口径**：离线书架原按 `allowOffline` 过滤，下载页不过滤；实测 `pref_offline_blocked_books` 里正是 `futuristic_tales`（`6bbbb0ce…`，W36 以来遗留状态）→ 书架「4 本」且无处回开（离线媒体库只列视频、没有书籍管理视图）。改为书架**列出全部已下载书籍**（关闭项行内置灰 + 开关可回开），与下载页「已完成 · 书籍」对齐。②**书名口径**：下载页原优先服务器元数据，离线时退化成「离线书籍 xxxxxxxx」占位，与书架（`.title` 侧车）不同名 → 统一「侧车优先 → 服务器名兜底 → 占位」。 | 真机离线模式：书架「离线模式 · 已下载 **5 本**」= 下载页 5 本同名（attention_is_all_you_need / futuristic_tales / 雷普利全集 / Anda's Game / 虚构推理 (2026)）。代码 `core` `OfflineBookNaming.kt`（纯函数，+3 单测）+ `app/phone` `OfflineMediaVisibility.downloadedBooks()`（+1 单测）/ `OfflineMediaViewModel` / `OfflineShelfScreen` + `modes:film` `DownloadsViewModel` |
| F3 | P3 | **不改（非 App 缺陷，素材也正常）**：Readium 3.4.0 Android 的 EPUB「滚动」= **每个 spine 资源内部垂直滚动 + 资源之间左右滑动翻页**（`EpubPreferences.scroll` 已生效；AAR 内 `R2BasicWebView.scrollLeft/scrollRight` + `disablePageTurnsWhileScrolling` 证实**没有**「纵向滚到底自动翻章」）。书首是封面 / 扉页 / 版权等**不足一屏**的资源，且资源滚到底后再上下滑不会有位移 → W61「上下滑无位移」。人工复测步骤：打开《雷普利全集》→ 左滑数次进正文 → 上下滑有位移；到章末 / 短页时用左右滑翻资源（分页档不受影响）。 | 真机 Pad 5（滚动档）：正文资源 `split_008/009/016` 上下滑 **18.7–22.7%** 像素位移；资源底部再滑 0.00–0.01%；左右滑翻资源 18.5–19.2%（logcat 资源名 `_split_009 → _split_010`）；EPUB = calibre 重排书（137 spine、无 `rendition:layout`、非固定版式） |
| F4 | P3 | **不改**：长按倍速无自定义参数——平台 `GestureDetector` 默认 500 ms + 触摸 slop，多点守卫 `pointerCount > 1`，章节跳转手势默认关闭；`enableSpeedIncrease()` 只在 `isPlaying` 时生效，release 统一回填。W61「speed=0.0」= **采样瞬间播放器不在播放态**（缓冲 / 转码窗口），不是 adb 注入限制。人工复测步骤：播放中在画面中央按住约 1 s（左右 1/5 边缘 + 章节手势开启时会改为跳章节）→ 出「2×」角标，松手回 1×。 | 真机 Pad 5（adb `input swipe x y x y 2600` 长按）：`dumpsys media_session` **1.0 → 2.0（1.4 s / 2.3 s 两采样）→ 释放回 1.0** |

**门禁（2026-10-04 11:12 · 本 worktree）**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务逐个 `--rerun` **713 项 / 0 失败 0 错误**（app **172** / core **76** / data **50** / player:local 110 / film 48 / book 113 / music 132 / player:core 12；基线 704 → **新增 9** = data 5 + core 3 + app 1）。

**红线申报**：未动 `NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local`；改动 = `data`（库计数口径）、`core`（书籍显示名纯函数）、`modes:film`（下载页书名）、`app/phone`（离线书架书籍清单口径）。

**未覆盖（转人工 / 后续）**：剧集库整剧批量下载真机（数据量）；本地媒体库条目「删除」置灰样本（本机本地库为书籍库）；搜索「已下载视频」角标样本（无已下载视频）；登录页离线入口（需登出，未破坏登录态）；F1 库卡在离线态的表现（离线库列表走既有空态，无样本）。

**设备还原（2026-10-04 11:21）**：双机 App force-stop；`/sdcard/w62*.xml`、`k62*.xml`、`/data/local/tmp/w62_offline_on.sed` 清理；离线模式经 App 内「退出离线模式」还原 → `pref_offline_mode=false`（核对）；`pref_reader_mode=scroll` 保持 W61 基线；未改分辨率 / 旋转 / 网络；服务器仅白名单动作（阅读进度 / 播放进度上报）。
