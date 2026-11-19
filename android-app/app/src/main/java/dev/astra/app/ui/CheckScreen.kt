package dev.astra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.astra.app.data.RegistryApi
import dev.astra.app.data.Store
import dev.astra.app.data.Tailscale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Check { CHECKING, PASS, FAIL }

/**
 * A1 · First launch / connection check. Runs by itself; when both rows pass the app moves
 * on to A2 with no button press. A failing row becomes the recovery button:
 * Tailscale -> E2, Pi registry -> edit its address.
 */
@Composable
fun CheckScreen(store: Store, onPassed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ts by remember { mutableStateOf(Check.CHECKING) }
    var reg by remember { mutableStateOf(Check.CHECKING) }
    var registryUrl by remember { mutableStateOf("") }
    var showE2 by remember { mutableStateOf(false) }
    var editUrl by remember { mutableStateOf(false) }
    var runs by remember { mutableIntStateOf(0) }

    LaunchedEffect(runs) {
        ts = Check.CHECKING; reg = Check.CHECKING
        registryUrl = store.current().registryUrl
        delay(400) // let the user see the check happen
        ts = if (Tailscale.isConnected()) Check.PASS else Check.FAIL
        if (ts == Check.FAIL) { reg = Check.FAIL; showE2 = true; return@LaunchedEffect }
        reg = if (RegistryApi(registryUrl, null).health()) Check.PASS else Check.FAIL
        if (reg == Check.PASS) { delay(500); onPassed() }
    }
    // Coming back from the Tailscale app re-runs A1.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (showE2) { showE2 = false; runs++ } }

    if (showE2) {
        // E2 · Tailscale off
        MessageState(
            title = "Tailscale is not connected",
            body = "This app reaches your PCs through Tailscale. Nothing works while it is off.",
            primary = action("Open Tailscale app") { Tailscale.open(context) },
            secondary = action("Check again") { showE2 = false; runs++ },
        )
        return
    }

    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ASTRA", color = Muted, style = MaterialTheme.typography.labelLarge)
            Text("Remote desktop", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            CheckRow("Tailscale", ts, "Connected", "Not connected · tap to fix") { showE2 = true }
            CheckRow("Pi registry", reg, "Reachable", "Unreachable · tap to change address") { editUrl = true }
            Text("Checking happens automatically. No button to press when both pass.", color = Muted,
                style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onPassed, enabled = ts == Check.PASS && reg == Check.PASS,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Continue") }
        }
    }

    if (editUrl) {
        var text by remember { mutableStateOf(registryUrl) }
        AlertDialog(
            onDismissRequest = { editUrl = false },
            title = { Text("Pi registry address") },
            text = {
                Column {
                    Text("Use the Pi's Tailscale name, for example http://yoyopi:8000", color = Muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { store.setRegistryUrl(text); editUrl = false; runs++ }
                }) { Text("Save and check") }
            },
            dismissButton = { TextButton(onClick = { editUrl = false; runs++ }) { Text("Check again") } },
        )
    }
}

@Composable
private fun CheckRow(label: String, state: Check, okText: String, failText: String, onFix: () -> Unit) {
    val fail = state == Check.FAIL
    Row(
        Modifier.fillMaxWidth()
            .background(CardBg, RoundedCornerShape(12.dp))
            .border(1.dp, if (fail) Danger else Line, RoundedCornerShape(12.dp))
            .let { if (fail) it.clickable(onClick = onFix) else it }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            Check.CHECKING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else -> Box(Modifier.size(12.dp).background(if (fail) Danger else Ok, CircleShape))
        }
        Column {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                when (state) { Check.CHECKING -> "Checking…"; Check.PASS -> okText; Check.FAIL -> failText },
                color = if (fail) Danger else Muted,
            )
        }
    }
}
