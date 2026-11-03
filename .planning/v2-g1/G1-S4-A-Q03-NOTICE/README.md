# G1-S4-A-Q03-NOTICE

状态：`MEDIA_NOTICE_PARTIAL / Q03_OPEN / G1_NOT_PASSED`。

本目录只冻结媒体 native notice / source-offer 的静态证据与待补文件清单，不是最终发布许可批准。

阅读顺序：

1. `report.json`
2. `provenance-matrix.json`
3. `notice-source-offer-matrix.json`
4. `bundle-file-list.json`
5. `manifest.json`
6. `commands.log`

核心边界：

- Media3 1.11.1 wrapper 与 Jellyfin FFmpeg decoder 1.9.0+1 wrapper / FFmpeg 6.0.2 native 分账。
- libmpv 1.0.0 wrapper MIT 不覆盖其内嵌 mpv / FFmpeg / libass。
- 实际 mpv 构建未传 `-Dgpl=false`，按源码定义是 GPL-2.0+，旧“libmpv 即 LGPL”字符串结论已收窄。
- 未生成最终 APK/AAB、完整第三方 notice、对应源码包或 written offer；不得宣称发布闭环。
