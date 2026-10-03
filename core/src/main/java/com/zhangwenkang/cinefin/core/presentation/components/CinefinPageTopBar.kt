package com.zhangwenkang.cinefin.core.presentation.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors

/**
 * 顶层页面共用顶栏（W8-R3 用户反馈 1）：媒体库 / 音乐 / 书架三页共用同一套尺寸与排版。
 *
 * 统一项：**56dp 内容高 + `statusBarsPadding()`**（音乐页原先 72dp 且不带状态栏内边距，顶栏键会被系统状态栏压住，这里根治）、 44dp 图标键（图标
 * 24dp，与首页 `HomeTopBar` 同一语言）、页边距随窗口分级 20 / 24 / 32 / 48dp、**一级页面左侧入口 = `ic_logo` app 图标 +
 * `content-desc="打开侧栏"`**（W46：与首页 / 音乐 / 书架 / 媒体库四页统一；二级页面仍用返回键）、标题排版 = 主标题（`TitleLarge`）+
 * 计数副标题（`BodySmall`）。Lumen 区域在顶栏下缘补一条发丝线（A 稿层次），Prism 区域保持纯净。
 */
@Composable
fun CinefinPageTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** 侧栏入口（一级页面）：app 图标（`ic_logo`）+ 「打开侧栏」；null = 不显示入口（平板形态 / 控制台页）。 */
    onOpenDrawer: (() -> Unit)? = null,
    /** 返回键（二级页面：专辑详情 / 库内容页）：优先于侧栏入口。 */
    onBack: (() -> Unit)? = null,
    /** 右侧页面动作（媒体库搜索、书籍库排序等）。 */
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalCinefinColors.current
    val lumen = LocalLumenColors.current
    val gutter = cinefinPageGutter()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .then(
                    if (lumen != null) {
                        Modifier.drawBehind {
                            val stroke = 1.dp.toPx()
                            drawRect(
                                color = lumen.lineSoft,
                                topLeft = Offset(0f, size.height - stroke),
                                size = Size(size.width, stroke),
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(start = gutter, end = gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            onBack != null ->
                CinefinTopBarIcon(
                    res = R.drawable.ic_arrow_left,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = colors.onSurfaceVariant,
                    onClick = onBack,
                )
            onOpenDrawer != null ->
                CinefinTopBarIcon(
                    res = R.drawable.ic_logo,
                    contentDescription = stringResource(R.string.nav_open_drawer),
                    // 品牌图标自带极光青渐变，不能按次级灰着色。
                    tint = Color.Unspecified,
                    onClick = onOpenDrawer,
                )
            else -> Unit
        }
        Spacer(Modifier.width(CinefinSpacing.Space2))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = CinefinType.TitleLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** 顶栏 44dp 图标键（与首页 `HomeTopBar` 的 `TopBarAction` 同尺寸、同图标 24dp）。 */
@Composable
private fun CinefinTopBarIcon(
    @DrawableRes res: Int,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(44.dp).clip(CinefinShapes.Sm).cinefinClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(res),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * 页面左右边距（与 `app:phone` 的 `rememberPageGutter()` 同一分级）。
 *
 * 放在 core 是为了让 `modes:music` 也能复用同一顶栏——音乐模块只依赖 core，拿不到 app 侧的窗口工具函数， 因此这里用
 * `LocalConfiguration.screenWidthDp` 复刻同一组阈值（Compact 20 / Medium 24 / Expanded 32 / Large 48dp）。
 */
@Composable
private fun cinefinPageGutter(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp >= 1200 -> 48.dp
        widthDp >= 840 -> 32.dp
        widthDp >= 600 -> 24.dp
        else -> 20.dp
    }
}
