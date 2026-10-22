package com.zhangwenkang.cinefin.player.core.domain.models

import android.os.Parcelable
import java.util.UUID
import kotlinx.parcelize.Parcelize

/** 播放源快照与内核快照的稳定身份；同 item 的不同 media source 也必须可区分。 */
@Parcelize
data class PlaybackMediaIdentity(
    val itemId: UUID,
    val mediaSourceId: String,
) : Parcelable
