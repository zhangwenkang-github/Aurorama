package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors

/**
 * 临时库视图的「返回默认 ×」胶囊（W53 追加）：一键回到该模式页的默认库。
 *
 * 与顶栏同一排使用（`CinefinPageTopBar(actions = { … })`）；配色只用当前皮肤语义 token （Prism / Lumen /
 * 阅读器纸色都能自动跟随），不新增色值。
 */
@Composable
fun CinefinBackToDefaultChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            modifier
                .clip(CinefinShapes.Full)
                .background(colors.surfaceContainerHigh)
                .border(1.dp, colors.outline, CinefinShapes.Full)
                .cinefinClickable(onClick = onClick)
                .defaultMinSize(minHeight = 36.dp)
                .padding(
                    horizontal = CinefinSpacing.Space3,
                    vertical = CinefinSpacing.Space1,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.temp_library_back_to_default),
            style = CinefinType.BodySmall,
            color = colors.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(CinefinSpacing.Space1))
        Icon(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}
