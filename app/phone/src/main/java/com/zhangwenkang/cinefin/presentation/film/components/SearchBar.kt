package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.search.SearchAction
import com.zhangwenkang.cinefin.film.presentation.search.SearchState
import com.zhangwenkang.cinefin.presentation.utils.GridCellsAdaptiveWithMinColumns
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FilmSearchBar(
    state: SearchState,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
    paddingStart: Dp = 0.dp,
    paddingEnd: Dp = 0.dp,
) {
    val media = LocalMediaColors.current
    val pageGutter = rememberPageGutter()
    val focusRequester = remember { FocusRequester() }
    val safePadding = rememberSafePadding()

    var query by rememberSaveable { mutableStateOf("") }

    val searchBarPaddingStart by
        animateDpAsState(
            targetValue = if (expanded) 0.dp else paddingStart,
            label = "search_bar_padding_start",
        )

    val searchBarPaddingEnd by
        animateDpAsState(
            targetValue = if (expanded) 0.dp else paddingEnd,
            label = "search_bar_padding_end",
        )

    val searchBarInputPaddingStart by
        animateDpAsState(
            targetValue = if (expanded) safePadding.start else 0.dp,
            label = "search_bar_padding_start",
        )

    val searchBarInputPaddingEnd by
        animateDpAsState(
            targetValue = if (expanded) safePadding.end else 0.dp,
            label = "search_bar_padding_end",
        )

    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            // Debounce scales with input length. Max debounce of 300ms.
            delay(minOf(50L + (query.count() * 50L), 300L))
        }
        onAction(SearchAction.Search(query))
    }

    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { onExpand(true) },
                expanded = expanded,
                onExpandedChange = { onExpand(it) },
                modifier =
                    Modifier.padding(
                            start = searchBarInputPaddingStart,
                            end = searchBarInputPaddingEnd,
                        )
                        .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        text = stringResource(FilmR.string.search_placeholder),
                        style = CinefinType.BodyLarge,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    AnimatedContent(targetState = expanded, label = "search_to_back") {
                        targetExpanded ->
                        if (targetExpanded) {
                            Box(
                                modifier =
                                    Modifier.size(44.dp).cinefinClickable { onExpand(false) },
                                contentAlignment = androidx.compose.ui.Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_arrow_left),
                                    contentDescription = null,
                                )
                            }
                        } else {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_search),
                                contentDescription = null,
                            )
                        }
                    }
                },
                trailingIcon = {
                    if (state.loading) {
                        Box(modifier = Modifier.size(32.dp)) {
                            CircularProgressIndicator(color = media.base)
                        }
                    } else if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier.size(44.dp).cinefinClickable { query = "" },
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_x),
                                contentDescription = null,
                            )
                        }
                    }
                },
            )
        },
        expanded = expanded,
        onExpandedChange = { onExpand(it) },
        modifier = modifier.padding(start = searchBarPaddingStart, end = searchBarPaddingEnd),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCellsAdaptiveWithMinColumns(minSize = 160.dp, minColumns = 2),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = safePadding.start + pageGutter,
                        top = pageGutter,
                        end = safePadding.end + pageGutter,
                        bottom = safePadding.bottom + pageGutter,
                    ),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
            ) {
                items(items = state.items, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        direction = Direction.VERTICAL,
                        onClick = { onAction(SearchAction.OnItemClick(item)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (query.isNotBlank() && state.items.isEmpty() && !state.loading) {
                CinefinEmptyState(
                    title = stringResource(FilmR.string.search_empty_title),
                    message = stringResource(FilmR.string.search_empty_message),
                    icon = { tint ->
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_search),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(44.dp),
                        )
                    },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                )
            }
        }
    }
}
