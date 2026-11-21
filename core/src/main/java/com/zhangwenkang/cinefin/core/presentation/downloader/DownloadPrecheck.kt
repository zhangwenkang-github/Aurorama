package com.zhangwenkang.cinefin.core.presentation.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.DownloadNetworkDecision
import com.zhangwenkang.cinefin.utils.DownloadNetworkRules
import java.util.Locale

/**
 * W7-T1 下载前置检查（纯数据 + 纯函数）。
 *
 * 所有下载入口（视频单集 / 电影 / 整剧 / 全季 / 音乐单曲 / 专辑批量 / 书籍）点击后先展示同一块提示层，
 * 确认后才入队。提示层只消费调用方**已经拿到的元数据**，不做任何网络请求——权限 / 网络类提示必须 先于任何入队请求出现（需求 V2-DOWNLOAD
 * 06/07/08/10/11/12/13/50/55）。
 */
enum class DownloadPrecheckKind {
    /** 视频单集 / 电影：标注「原始文件下载」+ 容器格式 + 文件大小。 */
    VIDEO,
    /** 整剧 / 全季：并入批量确认框（数量行 + 合计大小 + 空间预检）。 */
    VIDEO_BATCH,
    /** 音乐单曲：曲名 / 艺术家 / 格式 / 大小。 */
    AUDIO,
    /** 专辑 / 多选批量：将下载 N 首 + 合计大小。 */
    AUDIO_BATCH,
    /** 书籍：格式 +「下载后可在书架离线阅读」。 */
    BOOK,
}

/**
 * 提示层内容。
 *
 * 明细行由 [DownloadPrecheckDialog] 按 [kind] 分支生成，字符串统一走 core 资源（en / zh-rCN / zh-rTW）；
 * 这里只带结构化事实，避免调用方各自拼文案。
 */
data class DownloadPrecheckContent(
    val kind: DownloadPrecheckKind,
    /** 主标题（条目 / 专辑 / 书籍名称，已由数据层给出）。 */
    val title: String,
    /** 条目数量（单条为 1；批量 / 专辑为实际待入队数量）。 */
    val itemCount: Int = 1,
    /** 已知大小之和；null = 完全未知。 */
    val sizeBytes: Long? = null,
    /** 大小未知的条目数（批量用；单条未知时置 0 且 [sizeBytes] 为 null）。 */
    val unknownSizeCount: Int = 0,
    /** 容器 / 编码格式标签（如 mkv / flac / EPUB）。 */
    val formatLabel: String? = null,
    /** 次要信息（音乐艺术家等）。 */
    val secondaryLabel: String? = null,
    /** 时长标签（有声书等能识别时才给）。 */
    val durationLabel: String? = null,
    /** 是否有外部字幕 / 封面等附属文件会一并入库。 */
    val hasSidecarFiles: Boolean = false,
)

/** 空间预检结果：需求 50 的「需要 X / 可用 Y」。 */
data class DownloadSpaceStatus(
    val requiredBytes: Long?,
    val availableBytes: Long,
) {
    val insufficient: Boolean
        get() = requiredBytes != null && requiredBytes > availableBytes
}

/** 网络预检提示：移动数据 + 未允许移动数据时，主行动改为「继续下载」并给「仅 Wi-Fi 下载」副行动。 */
enum class DownloadNetworkPrompt {
    NONE,
    CONFIRM_MOBILE_DATA,
}

object DownloadPrecheckRules {

    fun spaceStatus(requiredBytes: Long?, availableBytes: Long): DownloadSpaceStatus =
        DownloadSpaceStatus(requiredBytes = requiredBytes, availableBytes = availableBytes)

    /** 复用 W57 [DownloadNetworkRules] 的判定结果：Wi-Fi / 以太网直接放行，其余网络等待网络策略。 */
    fun networkPrompt(decision: DownloadNetworkDecision): DownloadNetworkPrompt =
        when (decision) {
            DownloadNetworkDecision.ALLOW -> DownloadNetworkPrompt.NONE
            DownloadNetworkDecision.WAIT_FOR_MOBILE_DATA,
            DownloadNetworkDecision.WAIT_FOR_ROAMING -> DownloadNetworkPrompt.CONFIRM_MOBILE_DATA
        }

    /** 人类可读大小：B / KB / MB / GB，最多一位小数（与下载页 / 书架口径一致）。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kilobytes = bytes / 1024.0
        if (kilobytes < 1024) return String.format(Locale.US, "%.0f KB", kilobytes)
        val megabytes = kilobytes / 1024.0
        if (megabytes < 1024) return String.format(Locale.US, "%.1f MB", megabytes)
        return String.format(Locale.US, "%.2f GB", megabytes / 1024.0)
    }
}

/**
 * 提示层的设备侧事实读取（可用空间 / 网络提示）。
 *
 * 只读：空间按下载目标存储位置（[Context.getExternalFilesDirs]）用 [StatFs] 取值；网络按活动网络能力 + 既有
 * `pref_downloads_mobile_data` / `pref_downloads_roaming` 偏好判定，不新增偏好键。
 */
object DownloadPrecheckEnvironment {

    /** 与 `di/SharedPreferencesModule` 同一文件（`<package>_preferences`），走既有 [AppPreferences] 读键。 */
    private fun appPreferences(context: Context): AppPreferences =
        AppPreferences(
            context.getSharedPreferences(
                context.packageName + "_preferences",
                Context.MODE_PRIVATE,
            )
        )

    /** 下载目标位置的可用字节；取不到时返回 0（按空间不足处理，避免误放行）。 */
    fun availableBytes(context: Context, storageIndex: Int): Long {
        val dirs = runCatching { context.getExternalFilesDirs(null) }.getOrNull()
        val dir = dirs?.getOrNull(storageIndex.coerceAtLeast(0)) ?: return 0L
        return runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
    }

    /** 当前网络的提示状态。无法判定（网络切换瞬间 / 系统未返回能力）时返回 [DownloadNetworkPrompt.NONE]， 与引擎「无法判定则放行」同口径。 */
    fun networkPrompt(context: Context): DownloadNetworkPrompt {
        val manager =
            runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
                ?: return DownloadNetworkPrompt.NONE
        val capabilities =
            runCatching {
                manager.activeNetwork?.let { network -> manager.getNetworkCapabilities(network) }
            }
                .getOrNull() ?: return DownloadNetworkPrompt.NONE
        val wifiOrEthernet =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val roaming =
            cellular && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        val preferences = appPreferences(context)
        return DownloadPrecheckRules.networkPrompt(
            DownloadNetworkRules.decide(
                isWifiOrEthernet = wifiOrEthernet,
                isCellular = cellular,
                isRoaming = roaming,
                allowMobileData = preferences.getValue(preferences.downloadOverMobileData),
                allowRoaming = preferences.getValue(preferences.downloadWhenRoaming),
            )
        )
    }
}
