package dev.anilbeesetti.nextplayer.settings.screens.about

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.rememberTvListFocusRequester
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusDown
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

@Composable
fun AboutPreferencesScreen(viewModel: AboutPreferencesViewModel) {
    AboutPreferencesScreenContent(onAction = viewModel::onAction)
}

@Composable
private fun AboutPreferencesScreenContent(
    onAction: (AboutPreferencesAction) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val listFocusRequester = rememberTvListFocusRequester()
    val buttons = listOf(
        AboutButton(
            title = SocialDestinations.firstLabel(),
            destination = SocialDestinations.firstInstagram(),
            icon = NextIcons.Camera,
            accent = Color(0xFFE4405F),
        ),
        AboutButton(
            title = SocialDestinations.secondLabel(),
            destination = SocialDestinations.secondInstagram(),
            icon = NextIcons.Camera,
            accent = Color(0xFFD62976),
        ),
        AboutButton(
            title = SocialDestinations.thirdLabel(),
            destination = SocialDestinations.thirdInstagram(),
            icon = NextIcons.Camera,
            accent = Color(0xFF962FBF),
        ),
        AboutButton(
            title = SocialDestinations.developerLabel(),
            destination = SocialDestinations.telegram(),
            icon = NextIcons.Send,
            accent = Color(0xFF229ED9),
        ),
    )

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.about_name),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = { onAction(AboutPreferencesAction.NavigateUp) },
                        modifier = Modifier.tvFocusDown(listFocusRequester),
                    ) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .tvListFocus(listFocusRequester)
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            buttons.forEach { button ->
                SimpleAboutButton(
                    button = button,
                    onClick = {
                        uriHandler.openUriOrShowToast(button.destination, context)
                    },
                )
            }
        }
    }
}

@Composable
private fun SimpleAboutButton(
    button: AboutButton,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(button.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = button.icon,
                    contentDescription = null,
                    tint = button.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = button.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = NextIcons.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private data class AboutButton(
    val title: String,
    val destination: String,
    val icon: ImageVector,
    val accent: Color,
)

internal fun UriHandler.openUriOrShowToast(uri: String, context: Context) {
    try {
        openUri(uri)
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.error_opening_link), Toast.LENGTH_SHORT).show()
    }
}

private object SocialDestinations {
    private const val KEY = 55

    fun firstLabel(): String = decode(
        126, 89, 68, 67, 86, 80, 69, 86, 90, 23, 6, 23, 13, 23, 90, 88, 85, 94, 91, 82, 25, 67, 94, 89, 86,
    )

    fun secondLabel(): String = decode(
        126, 89, 68, 67, 86, 80, 69, 86, 90, 23, 5, 23, 13, 23, 90, 88, 85, 94, 91, 82, 25, 67, 94, 89, 86, 5,
    )

    fun thirdLabel(): String = decode(
        126, 89, 68, 67, 86, 80, 69, 86, 90, 23, 4, 23, 13, 23, 90, 88, 85, 94, 91, 82, 25, 67, 94, 89, 86, 86,
    )

    fun developerLabel(): String = decode(
        115, 82, 65, 82, 91, 88, 71, 82, 83, 23, 117, 78, 23, 118, 123, 126, 23, 122, 99, 103,
    )

    fun firstInstagram(): String = decode(
        95, 67, 67, 71, 68, 13, 24, 24, 94, 89, 68, 67, 86, 80, 69, 86, 90,
        25, 84, 88, 90, 24, 90, 88, 85, 94, 91, 82, 25, 67, 94, 89, 86,
    )

    fun secondInstagram(): String = decode(
        95, 67, 67, 71, 68, 13, 24, 24, 94, 89, 68, 67, 86, 80, 69, 86, 90,
        25, 84, 88, 90, 24, 90, 88, 85, 94, 91, 82, 25, 67, 94, 89, 86, 5,
    )

    fun thirdInstagram(): String = decode(
        95, 67, 67, 71, 68, 13, 24, 24, 94, 89, 68, 67, 86, 80, 69, 86, 90,
        25, 84, 88, 90, 24, 90, 88, 85, 94, 91, 82, 25, 67, 94, 89, 86, 86,
    )

    fun telegram(): String = decode(
        95, 67, 67, 71, 68, 13, 24, 24, 67, 25, 90, 82, 24, 97, 103, 121, 14, 1, 4,
    )

    private fun decode(vararg encoded: Int): String = buildString(encoded.size) {
        encoded.forEach { append((it xor KEY).toChar()) }
    }
}
