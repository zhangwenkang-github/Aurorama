package com.zhangwenkang.cinefin.film.presentation.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItemPerson
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind

@HiltViewModel
class ShowViewModel @Inject constructor(private val repository: JellyfinRepository) : ViewModel() {
    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()

    lateinit var showId: UUID

    fun loadShow(showId: UUID) {
        this.showId = showId
        viewModelScope.launch {
            try {
                val show = repository.getShow(showId)
                val nextUp = getNextUp(showId)
                val seasons = repository.getSeasons(showId)
                val actors = getActors(show)
                val director = getDirector(show)
                val writers = getWriters(show)
                _state.emit(
                    _state.value.copy(
                        show = show,
                        nextUp = nextUp,
                        seasons = seasons,
                        actors = actors,
                        director = director,
                        writers = writers,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun getNextUp(showId: UUID): FindroidEpisode? {
        val nextUpItems = repository.getNextUp(showId)
        return nextUpItems.getOrNull(0)
    }

    private suspend fun getActors(item: FindroidShow): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    private suspend fun getDirector(item: FindroidShow): FindroidItemPerson? {
        return withContext(Dispatchers.Default) {
            item.people.firstOrNull { it.type == PersonKind.DIRECTOR }
        }
    }

    private suspend fun getWriters(item: FindroidShow): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.WRITER }
        }
    }

    fun onAction(action: ShowAction) {
        when (action) {
            is ShowAction.MarkAsPlayed -> setPlayed(played = true)
            is ShowAction.UnmarkAsPlayed -> setPlayed(played = false)
            is ShowAction.MarkAsFavorite -> setFavorite(favorite = true)
            is ShowAction.UnmarkAsFavorite -> setFavorite(favorite = false)
            is ShowAction.LoadDownloadTargets -> loadDownloadTargets()
            else -> Unit
        }
    }

    /** W51：整剧下载目标按需加载（只取可下载且非虚拟的剧集，按季 / 集顺序）。 */
    fun loadDownloadTargets() {
        if (_state.value.downloadTargetsLoading) return
        val show = _state.value.show ?: return
        viewModelScope.launch {
            _state.update { it.copy(downloadTargetsLoading = true, downloadTargetsError = null) }
            try {
                val episodes =
                    _state.value.seasons
                        .sortedBy { season -> season.indexNumber }
                        .flatMap { season ->
                            repository.getEpisodes(
                                seriesId = show.id,
                                seasonId = season.id,
                                fields = DetailDownloadRules.EPISODE_FETCH_FIELDS,
                            )
                        }
                        .let(DetailDownloadRules::downloadTargets)
                _state.update {
                    it.copy(downloadTargetsLoading = false, downloadTargets = episodes)
                }
            } catch (e: Exception) {
                _state.update { it.copy(downloadTargetsLoading = false, downloadTargetsError = e) }
            }
        }
    }

    /** W51：已播放标记乐观更新，失败回滚（用户 2026-10-03 口径）。 */
    private fun setPlayed(played: Boolean) {
        val previous = _state.value.show?.played ?: return
        _state.update { it.copy(show = it.show?.copy(played = played)) }
        viewModelScope.launch {
            runCatching {
                if (played) repository.markAsPlayed(showId) else repository.markAsUnplayed(showId)
            }
                .onFailure { _state.update { it.copy(show = it.show?.copy(played = previous)) } }
        }
    }

    /** W51：喜欢标记乐观更新，失败回滚。 */
    private fun setFavorite(favorite: Boolean) {
        val previous = _state.value.show?.favorite ?: return
        _state.update { it.copy(show = it.show?.copy(favorite = favorite)) }
        viewModelScope.launch {
            runCatching {
                if (favorite) repository.markAsFavorite(showId)
                else repository.unmarkAsFavorite(showId)
            }
                .onFailure { _state.update { it.copy(show = it.show?.copy(favorite = previous)) } }
        }
    }
}
