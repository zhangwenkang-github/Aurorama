package com.zhangwenkang.cinefin.film.presentation.detail

/**
 * W8-T5：详情页「删除服务器文件」入口门控（纯逻辑，便于单测）。
 *
 * 依据是服务端按当前用户 policy 计算的条目级 `canDelete`（Jellyfin 官方 web 客户端的同一口径）； 离线模式下没有服务器可删，入口不渲染。
 */
object DetailDeleteRules {
    fun shouldShowDeleteAction(canDelete: Boolean, offlineMode: Boolean): Boolean =
        canDelete && !offlineMode
}
