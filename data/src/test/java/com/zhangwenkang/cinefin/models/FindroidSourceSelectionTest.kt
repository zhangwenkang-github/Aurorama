package com.zhangwenkang.cinefin.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FindroidSourceSelectionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing completed local file falls back to remote`() {
        val local =
            source(
                id = "local",
                type = FindroidSourceType.LOCAL,
                path = File(temporaryFolder.root, "deleted.mkv").path,
                taskStatus = "COMPLETED",
            )
        val remote =
            source(
                id = "remote",
                type = FindroidSourceType.REMOTE,
                path = "https://example.test/movie.mkv",
            )

        assertEquals(remote, selectFindroidSource(listOf(local, remote)))
    }

    @Test
    fun `readable completed local file remains preferred`() {
        val localFile =
            temporaryFolder.newFile("present.mkv").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val local =
            source(
                id = "local",
                type = FindroidSourceType.LOCAL,
                path = localFile.path,
                taskStatus = "COMPLETED",
            )
        val remote =
            source(
                id = "remote",
                type = FindroidSourceType.REMOTE,
                path = "https://example.test/movie.mkv",
            )

        assertEquals(local, selectFindroidSource(listOf(remote, local)))
    }

    @Test
    fun `content uri local source remains selectable`() {
        val local =
            source(
                id = "local-library",
                type = FindroidSourceType.LOCAL,
                path = "content://media/external/video/media/42",
            )
        val remote =
            source(
                id = "remote",
                type = FindroidSourceType.REMOTE,
                path = "https://example.test/movie.mkv",
            )

        assertEquals(local, selectFindroidSource(listOf(remote, local)))
    }

    @Test
    fun `local source with remote url fails closed instead of masquerading as a file`() {
        val localWithHttpPath =
            source(
                id = "misclassified-local",
                type = FindroidSourceType.LOCAL,
                path = "https://cdn.example.test/not-a-local-file.mkv",
                taskStatus = "COMPLETED",
            )
        val remote =
            source(
                id = "remote",
                type = FindroidSourceType.REMOTE,
                path = "https://example.test/movie.mkv",
            )

        assertEquals(remote, selectFindroidSource(listOf(localWithHttpPath, remote)))
    }

    @Test
    fun `missing local source without remote yields no selectable source`() {
        val local =
            source(
                id = "local",
                type = FindroidSourceType.LOCAL,
                path = File(temporaryFolder.root, "missing.mkv").path,
                taskStatus = "COMPLETED",
            )

        assertNull(selectFindroidSource(listOf(local)))
    }

    private fun source(
        id: String,
        type: FindroidSourceType,
        path: String,
        taskStatus: String? = null,
    ): FindroidSource =
        FindroidSource(
            id = id,
            name = "$id.mkv",
            type = type,
            path = path,
            size = 3L,
            mediaStreams = emptyList(),
            container = "mkv",
            downloadTaskStatus = taskStatus,
        )
}
