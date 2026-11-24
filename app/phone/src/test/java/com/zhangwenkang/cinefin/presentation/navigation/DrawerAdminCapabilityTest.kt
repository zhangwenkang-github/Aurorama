package com.zhangwenkang.cinefin.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W75 #2 / 2.0.1 T02：侧栏「控制台 / 资料管理」的管理员门控值。
 *
 * 缓存值只在「缓存里的账号 id == 当前账号 id」时作数；否则一律按非管理员（不显示后台入口）。 [drawerAdministratorFlag]
 * 额外允许「同账号上次已确认」兜底查询失败——查询结果本身不可区分「失败」与「非管理员」。
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

    @Test
    fun failedQueryKeepsConfirmedAdminEntry() {
        // 服务器短暂不可达 / 冷启动身份尚未发布：查询折算成 false，但同账号上次已确认是管理员，入口不能消失。
        assertTrue(
            drawerAdministratorFlag(
                queried = false,
                currentUserId = "user-a",
                cachedUserId = "user-a",
                cachedValue = true,
            )
        )
    }

    @Test
    fun confirmedQueryWinsInBothDirections() {
        assertTrue(
            drawerAdministratorFlag(
                queried = true,
                currentUserId = "user-a",
                cachedUserId = "user-a",
                cachedValue = false,
            )
        )
        // 服务器明确改判为非管理员：查询成功会先写缓存 false，入口必须隐藏。
        assertFalse(
            drawerAdministratorFlag(
                queried = false,
                currentUserId = "user-a",
                cachedUserId = "user-a",
                cachedValue = false,
            )
        )
    }

    @Test
    fun failedQueryNeverLeaksAnotherAccountsAdminFlag() {
        assertFalse(
            drawerAdministratorFlag(
                queried = false,
                currentUserId = "user-normal",
                cachedUserId = "user-admin",
                cachedValue = true,
            )
        )
        assertFalse(
            drawerAdministratorFlag(
                queried = false,
                currentUserId = null,
                cachedUserId = "user-admin",
                cachedValue = true,
            )
        )
        assertFalse(
            drawerAdministratorFlag(
                queried = false,
                currentUserId = "user-admin",
                cachedUserId = null,
                cachedValue = true,
            )
        )
    }
}
