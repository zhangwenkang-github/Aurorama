package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.zhangwenkang.cinefin.core.presentation.components.CinefinListRow
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.search.SearchAction
import com.zhangwenkang.cinefin.film.presentation.search.SearchCategory
import com.zhangwenkang.cinefin.film.presentation.search.SearchState
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.presentation.local.formatDuration
import com.zhangwenkang.cinefin.presentation.local.iconRes
import com.zhangwenkang.cinefin.presentation.local.sizeText
import com.zhangwenkang.cinefin.presentation.utils.GridCellsAdaptiveWithMinColumns
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.util.UUID
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
    /** W60b：下载状态角标（itemId → 角标）；搜索结果与列表同一套卡片角标。 */
    downloadBadges: Map<UUID, DownloadBadgeInfo> = emptyMap(),
) {
    val media = LocalMediaColors.current
    val pageGutter = rememberPageGutter()
    val focusRequester = remember { FocusRequester() }
    val safePadding = rememberSafePadding()

    var query by rememberSaveable { mutableStateOf("") }
    // 只在本页首次进入时自动聚焦；从结果详情返回时不重开输入法（否则系统返回要先收键盘）。
    var focusRequested by rememberSaveable { mutableStateOf(false) }

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
        if (!expanded) {
            focusRequested = false
        } else if (!focusRequested) {
            focusRequester.requestFocus()
            focusRequested = true
        }
    }

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            // Debounce scales with input length. Max debounce of 300ms.
            delay(minOf(50L + (query.count() * 50L), 300L))
        }
        onAction(SearchAction.Search(query))
    }

    // W60b：收藏变更后重跑当前查询，搜索结果卡片的收藏角标即时一致。
    FavoriteChangeEffect { if (query.isNotBlank()) onAction(SearchAction.Search(query)) }

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
                                    painter = painterResource(CoreR.drawable.ic_close),
                                    contentDescription = stringResource(FilmR.string.search_close),
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
                                contentDescription = stringResource(FilmR.string.search_clear),
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
                if (state.serverFailed && !state.isEmpty) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "search-server-failed") {
                        Text(
                            text = stringResource(FilmR.string.search_server_failed),
                            style = CinefinType.BodySmall,
                            color = LocalCinefinColors.current.onSurfaceVariant,
                        )
                    }
                }
                // W82 #25：先按影视 / 音乐 / 书籍分区；分区内服务器条目用既有海报卡，本地条目用整行。
                SearchCategories.forEach { (category, titleRes) ->
                    val serverItems = state.serverItemsIn(category)
                    val localItems = state.localItemsIn(category)
                    if (serverItems.isEmpty() && localItems.isEmpty()) return@forEach
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = "search-section-${category.name}",
                    ) {
                        SearchSectionHeader(
                            title = stringResource(titleRes),
                            count = serverItems.size + localItems.size,
                        )
                    }
                    items(
                        items = serverItems,
                        key = { "server-${category.name}-${it.id}" },
                    ) { item ->
                        ItemCard(
                            item = item,
                            direction = Direction.VERTICAL,
                            onClick = { onAction(SearchAction.OnItemClick(item)) },
                            modifier = Modifier.animateItem(),
                            downloadBadge = downloadBadges[item.id] ?: DownloadBadgeInfo(),
                        )
                    }
                    items(
                        items = localItems,
                        key = { "local-${category.name}-${it.entry.itemId}" },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { hit ->
                        LocalSearchResultRow(
                            hit = hit,
                            onClick = { onAction(SearchAction.OnLocalItemClick(hit)) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            if (query.isNotBlank() && state.loading && state.isEmpty) {
                CircularProgressIndicator(
                    color = media.base,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (query.isNotBlank() && state.isEmpty && !state.loading) {
                CinefinEmptyState(
                    title =
                        if (state.serverFailed) stringResource(FilmR.string.search_error_title)
                        else stringResource(FilmR.string.search_empty_query, query),
                    message =
                        if (state.serverFailed) stringResource(FilmR.string.search_error_message)
                        else stringResource(FilmR.string.search_empty_message),
                    icon = { tint ->
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_search),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(44.dp),
                        )
                    },
                    action =
                        if (state.serverFailed) {
                            {
                                TextButton(onClick = { onAction(SearchAction.Retry) }) {
                                    Text(stringResource(FilmR.string.search_retry))
                                }
                            }
                        } else null,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private val SearchCategories =
    listOf(
        SearchCategory.VIDEO to FilmR.string.search_category_video,
        SearchCategory.MUSIC to FilmR.string.search_category_music,
        SearchCategory.BOOK to FilmR.string.search_category_book,
    )

/** W43 搜索结果分区标题：`服务器` / `本地` + 命中数量（无命中的分区不渲染）。 */
@Composable
private fun SearchSectionHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = CinefinSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        Text(text = title, style = CinefinType.LabelMedium, color = colors.onSurfaceVariant)
        Text(
            text = count.toString(),
            style = CinefinType.MonoDataSmall,
            color = colors.onSurfaceFaint,
        )
    }
}

/**
 * W43 本地结果行：类型图标 + 名称 + 一行元数据（库 / 文件夹 · 时长 · 大小）+「本地」来源徽标。 点击事件仍走 [SearchAction.OnLocalItemClick]，由
 * MediaScreen 按类型分发打开链路。
 */
@Composable
private fun LocalSearchResultRow(
    hit: LocalSearchHit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val entry = hit.entry
    CinefinListRow(
        title = entry.displayName,
        secondary = localSearchMeta(hit),
        badge = stringResource(FilmR.string.search_section_local),
        onClick = onClick,
        modifier = modifier,
        leading = {
            Icon(
                painter = painterResource(entry.kind.iconRes()),
                contentDescription = entry.kind.label,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

/** 本地行元数据：库名 · 文件夹 → 音乐时长 → 文件大小（全为空时兜底「未知大小」）。 */
private fun localSearchMeta(hit: LocalSearchHit): String {
    val duration =
        hit.entry.durationMs
            .takeIf { it > 0L && hit.entry.kind == LocalMediaKind.MUSIC }
            ?.let { formatDuration(it) }
    return listOfNotNull(hit.folderLabel, duration, sizeText(hit.entry.sizeBytes))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}
