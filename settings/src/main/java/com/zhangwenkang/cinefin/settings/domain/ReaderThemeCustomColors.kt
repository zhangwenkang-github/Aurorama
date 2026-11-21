package com.zhangwenkang.cinefin.settings.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 阅读主题的自定义色（W6-T5 / READER-17）。
 *
 * 值 = ARGB 整数（[android.graphics.Color] 口径）；`null` = 沿用该主题的默认 token。链接色与高亮色不在其中（跟随 accent，定稿四 §3）。
 */
data class ReaderThemeCustomColors(val background: Int? = null, val content: Int? = null) {
    /** 两项都未自定义 = 与主题默认一致。 */
    val isDefault: Boolean
        get() = background == null && content == null

    companion object {
        val Default = ReaderThemeCustomColors()
    }
}

/**
 * 每套阅读主题自定义色的偏好键与 JSON 编解码（W6-T5，定稿四 §3）。
 *
 * 键 = `pref_reader_theme_custom_<theme>`（`<theme>` 为阅读域主题存储值），主题间互不影响； 不参与 App 主题，也不写回系统 / 服务器。
 */
object ReaderThemeCustomJson {
    fun prefName(themeStorage: String): String = "pref_reader_theme_custom_$themeStorage"

    fun encode(colors: ReaderThemeCustomColors): String = buildJsonObject {
        colors.background?.let { put("background", it) }
        colors.content?.let { put("content", it) }
    }
        .toString()

    /** 非法 / 空串 / 缺字段一律回落默认（不抛异常，阅读链路不因一条坏偏好中断）。 */
    fun decode(raw: String?): ReaderThemeCustomColors {
        if (raw.isNullOrBlank()) return ReaderThemeCustomColors()
        return runCatching {
                val element =
                    Json.parseToJsonElement(raw) as? JsonObject ?: return ReaderThemeCustomColors()
                ReaderThemeCustomColors(
                    background = element["background"].asArgbOrNull(),
                    content = element["content"].asArgbOrNull(),
                )
            }
            .getOrDefault(ReaderThemeCustomColors())
    }

    private fun JsonElement?.asArgbOrNull(): Int? =
        (this as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
}
