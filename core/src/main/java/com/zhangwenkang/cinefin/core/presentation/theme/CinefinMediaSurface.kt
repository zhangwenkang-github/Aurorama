package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 复杂背景可读性（2.0.1 定稿 §3 T10 / AUDIT-15）：hero 与封面图上的图标、文字、控件垫层。
 *
 * 适用边界：**只用于压在剧照 / 海报上的内容**——普通页面容器、列表行、面板一律走表面系 token，不要用这里的两个 token 造"浮层感"。
 */

/**
 * `scrimBehindControl`：黑 32% 的控件垫层（[CinefinColors.scrimBehindControl]）。
 *
 * 用在 hero / 封面图上的控件底（图标键、状态点、角标），保证亮剧照上仍能读清；普通页面容器禁止使用。
 */
fun Modifier.scrimBehindControl(
    colors: CinefinColors,
    shape: Shape = RectangleShape,
): Modifier = background(colors.scrimBehindControl, shape)

/**
 * `mediaContentShadow`：图上内容的控件级投影（黑 60%、最轻 elevation）。
 *
 * 文字请用 [mediaContentShadow] 的 TextStyle 重载（CSS `0 1px 4px` 的窄描边）；本 Modifier 用于控件垫层 / 小图形（圆点、角标底）。
 * 阴影以节点轮廓为形，不跟随图标笔画，故图标本体优先通过 [TextStyle] 或调用处 tint 保证对比，不要指望本 Modifier 描边。
 */
fun Modifier.mediaContentShadow(
    colors: CinefinColors,
    shape: Shape = RectangleShape,
    elevation: Dp = CinefinTokens.MediaContentShadowElevationDp.dp,
): Modifier =
    shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = colors.mediaContentShadow,
        spotColor = colors.mediaContentShadow,
    )

/**
 * `mediaContentShadow`（文字）：`0 1px 4px rgba(0,0,0,.6)` 的窄描边投影。
 *
 * 只用于图上文字；普通正文不要加投影。偏移 / 模糊半径按定稿的 CSS px 直取（不随 density 放大），保持"窄描边"观感。
 */
@Composable
fun TextStyle.mediaContentShadow(colors: CinefinColors): TextStyle =
    copy(
        shadow =
            Shadow(
                color = colors.mediaContentShadow,
                offset = Offset(0f, CinefinTokens.MediaContentShadowOffsetYPx),
                blurRadius = CinefinTokens.MediaContentShadowBlurRadiusPx,
            )
    )
