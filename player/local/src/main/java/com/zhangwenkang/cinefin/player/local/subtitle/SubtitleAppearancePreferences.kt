package com.zhangwenkang.cinefin.player.local.subtitle

import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleAppearance
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleLayout
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * 字幕外观偏好的唯一读写入口（主 / 次 / 共享布局）。
 *
 * 旧版本只有一套共享键；首次读取时把旧值一次性复制到主、次两套新键：
 * - 主字幕沿用旧字号 / 颜色 / 背景 / 描边；
 * - 次字幕沿用旧字号的 0.9 倍观感（吸附到 5% 步进），颜色 / 背景 / 描边与主字幕一致；
 * - 旧位置档位映射成共享布局的底部百分比；旧间距固定为 4dp；
 * - 旧键保留不删，迁移完成后只认新键，避免之后的修改被旧值覆盖。
 */
object SubtitleAppearancePreferences {

    private val LEGACY_SIZE_PERCENTS = listOf(75, 100, 125, 150, 200)

    private val LEGACY_COLORS =
        listOf(
            0xFFFFFFFF.toInt(),
            0xFFFFE082.toInt(),
            0xFF80DEEA.toInt(),
            0xFFA5D6A7.toInt(),
            0xFFFFAB91.toInt(),
        )

    /** 旧背景档位的不透明度百分比：无 / 轻纱 0x66 / 半透明 0xB3 / 实底 0xE6 */
    private val LEGACY_BACKGROUND_OPACITY_PERCENTS = listOf(0, 40, 70, 90)

    private val LEGACY_EDGE_WIDTHS = listOf(0, 2, 4)

    /** 旧位置档位：0.03 / 0.08 / 0.15 / 0.25 / 0.35 → 0~30% 的上限内 */
    private val LEGACY_BOTTOM_PERCENTS = listOf(3, 8, 15, 25, 30)

    fun read(appPreferences: AppPreferences): SubtitleAppearance {
        migrateIfNeeded(appPreferences)
        return SubtitleAppearance(
            primary =
                readStyle(
                    appPreferences = appPreferences,
                    sizeKey = appPreferences.playerSubtitlePrimarySizePercent,
                    colorKey = appPreferences.playerSubtitlePrimaryColor,
                    backgroundKey = appPreferences.playerSubtitlePrimaryBackgroundColor,
                    opacityKey = appPreferences.playerSubtitlePrimaryBackgroundOpacity,
                    edgeKey = appPreferences.playerSubtitlePrimaryEdgeDp,
                ),
            secondary =
                readStyle(
                    appPreferences = appPreferences,
                    sizeKey = appPreferences.playerSubtitleSecondarySizePercent,
                    colorKey = appPreferences.playerSubtitleSecondaryColor,
                    backgroundKey = appPreferences.playerSubtitleSecondaryBackgroundColor,
                    opacityKey = appPreferences.playerSubtitleSecondaryBackgroundOpacity,
                    edgeKey = appPreferences.playerSubtitleSecondaryEdgeDp,
                ),
            layout =
                SubtitleLayout(
                    gapDp =
                        appPreferences
                            .getValue(appPreferences.playerSubtitleGapDp)
                            .coerceIn(SubtitleLayout.MIN_GAP_DP, SubtitleLayout.MAX_GAP_DP),
                    bottomPercent =
                        (appPreferences.getValue(appPreferences.playerSubtitleBottomPermille) / 10)
                            .coerceIn(
                                SubtitleLayout.MIN_BOTTOM_PERCENT,
                                SubtitleLayout.MAX_BOTTOM_PERCENT,
                            ),
                ),
        )
    }

    fun write(
        appPreferences: AppPreferences,
        appearance: SubtitleAppearance,
    ) {
        val primary = sanitize(appearance.primary)
        val secondary = sanitize(appearance.secondary)
        val layout =
            SubtitleLayout(
                gapDp =
                    appearance.layout.gapDp.coerceIn(
                        SubtitleLayout.MIN_GAP_DP,
                        SubtitleLayout.MAX_GAP_DP,
                    ),
                bottomPercent =
                    appearance.layout.bottomPercent.coerceIn(
                        SubtitleLayout.MIN_BOTTOM_PERCENT,
                        SubtitleLayout.MAX_BOTTOM_PERCENT,
                    ),
            )

        appPreferences.setValue(
            appPreferences.playerSubtitlePrimarySizePercent,
            primary.sizePercent,
        )
        appPreferences.setValue(appPreferences.playerSubtitlePrimaryColor, primary.textColor)
        appPreferences.setValue(
            appPreferences.playerSubtitlePrimaryBackgroundColor,
            primary.backgroundColor,
        )
        appPreferences.setValue(
            appPreferences.playerSubtitlePrimaryBackgroundOpacity,
            primary.backgroundOpacityPercent,
        )
        appPreferences.setValue(appPreferences.playerSubtitlePrimaryEdgeDp, primary.edgeWidthDp)

        appPreferences.setValue(
            appPreferences.playerSubtitleSecondarySizePercent,
            secondary.sizePercent,
        )
        appPreferences.setValue(appPreferences.playerSubtitleSecondaryColor, secondary.textColor)
        appPreferences.setValue(
            appPreferences.playerSubtitleSecondaryBackgroundColor,
            secondary.backgroundColor,
        )
        appPreferences.setValue(
            appPreferences.playerSubtitleSecondaryBackgroundOpacity,
            secondary.backgroundOpacityPercent,
        )
        appPreferences.setValue(appPreferences.playerSubtitleSecondaryEdgeDp, secondary.edgeWidthDp)

        appPreferences.setValue(appPreferences.playerSubtitleGapDp, layout.gapDp)
        appPreferences.setValue(
            appPreferences.playerSubtitleBottomPermille,
            layout.bottomPercent * 10,
        )
    }

    private fun migrateIfNeeded(appPreferences: AppPreferences) {
        if (appPreferences.getValue(appPreferences.playerSubtitleAppearanceMigratedV2)) return

        val legacySizeIndex =
            appPreferences
                .getValue(appPreferences.playerSubtitleStyleSize)
                .coerceIn(LEGACY_SIZE_PERCENTS.indices)
        val legacyColorIndex =
            appPreferences
                .getValue(appPreferences.playerSubtitleStyleColor)
                .coerceIn(LEGACY_COLORS.indices)
        val legacyBackgroundIndex =
            appPreferences
                .getValue(appPreferences.playerSubtitleStyleBackground)
                .coerceIn(LEGACY_BACKGROUND_OPACITY_PERCENTS.indices)
        val legacyEdgeIndex =
            appPreferences
                .getValue(appPreferences.playerSubtitleStyleEdge)
                .coerceIn(LEGACY_EDGE_WIDTHS.indices)
        val legacyPositionIndex =
            appPreferences
                .getValue(appPreferences.playerSubtitleStylePosition)
                .coerceIn(LEGACY_BOTTOM_PERCENTS.indices)

        val primaryPercent = LEGACY_SIZE_PERCENTS[legacySizeIndex]
        val migratedStyle =
            SubtitleStyle(
                sizePercent = primaryPercent,
                textColor = LEGACY_COLORS[legacyColorIndex],
                backgroundColor = SubtitleStyle.DEFAULT_BACKGROUND_COLOR,
                backgroundOpacityPercent =
                    LEGACY_BACKGROUND_OPACITY_PERCENTS[legacyBackgroundIndex],
                edgeWidthDp = LEGACY_EDGE_WIDTHS[legacyEdgeIndex],
            )
        val appearance =
            SubtitleAppearance(
                primary = migratedStyle,
                secondary =
                    migratedStyle.copy(
                        sizePercent =
                            SubtitleStyle.snapSizePercent((primaryPercent * 0.9f).roundToInt())
                    ),
                layout =
                    SubtitleLayout(bottomPercent = LEGACY_BOTTOM_PERCENTS[legacyPositionIndex]),
            )
        write(appPreferences, appearance)
        appPreferences.setValue(appPreferences.playerSubtitleAppearanceMigratedV2, true)
        Timber.i(
            "字幕外观旧键迁移完成：主=%d%%/%d%% 次=%d%%/%d%% 底部=%d%% 间距=%ddp",
            appearance.primary.sizePercent,
            appearance.primary.backgroundOpacityPercent,
            appearance.secondary.sizePercent,
            appearance.secondary.backgroundOpacityPercent,
            appearance.layout.bottomPercent,
            appearance.layout.gapDp,
        )
    }

    private fun readStyle(
        appPreferences: AppPreferences,
        sizeKey: Preference<Int>,
        colorKey: Preference<Int>,
        backgroundKey: Preference<Int>,
        opacityKey: Preference<Int>,
        edgeKey: Preference<Int>,
    ): SubtitleStyle =
        SubtitleStyle(
            sizePercent = SubtitleStyle.snapSizePercent(appPreferences.getValue(sizeKey)),
            textColor = appPreferences.getValue(colorKey),
            backgroundColor = appPreferences.getValue(backgroundKey),
            backgroundOpacityPercent =
                appPreferences
                    .getValue(opacityKey)
                    .coerceIn(
                        SubtitleStyle.MIN_BACKGROUND_OPACITY_PERCENT,
                        SubtitleStyle.MAX_BACKGROUND_OPACITY_PERCENT,
                    ),
            edgeWidthDp =
                appPreferences
                    .getValue(edgeKey)
                    .coerceIn(
                        SubtitleStyle.MIN_EDGE_WIDTH_DP,
                        SubtitleStyle.MAX_EDGE_WIDTH_DP,
                    ),
        )

    private fun sanitize(style: SubtitleStyle): SubtitleStyle =
        style.copy(
            sizePercent = SubtitleStyle.snapSizePercent(style.sizePercent),
            backgroundOpacityPercent =
                style.backgroundOpacityPercent.coerceIn(
                    SubtitleStyle.MIN_BACKGROUND_OPACITY_PERCENT,
                    SubtitleStyle.MAX_BACKGROUND_OPACITY_PERCENT,
                ),
            edgeWidthDp =
                style.edgeWidthDp.coerceIn(
                    SubtitleStyle.MIN_EDGE_WIDTH_DP,
                    SubtitleStyle.MAX_EDGE_WIDTH_DP,
                ),
        )
}
