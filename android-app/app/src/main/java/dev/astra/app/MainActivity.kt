package dev.astra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.astra.app.data.PairTarget
import dev.astra.app.data.PairedDevice
import dev.astra.app.data.Store
import dev.astra.app.ui.AddDeviceScreen
import dev.astra.app.ui.AstraTheme
import dev.astra.app.ui.CheckScreen
import dev.astra.app.ui.DevicesScreen
import dev.astra.app.ui.OfflineScreen
import dev.astra.app.ui.PairScreen
import dev.astra.app.ui.ScanScreen
import dev.astra.app.ui.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Screen IDs follow the wireframes:
 *   A1 check -> A2 devices -> A3 settings
 *   B1 add -> B2 scan -> B3 PIN -> B4 result (B3/B4 share one screen)
 *   C1-C4 run in ViewerActivity; E1 offline, E2 Tailscale off, E3 wrong PIN, E4 not approved, E5 lost.
 */
object Routes {
    const val A1 = "a1"
    const val A2 = "a2"
    const val A3 = "a3"
    const val B1 = "b1"
    const val B2 = "b2"
    const val B3 = "b3"
    const val E1 = "e1"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        setContent {
            AstraTheme {
                val nav = rememberNavController()
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                // Small hand-offs between screens, kept in memory only.
                var pairTarget by remember { mutableStateOf<PairTarget?>(null) }
                var offlineDevice by remember { mutableStateOf<Pair<PairedDevice, String?>?>(null) }

                fun openViewer(d: PairedDevice) {
                    scope.launch {
                        val s = store.current()
                        val token = s.phoneToken ?: return@launch
                        context.startActivity(
                            ViewerActivity.intent(context, d.baseUrl, d.name, token, s.phoneName,
                                s.mouseMode.param, s.quality.param)
                        )
                    }
                }

                NavHost(nav, startDestination = Routes.A1) {
                    composable(Routes.A1) {
                        CheckScreen(store, onPassed = {
                            nav.navigate(Routes.A2) { popUpTo(Routes.A1) { inclusive = true } }
                        })
                    }
                    composable(Routes.A2) {
                        DevicesScreen(
                            store = store,
                            onConnect = ::openViewer,
                            onOffline = { d, lastSeen -> offlineDevice = d to lastSeen; nav.navigate(Routes.E1) },
                            onAdd = { nav.navigate(Routes.B1) },
                            onSettings = { nav.navigate(Routes.A3) },
                        )
                    }
                    composable(Routes.A3) { SettingsScreen(store, onBack = { nav.popBackStack() }) }
                    composable(Routes.E1) {
                        OfflineScreen(store, offlineDevice, onBack = { nav.popBackStack() },
                            onOnline = { d -> nav.popBackStack(); openViewer(d) })
                    }
                    composable(Routes.B1) {
                        AddDeviceScreen(
                            onScan = { nav.navigate(Routes.B2) },
                            onManual = { pairTarget = null; nav.navigate(Routes.B3) },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(Routes.B2) {
                        ScanScreen(
                            onFound = { t ->
                                pairTarget = t
                                nav.navigate(Routes.B3) { popUpTo(Routes.B1) }
                            },
                            onTypeInstead = { pairTarget = null; nav.navigate(Routes.B3) { popUpTo(Routes.B1) } },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(Routes.B3) {
                        PairScreen(
                            store = store,
                            initialTarget = pairTarget,
                            onBack = { nav.popBackStack() },
                            onScanInstead = { nav.navigate(Routes.B2) { popUpTo(Routes.B1) } },
                            onDone = { nav.popBackStack(Routes.A2, inclusive = false) },
                            onConnectNow = { d ->
                                nav.popBackStack(Routes.A2, inclusive = false)
                                openViewer(d)
                            },
                        )
                    }
                }
            }
        }
    }
}
