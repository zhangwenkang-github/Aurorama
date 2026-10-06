package com.zhangwenkang.cinefin.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W75 #2：侧栏「控制台 / 资料管理」的管理员门控值。
 *
 * 缓存值只在「缓存里的账号 id == 当前账号 id」时作数；否则一律按非管理员（不显示后台入口）。
 */
class DrawerAdminCapabilityTest {

    @Test
    fun cachedAdminOfSameUserShowsEntries() {
        assertTrue(
            sidebarAdministratorFlag(
                currentUserId = "user-a",
                cachedUserId = "user-a",
                cachedValue = true,
            )
        )
    }

    @Test
    fun cachedNormalUserStaysHidden() {
        assertFalse(
            sidebarAdministratorFlag(
                currentUserId = "user-b",
                cachedUserId = "user-b",
                cachedValue = false,
            )
        )
    }

    @Test
    fun adminFlagOfAnotherUserDoesNotLeak() {
        // 切到普通账号后，上一个账号（管理员）的缓存必须立刻失效。
        assertFalse(
            sidebarAdministratorFlag(
                currentUserId = "user-normal",
                cachedUserId = "user-admin",
                cachedValue = true,
            )
        )
    }

    @Test
    fun unknownUserOrMissingCacheHidesEntries() {
        assertFalse(
            sidebarAdministratorFlag(
                currentUserId = null,
                cachedUserId = "user-admin",
                cachedValue = true,
            )
        )
        assertFalse(
            sidebarAdministratorFlag(
                currentUserId = "user-admin",
                cachedUserId = null,
                cachedValue = true,
            )
        )
        assertFalse(
            sidebarAdministratorFlag(
                currentUserId = "user-admin",
                cachedUserId = "",
                cachedValue = true,
            )
        )
    }
}
