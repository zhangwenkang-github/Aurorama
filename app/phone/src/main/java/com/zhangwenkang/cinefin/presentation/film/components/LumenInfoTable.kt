package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors

/**
 * 制作信息表（§8.5 表格式信息行 · Lumen）：44dp 行高，key 用 `OnSurfaceFaint`、value 右对齐用 `OnSurfaceVariant`，1dp 弱分隔线。
 *
 * 平板详情页用它取代原来左侧一长列"标签: 值"文字——信息从主叙事流里挪到右侧，主栏只留剧情简介， 一眼能分清"我在看什么"和"这条目是什么"。
 */
@Composable
fun LumenInfoTable(
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    val colors = LocalCinefinColors.current
    Column(modifier = modifier) {
        rows.forEachIndexed { index, (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceFaint,
                    modifier = Modifier.width(88.dp),
                )
                Text(
                    text = value,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (index != rows.lastIndex) {
                HorizontalDivider(color = colors.outlineVariant)
            }
        }
        Spacer(modifier = Modifier.height(CinefinSpacing.Space2))
    }
}
