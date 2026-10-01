package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.settings.presentation.models.Preference

@Composable
fun SettingsBaseCard(
    preference: Preference,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 事务性内容色取自当前皮肤（Lumen 区域为月白），禁用态按 38% 收敛；卡片底色一律透明——
    // 容器由分类卡提供，行本身只负责"可点 + 状态层"。
    val contentColor =
        LocalCinefinColors.current.onSurface.copy(alpha = if (preference.enabled) 1.0f else 0.38f)
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = preference.enabled,
        color = Color.Transparent,
        contentColor = contentColor,
    ) {
        content()
    }
}
