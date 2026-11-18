package com.zhangwenkang.cinefin.presentation.setup.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zhangwenkang.cinefin.presentation.utils.plus
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

/**
 * 首连向导（欢迎 / 服务器 / 添加服务器 / 账号 / 登录）的公共服务外壳。
 *
 * 向导属于**影视域**：向导页没有任何音乐 / 阅读内容，不需要按域切色。
 */
@Composable
fun RootLayout(padding: PaddingValues = PaddingValues(), content: @Composable BoxScope.() -> Unit) {
    val safePadding = rememberSafePadding()

    val safePaddingValues =
        PaddingValues(
            start = safePadding.start,
            top = safePadding.top,
            end = safePadding.end,
            bottom = safePadding.bottom,
        )

    Box(
        modifier = Modifier.fillMaxSize().padding(safePaddingValues + padding),
        content = content,
    )
}
