package com.zhangwenkang.cinefin.presentation.setup.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zhangwenkang.cinefin.core.presentation.theme.ProvideLumen
import com.zhangwenkang.cinefin.presentation.utils.plus
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

/**
 * 首连向导（欢迎 / 服务器 / 添加服务器 / 账号 / 登录）的公共服务外壳。
 *
 * W6-VIS（D23）：向导属于**影视域**，整条流程统一铺 S1「A · Lumen」皮肤——向导页没有任何音乐 / 阅读内容， 不需要按域切色；用户第一次打开 App
 * 时看到的即是最终观感（曜石黑底 + 月白主行动 + 极光青焦点）。
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

    ProvideLumen {
        Box(
            modifier = Modifier.fillMaxSize().padding(safePaddingValues + padding),
            content = content,
        )
    }
}
