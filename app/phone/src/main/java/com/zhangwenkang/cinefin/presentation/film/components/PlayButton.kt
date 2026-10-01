package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

@Composable
fun PlayButton(
    item: FindroidItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val runtimeMinutesLeft by
        remember(item.playbackPositionTicks) {
            mutableLongStateOf((item.runtimeTicks - item.playbackPositionTicks) / 600000000)
        }

    CinefinButton(
        text =
            if (item.playbackPositionTicks > 0) {
                stringResource(CoreR.string.runtime_minutes_left, runtimeMinutesLeft)
            } else {
                stringResource(CoreR.string.play)
            },
        onClick = onClick,
        modifier = modifier,
        variant = CinefinButtonVariant.Filled,
        size = CinefinButtonSize.Large,
        // Lumen 区域（电影 / 剧集详情）用 A 稿月白主按钮；其余页面保持媒体色填充
        tone =
            if (LocalLumenColors.current != null) CinefinButtonTone.Inverse
            else CinefinButtonTone.Media,
        enabled = enabled,
        icon = { tint ->
            Icon(
                painter = painterResource(CoreR.drawable.ic_play),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun PlayButtonMoviePreview() {
    CinefinTheme { PlayButton(item = dummyMovie, onClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun PlayButtonEpisodePreview() {
    CinefinTheme { PlayButton(item = dummyEpisode, onClick = {}) }
}
