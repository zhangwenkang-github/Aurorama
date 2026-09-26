package com.zhangwenkang.cinefin.film.presentation.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.zhangwenkang.cinefin.core.Constants
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.CollectionSection
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class CollectionViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(CollectionState())
    val state = _state.asStateFlow()

    fun loadItems(parentId: UUID) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))

            try {
                val items = repository.getItems(parentId = parentId, sortBy = SortBy.RELEASE_DATE)

                val sections = mutableListOf<CollectionSection>()

                withContext(Dispatchers.Default) {
                    CollectionSection(
                            Constants.FAVORITE_TYPE_MOVIES,
                            UiText.StringResource(CoreR.string.movies_label),
                            items.filterIsInstance<FindroidMovie>(),
                        )
                        .let {
                            if (it.items.isNotEmpty()) {
                                sections.add(it)
                            }
                        }
                    CollectionSection(
                            Constants.FAVORITE_TYPE_SHOWS,
                            UiText.StringResource(CoreR.string.shows_label),
                            items.filterIsInstance<FindroidShow>(),
                        )
                        .let {
                            if (it.items.isNotEmpty()) {
                                sections.add(it)
                            }
                        }
                    CollectionSection(
                            Constants.FAVORITE_TYPE_EPISODES,
                            UiText.StringResource(CoreR.string.episodes_label),
                            items.filterIsInstance<FindroidEpisode>(),
                        )
                        .let {
                            if (it.items.isNotEmpty()) {
                                sections.add(it)
                            }
                        }
                }

                _state.emit(_state.value.copy(isLoading = false, sections = sections))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }
}
