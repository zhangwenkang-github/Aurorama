package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.repository.UserDataEvents

/**
 * 收藏变更补刷新（W60b）：订阅仓库层广播的收藏版本号，发现「有变化且本页还没处理」时回调 [onChange]。
 *
 * 用 [rememberSaveable] 记住本页已处理的版本：页面被详情页盖住（组合销毁）期间发生的收藏 / 取消收藏， 回到页面时版本号已经前进，会补一次刷新——保证详情 / 列表卡 /
 * 收藏页的收藏角标即时一致。
 */
@Composable
fun FavoriteChangeEffect(onChange: () -> Unit) {
    val version by UserDataEvents.favoriteVersion.collectAsStateWithLifecycle()
    var handledVersion by rememberSaveable { mutableIntStateOf(version) }
    LaunchedEffect(version) {
        if (version != handledVersion) {
            handledVersion = version
            onChange()
        }
    }
}
