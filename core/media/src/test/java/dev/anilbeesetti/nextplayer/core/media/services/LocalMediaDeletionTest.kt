package dev.anilbeesetti.nextplayer.core.media.services

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.app.RemoteAction
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.BaseColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalMediaDeletionTest {

    private lateinit var application: Application
    private lateinit var provider: VideoProvider
    private var controller: ActivityController<ComponentActivity>? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        provider = VideoProvider()
        provider.attachInfo(application, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        Dispatchers.resetMain()
    }

    @Test
    fun grantingLegacyWritePermissionDeletesTheVideo() = runTest {
        val registry = ConsentRegistry(granted = true)
        assertTrue(service(registry).deleteMedia(listOf(VIDEO_URI), permanently = true))

        assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), registry.permissions)
        assertFalse(provider.videoExists)
        assertEquals(1, provider.deleteAttempts)
    }

    @Test
    fun denyingLegacyWritePermissionLeavesTheVideoUntouched() = runTest {
        val registry = ConsentRegistry(granted = false)
        assertFalse(service(registry).deleteMedia(listOf(VIDEO_URI), permanently = true))

        assertTrue(provider.videoExists)
        assertEquals(0, provider.deleteAttempts)
    }

    @Test
    fun existingWritePermissionDoesNotPromptAgain() = runTest {
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val registry = ConsentRegistry(granted = true)

        assertTrue(service(registry).deleteMedia(listOf(VIDEO_URI), permanently = true))
        assertTrue(registry.permissions.isEmpty())
        assertFalse(provider.videoExists)
    }

    @Test
    fun providerFailureReturnsFalseAndPreservesTheVideo() = runTest {
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        provider.rejectDeletion = true

        assertFalse(service(ConsentRegistry(granted = true)).deleteMedia(listOf(VIDEO_URI), permanently = true))
        assertTrue(provider.videoExists)
    }

    @Test
    fun uninitializedPermissionLauncherFailsWithoutHanging() = runTest {
        assertFalse(LocalMediaOperationsService(application).deleteMedia(listOf(VIDEO_URI), permanently = true))
        assertTrue(provider.videoExists)
    }

    @Test
    @Config(sdk = [29])
    fun android10RetriesDeletionAfterPerFileConsent() = runTest {
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        provider.needsRecovery = true
        val registry = ConsentRegistry(granted = true)

        assertTrue(service(registry).deleteMedia(listOf(VIDEO_URI), permanently = true))
        assertEquals(1, registry.mediaRequests)
        assertEquals(2, provider.deleteAttempts)
        assertFalse(provider.videoExists)
    }

    @Test
    @Config(sdk = [29])
    fun android10ConsentCancellationPreservesTheVideo() = runTest {
        shadowOf(application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        provider.needsRecovery = true
        val registry = ConsentRegistry(granted = false)

        assertFalse(service(registry).deleteMedia(listOf(VIDEO_URI), permanently = true))
        assertEquals(1, registry.mediaRequests)
        assertEquals(1, provider.deleteAttempts)
        assertTrue(provider.videoExists)
    }

    @Test
    @Config(sdk = [30])
    fun modernTrashRequestsConsentWithoutLegacyStoragePermission() = runTest {
        val registry = ConsentRegistry(granted = true)

        assertTrue(service(registry).deleteMedia(listOf(VIDEO_URI), permanently = false))
        assertEquals("create_trash_request", provider.requestMethod)
        assertTrue(registry.permissions.isEmpty())
        assertTrue(provider.videoExists)
        assertTrue(provider.trashed)
    }

    @Test
    @Config(sdk = [30])
    fun modernPermanentDeletionUsesTheDeleteRequest() = runTest {
        val registry = ConsentRegistry(granted = true)

        assertTrue(service(registry).deleteMedia(listOf(VIDEO_URI), permanently = true))
        assertEquals("create_delete_request", provider.requestMethod)
        assertTrue(registry.permissions.isEmpty())
        assertFalse(provider.videoExists)
    }

    @Test
    @Config(sdk = [30])
    fun decliningModernConsentLeavesTheVideoUntouched() = runTest {
        assertFalse(service(ConsentRegistry(granted = false)).deleteMedia(listOf(VIDEO_URI), permanently = true))
        assertTrue(provider.videoExists)
        assertFalse(provider.trashed)
    }

    private fun service(registry: ActivityResultRegistry): LocalMediaOperationsService {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).create()
        controller = host
        return LocalMediaOperationsService(application).also {
            it.initialize(host.get(), registry)
            host.start().resume()
        }
    }

    private inner class ConsentRegistry(private val granted: Boolean) : ActivityResultRegistry() {
        val permissions = mutableListOf<String>()
        var mediaRequests = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            assertEquals("Consent must launch on the main thread", Looper.getMainLooper(), Looper.myLooper())
            if (contract is ActivityResultContracts.RequestPermission) {
                val permission = input as String
                permissions += permission
                if (granted) shadowOf(application).grantPermissions(permission)
                dispatchResult(requestCode, granted)
            } else {
                mediaRequests++
                if (granted) {
                    provider.needsRecovery = false
                    when (provider.requestMethod) {
                        "create_delete_request" -> provider.videoExists = false
                        "create_trash_request" -> provider.trashed = true
                    }
                }
                dispatchResult(requestCode, ActivityResult(if (granted) Activity.RESULT_OK else Activity.RESULT_CANCELED, null))
            }
        }
    }

    private inner class VideoProvider : ContentProvider() {
        var videoExists = true
        var deleteAttempts = 0
        var rejectDeletion = false
        var needsRecovery = false
        var requestMethod: String? = null
        var trashed = false

        override fun onCreate() = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            requestMethod = method
            val intent = PendingIntent.getActivity(application, 0, Intent(method), PendingIntent.FLAG_IMMUTABLE)
            return Bundle().apply { putParcelable("result", intent) }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleteAttempts++
            if (rejectDeletion) throw SecurityException("Read-only storage")
            if (needsRecovery) {
                val intent = PendingIntent.getActivity(application, 0, Intent("test.consent"), PendingIntent.FLAG_IMMUTABLE)
                throw RecoverableSecurityException(
                    SecurityException("Per-file consent required"),
                    "Allow deletion",
                    RemoteAction(Icon.createWithResource(application, android.R.drawable.ic_dialog_alert), "Allow", "Allow deletion", intent),
                )
            }
            check(application.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            if (!videoExists) return 0
            videoExists = false
            return 1
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf(BaseColumns._ID)).apply {
            if (videoExists) addRow(arrayOf(42L))
        }

        override fun getType(uri: Uri): String = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private companion object {
        val VIDEO_URI: Uri = Uri.parse("content://media/external/video/media/42")
    }
}
