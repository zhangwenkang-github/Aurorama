package com.zhangwenkang.cinefin.presentation.film

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding

/**
 * 顶部「书架」Tab 的落点页。
 *
 * 与媒体库总览同构的独立目的地：解析命中书籍库后直接复用 [LibraryScreen] 渲染书目； 没有书籍库（或全部为空）时给出空态，**不再回退媒体库总览**（2026-10-01
 * 验收缺陷修复）。
 */
@Composable
fun BookshelfScreen(
    onOpenDrawer: () -> Unit,
    onItemClick: (FindroidItem) -> Unit,
    navigateBack: () -> Unit,
    viewModel: BookshelfViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    when (val current = state) {
        is BookshelfState.Ready ->
            LibraryScreen(
                libraryId = current.library.id,
                libraryName = current.library.name,
                libraryType = current.library.type,
                onItemClick = onItemClick,
                navigateBack = navigateBack,
            )
        else ->
            BookshelfPlaceholder(
                state = current,
                onOpenDrawer = onOpenDrawer,
                onRetry = { viewModel.load(force = true) },
            )
    }
}

/** 解析中 / 空态 / 失败态的页面骨架：顶栏保留抽屉入口，内容区居中反馈，不让用户困在空白页。 */
@Composable
private fun BookshelfPlaceholder(
    state: BookshelfState,
    onOpenDrawer: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val paddingStart = safePadding.start + pageGutter
    val paddingEnd = safePadding.end + pageGutter

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = paddingStart, top = safePadding.top, end = paddingEnd)
                    .height(56.dp),
        ) {
            TopBarAction(
                icon = CoreR.drawable.ic_menu,
                onClick = onOpenDrawer,
                contentDescription = stringResource(CoreR.string.title_book_shelf),
            )
        }

        Text(
            text = stringResource(CoreR.string.title_book_shelf),
            style = CinefinType.HeadlineMedium,
            color = colors.onSurface,
            modifier =
                Modifier.padding(start = paddingStart, end = paddingEnd)
                    .padding(bottom = CinefinSpacing.Space6),
        )

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = paddingStart),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                BookshelfState.Loading -> CircularProgressIndicator(color = colors.onSurfaceVariant)
                BookshelfState.Empty ->
                    CinefinEmptyState(
                        title = stringResource(FilmR.string.bookshelf_empty_title),
                        message = stringResource(FilmR.string.bookshelf_empty_message),
                        icon = { tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_book),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(44.dp),
                            )
                        },
                    )
                is BookshelfState.Failed -> {
                    ErrorCard(
                        onShowStacktrace = { showErrorDialog = true },
                        onRetryClick = onRetry,
                    )
                    if (showErrorDialog) {
                        ErrorDialog(
                            exception = state.error,
                            onDismissRequest = { showErrorDialog = false },
                        )
                    }
                }
                is BookshelfState.Ready -> Unit
            }
        }
    }
}
