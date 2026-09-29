package com.zhangwenkang.cinefin.presentation.setup.welcome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.setup.components.RootLayout
import com.zhangwenkang.cinefin.presentation.setup.components.SetupBrandMark
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.setup.R as SetupR
import com.zhangwenkang.cinefin.setup.presentation.welcome.WelcomeAction

@Composable
fun WelcomeScreen(onContinueClick: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    WelcomeScreenLayout(
        onAction = { action ->
            when (action) {
                is WelcomeAction.OnContinueClick -> onContinueClick()
                is WelcomeAction.OnLearnMoreClick -> {
                    uriHandler.openUri("https://jellyfin.org/")
                }
            }
        }
    )
}

/**
 * 欢迎页：一枚印记、一句话、一个动作。
 *
 * 版式刻意不做居中的"启动页"套路——左对齐、留白压在上下两端， 让这一屏看起来像放映前的一页节目单，而不是一张宣传海报。
 */
@Composable
private fun WelcomeScreenLayout(onAction: (WelcomeAction) -> Unit) {
    RootLayout(padding = PaddingValues(horizontal = MaterialTheme.spacings.large)) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().align(Alignment.CenterStart)
        ) {
            SetupBrandMark(markSize = 56.dp, subtitle = stringResource(CoreR.string.app_tagline))

            Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))

            Text(
                text = stringResource(SetupR.string.welcome),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
            Text(
                text = stringResource(SetupR.string.welcome_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))

            Button(
                onClick = { onAction(WelcomeAction.OnContinueClick) },
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding =
                    PaddingValues(
                        horizontal = MaterialTheme.spacings.large,
                        vertical = MaterialTheme.spacings.medium,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(SetupR.string.welcome_btn_continue),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
            Text(
                text = stringResource(SetupR.string.welcome_btn_learn_more),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.align(Alignment.CenterHorizontally)
                        .clickable { onAction(WelcomeAction.OnLearnMoreClick) }
                        .padding(MaterialTheme.spacings.small),
            )
        }
    }
}

@PreviewScreenSizes
@Composable
private fun WelcomeScreenLayoutPreview() {
    CinefinTheme { WelcomeScreenLayout(onAction = {}) }
}
