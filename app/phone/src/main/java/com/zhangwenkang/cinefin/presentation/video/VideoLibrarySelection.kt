package com.zhangwenkang.cinefin.presentation.video

import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.presentation.utils.parseStoredLibraryId
import com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode
import java.util.UUID

/**
 * 视频页「显示哪个库」的解析结果（W54-C，纯逻辑便于单测）：
 *
 * - [selectedId]：落到实处的选择（服务器上找不到该库时为 null，即「全部库」）；
 * - [visible]：库卡网格与聚合流实际使用的库列表（未选择 = 全部视频库）。
 */
internal data class VideoLibrarySelection(
    val selectedId: UUID?,
    val visible: List<FindroidCollection>,
)

/**
 * 解析持久化的库选择：库 id 仍在视频库列表里 → 只显示该库；否则回落「全部库」。
 *
 * 用户口径（2026-10-03）：库卡模式 = 过滤显示哪些库卡；聚合模式 = 只显示所选库内容，或「全部库」。
 */
internal fun resolveVideoLibrarySelection(
    libraries: List<FindroidCollection>,
    storedId: String?,
): VideoLibrarySelection {
    val target = libraries.firstOrNull { it.id == parseStoredLibraryId(storedId) }
    return if (target == null) {
        VideoLibrarySelection(selectedId = null, visible = libraries)
    } else {
        VideoLibrarySelection(selectedId = target.id, visible = listOf(target))
    }
}

/**
 * D-F7（用户 2026-10-06 拍板 B）：视频页「库选择」按显示方式分流。
 *
 * - **库卡列表**：选中具体库 = 直达该库内容（由页面回调导航，不落偏好），页面本身就是「全部库」卡片 总览——因此历史偏好不再过滤库卡（覆盖 D57 的「选中具体库 =
 *   只过滤库卡」语义，「全部库」永远回到总览）；
 * - **聚合列表**：沿用 W54-C 口径——只合并所选库的条目，或「全部库」的全部条目。
 */
internal fun resolveVideoLibrariesForMode(
    displayMode: VideoDisplayMode,
    libraries: List<FindroidCollection>,
    storedId: String?,
): VideoLibrarySelection =
    when (displayMode) {
        VideoDisplayMode.Aggregated -> resolveVideoLibrarySelection(libraries, storedId)
        VideoDisplayMode.Cards -> VideoLibrarySelection(selectedId = null, visible = libraries)
    }
