package dev.anilbeesetti.nextplayer.feature.network.download

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PublicDownloadsPermissionTest {
    @Test
    @Config(sdk = [28])
    fun `Android 9 needs public storage permission`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertFalse(context.canWritePublicDownloads())

        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertTrue(context.canWritePublicDownloads())
    }

    @Test
    @Config(sdk = [29])
    fun `Android 10 can write an owned MediaStore download without storage permission`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertTrue(context.canWritePublicDownloads())
    }
}
