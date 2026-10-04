package com.zhangwenkang.cinefin.utils

import java.util.UUID

/**
 * W62：离线书籍条目的**显示名口径**（下载页「已完成 · 书籍」与离线书架共用，纯函数 + 单测）。
 *
 * 两处虽然都读同一份 `files/books` 目录下的 `.book` 文件，但书名来源不同：离线书架（`OfflineMediaRepository` 的书目） 只认下载时落盘的
 * `.title` 侧车，下载页则优先服务器元数据 —— 离线模式 / 服务器条目缺失时下载页退化成 「离线书籍 xxxxxxxx」占位名，看上去就像两处清单不一致（W61 F2 观察）。
 *
 * 统一口径：**侧车（下载时刻的真实书名）优先** → 服务器元数据兜底（在线时仍可用）→ 「离线书籍 <id 前 8 位>」占位。
 * 侧车是离线可读的唯一书名来源，放在最高优先级才能保证两处显示一致。
 */
fun offlineBookDisplayName(
    serverName: String?,
    sidecarTitle: String?,
    itemId: UUID,
): String =
    sidecarTitle?.takeIf { it.isNotBlank() }
        ?: serverName?.takeIf { it.isNotBlank() }
        ?: "离线书籍 ${itemId.toString().take(8)}"
