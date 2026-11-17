package com.zhangwenkang.cinefin.presentation.navigation

import androidx.compose.ui.semantics.Role
import com.zhangwenkang.cinefin.localLibraryQaSemantics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalLibraryQaSemanticsTest {

    @Test
    fun debugPadRailReturnsExactFourFixedTags() {
        val semantics = debugPadSemantics()

        val actual =
            listOf(
                semantics.mainNavigationScreenTag,
                semantics.mediaGroupTag,
                semantics.localLibraryItemTag,
                semantics.localLibraryScreenTag,
            )

        assertEquals(
            listOf(
                "aurorama.qa.main_navigation.screen",
                "aurorama.qa.media_group.nav_item",
                "aurorama.qa.local_library.nav_item",
                "aurorama.qa.local_library.screen",
            ),
            actual,
        )
        assertEquals(4, actual.distinct().size)
    }

    @Test
    fun debugPadRailUsesButtonRoleForBothNavigationControls() {
        val semantics = debugPadSemantics()

        assertEquals(Role.Button, semantics.clickableRole)
    }

    @Test
    fun releaseExposesNoQaSemantics() {
        assertNull(localLibraryQaSemantics(isPadRail = true, isDebugBuild = false))
    }

    @Test
    fun compactPhoneExposesNoPadRailSemantics() {
        assertNull(localLibraryQaSemantics(isPadRail = false, isDebugBuild = true))
    }

    @Test
    fun fixedTagsDoNotContainDynamicLibraryIdentity() {
        val semantics = debugPadSemantics()
        val privateLibraryName = "Private Library Source Token"
        val privateLibraryId = "8675309"
        val tags =
            listOf(
                semantics.mainNavigationScreenTag,
                semantics.mediaGroupTag,
                semantics.localLibraryItemTag,
                semantics.localLibraryScreenTag,
            )

        assertTrue(tags.none { it.contains(privateLibraryName) })
        assertTrue(tags.none { it.contains(privateLibraryId) })
    }

    @Test
    fun singleFixedMediaCandidateIsAccepted() {
        val result = resolveMediaGroup(inventory(mediaNodes = listOf(fixedMediaNode())))

        assertTrue(result is QaResolution.Ready)
    }

    @Test
    fun zeroCandidatesFailClosed() {
        assertEquals(QaFailureCode.LOCATOR_NOT_FOUND, failureCode(inventory()))
    }

    @Test
    fun multipleCandidatesFailClosed() {
        val result =
            inventory(
                mediaNodes =
                    listOf(
                        fixedMediaNode(),
                        fixedMediaNode().copy(enabled = true, clickable = true),
                    )
            )

        assertEquals(QaFailureCode.LOCATOR_AMBIGUOUS, failureCode(result))
    }

    @Test
    fun dynamicLibraryIdentityIsRejected() {
        val dynamicNode = fixedMediaNode().copy(resourceId = MediaGroupTag.VALUE + ".8675309")

        assertEquals(
            QaFailureCode.LOCATOR_NOT_FOUND,
            failureCode(inventory(mediaNodes = listOf(dynamicNode))),
        )
    }

    @Test
    fun wrongActivityFocusAndScreenFailClosed() {
        assertEquals(
            QaFailureCode.WRONG_ACTIVITY,
            failureCode(
                inventory(
                    activity = "com.example.OtherActivity",
                    mediaNodes = listOf(fixedMediaNode()),
                )
            ),
        )
        assertEquals(
            QaFailureCode.WRONG_FOCUS,
            failureCode(inventory(focusMatches = false, mediaNodes = listOf(fixedMediaNode()))),
        )
        assertEquals(
            QaFailureCode.STALE_SCREEN,
            failureCode(
                inventory(screenTag = "stale.screen", mediaNodes = listOf(fixedMediaNode()))
            ),
        )
    }

    @Test
    fun rawTextInputIsRejected() {
        assertEquals(
            QaFailureCode.RAW_TEXT_PROHIBITED,
            failureCode(inventory(containsRawText = true, mediaNodes = listOf(fixedMediaNode()))),
        )
    }

    @Test
    fun exactSinglePostconditionIsAccepted() {
        assertNull(evaluatePostcondition(postcondition()))
    }

    @Test
    fun missingOrDuplicatePostconditionFailsClosed() {
        assertEquals(
            QaFailureCode.POSTCONDITION_NOT_PROVEN,
            evaluatePostcondition(postcondition(screenMatchCount = 0)),
        )
        assertEquals(
            QaFailureCode.POSTCONDITION_AMBIGUOUS,
            evaluatePostcondition(postcondition(screenMatchCount = 2)),
        )
    }

    @Test
    fun wrongPostconditionActivityOrScopeFailsClosed() {
        assertEquals(
            QaFailureCode.WRONG_ACTIVITY,
            evaluatePostcondition(postcondition(activity = "com.example.OtherActivity")),
        )
        assertEquals(
            QaFailureCode.STALE_SCREEN,
            evaluatePostcondition(postcondition(destinationScope = "MediaRoute")),
        )
    }

    private fun failureCode(inventory: ClosedInventory): QaFailureCode {
        val result = resolveMediaGroup(inventory)
        if (result is QaResolution.Failure) {
            return result.code
        }
        fail("Expected a fail-closed result but got $result")
        throw AssertionError("unreachable")
    }

    private fun debugPadSemantics(): com.zhangwenkang.cinefin.LocalLibraryQaSemantics {
        val semantics = localLibraryQaSemantics(isPadRail = true, isDebugBuild = true)
        assertNotNull(semantics)
        return requireNotNull(semantics)
    }
}

private object MainScreenTag {
    const val VALUE = "aurorama.qa.main_navigation.screen"
}

private object MediaGroupTag {
    const val VALUE = "aurorama.qa.media_group.nav_item"
}

private object LocalLibraryItemTag {
    const val VALUE = "aurorama.qa.local_library.nav_item"
}

private object LocalLibraryScreenTag {
    const val VALUE = "aurorama.qa.local_library.screen"
}

private const val MAIN_ACTIVITY = "com.zhangwenkang.cinefin.MainActivity"
private const val OPEN_LOCAL_LIBRARY = "OPEN_LOCAL_LIBRARY"
private const val LOCAL_LIBRARY_ROUTE = "LocalLibraryRoute"

private enum class QaFailureCode {
    WRONG_ACTIVITY,
    WRONG_FOCUS,
    STALE_SCREEN,
    LOCATOR_NOT_FOUND,
    LOCATOR_AMBIGUOUS,
    POSTCONDITION_NOT_PROVEN,
    POSTCONDITION_AMBIGUOUS,
    RAW_TEXT_PROHIBITED,
}

private data class ClosedNode(
    val resourceId: String,
    val role: String = "BUTTON",
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val clickable: Boolean = true,
)

private data class ClosedInventory(
    val activity: String = MAIN_ACTIVITY,
    val focusMatches: Boolean = true,
    val screenTag: String = MainScreenTag.VALUE,
    val mediaNodes: List<ClosedNode> = emptyList(),
    val containsRawText: Boolean = false,
)

private sealed interface QaResolution {
    data class Ready(val node: ClosedNode) : QaResolution

    data class Failure(val code: QaFailureCode) : QaResolution
}

private data class PostconditionEvidence(
    val activity: String = MAIN_ACTIVITY,
    val operationId: String = OPEN_LOCAL_LIBRARY,
    val destinationScope: String = LOCAL_LIBRARY_ROUTE,
    val screenResourceId: String = LocalLibraryScreenTag.VALUE,
    val screenVisible: Boolean = true,
    val screenMatchCount: Int = 1,
    val containsRawText: Boolean = false,
)

private fun fixedMediaNode() = ClosedNode(resourceId = MediaGroupTag.VALUE, role = "BUTTON")

private fun inventory(
    activity: String = MAIN_ACTIVITY,
    focusMatches: Boolean = true,
    screenTag: String = MainScreenTag.VALUE,
    mediaNodes: List<ClosedNode> = emptyList(),
    containsRawText: Boolean = false,
) =
    ClosedInventory(
        activity = activity,
        focusMatches = focusMatches,
        screenTag = screenTag,
        mediaNodes = mediaNodes,
        containsRawText = containsRawText,
    )

private fun resolveMediaGroup(inventory: ClosedInventory): QaResolution {
    if (inventory.containsRawText) {
        return QaResolution.Failure(QaFailureCode.RAW_TEXT_PROHIBITED)
    }
    if (inventory.activity != MAIN_ACTIVITY) {
        return QaResolution.Failure(QaFailureCode.WRONG_ACTIVITY)
    }
    if (!inventory.focusMatches) {
        return QaResolution.Failure(QaFailureCode.WRONG_FOCUS)
    }
    if (inventory.screenTag != MainScreenTag.VALUE) {
        return QaResolution.Failure(QaFailureCode.STALE_SCREEN)
    }
    val candidates =
        inventory.mediaNodes.filter {
            it.resourceId == MediaGroupTag.VALUE &&
                it.role == "BUTTON" &&
                it.visible &&
                it.enabled &&
                it.clickable
        }
    return when (candidates.size) {
        0 -> QaResolution.Failure(QaFailureCode.LOCATOR_NOT_FOUND)
        1 -> QaResolution.Ready(candidates.single())
        else -> QaResolution.Failure(QaFailureCode.LOCATOR_AMBIGUOUS)
    }
}

private fun postcondition(
    activity: String = MAIN_ACTIVITY,
    destinationScope: String = LOCAL_LIBRARY_ROUTE,
    screenMatchCount: Int = 1,
) =
    PostconditionEvidence(
        activity = activity,
        destinationScope = destinationScope,
        screenMatchCount = screenMatchCount,
    )

private fun evaluatePostcondition(evidence: PostconditionEvidence): QaFailureCode? {
    if (evidence.containsRawText) return QaFailureCode.RAW_TEXT_PROHIBITED
    if (evidence.activity != MAIN_ACTIVITY) return QaFailureCode.WRONG_ACTIVITY
    if (
        evidence.operationId != OPEN_LOCAL_LIBRARY ||
            evidence.destinationScope != LOCAL_LIBRARY_ROUTE ||
            evidence.screenResourceId != LocalLibraryScreenTag.VALUE ||
            !evidence.screenVisible
    ) {
        return QaFailureCode.STALE_SCREEN
    }
    return when {
        evidence.screenMatchCount == 1 -> null
        evidence.screenMatchCount <= 0 -> QaFailureCode.POSTCONDITION_NOT_PROVEN
        else -> QaFailureCode.POSTCONDITION_AMBIGUOUS
    }
}
