package com.zhangwenkang.cinefin.presentation.console

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.BuildConfig
import com.zhangwenkang.cinefin.R as AppR
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject

/**
 * 服务器控制台：直接加载服务器自带的 Web 客户端。
 *
 * 官方客户端对控制台、元数据管理、插件、日志等海量服务端功能也是这么处理的—— 与其把上百个设置页重写一遍，不如复用服务器端已有的 Web 控制台，
 * 再把当前登录态注入进去，用户打开即是已登录状态。
 *
 * 与 App 的融合做了三件事：
 * 1. 没有 App 自己的标题栏——控制台铺满整屏，返回交给系统回退手势/返回键；
 * 2. 登录态通过一个"同源空白种子页"写入 localStorage，不再闪现 manifest.json 的代码；
 * 3. 每次页面加载都注入影阁皮肤（墨底 + 朱砂 + 发丝线）， 与 App 内的设置页、抽屉是同一套语言，不会出现"两个应用"的割裂感。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebConsoleScreen(
    /** 进入后台的哪一页：`/dashboard` 控制台、`/metadata` 媒体资料管理器、`/details?id=…` 详情页 */
    initialPath: String = "/dashboard",
    onBack: () -> Unit,
    viewModel: ConsoleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val safePadding = rememberSafePadding()
    val context = LocalContext.current

    // Web 客户端地址：控制台内部的所有跳转都发生在同一个 WebView 里
    val consoleUrl =
        remember(state.baseUrl, initialPath) { state.baseUrl.trimEnd('/') + "/web/#" + initialPath }

    val skinCss = remember {
        context.resources.openRawResource(AppR.raw.web_console_skin).use {
            it.bufferedReader().readText()
        }
    }

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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (state.loaded) {
            AndroidView(
                // 上下留出系统栏安全区，左右铺满；底色由 App 提供，页面与状态栏之间没有接缝
                modifier =
                    Modifier.fillMaxSize()
                        .padding(top = safePadding.top, bottom = safePadding.bottom),
                factory = { ctx ->
                    if (BuildConfig.DEBUG) {
                        // 仅调试构建开放 WebView 远程调试，便于用 CDP 核对控制台真实 DOM 与主题变量
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = false
                        settings.mediaPlaybackRequiresUserGesture = false
                        setBackgroundColor(android.graphics.Color.parseColor("#0B0C0E"))
                        webChromeClient = WebChromeClient()
                        webViewClient =
                            ConsoleWebViewClient(
                                serverHost = state.baseUrl.toHost(),
                                seedUrl = state.credentialsSeedUrl,
                                consoleUrl = consoleUrl,
                                // 令牌失效时不写入凭据，让用户能直接在网页里重新登录
                                credentialsScript =
                                    if (state.appTokenValid) buildCredentialsScript(state) else "",
                                skinScript = buildSkinScript(skinCss),
                                onLoadingChanged = { loading -> isLoading = loading },
                            )
                        // 先加载同源种子页写入登录态（页面本身不可见），再进入控制台
                        loadUrl(state.credentialsSeedUrl)
                        webView = this
                    }
                },
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 页面切换时只有一条 2dp 的朱砂细线，不占用版面
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }

        // 兜底入口：网页状态异常时可以重新载入
        webView?.let { view ->
            IconButton(
                onClick = { view.reload() },
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor =
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(
                            end = safePadding.end + MaterialTheme.spacings.medium,
                            bottom = safePadding.bottom + MaterialTheme.spacings.medium,
                        )
                        .size(40.dp),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun String.toHost(): String = runCatching { Uri.parse(this).host }.getOrNull().orEmpty()

/** 种子页地址：同源但不存在的路径，由 [ConsoleWebViewClient] 直接拦截返回空白页。 */
private val ConsoleState.credentialsSeedUrl: String
    get() = baseUrl.trimEnd('/') + "/cinefin-seed"

/**
 * 注入 Jellyfin Web 的登录凭据（localStorage 的 `jellyfin_credentials`， 与服务器端 apiclient
 * 使用的键保持一致），使控制台打开即为已登录状态。
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

/**
 * 每次页面加载都补一层影阁皮肤，重复注入时覆盖同一节点，不会叠加。
 *
 * 注意：Jellyfin 的主题样式（themes/<name>/theme.css）由前端在运行期后插到 <head> 末尾，
 * 如果在它之前落地，同优先级规则会被主题覆盖。所以这里除了提高选择器权重， 还把皮肤节点始终保持在 <head> 的最后一个子节点上（MutationObserver 跟随）。
 */
private fun buildSkinScript(css: String): String {
    val quoted = JSONObject.quote(css)
    return """
        (function(){
          try{
            var id='cinefin-skin';
            var head=document.head||document.documentElement;
            var el=document.getElementById(id);
            if(!el){
              el=document.createElement('style');
              el.id=id;
            }
            if(el.textContent !== $quoted){ el.textContent = $quoted; }
            keepLast();
            if(!window.__cinefinSkinWatching){
              window.__cinefinSkinWatching = true;
              new MutationObserver(keepLast).observe(head, {childList:true});
            }
            function keepLast(){
              if(head.lastElementChild !== el){ head.appendChild(el); }
            }
          }catch(e){}
        })()
        """
        .trimIndent()
}

/** 种子页：同源、空白、不可见，只负责把登录态写进 localStorage。 */
private fun buildSeedHtml(credentialsScript: String): String =
    """
    <!doctype html>
    <html><head><meta charset="utf-8">
    <style>html,body{margin:0;padding:0;background:#0B0C0E;}</style>
    </head><body><script>$credentialsScript</script></body></html>
    """
        .trimIndent()

private class ConsoleWebViewClient(
    private val serverHost: String,
    private val seedUrl: String,
    private val consoleUrl: String,
    private val credentialsScript: String,
    private val skinScript: String,
    private val onLoadingChanged: (Boolean) -> Unit,
) : WebViewClient() {
    private var seedHandled = false

    /**
     * 种子页只是写登录态的跳板，不该留在 WebView 历史里：否则控制台里按一次系统返回会先落到空白种子页。 标记在种子页 `onPageFinished`
     * 时置位，等控制台页真正加载完再清一次历史（此时清掉的只有种子页那一步）。
     */
    private var pendingSeedHistoryTrim = false

    /**
     * 种子页不发真实请求：直接在本地生成一个同源的空白页面。 这样既能把凭据写进该源的 localStorage，又不会像以前那样把 manifest.json 的原始代码显示在屏幕上。
     */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (url.startsWith(seedUrl)) {
            return WebResourceResponse(
                "text/html",
                "utf-8",
                buildSeedHtml(credentialsScript).byteInputStream(),
            )
        }
        return null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingChanged(true)
        // 尽量早地套上皮肤，避免先看到一帧 Jellyfin 默认蓝
        if (url != null && !url.startsWith(seedUrl)) {
            view?.evaluateJavascript(skinScript, null)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (view == null) return

        if (!seedHandled && url != null && url.startsWith(seedUrl)) {
            seedHandled = true
            pendingSeedHistoryTrim = true
            // App 侧令牌已失效时不再覆盖，让用户能直接在网页里登录
            view.loadUrl(consoleUrl)
            return
        }

        if (pendingSeedHistoryTrim) {
            pendingSeedHistoryTrim = false
            view.clearHistory()
        }

        onLoadingChanged(false)
        view.evaluateJavascript(skinScript, null)
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
