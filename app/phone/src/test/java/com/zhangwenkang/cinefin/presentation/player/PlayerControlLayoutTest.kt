package com.zhangwenkang.cinefin.presentation.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页控件分布与流光进度条的回归测试（W11 反馈③④⑧ + W12 终版布局）。
 *
 * 三条约束容易在后续会话里被改回去，所以钉成断言：
 * 1. 进度条下方 6 键与右上角 5 键的**顺序**是用户多轮确认的终版布局（改顺序即回归）；
 * 2. 底栏一行（6 键 + 全屏键）在窄窗里放不下时必须能靠横向滚动兜底，键尺寸不能比 W12 更大；
 * 3. 已播段渐变里辅光蓝的占比必须 <10%（A 稿：辅光蓝只做渐变辅助）。
 */
class PlayerControlLayoutTest {

    @Test
    fun bottomKeys_keepFinalOrder() {
        assertEquals(
            "进度条下方左侧 6 键顺序：音轨 · 字幕 · 倍率 · 码率 · 解码 · 详细信息",
            listOf(
                PlayerBottomKey.Audio,
                PlayerBottomKey.Subtitle,
                PlayerBottomKey.Speed,
                PlayerBottomKey.Bitrate,
                PlayerBottomKey.Decode,
                PlayerBottomKey.Info,
            ),
            PLAYER_BOTTOM_KEY_ORDER,
        )
    }

    @Test
    fun topKeys_keepFinalOrderWithAspectBetweenEpisodesAndSettings() {
        assertEquals(
            "右上角 5 键顺序：画中画 · 睡眠 · 选集 · 画面 · 设置（画面在选集与设置之间）",
            listOf(
                PlayerTopKey.Pip,
                PlayerTopKey.Sleep,
                PlayerTopKey.Episode,
                PlayerTopKey.Aspect,
                PlayerTopKey.Settings,
            ),
            PLAYER_TOP_KEY_ORDER,
        )
    }

    @Test
    fun bottomKeys_infoIsLastSoSpeedLabelSitsOnItsRight() {
        // W13 反馈③：1× 徽标渲染在工具行尾部 → 只有「详细信息」是键表最后一个，它才会落在详细信息右侧
        assertEquals(
            "「详细信息」必须是左下键表最后一个（1× 徽标紧跟其后）",
            PlayerBottomKey.Info,
            PLAYER_BOTTOM_KEY_ORDER.last(),
        )
    }

    @Test
    fun toolRow_fullscreenShowsLabels() {
        assertTrue(
            "全屏时工具键加小字（验收⑤）",
            playerToolLabelsVisible(
                isFullscreen = true,
                widthDp = 411f,
                formFactor = PlayerFormFactor.Phone,
            ),
        )
        assertEquals("左下 6 键恒定齐全", 6, PLAYER_BOTTOM_KEY_ORDER.size)
    }

    @Test
    fun toolRow_windowedNarrowPhone_hidesLabelsButKeepsAllKeys() {
        val showsLabels =
            playerToolLabelsVisible(
                isFullscreen = false,
                widthDp = 411f,
                formFactor = PlayerFormFactor.Phone,
            )

        assertFalse("非全屏窄窗（手机形态 411dp）只留图标、不加文字（验收⑤）", showsLabels)
        assertEquals(
            "窄屏仍保留全部 6 个入口（码率 / 解码 不再隐藏）",
            listOf(
                PlayerBottomKey.Audio,
                PlayerBottomKey.Subtitle,
                PlayerBottomKey.Speed,
                PlayerBottomKey.Bitrate,
                PlayerBottomKey.Decode,
                PlayerBottomKey.Info,
            ),
            PLAYER_BOTTOM_KEY_ORDER,
        )
    }

    @Test
    fun toolRow_wideNonFullscreenWindow_showsLabels() {
        // Pad 5 平板横屏 ≈1280dp：即使没点全屏（侧栏展开态）也要加文字
        assertTrue(
            "平板非全屏但宽度充足时加文字（验收⑤）",
            playerToolLabelsVisible(
                isFullscreen = false,
                widthDp = 1280f,
                formFactor = PlayerFormFactor.Tablet,
            ),
        )
        // 手机形态但窗口足够宽（横屏 / 宽窗口）：走宽度档位兜底，同样全显
        assertTrue(
            "宽度档位兜底：≥600dp 的非全屏窗口也加文字",
            playerToolLabelsVisible(
                isFullscreen = false,
                widthDp = 914f,
                formFactor = PlayerFormFactor.Phone,
            ),
        )
    }

    @Test
    fun toolRow_gradingThreshold_isSixHundredDp() {
        assertFalse(
            "599.9dp 仍按非全屏窄窗处理",
            playerToolLabelsVisible(false, 599.9f, PlayerFormFactor.Phone),
        )
        assertTrue(
            "600dp（playerControlSpec / PlayerFormFactor 的同一档位线）起加文字",
            playerToolLabelsVisible(false, 600f, PlayerFormFactor.Phone),
        )
    }

    @Test
    fun toolRow_compactNeverShowsLabels() {
        assertFalse(
            "Compact（自由窗口 / 分屏窄宽）无论宽度一律只留图标（验收⑤）",
            playerToolLabelsVisible(
                isFullscreen = false,
                widthDp = 1280f,
                formFactor = PlayerFormFactor.Freeform,
                isCompact = true,
            ),
        )
    }

    @Test
    fun wideLayout_keepsW12KeySizes() {
        val spec = playerControlSpec(800f)

        assertFalse("宽屏不收尺寸", spec.narrow)
        assertEquals("键框只比图标大一圈（W12 反馈 A）", 44f, spec.toolKeySizeDp, 0.001f)
        assertEquals("键内图标 24dp 网格", 24f, spec.iconSizeDp, 0.001f)
    }

    @Test
    fun narrowLayout_shrinksKeysAndKeepsIconSameWidthAsSpeedKey() {
        // 411dp ≈ Pad 5 手机形态 / K60 竖屏
        val spec = playerControlSpec(411f)

        assertTrue("窄屏收一档", spec.narrow)
        assertEquals(38f, spec.toolKeySizeDp, 0.001f)
        assertEquals("倍率键图标与其它键同宽（W12 反馈 A）", 22f, spec.iconSizeDp, 0.001f)
        assertTrue("窄屏留白也要收一档", spec.toolRowPaddingDp < 20f)
    }

    @Test
    fun thresholdAtSixHundredDp_switchesToNarrowSpec() {
        assertTrue(playerControlSpec(599.9f).narrow)
        assertFalse(playerControlSpec(600f).narrow)
    }

    @Test
    fun bottomRow_fitsPhoneWidthWithoutScrolling() {
        // 411dp 是最窄的常见手机形态（Pad 5 wm 覆盖 / K60 竖屏）。W17 起窄屏只留图标、不加文字，
        // 但仍按「6 键 + 1× 徽标 + 全屏键」计算，必须一行放得下
        val spec = playerControlSpec(411f)
        val used = spec.bottomRowWidthDp(showLabels = false) + spec.toolRowPaddingDp * 2f

        assertTrue("底栏一行 $used dp 会超出 411dp 手机宽度", used <= 411f)
    }

    @Test
    fun bottomRow_labeledKeysStillFitAtSixHundredDp() {
        // W17：宽屏加文字后键框加宽（44 → 56dp），600dp 阈值档仍要一行放得下
        val spec = playerControlSpec(600f)
        val used = spec.bottomRowWidthDp(showLabels = true) + spec.toolRowPaddingDp * 2f

        assertTrue("加文字后底栏一行 $used dp 会超出 600dp", used <= 600f)
        assertTrue(
            "加文字必须真的变宽（否则说明键框没加宽）",
            spec.bottomRowWidthDp(showLabels = true) > spec.bottomRowWidthDp(showLabels = false),
        )
    }

    @Test
    fun progressGradient_keepsAccentSecondaryUnderTenPercent() {
        val stops = playerProgressGradientStops()

        assertEquals("色标从 0 开始", 0f, stops.first(), 0.0001f)
        assertEquals("色标到 1 结束", 1f, stops.last(), 0.0001f)
        assertStopsSorted(stops)
        // 浮点减法会带误差（1f - 0.9f = 0.100000024），所以直接卡起点位置
        val secondaryStart = stops[stops.size - 2]
        assertTrue("辅光蓝起点必须 ≥0.9（占比 <10%），实际 $secondaryStart", secondaryStart >= 0.9f - 1e-4f)
        assertEquals(PLAYER_PROGRESS_ACCENT_END, secondaryStart, 0.0001f)
    }

    private fun assertStopsSorted(stops: List<Float>) {
        stops.zipWithNext { left, right -> assertTrue("色标必须单调递增：$left -> $right", left < right) }
    }

    @Test
    fun backKey_closesPanelBeforeLeavingPlayer() {
        assertEquals(
            "面板打开时必须先关面板，而不是退出播放",
            PlayerBackAction.ClosePanel,
            resolvePlayerBack(panelOpen = true, hasParentPanel = false),
        )
    }

    @Test
    fun backKey_subPanelGoesBackToParentPanel() {
        assertEquals(
            "子面板先回上一级（与抽屉返回箭头一致）",
            PlayerBackAction.BackToParentPanel,
            resolvePlayerBack(panelOpen = true, hasParentPanel = true),
        )
    }

    @Test
    fun backKey_withoutPanelFallsThroughToSystem() {
        assertEquals(
            "没有面板时返回键交回系统（真正退出播放页）",
            PlayerBackAction.Ignore,
            resolvePlayerBack(panelOpen = false, hasParentPanel = false),
        )
        assertEquals(
            "没有面板时即使残留上一级标记也不拦截",
            PlayerBackAction.Ignore,
            resolvePlayerBack(panelOpen = false, hasParentPanel = true),
        )
    }

    @Test
    fun backKey_closesSidePanelBeforeLeavingPlayer() {
        assertEquals(
            "覆盖层选集栏打开时要先收栏，不能退出播放页（W12 反馈 C）",
            PlayerBackAction.CloseSidePanel,
            resolvePlayerBack(panelOpen = false, hasParentPanel = false, sidePanelOpen = true),
        )
    }

    @Test
    fun backKey_prefersDrawerPanelOverSidePanel() {
        assertEquals(
            "抽屉面板与选集栏同时开着：先关抽屉面板",
            PlayerBackAction.ClosePanel,
            resolvePlayerBack(panelOpen = true, hasParentPanel = false, sidePanelOpen = true),
        )
    }

    @Test
    fun centerCluster_neverOverlapsLockKey() {
        // 锁定键 = 48dp 键 + 右侧 12dp 留白，固定在画面区右缘垂直居中；
        // 中央簇居中排布，因此不重叠条件是：簇宽 ≤ 画面区宽 − 2×(48+12)
        val lockReserve = 2f * (48f + 12f)
        listOf(280f, 305f, 320f, 360f, 411f, 600f, 800f, 1280f).forEach { width ->
            val cluster = playerCenterSpec(width).totalWidthDp
            assertTrue(
                "画面区 $width dp 时中央簇 $cluster dp 会与右缘锁定键重叠",
                cluster <= width - lockReserve,
            )
        }
    }

    @Test
    fun centerCluster_keepsPlayKeyLargest() {
        listOf(280f, 360f, 420f, 800f).forEach { width ->
            val spec = playerCenterSpec(width)
            assertTrue(
                "画面区 $width dp：主播放键必须仍是簇里最大的键",
                spec.playSizeDp > spec.transportSizeDp,
            )
        }
    }

    // ---------- W67b：结束时间进左下工具行后的键集分级 ----------

    @Test
    fun bottomKeys_narrowPhoneWithEndTime_dropsBitrateAndDecode() {
        // K60 竖屏 411dp：6 键 + 1× + 结束时间超预算 → 省「码率 / 解码」，保证结束时间完整
        val spec = playerControlSpec(411f)
        assertEquals(
            "窄窗保留 音轨 · 字幕 · 倍率 · 详细信息 + 1× + 结束时间（顺序不变）",
            listOf(
                PlayerBottomKey.Audio,
                PlayerBottomKey.Subtitle,
                PlayerBottomKey.Speed,
                PlayerBottomKey.Info,
            ),
            playerBottomKeysForWidth(
                widthDp = 411f,
                showLabels = false,
                hasEndTime = true,
                spec = spec,
            ),
        )
    }

    @Test
    fun bottomKeys_narrowPhoneWithoutEndTime_keepsSixKeys() {
        // 时长未知 / 直播：结束时间不占位 → 6 键照旧（W17 行为不回归）
        val spec = playerControlSpec(411f)
        assertEquals(
            PLAYER_BOTTOM_KEY_ORDER,
            playerBottomKeysForWidth(
                widthDp = 411f,
                showLabels = false,
                hasEndTime = false,
                spec = spec,
            ),
        )
    }

    @Test
    fun bottomKeys_wideWindows_keepSixKeys() {
        // K60 全屏横屏（914dp）与 Pad 5 横屏（1137dp）：带小字的 6 键 + 1× + 结束时间仍放得下
        val phoneLandscape = playerControlSpec(914f)
        assertEquals(
            PLAYER_BOTTOM_KEY_ORDER,
            playerBottomKeysForWidth(
                914f,
                showLabels = true,
                hasEndTime = true,
                spec = phoneLandscape,
            ),
        )
        val tablet = playerControlSpec(1137f)
        assertEquals(
            PLAYER_BOTTOM_KEY_ORDER,
            playerBottomKeysForWidth(1137f, showLabels = true, hasEndTime = true, spec = tablet),
        )
    }

    @Test
    fun bottomKeys_tierBoundaryFollowsWidthBudget() {
        val spec = playerControlSpec(600f)
        val budget =
            spec.bottomRowWidthDp(showLabels = true, withEndTime = true) +
                spec.toolRowPaddingDp * 2f
        assertTrue("600dp 档的预算应超过 600dp（否则用例失去意义）", budget > 600f)
        assertEquals(
            "刚好放得下（含端点）时 6 键齐全",
            PLAYER_BOTTOM_KEY_ORDER,
            playerBottomKeysForWidth(budget, showLabels = true, hasEndTime = true, spec = spec),
        )
        assertEquals(
            "差 1dp 就退到 4 键",
            4,
            playerBottomKeysForWidth(budget - 1f, showLabels = true, hasEndTime = true, spec = spec)
                .size,
        )
    }

    // ---------- W67b：竖屏内容区收起后的画面区 ----------

    @Test
    fun bottomContentCollapsed_letsVideoFillTheWindow() {
        val expanded =
            PlayerLayoutContext(
                chrome = PlayerChromeLayout.SplitPortrait,
                windowWidthDp = 411,
                windowHeightDp = 914,
            )
        assertTrue("展开态：竖屏有常驻内容区", expanded.hasBottomContent)
        assertEquals(
            "展开态画面区 = max(16:9, 42% 窗口高)",
            maxOf(411 * 9 / 16, 914 * 42 / 100),
            expanded.videoHeightDp,
        )

        val collapsed = expanded.copy(bottomContentExpanded = false)
        assertFalse("收起态：内容区不占位", collapsed.hasBottomContent)
        assertEquals("收起态画面区铺满整窗", 914, collapsed.videoHeightDp)
    }
}
