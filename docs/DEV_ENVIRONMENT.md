# 开发环境说明（DEV_ENVIRONMENT）

> 本文件不含任何密码。完整凭据（代理 / Jellyfin）存放在**本地共享文件**（不在仓库内，禁止提交）：
> `F:\Develop\codex_work\.planning\cinefin-expansion\proxy.md`

## 1. 网络代理（用于访问被墙资源）

- 代理主机：`proxy.zhangwenkang.com`（账号密码见本地共享文件）
- 端口：HTTP `30000`（`http://` 前缀）/ HTTPS `30001`（`https://` 前缀）
- 实测能力（2026-09-29）：
  - `30001`（TLS 隧道）：全部可用——Apple HIG、Android Developers、dl.google.com、GitHub raw、Maven Central；
  - `30000`（明文隧道）：GitHub / Maven Central 可用；**dl.google.com、plugins.gradle.org 不可用**。
- 用法（命令行工具首选 30001）：
  ```powershell
  curl.exe -x "https://user:******@proxy.zhangwenkang.com:30001" "<https 目标>"
  ```
- **Gradle 保持直连**（实测直连正常）；不要给 Gradle 配置代理（30000 走不通 Google Maven，会导致依赖解析失败）。

## 2. Jellyfin 测试服务器

- 地址：`https://jellyfins.zhangwenkang.com`（Jellyfin 10.11.8）
- 凭据：存于本地共享文件与 `Cinefin/.env.local`（均已 gitignore，禁止提交）
- 权限纪律：**只读 + 用户数据写白名单**（进度 / 收藏 / 播放列表）；禁止媒体库管理、扫描、删除

## 3. 构建与设备

- JDK：`F:\Develop\Android\Android Studio\jbr`（`$env:JAVA_HOME` 指向它；2026-10-06 工具链由 `D:\Android` 迁至 `F:\Develop\Android`）
- 编译验证：`.\gradlew.bat :app:phone:compileLibreDebugKotlin --console=plain`
- 打包：`.\gradlew.bat :app:phone:assembleLibreDebug --console=plain`
- 验收设备：Xiaomi Pad 5（Android 13，主）/ Redmi K60（Android 15）
- 真机验证需 `adb devices` 在线（开发阶段保持 USB 调试连接）

### 应用 ID 与 adb（W41 起）

- **运行时身份**（`applicationId`）：`io.github.zhangwenkang.aurorama`（debug `.debug` / staging `.staging`）；
- **Kotlin namespace / 包名**：仍为 `com.zhangwenkang.cinefin`（清单内相对类名、`am start -n <pkg>/<class>` 的类名部分都按 namespace 写）；
- 换 applicationId = 新 App 身份：旧包 `com.zhangwenkang.cinefin.debug` 的数据（登录态 / 下载 / SAF 授权）不迁移，需全新安装 + 重登；新包可用后卸载旧包；回滚 = 装回旧 APK（重新登录 / 重下）。

```powershell
adb -s 43af8627 install -r app\phone\build\outputs\apk\libre\debug\phone-libre-arm64-v8a-debug.apk
adb -s 43af8627 shell am start -W -n io.github.zhangwenkang.aurorama.debug/com.zhangwenkang.cinefin.MainActivity
adb -s 43af8627 shell am force-stop io.github.zhangwenkang.aurorama.debug
adb -s 43af8627 shell dumpsys package io.github.zhangwenkang.aurorama.debug | Select-String versionName
```

## 4. 会话约定（并行开发）

- 每个会话 = 1 个 feature 分支 + 1 个独立 worktree；
- 合并前 rebase 最新 `master`（包含 CI 修复），PR 由项目负责人 + 架构师审查；
- 需要联网查资料时，先读本地共享文件获取代理配置。
