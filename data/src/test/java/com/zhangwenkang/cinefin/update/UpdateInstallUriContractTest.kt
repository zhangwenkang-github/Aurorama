package com.zhangwenkang.cinefin.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateInstallUriContractTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun onlyExactProviderAuthorityAndValidatedUpdatesPathAreAccepted() {
        val updates = temporaryFolder.newFolder("updates")
        val apk = File(updates, "${"a".repeat(64)}.apk").apply { writeText("apk") }
        val appId = "io.github.zhangwenkang.aurorama.debug"

        assertTrue(
            validateUpdateInstallUri(
                applicationId = appId,
                apkFile = apk,
                updatesDirectory = updates,
                uriAuthority = "io.github.zhangwenkang.aurorama.debug.updateinstall",
                uriPathSegments = listOf("validated_updates", apk.name),
            )
        )
        assertFalse(
            validateUpdateInstallUri(
                applicationId = appId,
                apkFile = apk,
                updatesDirectory = updates,
                uriAuthority = "io.github.zhangwenkang.aurorama.updateinstall",
                uriPathSegments = listOf("validated_updates", apk.name),
            )
        )
    }

    @Test
    fun traversalNestedArbitraryOrNonApkPathsAreRejected() {
        val updates = temporaryFolder.newFolder("updates")
        val nested = File(updates, "nested").apply { mkdirs() }
        val apk = File(updates, "${"b".repeat(64)}.apk").apply { writeText("apk") }
        val nestedApk = File(nested, apk.name).apply { writeText("apk") }
        val nonApk = File(updates, "${"c".repeat(64)}.txt").apply { writeText("apk") }
        val outside = temporaryFolder.newFile("${"d".repeat(64)}.apk").apply { writeText("apk") }
        val appId = "io.github.zhangwenkang.aurorama.debug"

        listOf(
                validateUpdateInstallUri(
                    appId,
                    apk,
                    updates,
                    "$appId.updateinstall",
                    listOf("validated_updates", "..", apk.name),
                ),
                validateUpdateInstallUri(
                    appId,
                    nestedApk,
                    updates,
                    "$appId.updateinstall",
                    listOf("validated_updates", "nested", nestedApk.name),
                ),
                validateUpdateInstallUri(
                    appId,
                    nonApk,
                    updates,
                    "$appId.updateinstall",
                    listOf("validated_updates", nonApk.name),
                ),
                validateUpdateInstallUri(
                    appId,
                    outside,
                    updates,
                    "$appId.updateinstall",
                    listOf("validated_updates", outside.name),
                ),
            )
            .forEach { assertFalse(it) }
    }
}
