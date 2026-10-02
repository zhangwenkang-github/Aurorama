package com.zhangwenkang.cinefin.setup.domain

import com.zhangwenkang.cinefin.models.User
import java.util.UUID

/**
 * 删除某个用户后，服务器当前用户应该指向谁：
 *
 * - 删的不是当前用户 → 保持不变；
 * - 删的是当前用户 → 取剩余用户中的第一个继续登录（令牌 / 用户数据随行切换）；没有剩余用户则清空（返回 null）。
 *
 * 旧实现允许删除当前用户但不更新 `servers.currentUserId`，会留下指向已删除行的悬空 id； 下次启动时 `getServerWithAddressAndUser`
 * 解析不到用户，App 会以「无令牌」状态卡死。
 */
fun nextCurrentUserIdAfterDelete(
    remainingUsers: List<User>,
    deletedUserId: UUID,
    currentUserId: UUID?,
): UUID? =
    when {
        currentUserId == null || currentUserId != deletedUserId -> currentUserId
        else -> remainingUsers.firstOrNull { it.id != deletedUserId }?.id
    }
