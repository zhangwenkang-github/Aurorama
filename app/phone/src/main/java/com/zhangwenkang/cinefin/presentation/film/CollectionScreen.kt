package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovies
import com.zhangwenkang.cinefin.film.presentation.collection.CollectionAction
import com.zhangwenkang.cinefin.film.presentation.collection.CollectionState
import com.zhangwenkang.cinefin.film.presentation.collection.CollectionViewModel
import com.zhangwenkang.cinefin.models.CollectionSection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.presentation.film.components.CollectionGrid
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import java.util.UUID

@Composable
fun CollectionScreen(
    collectionId: UUID,
    collectionName: String,
    onItemClick: (item: FindroidItem) -> Unit,
    navigateBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadItems(collectionId) }

    CollectionScreenLayout(
        collectionName = collectionName,
        state = state,
        onAction = { action ->
            when (action) {
                is CollectionAction.OnItemClick -> onItemClick(action.item)
                is CollectionAction.OnBackClick -> navigateBack()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreenLayout(
    collectionName: String,
    state: CollectionState,
    onAction: (CollectionAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(collectionName) },
                navigationIcon = {
                    IconButton(onClick = { onAction(CollectionAction.OnBackClick) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        CollectionGrid(sections = state.sections, innerPadding = innerPadding, onAction = onAction)
    }
}

@PreviewScreenSizes
@Composable
private fun CollectionScreenLayoutPreview() {
    CinefinTheme {
        CollectionScreenLayout(
            collectionName = "Marvel",
            state =
                CollectionState(
                    sections =
                        listOf(
                            CollectionSection(
                                id = 0,
                                name = UiText.StringResource(CoreR.string.movies_label),
                                items = dummyMovies,
                            )
                        )
                ),
            onAction = {},
        )
    }
}
