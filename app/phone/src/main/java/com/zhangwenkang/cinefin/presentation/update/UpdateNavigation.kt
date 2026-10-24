package com.zhangwenkang.cinefin.presentation.update

import kotlinx.serialization.Serializable

@Serializable data object UpdateRoute

fun updateDestination(): Any = UpdateRoute
