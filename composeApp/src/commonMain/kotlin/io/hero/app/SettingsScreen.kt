package io.hero.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// SettingsScreen is the pre-login overlay wrapper (back bar + PrefsContent).
// Signed in, the same content renders as the dock's Settings section instead.
@Composable
fun SettingsScreen(
    settings: Settings,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onForget: () -> Unit,
    onClose: () -> Unit,
) {
    PredictiveBack(enabled = true) { onClose() }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.width(4.dp))
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        PrefsContent(settings, themeMode, onThemeMode, onForget)
    }
}

// PrefsContent is the system-settings body: appearance, saved login, app
// updates, about. All controls act on real state (Settings store, GitHub
// releases via checkForUpdate/installUpdate).
@Composable
fun PrefsContent(
    settings: Settings,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onForget: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsSection("Appearance") {
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Segmented(
                options = ThemeMode.entries.map { it.label },
                selectedIndex = ThemeMode.entries.indexOf(themeMode),
                onSelect = { onThemeMode(ThemeMode.entries[it]) },
            )
        }

        SettingsSection("Saved login") {
            val server = settings.getString(Keys.ServerUrl)
            val user = settings.getString(Keys.Username)
            val remembered = settings.getString(Keys.Remember) == "1"
            if (server.isNullOrBlank() && user.isNullOrBlank()) {
                Text("Nothing saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(buildString {
                    server?.let { append(it) }
                    user?.let { append("\n"); append(it) }
                    if (remembered) append("\nStays signed in on this device")
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onForget) { Text("Forget this device") }
            }
        }

        SettingsSection("Updates") { UpdateControls() }

        SettingsSection("About") {
            Text("HERO", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Harness Everything Routing Orchestrator", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("Version $AppVersion", style = MaterialTheme.typography.bodySmall)
            Text("github.com/$Repo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// SettingsSection caps the shared PanelSection at a readable width on desktop.
@Composable
private fun SettingsSection(title: String, body: @Composable ColumnScope.() -> Unit) =
    PanelSection(title, Modifier.fillMaxWidth().widthIn(max = 560.dp), body)

// Segmented is a minimal ink segmented control: a bordered row of equal cells,
// the selected one filled with the primary container.
@Composable
private fun Segmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(3.dp)) {
            options.forEachIndexed { i, label ->
                val sel = i == selectedIndex
                Surface(
                    color = if (sel) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f).padding(1.dp).selectable(selected = sel, onClick = { onSelect(i) }),
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// UpdateControls checks the project's PUBLIC release (no token) and installs.
// A failed check reads as a failure — never as "up to date" — and installs
// stream real download progress.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateControls() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Current: v$AppVersion") }
    var pending by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; status = "Checking…"
            scope.launch {
                runCatching { checkForUpdate(updateAssetSuffix()) }
                    .onSuccess { info ->
                        if (info == null) { status = "Up to date (v$AppVersion)"; pending = null }
                        else { status = "Update available: v${info.version}"; pending = info }
                    }
                    .onFailure { status = "Check failed: ${it.message ?: "network error"}"; pending = null }
                busy = false
            }
        }) { Text("Check for updates") }
        pending?.let { info ->
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    status = installUpdate(info) { received, total ->
                        status = if (total != null && total > 0) {
                            "Downloading v${info.version}… ${received * 100 / total}%"
                        } else {
                            "Downloading v${info.version}… ${received / 1024} kB"
                        }
                    }
                    busy = false
                }
            }) { Text("Install v${info.version}") }
        }
    }
    Spacer(Modifier.height(6.dp))
    // Selectable so the desktop "verified → <path>, run: java -jar …" instruction
    // can actually be copied; the running app is never auto-replaced there.
    SelectionContainer {
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
