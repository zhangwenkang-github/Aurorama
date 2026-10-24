package com.zhangwenkang.cinefin.presentation.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.zhangwenkang.cinefin.update.UpdateInstallerGateway
import com.zhangwenkang.cinefin.update.UpdateInstallerLaunchResult
import com.zhangwenkang.cinefin.update.validateUpdateInstallUri
import java.io.File

class AndroidUpdateInstallerGateway(private val context: Context) : UpdateInstallerGateway<Intent> {
    override fun launch(apkFile: File): UpdateInstallerLaunchResult<Intent> {
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
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("verified-update", uri)
        return UpdateInstallerLaunchResult.Launched<Intent>(intent)
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
