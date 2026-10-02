package com.zhangwenkang.cinefin.core.presentation.theme

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import java.util.concurrent.ConcurrentHashMap

/**
 * MiSans 可变字体（官方原文件 `assets/fonts/MiSansVF.ttf`，未改编 / 未子集化 / 未改名）。
 *
 * 许可依据（《MiSans 字体知识产权许可协议》，2026-10-03 自官方下载页许可协议节逐条核验，全文见 `assets/licenses/MiSans-License.txt`）：
 * - ①「您应在软件中特别注明使用了 MiSans 字体」→「客户端设置 → 关于」常显注明（`misans_attribution` 中英）；
 * - ②「不得对 MiSans 字体或其任何单独组件进行改编或二次开发」→ 文件按官方字节原样入库，不做子集化 / 改名 / 转格式，也不另存派生字体；
 * - ③「不得**单独**…进一步分发字体软件或其任何副本…此限制不适用于您使用 MiSans 字体创作的任何其他作品。 如您使用 MiSans 字体创作…应用 App
 *   等，您有权分发或出售该作品」→ 随本 App 内嵌分发属于「其他作品」，合规。
 *
 * 为什么不用 Compose 的 assets 重载 [androidx.compose.ui.text.font.Font]（`Font(path, assetManager, …)`）：
 * `CinefinType` 是静态字阶 token（在组合之外构造），构造期拿不到 `AssetManager`。`AndroidFont` 是官方提供的 低层扩展点：字体由 Compose
 * 在解析期传入 `Context` 时再加载（`AndroidFontLoader` 走 `context.applicationContext`）， 因此静态 token、Compose
 * 预览与两个 App 壳（phone / TV）都无需全局可变状态；底层仍与 Compose 官方 `AndroidAssetFont` 同路径：`Typeface.Builder(assets,
 * path)` + `setFontVariationSettings`。
 */
internal class MisansFont(
    override val weight: FontWeight,
    override val style: FontStyle = FontStyle.Normal,
) :
    AndroidFont(
        loadingStrategy = FontLoadingStrategy.Blocking,
        typefaceLoader = MisansTypefaceLoader,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

private const val MisansAssetPath = "fonts/MiSansVF.ttf"

/** 解析期加载器：按可变轴字符串缓存 Typeface，四档字重共用同一份字体文件。 */
private object MisansTypefaceLoader : AndroidFont.TypefaceLoader {
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
        val variation = font.variationSettings.toAndroidVariationString(context)
        return typefaces.computeIfAbsent(variation) { buildTypeface(context.assets, it) }
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
        loadBlocking(context, font)

    private fun buildTypeface(assets: AssetManager, variation: String): Typeface =
        Typeface.Builder(assets, MisansAssetPath)
            .apply { setFontVariationSettings(variation) }
            .build()
}

/**
 * 把 [FontVariation.Settings] 转成 Android `Typeface.Builder.setFontVariationSettings` 接受的 CSS 风格字符串（如
 * `'wght' 600`）。与 Compose 官方 `toAndroidString` 一致：wght 轴叠加系统 「字体粗细调整」（Android 12+）后夹到 [1, 1000]。
 */
internal fun FontVariation.Settings.toAndroidVariationString(
    density: Density,
    weightAdjustment: Float,
): String =
    settings.joinToString(", ") { setting ->
        val value =
            if (setting.axisName == "wght") {
                (setting.toVariationValue(density) + weightAdjustment).coerceIn(1f, 1000f)
            } else {
                setting.toVariationValue(density)
            }
        "'${setting.axisName}' $value"
    }

private fun FontVariation.Settings.toAndroidVariationString(context: Context): String {
    val density =
        Density(
            context.resources.displayMetrics.density,
            context.resources.configuration.fontScale,
        )
    val weightAdjustment =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.resources.configuration.fontWeightAdjustment.toFloat()
        } else {
            0f
        }
    return toAndroidVariationString(density, weightAdjustment)
}
