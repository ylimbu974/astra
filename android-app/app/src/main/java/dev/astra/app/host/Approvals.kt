package dev.astra.app.host

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.astra.app.MainActivity
import dev.astra.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Allow / Deny prompt (P2) on a phone that shares its screen. It's a pop-up
 * notification with two buttons, so it shows on top of whatever app is open.
 * It also appears on the Share screen if Astra itself is open.
 */
object Approvals {
    const val CHANNEL = "astra_approvals"
    const val ALLOW = "allow"
    const val DENY = "deny"
    const val TIMEOUT = "timeout"
    const val CANCELLED = "cancelled"

    private val ids = AtomicInteger(2000)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    class Prompt(val id: Int, val kind: String, val who: String, internal val answer: CompletableDeferred<String>)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Allow requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks before another phone sees or controls this one"
            })
        }
    }

    fun create(kind: String, who: String): Prompt {
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<String>()
        pending[id] = d
        return Prompt(id, kind, who, d)
    }

    /** Shows the prompt and waits up to [timeoutSec]. Returns ALLOW, DENY, TIMEOUT or CANCELLED. */
    suspend fun ask(context: Context, prompt: Prompt, timeoutSec: Int = 60): String {
        ensureChannel(context)
        HostRuntime.update { it.copy(prompt = prompt) }
        val title = if (prompt.kind == "pairing") "Pair with ${prompt.who}?" else "${prompt.who} wants to control this phone"
        val text = if (prompt.kind == "pairing") "It will be able to ask to see and control this phone."
        else "They will see your screen and can tap and type."
        val open = PendingIntent.getActivity(context, prompt.id, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_SHARE, true), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setTimeoutAfter(timeoutSec * 1000L)
            .setContentIntent(open)
            .addAction(0, "Deny", answerIntent(context, prompt.id, DENY))
            .addAction(0, "Allow", answerIntent(context, prompt.id, ALLOW))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(prompt.id, n) } // no permission = Share screen only
        return try {
            withTimeoutOrNull(timeoutSec * 1000L) { prompt.answer.await() } ?: TIMEOUT
        } finally {
            pending.remove(prompt.id)
            NotificationManagerCompat.from(context).cancel(prompt.id)
            HostRuntime.update { if (it.prompt?.id == prompt.id) it.copy(prompt = null) else it }
        }
    }

    fun answer(id: Int, answer: String) {
        pending[id]?.complete(answer)
    }

    private fun answerIntent(context: Context, id: Int, answer: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id * 2 + if (answer == ALLOW) 1 else 0,
            Intent(context, ApprovalReceiver::class.java).putExtra("id", id).putExtra("answer", answer),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val answer = intent.getStringExtra("answer")
        if (answer == Approvals.ALLOW || answer == Approvals.DENY) Approvals.answer(intent.getIntExtra("id", -1), answer)
    }
}
