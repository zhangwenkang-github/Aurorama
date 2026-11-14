package com.zhangwenkang.cinefin.presentation.selection

import com.zhangwenkang.cinefin.presentation.film.components.EpisodeDownloadSelectionTags
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDownloadSelectionContractTest {
    @Test
    fun `selection dialog exposes one stable action per semantic control`() {
        val actionTags =
            listOf(
                EpisodeDownloadSelectionTags.SELECT_ALL,
                EpisodeDownloadSelectionTags.SELECT_NONE,
                EpisodeDownloadSelectionTags.CONFIRM,
            )

        assertEquals(actionTags.size, actionTags.toSet().size)
        assertTrue(actionTags.all(String::isNotBlank))
    }

    @Test
    fun `candidate semantics identities stay one-to-one with stable item ids`() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val tags =
            listOf(
                EpisodeDownloadSelectionTags.candidate(first),
                EpisodeDownloadSelectionTags.candidate(second),
            )

        assertEquals(tags.size, tags.toSet().size)
        assertTrue(
            tags.none { tag ->
                tag in
                    setOf(
                        EpisodeDownloadSelectionTags.SELECT_ALL,
                        EpisodeDownloadSelectionTags.SELECT_NONE,
                        EpisodeDownloadSelectionTags.CONFIRM,
                    )
            }
        )
    }
}
