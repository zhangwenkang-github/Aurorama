package com.zhangwenkang.cinefin.player.local.domain

import android.os.Build
import androidx.media3.common.Tracks
import com.zhangwenkang.cinefin.language.LanguageMatcher
import java.util.Locale

fun List<Tracks.Group>.getTrackNames(): Array<String> {
    return this.map { group ->
            val nameParts: MutableList<String?> = mutableListOf()
            val format = group.mediaTrackGroup.getFormat(0)
            nameParts.run {
                add(format.label)
                add(
                    format.language?.let { language ->
                        // 已知语言直接显示中文名，其余回退到系统语言名
                        LanguageMatcher.normalize(language)?.let { LanguageMatcher.displayName(it) }
                            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                                Locale.of(language.split("-").last()).displayLanguage
                            } else {
                                @Suppress("DEPRECATION")
                                Locale(language.split("-").last()).displayLanguage
                            }
                    }
                )
                add(format.codecs)
                filterNotNull().joinToString(separator = " - ")
            }
        }
        .toTypedArray()
}
