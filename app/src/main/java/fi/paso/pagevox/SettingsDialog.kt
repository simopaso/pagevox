package fi.paso.pagevox

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val PRIVACY_POLICY_URL = "https://paso.fi/pagevox-privacy.html"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentHomeUrl: String,
    forceDarkWeb: Boolean,
    onToggleForceDarkWeb: (Boolean) -> Unit,
    selectedVoice: String,
    onSelectVoice: (String) -> Unit,
    onShowLicenses: () -> Unit,
    onClearSiteData: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(currentHomeUrl) { mutableStateOf(currentHomeUrl) }

    // Enumerate installed voices via a short-lived TTS engine, shut down on
    // dismiss. We list only locally-available voices, sorted by language.
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                voices = try {
                    engine?.voices
                        ?.filter {
                            !it.isNetworkConnectionRequired &&
                                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                        }
                        ?.sortedWith(compareBy({ it.locale.displayName }, { it.name }))
                        ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
        onDispose { engine?.stop(); engine?.shutdown() }
    }

    var voiceMenuOpen by remember { mutableStateOf(false) }
    val currentVoiceLabel = when {
        selectedVoice.isBlank() -> stringResource(R.string.settings_voice_system_default)
        else -> voices.firstOrNull { it.name == selectedVoice }?.locale?.displayName ?: selectedVoice
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_home_url))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true
                )
                Text(
                    stringResource(R.string.settings_home_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleForceDarkWeb(!forceDarkWeb) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_dark_web))
                        Text(
                            stringResource(R.string.settings_dark_web_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = forceDarkWeb, onCheckedChange = onToggleForceDarkWeb)
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.settings_voice))
                Text(
                    stringResource(R.string.settings_voice_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExposedDropdownMenuBox(
                    expanded = voiceMenuOpen,
                    onExpandedChange = { voiceMenuOpen = !voiceMenuOpen }
                ) {
                    OutlinedTextField(
                        value = currentVoiceLabel,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenuOpen) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = voiceMenuOpen,
                        onDismissRequest = { voiceMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_voice_system_default)) },
                            onClick = { onSelectVoice(""); voiceMenuOpen = false },
                            trailingIcon = {
                                if (selectedVoice.isBlank()) {
                                    Icon(Icons.Default.Check, stringResource(R.string.state_selected))
                                }
                            }
                        )
                        voices.forEach { v ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(v.locale.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            v.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                onClick = { onSelectVoice(v.name); voiceMenuOpen = false },
                                trailingIcon = {
                                    if (v.name == selectedVoice) {
                                        Icon(Icons.Default.Check, stringResource(R.string.state_selected))
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClearSiteData() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.NoAccounts,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.settings_clear_site_data))
                        Text(
                            stringResource(R.string.settings_clear_site_data_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowLicenses() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_licenses))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                            }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PrivacyTip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_privacy))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

// Third-party components bundled in the app, grouped by license. Update this
// list when dependencies in build.gradle.kts change.
private val APACHE_LIBRARIES = listOf(
    "Android Jetpack Compose (UI, Material 3, Material Icons)",
    "AndroidX Core KTX",
    "AndroidX Activity Compose",
    "AndroidX Lifecycle",
    "AndroidX DataStore Preferences",
    "AndroidX WebKit",
    "AndroidX Media3 (ExoPlayer, Session, UI)",
    "Kotlin Standard Library",
    "Guava (via Media3)"
)

/** Full-screen list of the open-source software bundled in the app, with the
 *  full text of each license. The Apache text is loaded from assets. */
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apacheText = remember {
        runCatching {
            context.assets.open("licenses/apache-2.0.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("See https://www.apache.org/licenses/LICENSE-2.0")
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.close))
                    }
                    Text(
                        stringResource(R.string.settings_licenses),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        stringResource(R.string.licenses_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(20.dp))

                    Text(
                        stringResource(R.string.licenses_apache_heading),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    APACHE_LIBRARIES.forEach {
                        Text("•  $it", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        apacheText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}
