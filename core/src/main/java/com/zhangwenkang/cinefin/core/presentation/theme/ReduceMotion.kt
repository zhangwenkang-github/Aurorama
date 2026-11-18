package com.zhangwenkang.cinefin.core.presentation.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 「减少动画」偏好：系统动画缩放（`Settings.Global.ANIMATOR_DURATION_SCALE`）为 0 时视为开启。
 *
 * 项目此前没有独立的减少动画开关，定稿要求直接读取系统开发者选项，因此这里作为唯一来源； 监听设置变化，用户在系统里关掉动画后无需 重启即可退化导航动效。
 */
@Composable
fun rememberReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    var reduceMotion by remember(context) { mutableStateOf(context.isReduceMotionEnabled()) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduceMotion = context.isReduceMotionEnabled()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        reduceMotion = context.isReduceMotionEnabled()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduceMotion
}

private fun Context.isReduceMotionEnabled(): Boolean =
    Settings.Global.getFloat(
        contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        /* def = */ 1f,
    ) == 0f
