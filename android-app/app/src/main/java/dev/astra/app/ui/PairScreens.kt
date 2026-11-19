package dev.astra.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.astra.app.data.ApiException
import dev.astra.app.data.HostApi
import dev.astra.app.data.PairTarget
import dev.astra.app.data.PairedDevice
import dev.astra.app.data.RegistryApi
import dev.astra.app.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** B1 · Add Device. Camera path by default; manual fallback for a broken camera. */
@Composable
fun AddDeviceScreen(onScan: () -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    AstraScreen("Add device", onBack = onBack) {
        Spacer(Modifier.height(24.dp))
        Text("On the PC, open the Host Agent. It shows a QR code and a PIN.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Adding another phone? On that phone open Astra, tap Share this phone and Start sharing. " +
            "Then choose Enter code manually here and type its name and PIN.", color = Muted)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Scan QR code") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Line)) {
            Text("Enter code manually")
        }
        Spacer(Modifier.height(12.dp))
        Text("Manual path: type the PC or phone name and the PIN it shows.", color = Muted)
    }
}

/**
 * B2 · Scan QR. On-device ML Kit, so no image leaves the phone. A good scan moves on by
 * itself; a QR from anything else says so and stays here.
 */
@Composable
fun ScanScreen(onFound: (PairTarget) -> Unit, onTypeInstead: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var badCodeAt by remember { mutableLongStateOf(0L) }
    var done by remember { mutableStateOf(false) }

    AstraScreen("Scan QR", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(CardBg)
                .border(1.dp, Line, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                QrCamera { raw ->
                    if (done) return@QrCamera
                    val target = PairTarget.fromQr(raw)
                    if (target != null) { done = true; onFound(target) } else badCodeAt = System.currentTimeMillis()
                }
            } else {
                Text(if (asked) "Camera permission is off." else "Waiting for camera permission…", color = Muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        val bad = System.currentTimeMillis() - badCodeAt < 3000
        Text(
            if (bad) "That code is not a PC from this app" else "Point at the QR code on the PC",
            color = if (bad) Danger else Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        if (bad) LaunchedEffect(badCodeAt) { delay(3000); badCodeAt = 0L }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onTypeInstead, modifier = Modifier.fillMaxWidth().height(50.dp), border = BorderStroke(1.dp, Line)) {
            Text("No camera? Type instead")
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
internal fun QrCamera(onRaw: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { ctx ->
        val view = PreviewView(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(input)
                    .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(onRaw) }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        view
    })
}

private sealed interface Phase {
    data object Entering : Phase
    data object Waiting : Phase
    data class Success(val device: PairedDevice) : Phase
    data class WrongPin(val attempt: Int) : Phase       // E3
    data object Locked : Phase                           // E3 after 3 tries
    data class NotApproved(val timeout: Boolean) : Phase // E4
}

/**
 * B3 · Enter PIN and B4 · Pairing result, one screen so the user never loses their place.
 *   1. Pi: POST /api/pairings  (gets this phone's token the first time)
 *   2. PC: POST /api/pair      (PIN check, then Allow / Deny on P2, up to 60 s)
 */
@Composable
fun PairScreen(
    store: Store,
    initialTarget: PairTarget?,
    onBack: () -> Unit,
    onScanInstead: () -> Unit,
    onDone: () -> Unit,
    onConnectNow: (PairedDevice) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Entering) }
    var target by remember { mutableStateOf(initialTarget) }
    var pcName by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    val name = target?.name ?: pcName.ifBlank { "the PC" }

    fun pair() = scope.launch {
        error = null
        phase = Phase.Waiting
        val s = store.current()
        try {
            val t = target ?: run {
                val wanted = pcName.trim()
                RegistryApi(s.registryUrl, s.phoneToken).devices().firstOrNull {
                    it.name.equals(wanted, ignoreCase = true) ||
                        (!it.relay && it.hostname.substringBefore('.').equals(wanted, ignoreCase = true))
                }?.let { PairTarget.fromRegistry(it) }
            }
            if (t == null) {
                error = "Nothing called \"${pcName.trim()}\" is listed on the Pi. Check the name under its QR code, or on the phone's Share screen."
                phase = Phase.Entering
                return@launch
            }
            target = t
            val (pairingId, newToken) = RegistryApi(s.registryUrl, s.phoneToken).startPairing(t.deviceId, s.phoneName, pin)
            if (newToken != null) store.setToken(newToken)
            HostApi.pair(t, pairingId, pin, s.phoneName)
            val device = t.toPaired()
            store.savePaired(device)
            phase = Phase.Success(device)
        } catch (e: ApiException) {
            pin = ""
            phase = when (e.code) {
                "wrong_pin" -> Phase.WrongPin(3 - (e.body?.optInt("attempts_left", 0) ?: 0))
                "locked" -> { lockedUntil = System.currentTimeMillis() + 5 * 60_000; Phase.Locked }
                "not_approved" -> Phase.NotApproved(e.body?.optString("reason") == "timeout")
                else -> {
                    error = when (e.code) {
                        "unreachable" -> "Could not reach $name. Check that its Host Agent is running."
                        "unknown_device" -> "That code is not a PC from this app."
                        "pairing_expired" -> "The pairing request expired. Try again."
                        "host_offline" -> "$name is not sharing right now. On it, open Astra and tap Start sharing."
                        "busy" -> "$name is busy with another prompt or session. Try again in a moment."
                        else -> e.detail.ifBlank { "Pairing failed (${e.code})." }
                    }
                    Phase.Entering
                }
            }
        }
    }

    when (val p = phase) {
        Phase.Waiting -> MessageState(
            title = "Waiting for approval",
            body = "Tap Allow on $name. This waits up to 60 seconds.",
            top = { CircularProgressIndicator(Modifier.size(40.dp)) },
        )
        is Phase.Success -> MessageState(
            title = "Paired ✓",
            body = "${p.device.name} is now in your list.",
            primary = action("Connect now") { onConnectNow(p.device) },
            secondary = "Done" to onDone,
        )
        is Phase.WrongPin -> MessageState(
            title = "That PIN did not match",
            body = "Attempt ${p.attempt} of 3. Check the PIN on the PC. It changes every 10 minutes.",
            primary = action("Try again") { phase = Phase.Entering },
            secondary = "Scan QR instead" to onScanInstead,
        )
        Phase.Locked -> MessageState(
            title = "Too many wrong PINs",
            body = "The PC made a new PIN and PIN entry is locked for 5 minutes.",
            primary = action("Try again") { phase = Phase.Entering },
            secondary = "Scan QR instead" to onScanInstead,
        )
        is Phase.NotApproved -> MessageState(
            title = "The PC did not approve",
            body = if (p.timeout) "Nobody answered on the PC within 60 seconds." else "Deny was tapped on the PC, or the popup was closed.",
            primary = action("Try again") { phase = Phase.Entering },
            secondary = "Back" to onDone,
        )
        Phase.Entering -> AstraScreen("Enter PIN", onBack = onBack) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(lockedUntil) { while (now < lockedUntil) { delay(1000); now = System.currentTimeMillis() } }
            val lockedFor = ((lockedUntil - now) / 1000).coerceAtLeast(0)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(12.dp))
                if (target != null) {
                    Text("Pairing with ${target!!.name}", style = MaterialTheme.typography.titleLarge)
                } else {
                    OutlinedTextField(pcName, { pcName = it }, label = { Text("PC or phone name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                Text("Enter the 6-digit PIN shown on the PC or on the other phone. It changes every 10 minutes.", color = Muted)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    placeholder = { Text("••••••") },
                    textStyle = TextStyle(fontSize = 28.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Danger) }
                if (lockedFor > 0) Text("PIN entry unlocks in ${lockedFor / 60}:${"%02d".format(lockedFor % 60)}", color = Danger)
                Button(
                    onClick = { pair() },
                    enabled = pin.length == 6 && lockedFor == 0L && (target != null || pcName.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                ) { Text("Pair") }
            }
        }
    }
}
