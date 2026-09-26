package com.zhangwenkang.cinefin.film.presentation.collection

import com.zhangwenkang.cinefin.models.CollectionSection

data class CollectionState(
    val sections: List<CollectionSection> = emptyList(),
    val isLoading: Boolean = false,
    val error: Exception? = null,
)
