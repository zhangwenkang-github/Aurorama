package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.models.FindroidItem

data class SearchState(val items: List<FindroidItem> = emptyList(), val loading: Boolean = false)
