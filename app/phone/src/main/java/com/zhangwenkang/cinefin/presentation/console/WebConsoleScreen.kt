package com.zhangwenkang.cinefin.presentation.console

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 服务器控制台：直接加载服务器自带的 Web 客户端。
 *
 * 官方客户端对控制台、元数据管理、插件、日志等海量服务端功能也是这么处理的——
 * 与其把上百个设置页重写一遍，不如复用服务器端已有的 Web 控制台，
 * 再把当前登录态注入进去，用户打开即是已登录状态。
 */
@Composable
fun WebConsoleScreen(
    onBack: () -> Unit,
    viewModel: ConsoleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val safePadding = rememberSafePadding(handleStartInsets = false)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { viewModel.load() }

    // 系统返回键优先在控制台内部后退（网页历史），退无可退再离开控制台
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            onBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = safePadding.start + MaterialTheme.spacings.small,
                        top = safePadding.top + MaterialTheme.spacings.small,
                        end = safePadding.end + MaterialTheme.spacings.small,
                    )
                    .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_left),
                    contentDescription = null,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreR.string.title_console),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.serverName.isNotBlank()) {
                    Text(
                        text = state.serverName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { webView?.reload() }) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                    contentDescription = null,
                )
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.loaded) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webChromeClient = WebChromeClient()
                            webViewClient =
                                ConsoleWebViewClient(
                                    serverHost = state.baseUrl.toHost(),
                                    credentialsScript = buildCredentialsScript(state),
                                    accessToken = state.accessToken,
                                    onLoadingChanged = { loading -> isLoading = loading },
                                )
                            loadUrl(state.consoleUrl)
                            webView = this
                        }
                    },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

private fun String.toHost(): String =
    runCatching { android.net.Uri.parse(this).host }.getOrNull().orEmpty()

/**
 * 注入 Jellyfin Web 的登录凭据（localStorage 中的 jellyfin_credentials），
 * 使 Web 控制台打开即为已登录状态，无需再次输入账号密码。
 */
private fun buildCredentialsScript(state: ConsoleState): String {
    val server =
        JsonObject(
            mapOf(
                "Id" to JsonPrimitive(state.serverId),
                "Name" to JsonPrimitive(state.serverName),
                "AccessToken" to JsonPrimitive(state.accessToken.orEmpty()),
                "UserId" to JsonPrimitive(state.userId.orEmpty()),
                "ManualAddress" to JsonPrimitive(state.baseUrl),
                "LastConnectionMode" to JsonPrimitive(1),
                "DateLastAccessed" to JsonPrimitive(System.currentTimeMillis()),
            )
        )
    val credentials = JsonObject(mapOf("Servers" to JsonArray(listOf(server))))
    // 用 JSON 字符串字面量包裹，避免服务器名里的引号破坏脚本
    val literal = JsonPrimitive(credentials.toString()).toString()
    return "try{localStorage.setItem('jellyfin_credentials', $literal);}catch(e){}"
}

private class ConsoleWebViewClient(
    private val serverHost: String,
    private val credentialsScript: String,
    private val accessToken: String?,
    private val onLoadingChanged: (Boolean) -> Unit,
) : WebViewClient() {
    private var retriedWithCredentials = false

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingChanged(true)
        // 在 Web 客户端初始化之前写入登录态
        view?.evaluateJavascript(credentialsScript, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onLoadingChanged(false)
        if (view == null || accessToken.isNullOrBlank()) return

        // 校验是否已成功登录；若仍是登录页，则补写一次凭据并刷新
        view.evaluateJavascript(
            "(function(){try{return (window.ApiClient&&ApiClient.accessToken&&ApiClient.accessToken())||'';}catch(e){return '';}})()"
        ) { result ->
            val currentToken = result?.trim('"')
            if (currentToken != accessToken && !retriedWithCredentials) {
                retriedWithCredentials = true
                view.evaluateJavascript(credentialsScript, null)
                view.reload()
            }
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
        // 只对当前配置的服务器放行自签名证书，其他站点保持严格校验
        val errorHost = runCatching { android.net.Uri.parse(error.url).host }.getOrNull()
        if (errorHost != null && errorHost == serverHost) {
            handler.proceed()
        } else {
            super.onReceivedSslError(view, handler, error)
        }
    }
}
