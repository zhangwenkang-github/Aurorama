package com.zhangwenkang.cinefin.update

data class UpdateInstallHandlerCandidate(
    val packageName: String,
    val activityClassName: String,
    val isSystemApplication: Boolean,
    val hasInstallPackagesPermission: Boolean,
)

data class TrustedUpdateInstallHandler(
    val packageName: String,
    val activityClassName: String,
)

fun <T> createTrustedUpdateInstallLaunch(
    candidates: List<UpdateInstallHandlerCandidate>,
    createCommand: (TrustedUpdateInstallHandler) -> UpdateInstallerLaunchResult<T>,
): UpdateInstallerLaunchResult<T> {
    val resolved =
        candidates
            .filter { it.packageName.isNotBlank() && it.activityClassName.isNotBlank() }
            .distinct()
    if (resolved.isEmpty()) {
        return UpdateInstallerLaunchResult.Failure(
            "update install handler is missing or unresolved"
        )
    }
    val trusted =
        resolved
            .filter { it.isSystemApplication && it.hasInstallPackagesPermission }
            .map {
                TrustedUpdateInstallHandler(
                    packageName = it.packageName,
                    activityClassName = it.activityClassName,
                )
            }
            .distinct()
    return when (trusted.size) {
        0 ->
            UpdateInstallerLaunchResult.Failure(
                "no system update install handler grants INSTALL_PACKAGES"
            )
        1 -> createCommand(trusted.single())
        else ->
            UpdateInstallerLaunchResult.Failure("multiple trusted update install handlers resolved")
    }
}
