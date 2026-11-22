package com.zhangwenkang.cinefin.film.presentation.episode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.domain.VideoMetadataParser
import com.zhangwenkang.cinefin.film.domain.selectSourceForMetadata
import com.zhangwenkang.cinefin.film.presentation.detail.DetailWriteEvent
import com.zhangwenkang.cinefin.film.presentation.detail.LatestContentRequest
import com.zhangwenkang.cinefin.film.presentation.detail.readOptionalDetail
import com.zhangwenkang.cinefin.film.presentation.detail.toDetailWriteEvent
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItemPerson
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.localStateChanged
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
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
class EpisodeViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val videoMetadataParser: VideoMetadataParser,
) : ViewModel() {
    private val _state = MutableStateFlow(EpisodeState())
    val state = _state.asStateFlow()

    /** W8-T1：收藏 / 已看的写结果（权限失败 / 排队待同步）交给屏幕弹 Snackbar。 */
    private val writeEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val writeEvents = writeEventsChannel.receiveAsFlow()
    private val contentRequests = LatestContentRequest()

    lateinit var episodeId: UUID

    fun loadEpisode(episodeId: UUID, preferredMediaSourceId: String? = null) {
        this.episodeId = episodeId
        val request = contentRequests.begin()
        viewModelScope.launch {
            try {
                val episode = repository.getEpisode(episodeId)
                if (!contentRequests.isCurrent(request)) return@launch
                _state.emit(
                    _state.value.copy(
                        episode = episode,
                        videoMetadata = null,
                        actors = emptyList(),
                        error = null,
                    )
                )
                val videoMetadataResult = readOptionalDetail {
                    selectSourceForMetadata(episode.sources, preferredMediaSourceId)?.let { source
                        ->
                        videoMetadataParser.parse(source)
                    }
                }
                val actorsResult = readOptionalDetail { getActors(episode) }
                val displayExtraInfoResult = readOptionalDetail {
                    appPreferences.getValue(appPreferences.displayExtraInfo)
                }
                if (!contentRequests.isCurrent(request)) return@launch
                val enrichmentError =
                    listOf(videoMetadataResult, actorsResult, displayExtraInfoResult)
                        .firstNotNullOfOrNull { result -> result.exceptionOrNull() as? Exception }
                _state.emit(
                    _state.value.copy(
                        videoMetadata = videoMetadataResult.getOrNull(),
                        actors = actorsResult.getOrDefault(emptyList()),
                        displayExtraInfo =
                            displayExtraInfoResult.getOrDefault(_state.value.displayExtraInfo),
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

    private suspend fun getActors(item: FindroidEpisode): List<FindroidItemPerson> {
        return withContext(Dispatchers.Default) {
            item.people.filter { it.type == PersonKind.ACTOR }
        }
    }

    fun onAction(action: EpisodeAction) {
        when (action) {
            is EpisodeAction.MarkAsPlayed -> setPlayed(played = true)
            is EpisodeAction.UnmarkAsPlayed -> setPlayed(played = false)
            is EpisodeAction.MarkAsFavorite -> setFavorite(favorite = true)
            is EpisodeAction.UnmarkAsFavorite -> setFavorite(favorite = false)
            else -> Unit
        }
    }

    /** W51：已播放标记乐观更新，失败回滚（用户 2026-10-03 口径）；W8-T1：失败 / 排队结果给屏幕提示。 */
    private fun setPlayed(played: Boolean) {
        val previous = _state.value.episode?.played ?: return
        _state.update { it.copy(episode = it.episode?.copy(played = played)) }
        viewModelScope.launch {
            val outcome = runCatching {
                if (played) repository.markAsPlayed(episodeId)
                else repository.markAsUnplayed(episodeId)
            }
                .getOrNull()
            if (outcome?.localStateChanged != true) {
                _state.update { it.copy(episode = it.episode?.copy(played = previous)) }
            }
            writeEventsChannel.send(outcome.toDetailWriteEvent())
        }
    }

    /** W51：喜欢标记乐观更新，失败回滚；W8-T1：失败 / 排队结果给屏幕提示。 */
    private fun setFavorite(favorite: Boolean) {
        val previous = _state.value.episode?.favorite ?: return
        _state.update { it.copy(episode = it.episode?.copy(favorite = favorite)) }
        viewModelScope.launch {
            val outcome = runCatching {
                if (favorite) repository.markAsFavorite(episodeId)
                else repository.unmarkAsFavorite(episodeId)
            }
                .getOrNull()
            if (outcome?.localStateChanged != true) {
                _state.update { it.copy(episode = it.episode?.copy(favorite = previous)) }
            }
            writeEventsChannel.send(outcome.toDetailWriteEvent())
        }
    }
}
