package com.zhangwenkang.cinefin.player.local.diag

import android.util.Log
import com.zhangwenkang.cinefin.player.local.BuildConfig

/** Playback diagnostic logger for on-device root-causing. No-op outside debug builds. */
object PlaybackDiag {
    private const val TAG = "AuroraPlayDiag"
    private val ENABLED = BuildConfig.DEBUG

    fun log(message: String) {
        if (!ENABLED) return
        // android.util.Log is not available in JVM unit tests; never let diagnostics crash callers.
        runCatching { Log.w(TAG, message) }
    }
}
