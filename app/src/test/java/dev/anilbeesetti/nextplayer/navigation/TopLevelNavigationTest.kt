package dev.anilbeesetti.nextplayer.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistDetailRoute
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistListRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelNavigationTest {

    @Test
    fun topLevelDestinationsAreInOrder() {
        assertEquals(
            listOf(
                TopLevelDestination.MEDIA,
                TopLevelDestination.NETWORK,
                TopLevelDestination.MORE,
            ),
            TopLevelDestination.entries,
        )
    }

    @Test
    fun switchingTabsPreservesPlaylistStackNestedUnderMore() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedIndexState = mutableIntStateOf(0),
        )
        val moreStack = stacks.getValue(TopLevelDestination.MORE.route)

        state.switchTo(TopLevelDestination.MORE.route)
        moreStack += PlaylistListRoute
        moreStack += PlaylistDetailRoute(7)
        state.switchTo(TopLevelDestination.MEDIA.route)
        state.switchTo(TopLevelDestination.MORE.route)

        assertEquals(
            listOf(TopLevelDestination.MORE.route, PlaylistListRoute, PlaylistDetailRoute(7)),
            state.currentStack,
        )
    }

    @Test
    fun topLevelContentKeysContainsDestinations() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedIndexState = mutableIntStateOf(0),
        )
        for (dest in TopLevelDestination.entries) {
            assert(state.topLevelContentKeys.contains(dest.route))
            assert(state.topLevelContentKeys.contains(Pair("${dest.route}", "${dest.route::class}")))
        }
    }
}
