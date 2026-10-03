package com.zhangwenkang.cinefin.film.presentation.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SeasonViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(SeasonState())
    val state = _state.asStateFlow()

    lateinit var seasonId: UUID

    fun loadSeason(seasonId: UUID) {
        this.seasonId = seasonId
        viewModelScope.launch {
            try {
                val season = repository.getSeason(seasonId)
                val episodes =
                    repository.getEpisodes(
                        seriesId = season.seriesId,
                        seasonId = seasonId,
                        fields = DetailDownloadRules.EPISODE_FETCH_FIELDS,
                    )
                _state.emit(_state.value.copy(season = season, episodes = episodes))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    fun onAction(action: SeasonAction) {
        when (action) {
            is SeasonAction.MarkAsPlayed -> setPlayed(played = true)
            is SeasonAction.UnmarkAsPlayed -> setPlayed(played = false)
            is SeasonAction.MarkAsFavorite -> setFavorite(favorite = true)
            is SeasonAction.UnmarkAsFavorite -> setFavorite(favorite = false)
            else -> Unit
        }
    }

    /** W51：已播放标记乐观更新，失败回滚（用户 2026-10-03 口径）。 */
    private fun setPlayed(played: Boolean) {
        val previous = _state.value.season?.played ?: return
        _state.update { it.copy(season = it.season?.copy(played = played)) }
        viewModelScope.launch {
            runCatching {
                if (played) repository.markAsPlayed(seasonId)
                else repository.markAsUnplayed(seasonId)
            }
                .onFailure {
                    _state.update { it.copy(season = it.season?.copy(played = previous)) }
                }
        }
    }

    /** W51：喜欢标记乐观更新，失败回滚。 */
    private fun setFavorite(favorite: Boolean) {
        val previous = _state.value.season?.favorite ?: return
        _state.update { it.copy(season = it.season?.copy(favorite = favorite)) }
        viewModelScope.launch {
            runCatching {
                if (favorite) repository.markAsFavorite(seasonId)
                else repository.unmarkAsFavorite(seasonId)
            }
                .onFailure {
                    _state.update { it.copy(season = it.season?.copy(favorite = previous)) }
                }
        }
    }
}
