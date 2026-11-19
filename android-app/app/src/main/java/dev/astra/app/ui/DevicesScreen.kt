package dev.astra.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.astra.app.data.PairedDevice
import dev.astra.app.data.RegistryApi
import dev.astra.app.data.RegistryDevice
import dev.astra.app.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A2 · Device List. Status always comes from the Pi registry: re-polled every 30 seconds
 * while the screen is visible and on pull-to-refresh. It never guesses a PC is online.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    store: Store,
    onConnect: (PairedDevice) -> Unit,
    onOffline: (PairedDevice, String?) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    val settings by store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Map<Int, RegistryDevice>>(emptyMap()) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableIntStateOf(0) }

    suspend fun refresh() {
        val s = store.current()
        try {
            status = RegistryApi(s.registryUrl, s.phoneToken).devices().associateBy { it.id }
            error = null
        } catch (e: Exception) {
            error = "Can't reach the Pi registry. Pull down to try again."
        }
    }

    LifecycleResumeEffect(Unit) {
        visible++
        onPauseOrDispose { visible = 0 }
    }
    LaunchedEffect(visible) {
        if (visible == 0) return@LaunchedEffect
        while (true) {
            refresh()
            delay(30_000)
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("My devices") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        },
        bottomBar = {
            if (settings?.paired?.isNotEmpty() == true) {
                OutlinedButton(
                    onClick = onAdd, border = BorderStroke(1.dp, Line),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(50.dp),
                ) { Icon(Icons.Filled.Add, null); Spacer(Modifier.size(8.dp)); Text("Add device") }
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; refresh(); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            val paired = settings?.paired.orEmpty()
            if (settings != null && paired.isEmpty()) {
                // A2-empty: teach the one next action instead of a blank list.
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 80.dp)) {
                            MessageState(
                                title = "No devices yet",
                                body = "Pair your first PC to see it here.\nTip: the PC shows a QR code in its Host Agent window.",
                                primary = "Add device" to onAdd,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    error?.let { msg -> item { Text(msg, color = Danger, modifier = Modifier.padding(top = 4.dp)) } }
                    items(paired, key = { it.id }) { d ->
                        val reg = status[d.id]
                        DeviceCard(d, reg,
                            onConnect = { if (reg?.online == true) onConnect(d) else onOffline(d, reg?.lastSeenAt) })
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: PairedDevice, reg: RegistryDevice?, onConnect: () -> Unit) {
    val online = reg?.online == true
    val name = reg?.name ?: d.name
    Column(
        Modifier.fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onConnect)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(if (online) Ok else Muted, CircleShape))
            Text(name, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            when {
                reg == null -> "Status unknown"
                online -> "Windows · Online now"
                else -> lastSeenText(reg.lastSeenAt)
            },
            color = Muted,
        )
        if (online) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        } else {
            // E1: offline PCs stay visible; tapping explains instead of connecting.
            OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Line)) {
                Text("Offline", color = Muted)
            }
        }
    }
}

/** E1 · Device offline. One recovery tap (Refresh status); no Wake button in the MVP. */
@Composable
fun OfflineScreen(
    store: Store,
    device: Pair<PairedDevice, String?>?,
    onBack: () -> Unit,
    onOnline: (PairedDevice) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var lastSeen by remember { mutableStateOf(device?.second) }
    var note by remember { mutableStateOf<String?>(null) }
    val d = device?.first
    if (d == null) {
        MessageState("Device not found", "Go back to the device list.", primary = "Back" to onBack)
        return
    }
    MessageState(
        title = "${d.name} is offline",
        body = "${lastSeenText(lastSeen)}. The PC may be asleep, off, or its Host Agent stopped." +
            (note?.let { "\n\n$it" } ?: ""),
        primary = action("Refresh status") {
            scope.launch {
                val s = store.current()
                val reg = runCatching { RegistryApi(s.registryUrl, s.phoneToken).devices() }.getOrNull()
                val now = reg?.firstOrNull { it.id == d.id }
                when {
                    reg == null -> note = "Can't reach the Pi registry right now."
                    now?.online == true -> onOnline(d)
                    else -> { lastSeen = now?.lastSeenAt; note = "Still offline." }
                }
            }
        },
        secondary = "Back" to onBack,
    )
}
