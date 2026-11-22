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
 * 收藏 / 条目内容变更补刷新（W60b，W8-T5 扩为内容集合变更）。
 *
 * 订阅仓库层广播的收藏版本号与内容版本号，发现「有变化且本页还没处理」时回调 [onChange]。删除服务器条目后条目已不存在， 只靠收藏角标 patch
 * 不够，因此与收藏共用同一个既有刷新入口。
 *
 * 用 [rememberSaveable] 记住本页已处理的版本：页面被详情页盖住（组合销毁）期间发生的变更，回到页面时版本号已经前进，会补一次刷新——保证详情 / 列表卡 /
 * 收藏页的收藏角标与条目集合即时一致。
 */
@Composable
fun FavoriteChangeEffect(onChange: () -> Unit) {
    val favoriteVersion by UserDataEvents.favoriteVersion.collectAsStateWithLifecycle()
    val contentVersion by UserDataEvents.contentVersion.collectAsStateWithLifecycle()
    var handledFavoriteVersion by rememberSaveable { mutableIntStateOf(favoriteVersion) }
    var handledContentVersion by rememberSaveable { mutableIntStateOf(contentVersion) }
    LaunchedEffect(favoriteVersion, contentVersion) {
        if (favoriteVersion != handledFavoriteVersion || contentVersion != handledContentVersion) {
            handledFavoriteVersion = favoriteVersion
            handledContentVersion = contentVersion
            onChange()
        }
    }
}
