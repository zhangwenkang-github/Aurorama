package com.zhangwenkang.cinefin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidSeasonDto
import com.zhangwenkang.cinefin.models.FindroidShowDto
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.utils.ImageCacheRules
import com.zhangwenkang.cinefin.utils.ImageCacheStore
import com.zhangwenkang.cinefin.utils.ImageDownloadOwner
import com.zhangwenkang.cinefin.utils.ImageDownloadParentKind
import com.zhangwenkang.cinefin.utils.ImageDownloadParentOwner
import com.zhangwenkang.cinefin.utils.ImageDownloadWorkRules
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * W60：图片缓存 worker 的失败重试口径（纯函数，单测覆盖）。
 *
 * 网络类瞬时失败（连接中断 / 超时 / 5xx / 408 / 429）最多尝试 [MAX_ATTEMPTS] 次； 每次重试由 WorkManager 的指数退避（默认 30s
 * 起）延迟，已落盘的图片幂等跳过。 单个非法地址 / 4xx 属于永久失败，不触发整体重试。
 */
internal object ImagesDownloadRetryRules {
    const val MAX_ATTEMPTS = 3

    /** `runAttemptCount` 从 0 开始（本次为第 runAttemptCount + 1 次尝试）；还有剩余尝试才重试。 */
    fun shouldRetry(hadTransientFailure: Boolean, runAttemptCount: Int): Boolean =
        hadTransientFailure && runAttemptCount < MAX_ATTEMPTS - 1

    /** 5xx / 408 / 429 视为瞬时失败；其余非 2xx 为永久失败（忽略该图，不重试）。 */
    fun isTransientHttpFailure(code: Int): Boolean = code == 408 || code == 429 || code >= 500
}

/** Worker 父级存在性 fence；查询异常必须交给 worker 外层决定取消或重试。 */
internal suspend fun isParentSnapshotCurrent(
    owner: ImageDownloadParentOwner,
    getShow: suspend (UUID) -> FindroidShowDto,
    getSeason: suspend (UUID) -> FindroidSeasonDto,
): Boolean =
    when (owner.kind) {
        ImageDownloadParentKind.SHOW -> {
            val show = getShow(owner.itemId)
            show?.serverId == owner.serverId
        }
        ImageDownloadParentKind.SEASON -> {
            val season = getSeason(owner.itemId)
            val show = getShow(season.seriesId)
            show?.serverId == owner.serverId
        }
    }

@HiltWorker
class ImagesDownloaderWorker
@AssistedInject
constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val repository: JellyfinRepository,
    private val database: ServerDatabaseDao,
    /** W66：图片缓存（含 `.meta` 与过期替换规则）单一落点。 */
    private val imageCacheStore: ImageCacheStore,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val rawItemId = params.inputData.getString(KEY_ITEM_ID)
        val itemId = rawItemId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (rawItemId.isNullOrBlank() || itemId == null) {
            Timber.w("图片缓存跳过：缺少 / 非法条目 id")
            return Result.success()
        }
        val sourceId = params.inputData.getString(KEY_SOURCE_ID)?.takeIf(String::isNotBlank)
        val generation = params.inputData.getLong(KEY_GENERATION, -1L)
        val rawParentKind = params.inputData.getString(KEY_PARENT_KIND)?.takeIf(String::isNotBlank)
        val parentOwner = rawParentKind?.let { value ->
            val kind =
                runCatching { ImageDownloadParentKind.valueOf(value) }.getOrNull()
                    ?: return@doWork logSkippedParent("父类型非法：$value")
            val serverId =
                params.inputData.getString(KEY_SERVER_ID)?.takeIf(String::isNotBlank)
                    ?: return@doWork logSkippedParent("缺少 serverId")
            val userId =
                params.inputData.getString(KEY_USER_ID)?.takeIf(String::isNotBlank)
                    ?: return@doWork logSkippedParent("缺少 userId")
            ImageDownloadParentOwner(
                kind = kind,
                itemId = itemId,
                serverId = serverId,
                userId = userId,
            )
        }
        val hadTransientFailure =
            try {
                downloadImages(
                    itemId = itemId,
                    sourceId = sourceId,
                    generation = generation,
                    parentOwner = parentOwner,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "图片缓存执行异常（%s）", itemId)
                true
            }
        if (ImagesDownloadRetryRules.shouldRetry(hadTransientFailure, runAttemptCount)) {
            Timber.i("图片缓存失败，交由 WorkManager 退避重试（第 %d 次尝试）：%s", runAttemptCount + 1, itemId)
            return Result.retry()
        }
        return Result.success()
    }

    /**
     * 返回 true 表示存在网络类瞬时失败（可重试）；永久失败 / 无图返回 false。
     *
     * W66：判定改为 [ImageCacheRules]——文件缺失 / 元数据缺失 / 源 URL 变化 / 超 TTL 都会重新拉取并原子替换
     * （不再「有文件就跳过」）；其余情况直接复用本地缓存（304 条件请求只刷新 `fetchedAt`）。
     */
    private suspend fun downloadImages(
        itemId: UUID,
        sourceId: String?,
        generation: Long,
        parentOwner: ImageDownloadParentOwner?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            var hadTransientFailure = false
            suspend fun currentSource(): Boolean {
                if (parentOwner != null) {
                    val visibleNamespace = imageCacheStore.currentNamespaceOrNull()
                    return ImageDownloadWorkRules.isParentCurrent(
                        owner = parentOwner,
                        visibleServerId = visibleNamespace?.serverId,
                        visibleUserId = visibleNamespace?.userId,
                        parentExists = parentScopeExists(parentOwner),
                    )
                }
                if (sourceId == null || generation < 0L) {
                    // 旧版无 owner 的父图任务无法证明 server/user；显式拒绝，避免借用子项 source 猜测 provenance。
                    Timber.w("图片缓存跳过：缺少 source 或父级 owner（%s）", itemId)
                    return false
                }
                val persisted = database.getSource(sourceId)
                return ImageDownloadWorkRules.isCurrent(
                    expectedSourceId = sourceId,
                    expectedGeneration = generation,
                    persisted =
                        persisted?.let {
                            ImageDownloadOwner(
                                sourceId = it.id,
                                status = it.taskStatus.orEmpty(),
                                generation = it.taskGeneration,
                            )
                        },
                )
            }
            if (!currentSource()) return@withContext false
            val item = repository.getItem(itemId) ?: return@withContext hadTransientFailure

            val uris =
                mapOf(
                    ImageCacheStore.PRIMARY to item.images.primary,
                    ImageCacheStore.BACKDROP to item.images.backdrop,
                )

            for ((name, uri) in uris) {
                val url = uri?.toString()?.takeIf { value -> value.isNotBlank() } ?: continue
                if (!currentSource()) break

                val decision = imageCacheStore.decide(itemId, currentSourceUrl = url, name = name)
                if (!ImageCacheRules.shouldRefetch(decision)) continue

                val outcome =
                    try {
                        imageCacheStore.fetch(
                            itemId = itemId,
                            url = url,
                            name = name,
                            commitGuard = { currentSource() },
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        // W57：单个非法地址（如本地合成 URI）不再让整个 worker 崩溃，其余图片继续。
                        Timber.w(error, "忽略非法图片地址（%s）", name)
                        continue
                    }
                if (outcome.transientFailure) {
                    hadTransientFailure = true
                }
            }
            return@withContext hadTransientFailure
        }

    private suspend fun parentScopeExists(owner: ImageDownloadParentOwner): Boolean =
        isParentSnapshotCurrent(
            owner = owner,
            getShow = database::getShow,
            getSeason = database::getSeason,
        )

    private fun logSkippedParent(reason: String): Result {
        Timber.w("父级图片缓存跳过：%s", reason)
        return Result.success()
    }

    companion object {
        const val KEY_ITEM_ID = "KEY_ITEM_ID"
        const val KEY_SOURCE_ID = "KEY_SOURCE_ID"
        const val KEY_GENERATION = "KEY_GENERATION"
        const val KEY_PARENT_KIND = "KEY_PARENT_KIND"
        const val KEY_SERVER_ID = "KEY_SERVER_ID"
        const val KEY_USER_ID = "KEY_USER_ID"
    }
}
