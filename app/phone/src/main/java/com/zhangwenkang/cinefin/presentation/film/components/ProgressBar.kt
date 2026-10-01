package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/**
 * 图上进度条（§8.8）：宽度跟随卡片，不再依赖调用方传像素宽度。
 *
 * 旧签名要调用方传"卡片宽度 - 内边距"，卡片一旦改成填充栅格列就会算错；现在统一 "轨道铺满 + 填充按比例"，卡片放到任何宽度都成立。
 */
@Composable
fun ProgressBar(item: FindroidItem, modifier: Modifier = Modifier) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val fraction =
        if (item.runtimeTicks > 0) {
            (item.playbackPositionTicks.toFloat() / item.runtimeTicks.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    if (fraction <= 0f) return
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CinefinShapes.TwoXs)
                .background(colors.progressTrackOnImage)
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth(fraction)
                    .height(3.dp)
                    .clip(CinefinShapes.TwoXs)
                    .background(media.base)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProgressBarPreview() {
    CinefinTheme { ProgressBar(item = dummyMovie, modifier = Modifier.fillMaxWidth()) }
}
