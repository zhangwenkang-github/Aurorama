package com.zhangwenkang.cinefin.presentation.settings

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.zhangwenkang.cinefin.BuildConfig
import com.zhangwenkang.cinefin.R
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.film.components.LumenCardFrame
import com.zhangwenkang.cinefin.presentation.settings.components.SettingsRow
import com.zhangwenkang.cinefin.presentation.settings.components.SettingsRowHorizontalPadding
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.settings.R as SettingsR

/** W71 · 项目主页 / 发布页（公开 GitHub 仓库）。 */
private const val PROJECT_HOME_URL = "https://github.com/zhangwenkang-github/Aurorama"

private const val PROJECT_RELEASES_URL = "$PROJECT_HOME_URL/releases"

private val ProjectHomeLabel = PROJECT_HOME_URL.removePrefix("https://")

/**
 * 关于页（W71 重做）：
 *
 * - 品牌区：应用标记 + 应用名（极光幕 / Aurorama）+ 版本号 + 简介 + MiSans 声明；
 * - 信息卡：包名 / 开源许可（GPL-3.0 · 基于 Findroid 改造，点开 = NOTICE）/ 隐私政策（应用内文本）/ 项目主页 / GitHub Releases；
 * - 下方保留 AboutLibraries 自动生成的第三方组件与许可证清单（按实际打包依赖生成）。
 *
 * 隐私政策与 NOTICE 的应用内文本是 res/raw 副本，改动时须与仓库根 `PRIVACY` / `NOTICE` 同步 （见 docs/RELEASE_PLAN.md
 * 的发布检查清单）。
 */
@Composable
fun AboutScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val colors = LocalCinefinColors.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val horizontalPadding = safePadding.start + pageGutter

    val libraries by produceLibraries(R.raw.aboutlibraries)
    val privacyText = rememberRawText(R.raw.privacy_policy)
    val noticeText = rememberRawText(R.raw.notice)

    var openPage by remember { mutableStateOf<AboutTextPage?>(null) }

    val openUri: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        CinefinPageTopBar(
            title = stringResource(SettingsR.string.about),
            onBack = navigateBack,
            modifier = Modifier.padding(start = safePadding.start),
        )
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = horizontalPadding,
                    end = safePadding.end + pageGutter,
                    top = CinefinSpacing.Space5,
                    bottom = CinefinSpacing.Space8,
                ),
            header = {
                item(key = "about-header") {
                    AboutHeaderBlock(
                        onOpenLicense = { openPage = AboutTextPage.License },
                        onOpenPrivacy = { openPage = AboutTextPage.Privacy },
                        onOpenProjectHome = { openUri(PROJECT_HOME_URL) },
                        onOpenReleases = { openUri(PROJECT_RELEASES_URL) },
                    )
                }
                item(key = "about-libraries-title") {
                    Text(
                        text = stringResource(R.string.about_third_party),
                        style = CinefinType.LabelSmall,
                        color = colors.onSurfaceVariant,
                        modifier =
                            Modifier.padding(
                                top = CinefinSpacing.Space6,
                                bottom = CinefinSpacing.Space2,
                            ),
                    )
                }
            },
        )
    }

    when (openPage) {
        AboutTextPage.License ->
            AboutTextDialog(
                title = stringResource(R.string.about_license),
                text = noticeText,
                onDismiss = { openPage = null },
            )
        AboutTextPage.Privacy ->
            AboutTextDialog(
                title = stringResource(R.string.about_privacy),
                text = privacyText,
                onDismiss = { openPage = null },
            )
        null -> Unit
    }
}

private enum class AboutTextPage {
    License,
    Privacy,
}

@Composable
private fun AboutHeaderBlock(
    onOpenLicense: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenProjectHome: () -> Unit,
    onOpenReleases: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val appName = stringResource(CoreR.string.app_name)
    // 「极光幕 / Aurorama」双名并列：非英文语境下补上国际名，英文语境不重复。
    val brandName =
        if (appName == InternationalAppName) appName else "$appName · $InternationalAppName"

    Column(verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space5)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(CoreR.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.width(120.dp),
            )
            Spacer(Modifier.height(CinefinSpacing.Space3))
            Text(
                text = brandName,
                style = CinefinType.TitleLarge,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(CinefinSpacing.Space1))
            Text(
                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = CinefinType.BodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(CinefinSpacing.Space2))
            Text(
                text = stringResource(CoreR.string.app_description),
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(CinefinSpacing.Space2))
            // MiSans 许可条款①：软件内特别注明使用了 MiSans 字体（W40 字体波）。
            Text(
                text = stringResource(CoreR.string.misans_attribution),
                style = CinefinType.BodySmall,
                color = colors.onSurfaceFaint,
            )
        }

        LumenCardFrame(shape = CinefinShapes.Lg, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AboutInfoRow(
                    title = stringResource(R.string.about_package_name),
                    description = BuildConfig.APPLICATION_ID,
                )
                AboutRowDivider()
                AboutInfoRow(
                    title = stringResource(R.string.about_license),
                    description = stringResource(R.string.about_license_summary),
                    onClick = onOpenLicense,
                )
                AboutRowDivider()
                AboutInfoRow(
                    title = stringResource(R.string.about_privacy),
                    onClick = onOpenPrivacy,
                )
                AboutRowDivider()
                AboutInfoRow(
                    title = stringResource(R.string.about_project_home),
                    description = ProjectHomeLabel,
                    onClick = onOpenProjectHome,
                )
                AboutRowDivider()
                AboutInfoRow(
                    title = stringResource(R.string.about_releases),
                    description = "$ProjectHomeLabel/releases",
                    onClick = onOpenReleases,
                )
            }
        }
    }
}

@Composable
private fun AboutInfoRow(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
) {
    SettingsRow(
        title = title,
        description = description,
        showChevron = onClick != null,
        modifier = if (onClick != null) Modifier.cinefinClickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun AboutRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SettingsRowHorizontalPadding),
        color = LocalCinefinColors.current.outlineVariant,
    )
}

@Composable
private fun AboutTextDialog(title: String, text: String, onDismiss: () -> Unit) {
    val colors = LocalCinefinColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) }
        },
        title = { Text(text = title, style = CinefinType.TitleMedium, color = colors.onSurface) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = text,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        containerColor = colors.surfaceContainerHighest,
    )
}

/** 读取 `res/raw` 文本（隐私政策 / NOTICE 的应用内副本），失败时给空串而不是崩溃。 */
@Composable
private fun rememberRawText(resId: Int): String {
    val context = LocalContext.current
    return remember(resId) {
        runCatching {
                context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
            }
            .getOrDefault("")
    }
}

/** 英文（默认资源）应用名；用于「极光幕 / Aurorama」双名展示。 */
private const val InternationalAppName = "Aurorama"

@Composable
@PreviewScreenSizes
private fun AboutScreenPreview() {
    CinefinTheme { AboutScreen(navigateBack = {}) }
}
