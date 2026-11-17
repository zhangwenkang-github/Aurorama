package com.zhangwenkang.cinefin.repository

import android.media.MediaCodecList
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.ContainerProfile
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

internal data class PlaybackCodecCapabilities(
    val videoCodecs: Set<String>,
    val audioCodecs: Set<String>,
)

internal object PlaybackDeviceProfileFactory {
    private val PCM_CODECS =
        setOf(
            "pcm_s8",
            "pcm_s16be",
            "pcm_s16le",
            "pcm_s24le",
            "pcm_s32le",
            "pcm_f32le",
            "pcm_alaw",
            "pcm_mulaw",
        )

    private val FORCED_AUDIO_CODECS =
        PCM_CODECS +
            setOf(
                "alac",
                "aac",
                "ac3",
                "eac3",
                "dts",
                "mlp",
                "truehd",
            )

    private val FALLBACK_CAPABILITIES =
        PlaybackCodecCapabilities(
            videoCodecs = setOf("h264"),
            audioCodecs = setOf("aac"),
        )

    private val CONTAINERS =
        listOf(
            ContainerSpec(
                name = "mp4",
                videoCodecs =
                    setOf(
                        "mpeg1video",
                        "mpeg2video",
                        "h263",
                        "mpeg4",
                        "h264",
                        "hevc",
                        "av1",
                        "vp9",
                    ),
                audioCodecs = setOf("mp1", "mp2", "mp3", "aac", "alac", "ac3", "opus"),
            ),
            ContainerSpec(
                name = "fmp4",
                videoCodecs =
                    setOf(
                        "mpeg1video",
                        "mpeg2video",
                        "h263",
                        "mpeg4",
                        "h264",
                        "hevc",
                        "av1",
                        "vp9",
                    ),
                audioCodecs = setOf("mp3", "aac", "ac3", "eac3"),
            ),
            ContainerSpec(
                name = "webm",
                videoCodecs = setOf("vp8", "vp9", "av1"),
                audioCodecs = setOf("vorbis", "opus"),
            ),
            ContainerSpec(
                name = "mkv",
                videoCodecs =
                    setOf(
                        "mpeg1video",
                        "mpeg2video",
                        "h263",
                        "mpeg4",
                        "h264",
                        "hevc",
                        "av1",
                        "vp8",
                        "vp9",
                    ),
                audioCodecs =
                    PCM_CODECS +
                        setOf(
                            "mp1",
                            "mp2",
                            "mp3",
                            "aac",
                            "vorbis",
                            "opus",
                            "flac",
                            "alac",
                            "ac3",
                            "eac3",
                            "dts",
                            "mlp",
                            "truehd",
                        ),
            ),
            ContainerSpec(name = "mp3", videoCodecs = emptySet(), audioCodecs = setOf("mp3")),
            ContainerSpec(
                name = "ogg",
                videoCodecs = emptySet(),
                audioCodecs = setOf("vorbis", "opus", "flac"),
            ),
            ContainerSpec(name = "wav", videoCodecs = emptySet(), audioCodecs = PCM_CODECS),
            ContainerSpec(
                name = "mpegts",
                videoCodecs =
                    setOf(
                        "mpeg1video",
                        "mpeg2video",
                        "mpeg4",
                        "h264",
                        "hevc",
                    ),
                audioCodecs =
                    PCM_CODECS +
                        setOf(
                            "mp1",
                            "mp2",
                            "mp3",
                            "aac",
                            "ac3",
                            "eac3",
                            "dts",
                            "mlp",
                            "truehd",
                        ),
            ),
            ContainerSpec(
                name = "flv",
                videoCodecs = setOf("mpeg4", "h264"),
                audioCodecs = setOf("mp3", "aac"),
            ),
            ContainerSpec(name = "aac", videoCodecs = emptySet(), audioCodecs = setOf("aac")),
            ContainerSpec(name = "flac", videoCodecs = emptySet(), audioCodecs = setOf("flac")),
            ContainerSpec(
                name = "3gp",
                videoCodecs = setOf("h263", "mpeg4", "h264", "hevc"),
                audioCodecs = setOf("3gpp", "aac", "flac"),
            ),
        )

    fun create(maxStreamingBitrate: Int): DeviceProfile =
        create(
            maxStreamingBitrate = maxStreamingBitrate,
            capabilities = readCapabilities(),
        )

    internal fun create(
        maxStreamingBitrate: Int,
        capabilities: PlaybackCodecCapabilities,
    ): DeviceProfile {
        val normalized =
            PlaybackCodecCapabilities(
                videoCodecs =
                    capabilities.videoCodecs.map(String::lowercase).toSet().ifEmpty {
                        FALLBACK_CAPABILITIES.videoCodecs
                    },
                audioCodecs =
                    (capabilities.audioCodecs.map(String::lowercase).toSet() + FORCED_AUDIO_CODECS)
                        .ifEmpty { FALLBACK_CAPABILITIES.audioCodecs },
            )
        val directPlayProfiles = mutableListOf<DirectPlayProfile>()
        val containerProfiles = mutableListOf<ContainerProfile>()
        val codecProfiles = mutableListOf<CodecProfile>()

        CONTAINERS.forEach { container ->
            val videoCodecs = container.videoCodecs.intersect(normalized.videoCodecs)
            val audioCodecs = container.audioCodecs.intersect(normalized.audioCodecs)
            if (videoCodecs.isNotEmpty() && audioCodecs.isNotEmpty()) {
                val sortedVideoCodecs = videoCodecs.sorted()
                containerProfiles +=
                    ContainerProfile(
                        type = DlnaProfileType.VIDEO,
                        conditions = emptyList(),
                        container = container.name,
                    )
                directPlayProfiles +=
                    DirectPlayProfile(
                        type = DlnaProfileType.VIDEO,
                        container = container.name,
                        videoCodec = sortedVideoCodecs.joinToString(","),
                        audioCodec = audioCodecs.sorted().joinToString(","),
                    )
                sortedVideoCodecs.forEach { codec ->
                    codecProfiles +=
                        CodecProfile(
                            type = CodecType.VIDEO,
                            conditions = emptyList(),
                            applyConditions = emptyList(),
                            codec = codec,
                            container = container.name,
                        )
                }
            }
            if (audioCodecs.isNotEmpty()) {
                containerProfiles +=
                    ContainerProfile(
                        type = DlnaProfileType.AUDIO,
                        conditions = emptyList(),
                        container = container.name,
                    )
                directPlayProfiles +=
                    DirectPlayProfile(
                        type = DlnaProfileType.AUDIO,
                        container = container.name,
                        audioCodec = audioCodecs.sorted().joinToString(","),
                    )
            }
        }

        return DeviceProfile(
            name = "Aurorama",
            maxStaticBitrate = maxStreamingBitrate,
            maxStreamingBitrate = maxStreamingBitrate,
            directPlayProfiles = directPlayProfiles,
            transcodingProfiles = defaultTranscodingProfiles(),
            containerProfiles = containerProfiles,
            codecProfiles = codecProfiles,
            subtitleProfiles =
                listOf(
                    SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                    SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
                ),
        )
    }

    private fun defaultTranscodingProfiles(): List<TranscodingProfile> =
        listOf(
            TranscodingProfile(
                container = "ts",
                type = DlnaProfileType.VIDEO,
                videoCodec = "h264",
                audioCodec = "aac,mp3,ac3,opus",
                protocol = MediaStreamProtocol.HLS,
                context = EncodingContext.STREAMING,
                enableSubtitlesInManifest = true,
                conditions = emptyList(),
            )
        )

    private fun readCapabilities(): PlaybackCodecCapabilities =
        try {
            val codecNames =
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .asSequence()
                    .filterNot { it.isEncoder }
                    .flatMap { codecInfo ->
                        runCatching { codecInfo.supportedTypes.asList().asSequence() }
                            .getOrDefault(emptySequence())
                    }
                    .mapNotNull(MIME_TO_JELLYFIN_CODEC::get)
                    .toSet()
            PlaybackCodecCapabilities(
                videoCodecs =
                    codecNames.intersect(VIDEO_CODEC_NAMES).ifEmpty {
                        FALLBACK_CAPABILITIES.videoCodecs
                    },
                audioCodecs = codecNames.intersect(AUDIO_CODEC_NAMES) + FORCED_AUDIO_CODECS,
            )
        } catch (_: RuntimeException) {
            FALLBACK_CAPABILITIES
        }

    private data class ContainerSpec(
        val name: String,
        val videoCodecs: Set<String>,
        val audioCodecs: Set<String>,
    )

    private val VIDEO_CODEC_NAMES =
        setOf(
            "mpeg1video",
            "mpeg2video",
            "h263",
            "mpeg4",
            "h264",
            "hevc",
            "av1",
            "vp8",
            "vp9",
        )

    private val AUDIO_CODEC_NAMES =
        PCM_CODECS +
            setOf(
                "mp1",
                "mp2",
                "mp3",
                "aac",
                "alac",
                "ac3",
                "eac3",
                "dts",
                "mlp",
                "truehd",
                "vorbis",
                "opus",
                "flac",
                "3gpp",
            )

    private val MIME_TO_JELLYFIN_CODEC =
        mapOf(
            "video/mpeg" to "mpeg1video",
            "video/mpeg2" to "mpeg2video",
            "video/mp4v-es" to "mpeg4",
            "video/3gpp" to "h263",
            "video/avc" to "h264",
            "video/hevc" to "hevc",
            "video/av01" to "av1",
            "video/x-vnd.on2.vp8" to "vp8",
            "video/x-vnd.on2.vp9" to "vp9",
            "audio/mpeg" to "mp3",
            "audio/mpeg-L1" to "mp1",
            "audio/mpeg-L2" to "mp2",
            "audio/mp4a-latm" to "aac",
            "audio/alac" to "alac",
            "audio/ac3" to "ac3",
            "audio/eac3" to "eac3",
            "audio/opus" to "opus",
            "audio/vorbis" to "vorbis",
            "audio/flac" to "flac",
            "audio/vnd.dts" to "dts",
            "audio/vnd.dts.hd" to "dts",
            "audio/true-hd" to "truehd",
            "audio/mlp" to "mlp",
            "audio/g711-alaw" to "pcm_alaw",
            "audio/g711-mlaw" to "pcm_mulaw",
        )
}
