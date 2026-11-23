package com.zhangwenkang.cinefin.music.data

import android.content.Context
import android.net.Uri
import com.zhangwenkang.cinefin.api.MusicStreamRequest
import com.zhangwenkang.cinefin.local.LocalMediaExtensions
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSource
import com.zhangwenkang.cinefin.player.core.domain.models.TrackSourceType
import com.zhangwenkang.cinefin.player.core.playback.AudioCodec
import com.zhangwenkang.cinefin.player.core.playback.MusicAudioDecision
import com.zhangwenkang.cinefin.player.core.playback.MusicAudioNegotiation
import com.zhangwenkang.cinefin.player.local.capabilities.DeviceCapabilitiesProbe
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.MusicStreamingQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

/**
 * 音乐曲目 → [PlayerItem] 的解析（W1 R2；W5-T4 改为 `/Audio/{itemId}/universal` 协商）。
 *
 * 三条路径：
 * 1. 本地媒体库曲目（SAF `content://`）→ 直连本地文件；
 * 2. 已下载 / 合成 LOCAL 源 → 直连本地路径（离线可播，不经过服务器协商）；
 * 3. 服务器曲目 → 按设备音频解码能力 + 音质偏好决定直连 / 转码，再用 [MusicStreamRequest] 拼 `/Audio/{itemId}/universal`。
 *
 * 第 3 条路径仍然先取一次 `getMediaSources`：既确认有没有可用的本地下载源，也拿到音频流参数（codec / 码率 / 采样率 / 位深）； 起播地址不再使用服务器返回的
 * `transcodingPath ?: path`，统一由本类协商后构造。
 */
@Singleton
class MusicTrackResolver
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    @ApplicationContext private val context: Context,
) {

    /** 单曲解析（W3-R3b）：起播只需要被点的那一首，先解析它就能立刻出声， 其余曲目由调用方随后按需补齐——避免"点歌曲页任意一首要先串行解析整份列表"。 */
    suspend fun toPlayerItem(song: MusicSong): PlayerItem = song.resolve()

    private suspend fun MusicSong.resolve(): PlayerItem {
        // W37：本地媒体库曲目直接使用 SAF content:// 文档 URI，不经过服务器（离线也可播）。
        localUri?.let { uri ->
            return localPlayerItem(uri).withLocalReadability()
        }
        /*
         * W9-T5（F4）：已下载曲目先把本机文件交给播放器——按 itemId 直查下载记录（下载行自带身份），
         * 不依赖当前会话 identity，也不发 PlaybackInfo / 任何网络请求；断网时绝不会退化成 HTTP 流。
         */
        repository.getDownloadedLocalSource(itemId)?.let { downloaded ->
            Timber.d("已下载曲目直开本地文件：item=%s path=%s", itemId, downloaded.path)
            return localPlayerItem(downloaded.path, mediaSourceId = downloaded.id)
                .withLocalReadability()
        }
        val sources = repository.getMediaSources(itemId, includePath = true)
        val source =
            sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                ?: sources.firstOrNull()
                ?: error("曲目「$name」没有可用的媒体源")
        // 已下载 / 本地合成源：直接播本地路径，不做服务器协商（离线链路）。
        if (source.type == FindroidSourceType.LOCAL) {
            return localPlayerItem(source.path, mediaSourceId = source.id).withLocalReadability()
        }
        return serverPlayerItem(source)
    }

    /**
     * W5-T8（MUSIC-49）：本地 `content://` 源不可读时标记「不可用」而不是抛错。
     *
     * 条目仍进队列（保留显示快照与手动移除入口），播放链会跳过它；非 `content://` 源不做本地探测。
     */
    private suspend fun PlayerItem.withLocalReadability(): PlayerItem {
        if (!mediaSourceUri.startsWith("content://", ignoreCase = true)) return this
        val readable =
            withContext(Dispatchers.IO) {
                runCatching {
                        context.contentResolver
                            .openAssetFileDescriptor(Uri.parse(mediaSourceUri), "r")
                            ?.use { true } ?: false
                    }
                    .getOrDefault(false)
            }
        if (readable) return this
        Timber.w("本地曲目不可读，标记不可用：%s", name)
        return copy(unavailable = true)
    }

    private fun MusicSong.localPlayerItem(
        uri: String,
        mediaSourceId: String = LOCAL_SOURCE_ID,
    ): PlayerItem =
        PlayerItem(
            name = name,
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            playbackPosition = resumePositionMs,
            mediaSourceUri = uri,
            thumbnailUri = imageUri,
            artist = artist,
            trackSource =
                TrackSource(
                    id = itemId.toString(),
                    sourceType = TrackSourceType.LOCAL,
                    streamUrl = uri,
                    duration = runtimeTicks / TICKS_PER_MS,
                    bitrate = bitrate,
                    codec = codec ?: container ?: LocalMediaExtensions.musicCodecOf(name),
                    container = container,
                    sampleRate = sampleRate,
                    bitDepth = bitDepth,
                    coverArtUrl = imageUri,
                    isTranscoded = false,
                ),
        )

    private fun MusicSong.serverPlayerItem(source: FindroidSource): PlayerItem {
        val audioStream = source.mediaStreams.firstOrNull { it.type == MediaStreamType.AUDIO }
        val sourceCodec = audioStream?.codec?.takeIf { it.isNotBlank() } ?: codec
        val sourceBitrate = audioStream?.bitRate ?: bitrate ?: source.bitrate
        val sourceContainer = source.container ?: container ?: sourceCodec
        val preference = appPreferences.getValue(appPreferences.musicStreamingQuality)
        val supportedAudioCodecs = DeviceCapabilitiesProbe.probe(context).supportedAudioCodecs()
        val decision =
            MusicAudioNegotiation.decide(
                sourceCodec = sourceCodec,
                sourceBitrateBps = sourceBitrate,
                supportedAudioCodecs = supportedAudioCodecs,
                bitrateCeilingBps = MusicStreamingQuality.bitrateCeilingBps(preference),
                allowTranscoding = MusicStreamingQuality.transcodingEnabled(preference),
            )
        val ceiling = MusicStreamingQuality.bitrateCeilingBps(preference).takeIf { it > 0L }
        val request =
            when (decision) {
                MusicAudioDecision.DirectPlay ->
                    MusicStreamRequest(
                        directPlayContainers =
                            directPlayContainers(
                                supported = supportedAudioCodecs,
                                forceTranscode = false,
                                sourceContainer = sourceContainer,
                            ),
                        maxStreamingBitrate = ceiling,
                        enableRedirection = true,
                    )
                is MusicAudioDecision.Transcode ->
                    MusicStreamRequest(
                        directPlayContainers =
                            directPlayContainers(
                                supported = supportedAudioCodecs,
                                forceTranscode = true,
                                sourceContainer = sourceContainer,
                            ),
                        audioCodec = decision.audioCodec,
                        transcodingContainer = MusicAudioNegotiation.TRANSCODE_CONTAINER,
                        transcodingProtocol = TRANSCODING_PROTOCOL,
                        maxStreamingBitrate = ceiling ?: decision.audioBitRateBps.toLong(),
                        audioBitRate = decision.audioBitRateBps,
                        enableRedirection = true,
                    )
            }
        val streamUrl =
            repository.getMusicStreamUrl(itemId, request) ?: error("曲目「$name」无法构造播放地址（服务器地址无效）")
        Timber.d(
            "音乐协商 item=%s codec=%s bitrate=%s pref=%s containers=%s decision=%s",
            itemId,
            sourceCodec,
            sourceBitrate,
            preference,
            request.directPlayContainers,
            decision,
        )
        return PlayerItem(
            name = name,
            itemId = itemId,
            mediaSourceId = source.id,
            // W2：服务器 UserData.playbackPositionTicks 换算出的续播位置（0 = 从头播放）
            playbackPosition = resumePositionMs,
            mediaSourceUri = streamUrl,
            thumbnailUri = imageUri,
            artist = artist,
            trackSource =
                TrackSource(
                    id = source.id.ifBlank { itemId.toString() },
                    sourceType = TrackSourceType.SERVER,
                    streamUrl = streamUrl,
                    duration = runtimeTicks / TICKS_PER_MS,
                    bitrate = sourceBitrate,
                    codec = sourceCodec,
                    container = sourceContainer,
                    sampleRate = audioStream?.sampleRate ?: sampleRate,
                    bitDepth = audioStream?.bitDepth ?: bitDepth,
                    channelCount = audioStream?.channels,
                    coverArtUrl = imageUri,
                    lyricsRef = "Audio/$itemId/Lyrics",
                    isTranscoded = decision is MusicAudioDecision.Transcode,
                ),
        )
    }

    /**
     * `Container` 直连容器表。
     *
     * 转码时剔除源容器，让服务端判定「不能直连」；剔除后为空时退回转码目标容器，避免发出空 `Container`。 设备能力探测失败（空集）时用默认音乐容器表，把判定交给服务端。
     */
    private fun directPlayContainers(
        supported: Set<AudioCodec>,
        forceTranscode: Boolean,
        sourceContainer: String?,
    ): List<String> {
        val base =
            if (supported.isEmpty()) DEFAULT_AUDIO_CONTAINERS
            else MusicAudioNegotiation.directPlayContainers(supported)
        if (!forceTranscode) return base
        val filtered = base.filterNot { container ->
            container.equals(sourceContainer, ignoreCase = true)
        }
        return filtered.ifEmpty { listOf(MusicAudioNegotiation.TRANSCODE_CONTAINER) }
    }

    private companion object {
        const val LOCAL_SOURCE_ID = "local"
        const val TICKS_PER_MS = 10_000L

        /** 转码协议：渐进式 HTTP（ExoPlayer 直接按字节流播，不依赖 HLS）。 */
        const val TRANSCODING_PROTOCOL = "http"

        /** 能力探测失败时的直连容器表（与 `AudioCodec.container` 同源，顺序稳定）。 */
        val DEFAULT_AUDIO_CONTAINERS: List<String> =
            MusicAudioNegotiation.directPlayContainers(AudioCodec.entries.toSet())
    }
}
