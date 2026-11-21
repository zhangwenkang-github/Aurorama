package com.zhangwenkang.cinefin.book.presentation.reader

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.EntryPointAccessors
import timber.log.Timber

/**
 * 音量键翻页 / 方向锁定的单一真相源（W6 T4）。
 *
 * 与 T3 的翻页动画设置同一模式：键自持在 `modes/book`（[ReaderInputPreferences]），读写直接落 [AppPreferences]； Activity 在
 * `onCreate` 里用注入的实例绑定一次，设置面板与读侧共享同一份 Compose 快照状态。
 */
internal object ReaderInputSetting {
    private var preferences: AppPreferences? = null

    /** 「音量键翻页」；默认关，关闭时音量键留给系统调音量。 */
    var volumeKeysPaging by mutableStateOf(false)
        private set

    /** 方向锁定三档；默认跟随系统。 */
    var orientation by mutableStateOf(ReaderOrientation.FollowSystem)
        private set

    /** Activity 注册的方向变更回调：面板改档即时下发，无需重建 Activity。 */
    var onOrientationChanged: ((ReaderOrientation) -> Unit)? = null

    /** Compose 侧绑定（面板 / 阅读视图首次组合时调用）；重复调用无副作用。 */
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
                .onFailure { Timber.w(it, "阅读输入偏好不可用，沿用默认值") }
                .getOrNull() ?: return
        bind(resolved)
    }

    /** Activity 侧绑定（已由 Hilt 注入的实例，避免再走 EntryPoint）。 */
    fun bind(appPreferences: AppPreferences) {
        if (preferences === appPreferences) return
        preferences = appPreferences
        volumeKeysPaging = appPreferences.getValue(ReaderInputPreferences.volumeKeysPaging)
        orientation =
            ReaderOrientation.fromStorage(
                appPreferences.getValue(ReaderInputPreferences.orientation)
            )
    }

    fun updateVolumeKeysPaging(enabled: Boolean) {
        volumeKeysPaging = enabled
        preferences?.setValue(ReaderInputPreferences.volumeKeysPaging, enabled)
    }

    fun updateOrientation(next: ReaderOrientation) {
        orientation = next
        preferences?.setValue(ReaderInputPreferences.orientation, next.storageValue)
        onOrientationChanged?.invoke(next)
    }
}

/** 面板与阅读视图共用的「音量键翻页」开关；首次组合时从偏好恢复。 */
@Composable
internal fun rememberReaderVolumeKeysPaging(): Boolean {
    val context = LocalContext.current
    LaunchedEffect(context) { ReaderInputSetting.bind(context) }
    return ReaderInputSetting.volumeKeysPaging
}

/** 面板与阅读视图共用的方向锁定档位；首次组合时从偏好恢复。 */
@Composable
internal fun rememberReaderOrientation(): ReaderOrientation {
    val context = LocalContext.current
    LaunchedEffect(context) { ReaderInputSetting.bind(context) }
    return ReaderInputSetting.orientation
}
