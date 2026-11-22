package com.zhangwenkang.cinefin.repository

import java.util.UUID
import org.jellyfin.sdk.model.api.UserPolicy

/**
 * W8-T2：当前用户的写权限快照（按 (serverId, userId) 键控，见 [UserPolicyStore]）。
 *
 * UI 只做「入口隐藏 / 禁用」的前置处理，最终以服务器校验为准（V2-WRITE-04/12）。[policy] 为 null 表示未拉取 / 拉取失败 / 身份已切换但尚未刷新——一律
 * fail-closed（全部不可写）。
 */
data class UserPolicyAccess(
    val serverId: String?,
    val userId: String?,
    val policy: UserPolicy?,
) {
    val isLoaded: Boolean
        get() = policy != null

    val isAdministrator: Boolean
        get() = policy?.isAdministrator == true

    fun canDeleteContent(libraryId: UUID?): Boolean = canDeleteContent(policy, libraryId)

    fun canEditMetadata(): Boolean = canEditMetadata(policy)

    internal fun matches(key: UserPolicyKey): Boolean =
        serverId == key.serverId && userId == key.userId

    companion object {
        fun unknown(serverId: String?, userId: String?) =
            UserPolicyAccess(serverId = serverId, userId = userId, policy = null)
    }
}

/** 权限缓存的键：换服 / 换账号后旧快照必须立刻失效，不得串号（V2-WRITE-11）。 */
data class UserPolicyKey(val serverId: String?, val userId: String?) {
    val isResolved: Boolean
        get() = !serverId.isNullOrBlank() && !userId.isNullOrBlank()
}

/**
 * 能否删除服务器条目（`DELETE /Items/{id}`）。
 *
 * 依据（V2-WRITE-02）：服务器在 `LibraryController.DeleteItem` 里用 `item.CanDelete(user)` 校验，权限字段为
 * `EnableContentDeletion`（全库）与 `EnableContentDeletionFromFolders`（库 id 白名单）——测试服 `zhangwenkang` 正是
 * `EnableContentDeletion=false` + 白名单仅「其他」库。管理员按 Jellyfin 管理端全权处理（管理端入口由 `RequiresElevation` 覆盖、测试服
 * admin 的 `EnableContentDeletion=true`）；App 侧只提前隐藏入口，删除仍由服务器裁决。
 *
 * [libraryId] 为条目所属媒体库 id（`/Users/{id}/Views` 的 Id，与 `EnableContentDeletionFromFolders` 同一 id 空间）。
 * 未知库 id 时只保留全局许可；policy 为 null 一律 false（fail-closed）。
 */
fun canDeleteContent(policy: UserPolicy?, libraryId: UUID?): Boolean {
    if (policy == null) return false
    if (policy.isAdministrator || policy.enableContentDeletion) return true
    val folderId = libraryId?.toString() ?: return false
    val allowedFolders = policy.enableContentDeletionFromFolders ?: return false
    return allowedFolders.any { it.equals(folderId, ignoreCase = true) }
}

/**
 * 能否编辑条目元数据（`POST /Items/{id}`）。
 *
 * 依据（V2-WRITE-02 实测）：Jellyfin 12.4.0 的 `ItemUpdateController` 整体标注 `[Authorize(Policy =
 * Policies.RequiresElevation)]`，即管理员专属；policy 里没有独立开关。测试服实测： 非管理员 `zhangwenkang` 对 6 个库各 1 条原值回写全部
 * 403（含获删权库），`admin` 全部 204。 policy 为 null 一律 false（fail-closed）。
 */
fun canEditMetadata(policy: UserPolicy?): Boolean = policy?.isAdministrator == true
