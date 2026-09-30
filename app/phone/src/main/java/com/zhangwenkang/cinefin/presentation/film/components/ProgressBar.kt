package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

@Composable
fun ProgressBar(item: FindroidItem, width: Int, modifier: Modifier = Modifier) {
    val media = LocalMediaColors.current
    val fraction =
        if (item.runtimeTicks > 0) {
            (item.playbackPositionTicks.toFloat() / item.runtimeTicks.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    Column(modifier = modifier) {
        Box(
            modifier =
                Modifier.height(3.dp)
                    .width((width - 16).coerceAtLeast(0).times(fraction).dp)
                    .clip(CinefinShapes.TwoXs)
                    .background(media.base)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProgressBarPreview() {
    CinefinTheme { ProgressBar(item = dummyMovie, width = 142) }
}
