package com.zhangwenkang.cinefin.film.presentation.episode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.domain.VideoMetadataParser
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItemPerson
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
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
class EpisodeViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val videoMetadataParser: VideoMetadataParser,
) : ViewModel() {
    private val _state = MutableStateFlow(EpisodeState())
    val state = _state.asStateFlow()

    lateinit var episodeId: UUID

    fun loadEpisode(episodeId: UUID) {
        this.episodeId = episodeId
        viewModelScope.launch {
            try {
                val episode = repository.getEpisode(episodeId)
                val videoMetadata = videoMetadataParser.parse(episode.sources.first())
                val actors = getActors(episode)
                val displayExtraInfo = appPreferences.getValue(appPreferences.displayExtraInfo)
                _state.emit(
                    _state.value.copy(
                        episode = episode,
                        videoMetadata = videoMetadata,
                        actors = actors,
                        displayExtraInfo = displayExtraInfo,
                    )
                )
            } catch (e: Exception) {
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

    /** W51：已播放标记乐观更新，失败回滚（用户 2026-10-03 口径）。 */
    private fun setPlayed(played: Boolean) {
        val previous = _state.value.episode?.played ?: return
        _state.update { it.copy(episode = it.episode?.copy(played = played)) }
        viewModelScope.launch {
            runCatching {
                if (played) repository.markAsPlayed(episodeId)
                else repository.markAsUnplayed(episodeId)
            }
                .onFailure {
                    _state.update { it.copy(episode = it.episode?.copy(played = previous)) }
                }
        }
    }

    /** W51：喜欢标记乐观更新，失败回滚。 */
    private fun setFavorite(favorite: Boolean) {
        val previous = _state.value.episode?.favorite ?: return
        _state.update { it.copy(episode = it.episode?.copy(favorite = favorite)) }
        viewModelScope.launch {
            runCatching {
                if (favorite) repository.markAsFavorite(episodeId)
                else repository.unmarkAsFavorite(episodeId)
            }
                .onFailure {
                    _state.update { it.copy(episode = it.episode?.copy(favorite = previous)) }
                }
        }
    }
}
