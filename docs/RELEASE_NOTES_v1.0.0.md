# 极光幕 · Aurorama 1.0.0 发布说明

> 本文是 GitHub Release 正文的中文草稿（W72 准备，2026-10-05）。发布动作（打 tag `v1.0.0`、建 Release、上传 APK）由发布负责人执行，步骤见 `docs/RELEASE_PLAN.md` §5。

## 关于本版本

极光幕（Aurorama）**首个公开发布版本**：面向手机 / 平板的第三方原生 Jellyfin 客户端，基于 [Findroid](https://github.com/jarnedemeulemeester/findroid)（GPL-3.0，基线 `a28ac9e`）改造，以直接播放（Direct Play）为主，自用为主、开源分享。

包名 `io.github.zhangwenkang.aurorama`，版本 `1.0.0 (1)`，要求 **Android 9（API 28）及以上**。

## 功能亮点

**影视**

- 首页 / 媒体库 / 搜索 / 收藏 / 下载 / 服务器控制台；电影、剧集、季、单集全链路
- 播放内核：ExoPlayer（硬解）+ FFmpeg 软解，解码失败自动降级 libmpv（二级兜底）
- 字幕：SRT / VTT / SSA-ASS（libass 渲染，支持特效字幕）、外挂字幕导入、字幕语言优先级与跨视频记忆
- 手势：加载中也能用的双击 / 横滑快进快退（排队落点）、长按倍速、亮度 / 音量滑动、双指缩放
- 画中画、章节刻度与跳转、Trickplay 预览、片头片尾跳过
- 进度写回 Jellyfin；系统媒体面板可控（播放 / 暂停 / 上一集 / 下一集 / 关闭）

**音乐**

- 音乐库浏览、播放队列、后台与锁屏播放
- 歌词：内嵌 / 本地 LRC 导入与编辑、逐字歌词、悬浮歌词窗
- 音效：均衡器、ReplayGain、交叉淡化

**阅读（EPUB / PDF / CBZ）**

- EPUB（Readium）、PDF（PdfBox-Android，双栏整页 / 搜索 / 高亮批注）、CBZ 漫画自然序
- 阅读进度与 Jellyfin 同步

**下载 / 离线**

- 自研断点续传引擎（HTTP Range、暂停保片、失败分类与退避、存储预检）
- 整剧 / 整季 / 整专辑批量下载；剧集 → 季 → 集、专辑 → 曲目层级
- 离线媒体库与逐项「允许离线观看」

**本地媒体库**

- SAF 建库（视频 / 音乐 / 书籍），本地媒体扫描、缩略图与封面生成

**平板优先**

- 常显侧轨（可手动折叠、宽度自适应）、库内容双列网格、横竖屏与分屏适配

**其它**

- 界面与隐私政策简体中文 / 繁体中文 / 英文等多语言（简体中文为「极光幕」品牌译名）
- 纯本地客户端：不收集、不上传任何数据（见 [PRIVACY](https://github.com/zhangwenkang-github/Aurorama/blob/master/PRIVACY)）

## 安装

从 [GitHub Releases](https://github.com/zhangwenkang-github/Aurorama/releases) 下载签名 APK，直接安装（首次安装需允许「安装未知应用」）。

| 下载文件 | 面向 | 建议 |
|----------|------|------|
| `Aurorama-1.0.0-universal.apk`（163.1 MB） | 全部 ABI（armeabi-v7a / arm64-v8a / x86 / x86_64） | 不确定机型 / 兼容性优先 |
| `Aurorama-1.0.0-arm64-v8a.apk`（73.9 MB） | 仅 arm64-v8a | 2017 年后的主流 64 位手机 / 平板（体积约为整包一半） |

> 两个包内容一致、同为 release 签名；**同一设备上请始终用同一签名的包升级**（换签名无法覆盖安装）。调试包（`.debug` 后缀）可与正式包共存、数据互相独立。

## 签名指纹

APK 签名证书（v2 方案，SHA-256）：

```
E4:49:C4:AA:DD:E1:91:A7:89:FE:72:CA:8F:7E:C5:47:15:47:C2:14:46:DD:C0:4E:4B:7B:8B:23:CE:E1:55:FF
```

校验方式：`apksigner verify --print-certs Aurorama-1.0.0-*.apk`。各包体积与 SHA-256 见 `docs/RELEASE_PLAN.md` §7。

## 许可证与致谢

- 本项目以 **GNU General Public License v3.0**（[LICENSE](https://github.com/zhangwenkang-github/Aurorama/blob/master/LICENSE)）发布；
- 基于 **Findroid**——<https://github.com/jarnedemeulemeester/findroid>（GPL-3.0，基线 `a28ac9e`）改造，原项目版权归其作者所有；
- 第三方组件与许可证清单见 [NOTICE](https://github.com/zhangwenkang-github/Aurorama/blob/master/NOTICE)，应用内「客户端设置 → 关于」也可查看完整列表。

## 已知限制

- **MIUI（小米 / Redmi）**：系统媒体面板不显示本应用自定义的「关闭」键（系统面板行为限制），可用面板内其它控制键或回到应用内关闭；
- 直接播放优先：服务器端不支持直放的片源会静默转为 HLS（画质 / 音轨以服务器转码设置为准）；
- 部分 10-bit / 少见编码片源本机硬解不支持时自动降级 libmpv 软解，功耗与发热更高；Hi10P 之外也可能触发；
- 本地媒体库受 Android 存储限制：无法选择 `Android/data`、`Android/obb`、其它应用私有目录；
- TV（遥控器 / D-pad）界面未适配，1.0 面向手机与平板；
- 仅做 GitHub Releases 分发，暂无国内商店 / Google Play 渠道。

## 反馈

问题与建议请开 [GitHub Issues](https://github.com/zhangwenkang-github/Aurorama/issues)（附设备型号、Android 版本与复现步骤）。
