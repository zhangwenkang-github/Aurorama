package com.zhangwenkang.cinefin.presentation.selection

import android.content.Context
import android.content.Intent
import com.zhangwenkang.cinefin.EXTRA_QUEUE_ITEM_IDS
import com.zhangwenkang.cinefin.EXTRA_QUEUE_ITEM_KINDS
import com.zhangwenkang.cinefin.PlayerActivity
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

/** W58b 视频批量播放：已解析成可播放条目的队列（电影 / 单集），保持当前列表序。 */
data class VideoPlaybackEntry(val itemId: UUID, val kind: BaseItemKind)

/**
 * 起播批量队列（W58b）：首个条目作为起播项，其余条目由播放页按序补进播放队列。
 *
 * 队列条目一律是**具体可播放条目**（电影 / 单集）——剧集 / 季在选择侧已解析成下一集 / 第一集， 因此播放页回退重启（Intent 里换的是实际播放条目）也能在队列里找到同一项。
 */
internal fun Context.startVideoQueuePlayback(entries: List<VideoPlaybackEntry>) {
    if (entries.isEmpty()) return
    val intent =
        Intent(this, PlayerActivity::class.java).apply {
            putExtra("itemId", entries.first().itemId.toString())
            putExtra("itemKind", entries.first().kind.serialName)
            putStringArrayListExtra(
                EXTRA_QUEUE_ITEM_IDS,
                ArrayList(entries.map { it.itemId.toString() }),
            )
            putStringArrayListExtra(
                EXTRA_QUEUE_ITEM_KINDS,
                ArrayList(entries.map { it.kind.serialName }),
            )
        }
    startActivity(intent)
}
