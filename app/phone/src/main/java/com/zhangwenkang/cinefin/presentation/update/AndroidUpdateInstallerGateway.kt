package com.zhangwenkang.cinefin.presentation.update

import android.Manifest
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.zhangwenkang.cinefin.update.TrustedUpdateInstallHandler
import com.zhangwenkang.cinefin.update.UpdateInstallHandlerCandidate
import com.zhangwenkang.cinefin.update.UpdateInstallerGateway
import com.zhangwenkang.cinefin.update.UpdateInstallerLaunchResult
import com.zhangwenkang.cinefin.update.createTrustedUpdateInstallLaunch
import com.zhangwenkang.cinefin.update.validateUpdateInstallUri
import java.io.File

class AndroidUpdateInstallerGateway(private val context: Context) : UpdateInstallerGateway<Intent> {
    override fun launch(apkFile: File): UpdateInstallerLaunchResult<Intent> {
        val candidates =
            try {
                queryUpdateInstallHandlers()
            } catch (exception: Exception) {
                return UpdateInstallerLaunchResult.Failure(
                    "cannot resolve update install handlers: ${exception.message}"
                )
            }
        return createTrustedUpdateInstallLaunch(candidates) { handler ->
            createExplicitInstallLaunch(apkFile, handler)
        }
    }

    private fun createExplicitInstallLaunch(
        apkFile: File,
        handler: TrustedUpdateInstallHandler,
    ): UpdateInstallerLaunchResult<Intent> {
        val updatesDirectory = File(context.filesDir, "updates")
        val authority = "${context.packageName}.updateinstall"
        val uri =
            try {
                FileProvider.getUriForFile(context, authority, apkFile)
            } catch (exception: Exception) {
                return UpdateInstallerLaunchResult.Failure(
                    "cannot create update content URI: ${exception.message}"
                )
            }
        if (
            !validateUpdateInstallUri(
                applicationId = context.packageName,
                apkFile = apkFile,
                updatesDirectory = updatesDirectory,
                uriAuthority = uri.authority,
                uriPathSegments = uri.pathSegments,
            )
        ) {
            return UpdateInstallerLaunchResult.Failure(
                "update content URI is outside the validated provider path"
            )
        }
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .setComponent(ComponentName(handler.packageName, handler.activityClassName))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("verified-update", uri)
        return UpdateInstallerLaunchResult.Launched<Intent>(intent)
    }

    @Suppress("DEPRECATION")
    private fun queryUpdateInstallHandlers(): List<UpdateInstallHandlerCandidate> {
        val packageManager = context.packageManager
        val probeUri =
            Uri.parse(
                "content://${context.packageName}.updateinstall/validated_updates/handler-probe.apk"
            )
        val queryIntent = Intent(Intent.ACTION_VIEW).setDataAndType(probeUri, APK_MIME_TYPE)
        return packageManager
            .queryIntentActivities(queryIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val applicationInfo = activityInfo.applicationInfo ?: return@mapNotNull null
                if (!activityInfo.enabled || !activityInfo.exported || !applicationInfo.enabled) {
                    return@mapNotNull null
                }
                val packageName =
                    activityInfo.packageName?.takeIf { it.isNotBlank() }
                        ?: applicationInfo.packageName?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                val activityClassName =
                    activityInfo.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                UpdateInstallHandlerCandidate(
                    packageName = packageName,
                    activityClassName = activityClassName,
                    isSystemApplication =
                        (applicationInfo.flags and
                            (ApplicationInfo.FLAG_SYSTEM or
                                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                    hasInstallPackagesPermission =
                        runCatching {
                                packageManager.checkPermission(
                                    Manifest.permission.INSTALL_PACKAGES,
                                    packageName,
                                ) == PackageManager.PERMISSION_GRANTED
                            }
                            .getOrDefault(false),
                )
            }
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
