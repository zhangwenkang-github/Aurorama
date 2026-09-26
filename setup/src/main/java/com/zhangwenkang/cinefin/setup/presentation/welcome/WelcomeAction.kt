package com.zhangwenkang.cinefin.setup.presentation.welcome

sealed interface WelcomeAction {
    data object OnContinueClick : WelcomeAction

    data object OnLearnMoreClick : WelcomeAction
}
