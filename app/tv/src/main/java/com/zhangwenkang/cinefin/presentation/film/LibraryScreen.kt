package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovies
import com.zhangwenkang.cinefin.film.presentation.library.LibraryAction
import com.zhangwenkang.cinefin.film.presentation.library.LibraryState
import com.zhangwenkang.cinefin.film.presentation.library.LibraryViewModel
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.presentation.film.components.SortByDialog
import com.zhangwenkang.cinefin.presentation.theme.FindroidTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.ui.components.Direction
import com.zhangwenkang.cinefin.ui.components.ItemCard
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun LibraryScreen(
    libraryId: UUID,
    libraryName: String,
    libraryType: CollectionType,
    navigateToLibrary: (libraryId: UUID, libraryName: String, libraryType: CollectionType) -> Unit,
    navigateToMovie: (itemId: UUID) -> Unit,
    navigateToShow: (itemId: UUID) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    var initialLoad by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(true) {
        viewModel.setup(parentId = libraryId, libraryType = libraryType)
        if (initialLoad) {
            viewModel.loadItems()
            initialLoad = false
        }
    }

    LibraryScreenLayout(
        libraryName = libraryName,
        state = state,
        onAction = { action ->
            when (action) {
                is LibraryAction.OnItemClick -> {
                    when (action.item) {
                        is FindroidMovie -> navigateToMovie(action.item.id)
                        is FindroidShow -> navigateToShow(action.item.id)
                        is FindroidFolder ->
                            navigateToLibrary(action.item.id, action.item.name, libraryType)
                    }
                }
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun LibraryScreenLayout(
    libraryName: String,
    state: LibraryState,
    onAction: (LibraryAction) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    val items = state.items.collectAsLazyPagingItems()

    var showSortByDialog by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        contentPadding =
            PaddingValues(
                horizontal = MaterialTheme.spacings.default * 2,
                vertical = MaterialTheme.spacings.large,
            ),
        modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
    ) {
        item(span = { GridItemSpan(this.maxLineSpan) }) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = libraryName, style = MaterialTheme.typography.displayMedium)
                Button(onClick = { showSortByDialog = true }) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_arrow_down_up),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(text = stringResource(CoreR.string.sort_by))
                }
            }
        }
        items(items.itemCount) { i ->
            val item = items[i]
            item?.let {
                ItemCard(
                    item = item,
                    direction = Direction.VERTICAL,
                    onClick = { onAction(LibraryAction.OnItemClick(item)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (showSortByDialog) {
        SortByDialog(
            currentSortBy = state.sortBy,
            currentSortOrder = state.sortOrder,
            onUpdate = { sortBy, sortOrder ->
                onAction(LibraryAction.ChangeSorting(sortBy, sortOrder))
            },
            onDismissRequest = { showSortByDialog = false },
        )
    }

    LaunchedEffect(items.itemCount > 0) {
        if (items.itemCount > 0) {
            focusRequester.requestFocus()
        }
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun LibraryScreenLayoutPreview() {
    val items: Flow<PagingData<FindroidItem>> = flowOf(PagingData.from(dummyMovies))
    FindroidTheme {
        LibraryScreenLayout(
            libraryName = "Movies",
            state = LibraryState(items = items),
            onAction = {},
        )
    }
}
