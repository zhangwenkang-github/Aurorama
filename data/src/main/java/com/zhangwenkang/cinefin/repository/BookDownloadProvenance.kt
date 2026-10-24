package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord
import com.zhangwenkang.cinefin.utils.DownloadMediaSidecar
import java.util.UUID

/** 入队 / 开始下载时冻结；下载途中切号不得改写。 */
internal data class BookDownloadOrigin(
    val serverId: String?,
    val userId: UUID?,
    val itemId: UUID,
    val displayName: String?,
)

/**
 * 仅在 `.book` 已完成原子 rename 后调用。
 *
 * 缺 server/user 时不猜测、不写 sidecar；已有旧书不会进入此路径，因此不会被当前会话回填。
 */
internal fun recordBookDownloadProvenance(
    sidecar: DownloadMediaSidecar,
    origin: BookDownloadOrigin,
): Boolean {
    val serverId = origin.serverId?.takeIf { it.isNotBlank() } ?: return false
    val userId = origin.userId ?: return false
    sidecar.replaceBook(
        DownloadMediaRecord(
            itemId = origin.itemId.toString(),
            kind = DownloadMediaKind.BOOK,
            serverId = serverId,
            userId = userId.toString(),
            sourceId = null,
            displayName =
                origin.displayName?.takeIf { it.isNotBlank() } ?: origin.itemId.toString(),
        )
    )
    return true
}

/** 失败 / 取消只清掉该 item 的 BOOK 记录，不能留下看似完成的 provenance。 */
internal fun clearBookDownloadProvenance(sidecar: DownloadMediaSidecar, itemId: UUID) {
    sidecar.removeBook(itemId)
}
