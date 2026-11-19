package dev.astra.app.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.astra.app.MainActivity
import dev.astra.app.R
import dev.astra.app.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Share this phone": a foreground service that keeps the screen capture and the link to
 * the Pi alive while you use other apps. Android shows its own "sharing your screen"
 * indicator the whole time, and our notification has a Stop button.
 */
class ShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var link: HostLink? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> if (projection == null) begin(intent) else Unit
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        // Android 14+: the service must be in the foreground *before* it takes the projection.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val p = data?.let { runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }.getOrNull() }
        if (p == null) {
            HostRuntime.update { it.copy(linkError = "Android did not allow screen sharing. Try Start sharing again.") }
            shutdown()
            return
        }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { shutdown() } } // stopped from Android's own "Stop sharing"
        }, main)
        projection = p
        instance = this
        val cap = ScreenCapturer(this, p).also { it.start() }
        capturer = cap
        HostRuntime.pins = PinManager()
        HostRuntime.update { HostState(sharing = true, link = "connecting") }

        scope.launch {
            val store = Store(applicationContext)
            val s = store.current()
            val key = s.hostKey
            if (key.isNullOrBlank()) {
                HostRuntime.update { it.copy(link = "error", linkError = "Add the Pi's key first (step 1).") }
                main.post { shutdown() }
                return@launch
            }
            val l = HostLink(applicationContext, s.registryUrl, key, store.installId(), s.phoneName, s.autoAllowPaired, cap, scope)
            link = l
            HostRuntime.update { it.copy(name = s.phoneName) }
            l.run()
        }
    }

    private fun shutdown() {
        if (instance === this) instance = null
        link?.stop()
        link = null
        capturer?.stop()
        capturer = null
        runCatching { projection?.stop() }
        projection = null
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        HostRuntime.update { HostState(linkError = it.linkError?.takeIf { e -> e.startsWith("Android") || e.startsWith("Add") }) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null) shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun notifyStatus() {
        if (projection == null) return
        val st = HostRuntime.state.value
        val text = when {
            st.session != null -> "${st.session} is controlling this phone"
            st.link == "online" -> "Sharing · waiting for the other phone"
            else -> "Reconnecting to the Pi…"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sharing this phone", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_SHARE, true), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Astra is sharing this phone")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "astra_sharing"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "dev.astra.app.SHARE_START"
        private const val ACTION_STOP = "dev.astra.app.SHARE_STOP"
        @Volatile private var instance: ShareService? = null
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        /** Call with the result of MediaProjectionManager.createScreenCaptureIntent(). */
        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
        }

        fun stop(context: Context) {
            if (!HostRuntime.state.value.sharing) return
            context.startService(Intent(context, ShareService::class.java).setAction(ACTION_STOP))
        }

        /** Updates the notification text after the link or session changes. */
        @Suppress("UNUSED_PARAMETER")
        fun refresh(context: Context) {
            instance?.let { svc -> svc.main.post { svc.notifyStatus() } }
        }
    }
}
