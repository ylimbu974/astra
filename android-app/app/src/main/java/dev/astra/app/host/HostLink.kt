package dev.astra.app.host

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import dev.astra.app.data.ApiException
import dev.astra.app.data.HostRegistryApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This phone acting as a host, through the Pi (registry/relay.py):
 *  * keeps a control WebSocket open to the Pi (register, heartbeat every 30 s, reconnects);
 *  * answers pairing requests: PIN check, then Allow / Deny here, then tells the Pi;
 *  * for every viewer that connects, opens a session socket and speaks the same protocol as
 *    the Python host agent's /stream: hello -> (token or PIN) -> Allow -> JPEG frames out, input in.
 */
class HostLink(
    private val context: Context,
    registryUrl: String,
    private val hostKey: String,
    private val installId: String,
    private val name: String,
    private val autoAllowPaired: Boolean,
    private val capturer: ScreenCapturer,
    private val scope: CoroutineScope,
) {
    private val base = registryUrl.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val api = HostRegistryApi(base, hostKey)
    private val busy = AtomicBoolean(false)
    @Volatile private var stopped = false
    @Volatile private var control: WebSocket? = null
    @Volatile private var deviceId: Int? = null
    @Volatile private var backoff = 2_000L

    private fun request(path: String) = Request.Builder().url("$base$path").header("X-Host-Key", hostKey).build()

    /** Runs until [stop]. Reconnects to the Pi by itself after Wi-Fi or mobile data drops. */
    suspend fun run() {
        while (!stopped) {
            HostRuntime.update { it.copy(link = "connecting") }
            val closed = CompletableDeferred<String?>()
            val ws = client.newWebSocket(request("/relay/host"), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "register").put("name", name).put("install_id", installId)
                        .put("os", "Android ${Build.VERSION.RELEASE}").toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) = onControl(webSocket, text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(null) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(if (response?.code == 403) "bad_key" else (t.message ?: "network error"))
                }
            })
            control = ws
            val heartbeat = scope.launch {
                while (isActive) { delay(30_000); ws.send("""{"type":"heartbeat"}""") }
            }
            val why = closed.await()
            heartbeat.cancel()
            control = null
            if (stopped) break
            HostRuntime.update {
                it.copy(link = "error", linkError = when (why) {
                    "bad_key" -> "The Pi did not accept this phone's key. Scan the key QR from the Pi again."
                    else -> "Can't reach the Pi at $base. Retrying…"
                })
            }
            ShareService.refresh(context)
            delay(if (why == "bad_key") 30_000 else backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    fun stop() {
        stopped = true
        HostRuntime.endSession?.invoke()
        control?.let { it.send("""{"type":"offline"}"""); it.close(1000, "stop") }
    }

    private fun onControl(ws: WebSocket, text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "registered" -> {
                deviceId = msg.getInt("device_id")
                backoff = 2_000L
                HostRuntime.update { it.copy(link = "online", linkError = null, deviceId = deviceId) }
                ShareService.refresh(context)
            }
            "session" -> {
                val sid = msg.optString("sid")
                if (sid.isNotEmpty()) scope.launch { runSession(sid) }
            }
            "http" -> {
                val rid = msg.optString("rid")
                val body = msg.optJSONObject("body") ?: JSONObject()
                scope.launch {
                    val (status, out) = if (msg.optString("path") == "/api/pair") handlePair(body)
                    else 404 to JSONObject().put("error", "not_found").put("detail", "Not found.")
                    ws.send(JSONObject().put("type", "http_result").put("rid", rid).put("status", status).put("body", out).toString())
                }
            }
        }
    }

    private fun error(status: Int, code: String, detail: String) =
        status to JSONObject().put("error", code).put("detail", detail)

    /** Flow B on this phone: B3 PIN check -> P2 Allow -> the Pi approves the pairing. */
    private suspend fun handlePair(body: JSONObject): Pair<Int, JSONObject> {
        val pairingId = body.optInt("pairing_id", -1)
        val pin = body.optString("pin")
        val phoneName = body.optString("phone_name").trim().take(64)
        if (pairingId < 0 || !PIN.matches(pin) || phoneName.isEmpty()) {
            return error(422, "invalid_request", "Send pairing_id, a 6-digit pin and phone_name.")
        }
        val dev = deviceId ?: return error(503, "registry_unreachable", "This phone has not reached the Pi yet.")
        val r = HostRuntime.pins.check(pin)
        if (!r.ok) {
            api.audit(dev, "pin_failed", "pairing=$pairingId ${r.error}")
            return if (r.error == "locked") {
                error(423, "locked", "Too many wrong PINs. The phone made a new PIN; try again in 5 minutes.")
                    .also { it.second.put("retry_after", r.retryAfter) }
            } else {
                error(401, "wrong_pin", "That PIN did not match.").also { it.second.put("attempts_left", r.attemptsLeft) }
            }
        }
        if (!busy.compareAndSet(false, true)) return error(409, "busy", "The phone is already showing a prompt or running a session.")
        try {
            val decision = Approvals.ask(context, Approvals.create("pairing", phoneName))
            if (decision != Approvals.ALLOW) {
                runCatching { api.rejectPairing(pairingId, dev, decision) }
                return 403 to JSONObject().put("error", "not_approved").put("detail", "The phone did not approve.").put("reason", decision)
            }
            try {
                api.approvePairing(pairingId, dev, pin)
            } catch (e: ApiException) {
                return error(if (e.status > 0) e.status else 502, e.code, e.detail)
            }
            return 200 to JSONObject().put("status", "approved").put("device_id", dev).put("name", name)
        } finally {
            busy.set(false)
        }
    }

    /** Flow C on this phone: one viewer, from hello to the end of the session. */
    private suspend fun runSession(sid: String) {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Boolean>()
        val sock = client.newWebSocket(request("/relay/host/session/$sid"), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(true) }
            override fun onMessage(webSocket: WebSocket, text: String) { incoming.trySend(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); incoming.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { opened.complete(false); incoming.close() }
        })
        fun send(o: JSONObject) = sock.send(o.toString())
        fun fail(code: String, detail: String, extra: JSONObject.() -> Unit = {}) {
            send(JSONObject().put("type", "error").put("error", code).put("detail", detail).apply(extra))
            sock.close(4000, null)
        }

        if (withTimeoutOrNull(15_000) { opened.await() } != true) { sock.cancel(); return }
        val hello = withTimeoutOrNull(10_000) { incoming.receiveCatching().getOrNull() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (hello == null || hello.optString("type") != "hello") return fail("bad_hello", "The viewer did not say hello.")
        val dev = deviceId ?: return fail("registry_unreachable", "This phone cannot reach the Pi registry right now.")
        if (!busy.compareAndSet(false, true)) return fail("busy", "This phone is already in a session or showing a prompt.")

        var sessionId: Int? = null
        var endReason = "ended"
        var bytes = 0L
        var live = false
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).let {
            @Suppress("DEPRECATION")
            it.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "astra:session")
        }
        try {
            var quality = hello.optString("quality").takeIf { it in QUALITIES } ?: "auto"
            val token = hello.optString("token").takeIf { it.isNotEmpty() }
            val pin = hello.optString("pin").takeIf { it.isNotEmpty() }
            val label: String

            // 1. Who is this? A paired phone (token, checked by the Pi) or a browser (PIN shown here).
            if (token != null) {
                try {
                    val (id, who) = api.openSession(dev, token)
                    sessionId = id
                    label = who
                } catch (e: ApiException) {
                    return fail(if (e.code == "unreachable") "registry_unreachable" else e.code, e.detail)
                }
            } else if (pin != null) {
                val r = HostRuntime.pins.check(pin)
                if (!r.ok) {
                    api.audit(dev, "pin_failed", "session ${r.error}")
                    return fail(r.error ?: "wrong_pin",
                        if (r.error == "locked") "Too many wrong PINs. Try again in 5 minutes." else "That PIN did not match.") {
                        put("attempts_left", r.attemptsLeft); put("retry_after", r.retryAfter)
                    }
                }
                label = hello.optString("phone_name").ifBlank { "Browser" }.take(64) + " (PIN)"
                sessionId = runCatching { api.openSession(dev, null, label).first }.getOrNull()
            } else {
                return fail("auth_required", "Enter the PIN shown on the phone.")
            }

            // 2. Allow / Deny on this phone, unless the owner chose to let paired phones straight in.
            val decision = if (token != null && autoAllowPaired) {
                api.audit(dev, "auto_allowed", "session for $label")
                Approvals.ALLOW
            } else {
                send(JSONObject().put("type", "waiting_approval").put("timeout", 60).put("name", name))
                val prompt = Approvals.create("session", label)
                val watcher = scope.launch {
                    for (m in incoming) if (runCatching { JSONObject(m).optString("type") }.getOrNull() == "cancel") break
                    Approvals.answer(prompt.id, Approvals.CANCELLED) // the viewer cancelled or went away
                }
                Approvals.ask(context, prompt).also { watcher.cancel() }
            }
            if (decision != Approvals.ALLOW) {
                sessionId?.let { id ->
                    runCatching {
                        api.sessionEvent(id, "denied", decision)
                        api.closeSession(id, if (decision == Approvals.DENY) "denied" else decision, 0)
                    }
                }
                sessionId = null
                if (decision != Approvals.CANCELLED) {
                    send(JSONObject().put("type", "not_approved").put("reason", decision))
                    sock.close(4003, null)
                }
                return
            }

            // 3. Live: frames out, input in.
            sessionId?.let { runCatching { api.sessionEvent(it, "approved") } }
            live = true
            val ended = CompletableDeferred<String>()
            HostRuntime.endSession = { ended.complete("ended_by_pc") }
            HostRuntime.update { it.copy(session = label) }
            ShareService.refresh(context)
            wake.acquire(4 * 60 * 60_000L)
            send(JSONObject().put("type", "approved").put("name", name).put("platform", "android")
                .put("screen", JSONObject().put("width", capturer.width).put("height", capturer.height)))
            if (!ControlService.isEnabled(context)) send(JSONObject().put("type", "warn").put("detail", CONTROL_OFF))

            val sender = scope.launch {
                var lastNumber = -1L
                var lastSent = 0L
                while (isActive) {
                    val t0 = SystemClock.elapsedRealtime()
                    val n = capturer.frameNumber
                    // Skip unchanged pictures, but resend one every 2 s so the viewer knows we're alive.
                    if ((n != lastNumber || t0 - lastSent >= 2_000) && sock.queueSize() < MAX_QUEUE) {
                        capturer.jpeg(quality)?.let { jpg ->
                            if (sock.send(jpg.toByteString())) { bytes += jpg.size; lastNumber = n; lastSent = t0 }
                        }
                    }
                    delay((FRAME_MS - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(10))
                }
            }
            val receiver = scope.launch {
                for (text in incoming) {
                    val ev = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    when (ev.optString("type")) {
                        "bye" -> { ended.complete("ended_by_phone"); return@launch }
                        "ping" -> send(JSONObject().put("type", "pong").put("t", ev.opt("t")))
                        "quality" -> ev.optString("value").takeIf { it in QUALITIES }?.let { quality = it }
                        else -> {
                            val c = ControlService.instance
                            val problem = if (c == null) CONTROL_OFF else withContext(Dispatchers.Main) { c.handle(ev) }
                            if (problem != null) send(JSONObject().put("type", "warn").put("detail", problem))
                        }
                    }
                }
                ended.complete("connection_lost")
            }
            endReason = ended.await()
            sender.cancel()
            receiver.cancel()
            if (endReason == "ended_by_pc") send(JSONObject().put("type", "ended").put("reason", "ended_by_pc"))
            sock.close(1000, null)
        } finally {
            busy.set(false)
            if (wake.isHeld) wake.release()
            if (live) {
                HostRuntime.endSession = null
                HostRuntime.update { it.copy(session = null) }
                ShareService.refresh(context)
            }
            sessionId?.let { id -> runCatching { api.closeSession(id, endReason, bytes) } }
        }
    }

    companion object {
        private val PIN = Regex("^\\d{6}$")
        private val QUALITIES = setOf("auto", "high", "low")
        private const val FRAME_MS = 100L          // up to 10 pictures a second
        private const val MAX_QUEUE = 1_500_000L   // skip pictures while the network is behind
        private const val CONTROL_OFF = "Control is off on that phone, so you can only watch. " +
            "On it: Settings > Accessibility > Astra control > On."
    }
}
