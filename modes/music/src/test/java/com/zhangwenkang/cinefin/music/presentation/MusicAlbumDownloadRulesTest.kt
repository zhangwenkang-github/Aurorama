package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W59 专辑下载计划（只补齐缺失 + 100 首上限 + 多专辑合并去重）。 */
class MusicAlbumDownloadRulesTest {

    private fun song(
        index: Int,
        name: String = "track$index",
        itemId: UUID = UUID.randomUUID(),
        serverId: String? = null,
        userId: String? = null,
    ) =
        MusicSong(
            itemId = itemId,
            name = name,
            albumName = "示例专辑",
            artist = "示例艺人",
            indexNumber = index,
            runtimeTicks = 0L,
            imageUri = null,
            serverId = serverId,
            userId = userId,
        )

    private fun album(name: String, songs: List<MusicSong>) =
        MusicAlbum(key = name, name = name, artist = "示例艺人", imageUri = null, songs = songs)

    @Test
    fun `只补齐缺失 已下载与队列内跳过`() {
        val downloaded = song(1, serverId = "server-a", userId = "user-a")
        val active = song(2, serverId = "server-a", userId = "user-a")
        val missingA = song(3, serverId = "server-a", userId = "user-a")
        val missingB = song(4, serverId = "server-a", userId = "user-a")
        val target = album("示例专辑", listOf(downloaded, active, missingA, missingB))

        val plan =
            MusicAlbumDownloadRules.plan(
                album = target,
                downloadedIdentities = setOf(downloaded.downloadIdentity()!!),
                activeIdentities = setOf(active.downloadIdentity()!!),
            )

        assertEquals(listOf(missingA.itemId, missingB.itemId), plan.targets.map { it.itemId })
        assertEquals(1, plan.skippedDownloaded)
        assertEquals(1, plan.skippedActive)
        assertEquals(0, plan.skippedByLimit)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `单次上限 100 首 其余标记为推迟`() {
        val songs = (1..120).map { index -> song(index) }
        val target = album("大专辑", songs)

        val plan =
            MusicAlbumDownloadRules.plan(
                album = target,
                downloadedIdentities = emptySet(),
                activeIdentities = emptySet(),
            )

        assertEquals(100, plan.targets.size)
        assertEquals(20, plan.skippedByLimit)
        // 按音轨序取前 100 首（1..100）。
        assertEquals((1..100).map { it }, plan.targets.map { it.indexNumber })
    }

    @Test
    fun `unknown 保持独立且不按 itemId 跳过`() {
        val songs = (1..3).map { index -> song(index) }
        val target = album("已完成专辑", songs)
        val plan =
            MusicAlbumDownloadRules.plan(
                album = target,
                downloadedIdentities = emptySet(),
                activeIdentities = emptySet(),
            )
        assertEquals(songs, plan.targets)
        assertEquals(0, plan.skippedDownloaded)
    }

    @Test
    fun `多专辑合并按专辑顺序 同一曲目去重 整批上限`() {
        val first = album("专辑一", (1..3).map { song(it, "A$it") })
        val second = (1..3).map { song(it, "B$it") }
        // 第二张专辑含一首与第一张重复的曲目。
        val duplicated = album("专辑二", listOf(first.songs[0], second[1], second[2]))

        val plan =
            MusicAlbumDownloadRules.planAlbums(
                albums = listOf(first, duplicated),
                downloadedIdentities = emptySet(),
                activeIdentities = emptySet(),
                maxTracks = 4,
            )

        assertEquals(listOf("A1", "A2", "A3", "B2"), plan.targets.map { it.name })
        assertEquals(1, plan.skippedByLimit)
    }

    @Test
    fun `同 UUID 仅另一 server 已下载时当前 server 仍进入专辑计划`() {
        val itemId = UUID.randomUUID()
        val currentServerSong =
            song(1, "B", itemId = itemId, serverId = "server-b", userId = "user-a")
        val otherServerDownloaded =
            song(1, "A", itemId = itemId, serverId = "server-a", userId = "user-a")

        val plan =
            MusicAlbumDownloadRules.plan(
                album = album("跨服专辑", listOf(currentServerSong)),
                downloadedIdentities = setOf(otherServerDownloaded.downloadIdentity()!!),
                activeIdentities = emptySet(),
            )

        assertEquals(listOf(currentServerSong), plan.targets)
        assertEquals(0, plan.skippedDownloaded)
    }

    @Test
    fun `同 UUID 的两条未知来源不得折叠也不认作已下载`() {
        val itemId = UUID.randomUUID()
        val unknownA = song(1, "A", itemId = itemId)
        val unknownB = song(2, "B", itemId = itemId)
        val unknownIdentity =
            DownloadCanonicalIdentity.of(
                mediaKind = DownloadMediaKind.MUSIC,
                itemId = itemId.toString(),
                serverId = null,
                userId = null,
                sourceId = null,
            )

        val plan =
            MusicAlbumDownloadRules.plan(
                album = album("未知来源专辑", listOf(unknownA, unknownB)),
                downloadedIdentities = setOf(unknownIdentity),
                activeIdentities = emptySet(),
            )

        assertEquals(listOf(unknownA, unknownB), plan.targets)
        assertEquals(0, plan.skippedDownloaded)
    }

    @Test
    fun `本地媒体库曲目不被专辑下载计划隐式当作服务器曲目`() {
        val local =
            song(1, "本地曲目")
                .copy(
                    localUri = "content://local/song",
                    source = com.zhangwenkang.cinefin.music.data.MusicItemSource.LOCAL,
                    localLibraryId = 7L,
                )

        val plan =
            MusicAlbumDownloadRules.plan(
                album = album("混合专辑", listOf(local)),
                downloadedIdentities = emptySet(),
                activeIdentities = emptySet(),
            )

        assertTrue(plan.targets.isEmpty())
    }

    @Test
    fun `歌单批量保持列表顺序 仅补齐缺失并跳过已下载与队列内`() {
        val downloaded = song(1, "已在本地", serverId = "server-a", userId = "user-a")
        val active = song(2, "队列中", serverId = "server-a", userId = "user-a")
        val missingLater = song(9, "第三首", serverId = "server-a", userId = "user-a")
        val missingEarly = song(3, "第四首", serverId = "server-a", userId = "user-a")
        // 歌单顺序与音轨号不同：不得按音轨号重排。
        val playlist = listOf(downloaded, missingLater, active, missingEarly)

        val plan =
            MusicAlbumDownloadRules.planSongs(
                songs = playlist,
                downloadedIdentities = setOf(downloaded.downloadIdentity()!!),
                activeIdentities = setOf(active.downloadIdentity()!!),
            )

        assertEquals(listOf(missingLater, missingEarly), plan.targets)
        assertEquals(1, plan.skippedDownloaded)
        assertEquals(1, plan.skippedActive)
        assertEquals(0, plan.skippedByLimit)
    }

    @Test
    fun `歌单批量单次上限 100 首且同一曲目去重`() {
        val first = song(1, "开场", serverId = "server-a", userId = "user-a")
        val songs =
            listOf(first) +
                (2..120).map { index ->
                    song(index, "track$index", serverId = "server-a", userId = "user-a")
                } +
                // 歌单里重复收录同一首（同 namespace 同 itemId）→ 只算一次。
                listOf(
                    song(
                        1,
                        "开场",
                        itemId = first.itemId,
                        serverId = "server-a",
                        userId = "user-a",
                    )
                )

        val plan =
            MusicAlbumDownloadRules.planSongs(
                songs = songs,
                downloadedIdentities = emptySet(),
                activeIdentities = emptySet(),
            )

        assertEquals(100, plan.targets.size)
        // 120 首去重后取前 100，剩 20 首因上限推迟。
        assertEquals(20, plan.skippedByLimit)
        assertEquals("开场", plan.targets.first().name)
        // 顺序保持传入顺序（先 index=1，再 2..100），不按音轨号重排。
        assertEquals((1..100).toList(), plan.targets.map { it.indexNumber })
    }

    @Test
    fun `歌单批量跳过本地媒体库曲目`() {
        val local =
            song(1, "本地曲目", serverId = "server-a", userId = "user-a")
                .copy(
                    localUri = "content://local/song",
                    source = com.zhangwenkang.cinefin.music.data.MusicItemSource.LOCAL,
                    localLibraryId = 7L,
                )
        val remote = song(2, "服务器曲目", serverId = "server-a", userId = "user-a")

        val plan =
            MusicAlbumDownloadRules.planSongs(
                songs = listOf(local, remote),
                downloadedIdentities = emptySet(),
                activeIdentities = emptySet(),
            )

        assertEquals(listOf(remote), plan.targets)
    }

    @Test
    fun `单条 unknown 保持独立不按 itemId 跳过`() {
        val legacy = song(1)

        val plan =
            MusicAlbumDownloadRules.plan(
                album = album("legacy", listOf(legacy)),
                downloadedIdentities = emptySet(),
                activeIdentities = emptySet(),
            )

        assertEquals(listOf(legacy), plan.targets)
        assertEquals(0, plan.skippedDownloaded)
    }
}
