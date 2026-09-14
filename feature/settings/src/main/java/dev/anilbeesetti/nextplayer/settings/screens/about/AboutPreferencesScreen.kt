package dev.anilbeesetti.nextplayer.settings.screens.about

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.rememberTvListFocusRequester
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusDown
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

@Composable
fun AboutPreferencesScreen(viewModel: AboutPreferencesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AboutPreferencesScreenContent(state = state, onAction = viewModel::onAction)
}

@Composable
private fun AboutPreferencesScreenContent(
    state: AboutPreferencesUiState,
    onAction: (AboutPreferencesAction) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val listFocusRequester = rememberTvListFocusRequester()

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.about_mobile_tina),
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroCard(appVersion = state.appVersion)

            SocialButton(
                title = stringResource(R.string.instagram_branch_one),
                handle = SocialDestinations.firstInstagramHandle(),
                icon = NextIcons.Camera,
                accent = Color(0xFFE4405F),
                onClick = {
                    uriHandler.openUriOrShowToast(SocialDestinations.firstInstagram(), context)
                },
            )
            SocialButton(
                title = stringResource(R.string.instagram_branch_two),
                handle = SocialDestinations.secondInstagramHandle(),
                icon = NextIcons.Camera,
                accent = Color(0xFFFD1D1D),
                onClick = {
                    uriHandler.openUriOrShowToast(SocialDestinations.secondInstagram(), context)
                },
            )
            SocialButton(
                title = stringResource(R.string.instagram_third),
                handle = SocialDestinations.thirdInstagramHandle(),
                icon = NextIcons.Camera,
                accent = Color(0xFFC13584),
                onClick = {
                    uriHandler.openUriOrShowToast(SocialDestinations.thirdInstagram(), context)
                },
            )
            SocialButton(
                title = stringResource(R.string.contact_developer),
                handle = SocialDestinations.telegramHandle(),
                icon = NextIcons.Send,
                accent = Color(0xFF229ED9),
                onClick = {
                    uriHandler.openUriOrShowToast(SocialDestinations.telegram(), context)
                },
            )

            Text(
                text = stringResource(R.string.store_addresses),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
            AddressCard(stringResource(R.string.store_address_one))
            AddressCard(stringResource(R.string.store_address_two))

            Text(
                text = stringResource(R.string.open_source_licenses),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(AboutPreferencesAction.OpenLibraries) }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun HeroCard(appVersion: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ),
            )
            .padding(horizontal = 24.dp, vertical = 30.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NextIcons.Player,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
            }
            Text(
                text = stringResource(R.string.about_us),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.follow_us_social),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.player_version, appVersion),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SocialButton(
    title: String,
    handle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "@$handle",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = NextIcons.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddressCard(address: String) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = NextIcons.Location,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(14.dp))
            Text(
                text = address,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

internal fun UriHandler.openUriOrShowToast(uri: String, context: Context) {
    try {
        openUri(uri)
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.error_opening_link), Toast.LENGTH_SHORT).show()
    }
}

private object SocialDestinations {
    private const val KEY = 55

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

    fun firstInstagramHandle(): String = decode(90, 88, 85, 94, 91, 82, 25, 67, 94, 89, 86)
    fun secondInstagramHandle(): String = decode(90, 88, 85, 94, 91, 82, 25, 67, 94, 89, 86, 5)
    fun thirdInstagramHandle(): String = decode(90, 88, 85, 94, 91, 82, 25, 67, 94, 89, 86, 86)
    fun telegramHandle(): String = decode(97, 103, 121, 14, 1, 4)

    private fun decode(vararg encoded: Int): String = buildString(encoded.size) {
        encoded.forEach { append((it xor KEY).toChar()) }
    }
}
