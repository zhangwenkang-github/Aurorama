package com.zhangwenkang.cinefin.presentation.console

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.theme.Motion
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
 *
 * 为了和 App 融为一体：
 * - 顶栏颜色取自网页顶部实际配色（跟随服务器自定义主题），并做平滑过渡；
 * - 去掉网页头部的阴影与分隔线，避免出现“两层皮”的接缝。
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
    var webThemeColor by remember { mutableStateOf<Color?>(null) }

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

    val barColor by
        animateColorAsState(
            targetValue = webThemeColor ?: MaterialTheme.colorScheme.surface,
            animationSpec = tween(Motion.durationMedium, easing = Motion.standard),
            label = "consoleBarColor",
        )
    // 跟随网页配色自动切换前景色，浅色主题下用深色文字
    val onBarColor = if (barColor.luminance() > 0.5f) Color.Black else Color.White

    Column(modifier = Modifier.fillMaxSize().background(barColor)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(barColor)
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
                    tint = onBarColor,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreR.string.title_console),
                    style = MaterialTheme.typography.titleMedium,
                    color = onBarColor,
                )
                if (state.serverName.isNotBlank()) {
                    Text(
                        text = state.serverName,
                        style = MaterialTheme.typography.bodySmall,
                        color = onBarColor.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { webView?.reload() }) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                    contentDescription = null,
                    tint = onBarColor,
                )
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.loaded) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            // 遵循网页自身的 viewport（width=device-width），
                            // 否则平板会被当成桌面宽视口，登录页等页面会错位
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.useWideViewPort = false
                            settings.loadWithOverviewMode = false
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = WebChromeClient()
                            webViewClient =
                                ConsoleWebViewClient(
                                    serverHost = state.baseUrl.toHost(),
                                    consoleUrl = state.consoleUrl,
                                    credentialsScript = buildCredentialsScript(state),
                                    shouldSeedCredentials = state.appTokenValid,
                                    onThemeColor = { color -> webThemeColor = color },
                                    onLoadingChanged = { loading -> isLoading = loading },
                                )
                            // 先加载同源页面写入登录态，再进入控制台
                            loadUrl(state.credentialsSeedUrl)
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
    runCatching { Uri.parse(this).host }.getOrNull().orEmpty()

/** 控制台地址（含登录态预置与页面美化脚本） */
private val ConsoleState.credentialsSeedUrl: String
    get() = baseUrl.trimEnd('/') + "/web/manifest.json"

/**
 * 注入 Jellyfin Web 的登录凭据（localStorage 的 `jellyfin_credentials`，
 * 与服务器端 apiclient 使用的键保持一致），使控制台打开即为已登录状态。
 */
private fun buildCredentialsScript(state: ConsoleState): String {
    val server =
        JsonObject(
            mapOf(
                "Id" to JsonPrimitive(state.serverId),
                "Name" to JsonPrimitive(state.serverName),
                "AccessToken" to JsonPrimitive(state.accessToken.orEmpty()),
                "UserId" to JsonPrimitive(state.userId.orEmpty()),
                "ManualAddress" to JsonPrimitive(state.baseUrl.trimEnd('/')),
                "LastConnectionMode" to JsonPrimitive(1),
                "DateLastAccessed" to JsonPrimitive(System.currentTimeMillis()),
            )
        )
    // 直接内联 JSON 对象字面量（JSON 是 JS 对象字面量的子集），
    // 注意不能用字符串包裹，否则会往数组里塞进一个字符串而不是对象。
    val entryLiteral = server.toString().replace("\u2028", "").replace("\u2029", "")
    return """
        (function(){
          try{
            var incoming = $entryLiteral;
            var raw = localStorage.getItem('jellyfin_credentials');
            var creds = raw ? JSON.parse(raw) : {};
            if(!creds || typeof creds !== 'object') creds = {};
            if(!Array.isArray(creds.Servers)) creds.Servers = [];
            // 清理历史遗留的非法条目（例如被误写成字符串的记录）
            creds.Servers = creds.Servers.filter(function(s){
              return s && typeof s === 'object' && s.Id;
            });
            var idx = -1;
            for(var i=0;i<creds.Servers.length;i++){
              if(creds.Servers[i] && creds.Servers[i].Id === incoming.Id){ idx = i; break; }
            }
            if(idx >= 0){
              for(var k in incoming){ creds.Servers[idx][k] = incoming[k]; }
            } else {
              creds.Servers.push(incoming);
            }
            localStorage.setItem('jellyfin_credentials', JSON.stringify(creds));
          }catch(e){}
        })()
        """
        .trimIndent()
}

/** 采样网页顶部背景色，并顺手去掉网页头部的阴影/描边，避免与应用顶栏出现接缝 */
private const val THEME_COLOR_SCRIPT =
    "(function(){try{" +
        "var style=document.getElementById('cinefin-blend');" +
        "if(!style){style=document.createElement('style');style.id='cinefin-blend';" +
        "style.textContent='.skinHeader,.MuiAppBar-root{box-shadow:none !important;border-bottom:none !important;}';" +
        "document.head&&document.head.appendChild(style);}" +
        "function pick(sel){var el=document.querySelector(sel);if(!el)return '';" +
        "var c=getComputedStyle(el).backgroundColor||'';" +
        "if(!c||c==='transparent'||c.indexOf('rgba(0, 0, 0, 0)')===0)return '';return c;}" +
        "var list=['.skinHeader','.MuiAppBar-root','header','#reactRoot','body'];" +
        "for(var i=0;i<list.length;i++){var c=pick(list[i]);if(c)return c;}" +
        "return '';}catch(e){return '';}})()"

private fun parseCssColor(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    val cleaned = raw.trim().removeSurrounding("\"")
    val numbers = Regex("\\d+").findAll(cleaned).map { it.value.toIntOrNull() ?: 0 }.toList()
    if (numbers.size < 3) return null
    val alpha = numbers.getOrNull(3)?.let { it } ?: 255
    if (alpha == 0) return null
    return Color(
        red = numbers[0],
        green = numbers[1],
        blue = numbers[2],
        alpha = alpha,
    )
}

private class ConsoleWebViewClient(
    private val serverHost: String,
    private val consoleUrl: String,
    private val credentialsScript: String,
    private val shouldSeedCredentials: Boolean,
    private val onThemeColor: (Color?) -> Unit,
    private val onLoadingChanged: (Boolean) -> Unit,
) : WebViewClient() {
    private var seedHandled = false

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingChanged(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (view == null) return

        if (!seedHandled) {
            // 首次进入：在服务器同源文档里预置登录态，然后加载控制台。
            // App 侧令牌已失效时不再覆盖，让用户能直接在网页里登录。
            seedHandled = true
            if (shouldSeedCredentials) {
                view.evaluateJavascript(credentialsScript) { view.loadUrl(consoleUrl) }
            } else {
                view.loadUrl(consoleUrl)
            }
            return
        }

        onLoadingChanged(false)

        // 采样网页配色，让 App 顶栏与网页自然衔接
        view.evaluateJavascript(THEME_COLOR_SCRIPT) { result ->
            onThemeColor(parseCssColor(result))
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
        // 只对当前配置的服务器放行自签名证书，其他站点保持严格校验
        val errorHost = runCatching { Uri.parse(error.url).host }.getOrNull()
        if (errorHost != null && errorHost == serverHost) {
            handler.proceed()
        } else {
            super.onReceivedSslError(view, handler, error)
        }
    }
}
