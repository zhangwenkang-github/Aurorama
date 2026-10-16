package com.zhangwenkang.cinefin.presentation.player

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zhangwenkang.cinefin.player.local.R
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.settings.domain.PlaybackSpeedOptions
import java.lang.IllegalStateException

class SpeedSelectionDialogFragment(private val viewModel: PlayerViewModel) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // W78：档位表与播放页面板 / 设置页共用同一份常量（PlaybackSpeedOptions.PLAYBACK）
        val speedNumbers = PlaybackSpeedOptions.PLAYBACK
        val speedTexts = speedNumbers.map(PlaybackSpeedOptions::label)

        return activity?.let { activity ->
            val builder = MaterialAlertDialogBuilder(activity)
            builder.setTitle(getString(R.string.select_playback_speed)).setSingleChoiceItems(
                speedTexts.toTypedArray(),
                speedNumbers.indexOf(viewModel.playbackSpeed),
            ) { dialog, which ->
                viewModel.selectSpeed(speedNumbers[which])
                dialog.dismiss()
            }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fix for hiding the system bars on API < 30
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
