package com.zhangwenkang.cinefin.film.presentation.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailWriteEvent
import com.zhangwenkang.cinefin.film.presentation.detail.LatestContentRequest
import com.zhangwenkang.cinefin.film.presentation.detail.toDetailWriteEvent
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.localStateChanged
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SeasonViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(SeasonState())
    val state = _state.asStateFlow()

    /** W8-T1：收藏 / 已看的写结果（权限失败 / 排队待同步）交给屏幕弹 Snackbar。 */
    private val writeEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val writeEvents = writeEventsChannel.receiveAsFlow()
    private val contentRequests = LatestContentRequest()

    lateinit var seasonId: UUID

    fun loadSeason(seasonId: UUID) {
        this.seasonId = seasonId
        val request = contentRequests.begin()
        viewModelScope.launch {
            try {
                val season = repository.getSeason(seasonId)
                val episodes =
                    repository.getEpisodes(
                        seriesId = season.seriesId,
                        seasonId = seasonId,
                        fields = DetailDownloadRules.EPISODE_FETCH_FIELDS,
                    )
                if (!contentRequests.isCurrent(request)) return@launch
                _state.emit(
                    _state.value.copy(
                        season = season,
                        episodes = episodes,
                        error = null,
                    )
                )
            } catch (e: Exception) {
                if (!contentRequests.isCurrent(request)) return@launch
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

    /** W51：已播放标记乐观更新，失败回滚（用户 2026-10-03 口径）；W8-T1：失败 / 排队结果给屏幕提示。 */
    private fun setPlayed(played: Boolean) {
        val previous = _state.value.season?.played ?: return
        _state.update { it.copy(season = it.season?.copy(played = played)) }
        viewModelScope.launch {
            val outcome = runCatching {
                if (played) repository.markAsPlayed(seasonId)
                else repository.markAsUnplayed(seasonId)
            }
                .getOrNull()
            if (outcome?.localStateChanged != true) {
                _state.update { it.copy(season = it.season?.copy(played = previous)) }
            }
            writeEventsChannel.send(outcome.toDetailWriteEvent())
        }
    }

    /** W51：喜欢标记乐观更新，失败回滚；W8-T1：失败 / 排队结果给屏幕提示。 */
    private fun setFavorite(favorite: Boolean) {
        val previous = _state.value.season?.favorite ?: return
        _state.update { it.copy(season = it.season?.copy(favorite = favorite)) }
        viewModelScope.launch {
            val outcome = runCatching {
                if (favorite) repository.markAsFavorite(seasonId)
                else repository.unmarkAsFavorite(seasonId)
            }
                .getOrNull()
            if (outcome?.localStateChanged != true) {
                _state.update { it.copy(season = it.season?.copy(favorite = previous)) }
            }
            writeEventsChannel.send(outcome.toDetailWriteEvent())
        }
    }
}
