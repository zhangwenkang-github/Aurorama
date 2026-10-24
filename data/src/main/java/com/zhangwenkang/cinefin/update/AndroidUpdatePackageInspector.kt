package com.zhangwenkang.cinefin.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File

class AndroidUpdatePackageInspector(private val context: Context) :
    UpdateApkMetadataReader, InstalledApplicationReader {
    private val packageManager = context.packageManager

    override fun readCandidateMetadata(apkFile: File): CandidateApkMetadata {
        val packageInfo =
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, signingFlags)
                ?: throw IllegalStateException("cannot parse candidate APK")
        val applicationInfo =
            packageInfo.applicationInfo
                ?: throw IllegalStateException("candidate APK has no application info")
        applicationInfo.sourceDir = apkFile.absolutePath
        applicationInfo.publicSourceDir = apkFile.absolutePath
        return CandidateApkMetadata(
            packageName = packageInfo.packageName,
            versionCode = packageInfo.longVersionCode,
            versionName = packageInfo.versionName.orEmpty(),
            minSdk = applicationInfo.minSdkVersion,
            signerCertificateDigests = packageInfo.signerCertificateDigests(),
        )
    }

    override fun readInstalledApplication(): InstalledAppMetadata {
        val packageInfo = packageManager.getPackageInfo(context.packageName, signingFlags)
        return InstalledAppMetadata(
            packageName = packageInfo.packageName,
            versionCode = packageInfo.longVersionCode,
            versionName = packageInfo.versionName.orEmpty(),
            signerCertificateDigests = packageInfo.signerCertificateDigests(),
        )
    }

    override fun readInstalledVersionCode(): Long =
        packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    private fun PackageInfo.signerCertificateDigests(): Set<String> =
        signingInfo?.apkContentsSigners.orEmpty().mapTo(mutableSetOf()) { signature ->
            updateSignerCertificateDigest(signature.toByteArray())
        }

    private companion object {
        const val signingFlags = PackageManager.GET_SIGNING_CERTIFICATES
    }
}
