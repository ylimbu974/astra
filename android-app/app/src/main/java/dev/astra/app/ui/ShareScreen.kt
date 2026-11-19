package dev.astra.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.astra.app.data.HostSetup
import dev.astra.app.data.Store
import dev.astra.app.host.Approvals
import dev.astra.app.host.ControlService
import dev.astra.app.host.HostRuntime
import dev.astra.app.host.ShareService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * S1 · Share this phone. Three one-time steps, then one big Start / Stop button.
 * While sharing it shows this phone's name and PIN, which the other phone types to pair.
 */
@Composable
fun ShareScreen(store: Store, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsState(initial = null)
    val host by HostRuntime.state.collectAsState()
    var controlOn by remember { mutableStateOf(ControlService.isEnabled(context)) }
    var notifyOn by remember { mutableStateOf(notificationsAllowed(context)) }
    var scanning by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    var keyNote by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        controlOn = ControlService.isEnabled(context)
        notifyOn = notificationsAllowed(context)
        onPauseOrDispose { }
    }

    var cameraOn by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraOn = it }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifyOn = it }
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) ShareService.start(context, res.resultCode, data)
        else keyNote = "Screen sharing was not allowed, so nothing was shared."
    }
    val s = settings ?: return

    if (scanning) {
        AstraScreen("Scan the Pi's key", onBack = { scanning = false }) {
            Spacer(Modifier.height(8.dp))
            LaunchedEffect(Unit) { if (!cameraOn) cameraLauncher.launch(Manifest.permission.CAMERA) }
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(CardBg),
                contentAlignment = Alignment.Center) {
                if (!cameraOn) Text("Waiting for camera permission…", color = Muted)
                else QrCamera { raw ->
                    val setup = HostSetup.fromQr(raw) ?: return@QrCamera
                    scope.launch {
                        store.setHostKey(setup.key)
                        setup.registryUrl?.let { store.setRegistryUrl(it) }
                        keyNote = null
                        scanning = false
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("On the Pi, run:  bash ~/astra/show-phone-key.sh\nthen point this phone at the QR code it shows.", color = Muted)
        }
        return
    }

    AstraScreen("Share this phone", onBack = onBack) {
        Text("Let another phone with Astra see and control this one, through your Pi.", color = Muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))

        // ---- live status ----
        if (host.sharing) {
            SharingCard(host.link, host.linkError, host.name.ifBlank { s.phoneName }, host.session, host.prompt)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { ShareService.stop(context) },
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Stop sharing") }
            Spacer(Modifier.height(24.dp))
            return@AstraScreen
        }

        // ---- step 1: the Pi's key ----
        val hasKey = !s.hostKey.isNullOrBlank()
        Step(1, "Connect to the Pi", hasKey) {
            if (hasKey) {
                Text("Key saved. Pi: ${s.registryUrl}", color = Muted)
                TextButton(onClick = { scope.launch { store.setHostKey(null) } }) { Text("Remove key") }
            } else {
                Text("This phone needs the Pi's key once. On the Pi run  bash ~/astra/show-phone-key.sh  and scan its QR code.",
                    color = Muted)
                Button(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) { Text("Scan key QR") }
                OutlinedTextField(keyDraft, { keyDraft = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Or paste HOST_API_KEY") })
                if (keyDraft.length >= 16) {
                    TextButton(onClick = { scope.launch { store.setHostKey(keyDraft); keyDraft = "" } }) { Text("Save key") }
                }
            }
        }

        // ---- step 2: accessibility (tap and type) ----
        Step(2, "Allow control", controlOn) {
            if (controlOn) {
                Text("Astra control is on. The other phone can tap, swipe and type.", color = Muted)
            } else {
                Text("Open Accessibility, find Astra control (it may be under Installed apps or Downloaded apps), " +
                    "turn it on and tap Allow. Without it, the other phone can only watch.", color = Muted)
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth()) { Text("Open Accessibility settings") }
                Text("Is the switch greyed out (\"Restricted setting\")? Tap below, then ⋮ (top right) > " +
                    "Allow restricted settings, and try again.", color = Muted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Line)) { Text("Open Astra's App info") }
            }
        }

        // ---- step 3: notifications (the Allow / Deny prompt) ----
        Step(3, "Show Allow requests", notifyOn) {
            if (notifyOn) {
                Text("You'll get a pop-up with Allow and Deny when the other phone asks.", color = Muted)
            } else {
                Text("Astra asks before anyone sees this phone, with a pop-up notification.", color = Muted)
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow notifications") }
            }
        }

        // ---- option ----
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().background(CardBg, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Let paired phones in without asking")
                    Text("Off: tap Allow every time. On: only for phones you already paired with the PIN.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.autoAllowPaired, onCheckedChange = { on -> scope.launch { store.setAutoAllow(on) } })
            }
        }

        (keyNote ?: host.linkError)?.let { Text(it, color = Danger, modifier = Modifier.padding(top = 12.dp)) }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                keyNote = null
                val mpm = context.getSystemService(MediaProjectionManager::class.java)
                captureLauncher.launch(mpm.createScreenCaptureIntent())
            },
            enabled = hasKey,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Start sharing", fontSize = 18.sp) }
        Text("Android then asks what to share: choose \"Entire screen\" (or \"A single app\" > change to entire screen).",
            color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
    }
}

@Composable
private fun SharingCard(link: String, linkError: String?, name: String, session: String?, prompt: Approvals.Prompt?) {
    var pin by remember { mutableStateOf(HostRuntime.pins.snapshot()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { pin = HostRuntime.pins.snapshot(); delay(1000); tick++ }

    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth().background(CardBg, RoundedCornerShape(14.dp))
            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val color = when { session != null -> Danger; link == "online" -> Ok; else -> Muted }
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Text(
                when {
                    session != null -> "$session is controlling this phone"
                    link == "online" -> "Sharing · ready"
                    link == "error" -> "Not connected to the Pi"
                    else -> "Connecting to the Pi…"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (link != "online" && linkError != null) Text(linkError, color = Danger)

        if (prompt != null) {
            Column(Modifier.fillMaxWidth().background(Bg, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (prompt.kind == "pairing") "Pair with ${prompt.who}?" else "${prompt.who} wants to control this phone",
                    style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { Approvals.answer(prompt.id, Approvals.DENY) }, modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Line)) { Text("Deny") }
                    Button(onClick = { Approvals.answer(prompt.id, Approvals.ALLOW) }, modifier = Modifier.weight(1f)) { Text("Allow") }
                }
            }
        }

        if (session != null) {
            OutlinedButton(onClick = { HostRuntime.endSession?.invoke() }, modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Danger)) { Text("End session", color = Danger) }
        } else {
            Text("On the other phone: Astra > Add device > Enter code manually. Type this name and PIN:", color = Muted)
            Text(name, style = MaterialTheme.typography.titleLarge)
            Text(pin.pin.chunked(3).joinToString(" "), fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pin.lockedFor > 0) "Locked for ${pin.lockedFor / 60}:${"%02d".format(pin.lockedFor % 60)} after 3 wrong PINs"
                    else "New PIN in ${pin.secondsLeft / 60}:${"%02d".format(pin.secondsLeft % 60)}",
                    color = if (pin.lockedFor > 0) Danger else Muted, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { HostRuntime.pins.rotate(); pin = HostRuntime.pins.snapshot() }) { Text("New PIN") }
            }
            Text("You can leave Astra now. Sharing keeps going until you tap Stop sharing here or in the notification.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(12.dp))
            .border(1.dp, if (done) Line else Accent, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(26.dp).background(if (done) Ok else Accent, CircleShape), contentAlignment = Alignment.Center) {
                Text(if (done) "✓" else "$n", fontWeight = FontWeight.Bold)
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}

private fun notificationsAllowed(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
