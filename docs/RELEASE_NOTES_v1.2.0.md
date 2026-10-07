# 极光幕 · Aurorama 1.2.0 发布说明

> v1.1.0 之后的功能更新（2026-10-07）：**阅读流式 / 按需加载**——大书打开不再等整本下载，先出页、按需取、省流量；另有阅读状态提示与热切换体验修复。

## 关于本版本

极光幕（Aurorama）1.2.0：面向手机 / 平板的第三方原生 Jellyfin 客户端，基于 [Findroid](https://github.com/jarnedemeulemeester/findroid)（GPL-3.0，基线 `a28ac9e`）改造，以直接播放（Direct Play）为主。

包名 `io.github.zhangwenkang.aurorama`，版本 `1.2.0 (3)`，要求 **Android 9（API 28）及以上**。

## 本版亮点与修复

**阅读流式 / 按需加载（大书秒开）**

- **未下载的 EPUB / CBZ「先出页」**：打开不再等整本下载完成——只按需加载当前页与接下来的几页。（实测：229 MB EPUB 约 10 秒内出页、取量约 4–5 MB；旧行为需整本下载约 108 秒。CBZ 十几 MB 的书数秒出页。）
- **不自动整本下载（省流量）**：远端阅读只取所需，不会在后台悄悄下整本；要离线或整本保存时点「下载整本」，完成后**无缝热切换**为本地阅读（页码与进度不动）。
- **阅读状态一目了然**：顶栏区分「流式载入中… / 流式阅读中 / 下载中 N% / 离线可读 · 大小」，不再出现「已经流式出页却显示下载中」的误导。
- **取消与恢复**：离开阅读页立即停止在途请求；断网或打开失败有明确错误提示与「重试」，恢复网络即可继续，不再卡在假进度。
- **PDF**：打开即显示下载进度。（PDF 的按需加载需要更换渲染引擎，已列入后续版本计划；当前仍为整本下载后打开。）

**阅读体验修复**

- 修复 CBZ 手动下载完成后热切换偶发卡顿（旧页源回收阻塞主线程，实测从最高约 4.8 秒降至毫秒级）；
- 修复打开未下载的书时顶栏「下载中 0%」停滞不动的旧问题（失败会明确转为可重试状态）。
- 修复书架滚动到「未下载、且服务器没有封面图」的 PDF 时，后台会静默拉取整本文件的问题（实测该流量从约 0.4 MB/s 降为 0；这类卡片现在直接显示类型占位图）。

## 安装与升级

从 [GitHub Releases](https://github.com/zhangwenkang-github/Aurorama/releases) 下载签名 APK，直接安装（首次安装需允许「安装未知应用」）。**1.1.0 / 1.0.0 可直接覆盖安装**，数据与登录态保留。

| 下载文件 | 面向 | 建议 |
|----------|------|------|
| `Aurorama-1.2.0-universal.apk`（163.3 MB） | 全部 ABI（armeabi-v7a / arm64-v8a / x86 / x86_64） | 不确定机型 / 兼容性优先 |
| `Aurorama-1.2.0-arm64-v8a.apk`（74.1 MB） | 仅 arm64-v8a | 2017 年后的主流 64 位手机 / 平板（体积约为整包一半） |

> 两个包内容一致、同为 release 签名；**同一设备上请始终用同一签名的包升级**（换签名无法覆盖安装）。调试包（`.debug` 后缀）可与正式包共存、数据互相独立。

## 签名指纹

APK 签名证书（v2 方案，SHA-256）：

```
E4:49:C4:AA:DD:E1:91:A7:89:FE:72:CA:8F:7E:C5:47:15:47:C2:14:46:DD:C0:4E:4B:7B:8B:23:CE:E1:55:FF
```

校验方式：`apksigner verify --print-certs Aurorama-1.2.0-*.apk`。各包体积与 SHA-256 见 `docs/RELEASE_PLAN.md` §7。

## 许可证与致谢

- 本项目以 **GNU General Public License v3.0**（[LICENSE](https://github.com/zhangwenkang-github/Aurorama/blob/master/LICENSE)）开源；
- 基于 **Findroid**——<https://github.com/jarnedemeulemeester/findroid>（GPL-3.0，基线 `a28ac9e`）改造，原项目版权归其作者所有；
- 第三方组件与许可证清单见 [NOTICE](https://github.com/zhangwenkang-github/Aurorama/blob/master/NOTICE)，应用内「客户端设置 → 关于」也可查看完整列表。

## 已知限制

- **远端 PDF 仍需整本下载后打开**（打开过程可看到下载进度）；PDF 按需加载 / 渲染引擎替换列入后续版本计划；
- 阅读流式加载需要网络连接；离线阅读请先「下载整本」；
- **MIUI（小米 / Redmi）**：系统媒体面板不显示本应用自定义的「关闭」键（系统面板行为限制），可用面板内其它控制键或回到应用内关闭；
- 直接播放优先：服务器端不支持直放的片源会静默转为 HLS（画质 / 音轨以服务器转码设置为准）；硬解与服务器转码都不成功时自动降级 libmpv 软解，功耗与发热更高；
- 本地媒体库受 Android 存储限制：无法选择 `Android/data`、`Android/obb`、其它应用私有目录；
- TV（遥控器 / D-pad）界面未适配，仍面向手机与平板；
- 仅做 GitHub Releases 分发，暂无国内商店 / Google Play 渠道。

## 反馈

问题与建议请开 [GitHub Issues](https://github.com/zhangwenkang-github/Aurorama/issues)（附设备型号、Android 版本与复现步骤）。
