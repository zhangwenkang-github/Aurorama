package com.zhangwenkang.cinefin.book.presentation.reader

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * 翻页动画设置的单一真相源（W6 T3）。
 *
 * 键与枚举自持在 `modes/book`（[ReaderPageAnimationPreferences]）。本卡文件边界不含 `ReaderViewModel` （W6-T2
 * 并行中），所以读写在这里直接落到 [AppPreferences]：设置面板写、阅读视图读，写入即持久化。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReaderPreferencesEntryPoint {
    fun appPreferences(): AppPreferences
}

internal object ReaderPageAnimationSetting {
    private var preferences: AppPreferences? = null

    /** 当前档位；Compose 快照状态，面板与阅读视图共享同一份。 */
    var value by mutableStateOf(ReaderPageAnimation.Slide)
        private set

    /** 首次组合时从偏好恢复；重复调用无副作用，取不到 [AppPreferences] 时保持默认值。 */
    fun bind(context: Context) {
        if (preferences != null) return
        val resolved =
            runCatching {
                EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        ReaderPreferencesEntryPoint::class.java,
                    )
                    .appPreferences()
            }
                .onFailure { Timber.w(it, "翻页动画偏好不可用，沿用默认档位") }
                .getOrNull() ?: return
        preferences = resolved
        value =
            ReaderPageAnimation.fromStorage(
                resolved.getValue(ReaderPageAnimationPreferences.pageAnimation)
            )
    }

    /** 写档位并落盘（`pref_reader_page_animation`）。 */
    fun set(next: ReaderPageAnimation) {
        value = next
        preferences?.setValue(ReaderPageAnimationPreferences.pageAnimation, next.storageValue)
    }
}

/** 面板与阅读视图共用的当前档位；首次组合时从偏好恢复。 */
@Composable
internal fun rememberReaderPageAnimation(): ReaderPageAnimation {
    val context = LocalContext.current
    LaunchedEffect(context) { ReaderPageAnimationSetting.bind(context) }
    return ReaderPageAnimationSetting.value
}
