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
| 1.1.0 | 2026-10-07（修复重建） | 2 | `assembleLibreRelease "-Paurorama.universalApk=true"` | 163.2 MB | 74.0 MB |

参考：同基线 debug（arm64-v8a）约 143 MB —— release 经 R8 混淆 + 资源压缩后约 73.9 MB（约 −48%）。

**1.1.0 发布资产校验 · ②（2026-10-07 · 修复重建候选，master 提交 `3b85d95`）——Q1–Q7 波后将再次重建（最终哈希以重建后回写为准）**

| 文件（上传时改名） | 体积（字节 / MiB） | SHA-256 |
|--------------------|--------------------|---------|
| `Aurorama-1.1.0-universal.apk`（构建产物 `phone-libre-universal-release.apk`） | 171,141,648 / 163.2 | `c00c33115536e6592e7d519e7c07577bf4af46535b9a20191a27005a31ef2f46` |
| `Aurorama-1.1.0-arm64-v8a.apk`（构建产物 `phone-libre-arm64-v8a-release.apk`） | 77,559,670 / 74.0 | `be25b20939629034e50cb369b8159a1370306e838e1df7556a3e5c47efef3ec9` |

- 重建原因：B9（首次下载必失败）/ B10（音乐后换内核 / 回退失效）/ B11 + B11b（系列页「播放」空白窗口）修复并入 1.1.0（D-F9 方案B）→ 哈希与初版不同。**2026-10-07 追加 D-F10（Q1–Q7 全并入 1.1.0）后，本表降级为候选记录：上传以 Q 波重建后的版本为准。**
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

**1.1.0（W76，2026-10-06 初版 / 2026-10-07 修复重建）检查状态**：版本号 `1.1.0 (2)` ✅；**D-F9 方案B：B9 / B10 / B11 + B11b 修复已并入 1.1.0**（`600be7a` / `d0942dc` / `1eb093f` / `3b85d95`，终合并态门禁 **920 / 0 / 0** ✅）；`assembleLibreRelease` 修复重建 + `apksigner verify`（v2、指纹一致；新哈希见 §7 ②）✅；`README` / `PRIVACY` / `NOTICE` / `LICENSE` 与 1.0.0 一致（W76 未改）✅；关于页版本号随构建显示 `1.1.0 (2)` ✅；截图沿用 1.0.0（`images/release/` 9 张；1.1.0 为缺陷修复 + 细节增强，未重拍）；真机冒烟 = W76 回归 **R1（K60，发布面补验）已完成**：①覆盖升级 ✅ / ②真实下载 ❌（既有缺陷 B9，**已修复、待修复包复验 V1**）/ ③SAF 本地库 ✅ / ④mpv 兜底自动回退 ✅ / ⑤Quick Connect ✅，**0 FATAL·ANR**（报告 `w76-reports/W76-R1.md`）；**R2（Pad 5，核心回归）已完成**（报告 `w76-reports/W76-R2.md`，0 FATAL·ANR；阅读组未覆盖 → 随修复包补测 V2）。**W76-R1b/R1c 定级取证**：D1 = P2（音乐会话粘住 → 已修 B10）/ D2 = P3（系列页「播放」空白 → 已修 B11/B11b）/ 首次下载必失败 B9（已修）——三项均**非 1.1.0 引入**。**2026-10-07 用户追加拍板（D-F10）**：Q1–Q7 **全部并入 1.1.0**（Q3 书籍库永久保留；Q4 主因合并定级 P2 + 对外文案简化）；**回归暂停令解除**——先修 Q 波 → 终门禁 → 候选 release → **V1/V2 回归（+ 播放组轻量复跑）** → **回归通过后正式重建签名 + 哈希落档** → 用户全检 → 通过后打 tag `v1.1.0` + GitHub Release + 上传 APK（**全检通过前不打 tag / 不发 Release**）。§7② 哈希为 Q 波前候选，最终以 Q 波后重建回写为准。Release notes 正文用 `docs/RELEASE_NOTES_v1.1.0.md`。

## 9. CI 发布（可选，后续）

- `.github/workflows/publish.yaml` 原为上游 Findroid 的自动发布流程（`on: push: tags: v*`，依赖 `FINDROID_KEYSTORE` / Play API 凭据并调用 `fastlane publish`，会尝试发布 Google Play）。
- **W72（2026-10-05）已将其改为仅 `workflow_dispatch` 触发**：推 `v*` tag 不再自动运行；该文件内的流水线仍是上游模板，**不要手动触发**（缺 secrets 且会尝试 Play 发布）。
- 当前的 1.0.0 发布走 §5 的手动流程；CI 自动发布（构建 → 签名 → 建 Release 上传 APK）后续按需重建，届时改为使用本仓库 secrets（keystore 的 base64 + 密码）并去掉 Play 步骤。
