package com.zhangwenkang.cinefin.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.zhangwenkang.cinefin.models.FindroidEpisodeDto
import com.zhangwenkang.cinefin.models.FindroidMediaStreamDto
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.FindroidSeasonDto
import com.zhangwenkang.cinefin.models.FindroidSegmentDto
import com.zhangwenkang.cinefin.models.FindroidShowDto
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidTrickplayInfoDto
import com.zhangwenkang.cinefin.models.FindroidUserDataDto
import com.zhangwenkang.cinefin.models.LocalLibraryDto
import com.zhangwenkang.cinefin.models.LocalLibraryFolderDto
import com.zhangwenkang.cinefin.models.LocalMediaCountRow
import com.zhangwenkang.cinefin.models.LocalMediaItemDto
import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.ServerWithAddressAndUser
import com.zhangwenkang.cinefin.models.ServerWithAddresses
import com.zhangwenkang.cinefin.models.ServerWithAddressesAndUsers
import com.zhangwenkang.cinefin.models.User
import java.util.UUID

@Dao
interface ServerDatabaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertServer(server: Server)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServerAddress(address: ServerAddress)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertUser(user: User)

    @Update suspend fun updateServer(server: Server)

    @Query("SELECT * FROM servers WHERE id = :id") suspend fun getServer(id: String): Server?

    @Query("SELECT * FROM users WHERE id = :id") suspend fun getUser(id: UUID): User?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerWithAddresses(id: String): ServerWithAddresses

    @Query("SELECT * FROM serverAddresses WHERE id = :id")
    suspend fun getAddress(id: UUID): ServerAddress

    @Query("SELECT * FROM users WHERE serverId = :serverId")
    suspend fun getUsers(serverId: String): List<User>

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerWithAddressesAndUsers(id: String): ServerWithAddressesAndUsers?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerWithAddressAndUser(id: String): ServerWithAddressAndUser?

    @Transaction
    @Query("SELECT * FROM servers")
    suspend fun getServersWithAddresses(): List<ServerWithAddresses>

    @Query("SELECT * FROM servers") suspend fun getServers(): List<Server>

    @Query("SELECT COUNT(*) FROM servers") suspend fun getServersCount(): Int

    @Query("DELETE FROM servers WHERE id = :id") suspend fun deleteServer(id: String)

    @Query("DELETE FROM users WHERE id = :id") suspend fun deleteUser(id: UUID)

    @Query("DELETE FROM serverAddresses WHERE id = :id") suspend fun deleteServerAddress(id: UUID)

    @Query("UPDATE servers SET currentUserId = :userId WHERE id = :serverId")
    suspend fun updateServerCurrentUser(serverId: String, userId: UUID)

    @Query(
        "SELECT * FROM users WHERE id = (SELECT currentUserId FROM servers WHERE id = :serverId)"
    )
    suspend fun getServerCurrentUser(serverId: String): User?

    @Query(
        "SELECT * FROM serverAddresses WHERE id = (SELECT currentServerAddressId FROM servers WHERE id = :serverId)"
    )
    suspend fun getServerCurrentAddress(serverId: String): ServerAddress?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMovie(movie: FindroidMovieDto)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: FindroidSourceDto)

    @Query("SELECT * FROM movies WHERE id = :id") suspend fun getMovie(id: UUID): FindroidMovieDto

    @Query("SELECT * FROM sources WHERE itemId = :itemId")
    suspend fun getSources(itemId: UUID): List<FindroidSourceDto>

    @Query("SELECT * FROM sources WHERE downloadId = :downloadId")
    suspend fun getSourceByDownloadId(downloadId: Long): FindroidSourceDto?

    /** W32：全部下载来源（下载管理页对账 / 已完成后删除）。 */
    @Query("SELECT * FROM sources") suspend fun getAllSources(): List<FindroidSourceDto>

    /** W32：未完成的下载来源（快照状态非 COMPLETED，或路径仍是 .download）。 */
    @Query(
        "SELECT * FROM sources WHERE taskStatus IS NULL OR taskStatus != 'COMPLETED' OR path LIKE '%.download'"
    )
    suspend fun getPendingSources(): List<FindroidSourceDto>

    @Query("UPDATE sources SET path = :path WHERE id = :id")
    suspend fun setSourcePath(id: String, path: String)

    @Query("UPDATE sources SET downloadId = :downloadId WHERE id = :id")
    suspend fun setSourceDownloadId(id: String, downloadId: Long?)

    @Query(
        "UPDATE sources SET taskStatus = :status, failureReason = :failureReason, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun setSourceTaskStatus(
        id: String,
        status: String?,
        failureReason: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteSource(id: String)

    /** W36：切换单个下载来源的「允许离线模式观看」开关。 */
    @Query("UPDATE sources SET allowOffline = :allowOffline WHERE id = :id")
    suspend fun setSourceAllowOffline(id: String, allowOffline: Boolean)

    @Query("DELETE FROM movies WHERE id = :id") suspend fun deleteMovie(id: UUID)

    @Query(
        "UPDATE userdata SET playbackPositionTicks = :playbackPositionTicks WHERE itemId = :itemId AND userid = :userId"
    )
    suspend fun setPlaybackPositionTicks(itemId: UUID, userId: UUID, playbackPositionTicks: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaStream(mediaStream: FindroidMediaStreamDto)

    @Query("SELECT * FROM mediastreams WHERE sourceId = :sourceId")
    suspend fun getMediaStreamsBySourceId(sourceId: String): List<FindroidMediaStreamDto>

    @Query("SELECT * FROM mediastreams WHERE downloadId = :downloadId")
    suspend fun getMediaStreamByDownloadId(downloadId: Long): FindroidMediaStreamDto?

    @Query("UPDATE mediastreams SET downloadId = :downloadId WHERE id = :id")
    suspend fun setMediaStreamDownloadId(id: UUID, downloadId: Long)

    @Query("UPDATE mediastreams SET path = :path WHERE id = :id")
    suspend fun setMediaStreamPath(id: UUID, path: String)

    @Query("DELETE FROM mediastreams WHERE id = :id") suspend fun deleteMediaStream(id: UUID)

    @Query("DELETE FROM mediastreams WHERE sourceId = :sourceId")
    suspend fun deleteMediaStreamsBySourceId(sourceId: String)

    @Query("UPDATE userdata SET played = :played WHERE userId = :userId AND itemId = :itemId")
    suspend fun setPlayed(userId: UUID, itemId: UUID, played: Boolean)

    @Query("UPDATE userdata SET favorite = :favorite WHERE userId = :userId AND itemId = :itemId")
    suspend fun setFavorite(userId: UUID, itemId: UUID, favorite: Boolean)

    @Query("SELECT * FROM movies ORDER BY name ASC") suspend fun getMovies(): List<FindroidMovieDto>

    @Query("SELECT * FROM movies WHERE serverId = :serverId ORDER BY name ASC")
    suspend fun getMoviesByServerId(serverId: String): List<FindroidMovieDto>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertShow(show: FindroidShowDto)

    @Query("SELECT * FROM shows WHERE id = :id") suspend fun getShow(id: UUID): FindroidShowDto

    @Query("SELECT * FROM shows ORDER BY name ASC") suspend fun getShows(): List<FindroidShowDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId ORDER BY name ASC")
    suspend fun getShowsByServerId(serverId: String): List<FindroidShowDto>

    @Query("DELETE FROM shows WHERE id = :id") suspend fun deleteShow(id: UUID)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeason(show: FindroidSeasonDto)

    @Query("SELECT * FROM seasons WHERE id = :id")
    suspend fun getSeason(id: UUID): FindroidSeasonDto

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY indexNumber ASC")
    suspend fun getSeasonsByShowId(seriesId: UUID): List<FindroidSeasonDto>

    @Query("DELETE FROM seasons WHERE id = :id") suspend fun deleteSeason(id: UUID)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisode(episode: FindroidEpisodeDto)

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisode(id: UUID): FindroidEpisodeDto

    @Query(
        "SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY parentIndexNumber ASC, indexNumber ASC"
    )
    suspend fun getEpisodesByShowId(seriesId: UUID): List<FindroidEpisodeDto>

    @Query("SELECT * FROM episodes WHERE seasonId = :seasonId ORDER BY indexNumber ASC")
    suspend fun getEpisodesBySeasonId(seasonId: UUID): List<FindroidEpisodeDto>

    @Query(
        "SELECT * FROM episodes WHERE serverId = :serverId ORDER BY seriesName ASC, parentIndexNumber ASC, indexNumber ASC"
    )
    suspend fun getEpisodesByServerId(serverId: String): List<FindroidEpisodeDto>

    @Query("DELETE FROM episodes WHERE id = :id") suspend fun deleteEpisode(id: UUID)

    /**
     * W34 下载层级：已下载剧集 + 所属季 / 节目名。
     *
     * 只返回带 LOCAL 完整文件（非 `.download` 残片）的剧集；seasonName 用于「季」容器标题。
     */
    @Query(
        """
        SELECT episodes.id AS episodeId,
               episodes.seasonId AS seasonId,
               episodes.seriesId AS seriesId,
               episodes.name AS episodeName,
               episodes.indexNumber AS episodeIndex,
               episodes.runtimeTicks AS runtimeTicks,
               shows.name AS seriesName,
               seasons.name AS seasonName,
               seasons.indexNumber AS seasonIndex
        FROM episodes
        INNER JOIN seasons ON seasons.id = episodes.seasonId
        INNER JOIN shows ON shows.id = episodes.seriesId
        WHERE EXISTS (
            SELECT 1 FROM sources
            WHERE sources.itemId = episodes.id
              AND sources.type = 'LOCAL'
              AND sources.path NOT LIKE '%.download'
        )
        ORDER BY shows.name ASC, seasons.indexNumber ASC, episodes.indexNumber ASC
        """
    )
    suspend fun getDownloadedEpisodeHierarchy(): List<DownloadedEpisodeHierarchy>

    /**
     * W34 下载层级：已完成（LOCAL 完整文件）的剧集条目。
     *
     * `getDownloads()` 只返回 movies + shows；剧集存在 `episodes` 表，这里按 LOCAL source 反查， 供下载页「节目 → 季 →
     * 剧集」层级使用（离线读库，不联网）。
     */
    @Query(
        """
        SELECT episodes.id AS episodeId,
               episodes.seasonId AS seasonId,
               episodes.seriesId AS seriesId,
               episodes.name AS episodeName,
               episodes.indexNumber AS episodeIndex,
               episodes.runtimeTicks AS runtimeTicks,
               shows.name AS seriesName,
               seasons.name AS seasonName,
               seasons.indexNumber AS seasonIndex
        FROM episodes
        INNER JOIN seasons ON seasons.id = episodes.seasonId
        INNER JOIN shows ON shows.id = episodes.seriesId
        WHERE EXISTS (
            SELECT 1 FROM sources
            WHERE sources.itemId = episodes.id
              AND sources.type = 'LOCAL'
              AND sources.path NOT LIKE '%.download'
        )
        """
    )
    suspend fun getCompletedEpisodeHierarchy(): List<DownloadedEpisodeHierarchy>

    /** W34：剧集 LOCAL 源（取第一个完整文件路径 / 体积）。 */
    @Query(
        """
        SELECT * FROM sources
        WHERE itemId = :itemId AND type = 'LOCAL' AND path NOT LIKE '%.download'
        LIMIT 1
        """
    )
    suspend fun getCompletedSource(itemId: UUID): FindroidSourceDto?

    /** W34 下载层级：已下载音频条目 id（音乐曲目在 movies 表里，只能靠主库曲库快照区分）。 */
    @Query(
        """
        SELECT DISTINCT sources.itemId AS itemId
        FROM sources
        WHERE sources.type = 'LOCAL' AND sources.path NOT LIKE '%.download'
        """
    )
    suspend fun getDownloadedItemIds(): List<UUID>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: FindroidSegmentDto)

    @Query("SELECT * FROM segments WHERE itemId = :itemId")
    suspend fun getSegments(itemId: UUID): List<FindroidSegmentDto>

    @Query("SELECT * FROM seasons") suspend fun getSeasons(): List<FindroidSeasonDto>

    @Query("SELECT * FROM episodes") suspend fun getEpisodes(): List<FindroidEpisodeDto>

    @Query("SELECT * FROM userdata WHERE itemId = :itemId AND userId = :userId")
    suspend fun getUserData(itemId: UUID, userId: UUID): FindroidUserDataDto?

    @Transaction
    suspend fun getUserDataOrCreateNew(itemId: UUID, userId: UUID): FindroidUserDataDto {
        var userData = getUserData(itemId, userId)

        // Create user data when there is none
        if (userData == null) {
            userData =
                FindroidUserDataDto(
                    userId = userId,
                    itemId = itemId,
                    played = false,
                    favorite = false,
                    playbackPositionTicks = 0L,
                )
            insertUserData(userData)
        }

        return userData
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserData(userData: FindroidUserDataDto)

    @Query("DELETE FROM userdata WHERE itemId = :itemId") suspend fun deleteUserData(itemId: UUID)

    @Query("SELECT * FROM userdata WHERE userId = :userId AND itemId = :itemId AND toBeSynced = 1")
    suspend fun getUserDataToBeSynced(userId: UUID, itemId: UUID): FindroidUserDataDto?

    @Query(
        "UPDATE userdata SET toBeSynced = :toBeSynced WHERE itemId = :itemId AND userId = :userId"
    )
    suspend fun setUserDataToBeSynced(userId: UUID, itemId: UUID, toBeSynced: Boolean)

    @Query("SELECT * FROM movies WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    suspend fun searchMovies(serverId: String, name: String): List<FindroidMovieDto>

    @Query("SELECT * FROM shows WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    suspend fun searchShows(serverId: String, name: String): List<FindroidShowDto>

    @Query("SELECT * FROM episodes WHERE serverId = :serverId AND name LIKE '%' || :name || '%'")
    suspend fun searchEpisodes(serverId: String, name: String): List<FindroidEpisodeDto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrickplayInfo(trickplayInfoDto: FindroidTrickplayInfoDto)

    @Query("SELECT * FROM trickplayInfos WHERE sourceId = :sourceId")
    suspend fun getTrickplayInfo(sourceId: String): FindroidTrickplayInfoDto?

    // ---------------------------------------------------------- W37 本地媒体库

    @Insert suspend fun insertLocalLibrary(library: LocalLibraryDto): Long

    @Query("SELECT * FROM local_libraries ORDER BY createdAt ASC, id ASC")
    suspend fun getLocalLibraries(): List<LocalLibraryDto>

    @Query("SELECT * FROM local_libraries WHERE id = :id")
    suspend fun getLocalLibrary(id: Long): LocalLibraryDto?

    @Query("UPDATE local_libraries SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateLocalLibraryName(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE local_libraries SET type = :type, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateLocalLibraryType(id: Long, type: String, updatedAt: Long)

    @Query(
        "UPDATE local_libraries SET visibleInLibrary = :visible, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateLocalLibraryVisible(id: Long, visible: Boolean, updatedAt: Long)

    @Query("DELETE FROM local_libraries WHERE id = :id") suspend fun deleteLocalLibrary(id: Long)

    @Insert suspend fun insertLocalLibraryFolder(folder: LocalLibraryFolderDto): Long

    @Query("SELECT * FROM local_library_folders ORDER BY libraryId ASC, addedAt ASC, id ASC")
    suspend fun getAllLocalLibraryFolders(): List<LocalLibraryFolderDto>

    @Query(
        "SELECT * FROM local_library_folders WHERE libraryId = :libraryId ORDER BY addedAt ASC, id ASC"
    )
    suspend fun getLocalLibraryFolders(libraryId: Long): List<LocalLibraryFolderDto>

    @Query("SELECT * FROM local_library_folders WHERE id = :id")
    suspend fun getLocalLibraryFolder(id: Long): LocalLibraryFolderDto?

    @Query("UPDATE local_library_folders SET browseMode = :mode WHERE id = :id")
    suspend fun updateLocalFolderBrowseMode(id: Long, mode: String)

    @Query("DELETE FROM local_library_folders WHERE id = :id")
    suspend fun deleteLocalLibraryFolder(id: Long)

    @Query("DELETE FROM local_library_folders WHERE libraryId = :libraryId")
    suspend fun deleteLocalLibraryFolders(libraryId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocalMediaItems(items: List<LocalMediaItemDto>)

    @Query("DELETE FROM local_media_items WHERE folderId = :folderId")
    suspend fun clearLocalMediaByFolder(folderId: Long)

    @Query("DELETE FROM local_media_items WHERE libraryId = :libraryId")
    suspend fun deleteLocalLibraryItems(libraryId: Long)

    @Query("SELECT * FROM local_media_items WHERE libraryId = :libraryId ORDER BY relativePath ASC")
    suspend fun getLocalMediaItems(libraryId: Long): List<LocalMediaItemDto>

    @Query("SELECT * FROM local_media_items WHERE folderId = :folderId ORDER BY relativePath ASC")
    suspend fun getLocalMediaItemsByFolder(folderId: Long): List<LocalMediaItemDto>

    @Query("SELECT * FROM local_media_items ORDER BY relativePath ASC")
    suspend fun getAllLocalMediaItems(): List<LocalMediaItemDto>

    @Query("SELECT * FROM local_media_items WHERE itemId = :itemId LIMIT 1")
    suspend fun getLocalMediaItem(itemId: UUID): LocalMediaItemDto?

    @Query(
        "SELECT libraryId AS libraryId, kind AS kind, COUNT(*) AS count FROM local_media_items GROUP BY libraryId, kind"
    )
    suspend fun getLocalMediaCounts(): List<LocalMediaCountRow>
}
