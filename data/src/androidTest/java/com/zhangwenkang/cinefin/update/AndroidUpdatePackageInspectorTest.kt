package com.zhangwenkang.cinefin.update

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidUpdatePackageInspectorTest {
    @Test
    fun installedAndArchiveMetadataUseTheSamePackageAndSignerSet() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val inspector = AndroidUpdatePackageInspector(context)
        val installed = inspector.readInstalledApplication()
        val apk = File(context.applicationInfo.sourceDir)
        assertTrue(apk.isFile)

        val candidate = inspector.readCandidateMetadata(apk)

        assertEquals(installed.packageName, candidate.packageName)
        assertEquals(installed.versionCode, candidate.versionCode)
        assertEquals(installed.versionName, candidate.versionName)
        assertEquals(installed.signerCertificateDigests, candidate.signerCertificateDigests)
        assertFalse(candidate.signerCertificateDigests.isEmpty())
        assertTrue(candidate.minSdk <= Build.VERSION.SDK_INT)
        assertEquals(installed.versionCode, inspector.readInstalledVersionCode())
    }
}
