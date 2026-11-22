package com.zhangwenkang.cinefin.film.presentation.detail

/**
 * W8-T6：详情页「编辑元数据」入口门控（纯逻辑，便于单测）。
 *
 * 依据是当前账号 policy 的 `isAdministrator`（Jellyfin 的 `ItemUpdateController` 整体要求 elevation，见
 * `canEditMetadata`）；离线模式下没有服务器可写，入口不渲染。
 */
object DetailEditRules {
    fun shouldShowEditAction(canEditMetadata: Boolean, offlineMode: Boolean): Boolean =
        canEditMetadata && !offlineMode
}
