package com.zhangwenkang.cinefin.film.presentation.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.zhangwenkang.cinefin.core.Constants
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.film.presentation.collection.CollectionState
import com.zhangwenkang.cinefin.models.CollectionSection
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class FavoritesViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(CollectionState())
    val state = _state.asStateFlow()

    fun loadItems() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))

            try {
                val items = repository.getFavoriteItems()

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
