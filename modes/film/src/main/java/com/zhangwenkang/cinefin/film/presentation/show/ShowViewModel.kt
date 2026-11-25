package com.zhangwenkang.cinefin.film.presentation.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailWriteEvent
import com.zhangwenkang.cinefin.film.presentation.detail.LatestContentRequest
import com.zhangwenkang.cinefin.film.presentation.detail.readOptionalDetail
import com.zhangwenkang.cinefin.film.presentation.detail.toDetailWriteEvent
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItemPerson
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.localStateChanged
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PersonKind

@HiltViewModel
class ShowViewModel @Inject constructor(private val repository: JellyfinRepository) : ViewModel() {
    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()

    /** W8-T1：收藏 / 已看的写结果（权限失败 / 排队待同步）交给屏幕弹 Snackbar。 */
    private val writeEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val writeEvents = writeEventsChannel.receiveAsFlow()
    private val contentRequests = LatestContentRequest()

    lateinit var showId: UUID

    fun loadShow(showId: UUID) {
        this.showId = showId
        val request = contentRequests.begin()
        viewModelScope.launch {
            try {
                val show = repository.getShow(showId)
                if (!contentRequests.isCurrent(request)) return@launch
                // W1-T05（#16）：同一部剧的刷新保留上次的季 / 演员等明细（静默刷新），避免清空后
                // 再填充的闪空中间态；换剧时才清掉旧剧明细，防止串剧。
                val latest = _state.value
                val sameShow = latest.show?.id == showId
                _state.emit(
                    latest.copy(
                        show = show,
                        nextUp = if (sameShow) latest.nextUp else null,
                        seasons = if (sameShow) latest.seasons else emptyList(),
                        actors = if (sameShow) latest.actors else emptyList(),
                        director = if (sameShow) latest.director else null,
                        writers = if (sameShow) latest.writers else emptyList(),
                        error = null,
                    )
                )
                val nextUpResult = readOptionalDetail { getNextUp(showId) }
                val seasonsResult = readOptionalDetail { repository.getSeasons(showId) }
                val actorsResult = readOptionalDetail { getActors(show) }
                val directorResult = readOptionalDetail { getDirector(show) }
                val writersResult = readOptionalDetail { getWriters(show) }
                if (!contentRequests.isCurrent(request)) return@launch

                // 静默刷新时明细取失败就沿用旧值，不把已展示的季 / 演员退回空列表。
                fun <T> settled(result: Result<T>, previous: T, fallback: T): T =
                    if (result.isFailure && sameShow) previous else result.getOrDefault(fallback)

                val enrichmentError =
                    listOf(
                            nextUpResult,
                            seasonsResult,
                            actorsResult,
                            directorResult,
                            writersResult,
                        )
                        .firstNotNullOfOrNull { result -> result.exceptionOrNull() as? Exception }
                val current = _state.value
                _state.emit(
                    current.copy(
                        nextUp = settled(nextUpResult, current.nextUp, null),
                        seasons = settled(seasonsResult, current.seasons, emptyList()),
                        actors = settled(actorsResult, current.actors, emptyList()),
                        director = settled(directorResult, current.director, null),
                        writers = settled(writersResult, current.writers, emptyList()),
                        error = enrichmentError,
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                if (!contentRequests.isCurrent(request)) return@launch
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

    /** W51：已播放标记乐观更新，失败回滚（用户 2026-10-03 口径）；W8-T1：失败 / 排队结果给屏幕提示。 */
    private fun setPlayed(played: Boolean) {
        val previous = _state.value.show?.played ?: return
        _state.update { it.copy(show = it.show?.copy(played = played)) }
        viewModelScope.launch {
            val outcome = runCatching {
                if (played) repository.markAsPlayed(showId) else repository.markAsUnplayed(showId)
            }
                .getOrNull()
            if (outcome?.localStateChanged != true) {
                _state.update { it.copy(show = it.show?.copy(played = previous)) }
            }
            writeEventsChannel.send(outcome.toDetailWriteEvent())
        }
    }

    /** W51：喜欢标记乐观更新，失败回滚；W8-T1：失败 / 排队结果给屏幕提示。 */
    private fun setFavorite(favorite: Boolean) {
        val previous = _state.value.show?.favorite ?: return
        _state.update { it.copy(show = it.show?.copy(favorite = favorite)) }
        viewModelScope.launch {
            val outcome = runCatching {
                if (favorite) repository.markAsFavorite(showId)
                else repository.unmarkAsFavorite(showId)
            }
                .getOrNull()
            if (outcome?.localStateChanged != true) {
                _state.update { it.copy(show = it.show?.copy(favorite = previous)) }
            }
            writeEventsChannel.send(outcome.toDetailWriteEvent())
        }
    }
}
