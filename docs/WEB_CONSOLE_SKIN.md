# 服务器控制台与 Web 端视觉统一（影阁皮肤）

## 目标

App 内的「服务器控制台」直接复用服务器自带的 Jellyfin Web 界面（元数据管理、插件、日志等上百个
设置页重写不现实），但 Web 界面默认是 Jellyfin 自己的观感（当前服务器主题为 WMC，蓝底 + `#00a4dc`），
与 App 的影阁风格割裂。本方案用**纯 CSS 重绘**把两边统一：墨底（`#0b0c0e`）、宣纸白（`#f1ede6`）、
朱砂（`#d2553c`）、发丝线（`#24282c`）、无阴影、胶囊按钮。

> 只改外观。**不触碰任何媒体、元数据、用户数据**。

## 组成

| 位置 | 作用 |
|------|------|
| `app/phone/src/main/res/raw/web_console_skin.css` | App 内注入（每次页面加载都补一层），随 App 发布 |
| `docs/web-console-skin.css` | 皮肤的唯一权威副本（改这里，再同步到 raw 与服务器） |
| 服务器「控制台 → 品牌 → 自定义 CSS」 | 同一份 CSS + 头部注释，桌面浏览器用户页生效 |

服务器端当前值（2026-09-27 更新）：

```
@import url("https://cdn.jsdelivr.net/gh/lscambo13/ElegantFin@main/Theme/ElegantFin-jellyfin-theme-build-latest-minified.css");
……影阁皮肤（docs/web-console-skin.css 全文）……
```

ElegantFin 保留在首行作为底层排版补充，影阁皮肤在其后加载并覆盖配色。

## 为什么要「注入 + 提权 + 保序」

实测（Jellyfin Web 10.11.8，`main.jellyfin.bundle.js`）：

1. React + MUI 主题用 CSS 变量，前缀 `jf`：
   `cssVariables:{cssVarPrefix:"jf", colorSchemeSelector:'[data-theme="%s"]'}`，
   可用 `--jf-palette-*` 覆盖，并存在 112 个 `MuiXxx-*` 类名（`.MuiCard-root`、`.MuiDrawer-paper` …）。
2. 主题样式 `themes/<主题名>/theme.css`（如 `wmc`）由前端**运行期**再插到 `<head>` 末尾，
   里面有 `.backgroundContainer{background:linear-gradient(#0f3562,#1162a4,#03215f)}`、
   `.mainDrawer{background-color:#0f3562}`、`a[data-role=button]{background:#082845!important}` 等规则。
   如果皮肤先落地，同优先级会被主题盖住。

对策（皮肤里的三条硬性写法）：

* 所有覆盖选择器加 `html body` 前缀提高权重，并统一 `!important`；
* 同时覆盖 MUI 变量/组件与 legacy 类名（`.backgroundContainer`、`.emby-*`、`.cardBox` 等）；
* App 侧注入脚本用 `MutationObserver` 把 `#cinefin-skin` 始终保持在 `<head>` 最后一个子节点。

## 自动登录（不再闪代码）

控制台需要「打开即已登录」。做法是先用 `shouldInterceptRequest` 返回一个同源空白种子页
（`/cinefin-seed`），在种子页里把 `localStorage.jellyfin_credentials` 写好，再跳转
`/web/#/dashboard`。种子页是本地生成的空白 HTML，因此不会再出现过去「页面显示 manifest.json 源码」的问题。

## 已知限制

* Jellyfin 10.11 的**管理后台路由（`#/dashboard`）在桌面浏览器里不加载服务器自定义 CSS**：
  前端只在「用户站点」根组件里渲染 `<style>{BrandingOptions.CustomCss}</style>`，
  后台路由只挂 `themes/<dashboardTheme>/theme.css`。
  → App 内因此改为自行注入同一份皮肤，控制台（后台）同样是影阁风格；
  → 桌面浏览器若要后台也统一，需要往服务器 `web/themes/` 增加主题文件（超出「只改自定义 CSS」的范围）。
* 令牌失效时不再写入凭据，控制台会显示 Jellyfin 自己的登录页（该页也已被皮肤覆盖）。

## 回滚

把服务器「品牌 → 自定义 CSS」整段替换回一行即可恢复原状：

```
@import url("https://cdn.jsdelivr.net/gh/lscambo13/ElegantFin@main/Theme/ElegantFin-jellyfin-theme-build-latest-minified.css");
```

原始备份也留在工作区 `_logs/branding_backup.json`、`_logs/branding_backup2.json`。

## 验证

* 冷启动进入控制台连拍 36 帧（约 28s）：无白屏、无源码页，全部为深色/皮肤色（`_logs/burst2/`）。
* App 内控制台、常规、品牌、用户、显示、活动、插件等页面截图（`_logs/web_*.png`）。
* 桌面浏览器（Edge headless CDP）用户站点实测：`#/home` 应用皮肤成功（`<style>` 21617 字符），
  后台 `#/dashboard` 如「已知限制」所述不加载。

## 维护提示

改皮肤的标准流程：

1. 编辑 `docs/web-console-skin.css`；
2. 复制到 `app/phone/src/main/res/raw/web_console_skin.css`；
3. 通过服务器 API 写入（只改 `CustomCss` 字段）：

```powershell
$apiKey = '<API Key>'
$h = @{ Authorization = "MediaBrowser Token=`"$apiKey`"" }
$css = (Get-Content -Raw docs/web-console-skin.css)
$body = @{ LoginDisclaimer=''; CustomCss=$css; SplashscreenEnabled=$false } | ConvertTo-Json -Depth 3
Invoke-WebRequest -Uri 'https://<服务器>/System/Configuration/Branding' -Method Post -Headers $h `
  -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

4. 验证：`GET /Branding/Configuration`（公开接口，Web 端真正读取的就是它）应返回新长度。

调试技巧：debug 版 App 打开了 WebView 远程调试，取到 socket 名后
`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`，
即可用 `_tools/cdp.mjs` 检查真实 DOM / 计算样式并热注入 CSS。
