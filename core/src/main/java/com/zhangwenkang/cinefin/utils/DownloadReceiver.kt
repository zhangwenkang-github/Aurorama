package com.zhangwenkang.cinefin.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.toFindroidEpisode
import com.zhangwenkang.cinefin.models.toFindroidMovie
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

@AndroidEntryPoint
class DownloadReceiver : BroadcastReceiver() {

    @Inject lateinit var database: ServerDatabaseDao

    @Inject lateinit var downloader: Downloader

    @Inject lateinit var repository: JellyfinRepository

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onReceive(context: Context, intent: Intent) =
        launchAsync(ioScope) {
            if (intent.action == "android.intent.action.DOWNLOAD_COMPLETE") {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != -1L) {
                    val source = database.getSourceByDownloadId(id)
                    if (source != null) {
                        val path = source.path.replace(".download", "")
                        val successfulRename = File(source.path).renameTo(File(path))
                        if (successfulRename) {
                            database.setSourcePath(source.id, path)
                        } else {
                            val items = mutableListOf<FindroidItem>()
                            items.addAll(
                                database.getMovies().map {
                                    it.toFindroidMovie(database, repository.getUserId())
                                }
                            )
                            items.addAll(
                                database.getEpisodes().map {
                                    it.toFindroidEpisode(database, repository.getUserId())
                                }
                            )

                            items
                                .firstOrNull { it.id == source.itemId }
                                ?.let {
                                    downloader.deleteItem(it, source.toFindroidSource(database))
                                }
                        }
                    } else {
                        val mediaStream = database.getMediaStreamByDownloadId(id)
                        if (mediaStream != null) {
                            val path = mediaStream.path.replace(".download", "")
                            val successfulRename = File(mediaStream.path).renameTo(File(path))
                            if (successfulRename) {
                                database.setMediaStreamPath(mediaStream.id, path)
                            } else {
                                database.deleteMediaStream(mediaStream.id)
                            }
                        }
                    }
                }
            }
        }
}
