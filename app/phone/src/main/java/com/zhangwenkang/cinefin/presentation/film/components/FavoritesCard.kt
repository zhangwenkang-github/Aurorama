package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

@Composable
fun FavoritesCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    CinefinCard(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(CinefinSpacing.Space4),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_star),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(CoreR.string.title_favorite),
                style = CinefinType.TitleMedium,
                color = colors.onSurface,
            )
        }
    }
}

@Preview
@Composable
private fun FavoritesCardPreview() {
    CinefinTheme { FavoritesCard(onClick = {}, modifier = Modifier.width(320.dp)) }
}
