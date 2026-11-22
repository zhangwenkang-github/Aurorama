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
import com.zhangwenkang.cinefin.repository.ItemMetadataSnapshot
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.UserPolicyStore
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
private constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val videoMetadataParser: VideoMetadataParser,
    /** W8-T6：元数据编辑入口的权限来源；测试构造器不注入时保持 fail-closed。 */
    private val userPolicyStore: UserPolicyStore?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) : ViewModel() {
    @Inject
    constructor(
        repository: JellyfinRepository,
        appPreferences: AppPreferences,
        videoMetadataParser: VideoMetadataParser,
        userPolicyStore: UserPolicyStore,
    ) : this(repository, appPreferences, videoMetadataParser, userPolicyStore, Unit)

    constructor(
        repository: JellyfinRepository,
        appPreferences: AppPreferences,
        videoMetadataParser: VideoMetadataParser,
    ) : this(repository, appPreferences, videoMetadataParser, null, Unit)

    private val _state = MutableStateFlow(EpisodeState())
    val state = _state.asStateFlow()

    /** W8-T1：收藏 / 已看的写结果（权限失败 / 排队待同步）交给屏幕弹 Snackbar。 */
    private val writeEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val writeEvents = writeEventsChannel.receiveAsFlow()

    /** W8-T5：删除服务器条目的结果；[DetailWriteEvent.Confirmed] = 屏幕关闭详情页，其余弹 Snackbar。 */
    private val deleteEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val deleteEvents = deleteEventsChannel.receiveAsFlow()

    /** W8-T6：当前账号能否编辑元数据（管理员专属）；未拉取 / 身份切换后一律 false（fail-closed）。 */
    private val _canEditMetadata = MutableStateFlow(false)
    val canEditMetadata = _canEditMetadata.asStateFlow()

    /** W8-T6：编辑对话框预填快照；null = 服务器当前值未取到（字段禁用）。 */
    private val _metadataSnapshot = MutableStateFlow<ItemMetadataSnapshot?>(null)
    val metadataSnapshot = _metadataSnapshot.asStateFlow()

    /** W8-T6：快照读取中——屏幕等它结束后再展示对话框。 */
    private val _metadataSnapshotLoading = MutableStateFlow(false)
    val metadataSnapshotLoading = _metadataSnapshotLoading.asStateFlow()

    /** W8-T6：提交中——对话框据此禁用保存按钮。 */
    private val _metadataSaving = MutableStateFlow(false)
    val metadataSaving = _metadataSaving.asStateFlow()

    /** W8-T6：编辑结果；[DetailWriteEvent.Confirmed] = 屏幕关闭对话框，其余走既有 Snackbar 文案。 */
    private val metadataEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val metadataEvents = metadataEventsChannel.receiveAsFlow()

    private val contentRequests = LatestContentRequest()

    init {
        val policyStore = userPolicyStore
        if (policyStore != null) {
            viewModelScope.launch {
                runCatching { policyStore.ensureLoaded() }
                policyStore.access.collect { access ->
                    _canEditMetadata.value = access.canEditMetadata()
                }
            }
        }
    }

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

    /** W8-T5：删除服务器条目。失败时本地未改动，不重载详情；成功由屏幕关闭详情页。 */
    fun deleteItem() {
        viewModelScope.launch {
            val outcome = runCatching { repository.deleteItem(episodeId) }.getOrNull()
            deleteEventsChannel.send(outcome.toDetailWriteEvent())
        }
    }

    /** W8-T6：打开编辑对话框前先取服务器当前值；取不到（null）时对话框字段禁用。 */
    fun loadMetadataSnapshot() {
        _metadataSnapshotLoading.value = true
        _metadataSnapshot.value = null
        viewModelScope.launch {
            _metadataSnapshot.value =
                runCatching { repository.fetchItemMetadataSnapshot(episodeId) }.getOrNull()
            _metadataSnapshotLoading.value = false
        }
    }

    /** W8-T6：提交元数据编辑。成功后重取详情（以服务端为准）并由屏幕关闭对话框；失败走既有 Snackbar 文案。 */
    fun saveMetadata(name: String, overview: String?, sortName: String?) {
        if (_metadataSaving.value) return
        _metadataSaving.value = true
        viewModelScope.launch {
            val outcome = runCatching {
                repository.updateItemMetadata(
                    itemId = episodeId,
                    name = name,
                    overview = overview,
                    sortName = sortName,
                )
            }
                .getOrNull()
            _metadataSaving.value = false
            if (outcome?.localStateChanged == true) loadEpisode(episodeId)
            metadataEventsChannel.send(outcome.toDetailWriteEvent())
        }
    }
}
