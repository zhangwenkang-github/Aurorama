# 极光幕 · 发布流程（RELEASE_PLAN）

> 建线：W71（2026-10-05）｜维护：发布负责人｜适用：1.0.0 起的 GitHub Releases 署名 APK
> 路线（用户 2026-10-05 拍板）：**自用为主 + GitHub 开源发布**；国内商店 / Google Play 后续再议，本波不做商店材料。

---

## 1. 发布物

| 产物 | 说明 | 面向 |
|------|------|------|
| `Aurorama-<version>-universal.apk` | 全部 ABI 的整包（armeabi-v7a / arm64-v8a / x86 / x86_64） | 兼容性优先 / 不确定机型 |
| `Aurorama-<version>-arm64-v8a.apk` | 仅 arm64-v8a 分包（体积约一半） | 主流 64 位手机 / 平板 |

- 两个 APK 均为 **release 签名**；`applicationId = io.github.zhangwenkang.aurorama`（debug 包为 `.debug` 后缀，可与 release 共存，数据互不影响）。
- 版本号：`versionName` 走语义化（`1.0.0`），`versionCode` 每次发布 **+1**；定义在 `buildSrc/src/main/kotlin/Versions.kt` 的 `APP_CODE` / `APP_NAME`。
- 升级规则：同一服务器 / 设备的正式包必须**同签名**（换 keystore 会导致无法覆盖安装，只能卸载重装）。

## 2. 签名（keystore）

| 项 | 值 |
|----|----|
| 密钥文件 | `E:\codex_work\Android_Studio_Work_Space\.release-keys\aurorama-release.jks` |
| 类型 | PKCS12 · RSA 4096 · 有效期 10000 天 · alias `aurorama` |
| 证书 SHA-256 | `E4:49:C4:AA:DD:E1:91:A7:89:FE:72:CA:8F:7E:C5:47:15:47:C2:14:46:DD:C0:4E:4B:7B:8B:23:CE:E1:55:FF` |
| Gradle 配置 | 仓库根 `keystore.properties`（**gitignored，不入库**） |

`keystore.properties` 字段（密码只由 Gradle 读取，**任何日志 / 汇报 / 文档不得回显密码内容**）：

```properties
storeFile=E:\\codex_work\\Android_Studio_Work_Space\\.release-keys\\aurorama-release.jks
storePassword=***
keyAlias=***
keyPassword=***
```

- `app/phone/build.gradle.kts` 在配置阶段检查该文件：存在且四字段齐全 → release 变体启用签名；缺失 / 不完整 → 打印提示并跳过签名（产物为未签名 APK，构建不中断）。staging 变体保持不签名。
- **备份**：keystore 与 properties 必须离线备份（至少云盘 + 本地各一份，分开存放）。密钥丢失 = 无法再发布可覆盖安装的升级包。

## 3. 构建

```powershell
$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'

# 发布包（universal + 4 个 ABI 分包）
.\gradlew.bat :app:phone:assembleLibreRelease -Paurorama.universalApk=true --console=plain
```

- 产物目录：`app/phone/build/outputs/apk/libre/release/`
  - `phone-libre-universal-release.apk` → 上传时重命名为 `Aurorama-<version>-universal.apk`
  - `phone-libre-arm64-v8a-release.apk` → 重命名为 `Aurorama-<version>-arm64-v8a.apk`
  - 其余 ABI（armeabi-v7a / x86 / x86_64）分包按需使用，默认不上传。
- `-Paurorama.universalApk=true` 只控制是否额外打整包；不加参数时只产出 ABI 分包（日常构建更快）。

## 4. 签名验证（发布前必过）

```powershell
& 'F:\Develop\Android\AndroidSDK\build-tools\37.0.0\apksigner.bat' verify --print-certs <apk>
```

- 期望 `certificate SHA-256 digest` = 上表证书指纹（十六进制小写形式 `e449c4aa…e155ff`）。
- 期望 `apksigner verify` 至少通过 **v2** 方案（本项目 minSdk 28，v2 足够；v1 关闭）。

## 5. GitHub Release 步骤

1. **打 tag**（由负责人在用户全检通过后执行）：

   ```powershell
   git tag -a v1.0.0 -m "Aurorama 1.0.0"
   git push origin v1.0.0
   ```

2. 打开 <https://github.com/zhangwenkang-github/Aurorama/releases> → **Draft a new release** → 选择 tag `v1.0.0`。
3. 上传两个 APK（步骤 3 的两个文件，按 `Aurorama-1.0.0-universal.apk` / `Aurorama-1.0.0-arm64-v8a.apk` 命名）。
4. **粘贴 Release notes**：正文直接用 `docs/RELEASE_NOTES_v1.0.0.md`（中文草稿；含亮点 / 安装说明 / 签名指纹 / 已知限制）。简版模板（按需增删）：

   ```markdown
   ## 极光幕 · Aurorama 1.0.0

   首个公开发布版本：第三方 Jellyfin 客户端（手机 / 平板），基于 Findroid 改造，GPL-3.0。

   - 影视：ExoPlayer + FFmpeg + libmpv 双内核、libass 特效字幕、手势体系、Trickplay / 片头尾跳过
   - 音乐：歌词（逐字 / LRC）、EQ / ReplayGain / 交叉淡化、后台与锁屏播放
   - 阅读：EPUB / PDF（搜索 + 批注）/ CBZ
   - 下载 / 离线：自研断点续传引擎、离线媒体库；本地媒体库（SAF）

   安装：Android 9（API 28）及以上。包名 `io.github.zhangwenkang.aurorama`。
   完整改动见 commit 记录；隐私说明见 PRIVACY。
   ```

5. 发布前自检：待上传 APK 的 SHA-256 / 体积与 §7 记录一致（`Get-FileHash`）。
6. 发布后自检：Release 页面两个 APK 可下载、大小与本地一致（见 §7 体积记录）、`apksigner verify --print-certs` 通过且指纹 = §2。

## 6. 版本号规则

- `versionName`：语义化 `MAJOR.MINOR.PATCH`（新功能 +MINOR、修复 +PATCH、不兼容变更 +MAJOR）。
- `versionCode`：**每次发布 +1**（单调递增，不跳号、不复用）；改 `Versions.kt` 后同步更新本文件 §7 的体积 / 版本记录。
- tag：`v<versionName>`（如 `v1.0.0`）。

## 7. 记录

| 版本 | 日期 | versionCode | 构建命令 | universal | arm64-v8a |
|------|------|-------------|----------|-----------|-----------|
| 1.0.0 | 2026-10-05 | 1 | `assembleLibreRelease -Paurorama.universalApk=true` | 163.1 MB | 73.9 MB |
| 1.1.0 | 2026-10-07（Q 波后重建） | 2 | `assembleLibreRelease "-Paurorama.universalApk=true"` | 163.2 MB | 74.0 MB |
| 1.2.0 | 2026-10-07（候选＝发布对象） | 3 | `assembleLibreRelease "-Paurorama.universalApk=true"` | 163.3 MB | 74.1 MB |

参考：同基线 debug（arm64-v8a）约 143 MB —— release 经 R8 混淆 + 资源压缩后约 73.9 MB（约 −48%）。

**1.2.0 发布资产校验 · 候选＝发布对象（2026-10-07 · W77 阅读流式波，master 提交 `6ef1901`（代码合并 `f4d4933`）；本表为上传口径）**

| 文件（上传时改名） | 体积（字节 / MiB） | SHA-256 |
|--------------------|--------------------|---------|
| `Aurorama-1.2.0-universal.apk`（构建产物 `phone-libre-universal-release.apk`） | 171,240,020 / 163.3 | `5b27d343fb6ba4aeb13e640c229d3c9510481b7d8ffbc2fa314396bc6df65fd5` |
| `Aurorama-1.2.0-arm64-v8a.apk`（构建产物 `phone-libre-arm64-v8a-release.apk`） | 77,658,042 / 74.1 | `071a0ec4320702cc15d2f734c4db9823687636b1e1982e5668d4b6b595a351bd` |

- 内容 = W77-1（EPUB 远程流式首开）+ W77-2（CBZ 远端页源）+ W77-3（三态状态 / 进度 + 移除 EPUB 自动整本下载）+ W77-4C（CBZ 热切换阻塞修复）+ W77-5（远端 PDF 封面不再整本 Range 拉取，回退类型占位）+ 版本号 **1.2.0 (3)**；D-F14 三格式统一「不自动整本下载」。
- `apksigner verify`：两份均 **v2 = true**（与 minSdk 28 口径一致）；证书 SHA-256 = `e449c4aa…e155ff`（与 §2 指纹一致）。
- 终端合并态门禁（master `f4d4933` + 文档 `6ef1901`）：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun-tasks` = **975 项 / 0 失败 0 错误**（app 273 / core 106 / data 68 / player:core 12 / player:local 178 / film 53 / book 145 / music 140；日志 `w77-evidence/gate_merged_w77_5_*.log`）。
- 回归与抽验：W77-4A（K60 8/8）+ W77-4B（Pad 5 12/12）+ W77-4C（P3 修复复测：CBZ 热切换 4796 ms → 7 ms）+ W77-5（K60：书架 PDF 封面流量 6.96 MB/20 s → **0 B**，报告 `w77-reports/W77-5.md`）；**1.2.0 候选（重建轮次）发布面抽验 = W77-4F**（报告 `w77-reports/W77-4F.md`）；**上传前待办 = 用户全检**（通过前不打 tag / 不发 Release）。
- 发布用副本在 `.planning/cinefin-expansion/w77-evidence/candidate/Aurorama-1.2.0-*.apk`（候选即发布对象，不再重建）。

**1.1.0 发布资产校验 · ③（2026-10-07 · Q 波后正式重建，master 提交 `61d13a8`；本表为上传口径）**

| 文件（上传时改名） | 体积（字节 / MiB） | SHA-256 |
|--------------------|--------------------|---------|
| `Aurorama-1.1.0-universal.apk`（构建产物 `phone-libre-universal-release.apk`） | 171,141,668 / 163.2 | `38b5992f4a372f63165d7918d8d89fdc0f1cb8e109a96ecf8ff4cc3c436c3f29` |
| `Aurorama-1.1.0-arm64-v8a.apk`（构建产物 `phone-libre-arm64-v8a-release.apk`） | 77,559,690 / 74.0 | `310ca8fdd0e60515b741e6396db03dae1cf52352f2e99c8d9bfe9d1a68d0012c` |

- 内容 = B9 / B10 / B11 / B11b（D-F9）+ Q1–Q7（D-F10）全部并入；回归对象 = 同代码候选包 `ef55c0af…`（V1 K60 / V2 Pad 5 通过，`w76-reports/W76-V1.md` / `W76-V2.md`）；本表为回归通过后的正式重建（与候选功能一致、仅哈希不同）。
- `apksigner verify`：两份均 **v2 = true**（v1 / v3 / v4 = false，与 minSdk 28 一致）；证书 SHA-256 = `e449c4aa…e155ff`（与 §2 指纹一致）。
- 终合并态门禁（master `61d13a8`）：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun-tasks` = **941 项 / 0 失败 0 错误**（app 273 / core 104 / data 68 / player:core 12 / player:local 178 / film 53 / book 113 / music 140）。
- 上传前待办 = **用户全检**（通过前不打 tag / 不发 Release）；发布用副本在 `.planning/cinefin-expansion/w76-evidence/Aurorama-1.1.0-*.apk`（旧构建在 `superseded/`，回归候选在 `candidate/`）。

**1.1.0 发布资产校验 · ②（2026-10-07 · 修复重建候选，master 提交 `3b85d95`）——已被 ③ 取代（勿上传）**

| 文件（上传时改名） | 体积（字节 / MiB） | SHA-256 |
|--------------------|--------------------|---------|
| `Aurorama-1.1.0-universal.apk`（构建产物 `phone-libre-universal-release.apk`） | 171,141,648 / 163.2 | `c00c33115536e6592e7d519e7c07577bf4af46535b9a20191a27005a31ef2f46` |
| `Aurorama-1.1.0-arm64-v8a.apk`（构建产物 `phone-libre-arm64-v8a-release.apk`） | 77,559,670 / 74.0 | `be25b20939629034e50cb369b8159a1370306e838e1df7556a3e5c47efef3ec9` |

- 重建原因：B9（首次下载必失败）/ B10（音乐后换内核 / 回退失效）/ B11 + B11b（系列页「播放」空白窗口）修复并入 1.1.0（D-F9 方案B）→ 哈希与初版不同。**2026-10-07 追加 D-F10（Q1–Q7 全并入 1.1.0）后本表作废：上传以 ③（Q 波后正式重建）为准。**
- `apksigner verify`：两份 APK 均 **v2 = true**（v1 / v3 / v4 = false，与 minSdk 28 口径一致）；证书 SHA-256 = `e449c4aa…e155ff`（与 §2 指纹一致）。
- 合并态门禁（W76 终版，master `3b85d95`）：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun-tasks` = **920 项 / 0 失败 0 错误**（app 272 / core 93 / data 68 / player:core 12 / player:local 169 / film 53 / book 113 / music 140）。
- 构建产物不入库，路径：`app/phone/build/outputs/apk/libre/release/`；发布用副本在 `.planning/cinefin-expansion/w76-evidence/Aurorama-1.1.0-*.apk`（含 1.0.0 对照包；初版副本在 `superseded/`）。

**1.1.0 发布资产校验 · ①（2026-10-06 · 初版构建，master 提交 `458eb9a`）——已作废（勿上传）**

| 文件（上传时改名） | 体积（字节 / MiB） | SHA-256 |
|--------------------|--------------------|---------|
| `Aurorama-1.1.0-universal.apk`（初版） | 171,125,240 / 163.2 | `9c20b06cb1a6b4c4e557c4860b374d27c1de1b1eed994f3d9b3c321245c58570` |
| `Aurorama-1.1.0-arm64-v8a.apk`（初版） | 77,543,262 / 74.0 | `d24df47ddc4839dc80820532115383d13657da9a36fd8fdf54ad508356566b0e` |

- `apksigner verify`：两份均 v2 = true、指纹一致（同上）；合并态门禁 = 899 项 / 0 失败 0 错误（日志 `w76-evidence/gate_merged.log`）。
- 作废原因：B9/B10/B11 修复合并后重新构建（见上方 ②）；初版副本已移至 `w76-evidence/superseded/`。

**1.0.0 发布资产校验（2026-10-05 · 发布构建，master 提交 `58e62fd`）**

| 文件（上传时改名） | 体积（字节 / MiB） | SHA-256 |
|--------------------|--------------------|---------|
| `Aurorama-1.0.0-universal.apk`（构建产物 `phone-libre-universal-release.apk`） | 171,042,160 / 163.1 | `d418bcd1fc448373d389a4b71b1e9e133bc89098fca5bf920d178c42aa8241dd` |
| `Aurorama-1.0.0-arm64-v8a.apk`（构建产物 `phone-libre-arm64-v8a-release.apk`） | 77,460,182 / 73.9 | `4760c773f67a6f4a355bc944132fef67355ae4e2648f807f831ad2b182180308` |

- `apksigner verify -v`：两份 APK 均 **v2 = true**（v1 / v3 / v4 = false，与 minSdk 28 口径一致）；证书 SHA-256 = `e449c4aa…e155ff`（与 §2 指纹一致）。
- 构建产物不入库，路径：`app/phone/build/outputs/apk/libre/release/`；每次重新构建哈希会变化，发布前以当次构建输出为准并回写本表。
- 门禁（W72 复跑）：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` = **813 项 / 0 失败 0 错误**；`assembleLibreRelease "-Paurorama.universalApk=true"` 成功。

**1.0.0 验收记录（2026-10-05，分支 `release/w71-packaging`）**

- 门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **813 项 / 0 失败 0 错误**。
- 签名：universal 与 arm64-v8a 两个 APK `apksigner verify` 通过（v2；v1 关闭），证书 SHA-256 = `e449c4aadde191a789fe72ca8f7ec5471547c21446ddc04e4b7b8b23cee155ff`（与 §2 指纹一致）。
- 真机冒烟（K60 `8e875894` 主 + Pad 5 `43af8627` 抽验，01:30–02:01）：release 与 debug 共存且数据互不影响；首启 → 登录 → 首页；播放（Hi10P → 静默转 HLS + ASS 字幕渲染；Pad 5 1080P HEVC 直放）；阅读 PDF / EPUB；音乐「播放全部」；下载页；关于页（隐私 / NOTICE 对话框）；**双机 0 FATAL / 0 ANR**。
- 详细结论与未覆盖项见 `.planning/cinefin-expansion/device-lock.md` 对应条目。

## 8. 每次发布检查清单

- [ ] `Versions.kt` 版本号已更新，`versionCode` 大于上一发布
- [ ] 全量门禁绿：根 `assembleDebug`（含 TV）+ `ktfmtCheck` + 8 个单测任务
- [ ] `assembleLibreRelease` 成功；`apksigner verify` 通过且指纹与 §2 一致
- [ ] 真机冒烟（K60 主 + Pad 5 抽验）：首启 / 登录 / 首页 / 播放（HLS + 字幕）/ 阅读 / 音乐 / 下载，0 FATAL·ANR
- [x] `README.md` / `PRIVACY` / `NOTICE` / `LICENSE` 与当前版本一致；**`PRIVACY` / `NOTICE` 有改动时同步 `app/phone/src/main/res/raw/privacy_policy.txt` / `notice.txt` 应用内副本**（W71 完成；W72 仅改 README 截图段）
- [x] 关于页显示版本号 / 包名 / 许可 / 隐私 / 项目链接正确（W71 完成；W72 双机复验链接指向 `zhangwenkang-github/Aurorama`）
- [x] 截图（W72 起）与实际界面一致（W72：`images/release/` 9 张，实拍自 1.0.0 正式包，K60 + Pad 5）
- [ ] 打 tag → 上传 APK → Release notes → 发布后自检

> 1.0.0 的未勾选项（版本号已就位，tag / 上传 / Release notes 粘贴）由发布负责人在用户全检通过后执行；Release notes 正文用 `docs/RELEASE_NOTES_v1.0.0.md`。

**1.1.0（W76，2026-10-06 初版 / 2026-10-07 Q 波后正式重建）检查状态**：版本号 `1.1.0 (2)` ✅；**D-F9 方案B（B9 / B10 / B11 + B11b）+ D-F10（Q1–Q7）全部并入 1.1.0**（`600be7a` / `d0942dc` / `1eb093f` / `3b85d95` / `61d13a8`，终合并态门禁 **941 / 0 / 0** ✅）；`assembleLibreRelease` 正式重建 + `apksigner verify`（v2、指纹一致；新哈希见 §7 ③）✅；`README` / `PRIVACY` / `NOTICE` / `LICENSE` 与 1.0.0 一致（W76 未改）✅；关于页版本号随构建显示 `1.1.0 (2)` ✅；截图沿用 1.0.0（`images/release/` 9 张；1.1.0 为缺陷修复 + 细节增强，未重拍）；真机回归 = **R1（K60 发布面补验）/ R2（Pad 5 核心回归）已完成**（`w76-reports/W76-R1.md` / `W76-R2.md`）；**V1（K60 发布包复验）✅**：覆盖升级 / B9 严格首下（`pm clear` + Quick Connect）/ B11b 起播 / B10 切内核 / Q7 5 次 / 播放复跑 / Q1 / 0 FATAL·ANR（首帧口径裁减：release 无 Timber，实测 6.0–6.6 s @ 当日 0.5–1.2 MB/s 网络；报告 `w76-reports/W76-V1.md`）；**V2（Pad 5 阅读组）✅**：书架 / EPUB·PDF·CBZ / 进度恢复 / 设置保持 / 离线 + 飞行模式 /「已完成」列表（报告 `w76-reports/W76-V2.md`；books 库需 `zhangwenkang` 账号）。**新观察项 Q8–Q10**（`FIX_PLAN` §6.1，不阻塞发布）。**用户全检（2026-10-07）✅ 通过**（含 ① #2 管理员入口代测 `w75-reports/W75-S2.md`；② #1 吸附手感代测 `w75-reports/W75-S1.md` §四）→ **已发布**：tag **`v1.1.0`** + GitHub Release「极光幕 / Aurorama v1.1.0」+ 双 APK 上传（assets 体积 / 哈希与 §7③ 一致）；发布后自检 ✅（assets 可下载；**实下载 SHA-256 与 §7③ 复核一致**）。Release notes 正文 = `docs/RELEASE_NOTES_v1.1.0.md`。

## 9. CI 发布（可选，后续）

- `.github/workflows/publish.yaml` 原为上游 Findroid 的自动发布流程（`on: push: tags: v*`，依赖 `FINDROID_KEYSTORE` / Play API 凭据并调用 `fastlane publish`，会尝试发布 Google Play）。
- **W72（2026-10-05）已将其改为仅 `workflow_dispatch` 触发**：推 `v*` tag 不再自动运行；该文件内的流水线仍是上游模板，**不要手动触发**（缺 secrets 且会尝试 Play 发布）。
- 当前的 1.0.0 发布走 §5 的手动流程；CI 自动发布（构建 → 签名 → 建 Release 上传 APK）后续按需重建，届时改为使用本仓库 secrets（keystore 的 base64 + 密码）并去掉 Play 步骤。
