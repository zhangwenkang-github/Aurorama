package com.zhangwenkang.cinefin.music.presentation

import kotlinx.serialization.Serializable

/**
 * 音乐模式路由契约（W1 R2）。
 *
 * 路由注册由 R3 在 `NavigationRoot.kt` 统一提交（咽喉文件约定）；本模块只提供入口 Composable [MusicModeScreen] 与本常量，抽屉入口由 R3
 * 一并接入。
 */
@Serializable data object MusicModeRoute
