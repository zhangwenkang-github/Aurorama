package com.zhangwenkang.cinefin.core.presentation.downloader

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonTone
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors

/**
 * W7-T1 下载前置检查提示层（视频 / 音乐 / 书籍共用）。
 *
 * 口径（负责人 2026-11-22 定稿）：Lumen 面板语言（1dp 描边 + 顶部内高光，宽 ≤480dp）+ 月白主行动； 面板内**即时**展示空间 /
 * 网络预检结果，不阻塞弹出；空间不足时确认键禁用并给「去清理」。
 */
@Composable
fun DownloadPrecheckDialog(
    content: DownloadPrecheckContent,
    space: DownloadSpaceStatus,
    networkPrompt: DownloadNetworkPrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onWifiOnly: () -> Unit = {},
    confirmLabel: String? = null,
    /** 批量空间截断说明（需求 50）：只入队前 N 集时由调用方给出。 */
    deferNote: String? = null,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    val insufficient = space.insufficient
    val confirmText = confirmLabel ?: stringResource(R.string.download_precheck_continue)
    val openStorageSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
        Unit
    }

    Dialog(onDismissRequest = onDismiss) {
        CinefinCard(
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            shape = CinefinShapes.Xl,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Column(modifier = Modifier.padding(CinefinSpacing.Space6)) {
                Text(
                    text = content.title,
                    style = CinefinType.HeadlineSmall,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(CinefinSpacing.Space3))
                precheckDetailLines(content).forEach { line ->
                    Text(
                        text = line,
                        style = CinefinType.BodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                }
                if (content.hasSidecarFiles) {
                    Text(
                        text = stringResource(R.string.download_precheck_sidecar),
                        style = CinefinType.BodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                }
                Spacer(Modifier.height(CinefinSpacing.Space3))
                Text(
                    text =
                        if (space.requiredBytes != null) {
                            stringResource(
                                R.string.download_precheck_space,
                                DownloadPrecheckRules.formatBytes(space.requiredBytes),
                                DownloadPrecheckRules.formatBytes(space.availableBytes),
                            )
                        } else {
                            stringResource(
                                R.string.download_precheck_space_unknown,
                                DownloadPrecheckRules.formatBytes(space.availableBytes),
                            )
                        },
                    style = CinefinType.BodyMedium,
                    color = if (insufficient) colors.error else colors.onSurfaceVariant,
                )
                if (insufficient) {
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = stringResource(R.string.download_precheck_space_insufficient),
                        style = CinefinType.BodySmall,
                        color = colors.error,
                    )
                }
                deferNote?.let { note ->
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = note,
                        style = CinefinType.BodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (networkPrompt == DownloadNetworkPrompt.CONFIRM_MOBILE_DATA) {
                    Spacer(Modifier.height(CinefinSpacing.Space2))
                    Text(
                        text = stringResource(R.string.download_precheck_mobile_data),
                        style = CinefinType.BodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(CinefinSpacing.Space6))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CinefinButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        variant = CinefinButtonVariant.Text,
                        size = CinefinButtonSize.Medium,
                    )
                    Spacer(Modifier.width(CinefinSpacing.Space2))
                    if (insufficient) {
                        CinefinButton(
                            text = stringResource(R.string.download_precheck_cleanup),
                            onClick = openStorageSettings,
                            variant = CinefinButtonVariant.Filled,
                            tone = CinefinButtonTone.Media,
                            size = CinefinButtonSize.Medium,
                        )
                    } else {
                        if (networkPrompt == DownloadNetworkPrompt.CONFIRM_MOBILE_DATA) {
                            CinefinButton(
                                text = stringResource(R.string.download_precheck_wifi_only),
                                onClick = onWifiOnly,
                                variant = CinefinButtonVariant.Outlined,
                                size = CinefinButtonSize.Medium,
                            )
                            Spacer(Modifier.width(CinefinSpacing.Space2))
                        }
                        CinefinButton(
                            text = confirmText,
                            onClick = onConfirm,
                            variant = CinefinButtonVariant.Filled,
                            tone = CinefinButtonTone.Media,
                            size = CinefinButtonSize.Medium,
                        )
                    }
                }
            }
        }
    }
}

/** 按媒体类型生成明细行（字符串全部走 core 资源）。 */
@Composable
private fun precheckDetailLines(content: DownloadPrecheckContent): List<String> {
    val format = content.formatLabel
    val size = content.sizeBytes?.let { DownloadPrecheckRules.formatBytes(it) }
    return when (content.kind) {
        DownloadPrecheckKind.VIDEO ->
            buildList {
                add(stringResource(R.string.download_precheck_video_original))
                format?.let { add(stringResource(R.string.download_precheck_format, it)) }
                add(
                    size?.let { stringResource(R.string.download_precheck_size, it) }
                        ?: stringResource(R.string.download_precheck_size_unknown)
                )
            }
        DownloadPrecheckKind.VIDEO_BATCH ->
            buildList {
                add(stringResource(R.string.download_precheck_batch_count, content.itemCount))
                add(
                    when {
                        size == null -> stringResource(R.string.download_precheck_size_unknown)
                        content.unknownSizeCount > 0 ->
                            stringResource(R.string.download_precheck_batch_total_partial, size)
                        else -> stringResource(R.string.download_precheck_batch_total, size)
                    }
                )
            }
        DownloadPrecheckKind.AUDIO ->
            buildList {
                content.secondaryLabel?.let { add(it) }
                format?.let { add(stringResource(R.string.download_precheck_format, it)) }
                add(
                    size?.let { stringResource(R.string.download_precheck_size, it) }
                        ?: stringResource(R.string.download_precheck_size_unknown)
                )
                content.durationLabel?.let {
                    add(stringResource(R.string.download_precheck_duration, it))
                }
            }
        DownloadPrecheckKind.AUDIO_BATCH ->
            buildList {
                add(stringResource(R.string.download_precheck_audio_batch_count, content.itemCount))
                add(
                    when {
                        size == null -> stringResource(R.string.download_precheck_size_unknown)
                        content.unknownSizeCount > 0 ->
                            stringResource(R.string.download_precheck_batch_total_partial, size)
                        else -> stringResource(R.string.download_precheck_batch_total, size)
                    }
                )
            }
        DownloadPrecheckKind.BOOK ->
            buildList {
                format?.let { add(stringResource(R.string.download_precheck_format, it)) }
                add(stringResource(R.string.download_precheck_book_offline))
                content.durationLabel?.let {
                    add(stringResource(R.string.download_precheck_duration, it))
                }
            }
    }
}
