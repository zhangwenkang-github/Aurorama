package com.zhangwenkang.cinefin.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W75 #2 / 2.0.1 T02 / T02b：侧栏「控制台 / 资料管理」的管理员门控值。
 *
 * 缓存值只在「缓存里的账号 id == 当前账号 id」时作数；否则一律按非管理员（不显示后台入口）。 [drawerAdministratorFlag]
 * 额外允许「同账号上次已确认」兜底查询失败——查询结果本身不可区分「失败」与「非管理员」。 [drawerAccountNeedsServerConfirmation]
 * 再把「缓存里没有当前账号记录」判成需要补一次服务器确认——否则全新登录 + 首次查询失败会让入口一直隐藏。
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

    @Test
    fun missingCacheRecordNeedsServerConfirmation() {
        // W1-T02b：全新登录的管理员，首次查询就失败——缓存里没有这个账号的任何记录（仍是初始空串）。
        // 此前 refreshAccount 只看「账号是否变过」，同账号 + 无记录会被当成「已确认」走零请求，入口此后再也不出现。
        assertFalse(
            drawerAdministratorFlag(
                queried = false,
                currentUserId = "user-admin",
                cachedUserId = "",
                cachedValue = false,
            )
        )
        assertTrue(
            drawerAccountNeedsServerConfirmation(
                currentUserId = "user-admin",
                resolvedUserId = "user-admin",
                cachedUserId = "",
            )
        )
        // 连别的账号的旧记录都算不上「当前账号的记录」。
        assertTrue(
            drawerAccountNeedsServerConfirmation(
                currentUserId = "user-admin",
                resolvedUserId = "user-admin",
                cachedUserId = "user-other",
            )
        )
        // 账号未知（会话未就绪）：同样要重判，入口保持隐藏。
        assertTrue(
            drawerAccountNeedsServerConfirmation(
                currentUserId = null,
                resolvedUserId = null,
                cachedUserId = "",
            )
        )
        assertFalse(sidebarAdministratorCacheHasRecord(null, ""))
    }

    @Test
    fun recoveredQueryAfterFirstFailureRestoresAdminEntry() {
        val userId = "user-admin"
        // 首次失败且无记录：入口隐藏。
        assertFalse(
            drawerAdministratorFlag(
                queried = false,
                currentUserId = userId,
                cachedUserId = "",
                cachedValue = false,
            )
        )
        // 下一次导航补查成功（仓库确认后写入 userId/true，这是 isCurrentUserAdministrator 的既有约定）：入口出现。
        assertTrue(
            drawerAdministratorFlag(
                queried = true,
                currentUserId = userId,
                cachedUserId = userId,
                cachedValue = true,
            )
        )
        // 之后查询再失败，同账号已确认身份兜底，入口不回退。
        assertTrue(
            drawerAdministratorFlag(
                queried = false,
                currentUserId = userId,
                cachedUserId = userId,
                cachedValue = true,
            )
        )
    }

    @Test
    fun cachedRecordSuppressesRepeatedQueries() {
        // 有记录（管理员或非管理员）都说明服务器已明确表态：导航时零请求，不重查。
        assertFalse(
            drawerAccountNeedsServerConfirmation(
                currentUserId = "user-admin",
                resolvedUserId = "user-admin",
                cachedUserId = "user-admin",
            )
        )
        assertFalse(
            drawerAccountNeedsServerConfirmation(
                currentUserId = "user-normal",
                resolvedUserId = "user-normal",
                cachedUserId = "user-normal",
            )
        )
    }

    @Test
    fun confirmedNonAdminRecordIsNeitherRequeriedNorCleared() {
        // 有记录且为 false = 服务器明确判为非管理员：不触发重查，也不能把入口误清成可见。
        assertFalse(
            drawerAccountNeedsServerConfirmation(
                currentUserId = "user-normal",
                resolvedUserId = "user-normal",
                cachedUserId = "user-normal",
            )
        )
        assertFalse(
            drawerAdministratorFlag(
                queried = false,
                currentUserId = "user-normal",
                cachedUserId = "user-normal",
                cachedValue = false,
            )
        )
    }

    @Test
    fun accountSwitchStillNeedsConfirmationEvenWithRecord() {
        assertTrue(
            drawerAccountNeedsServerConfirmation(
                currentUserId = "user-b",
                resolvedUserId = "user-a",
                cachedUserId = "user-b",
            )
        )
        assertTrue(
            drawerAccountNeedsServerConfirmation(
                currentUserId = "user-b",
                resolvedUserId = "user-a",
                cachedUserId = "user-a",
            )
        )
    }
}
