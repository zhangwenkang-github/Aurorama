package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

@Composable
fun ItemCountBadge(unplayedItemCount: Int, modifier: Modifier = Modifier) {
    BaseBadge(modifier = modifier) {
        Text(
            text = unplayedItemCountText(unplayedItemCount),
            color = Color.White,
            style = CinefinType.LabelSmall,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = CinefinSpacing.Space1),
        )
    }
}

@Composable
@Preview
private fun ItemCountBadgePreview() {
    CinefinTheme { ItemCountBadge(110) }
}
