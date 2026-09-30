package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * 空状态（§8.10）：垂直居中、上下留白 64dp、内容最大宽 480dp。
 *
 * 图标 44dp 单色描边（当前域 `Media.Bright`）；标题 HeadlineSmall `OnSurface`；说明 BodyMedium `OnSurfaceVariant`（≤2
 * 行，表述「发生了什么 + 下一步」）；主行动 Filled、次行动 Text，由调用方以槽位传入。
 */
@Composable
fun CinefinEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: (@Composable (tint: Color) -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    secondaryAction: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = CinefinSpacing.Space16)
                .widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Column(
                modifier = Modifier.size(44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                icon(media.bright)
            }
            Spacer(Modifier.height(CinefinSpacing.Space5))
        }
        Text(
            text = title,
            style = CinefinType.HeadlineSmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(CinefinSpacing.Space3))
            Text(
                text = message,
                style = CinefinType.BodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
        if (action != null || secondaryAction != null) {
            Spacer(Modifier.height(CinefinSpacing.Space6))
            Row(
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                action?.invoke()
                secondaryAction?.invoke()
            }
        }
    }
}
