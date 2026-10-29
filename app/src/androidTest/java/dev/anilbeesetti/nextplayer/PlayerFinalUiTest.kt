package dev.anilbeesetti.nextplayer

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.anilbeesetti.nextplayer.core.common.storagePermission
import dev.anilbeesetti.nextplayer.core.ui.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerFinalUiTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(storagePermission)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun awaitHome() {
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithContentDescription(text(R.string.menu)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun navigationHasOnlyHomeAndNetworkAfterRecreation() {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onAllNodes(tab).assertCountEquals(2)
        composeRule.onNodeWithContentDescription(text(R.string.network)).performClick()
        composeRule.onNodeWithContentDescription(text(R.string.home)).performClick()
        composeRule.activityRule.scenario.recreate()
        awaitHome()
        composeRule.onAllNodes(tab).assertCountEquals(2)
    }

    @Test
    fun quickSettingsShowsVideosListNewestAndEveryFieldSelected() {
        composeRule.onNodeWithContentDescription(text(R.string.menu)).performClick()

        listOf(
            R.string.videos,
            R.string.list,
            R.string.newest,
            R.string.duration,
            R.string.folder_duration,
            R.string.extension,
            R.string.path,
            R.string.played_progress,
            R.string.resolution,
            R.string.size,
            R.string.thumbnail,
        ).forEach { resource ->
            composeRule.onAllNodesWithText(text(resource)).filter(isSelected()).assertCountEquals(1)
        }
        composeRule.onNodeWithContentDescription(text(R.string.date)).assertIsOn()
    }

    private fun text(resource: Int): String = composeRule.activity.getString(resource)
}
