# 开发环境说明（DEV_ENVIRONMENT）

> 本文件不含任何密码。完整凭据（代理 / Jellyfin）存放在**本地共享文件**（不在仓库内，禁止提交）：
> `E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\proxy.md`

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

- JDK：`D:\Android\Android Studio\jbr`（`$env:JAVA_HOME` 指向它）
- 编译验证：`.\gradlew.bat :app:phone:compileLibreDebugKotlin --console=plain`
- 打包：`.\gradlew.bat :app:phone:assembleLibreDebug --console=plain`
- 验收设备：Xiaomi Pad 5（Android 13，主）/ Redmi K60（Android 15）
- 真机验证需 `adb devices` 在线（开发阶段保持 USB 调试连接）

## 4. 会话约定（并行开发）

- 每个会话 = 1 个 feature 分支 + 1 个独立 worktree；
- 合并前 rebase 最新 `master`（包含 CI 修复），PR 由项目负责人 + 架构师审查；
- 需要联网查资料时，先读本地共享文件获取代理配置。
