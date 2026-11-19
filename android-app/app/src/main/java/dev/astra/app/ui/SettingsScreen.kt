package dev.astra.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.astra.app.data.MouseMode
import dev.astra.app.data.PairedDevice
import dev.astra.app.data.Quality
import dev.astra.app.data.RegistryApi
import dev.astra.app.data.Store
import dev.astra.app.data.Tailscale
import kotlinx.coroutines.launch

/** A3 · Settings. Five rows, no account section (Tailscale owns identity). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit) {
    val s by store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var toRemove by remember { mutableStateOf<PairedDevice?>(null) }
    var nameDraft by remember { mutableStateOf<String?>(null) }
    val settings = s ?: return
    LaunchedEffect(settings.phoneName) { if (nameDraft == null) nameDraft = settings.phoneName }

    AstraScreen("Settings", onBack = onBack) {
        // 1 Default mouse mode
        Section("Default mouse mode") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MouseMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = settings.mouseMode == m,
                        onClick = { scope.launch { store.setMouseMode(m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, MouseMode.entries.size),
                    ) { Text(m.label) }
                }
            }
        }
        // 2 Stream quality
        Section("Stream quality") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Quality.entries.forEachIndexed { i, q ->
                    SegmentedButton(
                        selected = settings.quality == q,
                        onClick = { scope.launch { store.setQuality(q) } },
                        shape = SegmentedButtonDefaults.itemShape(i, Quality.entries.size),
                    ) { Text(q.label.substringBefore(" (")) }
                }
            }
            Text("Low saves mobile data.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        // 3 Paired phone name (shown on P2)
        Section("Paired phone name") {
            OutlinedTextField(
                value = nameDraft.orEmpty(), onValueChange = { nameDraft = it.take(64) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Shown on the PC's approval popup") },
            )
            val changed = !nameDraft.isNullOrBlank() && nameDraft != settings.phoneName
            if (changed) {
                TextButton(onClick = {
                    val n = nameDraft!!.trim()
                    scope.launch {
                        store.setPhoneName(n)
                        settings.phoneToken?.let { t -> runCatching { RegistryApi(settings.registryUrl, t).renamePhone(n) } }
                    }
                }) { Text("Save name") }
            }
        }
        // 4 Tailscale status (read-only)
        Section("Tailscale status") {
            Text(if (Tailscale.isConnected()) "Connected" else "Not connected")
            Text("Managed in the Tailscale app", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        // 5 Remove a device: the only destructive action, and the only one with a confirmation
        Section("Remove a device") {
            if (settings.paired.isEmpty()) Text("No paired PCs.", color = Muted)
            settings.paired.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { toRemove = d }, border = BorderStroke(1.dp, Danger)) {
                        Text("Remove", color = Danger)
                    }
                }
            }
        }
        Text("Pi registry: ${settings.registryUrl}", color = Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 16.dp))
    }

    toRemove?.let { d ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remove ${d.name}?") },
            text = { Text("It will need pairing again.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // Revoke on the Pi too, so the old pairing can't open sessions any more.
                        settings.phoneToken?.let { t -> runCatching { RegistryApi(settings.registryUrl, t).removePairing(d.id) } }
                        store.removePaired(d.id)
                        toRemove = null
                    }
                }) { Text("Remove", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Muted)
        content()
    }
}
