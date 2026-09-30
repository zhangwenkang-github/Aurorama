package com.zhangwenkang.cinefin.book.presentation.reader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID

/**
 * W1 阅读器 PoC 入口。
 *
 * 通过 adb 显式启动（W2 接入 NavigationRoot 后改为非 exported）： `adb shell am start -n
 * <applicationId>/com.zhangwenkang.cinefin.book.presentation.reader.ReaderActivity -e itemId
 * <uuid>`
 */
@AndroidEntryPoint
class ReaderActivity : AppCompatActivity() {
    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)?.toItemIdOrNull()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "阅读器 PoC" }

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            ReaderScreen(
                state = state,
                title = title,
                onLocationChanged = viewModel::onLocationChanged,
                onRetry = viewModel::retry,
            )
        }

        if (itemId != null) {
            viewModel.open(itemId)
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_TITLE = "title"
    }
}

/** Jellyfin REST 的 UUID 有两种写法：36 位标准格式与 32 位无连字符格式。 */
private fun String.toItemIdOrNull(): UUID? {
    if (matches(Regex("[0-9a-fA-F]{32}"))) {
        val dashed =
            "${substring(0, 8)}-${substring(8, 12)}-${substring(12, 16)}-" +
                "${substring(16, 20)}-${substring(20, 32)}"
        return runCatching { UUID.fromString(dashed) }.getOrNull()
    }
    return runCatching { UUID.fromString(this) }.getOrNull()
}
