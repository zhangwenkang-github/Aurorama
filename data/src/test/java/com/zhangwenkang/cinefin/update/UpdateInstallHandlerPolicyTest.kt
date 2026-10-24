package com.zhangwenkang.cinefin.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallHandlerPolicyTest {
    @Test
    fun trustedSystemInstallerIsSelectedExplicitlyInsteadOfFirstHandler() {
        var createdFor: TrustedUpdateInstallHandler? = null
        val candidates =
            listOf(
                candidate(
                    packageName = "com.example.user.installer",
                    activityClassName = "com.example.user.installer.InstallActivity",
                    isSystemApplication = false,
                    hasInstallPackagesPermission = true,
                ),
                candidate(
                    packageName = "com.android.packageinstaller",
                    activityClassName = "com.android.packageinstaller.InstallStart",
                    isSystemApplication = true,
                    hasInstallPackagesPermission = true,
                ),
            )

        val result =
            createTrustedUpdateInstallLaunch(candidates) { handler ->
                createdFor = handler
                UpdateInstallerLaunchResult.Launched(command = handler)
            }

        val trusted =
            TrustedUpdateInstallHandler(
                packageName = "com.android.packageinstaller",
                activityClassName = "com.android.packageinstaller.InstallStart",
            )
        assertEquals(trusted, createdFor)
        assertEquals(UpdateInstallerLaunchResult.Launched(trusted), result)
    }

    @Test
    fun userInstalledHandlerIsRejectedBeforeCommandCreation() {
        assertRejectedWithoutCommand(
            candidate(
                packageName = "com.example.user.installer",
                activityClassName = "com.example.user.installer.InstallActivity",
                isSystemApplication = false,
                hasInstallPackagesPermission = true,
            )
        )
    }

    @Test
    fun systemPackageWithoutInstallPackagesPermissionIsRejectedBeforeCommandCreation() {
        assertRejectedWithoutCommand(
            candidate(
                packageName = "com.android.packageinstaller",
                activityClassName = "com.android.packageinstaller.InstallStart",
                isSystemApplication = true,
                hasInstallPackagesPermission = false,
            )
        )
    }

    @Test
    fun packageWithoutSystemIdentityIsRejectedBeforeCommandCreation() {
        assertRejectedWithoutCommand(
            candidate(
                packageName = "com.example.privileged.lookalike",
                activityClassName = "com.example.privileged.lookalike.InstallActivity",
                isSystemApplication = false,
                hasInstallPackagesPermission = true,
            )
        )
    }

    @Test
    fun missingHandlerIsRejectedBeforeCommandCreation() {
        assertRejectedWithoutCommand()
    }

    @Test
    fun multipleQualifiedHandlersAreRejectedBeforeCommandCreation() {
        assertRejectedWithoutCommand(
            candidate(
                packageName = "com.android.packageinstaller",
                activityClassName = "com.android.packageinstaller.InstallStart",
                isSystemApplication = true,
                hasInstallPackagesPermission = true,
            ),
            candidate(
                packageName = "com.oem.packageinstaller",
                activityClassName = "com.oem.packageinstaller.InstallStart",
                isSystemApplication = true,
                hasInstallPackagesPermission = true,
            ),
        )
    }

    private fun assertRejectedWithoutCommand(vararg candidates: UpdateInstallHandlerCandidate) {
        var commandCreations = 0

        val result =
            createTrustedUpdateInstallLaunch(candidates.toList()) {
                commandCreations++
                UpdateInstallerLaunchResult.Launched(command = it)
            }

        assertTrue(result is UpdateInstallerLaunchResult.Failure)
        assertEquals(0, commandCreations)
    }

    private fun candidate(
        packageName: String,
        activityClassName: String,
        isSystemApplication: Boolean,
        hasInstallPackagesPermission: Boolean,
    ) =
        UpdateInstallHandlerCandidate(
            packageName = packageName,
            activityClassName = activityClassName,
            isSystemApplication = isSystemApplication,
            hasInstallPackagesPermission = hasInstallPackagesPermission,
        )
}
