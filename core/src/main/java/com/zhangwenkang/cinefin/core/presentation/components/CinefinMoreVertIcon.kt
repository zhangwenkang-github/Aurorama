package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R

/** 界面图标光学尺寸：与 K1 图标体系的 24dp 栅格一致（2.0.3 D Bug 6）。 */
private val CinefinIconOpticalSize = 24.dp

/**
 * 全局「⋮ 更多」图标（2.0.3 D Bug 6）。
 *
 * 此前各页用 `Text("⋮", CinefinType.TitleMedium)`（17sp）文本字形当图标，视觉上比 24dp 矢量图标小一档； 这里统一换成
 * [R.drawable.ic_more_vert] 的 24dp 出图。语义走 `contentDescription`，调用点不再包
 * `clearAndSetSemantics`；触控面（≥40dp）由外层 `CinefinIconButton`（44dp）或调用方容器保证。
 */
@Composable
fun CinefinMoreVertIcon(
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(R.drawable.ic_more_vert),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(CinefinIconOpticalSize),
    )
}
