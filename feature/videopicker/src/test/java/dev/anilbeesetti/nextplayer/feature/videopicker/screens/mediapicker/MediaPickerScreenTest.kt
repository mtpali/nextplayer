package dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anilbeesetti.nextplayer.core.domain.MediaHolder
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.videopicker.state.toSelectedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MediaPickerScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longPressMenuOmitsPlayCopyAndMove() {
        showVideo()
        composeRule.onNodeWithText("Clip.mp4").performTouchInput { longClick() }

        listOf("Play", "Copy", "Move").forEach { title ->
            composeRule.onNodeWithText(title).assertDoesNotExist()
        }
        listOf("Rename", "Share", "Info", "Delete").forEach { title ->
            composeRule.onNodeWithText(title).assertIsDisplayed()
        }
    }

    @Test
    fun confirmingDeleteRetainsTheSelectedVideoUri() {
        val actions = mutableListOf<MediaPickerAction>()
        val video = showVideo(actions::add)
        composeRule.onNodeWithText("Clip.mp4").performTouchInput { longClick() }
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNode(isToggleable()).performClick()
        composeRule.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()

        assertEquals(
            listOf(MediaPickerAction.DeleteSelectedItems(setOf(video.toSelectedVideo()), permanently = true)),
            actions.filterIsInstance<MediaPickerAction.DeleteSelectedItems>(),
        )
    }

    @Test
    fun cancellingDeletePreservesTheVideoSelection() {
        val actions = mutableListOf<MediaPickerAction>()
        showVideo(actions::add)
        composeRule.onNodeWithText("Clip.mp4").performTouchInput { longClick() }
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        assertTrue(actions.none { it is MediaPickerAction.DeleteSelectedItems })
    }

    private fun showVideo(onAction: (MediaPickerAction) -> Unit = {}): Video {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val video = Video.sample.copy(nameWithExtension = "Clip.mp4", uriString = "content://media/external/video/media/42")
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(
                        folderName = "Movies",
                        mediaDataState = DataState.Success(MediaHolder(listOf(video), emptyList())),
                    ),
                    onAction = onAction,
                )
            }
        }
        return video
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television")
    fun tvFolderFabUpReturnsToTheVideoInsteadOfSettings() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        shadowOf(application.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val video = Video.sample.copy(nameWithExtension = "Clip.mp4", uriString = "content://video/1")
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(
                        folderName = "Movies",
                        mediaDataState = DataState.Success(MediaHolder(listOf(video), emptyList())),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Clip.mp4").requestFocus()
        composeRule.onNodeWithContentDescription("Play").requestFocus()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("Clip.mp4").assertIsFocused()
    }

    @Test
    fun loadingWithoutStoragePermissionShowsPermissionMessage() {
        val permissionMessage = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.permission_not_granted)

        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(folderName = null),
                )
            }
        }

        composeRule.onNodeWithText(permissionMessage).assertIsDisplayed()
    }

    @Test
    fun grantingStoragePermissionFromSettingsStartsMediaCollection() {
        val actions = mutableListOf<MediaPickerAction>()
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(folderName = null),
                    onAction = actions::add,
                )
            }
        }
        composeRule.waitForIdle()

        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertTrue(MediaPickerAction.OnPermissionAccepted in actions)
    }
}
